package com.tiku.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 暂停/恢复复习：PUT /api/questions/{id}/review-suspend
 */
public record ReviewSuspendRequest(
        @NotNull(message = "状态不能为空")
        Boolean suspended
) {
}
