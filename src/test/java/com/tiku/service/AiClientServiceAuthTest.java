package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tiku.config.AiSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiClientService 的鉴权头行为：apiKey 非空 → {@code Authorization: Bearer <key>}（行为不变）；
 * apiKey 为空（本机/局域网 Ollama 等不校验 Key）→ <b>完全不发该头</b>，而不是发 {@code Bearer null}/{@code Bearer }。
 * <p>
 * 用本机假端点（HttpServer）收包断言，全程不连公网。
 */
class AiClientServiceAuthTest {

    private HttpServer server;
    private String base;
    private AiClientService client;

    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        client = new AiClientService(new ObjectMapper());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // 请求体读掉再回，避免连接复用异常
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private AiSettings settings(String apiKey) {
        AiSettings s = new AiSettings();
        s.setBaseUrl(base);
        s.setApiKey(apiKey);
        s.setModel("qwen2.5");
        return s;
    }

    @Test
    void bearerHeaderSentWhenKeyPresent() {
        String reply = client.chat(settings("sk-live-1"), "system", "hi", false);

        assertEquals("ok", reply);
        assertEquals(List.of("/v1/chat/completions"), paths);
        assertEquals(List.of("Bearer sk-live-1"), authHeaders);
    }

    /** 空 Key（本机/局域网 Ollama）：请求照常成功，且一个鉴权头都不发 */
    @Test
    void noAuthorizationHeaderWhenKeyIsBlank() {
        for (String apiKey : new String[]{null, "", "   "}) {
            authHeaders.clear();
            paths.clear();

            String reply = client.chat(settings(apiKey), "system", "hi", false);

            assertEquals("ok", reply, "apiKey=" + apiKey + " 时本地端点应正常返回");
            assertEquals(List.of("/v1/chat/completions"), paths);
            assertEquals(1, authHeaders.size());
            assertNull(authHeaders.get(0), "apiKey=" + apiKey + " 时不得发送 Authorization 头");
        }
    }

    /** 多模态路径同样走同一个 send：空 Key 不带鉴权头 */
    @Test
    void noAuthorizationHeaderForVisionCallWhenKeyIsBlank() {
        String reply = client.chatWithImages(settings(null), "system", "图题",
                List.of(new AiClientService.ImageData("image/png", new byte[]{1, 2, 3})), false);

        assertEquals("ok", reply);
        assertNull(authHeaders.get(0));
        assertTrue(paths.get(0).endsWith("/chat/completions"));
    }
}
