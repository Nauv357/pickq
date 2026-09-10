package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * baseUrl 校验规则（被 /api/ai/settings 保存路径与 /api/ai/models 共用）：
 * https 一律允许；http 仅本机与私网段（自建 Ollama/vLLM 跑在局域网另一台机器上）；
 * 公网 http 仍拒绝（明文传 Key 与题目内容）。纯校验，不发任何请求。
 */
class AiConfigServiceTest {

    private static final String HTTP_PUBLIC_MESSAGE = "http 仅支持本机或局域网地址（如 192.168.x.x），公网请使用 https";

    private static AiConfigService service(Path tempDir) {
        return new AiConfigService(tempDir.toString(), new ObjectMapper());
    }

    @Test
    void httpsAlwaysAllowed(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        for (String url : new String[]{
                "https://api.deepseek.com",
                "https://api.deepseek.com/v1",
                "https://localhost:11434/v1",
                "https://192.168.1.50:11434/v1"}) {
            assertDoesNotThrow(() -> service.validateBaseUrl(url), url);
        }
    }

    @Test
    void httpAllowedForLocalAndPrivateRanges(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        for (String url : new String[]{
                "http://localhost:11434/v1",
                "http://127.0.0.1:11434/v1",
                "http://[::1]:11434/v1",
                "http://10.0.0.7:8000/v1",
                "http://172.16.3.4:8000/v1",
                "http://172.31.9.9:8000/v1",
                "http://192.168.1.50:11434/v1"}) {
            assertDoesNotThrow(() -> service.validateBaseUrl(url), url);
        }
    }

    @Test
    void httpPublicStillRejected(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        for (String url : new String[]{
                "http://example.com/v1",
                "http://api.deepseek.com/v1",
                "http://8.8.8.8:8080/v1",
                "http://172.32.0.1:8000/v1",
                "http://192.169.1.1:8000/v1"}) {
            assertEquals(HTTP_PUBLIC_MESSAGE,
                    assertThrows(IllegalArgumentException.class, () -> service.validateBaseUrl(url)).getMessage(), url);
        }
    }

    @Test
    void nonHttpSchemeAndBlankRejected(@TempDir Path tempDir) {
        AiConfigService service = service(tempDir);
        assertEquals("baseUrl 必须使用 http(s) 链接",
                assertThrows(IllegalArgumentException.class, () -> service.validateBaseUrl("ftp://192.168.1.50")).getMessage());
        assertEquals("baseUrl 不能为空",
                assertThrows(IllegalArgumentException.class, () -> service.validateBaseUrl("  ")).getMessage());
        assertEquals("baseUrl 不能为空",
                assertThrows(IllegalArgumentException.class, () -> service.validateBaseUrl(null)).getMessage());
    }

    /**
     * isConfigured()（AI 导入建任务、测试连接等入口共用）：baseUrl + model 必填；
     * apiKey 仅在公网地址上必填——本机/局域网 Ollama 无 Key 也算已配置。
     */
    @Test
    void isConfiguredAllowsBlankKeyOnLocalAndPrivateAddresses(@TempDir Path tempDir) throws Exception {
        AiConfigService service = service(tempDir);

        // 本机/局域网 + 无 Key（或空白 Key）+ 有模型 → 可用
        for (String baseUrl : new String[]{"http://localhost:11434/v1", "http://127.0.0.1:11434/v1",
                "http://192.168.1.50:11434/v1", "http://10.0.0.9:8000/v1"}) {
            write(tempDir, baseUrl, null, "qwen2.5");
            assertTrue(service.isConfigured(), baseUrl + " 无 Key 应算已配置");
            write(tempDir, baseUrl, "   ", "qwen2.5");
            assertTrue(service.isConfigured(), baseUrl + " 空白 Key 应算已配置");
        }

        // 公网 + 无 Key → 未配置（仍会拦住并提示填 Key）
        write(tempDir, "https://api.deepseek.com/v1", null, "deepseek-chat");
        assertFalse(service.isConfigured(), "公网地址无 Key 不算已配置");
        write(tempDir, "https://api.deepseek.com/v1", "sk-1", "deepseek-chat");
        assertTrue(service.isConfigured(), "公网地址有 Key 应算已配置");

        // 缺 baseUrl / model 一律未配置（本机地址也不能只填一半）
        write(tempDir, "http://localhost:11434/v1", null, null);
        assertFalse(service.isConfigured(), "缺模型不算已配置");
        write(tempDir, null, "sk-1", "m");
        assertFalse(service.isConfigured(), "缺地址不算已配置");
    }

    /** 写一份 ai-config.json（null 字段写 null） */
    private static void write(Path dataDir, String baseUrl, String apiKey, String model) throws Exception {
        String json = "{\"baseUrl\":" + json(baseUrl) + ",\"apiKey\":" + json(apiKey)
                + ",\"model\":" + json(model) + "}";
        java.nio.file.Files.writeString(dataDir.resolve("ai-config.json"), json);
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
