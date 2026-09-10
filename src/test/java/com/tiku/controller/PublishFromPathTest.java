package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tiku.dto.PublishFromPathRequest;
import com.tiku.service.CenterAuthStore;
import com.tiku.service.ExportRecordService;
import com.tiku.util.PackageContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 从本地路径直接发布（POST /api/center/publish-from-path）的验证：
 * 文件不再经前端中转、体检与 multipart 发布同一套口径、成功后就地标记导出记录、
 * 校验不通过时一个字节都不发往公网、用户的磁盘文件绝不被删。
 * 用本机假中心服务器收包（不触网），数据目录用 @TempDir（不碰用户真实 ~/.tiku）。
 */
class PublishFromPathTest {

    private HttpServer server;
    private String center;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile byte[] receivedBody;
    private volatile String receivedContentType;
    private CenterAuthStore authStore;
    private ExportRecordService exportRecordService;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        dir = tempDir;
        authStore = new CenterAuthStore(tempDir.toString(), new ObjectMapper());
        authStore.save("tok-9", "tester");
        exportRecordService = mock(ExportRecordService.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                in.transferTo(bos);
            }
            receivedBody = bos.toByteArray();
            receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] response = "{\"code\":200,\"data\":{\"packageKey\":\"k\"},\"message\":\"发布成功\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        center = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private CenterPublishController controller() {
        return new CenterPublishController(authStore, exportRecordService);
    }

    /** 未登录的控制器（独立数据目录，避免读到本用例已登录的 center-auth.json） */
    private CenterPublishController loggedOutController() {
        CenterAuthStore loggedOut = new CenterAuthStore(dir.resolve("logged-out").toString(), new ObjectMapper());
        return new CenterPublishController(loggedOut, exportRecordService);
    }

    private PublishFromPathRequest request(String filePath, String storageKind, String downloadUrl, Long recordId) {
        return new PublishFromPathRequest(filePath, storageKind, downloadUrl, "标题A", "描述B", "来源C", recordId);
    }

    /** 造一个合法 .tiku（v2 容器：package.json + media/） */
    private Path writePackage(String fileName, String version) throws Exception {
        byte[] container = PackageContainer.pack(
                ("{\"schemaVersion\":2,\"packageKey\":\"mykey\",\"version\":\"" + version
                        + "\",\"title\":\"t\",\"questions\":[{}]}").getBytes(StandardCharsets.UTF_8),
                Map.of("260829/a.png", new byte[]{1, 2, 3}));
        return Files.write(dir.resolve(fileName), container);
    }

    private static byte[] partBytes(byte[] body, String boundary, String name) {
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        int marker = raw.indexOf("name=\"" + name + "\"");
        assertTrue(marker > 0, "缺少字段 " + name);
        int start = raw.indexOf("\r\n\r\n", marker) + 4;
        int end = raw.indexOf("\r\n--" + boundary, start);
        assertTrue(end > start, "字段 " + name + " 未闭合");
        return Arrays.copyOfRange(body, start, end);
    }

    private static String partText(byte[] body, String boundary, String name) {
        return new String(partBytes(body, boundary, name), StandardCharsets.UTF_8);
    }

    private String boundaryOf() {
        int i = receivedContentType.indexOf("boundary=");
        return receivedContentType.substring(i + "boundary=".length()).trim();
    }

    @Test
    void publishFromPathUploadsFileAndMarksExportRecord() throws Exception {
        Path file = writePackage("bank-1.0.1.tiku", "1.0.1");
        byte[] expected = Files.readAllBytes(file);

        ResponseEntity<String> response = controller()
                .publishFromPath(center, request(file.toString(), null, null, 5L));

        assertEquals(1, hits.get());
        assertTrue(response.getBody().contains("发布成功"), "响应体按官网原文透传");
        String boundary = boundaryOf();
        assertEquals("HOSTED", partText(receivedBody, boundary, "storageKind"), "缺省托管方式为 HOSTED");
        assertEquals("标题A", partText(receivedBody, boundary, "title"));
        assertEquals("描述B", partText(receivedBody, boundary, "description"));
        assertEquals("来源C", partText(receivedBody, boundary, "source"));
        assertArrayEquals(expected, partBytes(receivedBody, boundary, "file"), "转发内容 = 磁盘文件原字节");
        assertTrue(new String(receivedBody, StandardCharsets.ISO_8859_1).contains("filename=\"bank-1.0.1.tiku\""),
                "文件名按 ASCII 安全名送出");
        // 成功后标记导出记录，版本取包内 version
        verify(exportRecordService).markPublished(5L, "1.0.1");
        // 发布不会删用户的文件（Staged 不持有该文件的所有权）
        assertTrue(Files.exists(file), "从本地路径发布不得删除用户的 .tiku");
    }

    @Test
    void publishFromPathAcceptsPlainJsonPackage() throws Exception {
        Path file = Files.writeString(dir.resolve("bank.json"),
                "{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"2.0\",\"title\":\"t\",\"questions\":[{}]}");

        controller().publishFromPath(center,
                request(file.toString(), "EXTERNAL", "https://example.com/a.tiku", null));

        assertEquals(1, hits.get());
        String boundary = boundaryOf();
        assertEquals("EXTERNAL", partText(receivedBody, boundary, "storageKind"));
        assertEquals("https://example.com/a.tiku", partText(receivedBody, boundary, "downloadUrl"));
        assertArrayEquals(Files.readAllBytes(file), partBytes(receivedBody, boundary, "file"));
    }

    @Test
    void publishFromPathRejectsInvalidPackageWithoutRemoteRequest() throws Exception {
        Path file = Files.writeString(dir.resolve("broken.tiku"), "not a package");

        assertEquals("题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）",
                assertThrows(IllegalArgumentException.class,
                        () -> controller().publishFromPath(center, request(file.toString(), null, null, 8L)))
                        .getMessage());
        assertEquals(0, hits.get(), "体检不通过不得发出任何公网请求");
        assertTrue(Files.exists(file));
    }

    @Test
    void publishFromPathValidatesLoginPathAndFields() throws Exception {
        Path file = writePackage("ok.tiku", "1.0.0");

        // 未登录：本地直接拒绝（与 multipart 发布同口径），不发远程请求
        assertEquals("请先登录题库广场账号", assertThrows(IllegalStateException.class,
                () -> loggedOutController().publishFromPath(center, request(file.toString(), null, null, null)))
                .getMessage());

        CenterPublishController controller = controller();
        assertEquals("缺少请求体（需要 filePath）", assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, null)).getMessage());
        assertEquals("请提供要发布的内容包文件路径（filePath）", assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, request("  ", null, null, null))).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, request(dir.resolve("nope.tiku").toString(), null, null, null)))
                .getMessage().startsWith("内容包文件不存在："));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, request(dir.toString(), null, null, null)))
                .getMessage().startsWith("内容包文件路径是目录，不是文件："));
        Path empty = Files.createFile(dir.resolve("empty.tiku"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, request(empty.toString(), null, null, null)))
                .getMessage().startsWith("内容包文件为空："));
        assertEquals("外链方式需要提供内容包下载链接（http/https）", assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, request(file.toString(), "EXTERNAL", " ", null))).getMessage());
        assertEquals("下载链接需为 http(s) 链接", assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, request(file.toString(), "EXTERNAL", "ftp://x/y", null))).getMessage());
        assertEquals("托管方式只能是 HOSTED 或 EXTERNAL", assertThrows(IllegalArgumentException.class,
                () -> controller.publishFromPath(center, request(file.toString(), "MAGIC", null, null))).getMessage());
        assertEquals(0, hits.get(), "任何校验失败都不得发出公网请求");
    }

    /** HTTP 契约：JSON body 绑定（filePath/storageKind/exportRecordId）与错误响应结构 */
    @Test
    void publishFromPathHttpContract() throws Exception {
        Path file = writePackage("http.tiku", "3.0.0");
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filePath", file.toString());
        body.put("storageKind", "HOSTED");
        body.put("exportRecordId", 5);
        String json = new ObjectMapper().writeValueAsString(body);

        MockMvc loggedIn = MockMvcBuilders.standaloneSetup(controller())
                .setControllerAdvice(new GlobalExceptionHandler(errorView))
                .build();
        loggedIn.perform(post("/api/center/publish-from-path").param("center", center)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("发布成功"));
        assertEquals(1, hits.get());
        verify(exportRecordService).markPublished(5L, "3.0.0");

        // 未登录：500 + 可读文案（前端据此引导登录），且不发远程请求
        MockMvc loggedOut = MockMvcBuilders.standaloneSetup(loggedOutController())
                .setControllerAdvice(new GlobalExceptionHandler(errorView))
                .build();
        loggedOut.perform(post("/api/center/publish-from-path").param("center", center)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("请先登录题库广场账号"));
        assertEquals(1, hits.get());
    }
}
