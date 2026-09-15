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
 * 这里只负责"读 + 校验 + 查询"；模板的入库/替换在 {@link SkillGraphSyncService}。
 */
@Slf4j
@Service
public class SkillGraphService {

    private static final String TEMPLATE_PATTERN = "classpath:/skill-templates/*.json";
    /** 用户自定义知识点的存放位置：只影响本机，不进内容包 */
    private static final String CUSTOM_FILE = "skill-custom-nodes.json";
    /** 自定义节点统一挂在这个阶段下（界面上显示为「自定义知识点」） */
    public static final String CUSTOM_STAGE_ID = "custom";
    public static final String CUSTOM_STAGE_NAME = "自定义知识点";
    /** 自定义节点 id 前缀：用于区分哪些节点是用户加的（可删） */
    public static final String CUSTOM_ID_PREFIX = "custom.";

    private final ObjectMapper objectMapper;
    private final Path customPath;
    private final Map<String, SkillTemplate> templates = new LinkedHashMap<>();
    /** 用户自定义节点：templateId → 节点（有序，按加入时间） */
    private final Map<String, List<NodeView>> customNodes = new LinkedHashMap<>();

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
        templates.clear();
        for (Resource r : resources) {
            try {
                String json = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                SkillTemplate t = parse(json, r.getFilename());
                if (templates.containsKey(t.templateId())) {
                    throw new IllegalStateException("技能模板 id 重复：" + t.templateId());
                }
                templates.put(t.templateId(), t);
            } catch (IOException e) {
                throw new IllegalStateException("读取技能模板失败：" + r.getFilename() + "：" + e.getMessage(), e);
            }
        }
        if (templates.isEmpty()) {
            // 没有模板时标签功能没有任何意义，直接失败而不是空跑
            throw new IllegalStateException("没有可用的内置技能模板（skill-templates/*.json）");
        }
    }

    // ==================== 用户自定义知识点（本机私有词表） ====================

    /**
     * 读取 {@code {dataDir}/skill-custom-nodes.json}（文件不存在时保留内存里已有的自定义节点）。
     *
     * 为什么要有这一层：受控词表如果只能"官方定义"，用户在 AI 判错或题库里有图里没有的知识点时
     * 就没有任何出路（实测反馈："可以在下拉里输入文字，但保存不了 tag"）。
     * 自定义节点只影响本机、挂在「自定义知识点」阶段下；删除节点时它的标签会变成失效标签、可一键清理。
     */
    private void readCustomFromDisk() {
        if (customPath == null || !Files.exists(customPath)) {
            return; // 没有数据目录 / 还没写过：保留当前内存状态（单测直接 new 的场合）
        }
        Map<String, List<NodeView>> parsed = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(Files.readString(customPath, StandardCharsets.UTF_8));
            root.fields().forEachRemaining(e -> {
                List<NodeView> list = new ArrayList<>();
                for (JsonNode n : e.getValue()) {
                    String id = n.path("id").asText("").trim();
                    String name = n.path("name").asText("").trim();
                    if (!id.isEmpty() && !name.isEmpty()) {
                        list.add(new NodeView(id, name, null, 3, 1.0, true, List.of(),
                                CUSTOM_STAGE_ID, CUSTOM_STAGE_NAME, 999));
                    }
                }
                if (!list.isEmpty()) {
                    parsed.put(e.getKey(), list);
                }
            });
        } catch (Exception e) {
            log.warn("自定义知识点文件读取失败（按空处理，不影响内置模板）：{}", e.getMessage());
            return;
        }
        customNodes.clear();
        customNodes.putAll(parsed);
    }

    /** 把自定义节点并进模板：追加一个「自定义知识点」阶段 */
    private void applyCustom() {
        for (Map.Entry<String, List<NodeView>> e : customNodes.entrySet()) {
            SkillTemplate base = templates.get(e.getKey());
            if (base == null) {
                continue; // 模板已被移除：这些节点无处可挂，忽略（标签会被当失效标签清理）
            }
            templates.put(e.getKey(), mergeCustom(base, e.getValue()));
        }
    }

    private SkillTemplate mergeCustom(SkillTemplate base, List<NodeView> custom) {
        List<NodeView> nodes = new ArrayList<>(base.nodes());
        nodes.addAll(custom);
        List<String> stages = new ArrayList<>(base.stageOrder());
        if (!stages.contains(CUSTOM_STAGE_ID)) {
            stages.add(CUSTOM_STAGE_ID);
        }
        Map<String, String> stageNames = new LinkedHashMap<>(base.stageNames());
        stageNames.put(CUSTOM_STAGE_ID, CUSTOM_STAGE_NAME);
        Map<String, List<String>> prereq = new LinkedHashMap<>(base.prereq());
        for (NodeView n : custom) {
            prereq.put(n.nodeId(), List.of());
        }
        String fingerprint = base.templateId() + "|" + custom.stream()
                .map(n -> n.nodeId() + "=" + n.name()).reduce("", (a, b) -> a + ";" + b);
        return new SkillTemplate(base.templateId(), base.name(), base.version(), base.goalHint(), base.sourceType(),
                base.graphVersion() + "-c" + Integer.toHexString(fingerprint.hashCode()),
                List.copyOf(stages), Map.copyOf(stageNames), List.copyOf(nodes), Map.copyOf(prereq));
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
        SkillTemplate t = template(templateId);
        String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("知识点名称不能为空");
        }
        if (name.length() > 40) {
            name = name.substring(0, 40);
        }
        // 与已有节点同名（含内置）：直接复用，别制造"看起来一样但不是一个"的节点
        for (NodeView n : t.nodes()) {
            if (n.name().equalsIgnoreCase(name)) {
                return n;
            }
        }
        String nodeId = CUSTOM_ID_PREFIX + hash8(templateId + "|" + name);
        NodeView node = new NodeView(nodeId, name, null, 3, 1.0, true, List.of(),
                CUSTOM_STAGE_ID, CUSTOM_STAGE_NAME, 999);
        List<NodeView> list = new ArrayList<>(customNodes.getOrDefault(templateId, List.of()));
        list.add(node);
        customNodes.put(templateId, List.copyOf(list));
        persistCustomNodes();
        load();
        log.info("新增自定义知识点：{} → {}（{}）", templateId, nodeId, name);
        return node;
    }

    /** 删除自定义知识点（只能删 {@code custom.*}；它的标签会变成失效标签，由题库侧清理） */
    public synchronized void removeCustomNode(String templateId, String nodeId) {
        if (nodeId == null || !nodeId.startsWith(CUSTOM_ID_PREFIX)) {
            throw new IllegalArgumentException("只能删除自定义知识点：" + nodeId);
        }
        List<NodeView> list = new ArrayList<>(customNodes.getOrDefault(templateId, List.of()));
        if (list.removeIf(n -> n.nodeId().equals(nodeId))) {
            if (list.isEmpty()) {
                customNodes.remove(templateId);
            } else {
                customNodes.put(templateId, List.copyOf(list));
            }
            persistCustomNodes();
            load();
            log.info("删除自定义知识点：{} → {}", templateId, nodeId);
        }
    }

    private void persistCustomNodes() {
        if (customPath == null) {
            // 没有数据目录（单元测试直接 new 的场合）：只在内存里生效，不落盘
            return;
        }
        try {
            Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
            customNodes.forEach((k, v) -> out.put(k, v.stream()
                    .map(n -> Map.of("id", n.nodeId(), "name", n.name())).toList()));
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
