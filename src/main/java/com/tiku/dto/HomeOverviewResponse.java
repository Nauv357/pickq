package com.tiku.dto;

import java.time.LocalDateTime;

/**
 * 主页概览（题库列表页顶部卡带）：跨库轻量聚合。
 */
public record HomeOverviewResponse(
        long bankCount,      //题库总数
        long questionCount,  //题目总数
        long dueTotal,       //今日待复习（仅复习已启用的题库）
        int wrongTotal,      //错题数（最近一次作答为错，跨库）
        LastSession lastSession //最近一场练习（无则 null）
) {
    public record LastSession(
            Long sessionId,
            Long bankId,
            String bankName,
            String mode,
            String modeLabel,
            int correctCount,
            int answeredCount,
            LocalDateTime finishedAt
    ) {
    }
}
