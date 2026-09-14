package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private final ObjectMapper objectMapper;
    private final Map<String, SkillTemplate> templates = new LinkedHashMap<>();

    public SkillGraphService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(TEMPLATE_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("读取内置技能模板失败：" + e.getMessage(), e);
        }
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
        log.info("技能模板加载完成：{} 个（{}）", templates.size(),
                String.join(", ", templates.keySet()));
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
