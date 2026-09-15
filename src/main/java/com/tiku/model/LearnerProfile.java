package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个体输入（学习路径引擎阶段 2，设计 §3.3、§7.2）：目标模板、目标原话、每日题量/时间预算。
 * 本机单用户，固定一行 id=1。
 */
@TableName("learner_profile")
public class LearnerProfile {

    public static final Long SINGLE_ROW = 1L;

    @TableId(type = IdType.INPUT)
    private Long id;

    private String goalTemplateId;
    private String goalText;
    private Integer dailyQuestions;
    private Integer dailyMinutes;
    private LocalDate targetDate;
    private String baselineJson;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGoalTemplateId() {
        return goalTemplateId;
    }

    public void setGoalTemplateId(String goalTemplateId) {
        this.goalTemplateId = goalTemplateId;
    }

    public String getGoalText() {
        return goalText;
    }

    public void setGoalText(String goalText) {
        this.goalText = goalText;
    }

    public Integer getDailyQuestions() {
        return dailyQuestions;
    }

    public void setDailyQuestions(Integer dailyQuestions) {
        this.dailyQuestions = dailyQuestions;
    }

    public Integer getDailyMinutes() {
        return dailyMinutes;
    }

    public void setDailyMinutes(Integer dailyMinutes) {
        this.dailyMinutes = dailyMinutes;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public String getBaselineJson() {
        return baselineJson;
    }

    public void setBaselineJson(String baselineJson) {
        this.baselineJson = baselineJson;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
