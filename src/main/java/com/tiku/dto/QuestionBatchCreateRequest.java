package com.tiku.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量建题请求：POST /api/banks/{id}/questions/batch
 */
public record QuestionBatchCreateRequest(
        @NotEmpty(message = "题目列表不能为空")
        List<@Valid QuestionBatchItemRequest> questions
) {
}
