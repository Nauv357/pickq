package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tiku.dto.AiModelsRequest;
import com.tiku.dto.AiModelsResponse;
import com.tiku.dto.ApiResponse;
import com.tiku.service.AiConfigService;
import com.tiku.service.AiModelCatalogService;
import com.tiku.service.AiPresetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 预设/模型目录两条接口的 HTTP 契约（standalone MockMvc，只装 web 层）+
 * 用本机假远端（HttpServer）验证转发细节：候选地址顺序、鉴权头、缓存命中次数、错误文案。
 * <p>
 * 全程不连公网；AI 配置写进 @TempDir，绝不碰用户真实 ~/.tiku。
 */
class AiConfigControllerHttpTest {

    /** 假远端的一条路由：状态码 + 响应体 */
    private record Route(int code, String body) {
    }

    private ObjectMapper objectMapper;
    private AiConfigService aiConfigService;
    private AiConfigController controller;
    private MockMvc mockMvc;
    private Path dataDir;
    private HttpServer server;
    private String base;

    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());
    private final List<String> apiKeyHeaders = Collections.synchronizedList(new ArrayList<>());
    private final List<String> anthropicVersionHeaders = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        dataDir = tempDir;
        objectMapper = new ObjectMapper();
        aiConfigService = new AiConfigService(dataDir.toString(), objectMapper);
        controller = new AiConfigController(new AiPresetService(), new AiModelCatalogService(objectMapper),
                aiConfigService);
        mockMvc = mockMvc(controller);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            // 原样记录（缺头即 null）：用于断言"本地地址无 Key 时不发 Authorization"
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKeyHeaders.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            anthropicVersionHeaders.add(exchange.getRequestHeaders().getFirst("anthropic-version"));
            Route route = routes.get(exchange.getRequestURI().getPath());
            byte[] body = (route == null ? "{\"error\":\"not found\"}" : route.body())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(route == null ? 404 : route.code(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static MockMvc mockMvc(AiConfigController controller) {
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

    /** 写本地 AI 配置（等价于设置页保存过一次） */
    private void saveLocalConfig(String apiKey) throws Exception {
        Files.writeString(dataDir.resolve("ai-config.json"),
                "{\"baseUrl\":\"" + base + "/v1\",\"apiKey\":\"" + apiKey + "\",\"model\":\"m\"}");
    }

    private String modelsBody(String baseUrl, String apiKey) {
        return "{\"baseUrl\":\"" + baseUrl + "\"" + (apiKey == null ? "" : ",\"apiKey\":\"" + apiKey + "\"") + "}";
    }

    // ==================== GET /api/ai/presets ====================

    @Test
    void presetsArePassedThroughVerbatimAndCachedForOneHour() throws Exception {
        String json = "{\"version\":1,\"groups\":[{\"name\":\"DeepSeek\","
                + "\"models\":[{\"id\":\"deepseek-chat\"}],\"unknownFutureField\":true}]}";
        routes.put("/config/ai-presets.json", new Route(200, json));

        mockMvc.perform(get("/api/ai/presets").param("center", base))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 原样返回：未知字段也要原封不动（不做结构解析/改写）
                .andExpect(content().json(json, JsonCompareMode.STRICT));

        // 未到期：第二次请求命中内存缓存，远端只被请求过一次
        mockMvc.perform(get("/api/ai/presets").param("center", base))
                .andExpect(status().isOk())
                .andExpect(content().json(json, JsonCompareMode.STRICT));
        assertEquals(1, hits.get(), "1 小时内第二次请求必须命中缓存");
        assertEquals(List.of("/config/ai-presets.json"), paths);

        // refresh=true 强制刷新（远端换内容后应立刻拿到新内容）
        routes.put("/config/ai-presets.json", new Route(200, "{\"version\":2}"));
        mockMvc.perform(get("/api/ai/presets").param("center", base).param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"version\":2}", JsonCompareMode.STRICT));
        assertEquals(2, hits.get(), "refresh=true 必须绕过缓存");
    }

    @Test
    void presetsCacheIsKeyedByCenter() throws Exception {
        routes.put("/config/ai-presets.json", new Route(200, "{\"site\":\"a\"}"));

        HttpServer otherSite = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        otherSite.createContext("/", exchange -> {
            byte[] body = "{\"site\":\"b\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        otherSite.start();
        try {
            String otherBase = "http://127.0.0.1:" + otherSite.getAddress().getPort();
            assertEquals("{\"site\":\"b\"}", controller.getPresets(otherBase, false).getBody());
            assertEquals("{\"site\":\"a\"}", controller.getPresets(base, false).getBody(),
                    "换 center 后不得复用另一个站点的缓存");
        } finally {
            otherSite.stop(0);
        }
    }

    @Test
    void presetsRemoteErrorIsReadableAndBecomesHttp500() throws Exception {
        // 未注册路由 → 假远端 404
        mockMvc.perform(get("/api/ai/presets").param("center", base))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message", startsWith("无法获取模型预设：远端返回错误（HTTP 404）")));
    }

    @Test
    void presetsNonJsonBodyIsRejectedInsteadOfCached() {
        routes.put("/config/ai-presets.json", new Route(200, "<html>请先登录</html>"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> controller.getPresets(base, false));
        assertEquals("无法获取模型预设：远端返回的不是 JSON（可能被网关或登录页拦截）", e.getMessage());
    }

    @Test
    void presetsValidateCenterLikeCenterProxy() {
        assertEquals("广场地址需为 http(s) 链接",
                assertThrows(IllegalArgumentException.class, () -> controller.getPresets("pickq.cn", false)).getMessage());
        assertEquals("广场地址不合法",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.getPresets("https://user:pw@pickq.cn", false)).getMessage());
        assertEquals(0, hits.get(), "地址不合法时不得发出任何远程请求");
    }

    @Test
    void presetsWorkWithoutLoginAndWithoutAiKey() throws Exception {
        // 本测试全程没有写 ai-config.json（桌面未配置 AI Key），也没有任何登录态/Token 注入点
        routes.put("/config/ai-presets.json", new Route(200, "{\"version\":3}"));
        mockMvc.perform(get("/api/ai/presets").param("center", base))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"version\":3}", JsonCompareMode.STRICT));
        assertEquals(1, hits.get());
    }

    // ==================== POST /api/ai/models ====================

    @Test
    void modelsFromOpenAiStyleDataAtBaseModels() throws Exception {
        routes.put("/models", new Route(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-chat\"},{\"id\":\"deepseek-reasoner\"},"
                        + "{\"id\":\"deepseek-chat\"},{\"id\":\"\"}]}"));

        String body = mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base, "sk-live-abcdef12345")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 保持服务端顺序 + 去重 + 丢掉空 id
                .andExpect(jsonPath("$.data.models[0]").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.models[1]").value("deepseek-reasoner"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.resolvedBaseUrl").value(base))
                .andReturn().getResponse().getContentAsString();

        assertEquals(List.of("/models"), paths);
        assertEquals(List.of("Bearer sk-live-abcdef12345"), authHeaders);
        assertFalse(body.contains("sk-live-abcdef12345"), "响应绝不能回显 API Key：" + body);
        assertFalse(body.contains("Authorization"), "响应不含鉴权信息");
    }

    @Test
    void modelsFallsBackToV1PathAndReportsResolvedBase() throws Exception {
        // /models 未注册（404）→ 应改试 /v1/models，并把 /v1 形态回报给前端
        routes.put("/v1/models", new Route(200, "{\"data\":[{\"id\":\"m-1\"}]}"));

        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base, "k-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models[0]").value("m-1"))
                .andExpect(jsonPath("$.data.resolvedBaseUrl").value(base + "/v1"));

        assertEquals(List.of("/models", "/v1/models"), paths, "必须按 {base}/models → {base}/v1/models 顺序尝试");
    }

    @Test
    void modelsSkipV1DuplicateWhenBaseAlreadyEndsWithV1() throws Exception {
        routes.put("/v1/models", new Route(200, "{\"data\":[{\"id\":\"m-1\"}]}"));

        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base + "/v1/", "k-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolvedBaseUrl").value(base + "/v1"));

        assertEquals(List.of("/v1/models"), paths, "base 已带 /v1 时不再试 /v1/v1/models");
    }

    @Test
    void modelsUnderstandOllamaTagsShape() throws Exception {
        // 本地地址才会兜底 /api/tags（公网服务商没有该路径）
        routes.put("/api/tags", new Route(200,
                "{\"models\":[{\"name\":\"llama3.2:latest\",\"model\":\"llama3.2:latest\"},{\"name\":\"qwen2.5:7b\"}]}"));

        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base, "ollama")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models[0]").value("llama3.2:latest"))
                .andExpect(jsonPath("$.data.models[1]").value("qwen2.5:7b"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.resolvedBaseUrl").value(base));

        assertEquals(List.of("/models", "/v1/models", "/api/tags"), paths);
    }

    @Test
    void modelsUnderstandPlainStringArray() throws Exception {
        // base 已带 /v1 → 只请求 {base}/v1/models；纯数组形态 ["a","b"] 也要能解析
        routes.put("/v1/models", new Route(200, "[\"a\",\"b\",\"a\"]"));
        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base + "/v1", "k-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models[0]").value("a"))
                .andExpect(jsonPath("$.data.models[1]").value("b"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.resolvedBaseUrl").value(base + "/v1"));
    }

    @Test
    void modelsUseSavedKeyWhenRequestOmitsIt() throws Exception {
        saveLocalConfig("saved-key-9");
        routes.put("/models", new Route(200, "{\"data\":[{\"id\":\"m\"}]}"));

        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models[0]").value("m"));
        assertEquals(List.of("Bearer saved-key-9"), authHeaders, "缺省 apiKey 应用本地保存的 Key");

        // 空白字符串同样视为"缺省"
        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base, "   ")))
                .andExpect(status().isOk());
        assertEquals(List.of("Bearer saved-key-9", "Bearer saved-key-9"), authHeaders);
    }

    @Test
    void modelsRequestKeyWinsOverSavedKey() {
        routes.put("/models", new Route(200, "{\"data\":[{\"id\":\"m\"}]}"));
        controller.listModels(new AiModelsRequest(base, "  req-key-1  "));
        assertEquals(List.of("Bearer req-key-1"), authHeaders, "显式传入的 Key 优先，且去掉首尾空白");
    }

    /**
     * 本机/局域网地址无 Key 也能列出模型（本地 Ollama 真实场景）：不再报"请先填写 API Key"，
     * 且请求不带任何鉴权头（Ollama 不校验 Key，多送 Bearer 反而可能被网关拒绝）。
     */
    @Test
    void modelsWorkWithoutKeyOnLocalAddress() {
        routes.put("/models", new Route(200, "{\"data\":[{\"id\":\"llama3.2:latest\"},{\"id\":\"qwen2.5:7b\"}]}"));

        ApiResponse<AiModelsResponse> resp = controller.listModels(new AiModelsRequest(base, null));

        assertEquals(200, resp.code());
        assertEquals(List.of("llama3.2:latest", "qwen2.5:7b"), resp.data().models());
        assertEquals(2, resp.data().count());
        assertEquals(base, resp.data().resolvedBaseUrl());
        assertEquals(List.of("/models"), paths);
        assertEquals(1, authHeaders.size());
        assertNull(authHeaders.get(0), "本地地址且无 Key 时不得发送 Authorization 头");
        assertNull(apiKeyHeaders.get(0), "本地地址且无 Key 时不得发送 x-api-key 头");
    }

    /** 空白字符串的 apiKey 同样按"未填写"处理（本机地址照常成功） */
    @Test
    void modelsTreatBlankKeyAsMissingOnLocalAddress() throws Exception {
        routes.put("/models", new Route(200, "{\"data\":[{\"id\":\"m\"}]}"));
        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody(base, "   ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models[0]").value("m"));
    }

    /** 公网地址仍必须填 Key：缺 Key 直接 400，且一个请求都不发（连接 8s 超时也不该发生） */
    @Test
    void modelsStillRequireKeyForPublicAddress() throws Exception {
        mockMvc.perform(post("/api/ai/models").contentType(MediaType.APPLICATION_JSON)
                        .content(modelsBody("https://api.deepseek.com", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请先填写 API Key"));
        assertEquals(0, hits.get(), "公网缺 Key 不得发出任何远程请求");
    }

    @Test
    void modelsValidateBaseUrlBeforeAnyRequest() {
        assertEquals("请填写服务地址（baseUrl）",
                assertThrows(IllegalArgumentException.class, () -> controller.listModels(null)).getMessage());
        assertEquals("请填写服务地址（baseUrl）",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.listModels(new AiModelsRequest("   ", "k"))).getMessage());
        // 与保存配置同一套地址规则：https 一律允许；http 仅本机/局域网（公网 http 仍拒绝）
        assertEquals("http 仅支持本机或局域网地址（如 192.168.x.x），公网请使用 https",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.listModels(new AiModelsRequest("http://example.com", "k"))).getMessage());
        assertEquals("http 仅支持本机或局域网地址（如 192.168.x.x），公网请使用 https",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.listModels(new AiModelsRequest("http://8.8.8.8:8080", "k"))).getMessage());
        assertEquals("baseUrl 必须使用 http(s) 链接",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.listModels(new AiModelsRequest("ftp://192.168.1.50", "k"))).getMessage());
        assertEquals(0, hits.get());
    }

    @Test
    void modelsAuthErrorIsReadableAndStopsProbing() {
        routes.put("/models", new Route(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-live-abcdef12345\"}}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.listModels(new AiModelsRequest(base, "sk-live-abcdef12345")));
        assertEquals("API Key 无效或无权限访问该服务", e.getMessage());
        assertEquals(List.of("/models"), paths, "鉴权类错误不再试其它候选地址");

        routes.put("/models", new Route(403, "{\"error\":{\"message\":\"forbidden\"}}"));
        assertEquals("API Key 无效或无权限访问该服务", assertThrows(IllegalArgumentException.class,
                () -> controller.listModels(new AiModelsRequest(base, "sk-live-abcdef12345"))).getMessage());
    }

    @Test
    void modelsNotFoundEverywhereSaysUnsupported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.listModels(new AiModelsRequest(base, "k-1")));
        assertEquals("该服务商不支持列出模型，请手动填写模型名", e.getMessage());
        assertEquals(List.of("/models", "/v1/models", "/api/tags"), paths);
    }

    @Test
    void modelsSuccessButUnparseableBodyIsReadable() {
        routes.put("/models", new Route(200, "<html>gateway</html>"));
        routes.put("/v1/models", new Route(200, "{\"data\":[]}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.listModels(new AiModelsRequest(base, "k-1")));
        assertEquals("无法解析该服务返回的模型列表，请手动填写模型名", e.getMessage());
    }

    @Test
    void modelsOtherStatusCarriesCodeAndScrubbedSnippet() {
        routes.put("/models", new Route(500,
                "{\"error\":{\"message\":\"upstream boom sk-live-abcdef12345 " + "x".repeat(400) + "\"}}"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> controller.listModels(new AiModelsRequest(base, "sk-live-abcdef12345")));

        assertTrue(e.getMessage().startsWith("列出模型失败（HTTP 500）："), e.getMessage());
        assertFalse(e.getMessage().contains("sk-live-abcdef12345"), "错误片段里的 Key 必须打码：" + e.getMessage());
        assertTrue(e.getMessage().contains("***"), "错误片段里的 Key 应打码：" + e.getMessage());
        assertTrue(e.getMessage().length() <= 200 + 40, "错误片段应截断（200 字以内）：" + e.getMessage().length());
    }

    @Test
    void modelsConnectionFailureIsReadable() {
        // 指向一个没有服务在听的端口 → 连接失败，不应变成 500"内部服务器错误"
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> controller.listModels(new AiModelsRequest("http://127.0.0.1:1", "k-1")));
        assertTrue(e.getMessage().startsWith("无法连接模型服务："), e.getMessage());
    }
}
