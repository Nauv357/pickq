package com.tiku.dto;

/**
 * AI 配置响应（Key 脱敏，前端永不接触完整 Key）
 */
public record AiSettingsResponse(
        String baseUrl,
        boolean hasKey,
        String maskedKey,
        String model,
        String visionModel,
        Boolean thinking,
        boolean hasMineruKey,
        String maskedMineruKey
) {
}
