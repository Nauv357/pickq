package com.tiku.dto;

import jakarta.validation.constraints.NotBlank;

/** 保存题目解析请求（AI 单题解析 → 写入 analysis 字段） */
public record SaveAnalysisRequest(
        @NotBlank(message = "解析内容不能为空") String analysis
) {
}
