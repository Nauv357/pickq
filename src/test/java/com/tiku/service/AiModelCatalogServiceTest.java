package com.tiku.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 鉴权头形态（纯单测，不连公网）：
 * 默认 OpenAI 兼容用 Authorization: Bearer；host 含 anthropic.com 时改用 x-api-key + anthropic-version；
 * Key 为空（本机/局域网 Ollama 场景）不带任何鉴权头。
 */
class AiModelCatalogServiceTest {

    @Test
    void bearerForOpenAiCompatibleHosts() {
        for (String url : List.of("https://api.deepseek.com/v1/models",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
                "http://127.0.0.1:11434/v1/models")) {
            assertEquals(List.of(new AiModelCatalogService.AuthHeader("Authorization", "Bearer k-1")),
                    AiModelCatalogService.authHeaders(url, "k-1"), url);
        }
    }

    @Test
    void apiKeyHeaderForAnthropicHosts() {
        assertEquals(List.of(
                        new AiModelCatalogService.AuthHeader("x-api-key", "sk-ant-1"),
                        new AiModelCatalogService.AuthHeader("anthropic-version", "2023-06-01")),
                AiModelCatalogService.authHeaders("https://api.anthropic.com/v1/models", "sk-ant-1"));
    }

    /** 本机/局域网服务（Ollama）不需要 Key：不带任何鉴权头（多送 Bearer 反而可能被网关拒绝） */
    @Test
    void noAuthHeaderWhenKeyIsBlank() {
        for (String url : List.of("http://127.0.0.1:11434/v1/models",
                "http://192.168.1.50:11434/api/tags",
                "https://api.deepseek.com/v1/models")) {
            assertEquals(List.of(), AiModelCatalogService.authHeaders(url, null), url);
            assertEquals(List.of(), AiModelCatalogService.authHeaders(url, ""), url);
            assertEquals(List.of(), AiModelCatalogService.authHeaders(url, "   "), url);
        }
    }
}
