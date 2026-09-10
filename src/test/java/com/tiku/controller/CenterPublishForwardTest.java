package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tiku.service.CenterAuthStore;
import com.tiku.util.PackageContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 临时验证（不属于交付内容）：手写 multipart 转发的端到端校验，用本机假中心服务器收包。 */
class CenterPublishForwardTest {

    private record Captured(String method, String path, String contentType, String auth, long contentLength,
                            byte[] body) {
    }

    private HttpServer server;
    private String center;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Captured captured;
    private volatile int responseCode = 200;
    private volatile byte[] responseBody = "{\"code\":200,\"data\":{\"packageKey\":\"k\"},\"message\":\"发布成功\"}"
            .getBytes(StandardCharsets.UTF_8);

    private CenterAuthStore authStore;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        authStore = new CenterAuthStore(tempDir.toString(), new ObjectMapper());
        authStore.save("tok-123", "tester");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                in.transferTo(bos);
            }
            String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
            captured = new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    contentLength == null ? -1L : Long.parseLong(contentLength),
                    bos.toByteArray());
            exchange.sendResponseHeaders(responseCode, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        center = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String boundaryOf(String contentType) {
        int i = contentType.indexOf("boundary=");
        return contentType.substring(i + "boundary=".length()).trim();
    }

    /** 扫描用 ISO-8859-1 保证字节偏移 = 字符偏移 */
    private static int partStart(byte[] body, String name) {
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        int marker = raw.indexOf("name=\"" + name + "\"");
        assertTrue(marker > 0, "缺少字段 " + name);
        int headerEnd = raw.indexOf("\r\n\r\n", marker);
        return headerEnd + 4;
    }

    private static byte[] partBytes(byte[] body, String boundary, String name) {
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        int start = partStart(body, name);
        int end = raw.indexOf("\r\n--" + boundary, start);
        assertTrue(end > start, "字段 " + name + " 未闭合");
        return Arrays.copyOfRange(body, start, end);
    }

    private static String partText(byte[] body, String boundary, String name) {
        return new String(partBytes(body, boundary, name), StandardCharsets.UTF_8);
    }

    private static String partHeader(byte[] body, String name) {
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        int marker = raw.indexOf("name=\"" + name + "\"");
        int headerEnd = raw.indexOf("\r\n\r\n", marker);
        return raw.substring(raw.lastIndexOf("--", marker), headerEnd);
    }

    @Test
    void publishDefaultsToHostedAndKeepsFieldValues() throws Exception {
        byte[] pkg = "{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1.0\",\"title\":\"t\",\"questions\":[{}]}"
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "我的题库.tiku", "application/zip", pkg);

        ResponseEntity<String> resp = new CenterPublishController(authStore)
                .publish(center, file, null, null, "  标题A  ", "描述第一行\n描述第二行", "来源B");

        assertEquals(responseBody.length > 0, resp.getBody() != null && resp.getBody().contains("发布成功"));
        assertTrue(resp.getBody().contains("发布成功"));
        assertEquals(1, hits.get());
        assertEquals("POST", captured.method());
        assertEquals("/api/packs/upload", captured.path());
        assertEquals("Bearer tok-123", captured.auth());
        assertEquals(captured.body().length, captured.contentLength(), "Content-Length 必须与实际写出字节一致");
        String boundary = boundaryOf(captured.contentType());
        assertTrue(captured.contentType().startsWith("multipart/form-data; boundary=----TikuDesktopBoundary"));

        assertEquals("HOSTED", partText(captured.body(), boundary, "storageKind"), "缺省必须是 HOSTED");
        assertEquals("标题A", partText(captured.body(), boundary, "title"));
        assertEquals("描述第一行\n描述第二行", partText(captured.body(), boundary, "description"));
        assertEquals("来源B", partText(captured.body(), boundary, "source"));
        assertArrayEquals(pkg, partBytes(captured.body(), boundary, "file"));
        String fileHeader = partHeader(captured.body(), "file");
        assertTrue(fileHeader.contains("filename=\"package.tiku\""), "filename 应为 ASCII 安全名：" + fileHeader);
        assertTrue(fileHeader.contains("Content-Type: application/zip"), "应保留原始 Content-Type：" + fileHeader);
        assertTrue(new String(captured.body(), StandardCharsets.UTF_8).endsWith("--" + boundary + "--\r\n"));
    }

    @Test
    void publishLargeFileSpoolsToTempFileAndKeepsBytes() throws Exception {
        // 大文件必须是合法的 .tiku 容器：发布前会先做本地体检（元数据校验），随机字节会被本地拒绝
        byte[] blob = new byte[5 * 1024 * 1024 + 12345];
        new Random(7).nextBytes(blob);
        Map<String, byte[]> media = new LinkedHashMap<>();
        media.put("260829/big.bin", blob);
        byte[] big = PackageContainer.pack(
                "{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1.0\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8), media);
        MockMultipartFile file = new MockMultipartFile("file", "bank_v2.tiku", null, big);

        new CenterPublishController(authStore)
                .publish(center, file, "external", "https://example.com/a.tiku", null, null, null);

        String boundary = boundaryOf(captured.contentType());
        assertEquals("EXTERNAL", partText(captured.body(), boundary, "storageKind"));
        assertEquals("https://example.com/a.tiku", partText(captured.body(), boundary, "downloadUrl"));
        assertArrayEquals(big, partBytes(captured.body(), boundary, "file"));
        assertTrue(partHeader(captured.body(), "file").contains("filename=\"bank_v2.tiku\""));
        assertTrue(partHeader(captured.body(), "file").contains("Content-Type: application/octet-stream"));
    }

    @Test
    void publishRequiresLoginLocallyAndSendsNothing() {
        authStore.clear();
        MockMultipartFile file = new MockMultipartFile("file", "a.tiku", null, new byte[]{1, 2, 3});
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CenterPublishController(authStore).publish(center, file, null, null, null, null, null));
        assertEquals("请先登录题库广场账号", e.getMessage());
        assertEquals(0, hits.get(), "未登录不得发出远程请求");
    }

    @Test
    void publishValidatesExternalDownloadUrl() {
        MockMultipartFile file = new MockMultipartFile("file", "a.tiku", null, new byte[]{1, 2, 3});
        CenterPublishController controller = new CenterPublishController(authStore);
        assertEquals("外链方式需要提供内容包下载链接（http/https）", assertThrows(IllegalArgumentException.class,
                () -> controller.publish(center, file, "EXTERNAL", " ", null, null, null)).getMessage());
        assertEquals("下载链接需为 http(s) 链接", assertThrows(IllegalArgumentException.class,
                () -> controller.publish(center, file, "EXTERNAL", "ftp://x/y", null, null, null)).getMessage());
        assertEquals("托管方式只能是 HOSTED 或 EXTERNAL", assertThrows(IllegalArgumentException.class,
                () -> controller.publish(center, file, "MAGIC", null, null, null, null)).getMessage());
        assertEquals("请选择要上传的内容包文件（.tiku 或 .json）", assertThrows(IllegalArgumentException.class,
                () -> controller.publish(center, new MockMultipartFile("file", "a.tiku", null, new byte[0]),
                        null, null, null, null, null)).getMessage());
        assertEquals(0, hits.get());
    }

    @Test
    void remote401MessageIsPassedThrough() {
        responseCode = 401;
        responseBody = "{\"statusCode\":401,\"statusMessage\":\"请先登录\",\"message\":\"请先登录\",\"data\":{\"code\":401,\"message\":\"请先登录\"}}"
                .getBytes(StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CenterPublishController(authStore).myPacks(center, null, null));
        assertEquals("请先登录", e.getMessage());
        assertEquals("/api/me/packs", captured.path());
    }

    @Test
    void uploadFileUsesPutMultipart() throws Exception {
        // 补传也走体检：夹具必须是合法内容包，且 packageKey/version 与登记（mykey / 1.0）一致
        byte[] pkg = PackageContainer.pack(
                "{\"schemaVersion\":2,\"packageKey\":\"mykey\",\"version\":\"1.0\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8), null);
        ResponseEntity<String> resp = new CenterPublishController(authStore).uploadPackFile(center, "mykey", "1.0",
                new MockMultipartFile("file", "x.tiku", "application/zip", pkg));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("PUT", captured.method());
        assertEquals("/api/packs/mykey/1.0/file", captured.path());
        String boundary = boundaryOf(captured.contentType());
        assertArrayEquals(pkg, partBytes(captured.body(), boundary, "file"));
        assertNotNull(captured.body());
    }

    /** 补传：格式不合法 = 零公网请求（与 publish 同一套体检） */
    @Test
    void uploadFileRejectsInvalidPackageWithoutRemoteRequest() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CenterPublishController(authStore).uploadPackFile(center, "mykey", "1.0",
                        new MockMultipartFile("file", "x.tiku", null, "not a package".getBytes(StandardCharsets.UTF_8))));
        assertEquals("题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）", e.getMessage());
        assertEquals(0, hits.get(), "补传校验不通过不得发出任何公网请求");
    }

    /** 补传：文件内 packageKey/version 与登记不一致 = 零公网请求（官网 file.put.ts 同样会拒绝） */
    @Test
    void uploadFileRejectsMismatchedIdentityWithoutRemoteRequest() throws Exception {
        byte[] other = PackageContainer.pack(
                "{\"schemaVersion\":2,\"packageKey\":\"otherkey\",\"version\":\"9.9\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8), null);
        CenterPublishController controller = new CenterPublishController(authStore);
        assertEquals("文件内 packageKey 与登记不一致（文件 otherkey，登记 mykey）",
                assertThrows(IllegalArgumentException.class, () -> controller.uploadPackFile(center, "mykey", "1.0",
                        new MockMultipartFile("file", "x.tiku", null, other))).getMessage());

        byte[] sameKeyWrongVersion = PackageContainer.pack(
                "{\"schemaVersion\":2,\"packageKey\":\"mykey\",\"version\":\"2.0\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8), null);
        assertEquals("文件内 version 与登记不一致（文件 2.0，登记 1.0）",
                assertThrows(IllegalArgumentException.class, () -> controller.uploadPackFile(center, "mykey", "1.0",
                        new MockMultipartFile("file", "x.tiku", null, sameKeyWrongVersion))).getMessage());
        assertEquals(0, hits.get());
    }
}
