package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.service.AiClientService;
import com.tiku.service.AiConfigService;
import com.tiku.service.AiImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/ai/settings 保存配置时的 Key 必填性：
 * 本机/局域网 baseUrl（Ollama 等）允许 apiKey 留空保存（本地用户不必填占位串）；
 * 公网 baseUrl 仍必须填 Key，错误文案保持原样「请填写 apiKey」。
 * <p>
 * 判定针对<b>合并后的最终 baseUrl</b>（用户可能只改地址不改 Key，或反之），故用例覆盖"改地址"路径。
 * standalone MockMvc + @TempDir 数据目录：不碰真实 ~/.tiku，也不加载数据库。
 */
class AiSettingsSaveTest {

    private ObjectMapper objectMapper;
    private AiConfigService aiConfigService;
    private MockMvc mockMvc;
    private Path dataDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dataDir = tempDir;
        objectMapper = new ObjectMapper();
        aiConfigService = new AiConfigService(dataDir.toString(), objectMapper);
        // AiImportService / AiClientService 只作构造参数，保存配置路径用不到
        mockMvc = standalone(new AiImportController(mock(AiImportService.class), aiConfigService,
                mock(AiClientService.class)));
    }

    private static MockMvc standalone(AiImportController controller) {
        View errorView = new View() {
            @Override
            public String getContentType() {
                return "application/json";
            }

            @Override
            public void render(Map<String, ?> model, HttpServletRequest req, HttpServletResponse resp) {
                // 测试不渲染错误页
            }
        };
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(errorView))
                .build();
    }

    private AiSettings saved() throws Exception {
        return objectMapper.readValue(dataDir.resolve("ai-config.json").toFile(), AiSettings.class);
    }

    private void writeConfig(String baseUrl, String apiKey, String model) throws Exception {
        Files.writeString(dataDir.resolve("ai-config.json"),
                "{\"baseUrl\":\"" + baseUrl + "\",\"apiKey\":" + (apiKey == null ? "null" : "\"" + apiKey + "\"")
                        + ",\"model\":\"" + model + "\"}");
    }

    /** 本机地址 + 无 Key：保存成功，且配置被视为"可用"（AI 导入/测试连接入口不再拦） */
    @Test
    void localBaseUrlSavesWithoutKey() throws Exception {
        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://localhost:11434/v1\",\"model\":\"qwen2.5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AiSettings saved = saved();
        assertEquals("http://localhost:11434/v1", saved.getBaseUrl());
        assertEquals("qwen2.5", saved.getModel());
        assertTrue(saved.getApiKey() == null || saved.getApiKey().isBlank(), "本机地址应允许 Key 留空保存");
        assertTrue(aiConfigService.isConfigured(), "本机地址无 Key 也算已配置（AI 导入/测试连接可继续）");
    }

    /** 局域网地址（Ollama 跑在另一台机器）+ 无 Key / 空串 Key：保存成功 */
    @Test
    void privateBaseUrlSavesWithoutKey() throws Exception {
        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://192.168.1.50:11434/v1\",\"model\":\"qwen2.5\"}"))
                .andExpect(status().isOk());
        assertEquals("http://192.168.1.50:11434/v1", saved().getBaseUrl());

        // 空串 Key 与"未填"同义（前端可能送 ""）
        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://192.168.1.50:11434/v1\",\"apiKey\":\"\",\"model\":\"qwen2.5\"}"))
                .andExpect(status().isOk());
        assertTrue(saved().getApiKey() == null || saved().getApiKey().isBlank());
    }

    /** 公网地址 + 无 Key：仍拒绝，文案不变，且不落盘 */
    @Test
    void publicBaseUrlWithoutKeyStillRejected() throws Exception {
        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://api.deepseek.com/v1\",\"model\":\"deepseek-chat\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请填写 apiKey"));
        assertFalse(Files.exists(dataDir.resolve("ai-config.json")), "被拒绝的保存不应落盘");
    }

    /** 公网地址 + 已有 Key（本次不传 Key）：沿用旧 Key，照常保存成功（不能误伤） */
    @Test
    void publicBaseUrlKeepsExistingKey() throws Exception {
        writeConfig("https://api.deepseek.com/v1", "sk-old-1", "deepseek-chat");

        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://api.deepseek.com/v1\",\"model\":\"deepseek-reasoner\"}"))
                .andExpect(status().isOk());

        AiSettings saved = saved();
        assertEquals("sk-old-1", saved.getApiKey(), "未传 Key 应沿用旧 Key");
        assertEquals("deepseek-reasoner", saved.getModel());
    }

    /** 关键：判定用"合并后的最终 baseUrl"——本地无 Key 的旧配置改成公网地址时，必须要求补 Key */
    @Test
    void switchingFromLocalToPublicWithoutKeyIsRejected() throws Exception {
        writeConfig("http://192.168.1.50:11434/v1", null, "qwen2.5");

        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://api.deepseek.com/v1\",\"model\":\"deepseek-chat\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请填写 apiKey"));
        assertEquals("http://192.168.1.50:11434/v1", saved().getBaseUrl(), "被拒绝时不改旧配置");

        // 补上 Key 后即可切到公网
        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://api.deepseek.com/v1\",\"apiKey\":\"sk-new-1\",\"model\":\"deepseek-chat\"}"))
                .andExpect(status().isOk());
        assertEquals("https://api.deepseek.com/v1", saved().getBaseUrl());
        assertEquals("sk-new-1", saved().getApiKey());
    }

    /** 反向：公网 + Key 的旧配置改成局域网地址时不传 Key → 允许（旧 Key 保留，也可为空） */
    @Test
    void switchingFromPublicToLocalWithoutKeyIsAllowed() throws Exception {
        writeConfig("https://api.deepseek.com/v1", "sk-old-1", "deepseek-chat");

        mockMvc.perform(post("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://localhost:11434/v1\",\"model\":\"qwen2.5\"}"))
                .andExpect(status().isOk());

        AiSettings saved = saved();
        assertEquals("http://localhost:11434/v1", saved.getBaseUrl());
        assertEquals("sk-old-1", saved.getApiKey(), "未传 Key 应沿用旧 Key（局域网下用不到，但不应被清掉）");
        assertTrue(aiConfigService.isConfigured());
    }

    /** 读取配置接口不受影响：无 Key 的本地配置返回 hasKey=false、maskedKey=null（前端据此提示可选） */
    @Test
    void getSettingsReportsNoKeyForLocalConfig() throws Exception {
        writeConfig("http://localhost:11434/v1", null, "qwen2.5");

        mockMvc.perform(get("/api/ai/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseUrl").value("http://localhost:11434/v1"))
                .andExpect(jsonPath("$.data.hasKey").value(false))
                .andExpect(jsonPath("$.data.model").value("qwen2.5"));
    }
}
