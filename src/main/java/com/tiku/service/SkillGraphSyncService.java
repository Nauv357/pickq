package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tiku.mapper.SkillEdgeMapper;
import com.tiku.mapper.SkillNodeMapper;
import com.tiku.model.SkillEdge;
import com.tiku.model.SkillNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 把内置技能模板写入数据库（skill_node / skill_edge）。
 *
 * 阶段 0 的读路径走内存图（{@link SkillGraphService}，无状态、无漂移），
 * 这里的入库是给**将来的模板编辑器**（阶段 2：用户私有图、社区模板）准备同一张表的读写能力。
 * 先落库一次即验证了建表与字段口径；模板内容变更时整体替换（先清后写，保证与 JSON 完全一致）。
 */
@Slf4j
@Service
public class SkillGraphSyncService {

    private final SkillGraphService graphService;
    private final SkillNodeMapper nodeMapper;
    private final SkillEdgeMapper edgeMapper;

    public SkillGraphSyncService(SkillGraphService graphService, SkillNodeMapper nodeMapper, SkillEdgeMapper edgeMapper) {
        this.graphService = graphService;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
    }

    /** 写入/刷新某模板的节点与前置边，返回写入的节点数 */
    @Transactional
    public int sync(String templateId) {
        SkillGraphService.SkillTemplate template = graphService.template(templateId);
        nodeMapper.deleteByTemplate(templateId);
        edgeMapper.deleteByTemplate(templateId);

        LocalDateTime now = LocalDateTime.now();
        for (SkillGraphService.NodeView n : template.nodes()) {
            SkillNode row = new SkillNode();
            row.setTemplateId(templateId);
            row.setNodeId(n.nodeId());
            row.setName(n.name());
            row.setParentId(n.parentId());
            row.setLevel(n.level());
            row.setWeight(n.weight());
            row.setOptional(n.optional());
            row.setKeywords(String.join(",", n.keywords()));
            row.setStageId(n.stageId());
            row.setStageName(n.stageName());
            row.setStageOrder(n.stageOrder());
            row.setUpdatedAt(now);
            nodeMapper.insert(row);
        }
        for (SkillGraphService.NodeView n : template.nodes()) {
            for (String pre : template.prereq().getOrDefault(n.nodeId(), List.of())) {
                SkillEdge edge = new SkillEdge();
                edge.setTemplateId(templateId);
                edge.setFromId(pre);
                edge.setToId(n.nodeId());
                edge.setType("prereq");
                edgeMapper.insert(edge);
            }
        }
        log.info("技能模板已入库：{}（{} 节点，{} 前置边）", templateId, template.nodes().size(),
                (int) template.prereq().values().stream().mapToLong(List::size).sum());
        return template.nodes().size();
    }

    /** 数据库里的节点数（用于核对同步结果） */
    public int countNodes(String templateId) {
        return Math.toIntExact(nodeMapper.selectCount(new LambdaQueryWrapper<SkillNode>()
                .eq(SkillNode::getTemplateId, templateId)));
    }
}
