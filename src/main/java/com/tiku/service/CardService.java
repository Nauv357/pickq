package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.mapper.CardMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.QuestionSkillMapper;
import com.tiku.model.Card;
import com.tiku.model.Question;
import com.tiku.model.QuestionSkill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 闪卡（学习路径引擎阶段 3，设计 §3.5、§7.5）：把解析里的关键结论挖空，练"回忆"而不是"再认"。
 *
 * 与题目的分工（设计原话）：题目测**再认**，卡片测**回忆**，两者互补。
 * 因此卡片**不能**独立把节点判为已掌握，但答错可以作为**抽测证据**让已过关的节点掉下来。
 *
 * 调度：沿用与题目同一套阶梯（答对 level+1、间隔 = min(2^level, 30) 天；答错 level 归零、立刻重来）。
 * 不引入第二套算法，用户不需要理解两种复习节奏。
 */
@Slf4j
@Service
public class CardService {

    /** 一次生成调用带多少题（控制 token；设计 §9：卡片生成 20 题一批） */
    private static final int BATCH = 20;
    /** 解析太短的题不出卡（没东西可挖） */
    private static final int MIN_ANALYSIS_CHARS = 20;
    /** 单题最多几张卡（防止一道题刷出十张卡把复习队列淹了） */
    private static final int MAX_CARDS_PER_QUESTION = 3;

    private final CardMapper cardMapper;
    private final QuestionMapper questionMapper;
    private final QuestionSkillMapper questionSkillMapper;
    private final AiClientService aiClientService;
    private final AiConfigService aiConfigService;
    private final SkillGraphService graphService;
    private final ObjectMapper objectMapper;

    public CardService(CardMapper cardMapper, QuestionMapper questionMapper, QuestionSkillMapper questionSkillMapper,
                       AiClientService aiClientService, AiConfigService aiConfigService,
                       SkillGraphService graphService, ObjectMapper objectMapper) {
        this.cardMapper = cardMapper;
        this.questionMapper = questionMapper;
        this.questionSkillMapper = questionSkillMapper;
        this.aiClientService = aiClientService;
        this.aiConfigService = aiConfigService;
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    // ==================== 视图模型 ====================

    public record CardView(Long id, Long questionId, Integer questionNumber, String nodeId, String nodeName,
                           String front, String back, String source, boolean confirmed, int level,
                           int intervalDays, LocalDateTime dueAt, int lapses, String lastResult) {
    }

    public record GenerateResult(int scanned, int generated, int skippedHasCards, int skippedNoAnalysis,
                                 int aiCalls, int truncated, String message) {
    }

    public record CardStats(int total, int unconfirmed, int due, int learned) {
    }

    // ==================== 生成 ====================

    /**
     * 从解析里生成挖空卡（AI，按批推进，可中断续跑）。
     * - 只处理**有解析且还没有卡**的题（已出过的题跳过，不会重复烧 token）；
     * - 结果 `source=ai, confirmed=0`：**未确认不参与复习调度**（先让人过一眼，再进复习队列）。
     */
    @Transactional
    public GenerateResult generate(Long bankId, String templateId, String nodeId, Integer maxAiCalls) {
        if (!aiConfigService.isConfigured()) {
            throw new IllegalStateException("尚未配置 AI 模型，无法自动生成闪卡（可在设置页配置后再试）");
        }
        int budget = maxAiCalls == null || maxAiCalls <= 0 ? 5 : maxAiCalls;
        Set<Long> questionIds = questionIdsOfNode(bankId, templateId, nodeId);
        if (questionIds.isEmpty()) {
            return new GenerateResult(0, 0, 0, 0, 0, 0, "这个范围内还没有带知识点的题");
        }
        Set<Long> hasCards = new LinkedHashSet<>();
        for (Card c : cardMapper.selectList(new LambdaQueryWrapper<Card>().eq(Card::getBankId, bankId))) {
            hasCards.add(c.getQuestionId());
        }
        List<Question> candidates = new ArrayList<>();
        int noAnalysis = 0;
        int skippedHasCards = 0;
        for (Long qid : questionIds) {
            if (hasCards.contains(qid)) {
                skippedHasCards++;
                continue;
            }
            Question q = questionMapper.selectById(qid);
            if (q == null) {
                continue;
            }
            if (q.getAnalysis() == null || q.getAnalysis().trim().length() < MIN_ANALYSIS_CHARS) {
                noAnalysis++;
                continue;
            }
            candidates.add(q);
        }
        if (candidates.isEmpty()) {
            return new GenerateResult(questionIds.size(), 0, skippedHasCards, noAnalysis, 0, 0,
                    "没有可出卡的题（要么都已经有卡，要么解析太短）");
        }
        int generated = 0;
        int aiCalls = 0;
        boolean truncated = false;
        for (int start = 0; start < candidates.size(); start += BATCH) {
            if (aiCalls >= budget) {
                truncated = true;
                break;
            }
            List<Question> batch = candidates.subList(start, Math.min(start + BATCH, candidates.size()));
            try {
                generated += askForCards(bankId, batch, templateId);
                aiCalls++;
            } catch (Exception e) {
                log.warn("闪卡生成失败（该批跳过，可重试）：{}", e.getMessage());
                aiCalls++;
            }
        }
        log.info("闪卡生成：题库 {} 扫描 {} 题，新出 {} 张，调用 {} 次{}", bankId, candidates.size(), generated, aiCalls,
                truncated ? "（达到调用上限，可再次点击继续）" : "");
        return new GenerateResult(candidates.size(), generated, skippedHasCards, noAnalysis, aiCalls,
                truncated ? 1 : 0,
                truncated ? "已达到本次 AI 调用上限，可再次点击继续" : "生成完成");
    }

    private int askForCards(Long bankId, List<Question> batch, String templateId) {
        StringBuilder user = new StringBuilder();
        user.append("下面每道题都带了题干与解析，请为**每道题**提取 1–2 张挖空卡，用来考「回忆」：\n")
                .append("要求：\n")
                .append("1. front 是一句**挖空**的提示（用 ____ 表示被挖掉的关键内容），必须让人能看懂在问什么；\n")
                .append("2. back 只写被挖掉的关键内容（公式、结论、判别要点），不要整段解析；\n")
                .append("3. 挖的必须是「记住就能做对」的东西（公式、结论、易错点、判别口诀），不要挖题干里的数字；\n")
                .append("4. front/back 各不超过 60 字；只输出 JSON，不要解释。\n\n")
                .append("输出格式：{\"cards\":[{\"questionId\":123,\"front\":\"...____...\",\"back\":\"...\"}]}\n\n");
        for (Question q : batch) {
            user.append("【第 ").append(q.getQuestionNumber() == null ? "?" : q.getQuestionNumber())
                    .append(" 题】questionId=").append(q.getId()).append('\n');
            user.append("题干：").append(oneLine(q.getContent(), 200)).append('\n');
            if (q.getAnswerKeys() != null && !q.getAnswerKeys().isBlank()) {
                user.append("答案：").append(q.getAnswerKeys()).append('\n');
            }
            user.append("解析：").append(oneLine(q.getAnalysis(), 600)).append("\n\n");
        }
        AiSettings settings = aiConfigService.load();
        String reply = aiClientService.chat(settings, CARD_SYSTEM, user.toString(), true);
        JsonNode root = parseJson(reply);
        Map<Long, Integer> perQuestion = new HashMap<>();
        int created = 0;
        for (JsonNode node : root.path("cards")) {
            long qid = node.path("questionId").asLong(0);
            String front = oneLine(node.path("front").asText(""), 1000);
            String back = oneLine(node.path("back").asText(""), 1000);
            if (qid <= 0 || front.isBlank() || back.isBlank()) {
                continue;
            }
            // 只接受本批次的题（模型偶尔会"发明"题号）；每题的卡数也要封顶
            boolean inBatch = batch.stream().anyMatch(q -> q.getId() == qid);
            if (!inBatch || perQuestion.getOrDefault(qid, 0) >= MAX_CARDS_PER_QUESTION) {
                continue;
            }
            Card card = new Card();
            card.setBankId(bankId);
            card.setQuestionId(qid);
            card.setNodeId(primaryNodeOf(qid, templateId));
            card.setFront(front);
            card.setBack(back);
            card.setSource(Card.SOURCE_AI);
            card.setConfirmed(false);
            card.setLevel(0);
            card.setIntervalDays(1);
            card.setLapses(0);
            card.setCreatedAt(LocalDateTime.now());
            card.setUpdatedAt(LocalDateTime.now());
            cardMapper.insert(card);
            perQuestion.merge(qid, 1, Integer::sum);
            created++;
        }
        return created;
    }

    private String primaryNodeOf(Long questionId, String templateId) {
        if (templateId == null || !graphService.has(templateId)) {
            return null;
        }
        return questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                        .eq(QuestionSkill::getQuestionId, questionId)
                        .eq(QuestionSkill::getTemplateId, templateId)
                        .eq(QuestionSkill::getShadowed, false))
                .stream()
                .filter(r -> graphService.template(templateId).nodeIds().contains(r.getNodeId()))
                .map(QuestionSkill::getNodeId)
                .findFirst().orElse(null);
    }

    // ==================== 查询 / 管理 ====================

    public List<CardView> list(Long bankId, String status, String templateId) {
        LambdaQueryWrapper<Card> w = new LambdaQueryWrapper<Card>().eq(Card::getBankId, bankId);
        if ("unconfirmed".equals(status)) {
            w.eq(Card::getConfirmed, false);
        } else if ("due".equals(status)) {
            w.eq(Card::getConfirmed, true).le(Card::getDueAt, LocalDateTime.now());
        } else if ("confirmed".equals(status)) {
            w.eq(Card::getConfirmed, true);
        }
        w.orderByAsc(Card::getConfirmed).orderByAsc(Card::getDueAt).orderByAsc(Card::getId);
        return views(cardMapper.selectList(w), templateId);
    }

    public List<CardView> due(Long bankId, int limit, String templateId) {
        List<Card> rows = cardMapper.selectList(new LambdaQueryWrapper<Card>()
                .eq(Card::getBankId, bankId)
                .eq(Card::getConfirmed, true)
                .le(Card::getDueAt, LocalDateTime.now())
                .orderByAsc(Card::getDueAt)
                .last("LIMIT " + Math.max(1, Math.min(200, limit))));
        return views(rows, templateId);
    }

    public CardStats stats(Long bankId) {
        List<Card> all = cardMapper.selectList(new LambdaQueryWrapper<Card>().eq(Card::getBankId, bankId));
        int unconfirmed = 0;
        int due = 0;
        int learned = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Card c : all) {
            if (!Boolean.TRUE.equals(c.getConfirmed())) {
                unconfirmed++;
                continue;
            }
            if (c.getDueAt() != null && !c.getDueAt().isAfter(now)) {
                due++;
            }
            if (c.getLevel() != null && c.getLevel() >= 3) {
                learned++;
            }
        }
        return new CardStats(all.size(), unconfirmed, due, learned);
    }

    /** 确认（AI 生成的卡要人过一眼才进复习队列） */
    @Transactional
    public int confirm(Long bankId, List<Long> cardIds, boolean confirmed) {
        int n = 0;
        for (Card c : cardsOf(bankId, cardIds)) {
            c.setConfirmed(confirmed);
            // 刚确认 → 立刻可复习（due=now）；取消确认 → 移出队列
            c.setDueAt(confirmed ? LocalDateTime.now() : null);
            c.setUpdatedAt(LocalDateTime.now());
            cardMapper.updateById(c);
            n++;
        }
        return n;
    }

    @Transactional
    public CardView update(Long bankId, Long cardId, String front, String back) {
        Card card = cardMapper.selectById(cardId);
        if (card == null || !bankId.equals(card.getBankId())) {
            throw new IllegalArgumentException("卡片不存在：" + cardId);
        }
        if (front != null && !front.isBlank()) {
            card.setFront(front.trim());
        }
        if (back != null && !back.isBlank()) {
            card.setBack(back.trim());
        }
        // 手动改过的卡视为人工确认（自己写的总不能不进队列）
        card.setSource(Card.SOURCE_USER);
        if (!Boolean.TRUE.equals(card.getConfirmed())) {
            card.setConfirmed(true);
            card.setDueAt(LocalDateTime.now());
        }
        card.setUpdatedAt(LocalDateTime.now());
        cardMapper.updateById(card);
        return views(List.of(card), null).get(0);
    }

    @Transactional
    public int delete(Long bankId, List<Long> cardIds) {
        int n = 0;
        for (Card c : cardsOf(bankId, cardIds)) {
            cardMapper.deleteById(c.getId());
            n++;
        }
        return n;
    }

    /**
     * 复习一张卡：记得 → 升一级（间隔 = min(2^level, 30) 天）；忘记 → 归零、立刻重来、lapses+1。
     * 只有确认过的卡才会走到这里（未确认的不进队列）。
     */
    @Transactional
    public CardView review(Long bankId, Long cardId, boolean remembered) {
        Card card = cardMapper.selectById(cardId);
        if (card == null || !bankId.equals(card.getBankId())) {
            throw new IllegalArgumentException("卡片不存在：" + cardId);
        }
        if (remembered) {
            card.setLevel(Math.min((card.getLevel() == null ? 0 : card.getLevel()) + 1, 5));
            card.setIntervalDays(Math.min(1 << card.getLevel(), 30));
            card.setDueAt(LocalDateTime.now().plusDays(card.getIntervalDays()));
        } else {
            card.setLevel(0);
            card.setIntervalDays(1);
            card.setDueAt(LocalDateTime.now());
            card.setLapses((card.getLapses() == null ? 0 : card.getLapses()) + 1);
        }
        card.setLastResult(remembered ? Card.RESULT_REMEMBERED : Card.RESULT_FORGOT);
        card.setLastReviewedAt(LocalDateTime.now());
        card.setUpdatedAt(LocalDateTime.now());
        cardMapper.updateById(card);
        return views(List.of(card), null).get(0);
    }

    /**
     * 某题最近一次的卡片自评（供掌握度计算当**负向证据**用）：
     * 返回 [时间, 是否记得]；没有复习记录时返回 null。
     */
    public Object[] lastCardResultOf(Long questionId) {
        Card card = cardMapper.selectOne(new LambdaQueryWrapper<Card>()
                .eq(Card::getQuestionId, questionId)
                .isNotNull(Card::getLastReviewedAt)
                .orderByDesc(Card::getLastReviewedAt)
                .last("LIMIT 1"));
        if (card == null) {
            return null;
        }
        return new Object[]{card.getLastReviewedAt(), Card.RESULT_REMEMBERED.equals(card.getLastResult())};
    }

    /** 某节点下所有卡片最近一次自评里"最晚的那条"（节点降级判定用） */
    public Map<Long, Object[]> lastCardResultsOfQuestions(List<Long> questionIds) {
        Map<Long, Object[]> out = new LinkedHashMap<>();
        if (questionIds.isEmpty()) {
            return out;
        }
        for (Card card : cardMapper.selectList(new LambdaQueryWrapper<Card>()
                .in(Card::getQuestionId, questionIds)
                .isNotNull(Card::getLastReviewedAt))) {
            Object[] existing = out.get(card.getQuestionId());
            if (existing == null || ((LocalDateTime) existing[0]).isBefore(card.getLastReviewedAt())) {
                out.put(card.getQuestionId(), new Object[]{card.getLastReviewedAt(),
                        Card.RESULT_REMEMBERED.equals(card.getLastResult())});
            }
        }
        return out;
    }

    // ==================== 工具 ====================

    private Set<Long> questionIdsOfNode(Long bankId, String templateId, String nodeId) {
        LambdaQueryWrapper<QuestionSkill> w = new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getShadowed, false);
        if (templateId != null && !templateId.isBlank()) {
            w.eq(QuestionSkill::getTemplateId, templateId);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            w.eq(QuestionSkill::getNodeId, nodeId);
        }
        return new LinkedHashSet<>(questionSkillMapper.selectList(w).stream()
                .map(QuestionSkill::getQuestionId).toList());
    }

    private List<Card> cardsOf(Long bankId, List<Long> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) {
            return List.of();
        }
        return cardMapper.selectList(new LambdaQueryWrapper<Card>()
                .eq(Card::getBankId, bankId)
                .in(Card::getId, cardIds));
    }

    private List<CardView> views(List<Card> cards, String templateId) {
        if (cards.isEmpty()) {
            return List.of();
        }
        Set<Long> qids = new HashSet<>();
        for (Card c : cards) {
            qids.add(c.getQuestionId());
        }
        Map<Long, Question> questions = new HashMap<>();
        for (Question q : questionMapper.selectList(new LambdaQueryWrapper<Question>().in(Question::getId, qids))) {
            questions.put(q.getId(), q);
        }
        Map<String, String> nodeNames = new HashMap<>();
        if (templateId != null && graphService.has(templateId)) {
            for (SkillGraphService.NodeView n : graphService.template(templateId).nodes()) {
                nodeNames.put(n.nodeId(), n.name());
            }
        }
        List<CardView> out = new ArrayList<>();
        for (Card c : cards) {
            Question q = questions.get(c.getQuestionId());
            out.add(new CardView(c.getId(), c.getQuestionId(), q == null ? null : q.getQuestionNumber(),
                    c.getNodeId(), nodeNames.getOrDefault(c.getNodeId(), c.getNodeId()),
                    c.getFront(), c.getBack(), c.getSource(), Boolean.TRUE.equals(c.getConfirmed()),
                    c.getLevel() == null ? 0 : c.getLevel(), c.getIntervalDays() == null ? 1 : c.getIntervalDays(),
                    c.getDueAt(), c.getLapses() == null ? 0 : c.getLapses(), c.getLastResult()));
        }
        return out;
    }

    private JsonNode parseJson(String reply) {
        String text = reply == null ? "" : reply.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        int brace = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (brace >= 0 && last > brace) {
            text = text.substring(brace, last + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("闪卡生成返回不是合法 JSON，按空结果处理：{}", oneLine(reply, 120));
            return objectMapper.createObjectNode();
        }
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static final String CARD_SYSTEM = """
            你是题库应用的闪卡助手。你把一道题的解析提炼成挖空卡，用来考记忆（回忆），而不是再认。
            原则：
            - 只挖「记住就能做对」的关键内容：公式、结论、判别要点、易错点；
            - front 必须能独立看懂（不要写「上题的公式是____」这类离开原题就没法理解的句子）；
            - back 简短、准确，不复制整段解析；
            - 只输出 JSON 对象。""";
}
