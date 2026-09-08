package com.tiku.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 主观题自评请求：PUT /api/study-records/{id}/self-grade
 * earnedScore：实得分（0 ~ 题目满分，自由给分，如 8 分大题给 6 分）；
 * 后端派生 selfGrade（满分=CORRECT / 0=WRONG / 中间=PARTIAL）供对错统计（非满分仍按答错参与错题本/复习）。
 */
public record SelfGradeRequest(
        @NotNull(message = "自评得分不能为空")
        Double earnedScore
) {
}
