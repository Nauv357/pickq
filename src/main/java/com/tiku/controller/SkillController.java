package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.SkillApplyRequest;
import com.tiku.service.QuestionTaggingService;
import com.tiku.service.SkillGraphService;
import com.tiku.service.SkillGraphSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能图与知识点标签（学习路径引擎阶段 0，设计见 docs/learning-path-design.md）。
 *
 * 接口分工：
 * - 技能图是**只读**的受控词表（内置模板；将来支持私有/社区模板时再开写接口）；
 * - 标签是"AI 提建议 + 人拍板"：suggest 产出建议 → pending 看队列（**带全部单题**）→
 *   apply 批量或逐题确认/改挂/丢弃；questions 按状态把题列全（未匹配的题要能逐题打开去修）；
 * - 单题也可以直接标注：{@code PUT /api/questions/{id}/skills}（覆盖 AI 与作者，只影响本机）。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class SkillController {

    private final SkillGraphService graphService;
    private final SkillGraphSyncService graphSyncService;
    private final QuestionTaggingService taggingService;

    public SkillController(SkillGraphService graphService, SkillGraphSyncService graphSyncService,
                           QuestionTaggingService taggingService) {
        this.graphService = graphService;
        this.graphSyncService = graphSyncService;
        this.taggingService = taggingService;
    }

    /** 可用技能模板列表（轻量） */
    @GetMapping("/skills/templates")
    public ApiResponse<List<Map<String, Object>>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillGraphService.SkillTemplate t : graphService.templates()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateId", t.templateId());
            m.put("name", t.name());
            m.put("version", t.version());
            m.put("graphVersion", t.graphVersion());
            m.put("goalHint", t.goalHint());
            m.put("sourceType", t.sourceType());
            m.put("stageCount", t.stageOrder().size());
            m.put("nodeCount", t.nodes().size());
            out.add(m);
        }
        return ApiResponse.success(out);
    }

    /** 一张技能图全貌（阶段 → 节点 → 前置关系），供前端展示与将来的编辑器使用 */
    @GetMapping("/skills/templates/{templateId}")
    public ApiResponse<Map<String, Object>> template(@PathVariable String templateId) {
        SkillGraphService.SkillTemplate t = graphService.template(templateId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (SkillGraphService.NodeView n : t.nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", n.nodeId());
            m.put("name", n.name());
            m.put("stageId", n.stageId());
            m.put("stageName", n.stageName());
            m.put("stageOrder", n.stageOrder());
            m.put("level", n.level());
            m.put("weight", n.weight());
            m.put("optional", n.optional());
            m.put("keywords", n.keywords());
            m.put("prereq", t.prereq().getOrDefault(n.nodeId(), List.of()));
            nodes.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId", t.templateId());
        out.put("name", t.name());
        out.put("version", t.version());
        out.put("graphVersion", t.graphVersion());
        out.put("goalHint", t.goalHint());
        out.put("sourceType", t.sourceType());
        out.put("nodes", nodes);
        return ApiResponse.success(out);
    }

    /** 把内置模板写入数据库（阶段 0 只用于核对与将来的编辑器；读路径走内存图） */
    @PostMapping("/skills/templates/{templateId}/sync")
    public ApiResponse<Map<String, Object>> syncTemplate(@PathVariable String templateId) {
        int nodes = graphSyncService.sync(templateId);
        return ApiResponse.success(Map.of("templateId", templateId, "nodesWritten", nodes));
    }

    /** 触发知识点标注（AI 提建议，落待确认队列；按批推进，可中断续跑） */
    @PostMapping("/banks/{bankId}/skills/suggest")
    public ApiResponse<QuestionTaggingService.SuggestResult> suggest(
            @PathVariable Long bankId,
            @RequestParam String templateId,
            @RequestParam(defaultValue = "false") boolean includeUntagged,
            @RequestParam(required = false) Integer maxAiCalls) {
        return ApiResponse.success(taggingService.suggest(bankId, templateId, includeUntagged, maxAiCalls));
    }

    /**
     * 审阅清单：以**题**为单位，带每题当前标签与状态，界面就地查看/修改。
     * status = all（默认）/ confirmed / pending / untagged；nodeId 可选（只看某知识点的题）。
     *
     * 计数与清单由**同一份快照**算出来（`ReviewPage.counts`），所以界面上的数字与清单条数不可能对不上。
     */
    @GetMapping("/banks/{bankId}/skills/questions")
    public ApiResponse<QuestionTaggingService.ReviewPage> questions(
            @PathVariable Long bankId,
            @RequestParam String templateId,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String nodeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(taggingService.review(bankId, templateId, status, nodeId, page, size));
    }

    /**
     * 批量 / 逐题动作：confirm（确认建议）、set（把标签设定为给定节点）、retag（按节点改挂）、
     * reject（丢弃建议）。界面上的"就地改标签""批量设为知识点""确认""丢弃"都走这里。
     *
     * 作用范围：优先 questionIds；没给题时用 filterStatus（+可选 nodeId）在**后端**解析成题目列表，
     * 这样界面的"全选 N 题"不必把上千个 id 传到前端再传回来。
     */
    @PostMapping("/banks/{bankId}/skills/apply")
    public ApiResponse<Map<String, Object>> apply(@PathVariable Long bankId, @RequestBody SkillApplyRequest request) {
        String templateId = request.templateId();
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("缺少 templateId");
        }
        int affected = taggingService.apply(bankId, templateId, request.action(), request.nodeId(),
                request.newNodes(), request.questionIds(), request.filterStatus());
        return ApiResponse.success(Map.of("affected", affected));
    }

    /**
     * 清理失效标签：技能图升级（或删掉自定义知识点）后，指向已不存在节点的标签。
     * 它们不显示、不参与统计，留着只会让用户在题目详情里看到莫名其妙的原始 id。
     */
    @PostMapping("/banks/{bankId}/skills/cleanup-orphans")
    public ApiResponse<Map<String, Object>> cleanupOrphans(@PathVariable Long bankId,
                                                          @RequestParam String templateId) {
        return ApiResponse.success(Map.of("affected", taggingService.cleanupOrphanTags(bankId, templateId)));
    }

    /** 覆盖地图：路线节点在题库里的题量（<3 题视为证据不足，不参与门控） */
    @GetMapping("/banks/{bankId}/skills/coverage")
    public ApiResponse<QuestionTaggingService.Coverage> coverage(@PathVariable Long bankId,
                                                                @RequestParam String templateId) {
        return ApiResponse.success(taggingService.coverage(bankId, templateId));
    }

    /** 单题标签（题目详情/编辑用）。必须带 templateId：与审阅清单同口径（不会出现别的模板/失效节点的原始 id） */
    @GetMapping("/questions/{questionId}/skills")
    public ApiResponse<List<QuestionTaggingService.QuestionTag>> questionTags(@PathVariable Long questionId,
                                                                             @RequestParam String templateId) {
        return ApiResponse.success(taggingService.tagsOf(questionId, templateId));
    }

    /**
     * 新增自定义知识点（用户自己的词表，只存本机）：受控词表如果只能官方定义，
     * 用户在"图里没有这个知识点"时就没有出路（实测反馈：下拉里能输入但保存不了）。
     * 同名会复用已有节点（内置同名也算），不会造出两个看起来一样的节点。
     */
    @PostMapping("/skills/templates/{templateId}/nodes")
    public ApiResponse<Map<String, Object>> addCustomNode(@PathVariable String templateId,
                                                         @RequestBody Map<String, Object> body) {
        Object name = body.get("name");
        SkillGraphService.NodeView node = graphService.addCustomNode(templateId, name == null ? "" : String.valueOf(name));
        return ApiResponse.success(Map.of("nodeId", node.nodeId(), "name", node.name(),
                "stageId", node.stageId(), "stageName", node.stageName()));
    }

    /** 删除自定义知识点（只能删 custom.*）；它上面的标签会变成失效标签，可在题库里一键清理 */
    @DeleteMapping("/skills/templates/{templateId}/nodes/{nodeId}")
    public ApiResponse<Map<String, Object>> removeCustomNode(@PathVariable String templateId,
                                                            @PathVariable String nodeId) {
        graphService.removeCustomNode(templateId, nodeId);
        return ApiResponse.success(Map.of("nodeId", nodeId));
    }

    /** 用户手动标注单题（覆盖 AI 与作者；只影响本机） */
    @PutMapping("/questions/{questionId}/skills")
    public ApiResponse<Map<String, Object>> setQuestionTags(@PathVariable Long questionId,
                                                           @RequestBody Map<String, Object> body) {
        Object templateId = body.get("templateId");
        if (templateId == null || String.valueOf(templateId).isBlank()) {
            throw new IllegalArgumentException("缺少 templateId");
        }
        List<String> nodeIds = new ArrayList<>();
        Object raw = body.get("nodeIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    nodeIds.add(String.valueOf(o));
                }
            }
        }
        int n = taggingService.setUserTags(questionId, String.valueOf(templateId), nodeIds);
        return ApiResponse.success(Map.of("nodeIds", nodeIds, "written", n));
    }
}
