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
    /**
     * 逐题判定返回的置信度：默认 0.75（**低于**门控阈值 0.8，用于验证「低置信标签不算覆盖」）；
     * 续跑测试会调高到 0.9，才能用「未覆盖题数」衡量进度。
     */
    private double perQuestionConfidence = 0.75;

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
            parts.add("{\"group\":\"图形推理\",\"nodes\":[{\"nodeId\":\"gk.pd.figure.num\",\"confidence\":0.92},"
                    + "{\"nodeId\":\"gk.fake.node\",\"confidence\":0.99}]}");
        }
        if (body.contains("资料分析")) {
            parts.add("{\"group\":\"资料分析\",\"nodes\":[{\"nodeId\":\"gk.zl.growth\",\"confidence\":0.88},"
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
            parts.add("{\"id\":" + m.group(1) + ",\"nodes\":[{\"nodeId\":\"gk.pd.argue\",\"confidence\":"
                    + perQuestionConfidence + "}]}");
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
        assertEquals(2, r1.withoutUsableTag(), "没有 topic 的 2 道题此时不处理");

        var tagsGraph = tagging.tagsOf(graph);
        var tagsGraph2 = tagging.tagsOf(graph2);
        assertEquals(1, tagsGraph.size(), "编造的节点必须被丢弃，只剩真实节点：" + tagsGraph);
        assertEquals("gk.pd.figure.num", tagsGraph.get(0).nodeId());
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

        // ③ 审阅清单：以题为单位，带状态与当前标签（界面据此就地查看/修改）
        var pendingPage = tagging.review(BANK_ID, TEMPLATE, "pending", null, 1, 50);
        assertEquals(4, pendingPage.total(), "4 道有 topic 的题都有未确认建议");
        assertEquals(4, pendingPage.counts().pending());
        assertEquals(2, pendingPage.counts().untagged(), "没有 topic 的 2 题此时一条标签都没有");
        var row = pendingPage.records().stream().filter(r -> r.questionId().equals(graph)).findFirst().orElseThrow();
        assertEquals("pending", row.status());
        assertEquals(1, row.tags().size(), "编造的节点必须被丢弃，只剩真实节点");
        assertEquals("gk.pd.figure.num", row.tags().get(0).nodeId());
        assertEquals("图形推理·数量与属性", row.tags().get(0).name(), "清单里要带节点名，界面才能直接显示");
        assertTrue(row.preview() != null && !row.preview().isBlank(), "每道题要带题干预览，用户才能分辨是哪一题");
        assertTrue(row.questionNumber() != null, "要带题号，便于在列表里定位");

        // ④ 确认：确认后参与门控（confirmed=1）
        assertEquals(2, tagging.apply(BANK_ID, TEMPLATE, "confirm", "gk.pd.figure.num", null, null));
        assertTrue(tagging.tagsOf(graph).get(0).confirmed());

        // ⑤ 覆盖地图：4 题有标签（确认的是图形推理那 2 题）；每个节点只有 2 题 → 证据不足（<3）不能门控
        var coverage = tagging.coverage(BANK_ID, TEMPLATE);
        assertEquals(6, coverage.totalQuestions());
        assertEquals(4, coverage.usableQuestions(), "门控可用 = 已确认 2 + 高置信 AI 2");
        assertEquals(2, coverage.untaggedQuestions(), "无 topic 的 2 题一条标签都没有");
        assertEquals(2, coverage.pendingQuestions(), "资料分析那 2 题只有未确认建议");
        assertEquals(6,
                coverage.confirmedQuestions() + coverage.pendingQuestions() + coverage.untaggedQuestions(),
                "三段不重叠：已确认 + 待确认 + 未匹配 = 总题数（用户实测时两个数对不上就是口径不同造成的）");
        var figureCoverage = coverage.nodes().stream().filter(n -> n.nodeId().equals("gk.pd.figure.num")).findFirst().orElseThrow();
        assertEquals(2, figureCoverage.questionCount());
        assertFalse(figureCoverage.evidenceEnough(), "题量 <3 时不能算证据充足");
        assertEquals(2, coverage.confirmedQuestions(), "已确认题数按题去重（图形推理那 2 题）");

        // ⑥ 逐题判定（可选）：没有 topic 的 2 道题补上标签
        var r3 = tagging.suggest(BANK_ID, TEMPLATE, true, 10);
        assertEquals(1, r3.aiCalls(), "只有逐题判定需要一次调用（分组仍走缓存）");
        var tagsNoTopic = tagging.tagsOf(noTopic);
        assertEquals("gk.pd.argue", tagsNoTopic.get(0).nodeId(), "两道无 topic 的题都要打上标签");
        assertEquals("ai-direct", tagsNoTopic.get(0).origin(), "逐题判定来源应为 ai-direct");
        assertEquals("gk.pd.argue", tagging.tagsOf(noTopic2).get(0).nodeId());

        // ⑥b 关键设计：**低置信 AI 标签只作参考，不算"已覆盖"**（门控只认已确认或高置信）
        assertEquals(0.75, tagsNoTopic.get(0).confidence(), 0.001);
        assertFalse(tagsNoTopic.get(0).confirmed());
        assertEquals(2, r3.withoutUsableTag(), "置信度 0.75 < 0.8，仍如实算作未覆盖（不假装掌握了）");
        assertEquals(4, tagging.coverage(BANK_ID, TEMPLATE).usableQuestions());

        // ⑦ 用户手动标注：覆盖 AI，且该题的 AI 建议被清掉
        assertEquals(1, tagging.setUserTags(noTopic, TEMPLATE, List.of("gk.sl.calc")));
        var userTags = tagging.tagsOf(noTopic);
        assertEquals(1, userTags.size());
        assertEquals("gk.sl.calc", userTags.get(0).nodeId());
        assertEquals("user", userTags.get(0).source());
        assertTrue(userTags.get(0).confirmed(), "用户标注视为已确认");

        // ⑧ 用户标注不会被后续 AI 分析覆盖
        tagging.suggest(BANK_ID, TEMPLATE, true, 10);
        assertEquals("gk.sl.calc", tagging.tagsOf(noTopic).get(0).nodeId(), "重跑 AI 分析不能覆盖用户标注");

        // ⑨ 非法节点不接受
        assertThrows(IllegalArgumentException.class,
                () -> tagging.setUserTags(noTopic, TEMPLATE, List.of("gk.not.exist")));

        // ⑩ 逐题处理（用户实测：只给三条样例题干无法精确分配 → 现在按题列出并可就地改）
        var growthRows = tagging.review(BANK_ID, TEMPLATE, "all", "gk.zl.growth", 1, 50);
        assertEquals(2, growthRows.total(), "按知识点筛题：资料分析那 2 题");
        var first = growthRows.records().get(0);
        assertTrue(first.preview() != null && !first.preview().isBlank(), "每道题要带题干预览，用户才能分辨");
        assertTrue(first.questionNumber() != null, "要带题号，便于在列表里定位");

        assertEquals(1, tagging.apply(BANK_ID, TEMPLATE, "confirm", "gk.zl.growth", null, List.of(first.questionId())),
                "可只确认这一题");
        assertTrue(tagging.tagsOf(first.questionId()).stream()
                        .anyMatch(QuestionTaggingService.QuestionTag::confirmed), "被逐题确认的题应已确认");
        assertEquals(0, tagging.apply(BANK_ID, TEMPLATE, "reject", "gk.zl.growth", null, List.of(first.questionId())),
                "reject 只丢\"建议\"：已确认的标签不动");
        assertTrue(tagging.tagsOf(first.questionId()).stream()
                        .anyMatch(QuestionTaggingService.QuestionTag::confirmed), "已确认的标签要保留下来");

        // ⑪ 就地改标签（action=set）：把这些题的标签设定成给定节点，写成用户标注；
        //    反复设置不能累积出重复标签（界面上的下拉会被反复使用）
        assertEquals(1, tagging.apply(BANK_ID, TEMPLATE, "set", null, List.of("gk.sl.econ"), List.of(first.questionId())));
        assertEquals(1, tagging.apply(BANK_ID, TEMPLATE, "set", null, List.of("gk.sl.econ"), List.of(first.questionId())));
        var edited = tagging.tagsOf(first.questionId());
        assertEquals(1, edited.size(), "重复设置应替换而不是追加：" + edited);
        assertEquals("gk.sl.econ", edited.get(0).nodeId());
        assertEquals("user", edited.get(0).source());
        assertTrue(edited.get(0).confirmed());
        assertEquals(0, tagging.apply(BANK_ID, TEMPLATE, "set", null, List.of("gk.sl.econ"), null),
                "set 不给 questionIds 时不做任何事（危险动作必须显式给题）");
        // 清空标签 = newNodes 传空数组
        assertEquals(0, tagging.apply(BANK_ID, TEMPLATE, "set", null, List.of(), List.of(first.questionId())));
        assertTrue(tagging.tagsOf(first.questionId()).isEmpty(), "空数组表示把这题的标签清掉");

        // ⑫ 按状态列题：界面据此把"未匹配的题"摊开逐题补
        assertTrue(tagging.review(BANK_ID, TEMPLATE, "pending", null, 1, 50).total() >= 1, "待确认清单要能列题");
        assertTrue(tagging.review(BANK_ID, TEMPLATE, "confirmed", null, 1, 50).total() >= 1, "已确认清单要能列题");
        assertTrue(tagging.review(BANK_ID, TEMPLATE, "untagged", null, 1, 50).total() >= 1, "未匹配清单要能列题");
        var allPage = tagging.review(BANK_ID, TEMPLATE, "all", null, 1, 50);
        assertEquals(6, allPage.total(), "全部 = 总题数");
        assertEquals(allPage.counts().confirmed() + allPage.counts().pending() + allPage.counts().untagged(),
                allPage.counts().total(), "三段不重叠且相加 = 总题数");
    }

    @Test
    void suggestOnEmptyBankIsSafe() {
        var r = tagging.suggest(424242L, TEMPLATE, true, 5);
        assertEquals(0, r.taggedQuestions());
        assertEquals(0, r.aiCalls(), "空题库不应调用模型");
    }

    /**
     * 分批续跑：界面按"N 次调用一批"推进（172 题 ≈ 9 次调用，3000 题 ≈ 150 次，
     * 一次请求跑不完），因此第二次点击必须**跳过已处理的题**，否则每次从头重算、白烧 token。
     */
    @Test
    void suggestResumesWithoutReprocessingTaggedQuestions() {
        perQuestionConfidence = 0.9; // 高于门控阈值，才能用「未覆盖题数」衡量进度
        for (int i = 0; i < 45; i++) {
            insertQuestion(null, "无主题的第 " + i + " 道题：求某数的值。");
        }
        // 第一次：只允许 1 次调用（= 1 批 20 题）
        var r1 = tagging.suggest(BANK_ID, TEMPLATE, true, 1);
        assertEquals(1, r1.aiCalls());
        int leftAfterFirst = r1.withoutUsableTag();
        assertEquals(25, leftAfterFirst, "45 题打 20 题后应剩 25 题未覆盖");

        prompts.clear();
        // 第二次：再给 1 次调用额度 → 必须继续往前推进，而不是重算前 20 题
        var r2 = tagging.suggest(BANK_ID, TEMPLATE, true, 1);
        assertEquals(1, r2.aiCalls(), "续跑仍只花 1 次调用");
        assertEquals(5, r2.withoutUsableTag(), "第二批再处理 20 题 → 只剩 5 题");
        assertEquals(1, prompts.size(), "续跑只发一次请求（已标注的题不再重复问模型）");

        // 第三次：还剩 5 题 → 收尾
        var r3 = tagging.suggest(BANK_ID, TEMPLATE, true, 1);
        assertEquals(0, r3.withoutUsableTag(), "收尾后全部覆盖");
        assertFalse(r3.truncated(), "跑完不应再报「达到上限」");
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

    private int seqNo = 0;

    private Long insertQuestion(String topic, String content) {
        Question q = new Question();
        q.setQuestionNumber(++seqNo);
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
