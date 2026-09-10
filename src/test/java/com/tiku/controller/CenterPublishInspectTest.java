package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tiku.dto.ApiResponse;
import com.tiku.service.CenterAuthStore;
import com.tiku.service.ContentPackageInspector;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布前「本地体检」回归测试：POST /api/center/publish/inspect 的元数据提取与错误口径，
 * 以及 POST /api/center/publish 的前置校验（不合法 = 零公网请求，合法 = 字节原样转发）。
 *
 * 用本机假中心服务器（HttpServer）统计命中次数：hits == 0 即证明确实一个公网请求都没发。
 * 断言里的错误文案与官网 web/server/utils/package-meta.ts + upload.post.ts 一一对应，
 * 改文案即改契约（前端弹窗直接展示这些 message）。
 */
class CenterPublishInspectTest {

    private HttpServer server;
    private String center;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile byte[] body;
    private CenterAuthStore authStore;

    private static final byte[] OK_RESP = "{\"code\":200,\"data\":{\"packageKey\":\"k\"},\"message\":\"发布成功\"}"
            .getBytes(StandardCharsets.UTF_8);

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
            body = bos.toByteArray();
            exchange.sendResponseHeaders(200, OK_RESP.length);
            exchange.getResponseBody().write(OK_RESP);
            exchange.close();
        });
        server.start();
        center = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String v1(String extra) {
        return "{\"schemaVersion\":1,\"packageKey\":\"key-1\",\"version\":\"1.0.0\",\"title\":\" 数学题库 \","
                + "\"description\":\" 一份描述 \",\"source\":\" 某网站 \","
                + "\"questions\":[{\"questionKey\":\"q1\"},{\"questionKey\":\"q2\"}],"
                + "\"materials\":[{\"materialKey\":\"m1\"}]" + (extra == null ? "" : "," + extra) + "}";
    }

    private static byte[] tikuBytes(String packageJson, int mediaBytes) throws Exception {
        Map<String, byte[]> media = new LinkedHashMap<>();
        if (mediaBytes > 0) {
            byte[] blob = new byte[mediaBytes];
            new Random(11).nextBytes(blob);
            media.put("260829/big.bin", blob);
        }
        return PackageContainer.pack(packageJson.getBytes(StandardCharsets.UTF_8), media);
    }

    // ---------- A. 两种格式都支持，且按魔数判定（不看扩展名） ----------

    @Test
    void detectsFormatByMagicNotByExtension() throws Exception {
        // v1 纯 JSON 但文件名是 .tiku → 必须按 JSON 解析
        byte[] json = v1(null).getBytes(StandardCharsets.UTF_8);
        ContentPackageInspector.Inspection byJson = new CenterPublishController(authStore).inspectPublishFile(
                new MockMultipartFile("file", "wrong-ext.tiku", "application/octet-stream", json)).data();
        assertEquals(1, byJson.schemaVersion(), "扩展名 .tiku 但内容是 JSON → 按 v1 JSON 解析");
        assertEquals("key-1", byJson.packageKey());
        assertEquals(2, byJson.questionsCount());

        // v2 .tiku 容器但文件名是 .json → 必须按 zip 容器解析
        byte[] zip = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"kz\",\"version\":\"3.0\",\"title\":\"容器\","
                + "\"questions\":[{},{}]}", 512);
        ContentPackageInspector.Inspection byZip = new CenterPublishController(authStore).inspectPublishFile(
                new MockMultipartFile("file", "wrong-ext.json", "application/json", zip)).data();
        assertEquals(2, byZip.schemaVersion(), "扩展名 .json 但内容是 .tiku 容器 → 按 v2 容器解析");
        assertEquals("kz", byZip.packageKey());
        assertEquals(2, byZip.questionsCount());

        // 完全没有扩展名/未知扩展名同样按内容判定
        ContentPackageInspector.Inspection noExt = new CenterPublishController(authStore).inspectPublishFile(
                new MockMultipartFile("file", "bankfile", "application/octet-stream", zip)).data();
        assertEquals(2, noExt.schemaVersion());
        assertEquals("kz", noExt.packageKey());
    }

    @Test
    void bothFormatsExposeSameFieldNamesAndCounts() throws Exception {
        String fields = "\"packageKey\":\"same-key\",\"version\":\"1.2.3\",\"title\":\"同名标题\","
                + "\"description\":\"描述\",\"source\":\"来源\",\"questions\":[{},{}],\"materials\":[{}]";
        ContentPackageInspector.Inspection v1Meta = ContentPackageInspector.inspect(
                ("{\"schemaVersion\":1," + fields + "}").getBytes(StandardCharsets.UTF_8));
        ContentPackageInspector.Inspection v2Meta = ContentPackageInspector.inspect(
                tikuBytes("{\"schemaVersion\":2," + fields + "}", 256));
        assertEquals(v1Meta.packageKey(), v2Meta.packageKey());
        assertEquals(v1Meta.version(), v2Meta.version());
        assertEquals(v1Meta.title(), v2Meta.title());
        assertEquals(v1Meta.description(), v2Meta.description());
        assertEquals(v1Meta.source(), v2Meta.source());
        assertEquals(v1Meta.questionsCount(), v2Meta.questionsCount());
        assertEquals(v1Meta.materialsCount(), v2Meta.materialsCount());
        // schemaVersion 如实反映两种格式
        assertEquals(1, v1Meta.schemaVersion());
        assertEquals(2, v2Meta.schemaVersion());
    }

    // ---------- A. 体检接口 ----------

    @Test
    void inspectV1JsonReturnsMetadata() {
        ApiResponse<ContentPackageInspector.Inspection> r =
                new CenterPublishController(authStore).inspectPublishFile(
                        new MockMultipartFile("file", "b.json", "application/json", v1(null).getBytes(StandardCharsets.UTF_8)));
        ContentPackageInspector.Inspection d = r.data();
        assertEquals(1, d.schemaVersion());
        assertEquals("key-1", d.packageKey());
        assertEquals("1.0.0", d.version());
        assertEquals("数学题库", d.title(), "标题应 trim");
        assertEquals("一份描述", d.description());
        assertEquals("某网站", d.source());
        assertEquals(2, d.questionsCount());
        assertEquals(1, d.materialsCount());
        assertEquals(0, hits.get(), "体检不得访问公网");
    }

    @Test
    void inspectTikuZipReturnsMetadata() throws Exception {
        byte[] zip = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k2\",\"version\":\"2.1\",\"title\":\"容器题库\","
                + "\"questions\":[{}]}", 1024);
        ApiResponse<ContentPackageInspector.Inspection> r = new CenterPublishController(authStore).inspectPublishFile(
                new MockMultipartFile("file", "a.tiku", "application/zip", zip));
        assertEquals(2, r.data().schemaVersion());
        assertEquals("k2", r.data().packageKey());
        assertEquals(1, r.data().questionsCount());
        assertNull(r.data().description());
    }

    @Test
    void inspectLargeTikuUsesTempFileAndStillReadsMetadata() throws Exception {
        // 6MB > 4MB 暂存阈值 → 走临时文件 + ZipFile 随机读路径
        byte[] zip = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"big\",\"version\":\"9.9\",\"title\":\"大包\","
                + "\"questions\":[{},{},{}]}", 6 * 1024 * 1024);
        assertTrue(zip.length > 4 * 1024 * 1024, "测试前提：容器应超过暂存阈值");
        ApiResponse<ContentPackageInspector.Inspection> r = new CenterPublishController(authStore).inspectPublishFile(
                new MockMultipartFile("file", "big.tiku", "application/zip", zip));
        assertEquals("big", r.data().packageKey());
        assertEquals(3, r.data().questionsCount());
    }

    @Test
    void inspectRequiresLogin() {
        authStore.clear();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CenterPublishController(authStore).inspectPublishFile(
                        new MockMultipartFile("file", "b.json", null, v1(null).getBytes(StandardCharsets.UTF_8))));
        assertEquals("请先登录题库广场账号", e.getMessage());
        assertEquals(0, hits.get());
    }

    // ---------- A. 错误文案（与官网 package-meta.ts / upload.post.ts 同口径） ----------

    /** 只含 media/ 条目、没有 package.json 的真实 zip */
    private static byte[] zipWithoutPackageJson() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("media/a.png"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static String inspectMessage(byte[] data) {
        return assertThrows(IllegalArgumentException.class, () -> ContentPackageInspector.inspect(data)).getMessage();
    }

    @Test
    void errorMessagesAreUserReadableAndSpecific() throws Exception {
        assertEquals("请选择要上传的内容包文件（.tiku 或 .json）", inspectMessage(new byte[0]));
        // 既不是容器也不是 JSON
        assertEquals("题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）",
                inspectMessage("not json at all".getBytes(StandardCharsets.UTF_8)));
        // ---- 纯 JSON（v1）专属文案：v1 结构必备字段缺失 ----
        assertEquals("该 .json 不是 v1 题库文件（缺少 schemaVersion=1）",
                inspectMessage("{\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("该 .json 不是 v1 题库文件（缺少 schemaVersion=1）",
                inspectMessage("{\"schemaVersion\":\"1\",\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8)), "字符串 \"1\" 不是数字 1：官网同样拒绝");
        assertEquals("该 .json 不是 v1 题库文件（缺少 questions）",
                inspectMessage("{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("该 .json 不是 v1 题库文件（缺少 questions）",
                inspectMessage("{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("该 .json 不是 v1 题库文件（缺少 questions）",
                inspectMessage("{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":{\"a\":1}}"
                        .getBytes(StandardCharsets.UTF_8)));
        // schemaVersion 是数字但取值非法 → 沿用官网文案（两种格式一致）
        assertEquals("题库文件中 schemaVersion 需为 1 或 2",
                inspectMessage("{\"schemaVersion\":3,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        // ---- 两种格式共用的字段校验文案 ----
        assertEquals("题库文件缺少 packageKey",
                inspectMessage("{\"schemaVersion\":1,\"version\":\"1\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("题库文件 packageKey 不合法（只能是字母、数字、点、下划线、连字符，1-100 个字符）",
                inspectMessage("{\"schemaVersion\":1,\"packageKey\":\"a b/c\",\"version\":\"1\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("题库文件缺少版本号（version）",
                inspectMessage("{\"schemaVersion\":1,\"packageKey\":\"k\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("题库文件缺少标题",
                inspectMessage("{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"  \",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("题库文件缺少标题",
                inspectMessage(tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1\",\"questions\":[{}]}", 0)));
        // 末尾多余内容：与官网 JSON.parse 全量解析一致地报错
        assertEquals("题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）",
                inspectMessage((v1(null) + "{}").getBytes(StandardCharsets.UTF_8)));
        // 截断的 JSON
        assertEquals("题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）",
                inspectMessage("{\"schemaVersion\":1,\"packageKey\":\"k\"".getBytes(StandardCharsets.UTF_8)));
        // ---- .tiku（v2 容器）专属文案 ----
        assertEquals("题库文件解析失败：.tiku 容器内缺少 manifest（package.json）", inspectMessage(zipWithoutPackageJson()));
        assertEquals("题库文件解析失败：.tiku 容器解压失败（zip 格式损坏）",
                inspectMessage("PK\u0003\u0004not a real zip".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("题库文件解析失败：.tiku 容器内 manifest（package.json）不是合法 JSON",
                inspectMessage(tikuBytes("{not json", 0)));
        assertEquals("题库文件中 schemaVersion 需为 1 或 2", inspectMessage(tikuBytes("{}", 0)));
        assertEquals("题库文件中没有题目，无法发布",
                inspectMessage(tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[]}", 0)));
        assertEquals("题库文件缺少 packageKey",
                inspectMessage(tikuBytes("{\"schemaVersion\":2,\"version\":\"1\",\"title\":\"t\",\"questions\":[{}]}", 0)));
    }

    // ---------- B. 发布前置校验：不合法 = 零公网请求 ----------

    private static String errorOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** v1 纯 JSON 的大文件（>4MB，内联 images 很大）：走临时文件 + 流式解析路径 */
    private static byte[] bigV1Json() {
        String head = "{\"schemaVersion\":1,\"packageKey\":\"bigv1\",\"version\":\"1.0\",\"title\":\"大JSON\","
                + "\"questions\":[{}],\"images\":{\"a.png\":\"";
        return (head + "A".repeat(5 * 1024 * 1024) + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static String validV2Manifest() {
        return "{\"schemaVersion\":2,\"packageKey\":\"kv2\",\"version\":\"2.0\",\"title\":\"容器\",\"questions\":[{}]}";
    }

    /**
     * 一致性：同一个文件经 /publish/inspect 与 /publish 的判定必须完全相同。
     * 两条路径调用的是同一份 ContentPackageInspector 逻辑、同一份 Staged 暂存（无分支差异）；
     * 这里覆盖 v1 JSON / v2 .tiku、内存态与临时文件态（>4MB）、以及各类不合法样本：
     * 通过则 publish 必须真的转发且字节一致，不通过则两者 message 相同且零公网请求。
     */
    @Test
    void inspectAndPublishAgreeOnEverySample() throws Exception {
        List<byte[]> samples = new ArrayList<>();
        samples.add(v1(null).getBytes(StandardCharsets.UTF_8));
        samples.add(tikuBytes(validV2Manifest(), 512));
        samples.add(tikuBytes(validV2Manifest(), 5 * 1024 * 1024));
        samples.add(bigV1Json());
        samples.add("junk, neither zip nor json".getBytes(StandardCharsets.UTF_8));
        samples.add("{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1\",\"questions\":[{}]}"
                .getBytes(StandardCharsets.UTF_8));
        samples.add("{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\"}"
                .getBytes(StandardCharsets.UTF_8));
        samples.add("{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[{}]}"
                .getBytes(StandardCharsets.UTF_8));
        samples.add(tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\"}", 0));
        samples.add(tikuBytes("{not json", 0));
        samples.add(zipWithoutPackageJson());

        int forwarded = 0;
        for (byte[] data : samples) {
            CenterPublishController controller = new CenterPublishController(authStore);
            String inspectError = errorOf(() -> controller.inspectPublishFile(
                    new MockMultipartFile("file", "sample.tiku", null, data)));
            String publishError = errorOf(() -> controller.publish(center,
                    new MockMultipartFile("file", "sample.tiku", null, data), null, null, null, null, null));
            assertEquals(inspectError, publishError, "同一文件在 inspect 与 publish 的判定必须一致");
            if (inspectError == null) {
                forwarded++;
                assertEquals(forwarded, hits.get(), "校验通过必须真的转发");
                assertTrue(new String(body, StandardCharsets.ISO_8859_1)
                        .contains(new String(data, StandardCharsets.ISO_8859_1)), "转发字节必须与文件一致");
            } else {
                assertEquals(forwarded, hits.get(), "校验不通过不得发请求：" + inspectError);
            }
        }
        assertTrue(forwarded >= 4, "样本里至少有 4 个合法内容包（两种格式 × 内存态/临时文件态）");
    }

    @Test
    void publishRejectsInvalidPackageWithoutAnyRemoteRequest() {
        byte[] bad = "totally not a package".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CenterPublishController(authStore).publish(center,
                        new MockMultipartFile("file", "bad.tiku", null, bad), null, null, null, null, null));
        assertEquals("题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）", e.getMessage());
        assertEquals(0, hits.get(), "校验不通过不得发出任何公网请求");
    }

    /** v1 JSON 与 v2 .tiku 都要能发布（扩展名写错也不影响） */
    @Test
    void publishAcceptsBothFormatsRegardlessOfExtension() throws Exception {
        byte[] json = v1(null).getBytes(StandardCharsets.UTF_8);
        new CenterPublishController(authStore).publish(center,
                new MockMultipartFile("file", "wrong.tiku", null, json), null, null, null, null, null);
        assertEquals(1, hits.get());

        byte[] zip = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k2\",\"version\":\"2.1\",\"title\":\"容器题库\","
                + "\"questions\":[{}]}", 512);
        new CenterPublishController(authStore).publish(center,
                new MockMultipartFile("file", "wrong.json", "application/json", zip), null, null, null, null, null);
        assertEquals(2, hits.get());
    }

    @Test
    void publishRejectsEmptyQuestionBankWithoutRemoteRequest() throws Exception {
        byte[] emptyQuestions = "{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CenterPublishController(authStore).publish(center,
                        new MockMultipartFile("file", "a.json", null, emptyQuestions), null, null, null, null, null));
        assertEquals("该 .json 不是 v1 题库文件（缺少 questions）", e.getMessage());
        assertEquals(0, hits.get());

        // .tiku 容器空题数走容器文案
        byte[] emptyTiku = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1\",\"title\":\"t\",\"questions\":[]}", 0);
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> new CenterPublishController(authStore).publish(center,
                        new MockMultipartFile("file", "a.tiku", null, emptyTiku), null, null, null, null, null));
        assertEquals("题库文件中没有题目，无法发布", e2.getMessage());
        assertEquals(0, hits.get());
    }

    @Test
    void publishSendsValidPackageUnchanged() throws Exception {
        byte[] zip = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k2\",\"version\":\"2.1\",\"title\":\"容器题库\","
                + "\"questions\":[{}]}", 1024);
        ResponseEntity<String> resp = new CenterPublishController(authStore).publish(center,
                new MockMultipartFile("file", "a.tiku", "application/zip", zip), null, null, "标题", null, null);
        assertEquals(1, hits.get());
        assertTrue(resp.getBody().contains("发布成功"));
        assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains(
                new String(zip, StandardCharsets.ISO_8859_1)), "转发内容应与体检所用字节完全一致");
    }

    @Test
    void publishLargeFileSpoolsOnceAndKeepsBytes() throws Exception {
        byte[] zip = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"k9\",\"version\":\"1.0\",\"title\":\"大包\","
                + "\"questions\":[{}]}", 5 * 1024 * 1024 + 999);
        assertTrue(zip.length > 4 * 1024 * 1024);
        new CenterPublishController(authStore).publish(center,
                new MockMultipartFile("file", "bank_v2.tiku", null, zip), "external", "https://example.com/a.tiku",
                null, null, null);
        assertEquals(1, hits.get());
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        String zipRaw = new String(zip, StandardCharsets.ISO_8859_1);
        assertNotNull(raw);
        assertTrue(raw.contains(zipRaw), "大文件转发字节应与暂存内容一致");
        assertTrue(raw.contains("storageKind"), "storageKind 字段应显式下发");
    }

    /** 体检路径：文件态（Path）与内存态结果一致 */
    @Test
    void pathInspectMatchesByteInspect(@TempDir Path tempDir) throws Exception {
        byte[] zip = tikuBytes("{\"schemaVersion\":2,\"packageKey\":\"kp\",\"version\":\"1.2.3\",\"title\":\"路径\","
                + "\"questions\":[{},{}]}", 2048);
        Path f = tempDir.resolve("x.tiku");
        Files.write(f, zip);
        ContentPackageInspector.Inspection a = ContentPackageInspector.inspect(zip);
        ContentPackageInspector.Inspection b = ContentPackageInspector.inspect(f);
        assertEquals(a, b);

        Path json = tempDir.resolve("x.json");
        Files.write(json, v1(null).getBytes(StandardCharsets.UTF_8));
        ContentPackageInspector.Inspection c = ContentPackageInspector.inspect(json);
        assertEquals("key-1", c.packageKey());
        assertEquals(2, c.questionsCount());
        assertArrayEquals(zip, Files.readAllBytes(f));
    }
}
