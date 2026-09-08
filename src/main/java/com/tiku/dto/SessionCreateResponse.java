package com.tiku.dto;

import java.util.List;

/**
 * 创建会话响应：sessionId + 抽取的题目（做题格式，不含答案）
 */
public record SessionCreateResponse(
        Long sessionId,
        Integer total,
        List<QuestionPracticeResponse> questions
) {
}
