package com.tiku.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话详情：会话信息 + 题目列表（含每题的作答结果、用时、答案与解析——交卷后完整）
 */
public record SessionDetailResponse(
        Long id,
        Long bankId,
        String mode,
        Integer questionCount,
        Integer answeredCount,
        Integer correctCount,
        Double totalScore,
        Double maxScore,
        Long totalSeconds,
        String status,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        List<SessionQuestionItem> questions
) {
}
