package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.util.NetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * AI 模型配置存取（BYOK）：
 * - 配置文件：${tiku.data-dir}/ai-config.json，不入库、不进日志
 * - apiKey 前端只见脱敏（sk-***abc），完整 Key 仅存在于配置文件与本服务内存
 */
@Service
public class AiConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);

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
            log.warn("读取 AI 配置失败，将使用空配置：{}（{}）", configPath, e.getMessage());
            return new AiSettings();
        }
    }

    public synchronized void save(AiSettings settings) {
        Path temporaryPath = null;
        try {
            Files.createDirectories(configPath.getParent());
            temporaryPath = Files.createTempFile(configPath.getParent(), "ai-config-", ".tmp");
            objectMapper.writeValue(temporaryPath.toFile(), settings);
            moveAtomically(temporaryPath, configPath);
        } catch (IOException e) {
            throw new IllegalStateException("保存 AI 配置失败", e);
        } finally {
            if (temporaryPath != null) {
                try {
                    Files.deleteIfExists(temporaryPath);
                } catch (IOException e) {
                    log.warn("清理 AI 配置临时文件失败：{}（{}）", temporaryPath, e.getMessage());
                }
            }
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            log.debug("文件系统不支持 AI 配置原子替换，使用普通替换：{}", target);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 配置是否可用（能否发起 AI 调用）：baseUrl 与 model 必填，apiKey 仅在<b>公网地址</b>上必填。
     * <p>
     * 本机/局域网服务（Ollama / vLLM / LM Studio 等）不校验 Key，允许留空——否则本地用户被迫
     * 填一个占位串才能用（判定口径见 {@link NetAddress}，与地址校验、"是否发鉴权头"共用一套）。
     * AI 导入建任务、测试连接等入口都用本方法把关，故这里放开即整条链路放开。
     */
    public boolean isConfigured() {
        AiSettings s = load();
        if (!notBlank(s.getBaseUrl()) || !notBlank(s.getModel())) {
            return false;
        }
        return notBlank(s.getApiKey()) || NetAddress.isLocalOrPrivate(s.getBaseUrl());
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

    /**
     * 地址规则：https 一律允许；http 仅允许本机与局域网（私网）地址。
     * <p>
     * 放开私网 http 的原因：自建推理服务（Ollama / vLLM / LM Studio 等）通常没有证书，
     * http 是常态，且"Ollama 跑在另一台机器"（如 http://192.168.1.50:11434/v1）是很实际的用法；
     * 公网 http 仍然拒绝（明文传输 Key 与题目内容，风险不可接受）。
     * 本机/私网判定与"是否需要 API Key"共用 {@link NetAddress}，避免两处口径不一致。
     */
    public void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl 必须使用 http(s) 链接");
        }
        if (baseUrl.startsWith("https://")) {
            return;
        }
        if (NetAddress.isLocalOrPrivate(baseUrl)) {
            return;
        }
        throw new IllegalArgumentException("http 仅支持本机或局域网地址（如 192.168.x.x），公网请使用 https");
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
