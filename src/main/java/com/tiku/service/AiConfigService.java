package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AI 模型配置存取（BYOK）：
 * - 配置文件：${tiku.data-dir}/ai-config.json，不入库、不进日志
 * - apiKey 前端只见脱敏（sk-***abc），完整 Key 仅存在于配置文件与本服务内存
 */
@Service
public class AiConfigService {

    private final Path configPath;
    private final ObjectMapper objectMapper;

    public AiConfigService(@Value("${tiku.data-dir}") String dataDir, ObjectMapper objectMapper) {
        this.configPath = Path.of(dataDir, "ai-config.json");
        this.objectMapper = objectMapper;
    }

    public AiSettings load() {
        if (!Files.exists(configPath)) {
            return new AiSettings();
        }
        try {
            return objectMapper.readValue(configPath.toFile(), AiSettings.class);
        } catch (IOException e) {
            return new AiSettings();
        }
    }

    public synchronized void save(AiSettings settings) {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), settings);
        } catch (IOException e) {
            throw new IllegalStateException("保存 AI 配置失败", e);
        }
    }

    public boolean isConfigured() {
        AiSettings s = load();
        return notBlank(s.getBaseUrl()) && notBlank(s.getApiKey()) && notBlank(s.getModel());
    }

    /** 脱敏：sk-abc123def → sk-***def */
    public String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (apiKey.length() <= 6) {
            return "***";
        }
        return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
    }

    /** 保存请求：apiKey 为空 = 保留旧 Key；其余字段空 = 保留旧值 */
    public AiSettings merge(AiSettings current, String baseUrl, String apiKey, String model, String visionModel, Boolean thinking, String mineruKey) {
        AiSettings merged = new AiSettings();
        merged.setBaseUrl(notBlank(baseUrl) ? baseUrl : current.getBaseUrl());
        merged.setApiKey(notBlank(apiKey) ? apiKey : current.getApiKey());
        merged.setModel(notBlank(model) ? model : current.getModel());
        merged.setVisionModel(notBlank(visionModel) ? visionModel : current.getVisionModel());
        //thinking 是布尔开关：null 表示前端未传，保留旧值
        merged.setThinking(thinking != null ? thinking : current.getThinking());
        //mineruKey 为空 = 保留旧 Key（与 apiKey 同语义）
        merged.setMineruKey(notBlank(mineruKey) ? mineruKey : current.getMineruKey());
        return merged;
    }

    /** 是否已配置 MinerU 解析 Key（决定 AI 导入是否走 MinerU 结构化解析路径） */
    public boolean isMineruConfigured() {
        return notBlank(load().getMineruKey());
    }

    /** 仅允许 https（或 localhost，便于本地 Ollama 调试） */
    public void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        boolean https = baseUrl.startsWith("https://");
        boolean localhost = baseUrl.startsWith("http://localhost") || baseUrl.startsWith("http://127.0.0.1");
        if (!https && !localhost) {
            throw new IllegalArgumentException("baseUrl 必须使用 https（本地 Ollama 可用 http://localhost）");
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
