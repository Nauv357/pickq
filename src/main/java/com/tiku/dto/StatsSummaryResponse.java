package com.tiku.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习统计聚合响应（GET /api/stats/summary）。
 * 口径说明：
 * - 「作答数」= study_record 条数（一次作答一条，同题多次作答多次计）；
 * - 「正确率」= 有判定记录（客观 correct 非空，或主观已自评）中 effectiveGrade=CORRECT 占比；
 *   主观 PARTIAL 计入分母（半对不算对），无答案客观题作答（未判）计入题量但不进正确率；
 * - daily/dueForecast 日期均为 yyyy-MM-dd（本地日期，按 answered_at/due_at 归属）。
 */
public record StatsSummaryResponse(
        // ---- KPI ----
        int todayCount,          //今日作答数
        int todayDecided,        //今日有判定数（正确率分母）
        int todayCorrect,        //今日判对数
        int streakDays,          //连续学习天数（今天有作答则含今天，否则截至昨天）
        int longestStreak,       //历史最长连续
        int totalAnswered,       //累计作答数
        int decidedTotal,        //累计有判定数
        int correctTotal,        //累计判对数
        long totalSeconds,       //累计计时秒（仅交卷会话题有 seconds；统计口径角标由前端展示）
        int dueToday,            //今日到期 + 逾期总数（!suspended）
        int overdue,             //其中逾期（due_at < 今天）数
        int reviewQuestionCount, //有复习状态的题数（levelDist 之和）
        // ---- 日粒度（最近 365 天，含今天，无记录补 0；热力图与趋势共用） ----
        List<DailyStat> daily,
        // ---- 复习到期预测（今天起 30 天；今天的 count = 今日到期+逾期，前端红色高亮） ----
        List<DueDay> dueForecast,
        // ---- 熟练度分布：level 0..5 各有复习状态的题数（0=新进队列未熟练…5=已稳定） ----
        int[] levelDist
) {
    public record DailyStat(String date, int count, int decided, int correct) {
    }

    public record DueDay(String date, int count) {
    }
}
