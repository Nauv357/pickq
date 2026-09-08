package com.tiku.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 收藏/取消收藏题目：PUT /api/questions/{id}/favorite
 */
public record FavoriteRequest(
        @NotNull(message = "收藏状态不能为空")
        Boolean favorite
) {
}
