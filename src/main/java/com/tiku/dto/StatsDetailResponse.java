package com.tiku.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习统计二期（D/E/F）聚合响应（GET /api/stats/detail）。
 * - banks：题库 × 掌握度（全库一行；answered = 做过至少一次的题数，decided/correct 为库内作答判定口径）
 * - wrongHeal：错题治愈（当前错题数、近 6 个月错题数趋势、错题复现率、最近治愈题）
 * - recentSessions：最近 10 场完成会话得分概览
 * 口径与 /stats/summary 一致（正确率仅计有判定记录；主观按自评 CORRECT）。
 */
public record StatsDetailResponse(
        List<BankStat> banks,
        WrongHeal wrongHeal,
        List<SessionStat> recentSessions
) {
    /** 题库一行：total=总题数 answered=至少做过一次题数 decided/correct=库内作答判定 */
    public record BankStat(Long bankId, String name, int total, int answered, int decided, int correct) {
    }

    /** 错题治愈 */
    public record WrongHeal(
            int currentWrong,          //当前错题数（最近一次作答判错，含主观 PARTIAL/WRONG 口径）
            List<MonthPoint> wrongTrend, //近 6 个月（含当月）每月末错题数
            int reproduceDecided,      //复现作答判定数（曾错题在首次错误后的再作答题数）
            int reproduceCorrect,      //其中判对数
            List<HealedItem> recentlyHealed //最近治愈（曾错且最近一次答对）前 5 条
    ) {
    }

    public record MonthPoint(String month, int count) {
    }

    public record HealedItem(Long questionId, String bankName, Integer questionNumber,
                             String content, LocalDateTime answeredAt) {
    }

    /** 完成会话一行（得分率 = correct/questionCount，前端按 mode 显示标签） */
    public record SessionStat(Long sessionId, String bankName, String mode,
                              LocalDateTime finishedAt, int questionCount, int answered, int correct) {
    }
}
