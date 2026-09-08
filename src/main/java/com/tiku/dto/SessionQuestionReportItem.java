package com.tiku.dto;

/**
 * 交卷报告中的单题项
 *
 * @param questionId  题目 id
 * @param correct     作答结果（客观题判题结果；主观题未自评为 null）
 * @param score       本题分值
 * @param seconds     本题用时（相邻作答时间差；未作答为 null）
 * @param earnedScore 本题实得分数（客观题：对=score/错=0；主观题：自评对=score、部分对=score/2、错或未自评=0）
 * @param selfGrade   主观题自评结果 CORRECT/PARTIAL/WRONG；客观题为 null
 */
public record SessionQuestionReportItem(
        Long questionId,
        Boolean correct,
        Double score,
        Long seconds,
        Double earnedScore,
        String selfGrade
) {
}
