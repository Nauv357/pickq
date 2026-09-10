package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.util.NetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型服务的"可用模型列表"探测（接口 POST /api/ai/models 的数据来源）。
 * <p>
 * 存在意义：BYOK 场景下用户填的 baseUrl 形态五花八门（{@code https://api.deepseek.com} 与
 * {@code https://api.deepseek.com/v1} 都有人填），而模型列表可能挂在 {@code {base}/models}
 * 也可能挂在 {@code {base}/v1/models}。这里<b>依次尝试候选地址</b>，取第一个"HTTP 成功且能解析出模型名"
 * 的作为结果，并把发通的形态（不含 /models）返回给前端，供设置页回填/提示。
 * <p>
 * 约定（前端会原样展示错误文案，故分支要可读）：
 * - 401/403 → IllegalArgumentException("API Key 无效或无权限访问该服务")，换路径也一样，直接结束；
 * - 404/405 → 记下"不支持"，继续试下一个候选；全部候选都不支持 → IllegalArgumentException("该服务商不支持列出模型，请手动填写模型名")；
 * - 2xx 但解析不出模型名（网关 HTML、空对象等）→ 继续试下一个候选；全部如此 → IllegalArgumentException("无法解析该服务返回的模型列表，请手动填写模型名")；
 * - 其它状态码（400/429/5xx 等）→ IllegalStateException("列出模型失败（HTTP xxx）：<片段>")；
 * - 连不上/超时 → IllegalStateException("无法连接模型服务：<原因>")（同主机的其它路径不必再试）。
 * <p>
 * 安全：apiKey 只用于本次请求的鉴权头；不写日志、不进返回值；错误片段里的 Key 一律打码
 * （服务商 401 响应体常回显 Key 片段，如 OpenAI "Incorrect API key provided: sk-xxx"）。
 * <p>
 * apiKey 可为空：本机/局域网服务（Ollama 等）不校验 Key，此时请求不带任何鉴权头
 * （判空即"不带鉴权头"，判定口径见 {@link NetAddress}；公网地址缺 Key 由 controller 先行拦下）。
 */
@Slf4j
@Service
public class AiModelCatalogService {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** 错误片段截断 200 字（前端会原样展示） */
    private static final int ERROR_SNIPPET_MAX = 200;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper objectMapper;

    public AiModelCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 列表结果。
     *
     * @param models          模型名（保持服务端顺序、已去重）
     * @param resolvedBaseUrl 发通的 baseUrl 形态（不含 /models），可直接存进 AI 配置
     */
    public record Result(List<String> models, String resolvedBaseUrl) {
    }

    /** 请求结果（状态码 + 响应体文本） */
    private record Fetch(int code, String body) {
    }

    /** 候选地址：baseUrl 用于回填设置，url 是真正请求的列表地址 */
    private record Candidate(String baseUrl, String url) {
    }

    /** 鉴权头（值含 Key，仅存在于内存与本次请求） */
    record AuthHeader(String name, String value) {
    }

    /**
     * 列出该端点可用模型。
     *
     * @param baseUrl 已规范化（去尾部 /）的端点地址，调用方负责协议校验
     * @param apiKey  可为 null/空：本机/局域网服务（Ollama 等）不需要 Key，此时不带鉴权头；
     *                调用方负责用本地保存的 Key 兜底，并对公网地址强制要求 Key
     */
    public Result list(String baseUrl, String apiKey) {
        List<Candidate> candidates = candidates(baseUrl);
        boolean unparseable = false;
        for (Candidate candidate : candidates) {
            log.debug("探测模型列表地址：{}", candidate.url());
            Fetch fetch = get(candidate.url(), apiKey);
            if (fetch.code() == 401 || fetch.code() == 403) {
                // 鉴权问题与路径无关：换候选也一样失败，直接给用户可读结论（不回显响应体，避免带出 Key）
                throw new IllegalArgumentException("API Key 无效或无权限访问该服务");
            }
            if (fetch.code() == 404 || fetch.code() == 405) {
                // 该路径没有模型列表接口：换下一个候选
                continue;
            }
            if (fetch.code() >= 400) {
                throw new IllegalStateException("列出模型失败（HTTP " + fetch.code() + "）："
                        + snippet(fetch.body(), apiKey));
            }
            List<String> models = parseModels(fetch.body());
            if (!models.isEmpty()) {
                return new Result(models, candidate.baseUrl());
            }
            // 2xx 但没有模型名可解析（HTML 网关页/空对象）→ 换下一个候选再试
            unparseable = true;
        }
        // 有候选明确返回了 2xx（路径存在，只是内容用不了）时优先报"解析不出"，
        // 比笼统的"不支持列出模型"更贴近事实、也更好排查
        if (unparseable) {
            throw new IllegalArgumentException("无法解析该服务返回的模型列表，请手动填写模型名");
        }
        throw new IllegalArgumentException("该服务商不支持列出模型，请手动填写模型名");
    }

    /**
     * 候选地址（按顺序尝试）：
     * 1. {base}/models —— OpenAI 兼容端点若已带 /v1，这一条就是对的；
     * 2. {base}/v1/models —— 用户填的是裸域名（https://api.deepseek.com）时走这条；
     *    base 已以 /v1 结尾则跳过（避免 /v1/v1/models 这种必然 404 的请求）；
     * 3. {base}/api/tags —— Ollama 原生接口，仅在本机/局域网地址（{@link NetAddress}：localhost、127.0.0.1、::1、
     *    10.x、172.16-31.x、192.168.x）上兜底：公网服务商没有该路径，白白多打一次跨境请求；
     *    而本地/局域网 Ollama 的 /v1/models 万一被版本差异挡住，原生接口还能救回来
     *    （Ollama 的 /v1/models 返回 OpenAI 风格 {data:[{id}]}，同样能解析）。
     */
    private static List<Candidate> candidates(String base) {
        List<Candidate> list = new ArrayList<>();
        list.add(new Candidate(base, base + "/models"));
        if (!base.endsWith("/v1")) {
            list.add(new Candidate(base + "/v1", base + "/v1/models"));
        }
        if (NetAddress.isLocalOrPrivate(base)) {
            list.add(new Candidate(base, base + "/api/tags"));
        }
        return list;
    }

    /** GET 请求（带鉴权头）；连接/读取异常统一转成可读的 IllegalStateException */
    private Fetch get(String url, String apiKey) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            for (AuthHeader header : authHeaders(url, apiKey)) {
                conn.setRequestProperty(header.name(), header.value());
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = stream == null ? "" : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            return new Fetch(code, body);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接模型服务：" + e.getMessage(), e);
        }
    }

    /**
     * 鉴权头：
     * - apiKey 为空 → <b>不带任何鉴权头</b>（本机/局域网服务如 Ollama 不校验 Key，多送一个头反而可能被网关拒绝）；
     * - host 含 anthropic.com → Anthropic 官方协议 {@code x-api-key} + {@code anthropic-version}；
     * - 其余（OpenAI 兼容）→ {@code Authorization: Bearer <key>}。
     * 包级可见：便于单测覆盖各形态，不必真的连公网。
     */
    static List<AuthHeader> authHeaders(String url, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        if (NetAddress.hostOf(url).contains("anthropic.com")) {
            return List.of(new AuthHeader("x-api-key", apiKey),
                    new AuthHeader("anthropic-version", ANTHROPIC_VERSION));
        }
        return List.of(new AuthHeader("Authorization", "Bearer " + apiKey));
    }

    /**
     * 解析响应，兼容三种形态（都只取字符串模型名，去重保序）：
     * - OpenAI 风格 {@code {"data":[{"id":"deepseek-chat"}]}}（元素也可能是纯字符串）；
     * - Ollama 风格 {@code {"models":[{"name":"llama3.2:latest"}]}}；
     * - 纯数组 {@code ["a","b"]}。
     * 不是 JSON（网关 HTML 等）→ 返回空列表，交由调用方换候选/报错。
     */
    private List<String> parseModels(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return List.of();
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        Set<String> models = new LinkedHashSet<>();
        if (root.isArray()) {
            collect(root, models, false);
        } else {
            collect(root.path("data"), models, false);
            collect(root.path("models"), models, true);
        }
        return new ArrayList<>(models);
    }

    /** 从数组节点提取模型名（非数组节点直接忽略） */
    private static void collect(JsonNode array, Set<String> out, boolean preferName) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String name = nameOf(item, preferName);
            if (name != null && !name.isBlank()) {
                out.add(name);
            }
        }
    }

    /**
     * 单个元素的模型名：字符串直接用；对象按字段优先级取
     * （OpenAI 用 id；Ollama 的 /api/tags 用 name，故 models 数组优先 name）。
     */
    private static String nameOf(JsonNode item, boolean preferName) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isTextual()) {
            return item.asText().trim();
        }
        String[] fields = preferName ? new String[]{"name", "id", "model"} : new String[]{"id", "name", "model"};
        for (String field : fields) {
            JsonNode value = item.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    /** 错误片段：压平空白、截断 200 字，并把可能回显的 Key 打码 */
    private static String snippet(String body, String apiKey) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        if (text.length() > ERROR_SNIPPET_MAX) {
            text = text.substring(0, ERROR_SNIPPET_MAX) + "…";
        }
        return scrub(text, apiKey);
    }

    /** 错误消息里的 Key 一律替换为 ***（服务商错误体可能回显 Key 片段） */
    private static String scrub(String text, String apiKey) {
        String out = apiKey != null && !apiKey.isBlank() ? text.replace(apiKey, "***") : text;
        return out.replaceAll("sk-[A-Za-z0-9_-]{4,}", "sk-***");
    }
}
