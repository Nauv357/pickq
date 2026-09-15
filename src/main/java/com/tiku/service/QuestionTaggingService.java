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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    /**
     * 一次分析的结果。withoutUsableTag = **还没有"可用于判定掌握"的标签**的题数（已确认 或 AI 高置信），
     * 界面用它显示进度；注意它与 {@link Coverage#untaggedQuestions()}（一条标签都没有）不是一回事，
     * 两个同名不同义的数并列展示正是用户实测时"26 与 16 对不上"的来源。
     */
    public record SuggestResult(String templateId, String graphVersion, int groupCount, int mappedGroups,
                                int cachedGroups, int aiCalls, int taggedQuestions, int withoutUsableTag,
                                boolean truncated, String message) {
    }

    /**
     * 审阅清单里的一题：状态 + 当前标签（含来源与把握）。
     *
     * 为什么不再按节点聚合、也不再只给三条样例题干：用户实测反馈"每个分类下只简略显示题目简单信息，
     * 我根本分辨不出来是哪一题，在此界面可以说完全无法对题目精确分配 tag"。所以清单以**题**为单位，
     * 每题带上它的全部标签，界面可以就地改。
     */
    public record ReviewQuestion(Long questionId, Integer questionNumber, String type, String preview,
                                 String status, List<QuestionTag> tags) {
    }

    /** 审阅清单分页 + **同一份快照**算出来的计数（数字与清单不可能打架） */
    public record ReviewPage(int total, int page, int size, Counts counts, OrphanInfo orphan,
                             List<ReviewQuestion> records) {
    }

    /** 四段（含"可用于判定掌握"）计数，全部由同一份快照派生 */
    public record Counts(int total, int confirmed, int pending, int untagged, int usable) {
    }

    /** 单题标签（题目详情/编辑用） */
    public record QuestionTag(String nodeId, String name, String source, double confidence, boolean confirmed,
                              String origin) {
    }

    /**
     * 覆盖统计。
     *
     * ⚠️ confirmed / pending / untagged **三段不重叠**，相加 = totalQuestions：
     * 之前把"没有可用标签的题数"（26）和"未确认建议按节点聚合后的条目数"（16）并列展示，
     * 两个口径不同、看着互相矛盾，用户无法判断还剩多少题没处理（实测反馈）。
     * usableQuestions 是另一个口径：门控真正可用的题数（已确认 或 AI 高置信）。
     */
    public record Coverage(String templateId, String graphVersion, int totalQuestions,
                           int confirmedQuestions, int pendingQuestions, int untaggedQuestions,
                           int usableQuestions, List<NodeCoverage> nodes) {
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

        // 已有标签的题（含低置信建议）：分批续跑时跳过，避免每次从头重算白烧 token。
        // ⚠️ 必须在任何清理之前读——否则刚打完的那一批会被当成"没打过"而重复处理。
        Set<Long> humanTagged = new LinkedHashSet<>(questionSkillMapper.selectHumanTaggedQuestionIds(bankId));
        Set<Long> alreadyTagged = new LinkedHashSet<>(questionSkillMapper.selectTaggedQuestionIds(bankId, templateId));

        // 只清理"引用了已不在技能图里的节点"的旧建议（技能图更新后的兜底），不做全局删除
        questionSkillMapper.deleteAiSuggestionsWithUnknownNodes(bankId, templateId, template.nodeIds());
        groupMapMapper.deleteStaleVersions(templateId, template.graphVersion());

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

        // 可选：没有 topic/category 的题逐题判定（人工标注过、或已有标签的题跳过：
        // 前者人工优先，后者是分批续跑——已处理过的题不再重复问模型）
        List<Question> untaggedToAsk = untagged.stream()
                .filter(q -> !humanTagged.contains(q.getId()) && !alreadyTagged.contains(q.getId()))
                .toList();
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

        // 未标注数以**数据库现状**为准：模型漏答、返回无法解析、节点被丢弃的题都要如实算作未标注。
        // 口径 = 还没有"可用于判定掌握的标签"的题数（与覆盖页的 usable 互补），界面按它的减少显示进度
        Counts after = countsOf(snapshot(bankId, templateId));
        int withoutUsableTag = after.total() - after.usable();
        log.info("题库 {} 打标签完成：分组 {}（命中缓存 {}），AI 调用 {}，已标注 {} 题，未标注 {} 题{}",
                bankId, groups.size(), cached, aiCalls, tagged, withoutUsableTag, truncated ? "（达到调用上限，未跑完）" : "");
        return new SuggestResult(templateId, template.graphVersion(), groups.size(), resolved.size(), cached,
                aiCalls, tagged, withoutUsableTag, truncated,
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
        // 先按题精确清理"本次不再建议"的旧 AI 行：分批判量续跑时，只有被重新处理的题会走到这里，
        // 未处理的题保持原样（这就是续跑能累积进度的前提）。
        LambdaQueryWrapper<QuestionSkill> prune = new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getQuestionId, questionId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getSource, SOURCE_AI)
                .eq(QuestionSkill::getConfirmed, false);
        if (nodes.isEmpty()) {
            questionSkillMapper.delete(prune);
            return false;
        }
        List<String> keep = nodes.stream().map(NodeConfidence::nodeId).toList();
        questionSkillMapper.delete(prune.notIn(QuestionSkill::getNodeId, keep));

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

    // ==================== 读侧：一份快照，所有口径都由它派生 ====================

    public static final String STATUS_ALL = "all";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_UNTAGGED = "untagged";

    /**
     * 题库标签状态快照：题目、每题标签、每题状态**一次算清**。
     *
     * 为什么坚持"只有一个来源"：用户实测出现过「26 待标注 vs 16 待确认」——概览用一套 SQL、
     * 清单用另一套 SQL，两边口径一旦不一致就自相矛盾，用户无法判断还剩多少题没处理。
     * 现在概览数字、清单条数、节点覆盖全部从这一个快照派生，"数字与清单对不上"在结构上不可能发生。
     */
    private record Snapshot(List<Question> questions, Map<Long, List<QuestionSkill>> tags,
                            Map<Long, String> status, OrphanInfo orphan) {
    }

    /** 失效标签统计（条数 + 涉及题数） */
    public record OrphanInfo(int rows, int questions) {
    }

    private Snapshot snapshot(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId)
                .orderByAsc(Question::getVolume)
                .orderByAsc(Question::getQuestionNumber)
                .orderByAsc(Question::getId));
        List<QuestionSkill> rows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getTemplateId, templateId));
        Map<Long, List<QuestionSkill>> tags = new LinkedHashMap<>();
        Set<Long> orphanQuestions = new HashSet<>();
        int orphanRows = 0;
        for (QuestionSkill r : rows) {
            if (Boolean.TRUE.equals(r.getShadowed())) {
                continue;
            }
            // 技能图换过之后，指向已不存在的节点的旧标签不算数（否则覆盖数字会虚高），
            // 但要单独记一笔：这些"失效标签"要在界面上提示并可一键清理，
            // 否则它们会只在题目详情里冒出来（用户实测：详情有 gk.zl.concept、知识点页没有）。
            if (!template.nodeIds().contains(r.getNodeId())) {
                orphanQuestions.add(r.getQuestionId());
                orphanRows++;
                continue;
            }
            tags.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }
        Map<Long, String> status = new LinkedHashMap<>();
        for (Question q : questions) {
            status.put(q.getId(), statusOf(tags.getOrDefault(q.getId(), List.of())));
        }
        return new Snapshot(questions, tags, status, new OrphanInfo(orphanRows, orphanQuestions.size()));
    }

    /**
     * 失效标签：指向"当前技能图里已不存在节点"的标签（技能图升级、或用户删掉自定义节点之后留下的）。
     * 它们显示不出来、也不参与统计与门控，留着只会让用户困惑。
     */
    public OrphanInfo orphans(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        List<QuestionSkill> rows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getShadowed, false));
        Set<Long> questions = new HashSet<>();
        int n = 0;
        for (QuestionSkill r : rows) {
            if (!template.nodeIds().contains(r.getNodeId())) {
                n++;
                questions.add(r.getQuestionId());
            }
        }
        return new OrphanInfo(n, questions.size());
    }

    /** 清理失效标签（它们已经不可能被显示或使用） */
    @Transactional
    public int cleanupOrphanTags(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        List<QuestionSkill> rows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getTemplateId, templateId));
        int n = 0;
        for (QuestionSkill r : rows) {
            if (!template.nodeIds().contains(r.getNodeId())) {
                questionSkillMapper.deleteById(r.getId());
                n++;
            }
        }
        if (n > 0) {
            log.info("清理失效标签：题库 {} 模板 {} 共 {} 条（技能图更新后这些节点已不存在）", bankId, templateId, n);
        }
        return n;
    }

    /** 每题状态：有已确认标签 = confirmed；只有 AI 未确认建议 = pending；什么都没有 = untagged */
    private static String statusOf(List<QuestionSkill> rows) {
        if (rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.getConfirmed()))) {
            return STATUS_CONFIRMED;
        }
        if (rows.stream().anyMatch(r -> SOURCE_AI.equals(r.getSource()))) {
            return STATUS_PENDING;
        }
        return STATUS_UNTAGGED;
    }

    /**
     * 门控口径（与 {@link QuestionSkill#GATE_MIN_CONFIDENCE} 一致）：
     * 已确认，或 AI 高置信。低置信建议只作参考，绝不参与"是否掌握"的判断。
     */
    private static boolean usable(QuestionSkill r) {
        return Boolean.TRUE.equals(r.getConfirmed())
                || (r.getConfidence() != null && r.getConfidence() >= QuestionSkill.GATE_MIN_CONFIDENCE);
    }

    private Counts countsOf(Snapshot snap) {
        int confirmed = 0;
        int pending = 0;
        int untagged = 0;
        int usable = 0;
        for (Question q : snap.questions()) {
            String st = snap.status().get(q.getId());
            if (STATUS_CONFIRMED.equals(st)) {
                confirmed++;
            } else if (STATUS_PENDING.equals(st)) {
                pending++;
            } else {
                untagged++;
            }
            if (snap.tags().getOrDefault(q.getId(), List.of()).stream().anyMatch(QuestionTaggingService::usable)) {
                usable++;
            }
        }
        return new Counts(snap.questions().size(), confirmed, pending, untagged, usable);
    }

    /**
     * 审阅清单（界面主列表）：以**题**为单位，带每题当前标签与状态，可就地查看与修改。
     *
     * @param status all（默认）/ confirmed / pending / untagged
     * @param nodeId 可选：只看挂了该节点的题（按知识点分组批量处理时用）
     */
    public ReviewPage review(Long bankId, String templateId, String status, String nodeId, int page, int size) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        Snapshot snap = snapshot(bankId, templateId);
        String want = status == null || status.isBlank() ? STATUS_ALL : status;
        List<Question> filtered = new ArrayList<>();
        for (Question q : snap.questions()) {
            if (!STATUS_ALL.equals(want) && !want.equals(snap.status().get(q.getId()))) {
                continue;
            }
            if (nodeId != null && !nodeId.isBlank()) {
                List<QuestionSkill> rows = snap.tags().getOrDefault(q.getId(), List.of());
                if (rows.stream().noneMatch(r -> nodeId.equals(r.getNodeId()))) {
                    continue;
                }
            }
            filtered.add(q);
        }
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        List<ReviewQuestion> records = new ArrayList<>();
        for (Question q : filtered.subList(from, to)) {
            records.add(new ReviewQuestion(q.getId(), q.getQuestionNumber(),
                    q.getQuestionType() == null ? "" : q.getQuestionType().name(),
                    truncate(oneLine(q.getContent()), 120),
                    snap.status().get(q.getId()),
                    tagsFrom(snap.tags().getOrDefault(q.getId(), List.of()), template)));
        }
        return new ReviewPage(filtered.size(), safePage, safeSize, countsOf(snap), snap.orphan(), records);
    }

    /** 当前筛选下的全部题目 id（界面"全选 N 题"用：不把几千个 id 传到前端再传回来） */
    public List<Long> idsMatching(Long bankId, String templateId, String status, String nodeId) {
        Snapshot snap = snapshot(bankId, templateId);
        String want = status == null || status.isBlank() ? STATUS_ALL : status;
        List<Long> ids = new ArrayList<>();
        for (Question q : snap.questions()) {
            if (!STATUS_ALL.equals(want) && !want.equals(snap.status().get(q.getId()))) {
                continue;
            }
            if (nodeId != null && !nodeId.isBlank()) {
                List<QuestionSkill> rows = snap.tags().getOrDefault(q.getId(), List.of());
                if (rows.stream().noneMatch(r -> nodeId.equals(r.getNodeId()))) {
                    continue;
                }
            }
            ids.add(q.getId());
        }
        return ids;
    }

    private List<QuestionTag> tagsFrom(List<QuestionSkill> rows, SkillGraphService.SkillTemplate template) {
        List<QuestionTag> out = new ArrayList<>();
        for (QuestionSkill r : rows) {
            out.add(new QuestionTag(r.getNodeId(), nameOf(template, r.getNodeId()), r.getSource(),
                    r.getConfidence() == null ? 0 : r.getConfidence(),
                    Boolean.TRUE.equals(r.getConfirmed()), r.getOrigin()));
        }
        out.sort(Comparator.comparing(QuestionTag::nodeId));
        return out;
    }

    /**
     * 单题当前标签（含来源，供题目详情展示与用户修改）。
     *
     * ⚠️ 必须**按模板限定**：不限定就会把别的模板、以及技能图升级后已不存在的旧标签也返回，
     * 界面上就会冒出 `gk.zl.concept` 这种原始 id（用户实测反馈的正是这个：详情里有、知识点页里没有）。
     * 现在和审阅清单同口径——只认当前模板 + 当前图里存在的节点。
     */
    public List<QuestionTag> tagsOf(Long questionId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        List<QuestionSkill> rows = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getQuestionId, questionId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getShadowed, false));
        List<QuestionTag> out = new ArrayList<>();
        for (QuestionSkill r : rows) {
            if (!template.nodeIds().contains(r.getNodeId())) {
                continue; // 失效标签：不显示（由题库侧的一键清理处理掉）
            }
            out.add(new QuestionTag(r.getNodeId(), nameOf(template, r.getNodeId()),
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
        return writeUserTags(question, template, templateId, normalizeNodes(template, nodeIds));
    }

    /** 校验并归一化节点清单（去空、去重、必须在词表里） */
    private List<String> normalizeNodes(SkillGraphService.SkillTemplate template, List<String> nodeIds) {
        List<String> target = nodeIds == null ? List.of()
                : nodeIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        for (String n : target) {
            if (!template.nodeIds().contains(n)) {
                throw new IllegalArgumentException("技能节点不存在：" + n);
            }
        }
        return target;
    }

    /**
     * 把某题的标签**设定**为给定节点（写成 source=user、confirmed=1）。
     *
     * 同时清掉该题在**所有模板**下的 AI 建议（用户已经给出答案，AI 的猜测没有意义），
     * 以及同模板下旧的用户标注——否则反复修改会累积出重复的用户标签。
     * 「就地改标签」「批量设为知识点」「改挂」三条路径都收敛到这里，行为一致。
     */
    private int writeUserTags(Question question, SkillGraphService.SkillTemplate template, String templateId,
                              List<String> target) {
        Long questionId = question.getId();
        questionSkillMapper.delete(new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getQuestionId, questionId)
                .eq(QuestionSkill::getSource, SOURCE_AI));
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
     * 批量确认 / 设定标签 / 改挂 / 拒绝：
     * - action=confirm：把给定节点（或给定题）的 AI 建议置为已确认；
     * - action=set：把这些题的标签**设定**为给定节点（界面上"就地改标签/批量设为知识点"）；
     * - action=retag：按节点批量改挂（用 AI 建议反查题目，等价于对这批题执行 set）；
     * - action=reject：删除 AI 建议（已确认的标签不动）。
     *
     * 作用范围（questionIds vs filterStatus）见 {@link #resolveTargets}。
     */
    @Transactional
    public int apply(Long bankId, String templateId, String action, String nodeId,
                     List<String> newNodes, List<Long> questionIds) {
        return apply(bankId, templateId, action, nodeId, newNodes, questionIds, null);
    }

    @Transactional
    public int apply(Long bankId, String templateId, String action, String nodeId,
                     List<String> newNodes, List<Long> questionIds, String filterStatus) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        // 「全选 N 题」走这里：把筛选条件在**后端**解析成题目 id，界面不必回传上千个 id
        List<Long> scoped = resolveTargets(bankId, templateId, questionIds, filterStatus, nodeId);
        // 返回值统一是"影响到的**题数**"（不是标签行数）：一题可能有多个知识点，
        // 界面上的提示（"已确认 N 题"）必须和用户看到的行数一致。
        if ("reject".equals(action)) {
            Set<Long> affected = new HashSet<>();
            // 只丢"建议"：已确认的标签不动（要撤掉已确认的标签，请把标签设成别的或空）
            for (QuestionSkill r : selectAiRows(bankId, templateId, scoped == null ? nodeId : null, scoped, true)) {
                questionSkillMapper.deleteById(r.getId());
                affected.add(r.getQuestionId());
            }
            return affected.size();
        }
        if ("retag".equals(action) || "set".equals(action)) {
            List<String> target = normalizeNodes(template, newNodes);
            List<Long> ids;
            if (scoped != null) {
                ids = scoped;
            } else if ("set".equals(action)) {
                ids = List.of(); // 没给题、也没给筛选：不做任何事（危险动作必须显式指定范围）
            } else {
                ids = selectAiRows(bankId, templateId, nodeId, null, false).stream()
                        .map(QuestionSkill::getQuestionId).distinct().toList();
            }
            int n = 0;
            for (Long qid : ids) {
                Question q = questionMapper.selectById(qid);
                if (q == null || !bankId.equals(q.getBankId())) {
                    continue;
                }
                writeUserTags(q, template, templateId, target);
                n++;
            }
            return n;
        }
        // confirm（可按题：界面上逐题点"确认"）；已确认的无需重复处理
        Set<Long> affected = new HashSet<>();
        for (QuestionSkill r : selectAiRows(bankId, templateId, scoped == null ? nodeId : null, scoped, true)) {
            r.setConfirmed(true);
            r.setUpdatedAt(LocalDateTime.now());
            questionSkillMapper.updateById(r);
            affected.add(r.getQuestionId());
        }
        return affected.size();
    }

    /**
     * 解析作用范围：
     * - 给了 questionIds → 用它们（就地改标签、勾选批量）；
     * - 没给、但给了 filterStatus → 按"当前清单筛选"在服务端解析（界面的"全选 N 题"）；
     * - 都没给 → null 表示"沿用按 nodeId 的旧批量语义"（不是"什么题都没有"）。
     */
    private List<Long> resolveTargets(Long bankId, String templateId, List<Long> questionIds,
                                      String filterStatus, String nodeId) {
        if (questionIds != null && !questionIds.isEmpty()) {
            return questionIds.stream().filter(Objects::nonNull).distinct().toList();
        }
        if (filterStatus != null && !filterStatus.isBlank()) {
            return idsMatching(bankId, templateId, filterStatus, nodeId);
        }
        return null;
    }

    private List<QuestionSkill> selectAiRows(Long bankId, String templateId, String nodeId, List<Long> questionIds,
                                             boolean onlyUnconfirmed) {
        LambdaQueryWrapper<QuestionSkill> w = new LambdaQueryWrapper<QuestionSkill>()
                .eq(QuestionSkill::getBankId, bankId)
                .eq(QuestionSkill::getTemplateId, templateId)
                .eq(QuestionSkill::getSource, SOURCE_AI)
                .eq(QuestionSkill::getShadowed, false);
        if (nodeId != null && !nodeId.isBlank()) {
            w.eq(QuestionSkill::getNodeId, nodeId);
        }
        if (onlyUnconfirmed) {
            w.eq(QuestionSkill::getConfirmed, false);
        }
        if (questionIds != null && !questionIds.isEmpty()) {
            w.in(QuestionSkill::getQuestionId, questionIds);
        }
        return questionSkillMapper.selectList(w);
    }

    /** 覆盖地图：路线节点在题库里的题量（<3 视为证据不足，不参与门控） */
    public Coverage coverage(Long bankId, String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        Snapshot snap = snapshot(bankId, templateId);
        Counts c = countsOf(snap);
        Map<String, Integer> perNode = new LinkedHashMap<>();
        for (List<QuestionSkill> rows : snap.tags().values()) {
            Set<String> counted = new HashSet<>();
            for (QuestionSkill r : rows) {
                if (usable(r) && counted.add(r.getNodeId())) {
                    perNode.merge(r.getNodeId(), 1, Integer::sum);
                }
            }
        }
        List<NodeCoverage> nodes = new ArrayList<>();
        for (SkillGraphService.NodeView n : template.nodes()) {
            int count = perNode.getOrDefault(n.nodeId(), 0);
            nodes.add(new NodeCoverage(n.nodeId(), n.name(), n.stageName(), count, count >= 3, count > 0));
        }
        return new Coverage(templateId, template.graphVersion(), c.total(), c.confirmed(), c.pending(),
                c.untagged(), c.usable(), nodes);
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
