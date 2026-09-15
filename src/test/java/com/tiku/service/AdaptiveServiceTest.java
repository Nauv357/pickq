package com.tiku.service;

import com.tiku.mapper.QuestionDifficultyMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.Question;
import com.tiku.model.QuestionDifficulty;
import com.tiku.model.StudyRecord;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自适应难度（阶段 3，设计 §5.5）：
 * 85% 规则（挑"预测成功率 80–90%"的题）、Elo-lite 难度更新、交错混练比例。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdaptiveServiceTest {

    private static final Long BANK_ID = 990301L;

    @Autowired
    private AdaptiveService adaptive;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private StudyRecordMapper studyRecordMapper;
    @Autowired
    private QuestionDifficultyMapper difficultyMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM question_difficulty");
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);
    }

    private int seq = 0;

    private Question question(QuestionType type) {
        Question q = new Question();
        q.setQuestionNumber(++seq);
        q.setExternalId("ADP_" + System.nanoTime() + "_" + seq);
        q.setBankId(BANK_ID);
        q.setVolume(0);
        q.setQuestionType(type);
        q.setContent("自适应测试题 " + seq);
        q.setAnswerKeys("A");
        q.setScore(1.0);
        questionMapper.insert(q);
        return q;
    }

    @Test
    void initialDifficultyFollowsQuestionType() {
        Question single = question(QuestionType.SINGLE);
        Question multiple = question(QuestionType.MULTIPLE);
        Question judge = question(QuestionType.JUDGE);
        Question subjective = question(QuestionType.SUBJECTIVE);
        var map = adaptive.difficultiesOf(List.of(single, multiple, judge, subjective));
        assertEquals(400.0, map.get(single.getId()), 0.001);
        assertTrue(map.get(multiple.getId()) > map.get(single.getId()), "多选题应比单选难");
        assertTrue(map.get(judge.getId()) < map.get(single.getId()), "判断题应比单选易");
        assertTrue(map.get(subjective.getId()) > map.get(multiple.getId()), "主观题最难");
    }

    @Test
    void answeringCorrectlyLowersDifficultyAndWrongRaisesIt() {
        Question q = question(QuestionType.SINGLE);
        double ability = AdaptiveService.abilityOf(0.5); // 中性能力分 θ=0
        // 答对：比预期好 → 难度下调
        adaptive.recordAnswer(q.getId(), true, ability);
        QuestionDifficulty after = difficultyMapper.selectById(q.getId());
        assertTrue(after.getDifficulty() < 400, "答对后难度应下调：" + after.getDifficulty());
        assertEquals(1, after.getAttempts());

        // 连续答错：难度上调（这题对这个人来说偏难）
        double d1 = after.getDifficulty();
        adaptive.recordAnswer(q.getId(), false, ability);
        double d2 = difficultyMapper.selectById(q.getId()).getDifficulty();
        assertTrue(d2 > d1, "答错后难度应上调：" + d1 + " → " + d2);
    }

    /** 85% 规则：能力 0.5（θ=0）时，难度 400 的题预测成功率 50%，应排在最难/最易之后 */
    @Test
    void targetSuccessRulePicksQuestionsNearEightyFivePercent() {
        Question easy = question(QuestionType.JUDGE);      // 初值 320
        Question medium = question(QuestionType.SINGLE);   // 初值 400
        Question hard = question(QuestionType.SUBJECTIVE); // 初值 520
        // 把 medium 调成"预测 85%"：θ=0 时 p = 1/(1+10^(d/400))，要 p=0.85 需 d = 400×log10(0.15/0.85) ≈ −301
        // （负难度 = 比这个人当前水平更简单，这是 Elo 的正常表示）
        QuestionDifficulty row = new QuestionDifficulty();
        row.setQuestionId(medium.getId());
        row.setDifficulty(-301.0);
        row.setAttempts(0);
        row.setUpdatedAt(LocalDateTime.now());
        difficultyMapper.insert(row);

        List<Question> ordered = adaptive.orderByTargetSuccess(List.of(easy, hard, medium), 0.5);
        assertEquals(medium.getId(), ordered.get(0).getId(), "最接近 85% 的题应排最前");
        // 预测值本身：medium ≈ 85%，easy/hard 都偏离
        var difficulty = adaptive.difficultiesOf(List.of(easy, medium, hard));
        int pMedium = adaptive.predictedSuccess(medium, 0.5, difficulty);
        assertTrue(Math.abs(pMedium - 85) <= 2, "medium 的预测成功率应接近 85%：" + pMedium);
        assertTrue(adaptive.predictedSuccess(hard, 0.5, difficulty) < 85);
    }

    /** 掌握度越高 → 能力分越高 → 同一道题显得越容易 */
    @Test
    void abilityFollowsMastery() {
        assertEquals(-400.0, AdaptiveService.abilityOf(0), 0.001);
        assertEquals(0.0, AdaptiveService.abilityOf(0.5), 0.001);
        assertEquals(400.0, AdaptiveService.abilityOf(1), 0.001);
        Question q = question(QuestionType.SINGLE);
        var difficulty = adaptive.difficultiesOf(List.of(q));
        int lowAbility = adaptive.predictedSuccess(q, 0.2, difficulty);
        int highAbility = adaptive.predictedSuccess(q, 0.95, difficulty);
        assertTrue(highAbility > lowAbility, "掌握度高的节点，同一题更容易：" + lowAbility + " vs " + highAbility);
    }

    /** 交错混练：低掌握度纯集中练，掌握之后才混相邻节点（设计：交错对低基础者有害） */
    @Test
    void interleavingOnlyAfterMastery() {
        assertEquals(0.0, AdaptiveService.interleaveRatio(0.3), 0.001);
        assertEquals(0.0, AdaptiveService.interleaveRatio(0.59), 0.001);
        assertEquals(0.15, AdaptiveService.interleaveRatio(0.7), 0.001);
        assertEquals(0.35, AdaptiveService.interleaveRatio(0.9), 0.001);
    }

    /** 能力分按"该题所属知识点上的历史正确率"算，样本少时向 0.5 收缩 */
    @Test
    void abilityIsShrunkTowardsNeutralWhenEvidenceIsThin() {
        Question q = question(QuestionType.SINGLE);
        jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                        + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, 'gk.zl.growth', 'official.civil-service', "
                        + "'user', 1.0, 1, 'manual', 0, NOW())",
                q.getId(), BANK_ID);
        // 只有一次答对：收缩后应明显低于 1.0（不能因为做对一题就当高手）
        StudyRecord r = new StudyRecord();
        r.setBankId(BANK_ID);
        r.setQuestionId(q.getId());
        r.setQuestionKey("ADP_KEY_" + q.getId());
        r.setCorrect(true);
        r.setAnsweredAt(LocalDateTime.now());
        studyRecordMapper.insert(r);

        double ability = adaptive.abilityOnQuestionNodes(q);
        // 一次答对：收缩后 mastery 代理 ≈ (1+1.5)/(1+3) = 0.625 → θ ≈ 100（明显低于"都会了"的 400）
        assertTrue(ability > 0 && ability < 150, "一次答对的能力分应偏保守（θ 接近 0）：" + ability);
    }

    /** 抽题时不应把同一题重复列出来 */
    @Test
    void orderingKeepsAllQuestionsOnce() {
        List<Question> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(question(QuestionType.SINGLE));
        }
        List<Question> ordered = adaptive.orderByTargetSuccess(all, 0.4);
        assertEquals(5, ordered.size());
        assertEquals(5, ordered.stream().map(Question::getId).distinct().count());
    }
}
