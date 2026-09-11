package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CenterHttpClientTest {

    private HttpServer server;
    private CenterHttpClient client;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        CenterAuthStore store = new CenterAuthStore(tempDir.toString(), new ObjectMapper());
        store.save("test-token", "tester");
        client = new CenterHttpClient(store, new ObjectMapper());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void attachesLocalSessionTokenToRequests() throws IOException {
        server.createContext("/ok", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":true}");
        });

        assertEquals("{\"data\":true}", client.getText(url("/ok"), 1_000));
        assertEquals("Bearer test-token", authorization.get());
    }

    @Test
    void exposesStructuredRemoteErrorWithoutReturningRawPayload() throws IOException {
        server.createContext("/bad", exchange -> respond(exchange, 429,
                "{\"data\":{\"message\":\"请求过于频繁，请稍后重试\"}}"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.getText(url("/bad"), 1_000));
        assertEquals("请求过于频繁，请稍后重试", exception.getMessage());
    }

    @Test
    void rejectsDownloadAfterConfiguredLimit() throws IOException {
        server.createContext("/large", exchange -> respond(exchange, 200, "12345"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.getBytes(url("/large"), 1_000, 4));
        assertEquals("广场返回的内容包文件过大", exception.getMessage());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
