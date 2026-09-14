package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.QuestionSkillMapper;
import com.tiku.mapper.SkillGroupMapMapper;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识点标注的两级映射（学习路径引擎阶段 0，设计见 docs/learning-path-design.md §4）。
 *
 * 用真 DB（profile=test：内存 H2 + Flyway 迁移，含 V17）+ **本机假 AI 端点**，不连公网、不用真实 Key。
 * 锁住的设计要点：
 * 1. 先按作者 topic 分组、再一次调用映射整批分组（省 token）；
 * 2. **同类题标签必须完全一致**（逐题判定最容易出现"同类题标签不一致"，那是最危险的漏刷来源）；
 * 3. 分组映射落缓存：第二次运行不再调 AI；
 * 4. AI 建议是"建议"（source=ai/confirmed=0），确认后才参与门控；模型编造的 nodeId 被丢弃；
 * 5. 没有 topic 的题走逐题判定（可选），用户手动标注覆盖 AI 与作者。
 */
@SpringBootTest
@ActiveProfiles("test")
class QuestionTaggingServiceTest {

    private static final Long BANK_ID = 990001L;
    private static final String TEMPLATE = "official.civil-service";

    @Autowired
    private QuestionTaggingService tagging;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private QuestionSkillMapper questionSkillMapper;
    @Autowired
    private SkillGroupMapMapper groupMapMapper;
    @Autowired
    private SkillGraphService graphService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private HttpServer server;
    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM question_skill");
        jdbc.update("DELETE FROM skill_group_map");
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);
        prompts.clear();

        // 假 AI：按请求里出现的分组名返回对应映射；含 "id=" 的请求走逐题判定
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(body);
            String reply = body.contains("id=") ? perQuestionReply(body) : groupReply(body);
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":" + jsonString(reply) + "}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        server.start();

        // 写一份临时 ai-config.json（本地地址 → 不需要 Key）
        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "tiku-test-data");
        Files.createDirectories(dataDir);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        Files.writeString(dataDir.resolve("ai-config.json"),
                "{\"baseUrl\":\"" + base + "\",\"model\":\"fake-model\",\"thinking\":false}",
                StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 分组映射的假回复：图形推理→图形推理节点；资料分析→两个资料分析节点；外加一个编造的节点 */
    private String groupReply(String body) {
        StringBuilder sb = new StringBuilder("{\"mappings\":[");
        List<String> parts = new ArrayList<>();
        if (body.contains("图形推理")) {
            parts.add("{\"group\":\"图形推理\",\"nodes\":[{\"nodeId\":\"gk.pd.figure\",\"confidence\":0.92},"
                    + "{\"nodeId\":\"gk.fake.node\",\"confidence\":0.99}]}");
        }
        if (body.contains("资料分析")) {
            parts.add("{\"group\":\"资料分析\",\"nodes\":[{\"nodeId\":\"gk.zl.concept\",\"confidence\":0.88},"
                    + "{\"nodeId\":\"gk.zl.calc\",\"confidence\":0.7}]}");
        }
        return sb.append(String.join(",", parts)).append("]}").toString();
    }

    /** 逐题判定的假回复：把所有题都判成逻辑判断 */
    private String perQuestionReply(String body) {
        StringBuilder sb = new StringBuilder("{\"mappings\":[");
        List<String> parts = new ArrayList<>();
        // 请求体是 JSON，提示里的换行是转义后的 \n —— 直接正则抓全部 id=（不能按行 split）
        var m = java.util.regex.Pattern.compile("id=(\\d+)").matcher(body);
        while (m.find()) {
            parts.add("{\"id\":" + m.group(1) + ",\"nodes\":[{\"nodeId\":\"gk.pd.logic\",\"confidence\":0.75}]}");
        }
        return sb.append(String.join(",", parts)).append("]}").toString();
    }

    private static String jsonString(String raw) {
        try {
            return new ObjectMapper().writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void twoLevelMappingIsConsistentCachedAndConfirmable() {
        Long graph = insertQuestion("图形推理", "下面哪个图形符合规律？");
        Long graph2 = insertQuestion("图形推理", "选出与其余不同的图形。");
        Long data = insertQuestion("资料分析", "2024 年增长率是多少？");
        Long data2 = insertQuestion("资料分析", "比重与去年相比变化多少？");
        Long noTopic = insertQuestion(null, "甲乙丙三人的年龄之和为 60，求甲的年龄。");
        Long noTopic2 = insertQuestion(null, "某商品先涨价 10% 后降价 10%，最终价格如何变化？");

        // ① 第一次：两组各一次调用（省 token），且模型编造的 gk.fake.node 被丢弃
        var r1 = tagging.suggest(BANK_ID, TEMPLATE, false, 10);
        assertEquals(1, r1.aiCalls(), "两个分组应只花 1 次调用，实际 " + r1.aiCalls());
        assertEquals(2, r1.groupCount());
        assertEquals(2, r1.mappedGroups());
        assertEquals(4, r1.taggedQuestions(), "有 topic 的 4 道题应全部标注");
        assertEquals(2, r1.untaggedQuestions(), "没有 topic 的 2 道题此时不处理");

        var tagsGraph = tagging.tagsOf(graph);
        var tagsGraph2 = tagging.tagsOf(graph2);
        assertEquals(1, tagsGraph.size(), "编造的节点必须被丢弃，只剩真实节点：" + tagsGraph);
        assertEquals("gk.pd.figure", tagsGraph.get(0).nodeId());
        assertEquals(tagsGraph.stream().map(QuestionTaggingService.QuestionTag::nodeId).toList(),
                tagsGraph2.stream().map(QuestionTaggingService.QuestionTag::nodeId).toList(),
                "同类题（同 topic）标签必须完全一致");
        assertEquals("ai", tagsGraph.get(0).source());
        assertEquals("topic-map", tagsGraph.get(0).origin(), "来源应标记为分组映射");
        assertFalse(tagsGraph.get(0).confirmed(), "AI 结果默认未确认");

        // ② 第二次：命中分组缓存 → 不再调 AI
        var r2 = tagging.suggest(BANK_ID, TEMPLATE, false, 10);
        assertEquals(0, r2.aiCalls(), "分组映射应命中缓存，不再调 AI");
        assertEquals(2, r2.cachedGroups());
        assertEquals(4, r2.taggedQuestions());

        // ③ 待确认队列：按节点聚合 + 带样例题干
        var pending = tagging.pending(BANK_ID, TEMPLATE);
        var figure = pending.stream().filter(p -> p.nodeId().equals("gk.pd.figure")).findFirst().orElseThrow();
        assertEquals(2, figure.questionCount());
        assertEquals(2, figure.samples().size(), "要带样例题干，用户才能核对而不是盲点确认");

        // ④ 确认：确认后参与门控（confirmed=1）
        assertEquals(2, tagging.apply(BANK_ID, TEMPLATE, "confirm", "gk.pd.figure", null, null));
        assertTrue(tagging.tagsOf(graph).get(0).confirmed());

        // ⑤ 覆盖地图：4 题有标签（确认的是图形推理那 2 题）；每个节点只有 2 题 → 证据不足（<3）不能门控
        var coverage = tagging.coverage(BANK_ID, TEMPLATE);
        assertEquals(6, coverage.totalQuestions());
        assertEquals(4, coverage.coveredQuestions());
        assertEquals(2, coverage.untaggedQuestions());
        var figureCoverage = coverage.nodes().stream().filter(n -> n.nodeId().equals("gk.pd.figure")).findFirst().orElseThrow();
        assertEquals(2, figureCoverage.questionCount());
        assertFalse(figureCoverage.evidenceEnough(), "题量 <3 时不能算证据充足");
        assertEquals(2, coverage.confirmedQuestions(), "已确认题数按题去重（图形推理那 2 题）");

        // ⑥ 逐题判定（可选）：没有 topic 的 2 道题补上标签
        var r3 = tagging.suggest(BANK_ID, TEMPLATE, true, 10);
        assertEquals(1, r3.aiCalls(), "只有逐题判定需要一次调用（分组仍走缓存）");
        var tagsNoTopic = tagging.tagsOf(noTopic);
        assertEquals("gk.pd.logic", tagsNoTopic.get(0).nodeId(), "两道无 topic 的题都要打上标签");
        assertEquals("ai-direct", tagsNoTopic.get(0).origin(), "逐题判定来源应为 ai-direct");
        assertEquals("gk.pd.logic", tagging.tagsOf(noTopic2).get(0).nodeId());

        // ⑥b 关键设计：**低置信 AI 标签只作参考，不算"已覆盖"**（门控只认已确认或高置信）
        assertEquals(0.75, tagsNoTopic.get(0).confidence(), 0.001);
        assertFalse(tagsNoTopic.get(0).confirmed());
        assertEquals(2, r3.untaggedQuestions(), "置信度 0.75 < 0.8，仍如实算作未覆盖（不假装掌握了）");
        assertEquals(4, tagging.coverage(BANK_ID, TEMPLATE).coveredQuestions());

        // ⑦ 用户手动标注：覆盖 AI，且该题的 AI 建议被清掉
        assertEquals(1, tagging.setUserTags(noTopic, TEMPLATE, List.of("gk.sl.math")));
        var userTags = tagging.tagsOf(noTopic);
        assertEquals(1, userTags.size());
        assertEquals("gk.sl.math", userTags.get(0).nodeId());
        assertEquals("user", userTags.get(0).source());
        assertTrue(userTags.get(0).confirmed(), "用户标注视为已确认");

        // ⑧ 用户标注不会被后续 AI 分析覆盖
        tagging.suggest(BANK_ID, TEMPLATE, true, 10);
        assertEquals("gk.sl.math", tagging.tagsOf(noTopic).get(0).nodeId(), "重跑 AI 分析不能覆盖用户标注");

        // ⑨ 非法节点不接受
        assertThrows(IllegalArgumentException.class,
                () -> tagging.setUserTags(noTopic, TEMPLATE, List.of("gk.not.exist")));
    }

    @Test
    void suggestOnEmptyBankIsSafe() {
        var r = tagging.suggest(424242L, TEMPLATE, true, 5);
        assertEquals(0, r.taggedQuestions());
        assertEquals(0, r.aiCalls(), "空题库不应调用模型");
    }

    /** 未配置 AI 时要给出可照做的提示，而不是空跑 */
    @Test
    void unconfiguredAiFailsWithReadableMessage() throws Exception {
        Path cfg = Path.of(System.getProperty("java.io.tmpdir"), "tiku-test-data", "ai-config.json");
        String backup = Files.readString(cfg, StandardCharsets.UTF_8);
        try {
            Files.writeString(cfg, "{\"baseUrl\":\"\",\"model\":\"\"}", StandardCharsets.UTF_8);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> tagging.suggest(BANK_ID, TEMPLATE, false, 5));
            assertTrue(e.getMessage().contains("尚未配置 AI"), e.getMessage());
        } finally {
            Files.writeString(cfg, backup, StandardCharsets.UTF_8);
        }
    }

    private Long insertQuestion(String topic, String content) {
        Question q = new Question();
        q.setExternalId("TAG_" + System.nanoTime());
        q.setBankId(BANK_ID);
        q.setVolume(0);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent(content);
        q.setTopic(topic);
        q.setScore(1.0);
        q.setAnswerKeys("A");
        questionMapper.insert(q);
        return q.getId();
    }

}
