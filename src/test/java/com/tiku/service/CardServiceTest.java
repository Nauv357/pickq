package com.tiku.service;

import com.sun.net.httpserver.HttpServer;
import com.tiku.mapper.CardMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.Card;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.StudyRecord;
import com.tiku.model.enums.QuestionType;
import com.tiku.mapper.QuestionBankMapper;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闪卡（阶段 3，设计 §7.5）：从解析挖空生成、人工确认后才排期、复习调度、以及"卡片只作抽测证据"。
 *
 * 用真 DB（profile=test，含 V20）+ 本机假模型端点。
 */
@SpringBootTest
@ActiveProfiles("test")
class CardServiceTest {

    private static final Long BANK_ID = 990401L;
    private static final String TEMPLATE = "official.civil-service";

    @Autowired
    private CardService cards;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private StudyRecordMapper studyRecordMapper;
    @Autowired
    private CardMapper cardMapper;
    @Autowired
    private QuestionBankMapper bankMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer server;
    private final List<String> prompts = Collections.synchronizedList(new java.util.ArrayList<>());
    /** 假模型返回的卡（默认给两道题的卡，第二道故意用了不属于本批次的 questionId 以验证过滤） */
    private String reply = "";

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM card");
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_bank WHERE id = ?", BANK_ID);
        prompts.clear();
        QuestionBank bank = new QuestionBank();
        bank.setId(BANK_ID);
        bank.setName("闪卡测试题库");
        bank.setReviewEnabled(false);
        bank.setCreatedAt(LocalDateTime.now());
        bank.setUpdatedAt(LocalDateTime.now());
        bankMapper.insert(bank);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(body);
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
        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "tiku-test-data");
        Files.createDirectories(dataDir);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        Files.writeString(dataDir.resolve("ai-config.json"),
                "{\"baseUrl\":\"" + base + "\",\"model\":\"fake-model\",\"thinking\":false}", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String jsonString(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int seq = 0;

    private Long question(String analysis) {
        Question q = new Question();
        q.setQuestionNumber(++seq);
        q.setExternalId("CARD_" + System.nanoTime() + "_" + seq);
        q.setBankId(BANK_ID);
        q.setVolume(0);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent("求 2024 年的增长率。");
        q.setAnswerKeys("A");
        q.setScore(1.0);
        q.setAnalysis(analysis);
        questionMapper.insert(q);
        jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                        + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, 'gk.zl.growth', ?, 'user', 1.0, 1, 'manual', 0, NOW())",
                q.getId(), BANK_ID, TEMPLATE);
        return q.getId();
    }

    private void attempt(Long questionId, boolean correct, int daysAgo) {
        StudyRecord r = new StudyRecord();
        r.setBankId(BANK_ID);
        r.setQuestionId(questionId);
        r.setQuestionKey("CARD_KEY_" + questionId + "_" + System.nanoTime());
        r.setCorrect(correct);
        r.setAnsweredAt(LocalDateTime.now().minusDays(daysAgo));
        studyRecordMapper.insert(r);
    }

    @Test
    void generatesFromAnalysisSkipsQuestionsWithoutAnalysisAndRejectsForeignIds() {
        Long q1 = question("增长率 = 现期 / 基期 − 1。本题 120/100 − 1 = 20%。");
        Long q2 = question("短解析"); // 太短 → 不出卡
        reply = "{\"cards\":[{\"questionId\":" + q1 + ",\"front\":\"增长率 = 现期 / ____ − 1\",\"back\":\"基期\"},"
                + "{\"questionId\":999999,\"front\":\"不属于本批次的题\",\"back\":\"x\"}]}";

        CardService.GenerateResult result = cards.generate(BANK_ID, TEMPLATE, null, 5);
        assertEquals(1, result.generated(), "只应生成 1 张（模型编造的题号被丢弃）");
        assertEquals(1, result.skippedNoAnalysis(), "解析太短的题要如实计入跳过原因");

        List<CardService.CardView> list = cards.list(BANK_ID, "all", TEMPLATE);
        assertEquals(1, list.size());
        assertEquals(q1, list.get(0).questionId(), "卡片必须能溯源到原题");
        assertFalse(list.get(0).confirmed(), "AI 生成的卡默认未确认");
        assertNotNull(list.get(0).nodeName(), "要带上知识点名（可读）");

        // 再跑一次：已有卡的题跳过，不再重复烧 token
        CardService.GenerateResult again = cards.generate(BANK_ID, TEMPLATE, null, 5);
        assertEquals(0, again.generated());
        assertEquals(1, again.skippedHasCards());
        assertEquals(0, again.aiCalls(), "全部题都有卡时不该调用模型");
        assertTrue(q2 != null);
    }

    @Test
    void unconfirmedCardsDoNotEnterTheQueueUntilConfirmed() {
        Long q1 = question("比重 = 部分 / 整体。百分点是比重的差值单位。");
        reply = "{\"cards\":[{\"questionId\":" + q1 + ",\"front\":\"比重 = 部分 / ____\",\"back\":\"整体\"}]}";
        cards.generate(BANK_ID, TEMPLATE, null, 5);
        Long cardId = cards.list(BANK_ID, "all", TEMPLATE).get(0).id();

        assertEquals(0, cards.due(BANK_ID, 10, TEMPLATE).size(), "未确认的卡不进复习队列");
        CardService.CardStats stats = cards.stats(BANK_ID);
        assertEquals(1, stats.total());
        assertEquals(1, stats.unconfirmed());

        assertEquals(1, cards.confirm(BANK_ID, List.of(cardId), true));
        assertEquals(1, cards.due(BANK_ID, 10, TEMPLATE).size(), "确认后立刻可复习");
        assertEquals(0, cards.stats(BANK_ID).unconfirmed());

        // 取消确认 → 移出队列
        cards.confirm(BANK_ID, List.of(cardId), false);
        assertEquals(0, cards.due(BANK_ID, 10, TEMPLATE).size());
    }

    @Test
    void reviewSchedulingFollowsTheSameLadderAsQuestions() {
        Long q1 = question("年均增长率 = (末/初)^(1/n) − 1。");
        reply = "{\"cards\":[{\"questionId\":" + q1 + ",\"front\":\"年均增长率 = (末/初)^(1/n) − ____\",\"back\":\"1\"}]}";
        cards.generate(BANK_ID, TEMPLATE, null, 5);
        Long cardId = cards.list(BANK_ID, "all", TEMPLATE).get(0).id();
        cards.confirm(BANK_ID, List.of(cardId), true);

        // 记得 → 升一级，间隔变长，移出到期队列
        CardService.CardView remembered = cards.review(BANK_ID, cardId, true);
        assertEquals(1, remembered.level());
        assertEquals(2, remembered.intervalDays(), "间隔 = min(2^level, 30)");
        assertTrue(remembered.dueAt().isAfter(LocalDateTime.now()));
        assertEquals(0, cards.due(BANK_ID, 10, TEMPLATE).size());

        // 忘了 → 归零、立刻重来、lapses+1
        CardService.CardView forgot = cards.review(BANK_ID, cardId, false);
        assertEquals(0, forgot.level());
        assertEquals(1, forgot.intervalDays());
        assertEquals(1, forgot.lapses());
        assertEquals(1, cards.due(BANK_ID, 10, TEMPLATE).size(), "忘了的卡立刻回到队列");
        assertEquals(Card.RESULT_FORGOT, forgot.lastResult());
    }

    @Test
    void manualEditCountsAsHumanConfirmation() {
        Long q1 = question("平均数 = 总量 / 个数：先求和再除以个数，注意区分算术平均与加权平均。");
        reply = "{\"cards\":[{\"questionId\":" + q1 + ",\"front\":\"平均数 = 总量 / ____\",\"back\":\"个数\"}]}";
        cards.generate(BANK_ID, TEMPLATE, null, 5);
        Long cardId = cards.list(BANK_ID, "all", TEMPLATE).get(0).id();

        CardService.CardView updated = cards.update(BANK_ID, cardId, "平均数 = 总量 ÷ ____", "个数");
        assertEquals(Card.SOURCE_USER, updated.source(), "手动改过的是人工内容");
        assertTrue(updated.confirmed(), "人工改过直接进队列");
        assertEquals(1, cards.due(BANK_ID, 10, TEMPLATE).size());

        assertEquals(1, cards.delete(BANK_ID, List.of(cardId)));
        assertEquals(0, cards.list(BANK_ID, "all", TEMPLATE).size());
    }

    @Test
    void unconfiguredAiFailsWithReadableMessage() throws Exception {
        Path cfg = Path.of(System.getProperty("java.io.tmpdir"), "tiku-test-data", "ai-config.json");
        String backup = Files.readString(cfg, StandardCharsets.UTF_8);
        try {
            Files.writeString(cfg, "{\"baseUrl\":\"\",\"model\":\"\"}", StandardCharsets.UTF_8);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> cards.generate(BANK_ID, TEMPLATE, null, 3));
            assertTrue(e.getMessage().contains("尚未配置 AI"), e.getMessage());
        } finally {
            Files.writeString(cfg, backup, StandardCharsets.UTF_8);
        }
    }

    /** 卡片自评要能被掌握度计算当成"负向证据"取到（时间 + 是否记得） */
    @Test
    void lastCardResultIsExposedForMasteryDemotion() {
        Long q1 = question("翻番 = 变为原来的 2 倍；翻 n 番 = 变为原来的 2^n 倍，别和「增长 2 倍」混淆。");
        reply = "{\"cards\":[{\"questionId\":" + q1 + ",\"front\":\"翻番 = 变为原来的 ____ 倍\",\"back\":\"2\"}]}";
        cards.generate(BANK_ID, TEMPLATE, null, 5);
        Long cardId = cards.list(BANK_ID, "all", TEMPLATE).get(0).id();
        cards.confirm(BANK_ID, List.of(cardId), true);
        cards.review(BANK_ID, cardId, false);

        Object[] last = cards.lastCardResultOf(q1);
        assertNotNull(last, "卡片复习结果要能被查到（供节点降级判定）");
        assertFalse(Boolean.TRUE.equals(last[1]), "自评是「忘了」");
        assertEquals(1, cards.lastCardResultsOfQuestions(List.of(q1)).size());
        attempt(q1, true, 1);
    }
}
