package com.tiku.dto;

/**
 * AI 配置保存请求（apiKey 为空 = 保留旧 Key；其余字段空 = 保留旧值）
 */
public record AiSettingsRequest(
        String baseUrl,
        String apiKey,
        String model,
        String visionModel,
        Boolean thinking,
        String mineruKey
) {
}
