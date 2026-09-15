package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当天任务（学习路径引擎阶段 2，设计 §3.4、§7.1）：外缘主攻节点的 n 道新题 + 到期复习题。
 *
 * 为什么要落库（而不是每次现算）：题单必须在**当天冻结**——否则用户答掉一题，清单立刻变样，
 * "今天 3/20" 会莫名其妙跳动。生成是确定性的（同一天同样的输入 → 同样的清单）。
 */
@TableName("daily_task")
public class DailyTask {

    public static final String KIND_PRACTICE = "PRACTICE";
    public static final String KIND_REVIEW = "REVIEW";
    /** 抽测（阶段 3）：已过关节点按间隔复测 1–2 题，用来打掉"假掌握" */
    public static final String KIND_SPOT_CHECK = "SPOT_CHECK";
    /** 闪卡复习（阶段 3）：只统计已确认的卡；不占"新题"额度 */
    public static final String KIND_CARD = "CARD";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 题单按**题库**冻结：同一个用户有多个题库时不能共用一份今天的题单 */
    private Long bankId;
    private LocalDate taskDate;
    private String kind;
    private String nodeId;
    private String templateId;
    private String planJson;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBankId() {
        return bankId;
    }

    public void setBankId(Long bankId) {
        this.bankId = bankId;
    }

    public LocalDate getTaskDate() {
        return taskDate;
    }

    public void setTaskDate(LocalDate taskDate) {
        this.taskDate = taskDate;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
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

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
