package com.tiku.service;

import com.sun.net.httpserver.HttpServer;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.mapper.TutorMessageMapper;
import com.tiku.mapper.TutorSessionMapper;
import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.StudyRecord;
import com.tiku.model.TutorMessage;
import com.tiku.model.TutorSession;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 私教（学习路径引擎阶段 1）：提示楼梯 / 答错即问 / 自由追问 / 整场复盘。
 *
 * 用真 DB（profile=test：内存 H2 + Flyway 含 V18）+ **本机假流式端点**，锁住设计要点：
 * 1. 提示分级：L1/L2/L3 各自落库（hint_level），界面据此知道下一次该给哪一级；
 * 2. 上下文按固定顺序（题干→选项→答案→解析→我的作答→错因→知识点→最近对话），且**不暴露掌握度数值**；
 * 3. 复盘统计是**公式算的**（成绩、按知识点的错题分布、每点历史正确率），模型只负责讲人话；
 * 4. 同一题 + 同一场练习的会话会被复用（不会每点一次提示就新建一条记录）；
 * 5. 未配置 AI 时给可照做的提示，而不是空跑。
 */
@SpringBootTest
@ActiveProfiles("test")
class TutorServiceTest {

    private static final Long BANK_ID = 990101L;
    private static final Long SESSION_ID = 990102L;
    private static final String TEMPLATE = "official.civil-service";

    @Autowired
    private TutorService tutor;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private StudyRecordMapper studyRecordMapper;
    @Autowired
    private TutorSessionMapper sessionMapper;
    @Autowired
    private TutorMessageMapper messageMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer server;
    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
    /** 假端点是否支持流式（false 时返回普通 JSON，用于验证"端点不支持流式 → 自动降级"） */
    private boolean supportsStream = true;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM tutor_message");
        jdbc.update("DELETE FROM tutor_session");
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);
        prompts.clear();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(body);
            boolean stream = supportsStream && body.contains("\"stream\":true");
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            byte[] out;
            if (stream) {
                // 分三段推，模拟打字机
                StringBuilder sb = new StringBuilder();
                for (String piece : new String[]{"先看", "这个方向", "：增长率 = 现期/基期 - 1"}) {
                    sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(piece).append("\"}}]}\n\n");
                }
                sb.append("data: [DONE]\n\n");
                out = sb.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                out = "{\"choices\":[{\"message\":{\"content\":\"这是完整回答（非流式）\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
            }
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

    private Long insertQuestion(int number, String content, String analysis) {
        Question q = new Question();
        q.setQuestionNumber(number);
        q.setExternalId("TUTOR_" + System.nanoTime() + "_" + number);
        q.setBankId(BANK_ID);
        q.setVolume(0);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent(content);
        q.setOptions(List.of(new OptionItem("A", "选项一"), new OptionItem("B", "选项二")));
        q.setAnswerKeys("A");
        q.setAnalysis(analysis);
        q.setScore(1.0);
        questionMapper.insert(q);
        return q.getId();
    }

    private void insertRecord(Long questionId, boolean correct, String selectedKeys, Long seconds) {
        StudyRecord r = new StudyRecord();
        r.setBankId(BANK_ID);
        r.setQuestionId(questionId);
        r.setQuestionKey("TUTOR_KEY_" + questionId);
        r.setSessionId(SESSION_ID);
        r.setCorrect(correct);
        r.setSelectedKeys(selectedKeys);
        r.setSeconds(seconds);
        r.setAnsweredAt(LocalDateTime.now());
        studyRecordMapper.insert(r);
    }

    private void tag(Long questionId, String nodeId) {
        jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                        + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, ?, ?, 'user', 1.0, 1, 'manual', 0, NOW())",
                questionId, BANK_ID, nodeId, TEMPLATE);
    }

    @Test
    void hintLadderIsGradedPersistedAndContextFollowsDesignOrder() {
        Long qid = insertQuestion(7, "2024 年该省 GDP 为 120 亿，2023 年为 100 亿，增长率是多少？", "增长率 = 现期/基期 - 1 = 20%");
        tag(qid, "gk.zl.growth");
        insertRecord(qid, false, "B", 45L);

        TutorSession session = tutor.openSession(BANK_ID, qid, SESSION_ID, TutorSession.KIND_PER_QUESTION,
                TutorSession.REASON_NO_KNOWLEDGE, "公式记混了");

        StringBuilder streamed = new StringBuilder();
        TutorMessage l1 = tutor.hint(session.getId(), 1, TEMPLATE, streamed::append);
        assertEquals(1, l1.getHintLevel());
        assertTrue(streamed.toString().contains("增长率"), "流式回调要把增量文本给出来：" + streamed);
        assertEquals(1, tutor.maxHintLevel(session.getId()), "界面据此知道下一次给第 2 级");

        // 第 2 级：提示里要把第 1 级当成"我之前的发言"带上，避免重复
        tutor.hint(session.getId(), 2, TEMPLATE, d -> {
        });
        assertEquals(2, tutor.maxHintLevel(session.getId()));
        String lastPrompt = prompts.get(prompts.size() - 1);
        assertTrue(lastPrompt.contains("第 1 级提示"), "第 2 级要看到第 1 级说过什么：" + lastPrompt.substring(0, Math.min(300, lastPrompt.length())));

        // 上下文固定顺序 + 关键信息都在
        String firstPrompt = prompts.get(0);
        for (String section : new String[]{"【题目】", "【选项】", "【正确答案】A", "【官方解析】",
                "【我的作答】B（错误）", "【我的错因】这个知识点不会", "公式记混了", "【这道题的知识点】增长类"}) {
            assertTrue(firstPrompt.contains(section), "提示上下文缺少「" + section + "」：" + section);
        }
        assertFalse(firstPrompt.contains("掌握度是"), "不要把数值化的掌握度喂给模型");
        assertTrue(prompts.get(0).contains("\"stream\":true"), "应该走流式请求");

        // 同一题 + 同一场练习：会话复用，不会每点一次提示就多一条会话
        TutorSession again = tutor.openSession(BANK_ID, qid, SESSION_ID, TutorSession.KIND_PER_QUESTION, null, null);
        assertEquals(session.getId(), again.getId());
        assertEquals(1, sessionMapper.selectCount(null));
    }

    /** 端点不支持流式（400）时自动降级为一次性返回，功能不能因此不可用 */
    @Test
    void fallsBackWhenEndpointDoesNotSupportStreaming() {
        supportsStream = false;
        Long qid = insertQuestion(8, "某商品先涨价 10% 再降价 10%，最终价格如何变化？", null);
        TutorSession session = tutor.openSession(BANK_ID, qid, null, TutorSession.KIND_PER_QUESTION, null, null);
        StringBuilder out = new StringBuilder();
        TutorMessage msg = tutor.hint(session.getId(), 1, TEMPLATE, out::append);
        assertTrue(out.toString().contains("非流式"), "降级后也要把内容回调出来：" + out);
        assertTrue(msg.getContent().contains("非流式"));
    }

    @Test
    void askCarriesReasonAndKeepsConversation() {
        Long qid = insertQuestion(9, "由此可以推出：", null);
        TutorSession session = tutor.openSession(BANK_ID, qid, SESSION_ID, TutorSession.KIND_PER_QUESTION,
                TutorSession.REASON_CARELESS, "看漏了'不正确'三个字");

        tutor.ask(session.getId(), "我怎么才能不再看错题？", TEMPLATE, d -> {
        });
        List<TutorService.MessageView> messages = tutor.messages(session.getId());
        assertEquals(2, messages.size(), "一问一答各一条（提示级别为空表示自由追问）");
        assertEquals(TutorMessage.ROLE_USER, messages.get(0).role());
        assertEquals("我怎么才能不再看错题？", messages.get(0).content());
        assertEquals(TutorMessage.ROLE_ASSISTANT, messages.get(1).role());
        assertEquals(null, messages.get(1).hintLevel(), "自由追问不属于任何提示级别");
        String prompt = prompts.get(prompts.size() - 1);
        assertTrue(prompt.contains("看错/蒙的"), "错因要翻译成人话进上下文：" + prompt.substring(0, Math.min(200, prompt.length())));
        assertTrue(prompt.contains("看漏了'不正确'三个字"));
    }

    @Test
    void reviewSummaryIsFormulaBasedAndDiagnosisIsStreamed() {
        Long growth = insertQuestion(1, "2024 年增长率是多少？", "略");
        Long ratio = insertQuestion(2, "2024 年比重变化多少？", "略");
        Long other = insertQuestion(3, "下列说法正确的是：", null);
        tag(growth, "gk.zl.growth");
        tag(ratio, "gk.zl.ratio");
        insertRecord(growth, false, "A", 30L);
        insertRecord(ratio, false, "B", 40L);
        insertRecord(other, true, "A", 20L);

        TutorService.ReviewSummary summary = tutor.reviewSummary(BANK_ID, SESSION_ID, TEMPLATE);
        assertEquals(3, summary.total());
        assertEquals(1, summary.correct());
        assertEquals(2, summary.wrong());
        assertEquals(2, summary.wrongItems().size());
        assertEquals(2, summary.byNode().size(), "两道错题分属两个知识点");
        assertEquals("增长类（增长率/增长量）", summary.byNode().stream()
                .filter(n -> n.nodeId().equals("gk.zl.growth")).findFirst().orElseThrow().name());

        TutorSession session = tutor.openSession(BANK_ID, null, SESSION_ID, TutorSession.KIND_POST_REVIEW, null, null);
        StringBuilder streamed = new StringBuilder();
        TutorMessage msg = tutor.diagnose(session.getId(), TEMPLATE, streamed::append);
        assertTrue(msg.getContent().contains("增长率"), "诊断内容要落库：" + msg.getContent());
        assertTrue(streamed.length() > 0);
        String prompt = prompts.get(prompts.size() - 1);
        for (String section : new String[]{"【本场练习】共 3 题，答对 1，答错 2", "【错题按知识点分布】", "【错题清单】",
                "只写上面清单里的题"}) {
            assertTrue(prompt.contains(section), "复盘 prompt 缺少「" + section + "」");
        }
    }

    @Test
    void unconfiguredAiFailsWithReadableMessage() throws Exception {
        Long qid = insertQuestion(4, "随便一道题", null);
        TutorSession session = tutor.openSession(BANK_ID, qid, null, TutorSession.KIND_PER_QUESTION, null, null);
        Path cfg = Path.of(System.getProperty("java.io.tmpdir"), "tiku-test-data", "ai-config.json");
        String backup = Files.readString(cfg, StandardCharsets.UTF_8);
        try {
            Files.writeString(cfg, "{\"baseUrl\":\"\",\"model\":\"\"}", StandardCharsets.UTF_8);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> tutor.hint(session.getId(), 1, TEMPLATE, d -> {
                    }));
            assertTrue(e.getMessage().contains("尚未配置 AI"), e.getMessage());
        } finally {
            Files.writeString(cfg, backup, StandardCharsets.UTF_8);
        }
    }

    /** 复盘会话没有提示楼梯（提示要有具体题目），要说清楚而不是抛 NPE */
    @Test
    void reviewSessionRejectsHint() {
        TutorSession session = tutor.openSession(BANK_ID, null, SESSION_ID, TutorSession.KIND_POST_REVIEW, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tutor.hint(session.getId(), 1, TEMPLATE, d -> {
                }));
        assertTrue(e.getMessage().contains("提示楼梯"), e.getMessage());
        assertEquals(0, messageMapper.selectCount(null), "失败的调用不应留下消息");
    }

    /**
     * 讲解（主线）：三段结构指令进 prompt、讲的是"你这次的错"、且**不占提示级别**。
     * 讲解与提示楼梯是两件事：楼梯是做题中分级取用，讲解是做完之后一次看明白。
     */
    @Test
    void explainGivesThreeSectionsWithoutConsumingHintLevel() {
        Long qid = insertQuestion(11, "2024 年该省 GDP 为 120 亿，2023 年为 100 亿，增长率是多少？", "增长率 = 现期/基期 - 1 = 20%");
        tag(qid, "gk.zl.growth");
        insertRecord(qid, false, "B", 45L);
        TutorSession session = tutor.openSession(BANK_ID, qid, SESSION_ID, TutorSession.KIND_PER_QUESTION,
                TutorSession.REASON_NO_KNOWLEDGE, null);

        StringBuilder streamed = new StringBuilder();
        TutorMessage msg = tutor.explain(session.getId(), TEMPLATE, null, streamed::append);

        assertTrue(streamed.length() > 0, "讲解要流式给出来");
        assertEquals(null, msg.getHintLevel(), "讲解不占提示级别");
        assertEquals(0, tutor.maxHintLevel(session.getId()), "看完讲解后提示楼梯仍然从第 1 级开始");

        String prompt = prompts.get(prompts.size() - 1);
        for (String section : new String[]{"【错在哪】", "【这类题怎么做】", "【下次防错】",
                "【我的作答】B（错误）", "【我的错因】这个知识点不会", "【官方解析】"}) {
            assertTrue(prompt.contains(section), "讲解 prompt 缺少「" + section + "」");
        }
        assertTrue(prompt.contains("不要照抄"), "要讲透而不是照抄官方解析");
        assertFalse(prompt.contains("我没有作答记录"), "有作答时不该走中性口吻");

        // 讲解落库后就是这场对话的历史：接着追问要能看到它（否则追问等于重新问一遍）
        tutor.ask(session.getId(), "基期到底是哪一个？", TEMPLATE, d -> {
        });
        String askPrompt = prompts.get(prompts.size() - 1);
        assertTrue(askPrompt.contains("【最近的对话】"), askPrompt.substring(0, Math.min(200, askPrompt.length())));
        assertTrue(askPrompt.contains("老师：先看这个方向"), "追问上下文要包含刚才的讲解：" + askPrompt.substring(0, Math.min(300, askPrompt.length())));
    }

    /**
     * 统一解析的两种口吻：**没作答的题**（题库列表里点「讲解」）讲"这道题怎么做"，
     * 不能写成"你错在哪"——靠"这道题有没有作答记录"自动判定，调用方也可以显式指定。
     */
    @Test
    void explainSwitchesToNeutralVoiceWhenThereIsNoAttempt() {
        Long qid = insertQuestion(12, "某商品先涨价 10% 再降价 10%，最终价格如何变化？", "先乘 1.1 再乘 0.9");

        // ① 没作答 → 自动走中性口吻
        TutorSession neutral = tutor.openSession(BANK_ID, qid, null, TutorSession.KIND_PER_QUESTION, null, null);
        tutor.explain(neutral.getId(), TEMPLATE, null, d -> {
        });
        String prompt = prompts.get(prompts.size() - 1);
        for (String section : new String[]{"【这道题怎么做】", "【这类题怎么做】", "【易错点】", "我没有作答记录"}) {
            assertTrue(prompt.contains(section), "中性讲解缺少「" + section + "」");
        }
        assertFalse(prompt.contains("【错在哪】"), "没作答就不该讲「错在哪」");
        assertFalse(prompt.contains("【我的作答】"), "没作答就没有作答段落");

        // ② 有作答但显式要求中性 → 听调用方的（"这题怎么讲"的场景）
        insertRecord(qid, false, "B", 30L);
        TutorSession forced = tutor.openSession(BANK_ID, qid, SESSION_ID, TutorSession.KIND_PER_QUESTION, null, null);
        tutor.explain(forced.getId(), TEMPLATE, TutorService.MODE_NEUTRAL, d -> {
        });
        String forcedPrompt = prompts.get(prompts.size() - 1);
        assertTrue(forcedPrompt.contains("【这道题怎么做】"), forcedPrompt.substring(0, Math.min(200, forcedPrompt.length())));
        assertFalse(forcedPrompt.contains("【错在哪】"), "显式指定中性口吻时不要讲错在哪");

        // ③ 有作答且不指定 → 自动回到"错在哪"
        TutorSession wrong = tutor.openSession(BANK_ID, qid, SESSION_ID, TutorSession.KIND_PER_QUESTION, null, null);
        tutor.explain(wrong.getId(), TEMPLATE, null, d -> {
        });
        assertTrue(prompts.get(prompts.size() - 1).contains("【错在哪】"), "有作答时应讲「你错在哪」");
    }

    /** 答对过的题不该再讲"你错在哪"：最近一次作答是对的 → 中性口吻 */
    @Test
    void explainUsesNeutralVoiceWhenTheLastAttemptWasCorrect() {
        Long qid = insertQuestion(13, "下列说法正确的是：", "官方解析略");
        insertRecord(qid, true, "A", 20L);

        TutorSession session = tutor.openSession(BANK_ID, qid, SESSION_ID, TutorSession.KIND_PER_QUESTION, null, null);
        tutor.explain(session.getId(), TEMPLATE, null, d -> {
        });
        String prompt = prompts.get(prompts.size() - 1);
        assertTrue(prompt.contains("【这道题怎么做】"), prompt.substring(0, Math.min(200, prompt.length())));
        assertFalse(prompt.contains("【错在哪】"), "答对了就不该讲「你错在哪」");
    }

    /** 讲解必须有具体题目：整场复盘会话上点「讲解」要说清楚，不能空跑 */
    @Test
    void explainRequiresAQuestion() {
        TutorSession session = tutor.openSession(BANK_ID, null, SESSION_ID, TutorSession.KIND_POST_REVIEW, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tutor.explain(session.getId(), TEMPLATE, null, d -> {
                }));
        assertTrue(e.getMessage().contains("指定题目"), e.getMessage());
        assertEquals(0, messageMapper.selectCount(null));
    }
}
