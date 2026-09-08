package com.tiku.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项（含实时聚合成绩）
 */
public record SessionResponse(
        Long id,
        String mode,
        Integer questionCount,
        Integer answeredCount,
        Integer correctCount,
        Double totalScore,
        Double maxScore,
        Long totalSeconds,
        String status,            //IN_PROGRESS / COMPLETED
        LocalDateTime createdAt,
        LocalDateTime finishedAt
) {
}
