package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.QuestionSkillMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.QuestionSkill;
import com.tiku.model.ReviewState;
import com.tiku.model.StudyRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 * 练习配题：决定"下次练哪几题"，并给出**一句事实解释**。
 *
 * 为什么是"配题"而不是"学习路线"（2026-09-16 产品收口）：
 * 用户打开题库想要的是"开始练"，不是"先看一份计划"。路线页面把规划与做题拆到两个地方，
 * 用户得先做一轮元活动（看计划、设目标）才能练——这是隔离感的来源。
 * 所以：**规划隐形进练习入口**，用户看到的永远是「题 + 一句可核对的事实解释」。
 *
 * 同时砍掉了掌握度宣判（抽测/"你忘了"）与闪卡：那些是我们没资格下的结论，
 * 且都要求用户额外交互。这里保留的只有可核对的量：
 * 错过几次、什么时候到期、这个知识点你有几道题、这题预测成功率多少。
 *
 * **完全不调模型**（零 token），全部是确定性公式，刷新多少次结果都一样。
 */
@Slf4j
@Service
public class PracticePlanService {

    /** 参与掌握度计算的最近作答数（设计 §5.2：K=8，近 3 次加权 1.5） */
    private static final int MASTERY_WINDOW = 8;
    /** 练习节默认题量 */
    public static final int DEFAULT_COUNT = 20;
    /** 单题最多几张卡的封顶不在这里；这里只控题量上限 */
    private static final int MAX_COUNT = 200;

    /** 配比（设计 §7.1 的每日任务口径，按用户实际能力取整后顺延） */
    private static final double RATIO_WRONG = 0.30;
    private static final double RATIO_DUE = 0.30;
    private static final double RATIO_WEAK = 0.30;

    private final QuestionMapper questionMapper;
    private final QuestionSkillMapper questionSkillMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final QuestionBankMapper questionBankMapper;
    private final StudyRecordService studyRecordService;
    private final AdaptiveService adaptiveService;
    private final SkillGraphService graphService;

    public PracticePlanService(QuestionMapper questionMapper, QuestionSkillMapper questionSkillMapper,
                               StudyRecordMapper studyRecordMapper, ReviewStateMapper reviewStateMapper,
                               QuestionBankMapper questionBankMapper,
                               StudyRecordService studyRecordService, AdaptiveService adaptiveService,
                               SkillGraphService graphService) {
        this.questionMapper = questionMapper;
        this.questionSkillMapper = questionSkillMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.reviewStateMapper = reviewStateMapper;
        this.questionBankMapper = questionBankMapper;
        this.studyRecordService = studyRecordService;
        this.adaptiveService = adaptiveService;
        this.graphService = graphService;
    }

    // ==================== 对外模型 ====================

    /** 一道题为什么被选中（可核对的理由，用来在界面上解释"为什么给你这些题"） */
    public record PlanItem(Long questionId, String reason, String nodeId, String nodeName) {
    }

    /** 一节课的配题结果 */
    public record Plan(int requested, int total, int wrongCount, int dueCount, int weakCount, int newCount,
                       String weakNodeName, int weakNodeQuestions, List<PlanItem> items, String explain) {
    }

    public static final String REASON_WRONG = "WRONG";
    public static final String REASON_DUE = "DUE";
    public static final String REASON_WEAK = "WEAK";
    public static final String REASON_NEW = "NEW";

    // ==================== 配题 ====================

    /**
     * 配一节课的题：错题重做 → 到期复习 → 薄弱知识点 → 新题（按顺序去重后凑满）。
     * 每个桶内部按 85% 规则（预测成功率 80–90%）排序，而不是按题号。
     */
    public Plan plan(Long bankId, String templateId, Integer count) {
        int want = count == null || count <= 0 ? DEFAULT_COUNT : Math.min(count, MAX_COUNT);
        List<Question> all = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId)
                .orderByAsc(Question::getQuestionNumber)
                .orderByAsc(Question::getId));
        if (all.isEmpty()) {
            return new Plan(want, 0, 0, 0, 0, 0, null, 0, List.of(), "这个题库还没有题目");
        }
        Map<Long, Question> byId = new LinkedHashMap<>();
        for (Question q : all) {
            byId.put(q.getId(), q);
        }
        Map<Long, List<String>> nodesOfQuestion = templateId == null || templateId.isBlank()
                ? Map.of() : nodesOfQuestion(bankId, templateId);
        Map<String, Integer> questionsPerNode = new HashMap<>();
        Map<String, String> nodeNames = new HashMap<>();
        for (Map.Entry<Long, List<String>> e : nodesOfQuestion.entrySet()) {
            for (String node : e.getValue()) {
                questionsPerNode.merge(node, 1, Integer::sum);
            }
        }
        // 每题的掌握度（用所属节点里最高的那个；没有标签时按 0.5 中性）
        Map<Long, Double> masteryOfQuestion = new HashMap<>();
        Map<String, Double> masteryByNode = new HashMap<>();
        for (NodeState s : nodeStates(bankId, templateId)) {
            masteryByNode.put(s.nodeId(), s.mastery());
            nodeNames.put(s.nodeId(), s.name());
        }
        for (Map.Entry<Long, List<String>> e : nodesOfQuestion.entrySet()) {
            double best = 0.5;
            for (String node : e.getValue()) {
                best = Math.max(best, masteryByNode.getOrDefault(node, 0.5));
            }
            masteryOfQuestion.put(e.getKey(), best);
        }

        Set<Long> answered = new HashSet<>();
        Map<Long, Integer> wrongTimes = new HashMap<>();
        for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId))) {
            answered.add(r.getQuestionId());
            if (Boolean.FALSE.equals(r.getCorrect())) {
                wrongTimes.merge(r.getQuestionId(), 1, Integer::sum);
            }
        }

        Set<Long> picked = new LinkedHashSet<>();
        List<PlanItem> items = new ArrayList<>();
        // 桶配额：短练习（want < 4）时 floor 会把三个桶全部抹成 0（1×0.3=0），
        // 那样"练 3 题"会一道错题都不给，与"错题优先"的口径自相矛盾——所以按优先级给小课保底 1 题。
        int targetWrong = (int) Math.floor(want * RATIO_WRONG);
        int targetDue = (int) Math.floor(want * RATIO_DUE);
        int targetWeak = (int) Math.floor(want * RATIO_WEAK);
        if (want >= 1 && targetWrong == 0) {
            targetWrong = 1;
        }
        if (want >= 2 && targetDue == 0) {
            targetDue = 1;
        }
        if (want >= 3 && targetWeak == 0) {
            targetWeak = 1;
        }

        // ① 错题重做：按"错过几次"从多到少（最近一次作答为错才算错题，口径与错题本一致）
        List<Long> wrongIds = new ArrayList<>(studyRecordService.listWrongQuestionIds(bankId));
        wrongIds.sort(Comparator.comparingInt((Long id) -> -wrongTimes.getOrDefault(id, 0))
                .thenComparing(id -> id));
        addBucket(picked, items, byId, orderByTargetSuccess(wrongIds, byId, masteryOfQuestion),
                targetWrong, REASON_WRONG, nodesOfQuestion, nodeNames);

        // ② 到期复习（复习计划未开启时这个桶为空，属于正常）
        List<Long> dueIds = isReviewEnabled(bankId)
                ? reviewStateMapper.selectList(new LambdaQueryWrapper<ReviewState>()
                        .eq(ReviewState::getBankId, bankId)
                        .eq(ReviewState::getSuspended, false)
                        .le(ReviewState::getDueAt, LocalDateTime.now()))
                .stream().map(ReviewState::getQuestionId).distinct().toList()
                : List.of();
        addBucket(picked, items, byId, orderByTargetSuccess(dueIds, byId, masteryOfQuestion),
                targetDue, REASON_DUE, nodesOfQuestion, nodeNames);

        // ③ 薄弱知识点：优先"你有题但还没做过"的节点里最薄弱的那个（掌握度最低）
        String weakNode = weakestNode(byId.keySet(), nodesOfQuestion, masteryByNode, questionsPerNode, answered);
        List<Long> weakIds = new ArrayList<>();
        if (weakNode != null) {
            for (Map.Entry<Long, List<String>> e : nodesOfQuestion.entrySet()) {
                if (e.getValue().contains(weakNode) && !answered.contains(e.getKey())) {
                    weakIds.add(e.getKey());
                }
            }
            weakIds.sort(Comparator.naturalOrder());
        }
        addBucket(picked, items, byId, orderByTargetSuccess(weakIds, byId, masteryOfQuestion),
                targetWeak, REASON_WEAK, nodesOfQuestion, nodeNames);

        // ④ 新题补满（还没做过的题，按 85% 规则）
        List<Long> fresh = new ArrayList<>();
        for (Question q : all) {
            if (!answered.contains(q.getId())) {
                fresh.add(q.getId());
            }
        }
        int needNew = Math.max(0, want - picked.size());
        addBucket(picked, items, byId, orderByTargetSuccess(fresh, byId, masteryOfQuestion),
                needNew, REASON_NEW, nodesOfQuestion, nodeNames);

        // ⑤ 仍不够（题都做过且没有错题/到期）：用"最久没做过的"补满
        if (picked.size() < want) {
            List<Long> stalest = new ArrayList<>();
            for (Question q : all) {
                if (!picked.contains(q.getId())) {
                    stalest.add(q.getId());
                }
            }
            stalest.sort(Comparator.comparing(id -> lastAnsweredAt(bankId, id),
                    Comparator.nullsFirst(Comparator.naturalOrder())));
            addBucket(picked, items, byId, stalest, want - picked.size(), REASON_NEW, nodesOfQuestion, nodeNames);
        }

        int wrongCount = (int) items.stream().filter(i -> REASON_WRONG.equals(i.reason())).count();
        int dueCount = (int) items.stream().filter(i -> REASON_DUE.equals(i.reason())).count();
        int weakCount = (int) items.stream().filter(i -> REASON_WEAK.equals(i.reason())).count();
        int newCount = items.size() - wrongCount - dueCount - weakCount;
        int weakNodeQuestions = weakNode == null ? 0 : questionsPerNode.getOrDefault(weakNode, 0);
        String explain = explain(items.size(), wrongCount, dueCount, weakCount, newCount,
                weakNode == null ? null : nodeNames.getOrDefault(weakNode, weakNode), weakNodeQuestions, all.size());
        return new Plan(want, items.size(), wrongCount, dueCount, weakCount, newCount,
                weakNode == null ? null : nodeNames.getOrDefault(weakNode, weakNode), weakNodeQuestions,
                List.copyOf(items), explain);
    }

    private void addBucket(Set<Long> picked, List<PlanItem> items, Map<Long, Question> byId, List<Long> candidates,
                           int quota, String reason, Map<Long, List<String>> nodesOfQuestion,
                           Map<String, String> nodeNames) {
        if (quota <= 0) {
            return;
        }
        int added = 0;
        for (Long id : candidates) {
            if (added >= quota) {
                break;
            }
            if (byId.containsKey(id) && picked.add(id)) {
                List<String> nodes = nodesOfQuestion.getOrDefault(id, List.of());
                String nodeId = nodes.isEmpty() ? null : nodes.get(0);
                items.add(new PlanItem(id, reason, nodeId,
                        nodeId == null ? null : nodeNames.getOrDefault(nodeId, nodeId)));
                added++;
            }
        }
    }

    /**
     * 桶内按 85% 规则排序：**统一走 `AdaptiveService` 的那一份**（含"优先落在 80%–90% 区间"）。
     *
     * 2026-09-16 审计：这里曾有一份私有副本，只有"离 85% 的距离"而没有区间优待，
     * 于是文档承诺的"优先挑预测成功率 80%–90% 的题"实际没生效（`AdaptiveService.orderByTargetSuccess` 无人调用）。
     * 排名的分数口径只允许有一处实现——否则改了 A 忘了 B，用户看到的顺序与文档说的不一致。
     */
    private List<Long> orderByTargetSuccess(List<Long> ids, Map<Long, Question> byId,
                                            Map<Long, Double> masteryOfQuestion) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // 同掌握度的题一起算：把候选题按掌握度分组，每组只调一次共享排序
        List<Long> out = new ArrayList<>(ids);
        Map<Double, List<Long>> byMastery = new LinkedHashMap<>();
        for (Long id : out) {
            byMastery.computeIfAbsent(masteryOfQuestion.getOrDefault(id, 0.5), k -> new ArrayList<>()).add(id);
        }
        List<Long> sorted = new ArrayList<>();
        byMastery.forEach((mastery, group) -> {
            List<Question> questions = group.stream().map(byId::get)
                    .filter(java.util.Objects::nonNull).toList();
            if (questions.isEmpty()) {
                return;
            }
            sorted.addAll(adaptiveService.orderByTargetSuccess(questions, mastery).stream()
                    .map(Question::getId).toList());
        });
        return sorted;
    }

    private LocalDateTime lastAnsweredAt(Long bankId, Long questionId) {
        StudyRecord last = studyRecordMapper.selectOne(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId)
                .eq(StudyRecord::getQuestionId, questionId)
                .orderByDesc(StudyRecord::getAnsweredAt)
                .last("LIMIT 1"));
        return last == null ? null : last.getAnsweredAt();
    }

    private boolean isReviewEnabled(Long bankId) {
        QuestionBank bank = questionBankMapper.selectById(bankId);
        return bank != null && Boolean.TRUE.equals(bank.getReviewEnabled());
    }

    /**
     * 最该补的知识点：**你有题、但还有没做过的题**的节点里，掌握度最低的那个；
     * 掌握度相同（多数是都没做过，mastery=0）时优先"库里题少"的——那才是真的缺口，
     * 而且这个数字能作为事实写进解释里（"这个知识点你库里只有 N 题"）。
     * 没有任何可用标签时返回 null（那就纯按错题/新题配）。
     */
    private String weakestNode(Set<Long> bankQuestionIds, Map<Long, List<String>> nodesOfQuestion,
                               Map<String, Double> masteryByNode, Map<String, Integer> questionsPerNode,
                               Set<Long> answered) {
        Set<String> candidates = new LinkedHashSet<>();
        for (Map.Entry<Long, List<String>> e : nodesOfQuestion.entrySet()) {
            if (!bankQuestionIds.contains(e.getKey()) || answered.contains(e.getKey())) {
                continue;
            }
            for (String node : e.getValue()) {
                if (questionsPerNode.getOrDefault(node, 0) > 0) {
                    candidates.add(node);
                }
            }
        }
        return candidates.stream()
                .min(Comparator
                        .comparingDouble((String n) -> masteryByNode.getOrDefault(n, 0.0))
                        .thenComparingInt(n -> questionsPerNode.getOrDefault(n, 0))
                        .thenComparing(n -> n))
                .orElse(null);
    }

    /** 一句事实解释：只陈述可核对的数量，不做任何"你应该学会什么"的判断 */
    private String explain(int total, int wrong, int due, int weak, int fresh, String weakNode,
                           int weakNodeQuestions, int bankQuestions) {
        if (total == 0) {
            return "这个题库还没有可练的题";
        }
        List<String> parts = new ArrayList<>();
        if (wrong > 0) {
            parts.add(wrong + " 题是你之前做错的");
        }
        if (due > 0) {
            parts.add(due + " 题今天到期复习");
        }
        if (weak > 0 && weakNode != null) {
            parts.add(weak + " 题来自「" + weakNode + "」（这个知识点你库里只有 " + weakNodeQuestions + " 题）");
        }
        if (fresh > 0) {
            parts.add(fresh + " 题你还没做过");
        }
        String body = parts.isEmpty() ? "都是你练过的题" : String.join(" · ", parts);
        return "这 " + total + " 题：" + body + "（题库共 " + bankQuestions + " 题）";
    }

    // ==================== 知识点状态（配题用；也可作为统计口径） ====================

    /** 一个知识点的状态（掌握度是确定性公式，不落表） */
    public record NodeState(String nodeId, String name, String stageName, int questionCount, int attempts,
                            int correct, double mastery) {
    }

    /**
     * 每个知识点的掌握度（设计 §5.2）：
     * {@code evidence = 对错 × 独立度(提示/追问打折) × 时间衰减 × 间隔加分}，取最近 8 次作答（近 3 次权重 1.5）。
     * 现算而不是落表：作答记录就是唯一真相，删掉记录状态自然回退。
     */
    public List<NodeState> nodeStates(Long bankId, String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return List.of();
        }
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId));
        Set<Long> questionIds = new HashSet<>();
        for (Question q : questions) {
            questionIds.add(q.getId());
        }
        Map<Long, List<String>> nodesOfQuestion = nodesOfQuestion(bankId, templateId);
        Map<String, List<Long>> questionsOfNode = new LinkedHashMap<>();
        for (Map.Entry<Long, List<String>> e : nodesOfQuestion.entrySet()) {
            if (!questionIds.contains(e.getKey())) {
                continue;
            }
            for (String node : e.getValue()) {
                questionsOfNode.computeIfAbsent(node, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        Map<Long, List<StudyRecord>> records = new HashMap<>();
        for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId)
                .orderByAsc(StudyRecord::getAnsweredAt))) {
            records.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }
        List<NodeState> out = new ArrayList<>();
        for (Map.Entry<String, List<Long>> e : questionsOfNode.entrySet()) {
            List<StudyRecord> attempts = new ArrayList<>();
            for (Long qid : e.getValue()) {
                attempts.addAll(records.getOrDefault(qid, List.of()));
            }
            attempts.sort(Comparator.comparing(StudyRecord::getAnsweredAt,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
            out.add(new NodeState(e.getKey(), displayName(e.getKey(), templateId), null, e.getValue().size(), attempts.size(),
                    (int) attempts.stream().filter(r -> Boolean.TRUE.equals(r.getCorrect())).count(),
                    mastery(attempts)));
        }
        return out;
    }

    /** 节点显示名（"gk.zl.growth" → "增长类（增长率/增长量）"）；图里没有就退回 nodeId，绝不编造 */
    private String displayName(String nodeId, String templateId) {
        if (templateId == null || templateId.isBlank() || !graphService.has(templateId)) {
            return nodeId;
        }
        try {
            for (SkillGraphService.NodeView n : graphService.template(templateId).nodes()) {
                if (n.nodeId().equals(nodeId)) {
                    return n.name();
                }
            }
        } catch (Exception e) {
            log.debug("技能图读取失败，节点名退回 id：{}", e.getMessage());
        }
        return nodeId;
    }

    /**
     * 掌握度公式（独立度/衰减/间隔加分都在这里；提示与追问是负向证据）。
     * 提示与追问的判定数据来自 tutor_message：这条链路在阶段 1 已落地。
     */
    private double mastery(List<StudyRecord> attempts) {
        int total = attempts.size();
        int from = Math.max(0, total - MASTERY_WINDOW);
        double weighted = 0;
        double weights = 0;
        for (int i = from; i < total; i++) {
            StudyRecord r = attempts.get(i);
            boolean ok = Boolean.TRUE.equals(r.getCorrect());
            long days = r.getAnsweredAt() == null ? 0
                    : Duration.between(r.getAnsweredAt(), LocalDateTime.now()).toDays();
            double recency = Math.exp(-Math.max(0, days) / 30.0);
            double spacing = (ok && days >= 7) ? 1.20 : 1.00;
            double weight = (i >= total - 3) ? 1.5 : 1.0;
            weighted += (ok ? 1 : 0) * recency * spacing * weight;
            weights += weight;
        }
        return weights == 0 ? 0 : Math.round(weighted / weights * 1000) / 1000.0;
    }

    /** 题 → 该题挂的（当前图里存在的）知识点 */
    private Map<Long, List<String>> nodesOfQuestion(Long bankId, String templateId) {
        LambdaQueryWrapper<QuestionSkill> w = new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getShadowed, false);
        if (templateId != null && !templateId.isBlank()) {
            w.eq(QuestionSkill::getTemplateId, templateId);
        }
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (QuestionSkill r : questionSkillMapper.selectList(w)) {
            out.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r.getNodeId());
        }
        return out;
    }
}
