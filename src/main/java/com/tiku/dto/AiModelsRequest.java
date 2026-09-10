package com.tiku.dto;

/**
 * 列出可用模型的请求体：{ baseUrl, apiKey? }。
 * - baseUrl：用户填写的 OpenAI 兼容端点（可带也可不带 /v1），后端会依次尝试候选地址；
 * - apiKey：缺省/null/空 = 用本地 ai-config.json 里已保存的 Key；
 *   该字段只用于本次请求的鉴权头，不写日志、不进响应。
 */
public record AiModelsRequest(String baseUrl, String apiKey) {
}
