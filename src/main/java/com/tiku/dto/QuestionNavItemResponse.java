package com.tiku.dto;

import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;

/**
 * 题号导航项（题库详情右侧导航目录用）：
 * 与题目列表同排序同过滤（questionNumber ASC + id ASC），只含轻量字段，全量返回。
 */
public record QuestionNavItemResponse(
        Long questionId,
        QuestionType questionType,
        Integer questionNumber
) {
    public static QuestionNavItemResponse fromEntity(Question q) {
        return new QuestionNavItemResponse(q.getId(), q.getQuestionType(), q.getQuestionNumber());
    }
}
