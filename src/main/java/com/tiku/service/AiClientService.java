package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tiku.config.AiSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容大模型客户端（DeepSeek / 通义 / Kimi / OpenAI / 本地 Ollama）。
 * - 文本对话与多模态（图片 base64 data URL）
 * - response_format json_object（模型不支持时降级为 prompt 约束）
 * - Key 仅放 Authorization 头（Key 为空则完全不发该头，供本机/局域网 Ollama 无 Key 使用），日志不打印
 * - thinking=false（默认）时请求带 {"thinking":{"type":"disabled"}} 关闭推理思考（速度约快一倍；
 *   实测 deepseek-v4-flash-vision-exp 默认思考会占用大量输出 token 与耗时）；端点不支持时自动降级重试
 */
@Slf4j
@Service
public class AiClientService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiClientService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 文本对话：返回模型输出的文本（JSON 模式时通常是 JSON 字符串）
     */
    public String chat(AiSettings settings, String systemPrompt, String userContent, boolean jsonMode) {
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        return call(settings, systemPrompt, userMsg, jsonMode);
    }

    /**
     * 多模态对话：文本 + 图片列表（图片以 base64 data URL 发送）
     */
    public String chatWithImages(AiSettings settings, String systemPrompt, String text,
                                 List<ImageData> images, boolean jsonMode) {
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        for (ImageData image : images) {
            String dataUrl = "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.data());
            content.addObject()
                    .put("type", "image_url")
                    .putObject("image_url").put("url", dataUrl);
        }
        return call(settings, systemPrompt, userMsg, jsonMode);
    }

    /**
     * 测试连接：发一条最小请求，返回模型名与耗时
     */
    public TestResult test(AiSettings settings) {
        long start = System.currentTimeMillis();
        String reply = chat(settings, "你是连通性测试助手", "请只回复：ok", false);
        long latencyMs = System.currentTimeMillis() - start;
        return new TestResult(true, "连接成功，模型响应：" + reply.trim().substring(0, Math.min(30, reply.trim().length())), latencyMs);
    }

    /**
     * 流式文本对话（阶段 1 的提示楼梯 / 追问 / 复盘用它做"边生成边显示"）。
     *
     * 实现要点：
     * - 请求体加 {@code stream: true}，用 {@code BodyHandlers.ofLines()} 按行读 SSE，逐块回调；
     * - 只认 {@code data:} 行；{@code [DONE]} 结束；解析失败的行直接跳过（各端点偶发心跳/空行）；
     * - 端点不支持 stream（400/404）时**自动降级为一次性调用**：这样即便模型网关不支持流式，
     *   功能仍然可用，只是没有打字效果（用返回的整段文本回调一次）；
     * - 空回复同样是"失败"（见 call 的空白重试策略），由调用方决定要不要重试。
     *
     * @param onDelta 每收到一段增量文本回调一次（在**当前线程**同步回调，调用方负责推给前端）
     * @return 完整文本
     */
    public String chatStream(AiSettings settings, String systemPrompt, List<ChatTurn> turns, Consumer<String> onDelta) {
        boolean thinkingEnabled = Boolean.TRUE.equals(settings.getThinking());
        ObjectNode body = buildBody(settings, systemPrompt, turns, false, thinkingEnabled);
        body.put("stream", true);
        String full = tryStream(settings, body, onDelta);
        if (full != null) {
            return full;
        }
        // 降级：不带 stream 再要一次（各端点对 stream 的支持不一致，不能因为流式失败就让功能不可用）
        log.warn("模型端点不支持流式输出，已自动降级为一次性返回（内容不受影响）");
        String text = call(settings, systemPrompt, turns);
        if (text != null && !text.isBlank()) {
            onDelta.accept(text);
        }
        return text;
    }

    /** 返回 null 表示"这个端点不支持流式"，交给调用方降级；抛异常表示真的调用失败 */
    private String tryStream(AiSettings settings, ObjectNode body, Consumer<String> onDelta) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(settings.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream");
            String apiKey = settings.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() == 400 || response.statusCode() == 404 || response.statusCode() == 415) {
                response.body().close();
                return null; // 端点不认 stream → 降级
            }
            if (response.statusCode() != 200) {
                String err = response.body().limit(20).reduce("", (a, b) -> a + b);
                throw new IllegalStateException("模型调用失败 HTTP " + response.statusCode() + "："
                        + sanitize(extractError(err), settings.getApiKey()));
            }
            // 有些网关忽略 stream 参数、照样返回整段 JSON（200 + application/json）：
            // 这时候按普通响应解析即可，不必再发一次请求
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("application/json")) {
                String raw = response.body().reduce("", (a, b) -> a + b);
                String text = objectMapper.readTree(raw).path("choices").path(0).path("message").path("content").asText("");
                if (!text.isBlank()) {
                    onDelta.accept(text);
                }
                return text;
            }
            StringBuilder full = new StringBuilder();
            try (Stream<String> lines = response.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line == null || line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    String delta = deltaOf(payload);
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        onDelta.accept(delta);
                    }
                }
            }
            // 既没有 Content-Type 也没有任何 delta：当作"这个端点不会流式"，交给调用方降级
            return full.length() == 0 ? null : full.toString();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败：" + sanitize(String.valueOf(e), settings.getApiKey()), e);
        }
    }

    /** 从一条 SSE data 里取增量文本（兼容 chat/completions 的 delta 与少数端点的 message.content） */
    private String deltaOf(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode choice = node.path("choices").path(0);
            JsonNode delta = choice.path("delta").path("content");
            if (!delta.isMissingNode() && !delta.isNull()) {
                return delta.asText("");
            }
            JsonNode message = choice.path("message").path("content");
            return message.isMissingNode() || message.isNull() ? null : message.asText("");
        } catch (Exception e) {
            return null; // 心跳/非 JSON 行，跳过
        }
    }

    /**
     * 多轮对话（一条 system + 若干轮 user/assistant）：追问会话需要把历史带上。
     */
    public String call(AiSettings settings, String systemPrompt, List<ChatTurn> turns) {
        ObjectNode last = objectMapper.createObjectNode();
        last.put("role", "user");
        last.put("content", turns.isEmpty() ? "" : turns.get(turns.size() - 1).content());
        ObjectNode body = buildBody(settings, systemPrompt, last, false, Boolean.TRUE.equals(settings.getThinking()));
        // buildBody 只放了 system + 最后一条；这里把完整历史重建（顺序：system → 历史 → 最后一条 user）
        ArrayNode messages = objectMapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", systemPrompt == null ? "" : systemPrompt);
        for (ChatTurn turn : turns) {
            messages.addObject().put("role", turn.role()).put("content", turn.content());
        }
        body.set("messages", messages);
        try {
            HttpResponse<String> response = send(settings, body);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("模型调用失败 HTTP " + response.statusCode() + "："
                        + sanitize(extractError(response.body()), settings.getApiKey()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败：" + sanitize(String.valueOf(e), settings.getApiKey()), e);
        }
    }

    /** 多轮对话请求体（system + 历史），用于 chatStream */
    private ObjectNode buildBody(AiSettings settings, String systemPrompt, List<ChatTurn> turns,
                                 boolean jsonMode, Boolean thinking) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.getModel());
        body.put("temperature", 0.2);
        if (thinking != null) {
            body.putObject("thinking").put("type", thinking ? "enabled" : "disabled");
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt == null ? "" : systemPrompt);
        for (ChatTurn turn : turns) {
            messages.addObject().put("role", turn.role()).put("content", turn.content());
        }
        return body;
    }

    /** 一轮对话（role = user / assistant） */
    public record ChatTurn(String role, String content) {
        public static ChatTurn user(String content) {
            return new ChatTurn("user", content);
        }

        public static ChatTurn assistant(String content) {
            return new ChatTurn("assistant", content);
        }
    }

    private String call(AiSettings settings, String systemPrompt, JsonNode userMessage, boolean jsonMode) {
        //thinking=true → 显式开启；false/null → 显式关闭（快速模式，不依赖端点默认行为）
        boolean thinkingEnabled = Boolean.TRUE.equals(settings.getThinking());
        try {
            ObjectNode body = buildBody(settings, systemPrompt, userMessage, jsonMode, thinkingEnabled);
            HttpResponse<String> response = send(settings, body);
            if (response.statusCode() != 200) {
                if (response.statusCode() == 400) {
                    //部分 OpenAI 兼容端点不支持 thinking 参数（400）→ 自动降级重试（不带该参数）
                    log.warn("模型端点拒绝了 thinking 参数（HTTP 400），已自动降级重试（不影响结果）");
                    body = buildBody(settings, systemPrompt, userMessage, jsonMode, null);
                    response = send(settings, body);
                }
                if (response.statusCode() != 200) {
                    //错误 message 可能回显部分 Key（如 OpenAI 401 "Incorrect API key provided: sk-xxx"），必须脱敏
                    throw new IllegalStateException("模型调用失败 HTTP " + response.statusCode() + "：" + sanitize(extractError(response.body()), settings.getApiKey()));
                }
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            //端点偶发返回空白（DeepSeek 高峰期阵发性劣化）→ 内部最多再试 2 次（间隔 3s）
            if (content == null || content.isBlank()) {
                log.warn("模型调用返回空白：HTTP {} 模型 {} thinking {} 响应原文（前 400 字符）：{}",
                        response.statusCode(), settings.getModel(), settings.getThinking(),
                        response.body().length() > 400 ? response.body().substring(0, 400) : response.body());
            }
            for (int attempt = 0; attempt < 2 && (content == null || content.isBlank()); attempt++) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                HttpResponse<String> retryResp = send(settings, body);
                if (retryResp.statusCode() != 200) {
                    continue;
                }
                JsonNode retryRoot = objectMapper.readTree(retryResp.body());
                content = retryRoot.path("choices").path(0).path("message").path("content").asText("");
            }
            if (content == null || content.isBlank()) {
                //仍空白 → 视为失败，由调用方重试（思考/无思考/纯文本交替）
                throw new IllegalStateException("模型返回空白内容（端点瞬时劣化），请由调用方重试");
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败：" + sanitize(String.valueOf(e), settings.getApiKey()), e);
        }
    }

    /**
     * 构建请求体：thinking 参数三态（true→enabled，false→disabled，null→不发送，降级重试用）。
     * 显式发送 enabled/disabled，不依赖端点默认行为（实测 deepseek 系列默认思考开启，不传参数会静默走思考）。
     */
    private ObjectNode buildBody(AiSettings settings, String systemPrompt, JsonNode userMessage, boolean jsonMode, Boolean thinking) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.getModel());
        body.put("temperature", 0.2);
        if (thinking != null) {
            body.putObject("thinking").put("type", thinking ? "enabled" : "disabled");
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.add(userMessage);
        //json_object 模式要求系统提示中出现 "json" 字样（OpenAI 规范），且模型会据此强制输出合法 JSON：
        //Markdown 模板路径一旦发送 json_object → 模型把 Markdown 包成 {"output": "..."} 或返回纯空白
        //（实测 deepseek-v4-flash 系列）。门控用正向短语 "JSON 对象"（旧版 JSON 模板含），
        //避免 "不要输出 JSON" 之类的否定句误触发。
        if (jsonMode && systemPrompt != null
                && (systemPrompt.contains("JSON 对象") || systemPrompt.toLowerCase().contains("json object"))) {
            body.putObject("response_format").put("type", "json_object");
        }
        return body;
    }

    /**
     * 发送对话请求。
     * 鉴权头按需附加：apiKey 非空 → {@code Authorization: Bearer <key>}（行为不变）；
     * apiKey 为空 → <b>不带鉴权头</b>（本机/局域网 Ollama 等不校验 Key，无需任何占位值；
     * 过去会发出 {@code Bearer null} / {@code Bearer } 这类空头，部分网关会直接拒绝）。
     * 注：Anthropic 的 x-api-key 协议只用于"列出模型"（{@link AiModelCatalogService}），
     * 本类的对话请求走 OpenAI 兼容协议，不涉及该分支（Key 为空时同样不带任何鉴权头）。
     */
    private HttpResponse<String> send(AiSettings settings, ObjectNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(settings.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json");
        String apiKey = settings.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 从错误响应体提取 message */
    private String extractError(String body) {
        try {
            return objectMapper.readTree(body).path("error").path("message").asText("未知错误");
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    /** 错误消息中的 Key 一律替换为 ***（服务商错误回显可能包含 Key 片段） */
    private String sanitize(String message, String apiKey) {
        if (message == null || apiKey == null || apiKey.isBlank()) {
            return message;
        }
        return message.replace(apiKey, "***").replaceAll("sk-[A-Za-z0-9]{4,}", "sk-***");
    }

    public record ImageData(String mimeType, byte[] data) {
    }

    public record TestResult(boolean ok, String message, long latencyMs) {
    }
}
