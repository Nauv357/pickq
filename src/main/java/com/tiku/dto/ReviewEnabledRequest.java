package com.tiku.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 启用/关闭题库复习计划：PUT /api/banks/{id}/review-enabled
 */
public record ReviewEnabledRequest(
        @NotNull(message = "状态不能为空")
        Boolean enabled
) {
}
