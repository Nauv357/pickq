package com.tiku.service;

import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.StudyRecord;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这次作答算不算错 / 算不算对」**只允许有一套口径**。
 *
 * 为什么单独写这个测试：2026-09-16 的功能/模块审计发现同一条规则被实现了 5 份且互不相等——
 * - `StudyRecordService.isWrong`（权威）、`StatsService.isWrongDecide`（优先级相反）、
 *   `TutorService`（两份，一份漏 PARTIAL）、`AdaptiveService`（完全忽略自评）；
 * - 「未判定」（未配答案 / 主观未自评）在成绩报告里是"不判对错"，但在会话详情/会话列表里被算成**答错**。
 *
 * 于是同一道题会出现"错题本里是错的、统计里是对的、会话列表里也是错的、报告里却没判"的奇观。
 * 这个测试用**同一批数据**把所有出口都问一遍：任何一处实现漂移，这里立刻红。
 */
@SpringBootTest
@ActiveProfiles("test")
class WrongAnswerRuleConsistencyTest {

    private static final Long BANK_ID = 994001L;
    private static final Long SESSION_ID = 994002L;
    private static final String TEMPLATE = "official.civil-service";

    @Autowired
    private StudyRecordService studyRecordService;
    @Autowired
    private StudyRecordMapper studyRecordMapper;
    @Autowired
    private StatsService statsService;
    @Autowired
    private TutorService tutorService;
    @Autowired
    private PracticeSessionService sessionService;
    @Autowired
    private AdaptiveService adaptiveService;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 客观题答错 */
    private Long objectiveWrong;
    /** 客观题答对 */
    private Long objectiveRight;
    /** 主观题：判题列说对、自评说"部分对"——自评优先，算错 */
    private Long subjectivePartialWithCorrectFlag;
    /** 主观题：未判题（correct=null），自评"错"——算错 */
    private Long subjectiveWrong;
    /** 未判定：correct 与 selfGrade 都是 null（题没配答案 / 主观没自评）——既不算对也不算错 */
    private Long undecided;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM practice_session_question WHERE session_id IN (SELECT id FROM practice_session WHERE bank_id = ?)", BANK_ID);
        jdbc.update("DELETE FROM practice_session WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_bank WHERE id = ?", BANK_ID);
        jdbc.update("INSERT INTO question_bank (id, name, created_at, updated_at) VALUES (?, '一致性测试', NOW(), NOW())", BANK_ID);
        jdbc.update("INSERT INTO practice_session (id, bank_id, mode, question_count, created_at) VALUES (?, ?, 'ALL', 5, NOW())",
                SESSION_ID, BANK_ID);

        objectiveWrong = insertQuestion(1, QuestionType.SINGLE);
        objectiveRight = insertQuestion(2, QuestionType.SINGLE);
        subjectivePartialWithCorrectFlag = insertQuestion(3, QuestionType.SUBJECTIVE);
        subjectiveWrong = insertQuestion(4, QuestionType.SUBJECTIVE);
        undecided = insertQuestion(5, QuestionType.SUBJECTIVE);

        record(objectiveWrong, false, null, 1);
        record(objectiveRight, true, null, 2);
        // 判题列 true + 自评 PARTIAL：自评是"人拍板的"，必须优先
        record(subjectivePartialWithCorrectFlag, true, "PARTIAL", 3);
        record(subjectiveWrong, null, "WRONG", 4);
        // 未判定：不出现在任何"错"或"对"的集合里
        record(undecided, null, null, 5);

        // 会话里要真的有这几题（会话详情/报告按 practice_session_question 取题）
        int sort = 0;
        for (Long qid : List.of(objectiveWrong, objectiveRight, subjectivePartialWithCorrectFlag,
                subjectiveWrong, undecided)) {
            jdbc.update("INSERT INTO practice_session_question (session_id, question_id, sort) VALUES (?, ?, ?)",
                    SESSION_ID, qid, sort++);
        }
    }

    private Long insertQuestion(int number, QuestionType type) {
        Question q = new Question();
        q.setQuestionNumber(number);
        q.setExternalId("CONSIST_" + System.nanoTime() + "_" + number);
        q.setBankId(BANK_ID);
        q.setVolume(0);
        q.setQuestionType(type);
        q.setContent("第 " + number + " 题");
        if (type != QuestionType.SUBJECTIVE) {
            q.setOptions(List.of(new OptionItem("A", "选项一"), new OptionItem("B", "选项二")));
            q.setAnswerKeys("A");
        } else {
            q.setReferenceAnswer("参考答案");
        }
        q.setScore(2.0);
        questionMapper.insert(q);
        return q.getId();
    }

    private void record(Long questionId, Boolean correct, String selfGrade, int minutesAgo) {
        StudyRecord r = new StudyRecord();
        r.setBankId(BANK_ID);
        r.setQuestionId(questionId);
        r.setQuestionKey("CONSIST_KEY_" + questionId);
        r.setSessionId(SESSION_ID);
        r.setCorrect(correct);
        r.setSelfGrade(selfGrade);
        r.setSelectedKeys(correct == null ? null : "A");
        r.setAnsweredAt(LocalDateTime.now().minusMinutes(minutesAgo));
        studyRecordMapper.insert(r);
    }

    private Set<Long> wrongIds() {
        return StudyRecordService.computeWrongQuestionIds(
                studyRecordMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getBankId, BANK_ID)));
    }

    /** ① 错题集合：只有"最近一次作答为错"的三题（自评优先、未判定不算） */
    @Test
    void wrongQuestionSetFollowsTheSingleRule() {
        Set<Long> wrong = wrongIds();
        assertEquals(Set.of(objectiveWrong, subjectivePartialWithCorrectFlag, subjectiveWrong), wrong,
                "错题 = 客观答错 + 自评非 CORRECT；判题列 true 但自评 PARTIAL 也算错，未判定不算");
        assertFalse(wrong.contains(objectiveRight));
        assertFalse(wrong.contains(undecided), "未判定不能进错题本");
    }

    /** ② 统计页的题库口径与错题集合一致（此前它是"correct 优先"，与①相反） */
    @Test
    void statsBankCountsAgreeWithTheWrongQuestionSet() {
        var detail = statsService.detail();
        var bank = detail.banks().stream().filter(b -> BANK_ID.equals(b.bankId())).findFirst().orElseThrow();
        assertEquals(5, bank.answered(), "5 道题都有作答记录");
        assertEquals(4, bank.decided(), "未判定的那题不计入'有判定'");
        assertEquals(1, bank.correct(), "只有客观答对那题算对（PARTIAL 自评优先，算错）");
        // currentWrong 是**全库**计数（测试库与其它测试类共用），这里只要求它把本库这三题算进去
        assertTrue(detail.wrongHeal().currentWrong() >= 3,
                "统计的错题计数必须与错题集合同口径（实际 " + detail.wrongHeal().currentWrong() + "）");
    }

    /** ③ 讲解/复盘用的错题统计与①②一致（这里曾漏掉 PARTIAL 的分支） */
    @Test
    void tutorReviewSummaryAgreesWithTheWrongQuestionSet() {
        TutorService.ReviewSummary summary = tutorService.reviewSummary(BANK_ID, SESSION_ID, TEMPLATE);
        assertEquals(5, summary.total());
        assertEquals(1, summary.correct(), "只有客观答对那题算对");
        assertEquals(3, summary.wrong(), "两题自评错 + 一题客观答错");
        assertEquals(1, summary.unanswered(), "未判定的那题既不算对也不算错，单独统计");
        assertEquals(3, summary.wrongItems().size(), "错题清单条数与 wrong 一致");
    }

    /** ④ 会话详情/列表：未判定的题不能被算成"答错"（这里曾是 WRONG） */
    @Test
    void sessionDetailDoesNotTreatUndecidedAsWrong() {
        var detail = sessionService.getSessionDetail(SESSION_ID);
        assertEquals(1, detail.correctCount(), "只有客观答对那题算对");
        var undecidedItem = detail.questions().stream()
                .filter(q -> q.questionId().equals(undecided)).findFirst().orElseThrow();
        assertNull(undecidedItem.correct(), "未判定的题：correct 保持 null（不猜）");
        assertNull(undecidedItem.selfGrade());
        var partialItem = detail.questions().stream()
                .filter(q -> q.questionId().equals(subjectivePartialWithCorrectFlag)).findFirst().orElseThrow();
        assertEquals("PARTIAL", partialItem.selfGrade(), "自评原样返回，界面自己决定怎么显示");
        // 交卷报告（同一份数据）也要一致
        var report = sessionService.finishSession(SESSION_ID, null);
        assertEquals(detail.correctCount(), report.correctCount(), "报告与会话详情的答对数必须一致");
        assertTrue(report.maxScore() > 0);
    }

    /** ⑤ 能力分（85% 规则的输入）：自评错也算"作答过但不对" */
    @Test
    void abilityCountsSelfGradedWrong() {
        // 节点 A：只有客观答对那题（1 对 0 错）
        tag(objectiveRight, "gk.zl.growth");
        // 节点 B：一处答对 + 一处"判题列 true 但自评 PARTIAL"（1 对 1 错）
        Long plainRight = insertQuestion(6, QuestionType.SINGLE);
        record(plainRight, true, null, 6);
        tag(plainRight, "gk.zl.ratio");
        tag(subjectivePartialWithCorrectFlag, "gk.zl.ratio");

        double abilityOnlyRight = adaptiveService.abilityOnQuestionNodes(questionMapper.selectById(objectiveRight));
        double abilityWithPartial = adaptiveService.abilityOnQuestionNodes(questionMapper.selectById(plainRight));

        assertTrue(abilityWithPartial < abilityOnlyRight,
                "自评 PARTIAL 必须计入「作答过但不对」：带 PARTIAL 的节点能力分应更低（"
                        + abilityWithPartial + " vs " + abilityOnlyRight + "）");
    }

    private void tag(Long questionId, String nodeId) {
        jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                        + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, ?, ?, 'user', 1.0, 1, 'manual', 0, NOW())",
                questionId, BANK_ID, nodeId, TEMPLATE);
    }
}
