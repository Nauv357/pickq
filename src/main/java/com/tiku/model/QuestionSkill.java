package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 题目标签：题目 ↔ 技能节点。
 * source 表示这一行当前由谁定（user &gt; author &gt; ai）；AI 结果默认 confirmed=0，进待确认队列。
 * 门控只用「已确认」或「高置信 AI」标签（见 docs/learning-path-design.md §4.2、§5.3）。
 */
@TableName("question_skill")
public class QuestionSkill {

    /** 参与门控所需的最低 AI 置信度 */
    public static final double GATE_MIN_CONFIDENCE = 0.8;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;
    private Long bankId;
    private String nodeId;
    private String templateId;
    /** user / author / ai */
    private String source;
    private Double confidence;
    private Boolean confirmed;
    /** topic-map / ai-direct / manual */
    private String origin;
    /** 被更高来源覆盖（保留历史，用于提示冲突） */
    private Boolean shadowed;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getBankId() {
        return bankId;
    }

    public void setBankId(Long bankId) {
        this.bankId = bankId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public Boolean getShadowed() {
        return shadowed;
    }

    public void setShadowed(Boolean shadowed) {
        this.shadowed = shadowed;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
