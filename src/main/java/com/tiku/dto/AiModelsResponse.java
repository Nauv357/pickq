package com.tiku.dto;

import java.util.List;

/**
 * 可用模型列表（响应里绝不包含任何 Key 信息，只有模型名与地址形态）。
 *
 * @param models          模型名列表：保持服务端返回顺序、已去重（LinkedHashSet 保序）
 * @param resolvedBaseUrl 最终发通的地址形态，<b>不含 /models</b>，可直接写回 AI 配置：
 *                        用户填 https://api.deepseek.com 时这里会是 https://api.deepseek.com/v1
 *                        （前端据此提示"地址已自动补全为 /v1"）
 * @param count           models 的元素个数（= models.size()，前端不必自己算）
 */
public record AiModelsResponse(List<String> models, String resolvedBaseUrl, int count) {
}
