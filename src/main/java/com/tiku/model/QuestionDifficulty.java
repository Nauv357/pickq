package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 每题难度（学习路径引擎阶段 3，设计 §5.5 的 Elo-lite）。
 *
 * 用途：按"预测成功率 80%–90%"挑题（85% 规则）——太简单不涨水平，太难只挫伤信心。
 * 初值按题型估计（主观题最难、判断最易），之后每次作答更新：
 * {@code d_q ← d_q − K×(实际 − 预测)}（答对比预期好 → 这题显得更简单 → 难度下调）。
 */
@TableName("question_difficulty")
public class QuestionDifficulty {

    @TableId(type = IdType.INPUT)
    private Long questionId;

    private Double difficulty;
    private Integer attempts;
    private LocalDateTime updatedAt;

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Double getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Double difficulty) {
        this.difficulty = difficulty;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
