package com.tiku.dto;

import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;

import java.time.LocalDateTime;

/**
 * 待复习队列项：题目（做题格式，无答案）+ 复习状态
 * 主观题附带参考答案（复习时对照自评）。
 * overdueDays：已逾期天数（0 = 今天到期/未逾期；>0 = 到期日早于今天），
 * 前端据此展示"已逾期 N 天"弱标记，区分历史欠账与今日到期。
 */
public record ReviewDueItemResponse(
        Long questionId,
        QuestionType questionType,
        String typeLabel,
        Integer questionNumber,
        String content,
        java.util.List<OptionItem> options,
        Double score,
        String category,
        String topic,
        Integer level,
        Integer intervalDays,
        LocalDateTime dueAt,
        Long overdueDays,
        String referenceAnswer
) {
    public static ReviewDueItemResponse fromEntity(Question question, Integer level, Integer intervalDays,
                                                   LocalDateTime dueAt, Long overdueDays) {
        return new ReviewDueItemResponse(
                question.getId(),
                question.getQuestionType(),
                question.getQuestionType().getLabel(),
                question.getQuestionNumber(),
                question.getContent(),
                question.getOptions(),
                question.getScore(),
                question.getCategory(),
                question.getTopic(),
                level, intervalDays, dueAt, overdueDays,
                question.getReferenceAnswer()
        );
    }
}
