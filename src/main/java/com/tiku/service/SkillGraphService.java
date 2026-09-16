package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 技能图（受控词表）加载与校验：内置模板来自 {@code classpath:/skill-templates/*.json}。
 * 设计见 docs/learning-path-design.md §3.1、§8。
 *
 * 校验是**必须的**，因为前置关系错了会让"外缘算法"给出错误的学习顺序，环会让它死循环：
 * - nodeId 全局唯一且非空；
 * - prereq 必须指向同模板内的节点（悬空 = 报错，不静默忽略）；
 * - 不允许前置环。
 *
 * 这里只负责"读 + 校验 + 查询"；用户的本机改动（自定义节点 / 停用官方节点）也由本类管理并落盘。
 * （2026-09-16 审计：原先把内置模板写进 `skill_node`/`skill_edge` 的同步链路已删除——
 *  那两张表从来没有被读过，读路径一直是这里的内存图。）
 */
@Slf4j
@Service
public class SkillGraphService {

    private static final String TEMPLATE_PATTERN = "classpath:/skill-templates/*.json";
    /** 用户自定义知识点的存放位置：只影响本机，不进内容包 */
    private static final String CUSTOM_FILE = "skill-custom-nodes.json";
    /** 自定义节点统一挂在这个阶段下（界面上显示为「自定义知识点」）；也可以挂到官方阶段下 */
    public static final String CUSTOM_STAGE_ID = "custom";
    public static final String CUSTOM_STAGE_NAME = "自定义知识点";
    /** 自定义节点 id 前缀：用于区分哪些节点是用户加的（可改可删） */
    public static final String CUSTOM_ID_PREFIX = "custom.";
    /** 「自定义知识点」阶段排在最后 */
    public static final int CUSTOM_STAGE_ORDER = 999;

    private final ObjectMapper objectMapper;
    private final Path customPath;
    /** 官方原图（未被本机改动过）：用于"停用的节点叫什么名字""恢复官方模板"这类对照 */
    private final Map<String, SkillTemplate> builtIns = new LinkedHashMap<>();
    /** 生效中的图 = 官方原图 + 自定义节点 − 停用节点 */
    private final Map<String, SkillTemplate> templates = new LinkedHashMap<>();
    /** 用户自定义节点：templateId → 节点（有序，按加入时间） */
    private final Map<String, List<NodeView>> customNodes = new LinkedHashMap<>();
    /** 用户**停用**的官方节点：templateId → nodeId 集合（只影响本机：不出现在词表/下拉/AI 提示里） */
    private final Map<String, Set<String>> disabledNodes = new LinkedHashMap<>();

    /** 单测用（没有数据目录：自定义知识点只在内存里生效） */
    public SkillGraphService(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    /** Spring 用这个：数据目录决定自定义知识点的落盘位置。两个构造器并存，必须显式标注用哪个 */
    @Autowired
    public SkillGraphService(ObjectMapper objectMapper, @Value("${tiku.data-dir:}") String dataDir) {
        this.objectMapper = objectMapper;
        this.customPath = dataDir == null || dataDir.isBlank() ? null : Path.of(dataDir, CUSTOM_FILE);
        load();
    }

    /** 一个技能节点（图里的节点视图） */
    public record NodeView(String nodeId, String name, String parentId, int level, double weight,
                           boolean optional, List<String> keywords,
                           String stageId, String stageName, int stageOrder) {
    }

    /** 一张技能图：阶段 + 节点 + 前置关系 */
    public record SkillTemplate(String templateId, String name, String version, String goalHint, String sourceType,
                                String graphVersion,
                                List<String> stageOrder, Map<String, String> stageNames,
                                List<NodeView> nodes, Map<String, List<String>> prereq) {

        public Set<String> nodeIds() {
            return nodes.stream().map(NodeView::nodeId).collect(LinkedHashSet::new, Set::add, Set::addAll);
        }
    }

    public List<SkillTemplate> templates() {
        return templates.values().stream()
                .sorted(Comparator.comparing(SkillTemplate::templateId))
                .toList();
    }

    public SkillTemplate template(String templateId) {
        SkillTemplate t = templates.get(templateId);
        if (t == null) {
            throw new IllegalArgumentException("未知的技能模板：" + templateId);
        }
        return t;
    }

    public boolean has(String templateId) {
        return templates.containsKey(templateId);
    }

    /** 给 AI 看的节点清单（nodeId | name | keywords），控制 token：只给节点名与关键词，不给整图 */
    public String nodeCatalogForPrompt(SkillTemplate t) {
        StringBuilder sb = new StringBuilder();
        for (NodeView n : t.nodes()) {
            sb.append(n.stageName()).append(" / ").append(n.nodeId()).append(" | ").append(n.name());
            if (!n.keywords().isEmpty()) {
                sb.append(" | ").append(String.join("、", n.keywords()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ==================== 加载与校验 ====================

    private void load() {
        loadBuiltIns();
        readCustomFromDisk();
        applyCustom();
        log.info("技能模板加载完成：{} 个（{}）；自定义节点 {} 个", templates.size(),
                String.join(", ", templates.keySet()),
                customNodes.values().stream().mapToInt(List::size).sum());
    }

    private void loadBuiltIns() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(TEMPLATE_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("读取内置技能模板失败：" + e.getMessage(), e);
        }
        builtIns.clear();
        for (Resource r : resources) {
            try {
                String json = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                SkillTemplate t = parse(json, r.getFilename());
                if (builtIns.containsKey(t.templateId())) {
                    throw new IllegalStateException("技能模板 id 重复：" + t.templateId());
                }
                builtIns.put(t.templateId(), t);
            } catch (IOException e) {
                throw new IllegalStateException("读取技能模板失败：" + r.getFilename() + "：" + e.getMessage(), e);
            }
        }
        if (builtIns.isEmpty()) {
            // 没有模板时标签功能没有任何意义，直接失败而不是空跑
            throw new IllegalStateException("没有可用的内置技能模板（skill-templates/*.json）");
        }
        templates.clear();
        templates.putAll(builtIns);
    }

    /** 官方原图里的节点（不受"停用/自定义"影响）：界面要能显示"被停用的那个叫什么" */
    public List<NodeView> officialNodes(String templateId) {
        SkillTemplate t = builtIns.get(templateId);
        return t == null ? List.of() : t.nodes();
    }

    /** 官方原图（未应用本机改动）：对照用 */
    public SkillTemplate officialTemplate(String templateId) {
        SkillTemplate t = builtIns.get(templateId);
        if (t == null) {
            throw new IllegalArgumentException("未知的技能模板：" + templateId);
        }
        return t;
    }

    // ==================== 用户自定义知识点（本机私有词表） ====================

    /**
     * 读取 {@code {dataDir}/skill-custom-nodes.json}（文件不存在时保留内存里已有的自定义节点）。
     *
     * 为什么要有这一层：受控词表如果只能"官方定义"，用户在 AI 判错或题库里有图里没有的知识点时
     * 就没有任何出路（实测反馈："可以在下拉里输入文字，但保存不了 tag"）。
     * 文件里存两样东西：**自定义节点**（可增删改、可挂到任意阶段）与**被停用的官方节点**
     * （只在本机隐藏，不改官方模板本身，避免远端模板更新时冲突）。
     *
     * 兼容旧格式：值直接是数组 = 只有自定义节点（早期版本写的就是这个形状）。
     */
    private void readCustomFromDisk() {
        if (customPath == null || !Files.exists(customPath)) {
            return; // 没有数据目录 / 还没写过：保留当前内存状态（单测直接 new 的场合）
        }
        Map<String, List<NodeView>> parsed = new LinkedHashMap<>();
        Map<String, Set<String>> parsedDisabled = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(Files.readString(customPath, StandardCharsets.UTF_8));
            root.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                JsonNode nodes = value.isArray() ? value : value.path("nodes");
                List<NodeView> list = new ArrayList<>();
                for (JsonNode n : nodes) {
                    String id = n.path("id").asText("").trim();
                    String name = n.path("name").asText("").trim();
                    String stageId = n.path("stageId").asText("").trim();
                    if (!id.isEmpty() && !name.isEmpty()) {
                        String stage = stageId.isEmpty() ? CUSTOM_STAGE_ID : stageId;
                        list.add(new NodeView(id, name, null, 3, 1.0, true, List.of(),
                                stage, CUSTOM_STAGE_ID.equals(stage) ? CUSTOM_STAGE_NAME : null, CUSTOM_STAGE_ORDER));
                    }
                }
                if (!list.isEmpty()) {
                    parsed.put(entry.getKey(), list);
                }
                Set<String> disabled = new LinkedHashSet<>();
                for (JsonNode d : value.path("disabled")) {
                    String id = d.asText("").trim();
                    if (!id.isEmpty()) {
                        disabled.add(id);
                    }
                }
                if (!disabled.isEmpty()) {
                    parsedDisabled.put(entry.getKey(), disabled);
                }
            });
        } catch (Exception e) {
            log.warn("自定义知识点文件读取失败（按空处理，不影响内置模板）：{}", e.getMessage());
            return;
        }
        customNodes.clear();
        customNodes.putAll(parsed);
        disabledNodes.clear();
        disabledNodes.putAll(parsedDisabled);
    }

    /**
     * 把自定义节点并进模板、把停用的官方节点摘掉。
     * **每次都从官方原图重算**（不是在上一次结果上叠加），否则反复改动会越并越多。
     */
    private void applyCustom() {
        templates.clear();
        templates.putAll(builtIns);
        for (Map.Entry<String, List<NodeView>> e : customNodes.entrySet()) {
            SkillTemplate base = templates.get(e.getKey());
            if (base == null) {
                continue; // 模板已被移除：这些节点无处可挂，忽略（标签会被当失效标签清理）
            }
            templates.put(e.getKey(), mergeCustom(base, e.getValue()));
        }
        for (Map.Entry<String, Set<String>> e : disabledNodes.entrySet()) {
            SkillTemplate base = templates.get(e.getKey());
            if (base == null || e.getValue().isEmpty()) {
                continue;
            }
            templates.put(e.getKey(), removeNodes(base, e.getValue()));
        }
    }

    /**
     * 摘掉被停用的节点。
     * **同时把其它节点的前置关系里对它的引用一并去掉**——"我不用这个节点"意味着它也不该再挡着别人，
     * 留着悬空前置会让顺序判断出问题（设计 §3.1 明确不允许悬空前置）。
     */
    private SkillTemplate removeNodes(SkillTemplate base, Set<String> nodeIds) {
        List<NodeView> nodes = base.nodes().stream().filter(n -> !nodeIds.contains(n.nodeId())).toList();
        Map<String, List<String>> prereq = new LinkedHashMap<>();
        base.prereq().forEach((node, pre) -> prereq.put(node,
                pre.stream().filter(p -> !nodeIds.contains(p)).toList()));
        return copyWith(base, base.graphVersion() + "-d" + Integer.toHexString(nodeIds.hashCode()),
                base.stageOrder(), base.stageNames(), nodes, prereq);
    }

    private SkillTemplate mergeCustom(SkillTemplate base, List<NodeView> custom) {
        List<NodeView> nodes = new ArrayList<>(base.nodes());
        nodes.addAll(custom);
        List<String> stages = new ArrayList<>(base.stageOrder());
        Map<String, String> stageNames = new LinkedHashMap<>(base.stageNames());
        // 自定义节点可以挂在官方阶段下；只有真的用了「自定义知识点」阶段才把它加进阶段列表
        for (NodeView n : custom) {
            if (!stages.contains(n.stageId())) {
                stages.add(n.stageId());
            }
            stageNames.putIfAbsent(n.stageId(),
                    n.stageName() == null ? n.stageId() : n.stageName());
        }
        Map<String, List<String>> prereq = new LinkedHashMap<>(base.prereq());
        for (NodeView n : custom) {
            prereq.put(n.nodeId(), List.of());
        }
        String fingerprint = base.templateId() + "|" + custom.stream()
                .map(n -> n.nodeId() + "=" + n.name() + "@" + n.stageId()).reduce("", (a, b) -> a + ";" + b);
        return copyWith(base, base.graphVersion() + "-c" + Integer.toHexString(fingerprint.hashCode()),
                List.copyOf(stages), Map.copyOf(stageNames), List.copyOf(nodes), Map.copyOf(prereq));
    }

    private static SkillTemplate copyWith(SkillTemplate base, String graphVersion, List<String> stages,
                                         Map<String, String> stageNames, List<NodeView> nodes,
                                         Map<String, List<String>> prereq) {
        return new SkillTemplate(base.templateId(), base.name(), base.version(), base.goalHint(), base.sourceType(),
                graphVersion, stages, stageNames, nodes, prereq);
    }

    /** 该模板下的自定义节点（界面上可删的只有这些） */
    public List<NodeView> customNodes(String templateId) {
        return customNodes.getOrDefault(templateId, List.of());
    }

    /**
     * 新增一个自定义知识点（同名已存在时直接复用，保证"输入同一个名字"不会造出两个节点）。
     * 返回新建/复用的节点视图。
     */
    public synchronized NodeView addCustomNode(String templateId, String rawName) {
        return addCustomNode(templateId, rawName, null);
    }

    /** 新增自定义知识点并可指定所属阶段（stageId 空 = 挂到「自定义知识点」阶段） */
    public synchronized NodeView addCustomNode(String templateId, String rawName, String rawStageId) {
        SkillTemplate t = template(templateId);
        String name = normalizeName(rawName);
        // 与已有节点同名（含内置）：直接复用，别制造"看起来一样但不是一个"的节点
        for (NodeView n : t.nodes()) {
            if (n.name().equalsIgnoreCase(name)) {
                return n;
            }
        }
        String stageId = resolveStageId(t, rawStageId);
        String nodeId = CUSTOM_ID_PREFIX + hash8(templateId + "|" + name);
        NodeView node = new NodeView(nodeId, name, null, 3, 1.0, true, List.of(),
                stageId, stageNameOf(t, stageId), stageOrderOf(t, stageId));
        List<NodeView> list = new ArrayList<>(customNodes.getOrDefault(templateId, List.of()));
        list.add(node);
        customNodes.put(templateId, List.copyOf(list));
        save();
        log.info("新增自定义知识点：{} → {}（{} @{}）", templateId, nodeId, name, stageId);
        return node;
    }

    /**
     * 改名 / 换阶段（只针对 {@code custom.*}）。
     * **改名不动 nodeId**：已打在这道题上的标签是存 nodeId 的，改名只改显示，所以标签不会失效。
     */
    public synchronized NodeView updateCustomNode(String templateId, String nodeId, String rawName, String rawStageId) {
        requireCustomNode(templateId, nodeId);
        String name = normalizeName(rawName);
        SkillTemplate t = template(templateId);
        String stageId = resolveStageId(t, rawStageId);
        List<NodeView> list = new ArrayList<>(customNodes.getOrDefault(templateId, List.of()));
        NodeView updated = null;
        for (int i = 0; i < list.size(); i++) {
            NodeView n = list.get(i);
            if (n.nodeId().equals(nodeId)) {
                updated = new NodeView(n.nodeId(), name, n.parentId(), n.level(), n.weight(), n.optional(),
                        n.keywords(), stageId, stageNameOf(t, stageId), stageOrderOf(t, stageId));
                list.set(i, updated);
            }
        }
        customNodes.put(templateId, List.copyOf(list));
        save();
        log.info("修改自定义知识点：{} → {}（{} @{}）", templateId, nodeId, name, stageId);
        return updated;
    }

    /** 删除自定义知识点（只能删 {@code custom.*}；它的标签会变成失效标签，由题库侧清理） */
    public synchronized void removeCustomNode(String templateId, String nodeId) {
        requireCustomNode(templateId, nodeId);
        List<NodeView> list = new ArrayList<>(customNodes.getOrDefault(templateId, List.of()));
        if (list.removeIf(n -> n.nodeId().equals(nodeId))) {
            if (list.isEmpty()) {
                customNodes.remove(templateId);
            } else {
                customNodes.put(templateId, List.copyOf(list));
            }
            save();
            log.info("删除自定义知识点：{} → {}", templateId, nodeId);
        }
    }

    /**
     * 停用 / 恢复一个**官方**节点（自定义节点请直接删）。
     * 停用只影响本机：不再出现在词表、下拉与 AI 提示里；它上面的标签会变成失效标签（可一键清理）。
     * 不改官方模板本身，所以远端模板更新不会与本地冲突。
     */
    public synchronized void setNodeDisabled(String templateId, String nodeId, boolean disabled) {
        SkillTemplate t = template(templateId);
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("缺少知识点 id");
        }
        if (nodeId.startsWith(CUSTOM_ID_PREFIX)) {
            throw new IllegalArgumentException("自定义知识点请直接删除，不需要停用：" + nodeId);
        }
        Set<String> set = new LinkedHashSet<>(disabledNodes.getOrDefault(templateId, Set.of()));
        if (disabled) {
            if (!t.nodeIds().contains(nodeId)) {
                throw new IllegalArgumentException("知识点不在当前技能图里：" + nodeId);
            }
            set.add(nodeId);
        } else if (!set.remove(nodeId)) {
            // 恢复：被停用的节点当然不在图里，所以这里不能按"图里有没有"校验；没有停用记录就是本来就启用的
            return;
        }
        if (set.isEmpty()) {
            disabledNodes.remove(templateId);
        } else {
            disabledNodes.put(templateId, Set.copyOf(set));
        }
        save();
        log.info("{}官方知识点：{} → {}", disabled ? "停用" : "恢复", templateId, nodeId);
    }

    /** 用户停用的官方节点（界面上"已停用"分组要能列出来并可恢复） */
    public List<String> disabledNodes(String templateId) {
        return List.copyOf(disabledNodes.getOrDefault(templateId, Set.of()));
    }

    /** 是否对本模板做过本机改动（自定义节点 / 停用官方节点）——决定"恢复官方模板"是否可点 */
    public boolean customized(String templateId) {
        return !customNodes.getOrDefault(templateId, List.of()).isEmpty()
                || !disabledNodes.getOrDefault(templateId, Set.of()).isEmpty();
    }

    /** 恢复官方模板：清掉本模板的全部自定义节点与停用记录（题目上的标签会变成失效标签，可一键清理） */
    public synchronized void resetCustomizations(String templateId) {
        template(templateId);
        customNodes.remove(templateId);
        disabledNodes.remove(templateId);
        save();
        log.info("恢复官方模板：{}", templateId);
    }

    private void requireCustomNode(String templateId, String nodeId) {
        if (nodeId == null || !nodeId.startsWith(CUSTOM_ID_PREFIX)) {
            throw new IllegalArgumentException("只能修改/删除自定义知识点：" + nodeId);
        }
        boolean exists = customNodes.getOrDefault(templateId, List.of()).stream()
                .anyMatch(n -> n.nodeId().equals(nodeId));
        if (!exists) {
            throw new IllegalArgumentException("自定义知识点不存在：" + nodeId);
        }
    }

    private static String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("知识点名称不能为空");
        }
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    /**
     * 阶段必须是这张图里存在的（含「自定义知识点」）；空值 = 用「自定义知识点」。
     * 注意：「自定义知识点」阶段是**隐式可用**的——图上还没有任何自定义节点时它不在 stageOrder 里，
     * 但用户完全可以先建节点再出现这个阶段（否则第一次新建必然失败）。
     */
    private static String resolveStageId(SkillTemplate t, String rawStageId) {
        String stageId = rawStageId == null ? "" : rawStageId.trim();
        if (stageId.isEmpty() || CUSTOM_STAGE_ID.equals(stageId)) {
            return CUSTOM_STAGE_ID;
        }
        if (!t.stageOrder().contains(stageId)) {
            throw new IllegalArgumentException("阶段不存在：" + stageId);
        }
        return stageId;
    }

    private static String stageNameOf(SkillTemplate t, String stageId) {
        return CUSTOM_STAGE_ID.equals(stageId)
                ? CUSTOM_STAGE_NAME
                : t.stageNames().getOrDefault(stageId, stageId);
    }

    private static int stageOrderOf(SkillTemplate t, String stageId) {
        if (CUSTOM_STAGE_ID.equals(stageId)) {
            return CUSTOM_STAGE_ORDER;
        }
        int idx = t.stageOrder().indexOf(stageId);
        return idx < 0 ? CUSTOM_STAGE_ORDER : idx;
    }

    /** 落盘（内存态已经改好，写盘失败要抛出来，别让用户以为改成功了） */
    private void save() {
        persistCustomNodes();
        load();
    }

    private void persistCustomNodes() {
        if (customPath == null) {
            // 没有数据目录（单元测试直接 new 的场合）：只在内存里生效，不落盘
            return;
        }
        try {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            Set<String> ids = new LinkedHashSet<>(customNodes.keySet());
            ids.addAll(disabledNodes.keySet());
            for (String templateId : ids) {
                List<Map<String, String>> nodes = customNodes.getOrDefault(templateId, List.of()).stream()
                        .map(n -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("id", n.nodeId());
                            m.put("name", n.name());
                            m.put("stageId", n.stageId());
                            return m;
                        }).toList();
                List<String> disabled = List.copyOf(disabledNodes.getOrDefault(templateId, Set.of()));
                if (nodes.isEmpty() && disabled.isEmpty()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("nodes", nodes);
                entry.put("disabled", disabled);
                out.put(templateId, entry);
            }
            Files.createDirectories(customPath.getParent());
            Files.writeString(customPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("自定义知识点保存失败：" + e.getMessage(), e);
        }
    }

    private static String hash8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    SkillTemplate parse(String json, String fileName) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("技能模板不是合法 JSON：" + fileName + "：" + e.getMessage(), e);
        }
        String templateId = text(root, "templateId");
        String name = text(root, "name");
        if (templateId.isBlank() || name.isBlank()) {
            throw new IllegalStateException("技能模板缺少 templateId/name：" + fileName);
        }
        String version = root.path("version").asText("");
        String goalHint = root.path("goalHint").asText("");
        String sourceType = root.path("source").path("type").asText("official");

        List<String> stageOrder = new ArrayList<>();
        Map<String, String> stageNames = new LinkedHashMap<>();
        List<NodeView> nodes = new ArrayList<>();
        Map<String, List<String>> prereq = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        JsonNode stages = root.path("stages");
        if (!stages.isArray() || stages.isEmpty()) {
            throw new IllegalStateException("技能模板缺少 stages：" + templateId);
        }
        int order = 0;
        for (JsonNode stage : stages) {
            String stageId = stage.path("id").asText("");
            String stageName = stage.path("name").asText("");
            if (stageId.isBlank() || stageName.isBlank()) {
                throw new IllegalStateException("技能模板阶段缺少 id/name：" + templateId);
            }
            stageOrder.add(stageId);
            stageNames.put(stageId, stageName);
            for (JsonNode node : stage.path("nodes")) {
                String nodeId = node.path("id").asText("");
                String nodeName = node.path("name").asText("");
                if (nodeId.isBlank() || nodeName.isBlank()) {
                    throw new IllegalStateException("技能节点缺少 id/name（模板 " + templateId + " 阶段 " + stageId + "）");
                }
                if (!seen.add(nodeId)) {
                    throw new IllegalStateException("技能节点 id 重复：" + nodeId + "（模板 " + templateId + "）");
                }
                List<String> keywords = new ArrayList<>();
                for (JsonNode k : node.path("keywords")) {
                    String kw = k.asText("").trim();
                    if (!kw.isEmpty()) {
                        keywords.add(kw);
                    }
                }
                List<String> pre = new ArrayList<>();
                for (JsonNode p : node.path("prereq")) {
                    String pid = p.asText("").trim();
                    if (!pid.isEmpty()) {
                        pre.add(pid);
                    }
                }
                nodes.add(new NodeView(nodeId, nodeName, node.path("parentId").asText(null),
                        node.path("level").asInt(3), node.path("weight").asDouble(1.0),
                        node.path("optional").asBoolean(false), keywords, stageId, stageName, order));
                prereq.put(nodeId, pre);
            }
            order++;
        }
        // 悬空前置：直接报错（静默忽略会让顺序悄悄错掉）
        for (Map.Entry<String, List<String>> e : prereq.entrySet()) {
            for (String p : e.getValue()) {
                if (!seen.contains(p)) {
                    throw new IllegalStateException("技能模板 " + templateId + " 的前置节点不存在：" + p + "（被 " + e.getKey() + " 引用）");
                }
            }
        }
        assertAcyclic(templateId, prereq);
        return new SkillTemplate(templateId, name, version, goalHint, sourceType, graphVersion(version, json),
                List.copyOf(stageOrder), Map.copyOf(stageNames), List.copyOf(nodes), deepCopy(prereq));
    }

    /** 前置环检测（DFS 三色法）：环会让外缘计算永远找不到"可开始的节点" */
    private void assertAcyclic(String templateId, Map<String, List<String>> prereq) {
        Map<String, Integer> color = new HashMap<>(); // 0 未访问 / 1 在栈上 / 2 完成
        for (String node : prereq.keySet()) {
            visit(templateId, node, prereq, color, new ArrayList<>());
        }
    }

    private void visit(String templateId, String node, Map<String, List<String>> prereq,
                       Map<String, Integer> color, List<String> path) {
        Integer c = color.get(node);
        if (c != null && c == 2) {
            return;
        }
        if (c != null && c == 1) {
            path.add(node);
            throw new IllegalStateException("技能模板 " + templateId + " 的前置关系存在环：" + String.join(" → ", path));
        }
        color.put(node, 1);
        path.add(node);
        for (String p : prereq.getOrDefault(node, List.of())) {
            visit(templateId, p, prereq, color, path);
        }
        path.remove(path.size() - 1);
        color.put(node, 2);
    }

    /** 图版本 = 模板 version + 内容摘要：内容一改，缓存的分组映射与标签自动失效重算 */
    private static String graphVersion(String version, String json) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return (version.isBlank() ? "v0" : version) + "-" + sb;
        } catch (Exception e) {
            return version.isBlank() ? "v0" : version;
        }
    }

    private static Map<String, List<String>> deepCopy(Map<String, List<String>> src) {
        Map<String, List<String>> copy = new TreeMap<>();
        src.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Map.copyOf(copy);
    }

    private static String text(JsonNode root, String field) {
        return root.path(field).asText("").trim();
    }
}
