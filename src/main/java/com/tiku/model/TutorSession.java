package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 追问会话（学习路径引擎阶段 1，设计见 docs/learning-path-design.md §3.5、§7.4）。
 *
 * kind=PER_QUESTION 表示单题追问（做题中要提示、或复盘里问"为什么错"）；
 * kind=POST_REVIEW 表示整场练习的复盘（question_id 为空）。
 * 答错即问的快捷三选写进 self_reason：这是"错因"的第一手数据，比模型猜准得多。
 */
@TableName("tutor_session")
public class TutorSession {

    public static final String KIND_PER_QUESTION = "PER_QUESTION";
    public static final String KIND_POST_REVIEW = "POST_REVIEW";

    /** 错因：看错/蒙的 · 这个知识点不会 · 完全没见过 */
    public static final String REASON_CARELESS = "CARELESS";
    public static final String REASON_NO_KNOWLEDGE = "NO_KNOWLEDGE";
    public static final String REASON_NEVER_SEEN = "NEVER_SEEN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bankId;
    private Long questionId;
    private Long practiceSessionId;
    private String kind;
    private String selfReason;
    private String selfNote;
    private String status;
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

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getPracticeSessionId() {
        return practiceSessionId;
    }

    public void setPracticeSessionId(Long practiceSessionId) {
        this.practiceSessionId = practiceSessionId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSelfReason() {
        return selfReason;
    }

    public void setSelfReason(String selfReason) {
        this.selfReason = selfReason;
    }

    public String getSelfNote() {
        return selfNote;
    }

    public void setSelfNote(String selfNote) {
        this.selfNote = selfNote;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
