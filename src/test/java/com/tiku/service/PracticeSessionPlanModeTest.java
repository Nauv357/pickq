package com.tiku.service;

import com.tiku.dto.SessionCreateRequest;
import com.tiku.dto.SessionCreateResponse;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mode=PLAN：按配题结果开练（"开始练习"一键的落地方式）。
 *
 * 界面先把 /practice-plan 的结果展示给用户，再把这批 questionIds 原样传回来开练——
 * 所以这里必须保证：**练的题、题数、顺序**与用户刚看到的那批完全一致
 * （否则"这 20 题：8 题是你之前做错的"就成了假话）。
 */
@SpringBootTest
@ActiveProfiles("test")
class PracticeSessionPlanModeTest {

    private static final Long BANK_ID = 992001L;

    @Autowired
    private PracticeSessionService sessionService;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM practice_session_question WHERE session_id IN (SELECT id FROM practice_session WHERE bank_id = ?)", BANK_ID);
        jdbc.update("DELETE FROM practice_session WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_bank WHERE id = ?", BANK_ID);
        jdbc.update("INSERT INTO question_bank (id, name, created_at, updated_at) VALUES (?, 'PLAN 模式测试', NOW(), NOW())", BANK_ID);
    }

    private Long insertQuestion(int number) {
        Question q = new Question();
        q.setQuestionNumber(number);
        q.setExternalId("PLANMODE_" + System.nanoTime() + "_" + number);
        q.setBankId(BANK_ID);
        q.setVolume(0);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent("第 " + number + " 题");
        q.setOptions(List.of(new OptionItem("A", "选项一"), new OptionItem("B", "选项二")));
        q.setAnswerKeys("A");
        q.setScore(1.0);
        questionMapper.insert(q);
        return q.getId();
    }

    @Test
    void planModePractisesExactlyTheGivenQuestionsInTheGivenOrder() {
        Long q1 = insertQuestion(1);
        Long q2 = insertQuestion(2);
        Long q3 = insertQuestion(3);

        // 顺序 = 配题优先级（不是题号顺序），界面看到什么就练什么
        SessionCreateResponse resp = sessionService.createSession(BANK_ID,
                new SessionCreateRequest("PLAN", List.of(q3, q1), null, null, null, 20, null, null, null, null));

        assertEquals(2, resp.total());
        assertEquals(List.of(q3, q1), resp.questions().stream()
                .map(com.tiku.dto.QuestionPracticeResponse::questionId).toList());
        assertTrue(resp.questions().stream().noneMatch(q -> q.questionId().equals(q2)));
    }

    /** count 由配题引擎决定：PLAN 模式忽略它，避免"解释里 20 题、实际练 5 题" */
    @Test
    void planModeIgnoresCountSoTheExplanationStaysTrue() {
        Long q1 = insertQuestion(1);
        Long q2 = insertQuestion(2);
        SessionCreateResponse resp = sessionService.createSession(BANK_ID,
                new SessionCreateRequest("PLAN", List.of(q1, q2), null, null, null, 1, null, null, null, null));
        assertEquals(2, resp.total());
    }

    @Test
    void planModeDropsUnknownIdsAndDuplicates() {
        Long q1 = insertQuestion(1);
        SessionCreateResponse resp = sessionService.createSession(BANK_ID,
                new SessionCreateRequest("PLAN", java.util.Arrays.asList(q1, q1, 999999999L, null),
                        null, null, null, null, null, null, null, null));
        assertEquals(1, resp.total(), "重复/不存在的 id 不能变成 500 或幻觉题");
        assertEquals(q1, resp.questions().get(0).questionId());
    }

    /** 空 questionIds 是调用错误：说清楚怎么修，而不是创建一个 0 题会话 */
    @Test
    void planModeWithoutIdsFailsWithReadableMessage() {
        insertQuestion(1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sessionService.createSession(BANK_ID,
                        new SessionCreateRequest("PLAN", List.of(), null, null, null, 20, null, null, null, null)));
        assertTrue(e.getMessage().contains("practice-plan"), e.getMessage());
    }
}
