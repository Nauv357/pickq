package com.tiku.dto;

/**
 * 主观题自评结果
 *
 * @param recordId    刷题记录 id
 * @param selfGrade   CORRECT / PARTIAL / WRONG
 * @param earnedScore 该题按自评应得分数（对=score，部分对=score/2，错=0）
 */
public record SelfGradeResponse(
        Long recordId,
        String selfGrade,
        Double earnedScore
) {
}
