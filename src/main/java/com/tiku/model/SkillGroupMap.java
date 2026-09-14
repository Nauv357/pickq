package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分组映射缓存：两级映射的第一级（作者 topic/category → 技能节点）。
 * 命中缓存就不再调 AI；同时它是**用户批量纠错的单位**（改一组 = 改一类题）。
 * graphVersion 变化（模板更新）后自动失效重算。
 */
@TableName("skill_group_map")
public class SkillGroupMap {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String templateId;
    /** T:topic 或 C:category */
    private String groupKey;
    private String graphVersion;
    /** JSON：[{"nodeId":"...","confidence":0.9}] */
    private String nodesJson;
    /** ai / user */
    private String source;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public String getGraphVersion() {
        return graphVersion;
    }

    public void setGraphVersion(String graphVersion) {
        this.graphVersion = graphVersion;
    }

    public String getNodesJson() {
        return nodesJson;
    }

    public void setNodesJson(String nodesJson) {
        this.nodesJson = nodesJson;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
