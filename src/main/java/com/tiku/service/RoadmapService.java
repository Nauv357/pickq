package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.mapper.DailyTaskMapper;
import com.tiku.mapper.LearnerProfileMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.QuestionSkillMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.mapper.TutorMessageMapper;
import com.tiku.mapper.TutorSessionMapper;
import com.tiku.model.DailyTask;
import com.tiku.model.LearnerProfile;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.QuestionSkill;
import com.tiku.model.ReviewState;
import com.tiku.model.StudyRecord;
import com.tiku.model.TutorMessage;
import com.tiku.model.TutorSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习路线（学习路径引擎阶段 2）：外缘 + 缺口 + 每日任务。
 * 设计见 docs/learning-path-design.md §5.2、§5.3、§7.1、§7.2。
 *
 * **核心承诺：这一层完全不调模型**（日常零 token）。掌握度、门控、外缘顺序、每日任务量
 * 全部是确定性公式；AI 只在"打标签、路线裁剪、复盘诊断、追问、卡片"这五处出现。
 *
 * 为什么掌握度**不落表**：study_record + question_skill + tutor_message 已经是全部证据，
 * 再存一份 user_skill_state 就会出现"两份真相"（用户删掉作答记录后状态对不上）。
 * 现算是 O(该题库作答数)，本地数据量下毫秒级；真要慢了也是先加缓存，不加真相。
 */
@Slf4j
@Service
public class RoadmapService {

    /** 参与掌握度计算的最近作答数（设计 §5.2：K=8，近 3 次加权 1.5） */
    private static final int MASTERY_WINDOW = 8;
    /** 门控：节点至少要有几道可练习题才算"证据充分"（设计 §5.3） */
    private static final int MIN_QUESTIONS_FOR_GATE = 3;
    /** 门控：mastery 阈值 */
    private static final double CLEAR_THRESHOLD = 0.85;
    /** 门控：独立正确次数下限（没要过提示、没追问过） */
    private static final int MIN_INDEPENDENT_CORRECT = 3;
    /** 门控：独立作答占比下限 */
    private static final double MIN_INDEPENDENT_RATIO = 0.70;
    /** 间隔复测间隔（天）：≥7 天后再做对一次，才算"记住了" */
    private static final int SPACED_DAYS = 7;

    private final QuestionMapper questionMapper;
    private final QuestionSkillMapper questionSkillMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final TutorSessionMapper tutorSessionMapper;
    private final TutorMessageMapper tutorMessageMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final QuestionBankMapper questionBankMapper;
    private final LearnerProfileMapper profileMapper;
    private final DailyTaskMapper dailyTaskMapper;
    private final SkillGraphService graphService;
    private final ObjectMapper objectMapper;

    public RoadmapService(QuestionMapper questionMapper, QuestionSkillMapper questionSkillMapper,
                          StudyRecordMapper studyRecordMapper, TutorSessionMapper tutorSessionMapper,
                          TutorMessageMapper tutorMessageMapper, ReviewStateMapper reviewStateMapper,
                          QuestionBankMapper questionBankMapper,
                          LearnerProfileMapper profileMapper, DailyTaskMapper dailyTaskMapper,
                          SkillGraphService graphService, ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.questionSkillMapper = questionSkillMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.tutorSessionMapper = tutorSessionMapper;
        this.tutorMessageMapper = tutorMessageMapper;
        this.reviewStateMapper = reviewStateMapper;
        this.questionBankMapper = questionBankMapper;
        this.profileMapper = profileMapper;
        this.dailyTaskMapper = dailyTaskMapper;
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    // ==================== 对外模型 ====================

    /** 一个知识点的状态（掌握度与门控都是公式算的，可解释、可复现） */
    public record NodeState(String nodeId, String name, String stageId, String stageName, int stageOrder,
                            int questionCount, int attempts, int correct, double mastery,
                            int independentCorrect, double independentRatio, int hintCount, boolean spacedOk,
                            String status, boolean prereqReady, List<String> blockedBy) {
    }

    /** 一个阶段（含它的节点） */
    public record StageState(String stageId, String name, int order, int nodeCount, int clearedCount,
                             List<NodeState> nodes) {
    }

    /** 缺口：没有题 / 题太少（防漏刷的第 5 层护栏）。reason 是短文案，长说明见界面的统一提示与 tooltip */
    public record Gap(String nodeId, String name, String stageName, int questionCount, String reason) {
    }

    /** 整条路线 */
    public record Roadmap(String templateId, String templateName, String goalText, int totalQuestions,
                          int coveredQuestions, int clearedNodes, int totalNodes,
                          List<StageState> stages, List<NodeState> nextBatch, List<Gap> gaps, String note) {
    }

    /** 今日任务（题单冻结，完成度按当天作答现算） */
    public record TaskView(String kind, String nodeId, String nodeName, List<Long> questionIds, int done, int total,
                           String title) {
    }

    public record TodayView(LocalDate date, int targetQuestions, int plannedQuestions, int doneQuestions,
                            boolean reviewEnabled, List<TaskView> tasks, String note) {
    }

    public record ProfileView(String templateId, String goalText, int dailyQuestions, Integer dailyMinutes,
                              String targetDate) {
    }

    // ==================== 个体输入 ====================

    public LearnerProfile profile() {
        LearnerProfile p = profileMapper.selectById(LearnerProfile.SINGLE_ROW);
        if (p == null) {
            p = new LearnerProfile();
            p.setId(LearnerProfile.SINGLE_ROW);
            p.setDailyQuestions(20);
            p.setUpdatedAt(LocalDateTime.now());
            profileMapper.insert(p);
        }
        return p;
    }

    @Transactional
    public LearnerProfile saveProfile(String templateId, String goalText, Integer dailyQuestions,
                                      Integer dailyMinutes, String targetDate) {
        if (templateId != null && !templateId.isBlank() && !graphService.has(templateId)) {
            throw new IllegalArgumentException("未知的技能模板：" + templateId);
        }
        LearnerProfile p = profile();
        boolean clearGoalText = false;
        boolean clearMinutes = false;
        boolean clearTarget = false;
        if (templateId != null && !templateId.isBlank()) {
            p.setGoalTemplateId(templateId);
        }
        if (goalText != null) {
            String trimmed = goalText.trim();
            if (trimmed.isEmpty()) {
                clearGoalText = true;
            } else {
                p.setGoalText(truncate(trimmed, 500));
            }
        }
        if (dailyQuestions != null) {
            // 只夹掉不合理的值（0/负数/上千题），不擅自替用户决定"至少多少题"
            p.setDailyQuestions(Math.max(1, Math.min(200, dailyQuestions)));
        }
        if (dailyMinutes != null) {
            if (dailyMinutes <= 0) {
                clearMinutes = true; // 0 = 不设时间预算
            } else {
                p.setDailyMinutes(Math.min(600, dailyMinutes));
            }
        }
        if (targetDate != null) {
            if (targetDate.isBlank()) {
                clearTarget = true;
            } else {
                p.setTargetDate(LocalDate.parse(targetDate));
            }
        }
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);
        // 清空同样要显式 wrapper：MyBatis-Plus 实体的 null 不会进 UPDATE（见 QuestionService 同样的处理）
        if (clearGoalText || clearMinutes || clearTarget) {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LearnerProfile> clear =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LearnerProfile>()
                            .eq(LearnerProfile::getId, LearnerProfile.SINGLE_ROW);
            if (clearGoalText) {
                clear.set(LearnerProfile::getGoalText, null);
            }
            if (clearMinutes) {
                clear.set(LearnerProfile::getDailyMinutes, null);
            }
            if (clearTarget) {
                clear.set(LearnerProfile::getTargetDate, null);
            }
            profileMapper.update(null, clear);
        }
        return p;
    }

    public ProfileView profileView() {
        LearnerProfile p = profile();
        return new ProfileView(p.getGoalTemplateId(), p.getGoalText(),
                p.getDailyQuestions() == null ? 20 : p.getDailyQuestions(), p.getDailyMinutes(),
                p.getTargetDate() == null ? null : p.getTargetDate().toString());
    }

    // ==================== 状态计算（公式） ====================

    /** 一次作答带上的"独立度"上下文：这题在这之前用过几级提示、是否追问过 */
    private record AttemptCtx(int hintLevel, boolean askedTutor) {
    }

    /**
     * 计算每个知识点的状态。所需数据一次取齐（题库作答、题目标签、提示记录），避免逐节点查库。
     */
    public List<NodeState> nodeStates(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId));
        Set<Long> questionIds = new HashSet<>();
        for (Question q : questions) {
            questionIds.add(q.getId());
        }
        // 题 → 可用标签（只认当前图里存在的节点，与审阅清单同口径）
        List<QuestionSkill> skillRows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getShadowed, false));
        Map<String, List<Long>> questionsOfNode = new LinkedHashMap<>();
        Map<Long, List<String>> nodesOfQuestion = new HashMap<>();
        for (QuestionSkill r : skillRows) {
            if (!questionIds.contains(r.getQuestionId()) || !template.nodeIds().contains(r.getNodeId())) {
                continue;
            }
            questionsOfNode.computeIfAbsent(r.getNodeId(), k -> new ArrayList<>()).add(r.getQuestionId());
            nodesOfQuestion.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r.getNodeId());
        }
        // 作答记录（按题分组，时间升序）
        Map<Long, List<StudyRecord>> records = new HashMap<>();
        for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId)
                .orderByAsc(StudyRecord::getAnsweredAt))) {
            records.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }
        // 提示与追问记录（按题分组，时间升序）：负向证据 → 独立度打折（设计 §5.1、§7.2）
        Map<Long, List<Object[]>> hintRecords = loadHintRecords(bankId);

        List<NodeState> out = new ArrayList<>();
        // 先算一遍"未过滤"的状态，再判断前置是否满足（前置看的是"已过关集合"，所以两遍）
        Map<String, NodeState> base = new LinkedHashMap<>();
        for (SkillGraphService.NodeView node : template.nodes()) {
            List<Long> qids = questionsOfNode.getOrDefault(node.nodeId(), List.of());
            List<StudyRecord> attempts = new ArrayList<>();
            for (Long qid : qids) {
                attempts.addAll(records.getOrDefault(qid, List.of()));
            }
            attempts.sort(Comparator.comparing(StudyRecord::getAnsweredAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            NodeState state = computeNode(template, node, qids.size(), attempts, hintRecords);
            base.put(node.nodeId(), state);
        }
        // 前置就绪：所有 prereq 都已 CLEARED（设计 §7.1）
        for (SkillGraphService.NodeView node : template.nodes()) {
            NodeState s = base.get(node.nodeId());
            List<String> blocked = new ArrayList<>();
            for (String pre : template.prereq().getOrDefault(node.nodeId(), List.of())) {
                NodeState preState = base.get(pre);
                if (preState == null || !"CLEARED".equals(preState.status())) {
                    blocked.add(preState == null ? pre : preState.name());
                }
            }
            out.add(new NodeState(s.nodeId(), s.name(), s.stageId(), s.stageName(), s.stageOrder(),
                    s.questionCount(), s.attempts(), s.correct(), s.mastery(), s.independentCorrect(),
                    s.independentRatio(), s.hintCount(), s.spacedOk(), s.status(),
                    blocked.isEmpty(), List.copyOf(blocked)));
        }
        return out;
    }

    private NodeState computeNode(SkillGraphService.SkillTemplate template, SkillGraphService.NodeView node,
                                  int questionCount, List<StudyRecord> attempts, Map<Long, List<Object[]>> hints) {
        int total = attempts.size();
        int correct = 0;
        int hintCount = 0;
        int independentCorrect = 0;
        int independentAttempts = 0;
        boolean spacedOk = false;
        double weightedSum = 0;
        double weightTotal = 0;
        // 只取最近 K 次参与掌握度（设计 §5.2）
        int from = Math.max(0, total - MASTERY_WINDOW);
        for (int i = from; i < total; i++) {
            StudyRecord r = attempts.get(i);
            AttemptCtx ctx = ctxBefore(hints.get(r.getQuestionId()), r.getAnsweredAt());
            boolean ok = Boolean.TRUE.equals(r.getCorrect());
            if (ok) {
                correct++;
            }
            if (ctx.hintLevel() > 0 || ctx.askedTutor()) {
                hintCount++;
            } else {
                independentAttempts++;
                if (ok) {
                    independentCorrect++;
                }
            }
            double independence = clamp(1 - 0.15 * ctx.hintLevel() - (ctx.askedTutor() ? 0.25 : 0), 0.30, 1.00);
            long days = r.getAnsweredAt() == null ? 0
                    : Duration.between(r.getAnsweredAt(), LocalDateTime.now()).toDays();
            double recency = Math.exp(-Math.max(0, days) / 30.0);
            double spacing = (ok && days >= SPACED_DAYS) ? 1.20 : 1.00;
            if (ok && days >= SPACED_DAYS) {
                spacedOk = true;
            }
            double evidence = (ok ? 1 : 0) * independence * recency * spacing;
            double weight = (i >= total - 3) ? 1.5 : 1.0; // 近 3 次更重
            weightedSum += evidence * weight;
            weightTotal += weight;
        }
        double mastery = weightTotal == 0 ? 0 : weightedSum / weightTotal;
        double independentRatio = independentAttempts == 0 ? 0 : independentAttempts * 1.0 / Math.max(1, total);

        // 节点可练习题数：整节点（不是窗口内），门控要求 ≥3（设计 §5.3）
        String status;
        if (questionCount < MIN_QUESTIONS_FOR_GATE) {
            status = "UNVERIFIED"; // 题库里题太少，无法确认掌握（必须显式提示，不假装过关）
        } else if (mastery >= CLEAR_THRESHOLD && independentCorrect >= MIN_INDEPENDENT_CORRECT
                && spacedOk && independentRatio >= MIN_INDEPENDENT_RATIO) {
            status = "CLEARED";
        } else {
            status = "LEARNING";
        }
        return new NodeState(node.nodeId(), node.name(), node.stageId(), node.stageName(), node.stageOrder(),
                questionCount, total, correct, round3(mastery), independentCorrect, round3(independentRatio),
                hintCount, spacedOk, status, false, List.of());
    }

    /** 某题在给定时刻**之前**用过的最高提示级别与是否追问过 */
    private AttemptCtx ctxBefore(List<Object[]> records, LocalDateTime at) {
        if (records == null || at == null) {
            return new AttemptCtx(0, false);
        }
        int level = 0;
        boolean asked = false;
        for (Object[] row : records) {
            LocalDateTime when = (LocalDateTime) row[0];
            if (when != null && when.isAfter(at)) {
                continue;
            }
            if (row[1] != null) {
                level = Math.max(level, (Integer) row[1]);
            }
            if (Boolean.TRUE.equals(row[2])) {
                asked = true;
            }
        }
        return new AttemptCtx(level, asked);
    }

    /** 该题库的提示/追问记录：questionId → [[时间, hintLevel|null, 是否用户提问], ...] */
    private Map<Long, List<Object[]>> loadHintRecords(Long bankId) {
        List<TutorSession> sessions = tutorSessionMapper.selectList(new LambdaQueryWrapper<TutorSession>()
                .eq(TutorSession::getBankId, bankId));
        Map<Long, Long> questionOfSession = new HashMap<>();
        for (TutorSession s : sessions) {
            if (s.getQuestionId() != null) {
                questionOfSession.put(s.getId(), s.getQuestionId());
            }
        }
        if (questionOfSession.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Object[]>> out = new HashMap<>();
        for (TutorMessage m : tutorMessageMapper.selectList(new LambdaQueryWrapper<TutorMessage>()
                .in(TutorMessage::getSessionId, questionOfSession.keySet())
                .orderByAsc(TutorMessage::getId))) {
            Long qid = questionOfSession.get(m.getSessionId());
            if (qid == null) {
                continue;
            }
            boolean isUser = TutorMessage.ROLE_USER.equals(m.getRole());
            out.computeIfAbsent(qid, k -> new ArrayList<>())
                    .add(new Object[]{m.getCreatedAt(), isUser ? null : m.getHintLevel(), isUser});
        }
        return out;
    }

    // ==================== 路线 / 外缘 / 缺口 ====================

    /** 整条路线：阶段 → 节点状态 + 外缘（下一步做什么）+ 缺口（还缺什么） */
    public Roadmap roadmap(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        List<NodeState> states = nodeStates(bankId, templateId);
        Map<String, NodeState> byId = new LinkedHashMap<>();
        for (NodeState s : states) {
            byId.put(s.nodeId(), s);
        }
        // 阶段聚合
        Map<String, List<NodeState>> byStage = new LinkedHashMap<>();
        for (NodeState s : states) {
            byStage.computeIfAbsent(s.stageId() == null ? "" : s.stageId(), k -> new ArrayList<>()).add(s);
        }
        List<StageState> stages = new ArrayList<>();
        for (String stageId : template.stageOrder()) {
            List<NodeState> nodes = byStage.getOrDefault(stageId, List.of());
            int cleared = (int) nodes.stream().filter(n -> "CLEARED".equals(n.status())).count();
            stages.add(new StageState(stageId, template.stageNames().getOrDefault(stageId, stageId),
                    nodes.isEmpty() ? 0 : nodes.get(0).stageOrder(), nodes.size(), cleared, nodes));
        }
        // 外缘：前置全过关 + 自己没过关 + 不是可选节点；按（阶段顺序, 权重降序, 题量降序）取前 3。
        // 额外加一条**实用约束**：优先选"这个节点你确实有题可练"的——否则路线会指着一个空节点，
        // 「今天做什么」直接是空的（题库只有部分知识点有题是常态）。都没有题时仍然给出方向，
        // 让界面能提示"下一步是 X，但你还没有这个知识点的题 → 去补题"。
        List<NodeState> ready = new ArrayList<>();
        for (SkillGraphService.NodeView n : template.nodes()) {
            NodeState s = byId.get(n.nodeId());
            if (s == null || n.optional() || "CLEARED".equals(s.status()) || !s.prereqReady()) {
                continue;
            }
            ready.add(s);
        }
        ready.sort(Comparator.comparingInt(NodeState::stageOrder)
                .thenComparing(n -> -weightOf(template, n.nodeId()))
                .thenComparing(n -> -n.questionCount()));
        List<NodeState> withQuestions = ready.stream().filter(n -> n.questionCount() > 0).toList();
        List<NodeState> pool = withQuestions.isEmpty() ? ready : withQuestions;
        List<NodeState> nextBatch = pool.size() > 3 ? pool.subList(0, 3) : pool;

        // 缺口：完全没题的节点 + 题量不足 3 的节点（按阶段顺序）。
        // 文案要短：这是列表项，长句子会被截断（界面上还有一行统一说明与 tooltip）
        List<Gap> gaps = new ArrayList<>();
        for (NodeState s : states) {
            if (s.questionCount() == 0) {
                gaps.add(new Gap(s.nodeId(), s.name(), s.stageName(), 0, "一道题都没有"));
            } else if (s.questionCount() < MIN_QUESTIONS_FOR_GATE) {
                gaps.add(new Gap(s.nodeId(), s.name(), s.stageName(), s.questionCount(),
                        "只有 " + s.questionCount() + " 题"));
            }
        }
        int covered = (int) states.stream().filter(s -> s.questionCount() > 0).count();
        int cleared = (int) states.stream().filter(s -> "CLEARED".equals(s.status())).count();
        int totalQuestions = Math.toIntExact(questionMapper.selectCount(new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId)));
        String note = nextBatch.isEmpty()
                ? "路线上的节点都过关了（或前置还没满足）。可以去「知识点」页看看有没有漏标的题。"
                : withQuestions.isEmpty()
                        ? "下一步是「" + nextBatch.get(0).name() + "」，但你还没有这个知识点的题——先补题（导入/录题）再来练。"
                        : "下一步按「前置已过关 + 还没过关 + 你有题可练」挑出来，顺序由技能图决定，不看模型脸色。";
        return new Roadmap(template.templateId(), template.name(),
                profile().getGoalText(), totalQuestions, covered, cleared, states.size(),
                stages, List.copyOf(nextBatch), gaps, note);
    }

    private double weightOf(SkillGraphService.SkillTemplate template, String nodeId) {
        for (SkillGraphService.NodeView n : template.nodes()) {
            if (n.nodeId().equals(nodeId)) {
                return n.weight();
            }
        }
        return 1.0;
    }

    // ==================== 今日任务 ====================

    /**
     * 今天的任务：外缘第一个节点的 n 道题（优先没做过的）+ 到期复习题。
     * **当天冻结**（落库 daily_task）：答掉一题清单不会变样；完成度按当天的作答现算。
     */
    @Transactional
    public TodayView today(Long bankId, String templateId) {
        LocalDate date = LocalDate.now();
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        LearnerProfile p = profile();
        int target = p.getDailyQuestions() == null ? 20 : p.getDailyQuestions();
        Map<String, String> nodeNames = new HashMap<>();
        for (SkillGraphService.NodeView n : template.nodes()) {
            nodeNames.put(n.nodeId(), n.name());
        }

        List<TaskView> tasks = new ArrayList<>();
        // ① 主攻：外缘第一个节点
        Roadmap map = roadmap(bankId, templateId);
        if (!map.nextBatch().isEmpty()) {
            NodeState focus = map.nextBatch().get(0);
            List<Long> picked = pickQuestions(bankId, focus.nodeId(), target);
            if (!picked.isEmpty()) {
                DailyTask row = ensureTask(date, DailyTask.KIND_PRACTICE, focus.nodeId(), templateId,
                        picked, "主攻「" + focus.name() + "」");
                List<Long> frozen = readIds(row);
                tasks.add(new TaskView(DailyTask.KIND_PRACTICE, focus.nodeId(), focus.name(), frozen,
                        countDoneToday(bankId, frozen), frozen.size(), "主攻「" + focus.name() + "」"));
            }
        }
        // ② 到期复习
        boolean reviewEnabled = isReviewEnabled(bankId);
        if (reviewEnabled) {
            List<Long> due = dueQuestionIds(bankId, target);
            if (!due.isEmpty()) {
                DailyTask row = ensureTask(date, DailyTask.KIND_REVIEW, "", templateId, due, "到期复习");
                List<Long> frozen = readIds(row);
                tasks.add(new TaskView(DailyTask.KIND_REVIEW, null, null, frozen,
                        countDoneToday(bankId, frozen), frozen.size(), "到期复习"));
            }
        }
        int planned = tasks.stream().mapToInt(TaskView::total).sum();
        int done = tasks.stream().mapToInt(TaskView::done).sum();
        String note = tasks.isEmpty()
                ? "今天没有安排（可能技能图上的节点都过关了，或题库还没有可练的题）。"
                : "任务由公式生成：外缘节点的题优先、没做过优先；完成度按今天的实际作答算。";
        return new TodayView(date, target, planned, done, reviewEnabled, tasks, note);
    }

    /** 取该节点下的题：先没做过的（按题号），不够再用做过的补（按最久没做） */
    private List<Long> pickQuestions(Long bankId, String nodeId, int limit) {
        List<QuestionSkill> rows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getNodeId, nodeId)
                .eq(QuestionSkill::getShadowed, false));
        List<Long> ids = rows.stream().map(QuestionSkill::getQuestionId).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .in(Question::getId, ids)
                .orderByAsc(Question::getQuestionNumber).orderByAsc(Question::getId));
        Set<Long> answered = new HashSet<>();
        for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId)
                .in(StudyRecord::getQuestionId, ids))) {
            answered.add(r.getQuestionId());
        }
        List<Long> fresh = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (Question q : questions) {
            (answered.contains(q.getId()) ? seen : fresh).add(q.getId());
        }
        List<Long> out = new ArrayList<>(fresh);
        for (Long id : seen) {
            if (out.size() >= limit) {
                break;
            }
            out.add(id);
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private boolean isReviewEnabled(Long bankId) {
        QuestionBank bank = questionBankMapper.selectById(bankId);
        return bank != null && Boolean.TRUE.equals(bank.getReviewEnabled());
    }

    private List<Long> dueQuestionIds(Long bankId, int limit) {
        return reviewStateMapper.selectList(new LambdaQueryWrapper<ReviewState>()
                        .eq(ReviewState::getBankId, bankId)
                        .eq(ReviewState::getSuspended, false)
                        .le(ReviewState::getDueAt, LocalDateTime.now()))
                .stream().map(ReviewState::getQuestionId).distinct().limit(limit).toList();
    }

    private DailyTask ensureTask(LocalDate date, String kind, String nodeId, String templateId,
                                 List<Long> ids, String title) {
        DailyTask existing = dailyTaskMapper.selectOne(new LambdaQueryWrapper<DailyTask>()
                .eq(DailyTask::getTaskDate, date)
                .eq(DailyTask::getKind, kind)
                .eq(DailyTask::getNodeId, nodeId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        DailyTask row = new DailyTask();
        row.setTaskDate(date);
        row.setKind(kind);
        row.setNodeId(nodeId == null ? "" : nodeId);
        row.setTemplateId(templateId);
        try {
            row.setPlanJson(objectMapper.writeValueAsString(Map.of("title", title, "questionIds", ids)));
        } catch (Exception e) {
            row.setPlanJson("{\"questionIds\":[]}");
        }
        row.setCreatedAt(LocalDateTime.now());
        dailyTaskMapper.insert(row);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Long> readIds(DailyTask row) {
        try {
            Map<String, Object> plan = objectMapper.readValue(row.getPlanJson(), Map.class);
            Object ids = plan.get("questionIds");
            if (ids instanceof List<?> list) {
                List<Long> out = new ArrayList<>();
                for (Object o : list) {
                    out.add(Long.valueOf(String.valueOf(o)));
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("当日任务清单解析失败（按空处理）：{}", e.getMessage());
        }
        return List.of();
    }

    /** 完成度：清单里今天已作答的题数（不依赖任何额外状态，删掉作答记录就自动回退） */
    private int countDoneToday(Long bankId, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        LocalDateTime start = LocalDate.now().atStartOfDay();
        Set<Long> done = new LinkedHashSet<>();
        for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId)
                .in(StudyRecord::getQuestionId, ids)
                .ge(StudyRecord::getAnsweredAt, start))) {
            done.add(r.getQuestionId());
        }
        return done.size();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
