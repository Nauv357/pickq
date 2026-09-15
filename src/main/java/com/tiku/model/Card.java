package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 闪卡（学习路径引擎阶段 3，设计 §3.5、§7.5）：把解析里的关键结论挖空，考"回忆"而不是"再认"。
 *
 * 几条硬约束（都来自实测教训与设计）：
 * - **必须可溯源**：每张卡都带 question_id，卡片界面能点回原题；
 * - **AI 生成的默认未确认**，未确认的卡**不参与复习调度**（否则一堆没校对过的卡会挤满复习队列）；
 * - 调度沿用与题目同一套阶梯（答对升一级、答错清零），不引入第二套算法；
 * - 卡片只作**抽测证据**：答错能让已过关的节点掉下来，但答对**不足以**把节点判为已掌握。
 */
@TableName("card")
public class Card {

    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_USER = "user";
    public static final String RESULT_REMEMBERED = "REMEMBERED";
    public static final String RESULT_FORGOT = "FORGOT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bankId;
    private String nodeId;
    private Long questionId;
    private String front;
    private String back;
    private String source;
    private Boolean confirmed;
    private Integer level;
    private Integer intervalDays;
    private LocalDateTime dueAt;
    private Integer lapses;
    private String lastResult;
    private LocalDateTime lastReviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getFront() {
        return front;
    }

    public void setFront(String front) {
        this.front = front;
    }

    public String getBack() {
        return back;
    }

    public void setBack(String back) {
        this.back = back;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getIntervalDays() {
        return intervalDays;
    }

    public void setIntervalDays(Integer intervalDays) {
        this.intervalDays = intervalDays;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public Integer getLapses() {
        return lapses;
    }

    public void setLapses(Integer lapses) {
        this.lapses = lapses;
    }

    public String getLastResult() {
        return lastResult;
    }

    public void setLastResult(String lastResult) {
        this.lastResult = lastResult;
    }

    public LocalDateTime getLastReviewedAt() {
        return lastReviewedAt;
    }

    public void setLastReviewedAt(LocalDateTime lastReviewedAt) {
        this.lastReviewedAt = lastReviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
