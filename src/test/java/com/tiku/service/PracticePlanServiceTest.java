package com.tiku.service;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 练习配题（PracticePlanService）：把"学习路线"收口成"开始练习"之后的核心引擎。
 *
 * 锁住的设计要点（都是产品口径的落地，不是实现细节）：
 * 1. 四个桶按优先级去重：错题 → 到期复习 → 薄弱知识点 → 新题，同一题只出现一次（顺序即优先级）；
 * 2. 解释只讲**可核对的事实**（几道错题、几道到期、这个知识点库里几道题、题库共几道），
 *    不出现"你应该/你需要加强"这类宣判；
 * 3. 薄弱知识点挑"有题、还有没做过的题、掌握度最低"的那个，并把它的真实名称写进解释；
 * 4. 结果确定性：同一状态调两次完全一样（纯公式，零 token）；
 * 5. 题库之间互不串题。
 */
@SpringBootTest
@ActiveProfiles("test")
class PracticePlanServiceTest {

    private static final String TEMPLATE = "official.civil-service";
    private static final String NODE_GROWTH = "gk.zl.growth";
    private static final String NODE_RATIO = "gk.zl.ratio";

    private static final Long BANK_A = 991001L;
    private static final Long BANK_B = 991002L;
    private static final Long BANK_EMPTY = 991003L;

    @Autowired
    private PracticePlanService planService;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        for (Long bankId : List.of(BANK_A, BANK_B, BANK_EMPTY)) {
            jdbc.update("DELETE FROM tutor_message WHERE session_id IN (SELECT id FROM tutor_session WHERE bank_id = ?)", bankId);
            jdbc.update("DELETE FROM tutor_session WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM review_state WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM study_record WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question_bank WHERE id = ?", bankId);
        }
        insertBank(BANK_A, true);
        insertBank(BANK_B, false);
        insertBank(BANK_EMPTY, true);
    }

    // ==================== 工具 ====================

    private void insertBank(Long bankId, boolean reviewEnabled) {
        jdbc.update("INSERT INTO question_bank (id, name, description, review_enabled, created_at, updated_at) "
                + "VALUES (?, ?, 'practice-plan-test', ?, NOW(), NOW())", bankId, "配题测试题库", reviewEnabled ? 1 : 0);
    }

    private Long insertQuestion(Long bankId, int number) {
        Question q = new Question();
        q.setQuestionNumber(number);
        q.setExternalId("PLAN_" + bankId + "_" + System.nanoTime() + "_" + number);
        q.setBankId(bankId);
        q.setVolume(0);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent("第 " + number + " 题：2024 年该省 GDP 为 120 亿，2023 年为 100 亿，增长率是多少？");
        q.setOptions(List.of(new OptionItem("A", "20%"), new OptionItem("B", "16.7%")));
        q.setAnswerKeys("A");
        q.setScore(1.0);
        questionMapper.insert(q);
        return q.getId();
    }

    private void answer(Long bankId, Long questionId, boolean correct, int daysAgo) {
        jdbc.update("INSERT INTO study_record (bank_id, question_id, question_key, is_correct, selected_keys, answered_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                bankId, questionId, "PLAN_KEY_" + questionId, correct ? 1 : 0, correct ? "A" : "B",
                LocalDateTime.now().minusDays(daysAgo));
    }

    private void due(Long bankId, Long questionId, int inDays) {
        jdbc.update("INSERT INTO review_state (question_id, bank_id, level, interval_days, due_at, suspended) "
                + "VALUES (?, ?, 1, 3, ?, 0)", questionId, bankId, LocalDateTime.now().plusDays(inDays));
    }

    private void tag(Long questionId, String nodeId) {
        jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                        + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, ?, ?, 'user', 1.0, 1, 'manual', 0, NOW())",
                questionId, BANK_A, nodeId, TEMPLATE);
    }

    private List<Long> idsOfType(PracticePlanService.Plan plan, String reason) {
        return plan.items().stream()
                .filter(i -> reason.equals(i.reason()))
                .map(PracticePlanService.PlanItem::questionId)
                .toList();
    }

    // ==================== 1. 四个桶 + 去重 + 顺序 ====================

    @Test
    void bucketsFillInPriorityOrderWithoutDuplicates() {
        // 12 题：2 道错题（其中 1 道同时到期）→ 1 道纯到期 → 3 道薄弱知识点（没做过）→ 其余新题
        Long q1 = insertQuestion(BANK_A, 1);
        Long q2 = insertQuestion(BANK_A, 2);
        Long q3 = insertQuestion(BANK_A, 3);
        Long q4 = insertQuestion(BANK_A, 4);
        Long q5 = insertQuestion(BANK_A, 5);
        Long q6 = insertQuestion(BANK_A, 6);
        for (int n = 7; n <= 12; n++) {
            insertQuestion(BANK_A, n);
        }
        answer(BANK_A, q1, false, 3);   // 错题 + 到期（同一题只能出现一次）
        answer(BANK_A, q2, false, 1);   // 错题
        due(BANK_A, q1, -1);
        due(BANK_A, q3, -1);            // 纯到期（没做过）
        tag(q4, NODE_GROWTH);
        tag(q5, NODE_GROWTH);
        tag(q6, NODE_GROWTH);

        PracticePlanService.Plan plan = planService.plan(BANK_A, TEMPLATE, 10);

        assertEquals(10, plan.total());
        assertEquals(2, plan.wrongCount(), "错题桶：最近一次答错的两题");
        assertEquals(1, plan.dueCount(), "到期桶：q1 已被错题桶拿走，只剩 q3");
        assertEquals(3, plan.weakCount(), "薄弱知识点桶：该节点 3 道没做过的题");
        assertEquals(4, plan.newCount(), "剩下用新题补满");

        List<Long> ids = plan.items().stream().map(PracticePlanService.PlanItem::questionId).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "同一题不能出现两次：" + ids);
        assertEquals(Set.of(q1, q2), Set.copyOf(idsOfType(plan, PracticePlanService.REASON_WRONG)));
        assertEquals(List.of(q3), idsOfType(plan, PracticePlanService.REASON_DUE));
        assertEquals(Set.of(q4, q5, q6), Set.copyOf(idsOfType(plan, PracticePlanService.REASON_WEAK)));

        // 顺序即优先级：错题在最前，其次到期，再次薄弱，最后新题
        List<String> reasons = plan.items().stream().map(PracticePlanService.PlanItem::reason).toList();
        assertEquals(reasons.stream().sorted(java.util.Comparator.comparingInt(
                r -> switch (r) {
                    case PracticePlanService.REASON_WRONG -> 0;
                    case PracticePlanService.REASON_DUE -> 1;
                    case PracticePlanService.REASON_WEAK -> 2;
                    default -> 3;
                })).toList(), reasons, "桶顺序不能乱：" + reasons);
    }

    // ==================== 2. 解释只讲可核对的事实 ====================

    @Test
    void explanationStatesVerifiableFactsOnly() {
        Long q1 = insertQuestion(BANK_A, 1);
        Long q2 = insertQuestion(BANK_A, 2);
        Long q3 = insertQuestion(BANK_A, 3);
        Long q4 = insertQuestion(BANK_A, 4);
        answer(BANK_A, q1, false, 2);
        due(BANK_A, q2, -1);
        tag(q3, NODE_GROWTH);
        tag(q4, NODE_GROWTH);

        PracticePlanService.Plan plan = planService.plan(BANK_A, TEMPLATE, 4);

        String explain = plan.explain();
        assertTrue(explain.startsWith("这 4 题："), explain);
        assertTrue(explain.contains("1 题是你之前做错的"), explain);
        assertTrue(explain.contains("1 题今天到期复习"), explain);
        assertNotNull(plan.weakNodeName(), "有标签时必须说出是哪个知识点");
        assertTrue(explain.contains("题来自「" + plan.weakNodeName() + "」"), explain);
        assertTrue(explain.contains("这个知识点你库里只有 2 题"), explain);
        assertTrue(explain.contains("（题库共 4 题）"), explain);
        for (String banned : new String[]{"掌握度", "你应该", "建议你", "你需要加强", "薄弱"}) {
            assertFalse(explain.contains(banned), "解释里不能出现宣判式措辞「" + banned + "」：" + explain);
        }
    }

    /** 知识点名称来自技能图（不是 nodeId），否则用户看到的是 gk.zl.growth 这种内部 id */
    @Test
    void weakNodeNameComesFromTheSkillGraph() {
        Long q1 = insertQuestion(BANK_A, 1);
        tag(q1, NODE_GROWTH);
        PracticePlanService.Plan plan = planService.plan(BANK_A, TEMPLATE, 3);
        assertEquals(1, plan.weakCount());
        assertTrue(plan.weakNodeName().contains("增长"), "应显示技能图里的中文名：" + plan.weakNodeName());
        assertFalse(plan.weakNodeName().startsWith("gk."), "不能把内部 id 当名称：" + plan.weakNodeName());
    }

    // ==================== 3. 薄弱知识点 = "掌握度最低且还有没做过的题" ====================

    @Test
    void weakestNodePrefersTheLowestMasteryThatStillHasUnseenQuestions() {
        // 增长类：3 道做过（全对）；比重类：3 道做过（全错）
        Long ok1 = insertQuestion(BANK_A, 1);
        Long ok2 = insertQuestion(BANK_A, 2);
        Long ok3 = insertQuestion(BANK_A, 3);
        Long bad1 = insertQuestion(BANK_A, 4);
        Long bad2 = insertQuestion(BANK_A, 5);
        Long bad3 = insertQuestion(BANK_A, 6);
        for (Long id : List.of(ok1, ok2, ok3)) {
            tag(id, NODE_GROWTH);
            answer(BANK_A, id, true, 2);
        }
        for (Long id : List.of(bad1, bad2, bad3)) {
            tag(id, NODE_RATIO);
            answer(BANK_A, id, false, 2);
        }
        // 两个节点各留一道没做过的题，配题要挑掌握度更低的那个（比重类）
        Long unseenGrowth = insertQuestion(BANK_A, 7);
        Long unseenRatio = insertQuestion(BANK_A, 8);
        tag(unseenGrowth, NODE_GROWTH);
        tag(unseenRatio, NODE_RATIO);

        PracticePlanService.Plan plan = planService.plan(BANK_A, TEMPLATE, 6);

        assertEquals(List.of(unseenRatio), idsOfType(plan, PracticePlanService.REASON_WEAK),
                "掌握度最低的节点优先（比重类全错）");
        assertTrue(plan.weakNodeName().contains("比重"), plan.weakNodeName());
    }

    /** 短练习也要给错题留位置（floor(0.3×1)=0 曾让"练 1 题"永远只给新题） */
    @Test
    void shortPlanStillStartsWithTheWrongQuestions() {
        Long wrong = insertQuestion(BANK_A, 1);
        for (int n = 2; n <= 5; n++) {
            insertQuestion(BANK_A, n);
        }
        answer(BANK_A, wrong, false, 1);

        PracticePlanService.Plan one = planService.plan(BANK_A, TEMPLATE, 1);
        assertEquals(List.of(wrong), idsOfType(one, PracticePlanService.REASON_WRONG), one.explain());
        assertEquals(1, one.wrongCount());
        assertTrue(one.explain().contains("1 题是你之前做错的"), one.explain());
    }

    // ==================== 4. 确定性 + 题量收敛 ====================

    @Test
    void planIsDeterministicAndCountIsClamped() {
        for (int n = 1; n <= 6; n++) {
            Long q = insertQuestion(BANK_A, n);
            if (n <= 2) {
                answer(BANK_A, q, false, 1);
            }
        }
        PracticePlanService.Plan first = planService.plan(BANK_A, null, 5);
        PracticePlanService.Plan second = planService.plan(BANK_A, null, 5);
        assertEquals(first.items().stream().map(PracticePlanService.PlanItem::questionId).toList(),
                second.items().stream().map(PracticePlanService.PlanItem::questionId).toList(),
                "同一状态两次配题必须完全一致（零 token、无随机）");
        assertEquals(5, first.total());

        // 0 / 负数 → 默认题量（这里题库只有 6 题，所以是 6）；超过上限也不报错
        assertEquals(6, planService.plan(BANK_A, null, 0).total());
        assertEquals(6, planService.plan(BANK_A, null, -3).total());
        assertEquals(6, planService.plan(BANK_A, null, 500).total());
        assertEquals(1, planService.plan(BANK_A, null, 1).total());
    }

    /** 没做过任何题的新题库：不该靠"错题/到期"配，直接全给新题 */
    @Test
    void freshBankGetsNewQuestionsOnly() {
        for (int n = 1; n <= 5; n++) {
            insertQuestion(BANK_A, n);
        }
        PracticePlanService.Plan plan = planService.plan(BANK_A, TEMPLATE, 3);
        assertEquals(3, plan.total());
        assertEquals(0, plan.wrongCount());
        assertEquals(0, plan.dueCount());
        assertEquals(0, plan.weakCount());
        assertEquals(3, plan.newCount());
        assertTrue(plan.explain().contains("3 题你还没做过"), plan.explain());
    }

    @Test
    void emptyBankExplainsItselfInsteadOfThrowing() {
        PracticePlanService.Plan plan = planService.plan(BANK_EMPTY, TEMPLATE, 20);
        assertEquals(0, plan.total());
        assertTrue(plan.items().isEmpty());
        assertEquals("这个题库还没有题目", plan.explain());
    }

    // ==================== 5. 题库隔离 ====================

    @Test
    void planNeverLeaksQuestionsFromAnotherBank() {
        Long mine = insertQuestion(BANK_A, 1);
        Long theirsWrong = insertQuestion(BANK_B, 2);
        answer(BANK_B, theirsWrong, false, 1);
        // 另一个库里到期 + 有标签的题也不该被抓过来
        Long theirsDue = insertQuestion(BANK_B, 3);
        due(BANK_B, theirsDue, -1);
        jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                        + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, ?, ?, 'user', 1.0, 1, 'manual', 0, NOW())",
                theirsDue, BANK_B, NODE_RATIO, TEMPLATE);

        PracticePlanService.Plan plan = planService.plan(BANK_A, TEMPLATE, 10);

        Set<Long> ids = plan.items().stream().map(PracticePlanService.PlanItem::questionId).collect(Collectors.toSet());
        assertEquals(Set.of(mine), ids, "只配本库的题：" + ids);
        assertEquals(0, plan.wrongCount());
        assertEquals(0, plan.dueCount());
        assertTrue(plan.explain().contains("（题库共 1 题）"), plan.explain());
    }

    /** 复习开关关闭时：到期桶为空（不提醒），但已经配出来的题照常给 */
    @Test
    void dueBucketStaysEmptyWhenReviewIsOff() {
        Long q1 = insertQuestion(BANK_A, 1);
        Long q2 = insertQuestion(BANK_B, 2);
        due(BANK_A, q1, -1);
        due(BANK_B, q2, -1);

        PracticePlanService.Plan off = planService.plan(BANK_B, TEMPLATE, 5);
        assertEquals(0, off.dueCount(), "复习计划没开就不该出现「今天到期复习」");
        assertFalse(off.explain().contains("到期"), off.explain());
    }
}
