package com.tiku.dto;

/**
 * 题库学习进度
 */
public record BankProgressResponse(
        Long bankId,
        Long totalQuestions,      //题库总题数
        Long answeredQuestions,   //已作答的题目数（去重）
        Long recordsCount,        //总作答次数
        Long correctCount,        //答对次数
        double accuracy,          //正确率（答对次数/总作答次数，0~1）
        int progressPercent       //完成度（已做题数/总题数，0~100）
) {
}
