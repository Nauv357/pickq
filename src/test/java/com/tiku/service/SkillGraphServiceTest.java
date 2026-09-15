package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能图加载与校验（学习路径引擎阶段 0）。
 *
 * 校验必须"报错而不是静默忽略"，因为前置关系错了会让外缘算法给出错误的学习顺序：
 * 悬空前置、重复 nodeId、前置环都必须直接失败。
 */
class SkillGraphServiceTest {

    private final SkillGraphService service = new SkillGraphService(new ObjectMapper());

    /**
     * 自定义知识点（本机私有词表）：受控词表如果只能官方定义，用户在"图里没有这个知识点"时就没有出路
     * （实测反馈：下拉里能输入文字，但保存不了 tag）。这里锁住三件事：
     * ① 新建后立刻出现在图里（同一个 name 复用，不造重复节点）；② 落在「自定义知识点」阶段下；
     * ③ 删除后从图里消失（它上面的标签会变成失效标签，由题库侧清理）。
     */
    @Test
    void customNodesCanBeAddedReusedAndRemoved() {
        String tpl = "official.civil-service";
        int before = service.template(tpl).nodes().size();

        var node = service.addCustomNode(tpl, "  我自己的知识点  ");
        assertEquals("我自己的知识点", node.name(), "名称要去掉首尾空白");
        assertTrue(node.nodeId().startsWith(SkillGraphService.CUSTOM_ID_PREFIX), node.nodeId());
        assertEquals(SkillGraphService.CUSTOM_STAGE_NAME, node.stageName());
        assertEquals(before + 1, service.template(tpl).nodes().size(), "新节点要立刻进图");
        assertTrue(service.template(tpl).nodeIds().contains(node.nodeId()));
        assertTrue(service.template(tpl).stageOrder().contains(SkillGraphService.CUSTOM_STAGE_ID));

        // 同名再加一次：复用同一个节点（避免"看起来一样但不是一个"的重复项）
        var again = service.addCustomNode(tpl, "我自己的知识点");
        assertEquals(node.nodeId(), again.nodeId());
        assertEquals(before + 1, service.template(tpl).nodes().size());

        // 与内置节点同名：也复用内置的，不新建
        var builtInName = service.template(tpl).nodes().get(0).name();
        var reuse = service.addCustomNode(tpl, builtInName);
        assertEquals(builtInName, reuse.name());
        assertTrue(!reuse.nodeId().startsWith(SkillGraphService.CUSTOM_ID_PREFIX), "复用的是内置节点");
        assertEquals(before + 1, service.template(tpl).nodes().size());

        // 空名字直接报错（可照做），不是静默成功
        assertThrows(IllegalArgumentException.class, () -> service.addCustomNode(tpl, "   "));

        service.removeCustomNode(tpl, node.nodeId());
        assertEquals(before, service.template(tpl).nodes().size(), "删除后要立刻从图里消失");
        // 内置节点不允许删（只有 custom.* 才是用户的）
        assertThrows(IllegalArgumentException.class,
                () -> service.removeCustomNode(tpl, service.template(tpl).nodes().get(0).nodeId()));
    }

    /**
     * 用户自己维护词表（2026-09-16 用户反馈："既不能让用户自己编写，又不太准确，应该开放编辑权"）：
     * ① 改名**不动 nodeId**（已打的标签不能因此失效）；② 可以挂到官方阶段下；
     * ③ 官方节点可以停用/恢复（不改官方模板本身）；④ 一键恢复官方模板。
     */
    @Test
    void customNodesCanBeRenamedMovedAndOfficialNodesDisabled() {
        String tpl = "official.civil-service";
        var node = service.addCustomNode(tpl, "我的速算技巧");
        String firstStage = service.template(tpl).stageOrder().get(0);

        // ② 指定官方阶段
        var moved = service.updateCustomNode(tpl, node.nodeId(), "我的速算技巧", firstStage);
        assertEquals(firstStage, moved.stageId());
        assertEquals(service.template(tpl).stageNames().get(firstStage), moved.stageName());
        var inGraph = service.template(tpl).nodes().stream()
                .filter(n -> n.nodeId().equals(node.nodeId())).findFirst().orElseThrow();
        assertEquals(firstStage, inGraph.stageId(), "换阶段要立刻生效");

        // ① 改名：nodeId 不变（标签是存 nodeId 的，改名不能让标签失效）
        var renamed = service.updateCustomNode(tpl, node.nodeId(), "速算与估算", firstStage);
        assertEquals(node.nodeId(), renamed.nodeId(), "改名不能换 id");
        assertEquals("速算与估算", renamed.name());

        // 名称不能空；阶段必须真实存在
        assertThrows(IllegalArgumentException.class,
                () -> service.updateCustomNode(tpl, node.nodeId(), "  ", firstStage));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateCustomNode(tpl, node.nodeId(), "随便", "no.such.stage"));

        // ③ 停用官方节点：从图里消失（连前置引用一起摘掉），可恢复
        var official = service.template(tpl).nodes().stream()
                .filter(n -> !n.nodeId().startsWith(SkillGraphService.CUSTOM_ID_PREFIX))
                .filter(n -> service.template(tpl).prereq().values().stream().anyMatch(p -> p.contains(n.nodeId())))
                .findFirst().orElseThrow();
        int nodesBefore = service.template(tpl).nodes().size();
        service.setNodeDisabled(tpl, official.nodeId(), true);
        assertTrue(!service.template(tpl).nodeIds().contains(official.nodeId()), "停用后不该再出现在词表里");
        assertEquals(nodesBefore - 1, service.template(tpl).nodes().size());
        service.template(tpl).prereq().forEach((n, pres) ->
                assertTrue(!pres.contains(official.nodeId()), "停用节点的前置引用要一起摘掉（不能留悬空前置）"));
        assertEquals(java.util.List.of(official.nodeId()), service.disabledNodes(tpl));
        assertTrue(service.customized(tpl));

        service.setNodeDisabled(tpl, official.nodeId(), false);
        assertTrue(service.template(tpl).nodeIds().contains(official.nodeId()), "恢复后要回到词表里");
        assertEquals(nodesBefore, service.template(tpl).nodes().size());

        // 自定义节点不能用"停用"（该直接删）
        assertThrows(IllegalArgumentException.class,
                () -> service.setNodeDisabled(tpl, node.nodeId(), true));

        // ④ 恢复官方模板：自定义节点与停用记录一起清掉
        service.setNodeDisabled(tpl, official.nodeId(), true);
        service.resetCustomizations(tpl);
        assertTrue(service.customNodes(tpl).isEmpty(), "自定义节点应被清空");
        assertTrue(service.disabledNodes(tpl).isEmpty(), "停用记录应被清空");
        assertTrue(!service.customized(tpl));
        assertTrue(service.template(tpl).nodeIds().contains(official.nodeId()));
    }

    @Test
    void builtInTemplatesLoadAndPassValidation() {
        var templates = service.templates();
        assertTrue(templates.size() >= 2, "至少应有 2 个内置模板，实际 " + templates.size());
        for (var t : templates) {
            assertTrue(!t.nodes().isEmpty(), t.templateId() + " 应有节点");
            assertTrue(!t.graphVersion().isBlank(), t.templateId() + " 应有 graphVersion");
            // 每个节点的前置都必须在图内（load 已校验，这里再兜一层）
            t.prereq().forEach((node, pres) -> pres.forEach(p ->
                    assertTrue(t.nodeIds().contains(p), p + " 不在 " + t.templateId() + " 内")));
        }
        assertTrue(service.has("official.civil-service"));
        assertTrue(service.has("official.cs-ai-fullstack"));
    }

    @Test
    void nodeCatalogForPromptCarriesIdNameAndKeywords() {
        var t = service.template("official.civil-service");
        String catalog = service.nodeCatalogForPrompt(t);
        assertTrue(catalog.contains("gk.pd.figure.num | 图形推理·数量与属性"), catalog);
        assertTrue(catalog.contains("笔画数"), "关键词要进 prompt（提高映射准确率）");
    }

    @Test
    void unknownTemplateFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> service.template("no.such.template"));
    }

    @Test
    void danglingPrereqIsRejected() {
        String json = template("""
                {"id":"a","name":"A","weight":1.0,"prereq":["not-exist"],"keywords":[]}
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.parse(json, "t.json"));
        assertTrue(e.getMessage().contains("前置节点不存在"), e.getMessage());
    }

    @Test
    void duplicateNodeIdIsRejected() {
        String json = template("""
                {"id":"a","name":"A","weight":1.0,"prereq":[],"keywords":[]},
                {"id":"a","name":"A2","weight":1.0,"prereq":[],"keywords":[]}
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.parse(json, "t.json"));
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    @Test
    void prerequisiteCycleIsRejected() {
        String json = template("""
                {"id":"a","name":"A","weight":1.0,"prereq":["b"],"keywords":[]},
                {"id":"b","name":"B","weight":1.0,"prereq":["a"],"keywords":[]}
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.parse(json, "t.json"));
        assertTrue(e.getMessage().contains("环"), e.getMessage());
    }

    @Test
    void graphVersionChangesWhenContentChanges() {
        var t = service.template("official.civil-service");
        var same = service.parse(rawJsonOf(t.templateId()), t.templateId());
        assertEquals(t.graphVersion(), same.graphVersion(), "同样内容 → 同样版本");
        var changed = service.parse(rawJsonOf(t.templateId()).replace("图形推理", "图形推理（改名）"), t.templateId());
        assertTrue(!t.graphVersion().equals(changed.graphVersion()), "内容变了 → 版本必须变（缓存与标签才会失效重算）");
    }

    /** 用真实模板的 JSON 做"内容变化"测试的底稿 */
    private String rawJsonOf(String templateId) {
        var t = service.template(templateId);
        // 从内置资源重新读一遍原文（保证与原文件一致）
        try (var in = getClass().getResourceAsStream("/skill-templates/" + fileNameOf(templateId))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fileNameOf(String templateId) {
        return switch (templateId) {
            case "official.civil-service" -> "official-civil-service.json";
            case "official.cs-ai-fullstack" -> "official-cs-ai-fullstack.json";
            default -> throw new IllegalArgumentException(templateId);
        };
    }

    /** 包一层最小模板外壳，便于单独测校验规则 */
    private static String template(String nodesJson) {
        return """
                {
                  "schemaVersion": 1,
                  "templateId": "t.test",
                  "name": "测试模板",
                  "version": "1",
                  "stages": [ { "id": "s1", "name": "阶段一", "nodes": [ %s ] } ]
                }
                """.formatted(nodesJson);
    }
}
