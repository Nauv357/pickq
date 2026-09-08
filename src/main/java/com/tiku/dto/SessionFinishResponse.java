package com.tiku.dto;

import java.util.List;

/**
 * 交卷报告（POST /api/sessions/{id}/finish 响应）
 *
 * @param totalQuestions 总题数
 * @param answeredCount  已答数（允许未答完交卷）
 * @param correctCount   答对数（客观题判对 + 主观题自评对）
 * @param totalScore     得分（客观题答对满分；主观题按自评：对=满分、部分对=一半、错/未自评=0）
 * @param maxScore       满分
 * @param totalSeconds   总用时（交卷时间 - 会话开始时间）
 * @param questions      每题用时与结果
 */
public record SessionFinishResponse(
        Long sessionId,
        Integer totalQuestions,
        Integer answeredCount,
        Integer correctCount,
        Double totalScore,
        Double maxScore,
        Long totalSeconds,
        List<SessionQuestionReportItem> questions
) {
}
