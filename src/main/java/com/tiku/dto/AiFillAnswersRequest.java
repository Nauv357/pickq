package com.tiku.dto;

import com.tiku.model.enums.QuestionType;

/**
 * AI 批量补答案请求：POST /api/banks/{id}/questions/ai-fill-answers
 * questionType：只补该题型（可空 = 全部客观题）；withAnalysis：同时补解析（仅当题无解析时）。
 */
public record AiFillAnswersRequest(
        QuestionType questionType,
        Boolean withAnalysis
) {
}
