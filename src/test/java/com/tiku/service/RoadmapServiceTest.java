package com.tiku.service;

import com.tiku.mapper.DailyTaskMapper;
import com.tiku.mapper.LearnerProfileMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.mapper.TutorMessageMapper;
import com.tiku.mapper.TutorSessionMapper;
import com.tiku.model.DailyTask;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.StudyRecord;
import com.tiku.model.TutorMessage;
import com.tiku.model.TutorSession;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学习路线（阶段 2）：掌握度与门控、外缘、缺口、当天任务。
 *
 * 这一层的承诺是"**日常零 token**"：所有结论都必须是**可复现的公式**，
 * 所以测试把公式的关键分支逐个锁住（证据不足 / 在练 / 过关 / 提示打折 / 前置未满足 / 题单冻结）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RoadmapServiceTest {

    private static final Long BANK_ID = 990201L;
    private static final String TEMPLATE = "official.civil-service";

    @Autowired
    private RoadmapService roadmap;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private StudyRecordMapper studyRecordMapper;
    @Autowired
    private TutorSessionMapper tutorSessionMapper;
    @Autowired
    private TutorMessageMapper tutorMessageMapper;
    @Autowired
    private DailyTaskMapper dailyTaskMapper;
    @Autowired
    private LearnerProfileMapper profileMapper;
    @Autowired
    private QuestionBankMapper bankMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM tutor_message");
        jdbc.update("DELETE FROM tutor_session");
        jdbc.update("DELETE FROM daily_task");
        jdbc.update("DELETE FROM learner_profile");
        jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_bank WHERE id = ?", BANK_ID);
        QuestionBank bank = new QuestionBank();
        bank.setId(BANK_ID);
        bank.setName("路线测试题库");
        bank.setReviewEnabled(false);
        bank.setCreatedAt(LocalDateTime.now());
        bank.setUpdatedAt(LocalDateTime.now());
        bankMapper.insert(bank);
    }

    private int seq = 0;

    private Long question(String content) {
        Question q = new Question();
        q.setQuestionNumber(++seq);
        q.setExternalId("ROAD_" + System.nanoTime() + "_" + seq);
        q.setBankId(BANK_ID);
        q.setVolume(0);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent(content);
        q.setAnswerKeys("A");
        q.setScore(1.0);
        questionMapper.insert(q);
        return q.getId();
    }

    private void tag(Long questionId, String nodeId) {
        jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                        + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, ?, ?, 'user', 1.0, 1, 'manual', 0, NOW())",
                questionId, BANK_ID, nodeId, TEMPLATE);
    }

    /** 写一条作答记录（做成"几天前"的，用来验证间隔加分与时间衰减） */
    private void attempt(Long questionId, boolean correct, int daysAgo) {
        StudyRecord r = new StudyRecord();
        r.setBankId(BANK_ID);
        r.setQuestionId(questionId);
        r.setQuestionKey("ROAD_KEY_" + questionId);
        r.setCorrect(correct);
        r.setSelectedKeys(correct ? "A" : "B");
        r.setAnsweredAt(LocalDateTime.now().minusDays(daysAgo));
        studyRecordMapper.insert(r);
    }

    /** 这道题在被作答之前用过提示（负向证据 → 独立度打折） */
    private void hintBefore(Long questionId, int level, int daysAgo) {
        TutorSession s = new TutorSession();
        s.setBankId(BANK_ID);
        s.setQuestionId(questionId);
        s.setKind(TutorSession.KIND_PER_QUESTION);
        s.setStatus("OPEN");
        s.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        s.setUpdatedAt(LocalDateTime.now().minusDays(daysAgo));
        tutorSessionMapper.insert(s);
        TutorMessage m = new TutorMessage();
        m.setSessionId(s.getId());
        m.setRole(TutorMessage.ROLE_ASSISTANT);
        m.setContent("提示内容");
        m.setHintLevel(level);
        m.setCreatedAt(LocalDateTime.now().minusDays(daysAgo).plusMinutes(1));
        tutorMessageMapper.insert(m);
    }

    /** 把某节点做成"已过关"：3 道题各独立做对一次，且是 8 天前（满足间隔复测） */
    private void clearNode(String nodeId) {
        for (int i = 0; i < 3; i++) {
            Long qid = question("过关题 " + nodeId + " #" + i);
            tag(qid, nodeId);
            attempt(qid, true, 8);
        }
    }

    private RoadmapService.NodeState node(String nodeId) {
        return roadmap.nodeStates(BANK_ID, TEMPLATE).stream()
                .filter(n -> n.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    @Test
    void nodeWithTooFewQuestionsIsUnverifiedNotCleared() {
        Long q = question("只有一道题的知识点");
        tag(q, "gk.zl.growth");
        attempt(q, true, 1);

        RoadmapService.NodeState state = node("gk.zl.growth");
        assertEquals("UNVERIFIED", state.status(), "题量 <3 不能确认掌握（防漏刷第 3 层护栏）");
        assertEquals(1, state.questionCount());
        assertTrue(state.attempts() >= 1);
        assertFalse(state.spacedOk(), "刚做过不算间隔复测");
    }

    @Test
    void independentSpacedCorrectsClearANodeButHintsAndFreshnessDoNot() {
        // ① 独立 + 间隔复测 → 过关
        for (int i = 0; i < 3; i++) {
            Long q = question("增长类题 " + i);
            tag(q, "gk.zl.growth");
            attempt(q, true, 8);
        }
        RoadmapService.NodeState cleared = node("gk.zl.growth");
        assertEquals("CLEARED", cleared.status(), JSON(cleared));
        assertTrue(cleared.mastery() >= 0.85, "mastery=" + cleared.mastery());
        assertEquals(3, cleared.independentCorrect());

        // ② 同样的题量，但都用了提示 → 独立正确数为 0，不能过关（提示/追问是负向证据）
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", BANK_ID);
        for (int i = 0; i < 3; i++) {
            Long q = question("靠提示做对的题 " + i);
            tag(q, "gk.zl.growth");
            hintBefore(q, 2, 9);
            attempt(q, true, 8);
        }
        RoadmapService.NodeState hinted = node("gk.zl.growth");
        assertEquals("LEARNING", hinted.status(), "用了提示才做对不算过关：" + JSON(hinted));
        assertEquals(0, hinted.independentCorrect());
        assertEquals(3, hinted.hintCount(), "三题都在作答前用过提示");

        // ③ 同样做对三次，但都是刚刚（没有间隔复测）→ 不过关
        jdbc.update("DELETE FROM study_record WHERE bank_id = ?", BANK_ID);
        jdbc.update("DELETE FROM tutor_message");
        jdbc.update("DELETE FROM tutor_session");
        jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", BANK_ID);
        for (int i = 0; i < 3; i++) {
            Long q = question("刚做对的题 " + i);
            tag(q, "gk.zl.growth");
            attempt(q, true, 0);
        }
        RoadmapService.NodeState fresh = node("gk.zl.growth");
        assertEquals("LEARNING", fresh.status(), "刚做对不代表一周后还记得（要间隔复测）：" + JSON(fresh));
    }

    @Test
    void outerFringeRespectsPrerequisitesAndGapsAreReported() {
        // 资料分析：增长类（无前置）先过关 → 比重与倍数（前置=增长类）才进入外缘
        List<RoadmapService.NodeState> beforeStates = roadmap.nodeStates(BANK_ID, TEMPLATE);
        RoadmapService.NodeState ratioBefore = beforeStates.stream()
                .filter(n -> n.nodeId().equals("gk.zl.ratio")).findFirst().orElseThrow();
        assertFalse(ratioBefore.prereqReady(), "前置（增长类）没过关前，比重与倍数不该就绪");
        assertTrue(ratioBefore.blockedBy().contains("增长类（增长率/增长量）"), JSON(ratioBefore));

        clearNode("gk.zl.growth");
        RoadmapService.NodeState ratioAfter = node("gk.zl.ratio");
        assertTrue(ratioAfter.prereqReady(), "增长类过关后，比重与倍数应进入外缘候选");
        assertTrue(ratioAfter.blockedBy().isEmpty());

        // 外缘优先选"确实有题可练"的节点：给平均数与指数（前置=增长类）挂 2 道题
        RoadmapService.Roadmap withoutQuestions = roadmap.roadmap(BANK_ID, TEMPLATE);
        assertTrue(withoutQuestions.nextBatch().size() > 0, "都没有题时仍要给出方向（界面会提示去补题）");
        assertTrue(withoutQuestions.note().contains("先补题"), withoutQuestions.note());

        for (int i = 0; i < 2; i++) {
            Long q = question("平均数题 " + i);
            tag(q, "gk.zl.avg");
        }
        RoadmapService.Roadmap map = roadmap.roadmap(BANK_ID, TEMPLATE);
        List<String> nextIds = map.nextBatch().stream().map(RoadmapService.NodeState::nodeId).toList();
        assertTrue(nextIds.contains("gk.zl.avg"), "有题可练的候选应进入外缘：" + nextIds);
        assertFalse(nextIds.contains("gk.cs.politics"),
                "空节点不该挤掉有题可练的节点（否则「今天做什么」是空的）：" + nextIds);
        assertFalse(nextIds.contains("gk.zl.growth"), "已过关的节点不该再出现在外缘");

        // 缺口：把"没题"和"题太少"都摊开
        assertTrue(map.gaps().size() > 20, "技能图 30+ 个节点，只有 2 个有题 → 缺口应把所有空节点列出来");
        assertTrue(map.gaps().stream().anyMatch(g -> g.nodeId().equals("gk.cs.law") && g.questionCount() == 0
                && g.reason().contains("一道题都没有")));
        assertTrue(map.gaps().stream().anyMatch(g -> g.nodeId().equals("gk.zl.avg") && g.questionCount() == 2
                && g.reason().contains("只有 2 题")), "题量 <3 也要如实提示：" + JSON(map.gaps()));
        assertTrue(map.clearedNodes() >= 1);
        assertEquals(TEMPLATE, map.templateId());
    }

    @Test
    void todayTasksAreFrozenForTheDayAndCompletionFollowsRealAnswers() {
        clearNode("gk.zl.growth");
        for (int i = 0; i < 5; i++) {
            Long q = question("比重题 " + i);
            tag(q, "gk.zl.ratio");
        }
        roadmap.saveProfile(TEMPLATE, "两个月内行测上 70", 3, 60, null);

        RoadmapService.TodayView first = roadmap.today(BANK_ID, TEMPLATE);
        // 今天可能同时有"主攻"和"抽测"两类任务（增长类 8 天前过的关 → 到期抽测）
        RoadmapService.TaskView task = first.tasks().stream()
                .filter(t -> "PRACTICE".equals(t.kind())).findFirst().orElseThrow();
        assertEquals("gk.zl.ratio", task.nodeId());
        assertEquals(3, task.total(), "每日题量按 profile 的 3 题");
        assertEquals(0, task.done());
        assertTrue(first.tasks().stream().allMatch(t -> t.total() > 0), "每条任务都要有题：" + first.tasks());

        // 同一天再问一次：题单必须一模一样（不能因为答了一题就换一套）
        RoadmapService.TodayView again = roadmap.today(BANK_ID, TEMPLATE);
        assertEquals(task.questionIds(), practiceTask(again).questionIds(), "当天题单要冻结");

        // 答掉两题 → 完成度 2/3（按当天的实际作答算，不存额外状态）
        attempt(task.questionIds().get(0), true, 0);
        attempt(task.questionIds().get(1), false, 0);
        RoadmapService.TodayView afterAnswer = roadmap.today(BANK_ID, TEMPLATE);
        assertEquals(2, practiceTask(afterAnswer).done());
        // 总完成度会 ≥2：同一道题可能同时属于"主攻"和"抽测"两条任务
        assertTrue(afterAnswer.doneQuestions() >= 2, "总完成度：" + JSON(afterAnswer));
        assertEquals(3, practiceTask(afterAnswer).total());

        // 昨天生成的清单不算今天：会重新生成一行
        jdbc.update("UPDATE daily_task SET task_date = ?", LocalDate.now().minusDays(1));
        RoadmapService.TodayView tomorrow = roadmap.today(BANK_ID, TEMPLATE);
        assertEquals(0, practiceTask(tomorrow).done(), "昨天的完成度不能算到今天头上");
        assertTrue(practiceTask(tomorrow).total() > 0, "新的一天仍要有题可做");
    }

    /** 取今天的主攻任务（今天可能还有抽测/闪卡任务，不假设只有一条） */
    private static RoadmapService.TaskView practiceTask(RoadmapService.TodayView view) {
        return view.tasks().stream().filter(t -> "PRACTICE".equals(t.kind())).findFirst().orElseThrow();
    }

    /**
     * 假掌握检测（阶段 3，设计 §5.4）：已过关的节点一旦出现"过关之后的失败证据"就要降级。
     * 两类证据：① 抽测做错；② 闪卡自评「忘了」。这条是"蒙对的、标签错的会被打回"的保证。
     */
    @Test
    void clearedNodeDemotesOnFailedSpotCheckOrForgottenCard() {
        clearNode("gk.zl.growth");
        assertEquals("CLEARED", node("gk.zl.growth").status());

        // ① 抽测做错（刚刚）→ REGRESSED，掌握度打折展示（×0.6），并重新进入外缘
        Long extra = question("增长类抽测题");
        tag(extra, "gk.zl.growth");
        attempt(extra, false, 0);
        RoadmapService.NodeState regressed = node("gk.zl.growth");
        assertEquals("REGRESSED", regressed.status(), "过关之后又做错 → 抽测降级：" + JSON(regressed));
        assertTrue(regressed.mastery() <= 0.85, "降级后掌握度要打折：" + regressed.mastery());
        assertTrue(roadmap.roadmap(BANK_ID, TEMPLATE).nextBatch().stream()
                        .anyMatch(n -> n.nodeId().equals("gk.zl.growth")),
                "降级的节点要重新回到外缘（重新练）");

        // 重做对一次也不能立刻回到 CLEARED（避免"错了马上改一下就算过关"）
        attempt(extra, true, 0);
        assertEquals("REGRESSED", node("gk.zl.growth").status(), "刚做对还不算恢复（要重新满足门控）");
    }

    /** 抽测安排：过关后 7 天没碰过 → 今天安排一题抽测（设计 §5.4 的 7/21/60 间隔阶梯） */
    @Test
    void spotCheckIsScheduledAfterTheIntervalLadder() {
        // 刚过关（证据是 8 天前的，但"最近一次作答"也是 8 天前 → 距今天数 8 ≥ 7 → 需要抽测）
        clearNode("gk.zl.growth");
        List<RoadmapService.NodeState> due = roadmap.spotCheckDue(BANK_ID, TEMPLATE);
        assertTrue(due.stream().anyMatch(n -> n.nodeId().equals("gk.zl.growth")),
                "过关 8 天没碰过 → 应安排抽测：" + due.stream().map(RoadmapService.NodeState::nodeId).toList());

        RoadmapService.TodayView today = roadmap.today(BANK_ID, TEMPLATE);
        assertTrue(today.tasks().stream().anyMatch(t -> "SPOT_CHECK".equals(t.kind())),
                "今日任务里应出现抽测：" + today.tasks());

        // 最近刚碰过 → 不再安排
        Long extra = question("增长类今天做过的题");
        tag(extra, "gk.zl.growth");
        attempt(extra, true, 0);
        assertTrue(roadmap.spotCheckDue(BANK_ID, TEMPLATE).stream()
                        .noneMatch(n -> n.nodeId().equals("gk.zl.growth")),
                "刚练过就不该再抽测");
    }

    @Test
    void dailyTasksAreScopedToTheBank() {
        // 两个题库同一天各练各的：题单不能串（实测 bug：daily_task 漏了 bank_id，
        // 换题库看路线时看到的还是上一个题库的题，完成度永远 0）
        clearNode("gk.zl.growth");
        for (int i = 0; i < 3; i++) {
            Long q = question("比重题 " + i);
            tag(q, "gk.zl.ratio");
        }
        RoadmapService.TodayView bankOne = roadmap.today(BANK_ID, TEMPLATE);
        assertTrue(practiceTask(bankOne).total() > 0);

        Long otherBank = 990299L;
        jdbc.update("DELETE FROM question WHERE bank_id = ?", otherBank);
        jdbc.update("DELETE FROM question_bank WHERE id = ?", otherBank);
        QuestionBank other = new QuestionBank();
        other.setId(otherBank);
        other.setName("第二个题库");
        other.setCreatedAt(LocalDateTime.now());
        other.setUpdatedAt(LocalDateTime.now());
        bankMapper.insert(other);
        try {
            Long otherQuestion = question("另一个题库的题");
            Question q = questionMapper.selectById(otherQuestion);
            q.setBankId(otherBank);
            questionMapper.updateById(q);
            jdbc.update("INSERT INTO question_skill (question_id, bank_id, node_id, template_id, source, confidence, "
                            + "confirmed, origin, shadowed, updated_at) VALUES (?, ?, 'gk.zl.growth', ?, 'user', 1.0, 1, 'manual', 0, NOW())",
                    otherQuestion, otherBank, TEMPLATE);

            RoadmapService.TodayView bankTwo = roadmap.today(otherBank, TEMPLATE);
            assertEquals(1, bankTwo.tasks().size(), "第二个题库只有它自己的任务：" + JSON(bankTwo));
            assertTrue(bankTwo.tasks().get(0).questionIds().contains(otherQuestion),
                    "题单必须是本库的题：" + JSON(bankTwo));
            assertFalse(bankTwo.tasks().get(0).questionIds().contains(practiceTask(bankOne).questionIds().get(0)),
                    "不能把上一个题库的题单拿过来用");
        } finally {
            jdbc.update("DELETE FROM question_skill WHERE bank_id = ?", otherBank);
            jdbc.update("DELETE FROM question WHERE bank_id = ?", otherBank);
            jdbc.update("DELETE FROM daily_task WHERE bank_id = ?", otherBank);
            jdbc.update("DELETE FROM question_bank WHERE id = ?", otherBank);
        }
    }

    @Test
    void profileIsClampedAndValidated() {
        // saveProfile 返回实体（服务内部用），读回来的是视图（接口用）——两者都验一遍
        roadmap.saveProfile(TEMPLATE, "  目标：把资料分析吃透  ", 999, 30, "2026-12-31");
        RoadmapService.ProfileView saved = roadmap.profileView();
        assertEquals(200, saved.dailyQuestions(), "每日题量上限 200（避免不现实的计划）");
        assertEquals("目标：把资料分析吃透", saved.goalText());
        assertEquals("2026-12-31", saved.targetDate());

        roadmap.saveProfile(null, null, 3, 0, null);
        assertEquals(3, roadmap.profileView().dailyQuestions());
        assertEquals(null, roadmap.profileView().dailyMinutes(), "0 分钟 = 不设预算");

        assertThrows(IllegalArgumentException.class,
                () -> roadmap.saveProfile("no.such.template", null, null, null, null));
        assertEquals(1, profileMapper.selectCount(null), "个体输入始终只有一行");
    }

    private static String JSON(Object o) {
        return String.valueOf(o);
    }
}
