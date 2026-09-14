package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.QuestionSkillMapper;
import com.tiku.mapper.SkillGroupMapMapper;
import com.tiku.model.Question;
import com.tiku.model.QuestionSkill;
import com.tiku.model.SkillGroupMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 题目知识点标注（学习路径引擎阶段 0，设计见 docs/learning-path-design.md §4）。
 *
 * <b>两级映射</b>（关键设计）：
 *   ① 先按作者填写的 topic / category 分组（题库里通常只有 10–30 个唯一值）；
 *   ② 一次 AI 调用把这批"分组名"映射到技能节点，结果落 {@code skill_group_map} 缓存；
 *   ③ 组内所有题继承该映射（保证**同类题标签一致**——逐题判定最容易出现"同类题标签不一致"，
 *      那是最危险的漏刷来源）；
 *   ④ 没有 topic/category 的题（可选）再走逐题判定。
 *
 * 缓存的意义：命中缓存就**不再调 AI**（换机器/重跑都是免费的），且 `skill_group_map` 同时是
 * **用户批量纠错的单位**（改一组 = 改一类题）。
 *
 * 所有 AI 结果都是"建议"：{@code source='ai', confirmed=0}，进待确认队列；
 * 门控只认「已确认」或「高置信 AI」标签（{@link QuestionSkill#GATE_MIN_CONFIDENCE}）。
 */
@Slf4j
@Service
public class QuestionTaggingService {

    /** 一次 AI 调用最多带多少个分组（控制 prompt 体积与失败面） */
    private static final int GROUPS_PER_CALL = 40;
    /** 逐题判定时每批题数 */
    private static final int QUESTIONS_PER_CALL = 20;
    /** 每组分给模型的样例题干数与长度（样例能显著提高映射准确率，成本很低） */
    private static final int SAMPLES_PER_GROUP = 2;
    private static final int SAMPLE_CHARS = 60;
    /** 单题最多挂几个节点（多标签降低误标风险，但过多会污染掌握度） */
    private static final int MAX_NODES_PER_QUESTION = 4;
    /** 默认单次请求允许的 AI 调用上限（防一次点击烧太多额度） */
    public static final int DEFAULT_MAX_AI_CALLS = 40;

    private static final String SOURCE_AI = "ai";
    private static final String SOURCE_USER = "user";
    private static final String ORIGIN_TOPIC_MAP = "topic-map";
    private static final String ORIGIN_AI_DIRECT = "ai-direct";
    private static final String ORIGIN_MANUAL = "manual";

    private final QuestionMapper questionMapper;
    private final QuestionSkillMapper questionSkillMapper;
    private final SkillGroupMapMapper groupMapMapper;
    private final SkillGraphService graphService;
    private final AiClientService aiClientService;
    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper;

    public QuestionTaggingService(QuestionMapper questionMapper, QuestionSkillMapper questionSkillMapper,
                                  SkillGroupMapMapper groupMapMapper, SkillGraphService graphService,
                                  AiClientService aiClientService, AiConfigService aiConfigService,
                                  ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.questionSkillMapper = questionSkillMapper;
        this.groupMapMapper = groupMapMapper;
        this.graphService = graphService;
        this.aiClientService = aiClientService;
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
    }

    // ==================== 视图模型 ====================

    /** 一个分组（作者 topic/category）映射到的节点 */
    public record NodeConfidence(String nodeId, String name, double confidence) {
    }

    /** 分组 → 节点映射（第二级结果） */
    public record GroupMapping(String groupKey, String display, int questionCount, List<NodeConfidence> nodes) {
    }

    public record SuggestResult(String templateId, String graphVersion, int groupCount, int mappedGroups,
                                int cachedGroups, int aiCalls, int taggedQuestions, int untaggedQuestions,
                                boolean truncated, String message) {
    }

    /** 待确认队列里的一项：一个节点下有哪些题（来自哪些分组） */
    public record PendingNode(String nodeId, String name, int questionCount, double avgConfidence,
                              List<String> groups, List<SampleQuestion> samples) {
    }

    /** 队列里的样例题（供 UI 让人一眼判断"这些题确实属于这个知识点吗"） */
    public record SampleQuestion(Long questionId, String preview) {
    }

    /** 单题标签（题目详情/编辑用） */
    public record QuestionTag(String nodeId, String name, String source, double confidence, boolean confirmed,
                              String origin) {
    }

    public record Coverage(String templateId, String graphVersion, int totalQuestions, int coveredQuestions,
                           int confirmedQuestions, int untaggedQuestions, List<NodeCoverage> nodes) {
    }

    public record NodeCoverage(String nodeId, String name, String stageName, int questionCount,
                               boolean evidenceEnough, boolean confirmed) {
    }

    // ==================== 打标签 ====================

    @Transactional
    public SuggestResult suggest(Long bankId, String templateId, boolean includeUntagged, Integer maxAiCalls) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        AiSettings settings = requireSettings();
        int callBudget = maxAiCalls == null || maxAiCalls <= 0 ? DEFAULT_MAX_AI_CALLS : maxAiCalls;

        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId)
                .select(Question::getId, Question::getContent, Question::getTopic, Question::getCategory,
                        Question::getQuestionType));
        if (questions.isEmpty()) {
            return new SuggestResult(templateId, template.graphVersion(), 0, 0, 0, 0, 0, 0, false, "题库里没有题目");
        }

        // 分组：topic 优先，其次 category（保留原始写法用于展示与纠错）
        Map<String, List<Question>> groups = new LinkedHashMap<>();
        List<Question> untagged = new ArrayList<>();
        for (Question q : questions) {
            String key = groupKeyOf(q);
            if (key == null) {
                untagged.add(q);
            } else {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
            }
        }

        // 清掉上一次未确认的 AI 建议（已确认的与用户标注保留）
        questionSkillMapper.deleteAiSuggestions(bankId, templateId);
        groupMapMapper.deleteStaleVersions(templateId, template.graphVersion());

        // 已被人工标注（用户修正 / 作者标注）的题：AI 不再插嘴——人工优先，也避免多标签污染掌握度
        Set<Long> humanTagged = new LinkedHashSet<>(questionSkillMapper.selectHumanTaggedQuestionIds(bankId));

        int aiCalls = 0;
        int cached = 0;
        Map<String, List<NodeConfidence>> resolved = new LinkedHashMap<>();
        List<String> toAsk = new ArrayList<>();
        for (String key : groups.keySet()) {
            SkillGroupMap cachedMap = groupMapMapper.selectByGroup(templateId, key);
            if (cachedMap != null && template.graphVersion().equals(cachedMap.getGraphVersion())) {
                resolved.put(key, parseNodes(cachedMap.getNodesJson(), template));
                cached++;
            } else {
                toAsk.add(key);
            }
        }

        boolean truncated = false;
        for (int i = 0; i < toAsk.size(); i += GROUPS_PER_CALL) {
            if (aiCalls >= callBudget) {
                truncated = true;
                break;
            }
            List<String> batch = toAsk.subList(i, Math.min(toAsk.size(), i + GROUPS_PER_CALL));
            Map<String, List<NodeConfidence>> mapped;
            try {
                mapped = askGroups(settings, template, batch, groups);
                aiCalls++;
            } catch (Exception e) {
                log.warn("题库 {} 的分组映射调用失败（该批跳过）：{}", bankId, e.getMessage());
                continue;
            }
            for (String key : batch) {
                List<NodeConfidence> nodes = mapped.getOrDefault(key, List.of());
                resolved.put(key, nodes);
                upsertGroupMap(templateId, template.graphVersion(), key, nodes);
            }
        }

        // 展开到题目：组内所有题继承组映射
        int tagged = 0;
        for (Map.Entry<String, List<Question>> e : groups.entrySet()) {
            List<NodeConfidence> nodes = resolved.get(e.getKey());
            if (nodes == null || nodes.isEmpty()) {
                continue;
            }
            for (Question q : e.getValue()) {
                if (humanTagged.contains(q.getId())) {
                    continue;
                }
                if (writeQuestionSkills(bankId, templateId, q.getId(), nodes, ORIGIN_TOPIC_MAP)) {
                    tagged++;
                }
            }
        }

        // 可选：没有 topic/category 的题逐题判定（人工标注过的题不参与，省 token 也避免污染）
        List<Question> untaggedToAsk = untagged.stream().filter(q -> !humanTagged.contains(q.getId())).toList();
        if (includeUntagged && !untaggedToAsk.isEmpty()) {
            for (int i = 0; i < untaggedToAsk.size(); i += QUESTIONS_PER_CALL) {
                if (aiCalls >= callBudget) {
                    truncated = true;
                    break;
                }
                List<Question> batch = List.copyOf(untaggedToAsk.subList(i, Math.min(untaggedToAsk.size(), i + QUESTIONS_PER_CALL)));
                Map<Long, List<NodeConfidence>> mapped;
                try {
                    mapped = askQuestions(settings, template, batch);
                    aiCalls++;
                } catch (Exception ex) {
                    log.warn("题库 {} 的逐题判定失败（该批跳过）：{}", bankId, ex.getMessage());
                    continue;
                }
                for (Question q : batch) {
                    List<NodeConfidence> nodes = mapped.getOrDefault(q.getId(), List.of());
                    if (writeQuestionSkills(bankId, templateId, q.getId(), nodes, ORIGIN_AI_DIRECT)) {
                        tagged++;
                    }
                }
            }
        }

        // 未标注数以**数据库现状**为准：模型漏答、返回无法解析、节点被丢弃的题都要如实算作未标注
        int untaggedLeft = questionSkillMapper.countQuestionsWithoutSkills(bankId, templateId);
        log.info("题库 {} 打标签完成：分组 {}（命中缓存 {}），AI 调用 {}，已标注 {} 题，未标注 {} 题{}",
                bankId, groups.size(), cached, aiCalls, tagged, untaggedLeft, truncated ? "（达到调用上限，未跑完）" : "");
        return new SuggestResult(templateId, template.graphVersion(), groups.size(), resolved.size(), cached,
                aiCalls, tagged, untaggedLeft, truncated,
                truncated ? "已达到本次 AI 调用上限，剩余分组未处理，可再次点击继续" : "分析完成");
    }

    /** 一次调用：把一批分组名映射到技能节点（带每组的样例题干，显著提高准确率） */
    private Map<String, List<NodeConfidence>> askGroups(AiSettings settings, SkillGraphService.SkillTemplate template,
                                                        List<String> batch, Map<String, List<Question>> groups) {
        StringBuilder user = new StringBuilder();
        user.append("【可选技能节点】\n").append(graphService.nodeCatalogForPrompt(template)).append('\n');
        user.append("【待归类分组】每题库作者填写的主题/分类名，括号内是该组的样例题干\n");
        for (String key : batch) {
            user.append("- ").append(displayOf(key));
            List<Question> qs = groups.getOrDefault(key, List.of());
            if (!qs.isEmpty()) {
                user.append("（");
                for (int i = 0; i < Math.min(SAMPLES_PER_GROUP, qs.size()); i++) {
                    if (i > 0) {
                        user.append("；");
                    }
                    user.append(truncate(oneLine(qs.get(i).getContent()), SAMPLE_CHARS));
                }
                user.append("）");
            }
            user.append('\n');
        }
        user.append('\n').append("""
                要求：
                1. 每个分组给 1–3 个最匹配的节点，nodeId 必须来自上面的清单，不要发明新节点；
                2. confidence 用 0–1 表示你的把握（样例与节点名明显对应就给 0.85 以上）；
                3. 确实没有合适节点时给空数组，不要硬套；
                4. 只输出 JSON，不要解释、不要代码块。

                输出格式：{"mappings":[{"group":"分组名","nodes":[{"nodeId":"...","confidence":0.9}]}]}
                """);

        String reply = aiClientService.chat(settings, SYSTEM_PROMPT, user.toString(), true);
        Map<String, List<NodeConfidence>> result = new HashMap<>();
        for (JsonNode m : parseJson(reply).path("mappings")) {
            String group = m.path("group").asText("");
            if (group.isBlank()) {
                continue;
            }
            result.put(matchKey(batch, group), readNodes(m.path("nodes"), template));
        }
        return result;
    }

    /** 逐题判定（没有 topic/category 的题） */
    private Map<Long, List<NodeConfidence>> askQuestions(AiSettings settings, SkillGraphService.SkillTemplate template,
                                                         List<Question> batch) {
        StringBuilder user = new StringBuilder();
        user.append("【可选技能节点】\n").append(graphService.nodeCatalogForPrompt(template)).append("\n\n");
        user.append("【待归类题目】\n");
        for (Question q : batch) {
            user.append("- id=").append(q.getId())
                    .append(" 题型=").append(q.getQuestionType())
                    .append(" 题干=").append(truncate(oneLine(q.getContent()), 160))
                    .append('\n');
        }
        user.append('\n').append("""
                要求：
                1. 每题给 1–3 个最匹配的节点，nodeId 必须来自上面的清单；
                2. confidence 用 0–1 表示把握；题干信息不足就给低置信度；
                3. 只输出 JSON，不要解释、不要代码块。

                输出格式：{"mappings":[{"id":123,"nodes":[{"nodeId":"...","confidence":0.8}]}]}
                """);
        String reply = aiClientService.chat(settings, SYSTEM_PROMPT, user.toString(), true);
        Map<Long, List<NodeConfidence>> result = new HashMap<>();
        for (JsonNode m : parseJson(reply).path("mappings")) {
            long id = m.path("id").asLong(0);
            if (id > 0) {
                result.put(id, readNodes(m.path("nodes"), template));
            }
        }
        return result;
    }

    private static final String SYSTEM_PROMPT = """
            你是题库的知识点标注助手。你的任务是把"分组名"或"题目"对应到给定的技能节点清单上。
            规则：nodeId 必须严格来自清单；不确定就给低置信度；宁缺毋滥，不要为凑数而硬套。
            只输出 JSON。""";

    private List<NodeConfidence> readNodes(JsonNode nodes, SkillGraphService.SkillTemplate template) {
        List<NodeConfidence> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : nodes) {
            String nodeId = n.path("nodeId").asText("").trim();
            if (nodeId.isEmpty() || !seen.add(nodeId)) {
                continue;
            }
            if (!template.nodeIds().contains(nodeId)) {
                log.debug("忽略技能图里不存在的节点建议：{}", nodeId);
                continue;
            }
            double confidence = n.path("confidence").asDouble(0.5);
            confidence = Math.max(0, Math.min(1, confidence));
            out.add(new NodeConfidence(nodeId, nameOf(template, nodeId), confidence));
            if (out.size() >= MAX_NODES_PER_QUESTION) {
                break;
            }
        }
        out.sort(Comparator.comparingDouble(NodeConfidence::confidence).reversed());
        return out;
    }

    /** 写题目标签（同一题同一节点一行；AI 建议不覆盖用户/作者标注） */
    private boolean writeQuestionSkills(Long bankId, String templateId, Long questionId,
                                        List<NodeConfidence> nodes, String origin) {
        boolean wrote = false;
        for (NodeConfidence n : nodes) {
            QuestionSkill existing = questionSkillMapper.selectOne(new LambdaQueryWrapper<QuestionSkill>()
                    .eq(QuestionSkill::getQuestionId, questionId)
                    .eq(QuestionSkill::getNodeId, n.nodeId()));
            if (existing != null) {
                // 人工标注优先：AI 建议不覆盖 user/author
                if (SOURCE_USER.equals(existing.getSource()) || "author".equals(existing.getSource())) {
                    continue;
                }
                existing.setConfidence(Math.max(n.confidence(), existing.getConfidence() == null ? 0 : existing.getConfidence()));
                existing.setOrigin(origin);
                existing.setUpdatedAt(LocalDateTime.now());
                questionSkillMapper.updateById(existing);
                wrote = true;
                continue;
            }
            QuestionSkill row = new QuestionSkill();
            row.setQuestionId(questionId);
            row.setBankId(bankId);
            row.setNodeId(n.nodeId());
            row.setTemplateId(templateId);
            row.setSource(SOURCE_AI);
            row.setConfidence(n.confidence());
            row.setConfirmed(false);
            row.setOrigin(origin);
            row.setShadowed(false);
            row.setUpdatedAt(LocalDateTime.now());
            questionSkillMapper.insert(row);
            wrote = true;
        }
        return wrote;
    }

    private void upsertGroupMap(String templateId, String graphVersion, String groupKey, List<NodeConfidence> nodes) {
        SkillGroupMap existing = groupMapMapper.selectByGroup(templateId, groupKey);
        try {
            String json = objectMapper.writeValueAsString(nodes);
            if (existing == null) {
                SkillGroupMap row = new SkillGroupMap();
                row.setTemplateId(templateId);
                row.setGroupKey(groupKey);
                row.setGraphVersion(graphVersion);
                row.setNodesJson(json);
                row.setSource(SOURCE_AI);
                row.setUpdatedAt(LocalDateTime.now());
                groupMapMapper.insert(row);
            } else {
                existing.setGraphVersion(graphVersion);
                existing.setNodesJson(json);
                existing.setSource(SOURCE_AI);
                existing.setUpdatedAt(LocalDateTime.now());
                groupMapMapper.updateById(existing);
            }
        } catch (Exception e) {
            log.warn("写入分组映射失败：{} → {}", groupKey, e.getMessage());
        }
    }

    private List<NodeConfidence> parseNodes(String json, SkillGraphService.SkillTemplate template) {
        List<NodeConfidence> out = new ArrayList<>();
        try {
            for (JsonNode n : objectMapper.readTree(json)) {
                String nodeId = n.path("nodeId").asText("");
                if (template.nodeIds().contains(nodeId)) {
                    out.add(new NodeConfidence(nodeId, nameOf(template, nodeId), n.path("confidence").asDouble(0.5)));
                }
            }
        } catch (Exception e) {
            log.warn("分组映射缓存解析失败（按空处理，下次会重算）：{}", e.getMessage());
        }
        return out;
    }

    // ==================== 待确认队列 / 确认 / 覆盖 ====================

    /** 待确认队列：按节点聚合（AI 未确认的建议） */
    public List<PendingNode> pending(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        List<QuestionSkill> rows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getSource, SOURCE_AI)
                .eq(QuestionSkill::getConfirmed, false)
                .eq(QuestionSkill::getShadowed, false)
                .select(QuestionSkill::getQuestionId, QuestionSkill::getNodeId,
                        QuestionSkill::getConfidence, QuestionSkill::getOrigin));
        Map<String, List<QuestionSkill>> byNode = new LinkedHashMap<>();
        for (QuestionSkill r : rows) {
            byNode.computeIfAbsent(r.getNodeId(), k -> new ArrayList<>()).add(r);
        }
        List<PendingNode> out = new ArrayList<>();
        byNode.forEach((nodeId, list) -> {
            double avg = list.stream().mapToDouble(r -> r.getConfidence() == null ? 0 : r.getConfidence()).average().orElse(0);
            Set<String> origins = new LinkedHashSet<>();
            for (QuestionSkill r : list) {
                if (r.getOrigin() != null) {
                    origins.add(r.getOrigin());
                }
            }
            List<Long> ids = list.stream().map(QuestionSkill::getQuestionId).distinct().limit(3).toList();
            out.add(new PendingNode(nodeId, nameOf(template, nodeId), list.size(),
                    Math.round(avg * 100) / 100.0, List.copyOf(origins), previewsOf(ids)));
        });
        out.sort(Comparator.comparingInt(PendingNode::questionCount).reversed());
        return out;
    }

    /** 样例题干（截断）：让用户在确认前能核对，而不是盲点"确认" */
    private List<SampleQuestion> previewsOf(List<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return List.of();
        }
        List<Question> qs = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .in(Question::getId, questionIds)
                .select(Question::getId, Question::getContent));
        List<SampleQuestion> out = new ArrayList<>();
        for (Question q : qs) {
            out.add(new SampleQuestion(q.getId(), truncate(oneLine(q.getContent()), 80)));
        }
        return out;
    }

    /** 单题当前标签（含来源，供题目详情展示与用户修改） */
    public List<QuestionTag> tagsOf(Long questionId) {
        List<QuestionSkill> rows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getQuestionId, questionId)
                .eq(QuestionSkill::getShadowed, false));
        List<QuestionTag> out = new ArrayList<>();
        for (QuestionSkill r : rows) {
            SkillGraphService.SkillTemplate t = graphService.has(r.getTemplateId())
                    ? graphService.template(r.getTemplateId()) : null;
            out.add(new QuestionTag(r.getNodeId(), t == null ? r.getNodeId() : nameOf(t, r.getNodeId()),
                    r.getSource(), r.getConfidence() == null ? 0 : r.getConfidence(),
                    Boolean.TRUE.equals(r.getConfirmed()), r.getOrigin()));
        }
        out.sort(Comparator.comparing(QuestionTag::nodeId));
        return out;
    }

    /**
     * 用户手动设定某题的标签（**覆盖** AI 与作者标注）：写成 source=user、confirmed=1。
     * 这是"把标签权力交给用户"的最小实现——用户修正只影响本机，不回写作者的内容包。
     */
    @Transactional
    public int setUserTags(Long questionId, String templateId, List<String> nodeIds) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("题目不存在：" + questionId);
        }
        List<String> target = nodeIds == null ? List.of() : nodeIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        for (String n : target) {
            if (!template.nodeIds().contains(n)) {
                throw new IllegalArgumentException("技能节点不存在：" + n);
            }
        }
        // 清掉该题在**所有模板**下的 AI 建议（用户已给出正确答案，AI 猜测没有意义）
        questionSkillMapper.delete(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getQuestionId, questionId)
                .eq(QuestionSkill::getSource, SOURCE_AI));
        // 清掉同模板下的旧用户标注（重新设定）
        questionSkillMapper.delete(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getQuestionId, questionId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getSource, SOURCE_USER));
        int n = 0;
        for (String nodeId : target) {
            QuestionSkill row = new QuestionSkill();
            row.setQuestionId(questionId);
            row.setBankId(question.getBankId());
            row.setNodeId(nodeId);
            row.setTemplateId(templateId);
            row.setSource(SOURCE_USER);
            row.setConfidence(1.0);
            row.setConfirmed(true);
            row.setOrigin(ORIGIN_MANUAL);
            row.setShadowed(false);
            row.setUpdatedAt(LocalDateTime.now());
            questionSkillMapper.insert(row);
            n++;
        }
        return n;
    }

    /**
     * 批量确认 / 改节点 / 拒绝：
     * - action=confirm：把该节点下 AI 建议置为已确认（一条 SQL 级别的批量，前端一次点击）；
     * - action=retag：把这些题改挂到给定节点（写成 source=user，覆盖 AI 与作者）；
     * - action=reject：删除该节点下的 AI 建议。
     */
    @Transactional
    public int apply(Long bankId, String templateId, String action, String nodeId,
                     List<String> newNodes, List<Long> questionIds) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        if ("reject".equals(action)) {
            int n = 0;
            for (QuestionSkill r : selectAiRows(bankId, templateId, nodeId)) {
                questionSkillMapper.deleteById(r.getId());
                n++;
            }
            return n;
        }
        if ("retag".equals(action)) {
            List<String> target = newNodes == null ? List.of() : newNodes;
            for (String t : target) {
                if (!template.nodeIds().contains(t)) {
                    throw new IllegalArgumentException("技能节点不存在：" + t);
                }
            }
            List<Long> ids = questionIds == null || questionIds.isEmpty()
                    ? selectAiRows(bankId, templateId, nodeId).stream().map(QuestionSkill::getQuestionId).distinct().toList()
                    : questionIds;
            int n = 0;
            for (Long qid : ids) {
                questionSkillMapper.delete(new LambdaQueryWrapper<QuestionSkill>()
                        .eq(QuestionSkill::getQuestionId, qid)
                        .eq(QuestionSkill::getSource, SOURCE_AI));
                for (String t : target) {
                    QuestionSkill row = new QuestionSkill();
                    row.setQuestionId(qid);
                    row.setBankId(bankId);
                    row.setNodeId(t);
                    row.setTemplateId(templateId);
                    row.setSource(SOURCE_USER);
                    row.setConfidence(1.0);
                    row.setConfirmed(true);
                    row.setOrigin(ORIGIN_MANUAL);
                    row.setShadowed(false);
                    row.setUpdatedAt(LocalDateTime.now());
                    questionSkillMapper.insert(row);
                    n++;
                }
            }
            return n;
        }
        // confirm
        List<QuestionSkill> rows = selectAiRows(bankId, templateId, nodeId);
        for (QuestionSkill r : rows) {
            r.setConfirmed(true);
            r.setUpdatedAt(LocalDateTime.now());
            questionSkillMapper.updateById(r);
        }
        return rows.size();
    }

    private List<QuestionSkill> selectAiRows(Long bankId, String templateId, String nodeId) {
        LambdaQueryWrapper<QuestionSkill> w = new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getSource, SOURCE_AI)
                .eq(QuestionSkill::getShadowed, false);
        if (nodeId != null && !nodeId.isBlank()) {
            w.eq(QuestionSkill::getNodeId, nodeId);
        }
        return questionSkillMapper.selectList(w);
    }

    /** 覆盖地图：路线节点在题库里的题量（<3 视为证据不足，不参与门控） */
    public Coverage coverage(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        int total = Math.toIntExact(questionMapper.selectCount(new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId)));
        int covered = questionSkillMapper.countCoveredQuestions(bankId, templateId);
        int confirmedQuestions = questionSkillMapper.countConfirmedQuestions(bankId, templateId);
        List<NodeCoverage> nodes = new ArrayList<>();
        for (SkillGraphService.NodeView n : template.nodes()) {
            int count = questionSkillMapper.countCoveredQuestionsInNode(bankId, templateId, n.nodeId());
            nodes.add(new NodeCoverage(n.nodeId(), n.name(), n.stageName(), count, count >= 3, count > 0));
        }
        return new Coverage(templateId, template.graphVersion(), total, covered,
                Math.min(confirmedQuestions, covered), Math.max(0, total - covered), nodes);
    }

    // ==================== 工具 ====================

    /** 分组键：topic 优先，其次 category；都没有则 null（进"未分类"池，仍正常调度） */
    static String groupKeyOf(Question q) {
        String topic = oneLine(q.getTopic());
        if (!topic.isEmpty()) {
            return truncate("T:" + topic, 160);
        }
        String category = oneLine(q.getCategory());
        if (!category.isEmpty()) {
            return truncate("C:" + category, 160);
        }
        return null;
    }

    static String displayOf(String groupKey) {
        return groupKey.length() > 2 && (groupKey.startsWith("T:") || groupKey.startsWith("C:"))
                ? groupKey.substring(2) : groupKey;
    }

    private static String matchKey(List<String> batch, String groupFromAi) {
        String target = groupFromAi.trim();
        for (String key : batch) {
            if (displayOf(key).equals(target) || key.equals(target)) {
                return key;
            }
        }
        return target;
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 解析模型回复：容忍 ```json 代码块与前后废话 */
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
            log.warn("模型返回不是合法 JSON，按空结果处理：{}", truncate(oneLine(reply), 120));
            return objectMapper.createObjectNode();
        }
    }

    private AiSettings requireSettings() {
        if (!aiConfigService.isConfigured()) {
            throw new IllegalStateException("尚未配置 AI 模型，无法自动标注知识点（可在设置页配置后再试）");
        }
        return aiConfigService.load();
    }

    private static String nameOf(SkillGraphService.SkillTemplate template, String nodeId) {
        for (SkillGraphService.NodeView n : template.nodes()) {
            if (n.nodeId().equals(nodeId)) {
                return n.name();
            }
        }
        return nodeId;
    }

    /** 内容指纹（未来做"题目没变就不重算"的缓存键；阶段 0 先留着工具方法） */
    static String contentHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(content == null ? 0 : content.hashCode());
        }
    }
}
