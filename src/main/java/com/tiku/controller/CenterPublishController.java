package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.PublishFromPathRequest;
import com.tiku.service.CenterAuthStore;
import com.tiku.service.ContentPackageInspector;
import com.tiku.service.ExportRecordService;
import com.tiku.util.PackageContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 题库广场「发布作品 / 我的作品」写侧代理（二期）。
 * 桌面端在应用内发布内容包、管理自己的作品，全部经本地后端转发到官网：
 * - POST   /api/center/publish/inspect             发布前本地体检（只解析元数据，不导入不转发）
 * - POST   /api/center/publish                     直传发布（multipart，服务端解析元数据自动登记）
 * - POST   /api/center/publish-from-path           从本地路径直接发布（JSON body，文件不走前端中转）
 * - GET    /api/me/packs                           我的作品（含已下架，每作品最新一行 + 版本数）
 * - DELETE /api/packs/{packageKey}                 下架整个作品
 * - PUT    /api/packs/{packageKey}/{version}       更新版本元数据（描述/来源/外链）
 * - PUT    /api/packs/{packageKey}/{version}/file  补传托管文件（multipart）
 *
 * 与 {@link CenterProxyController}（只读浏览）同样用 HttpURLConnection 转发：
 * 上传走手写 multipart body + setFixedLengthStreamingMode 流式写出，200MB 内容包不进堆；
 * 已登录自动带 Authorization: Bearer（token 存 CenterAuthStore，前端不接触 token）；
 * 本地无 token 直接拒绝，不发远程请求——官网会话失效时官网返回 401，message 原样透传前端。
 *
 * 发布/补传前置校验（避免 200MB 文件跨境传完才被官网拒绝）：
 * - POST /publish 与 PUT /packs/{k}/{v}/file 转发前都先用 {@link ContentPackageInspector}
 *   按官网 package-meta.ts / upload.post.ts / file.put.ts 同口径严格校验（.tiku 与 .json 两种格式都支持），
 *   不合法直接抛 IllegalArgumentException（400 + 可读 message），一个字节都不发往公网；
 * - /publish/inspect 是同一套校验的只读入口（前端选文件后立刻调用，不转发）；
 * - 上传内容只读一次：先暂存（大文件落临时文件，见 {@link Staged}），体检与转发共用同一份暂存，
 *   体检只从暂存里取 manifest（大文件走 zip 中央目录随机读），不触发全文件二次读取。
 */
@RestController
@RequestMapping("/api/center")
public class CenterPublishController {

    private static final Logger log = LoggerFactory.getLogger(CenterPublishController.class);

    /** 内容包文件上限，与官网 upload.post.ts / file.put.ts 的 MAX_BYTES 一致 */
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    /** 超过该阈值先把上传内容落到临时文件，再流式转发（小文件直接留在内存，省一次磁盘往返） */
    private static final long SPOOL_THRESHOLD_BYTES = 4L * 1024 * 1024;
    private static final int COPY_BUFFER = 64 * 1024;
    /** 连接 8s；读取 300s（上传/发布可能较慢，含官网解包内容包与落盘的时间） */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 300_000;

    private final CenterAuthStore authStore;
    /**
     * 导出记录（可选依赖）：从本地路径发布时带 exportRecordId 才用到。
     * 单独留一个单参构造：单元测试与手工构造（CenterPublishForwardTest 等）不需要数据层，
     * 此时联动标记为 null = 不标记（发布本身照常完成）。
     */
    private final ExportRecordService exportRecordService;

    /** 单参构造：测试/手工构造用（不联动导出记录标记） */
    public CenterPublishController(CenterAuthStore authStore) {
        this(authStore, null);
    }

    @Autowired
    public CenterPublishController(CenterAuthStore authStore, ExportRecordService exportRecordService) {
        this.authStore = authStore;
        this.exportRecordService = exportRecordService;
    }

    // ==================== 校验与鉴权 ====================

    /** 校验中心地址：仅 http/https，禁止带用户信息（官方地址 https://pickq.cn） */
    private String checkBase(String center) {
        String base = center == null || center.isBlank() ? "https://pickq.cn" : center.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("广场地址需为 http(s) 链接");
        }
        if (base.contains("@")) {
            throw new IllegalArgumentException("广场地址不合法");
        }
        return base.replaceAll("/+$", "");
    }

    /** 发布/管理作品都需要登录；本地无 token 直接拒绝（前端据此引导登录），不发远程请求 */
    private void requireLogin() {
        if (authStore == null || !authStore.isLoggedIn()) {
            throw new IllegalStateException("请先登录题库广场账号");
        }
    }

    /** 已登录广场则附加 Authorization（官网据会话判定作者身份与权限） */
    private void attachAuth(HttpURLConnection conn) {
        String token = authStore == null ? null : authStore.token();
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
    }

    /** 托管方式：HOSTED（托管，文件直传中心）/ EXTERNAL（作者外链，文件解析后即弃）；缺省 HOSTED */
    private static String normalizeStorageKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return "HOSTED";
        }
        String kind = raw.trim().toUpperCase(Locale.ROOT);
        if (!"HOSTED".equals(kind) && !"EXTERNAL".equals(kind)) {
            throw new IllegalArgumentException("托管方式只能是 HOSTED 或 EXTERNAL");
        }
        return kind;
    }

    /** 外链校验：官网 upload.post.ts 只接受 http/https 直链 */
    private static String checkDownloadUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        String scheme;
        try {
            scheme = URI.create(url).getScheme();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("下载链接不合法");
        }
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("下载链接需为 http(s) 链接");
        }
        return url;
    }

    /** 上传文件通用校验（官网上限同为 200MB） */
    private static void checkFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的内容包文件（.tiku 或 .json）");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("内容包文件超过 200MB 上限");
        }
    }

    /**
     * filename 用 ASCII 安全名（如 package.tiku）：官网不靠文件名解析（认 PK 魔数/JSON 内容），
     * 但中文名会让 multipart 头部依赖编码约定，故只保留 [A-Za-z0-9._-] 并保留原扩展名。
     */
    private static String asciiFilename(String original) {
        String name = original == null ? "" : original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String ext = "";
        String stem = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1 && name.substring(dot + 1).matches("[A-Za-z0-9]{1,8}")) {
            ext = "." + name.substring(dot + 1).toLowerCase(Locale.ROOT);
            stem = name.substring(0, dot);
        }
        String safeStem = stem.replaceAll("[^A-Za-z0-9._-]", "");
        if (safeStem.isBlank()) {
            safeStem = "package";
        }
        if (safeStem.length() > 60) {
            safeStem = safeStem.substring(0, 60);
        }
        return safeStem + ext;
    }

    /** 保留浏览器上报的原始 Content-Type（缺省 application/octet-stream），剔除 CR/LF 防头部注入 */
    private static String safeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "application/octet-stream";
        }
        String contentType = raw.replaceAll("[\r\n]", "").trim();
        return contentType.isEmpty() ? "application/octet-stream" : contentType;
    }

    // ==================== 上传内容暂存（体检与转发共用同一次读入） ====================

    /**
     * 上传内容的一次性暂存：≤ {@link #SPOOL_THRESHOLD_BYTES} 留内存，更大落临时文件。
     * 存在的意义：发布路径既要"体检"又要"转发"，若各自读一遍 MultipartFile，
     * 200MB 文件会被完整读两次；暂存后体检只取元数据（zip 随机读 package.json）、转发流式写出，
     * 客户端请求体只被读一次。close() 只删除自己创建的临时文件。
     * <p>
     * 从本地路径发布（{@link #stageFile}）时 tempFile 就是用户磁盘上的那个 .tiku：
     * 不复制、不读进内存，自有标记 owned=false 保证 close() 绝不删用户文件。
     */
    private record Staged(byte[] bytes, Path tempFile, long size, boolean owned) implements AutoCloseable {

        /** 顺序读取（转发写出用） */
        InputStream open() throws IOException {
            return bytes != null ? new ByteArrayInputStream(bytes) : Files.newInputStream(tempFile);
        }

        /**
         * 本地体检：内存态直接解析；文件态交给 {@link ContentPackageInspector#inspect(Path)}——
         * .tiku 走 ZipFile 随机读中央目录，只解压 package.json，media 图片不参与解压。
         */
        ContentPackageInspector.Inspection inspect() {
            return bytes != null
                    ? ContentPackageInspector.inspect(bytes)
                    : ContentPackageInspector.inspect(tempFile);
        }

        @Override
        public void close() {
            //只删本类自己创建的临时文件（owned）：从本地路径发布时那是用户的文件
            if (tempFile != null && owned) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    /* 临时文件清理失败可忽略（系统临时目录会回收） */
                }
            }
        }
    }

    /** 把上传内容暂存一次（内存或临时文件），失败抛 IllegalStateException（IO 问题，非用户输入问题） */
    private static Staged stage(MultipartFile file) {
        try {
            if (file.getSize() > SPOOL_THRESHOLD_BYTES) {
                Path tempFile = Files.createTempFile("tiku-center-upload-", ".part");
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                        /* 清理失败可忽略 */
                    }
                    throw e;
                }
                return new Staged(null, tempFile, Files.size(tempFile), true);
            }
            byte[] bytes = file.getBytes();
            return new Staged(bytes, null, bytes.length, true);
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage());
        }
    }

    /** 从本地磁盘路径暂存（发布中心导出的文件就在本机）：直接引用原文件，不复制不删（owned=false） */
    private static Staged stageFile(Path file, long size) {
        return new Staged(null, file, size, false);
    }

    // ==================== multipart 组装与转发 ====================

    /** 普通文本字段（Content-Disposition: form-data; name="x" + UTF-8 值） */
    private record TextPart(String name, String value) {
    }

    /**
     * multipart 骨架：文本段 + 文件段头 + 收尾段。
     * 先算字节长度（文本按 UTF-8 编码后计长，非字符数），才能用
     * setFixedLengthStreamingMode(总长度) 精确流式写出，不必先缓冲整个 body。
     */
    private record MultipartSkeleton(String boundary, byte[] texts, byte[] fileHeader, byte[] tail) {

        long totalLength(long fileSize) {
            return texts.length + fileHeader.length + fileSize + tail.length;
        }
    }

    private static MultipartSkeleton buildSkeleton(List<TextPart> parts, String fileName, String contentType) {
        String boundary = "----TikuDesktopBoundary" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        for (TextPart part : parts) {
            sb.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(part.name()).append("\"\r\n\r\n")
                    .append(part.value()).append("\r\n");
        }
        String fileHeader = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        return new MultipartSkeleton(boundary, sb.toString().getBytes(StandardCharsets.UTF_8),
                fileHeader.getBytes(StandardCharsets.UTF_8), tail.getBytes(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[COPY_BUFFER];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    /**
     * 流式转发 multipart（POST 发布 / PUT 补传文件）：
     * - 文件内容已由调用方暂存（见 {@link Staged}）：内存态直接写、文件态边读边写请求体；
     *   setFixedLengthStreamingMode(总长度) 让 JDK 不做内存缓冲 → 200MB 只有 64KB 级的内存峰值；
     * - 临时文件的清理责任在调用方（try-with-resources 里的 Staged）——官网响应读完后统一清理；
     * - 文本字段保留原字段名、值以 UTF-8 原样写出；文件字段名固定 file（官网读的是 file）。
     */
    private String forwardMultipart(String method, String url, List<TextPart> textParts, Staged staged,
                                    String originalFilename, String contentType) {
        MultipartSkeleton skeleton = buildSkeleton(textParts, asciiFilename(originalFilename),
                safeContentType(contentType));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            attachAuth(conn);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + skeleton.boundary());
            conn.setFixedLengthStreamingMode(skeleton.totalLength(staged.size()));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(skeleton.texts());
                os.write(skeleton.fileHeader());
                try (InputStream in = staged.open()) {
                    copy(in, os);
                }
                os.write(skeleton.tail());
            }
            return readResponse(conn);
        } catch (IOException e) {
            // 大文件写到一半被官网提前拒绝（401/409/400）时只会看到 broken pipe 之类的 IO 异常，
            // 先尽力把官网已返回的 message 捞出来透传，捞不到才报连接失败
            String remote = earlyRemoteError(conn);
            throw new IllegalStateException(remote != null ? remote : "无法连接题库广场：" + e.getMessage());
        }
    }

    /** JSON body 原样透传的转发（DELETE 下架 / PUT 更新元数据） */
    private String forwardJson(String method, String url, String jsonBody) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            attachAuth(conn);
            conn.setRequestProperty("Accept", "application/json");
            if (jsonBody != null && !jsonBody.isBlank()) {
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            return readResponse(conn);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接题库广场：" + e.getMessage());
        }
    }

    /** GET 转发：返回响应体文本 */
    private String getText(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            attachAuth(conn);
            conn.setRequestProperty("Accept", "application/json");
            return readResponse(conn);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接题库广场：" + e.getMessage());
        }
    }

    /** 上传途中写请求体失败时，尝试读取官网已返回的错误体（拿不到则返回 null） */
    private static String earlyRemoteError(HttpURLConnection conn) {
        if (conn == null) {
            return null;
        }
        try {
            int code = conn.getResponseCode();
            if (code < 400) {
                return null;
            }
            InputStream err = conn.getErrorStream();
            if (err == null) {
                return null;
            }
            String body = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            return extractRemoteError(body, code);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** 读取官网响应：HTTP >= 400 → 抛 IllegalStateException（官网 message 原样给前端） */
    private static String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = stream == null ? ""
                : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
        if (code >= 400) {
            throw new IllegalStateException(extractRemoteError(body, code));
        }
        return body;
    }

    /** 从官网错误响应体提取用户可读 message（h3 错误 JSON：顶层 message / data.message / statusMessage） */
    private static String extractRemoteError(String body, int code) {
        if (body != null && !body.isBlank()) {
            for (String field : new String[]{"message", "statusMessage"}) {
                int idx = body.indexOf('"' + field + '"');
                if (idx >= 0) {
                    int colon = body.indexOf(':', idx);
                    int start = colon > 0 ? body.indexOf('"', colon) : -1;
                    int end = start > 0 ? body.indexOf('"', start + 1) : -1;
                    if (start > 0 && end > start) {
                        String msg = body.substring(start + 1, end);
                        if (!msg.isBlank()) {
                            return msg;
                        }
                    }
                }
            }
        }
        return "题库广场返回错误（HTTP " + code + "）";
    }

    private static ResponseEntity<String> textJson(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    // ==================== 接口 ====================

    /**
     * POST /api/center/publish/inspect（multipart/form-data，需登录）— 发布前本地体检。
     * 表单字段：file（内容包 .tiku / .json，≤200MB）。
     * 两种格式都支持，且按魔数判定、不看扩展名（用户可能选错后缀）：
     * PK\x03\x04 魔数 = v2 .tiku zip 容器（解出 manifest：package.json，media/ 图片不解压）；
     * 否则按 v1 纯 JSON 解析。两者字段名与计数口径一致，schemaVersion 如实回传（1 或 2）。
     * 只解析元数据：不导入题库、不落库、不持久化（大文件仅暂存到系统临时文件，返回前删除）、
     * 不向公网发任何请求；
     * 成功返回 { packageKey, version, title, description, source, schemaVersion, questionsCount, materialsCount }
     * （取不到的字段为 null），失败抛 IllegalArgumentException → 400 + 对用户可读的 message。
     * 前端在用户选中文件后立刻调用：先本地判定能不能发布，再决定要不要上传 200MB。
     */
    @PostMapping(value = "/publish/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContentPackageInspector.Inspection> inspectPublishFile(@RequestPart("file") MultipartFile file) {
        requireLogin();
        checkFile(file);
        // 大文件先暂存到临时文件，让 zip 走中央目录随机读（只解压 package.json）；close() 删除临时文件
        try (Staged staged = stage(file)) {
            return ApiResponse.success(staged.inspect());
        }
    }

    /**
     * POST /api/center/publish（multipart/form-data，需登录）— 应用内发布作品。
     * 表单字段：file（内容包 .tiku / .json，≤200MB）、storageKind（HOSTED 缺省 / EXTERNAL）、
     * downloadUrl（EXTERNAL 必填）、title、description、source；center 可作查询参数或表单字段。
     * 官网 upload.post.ts 读的字段正是 file / storageKind / downloadUrl / description / source
     * （title 会覆盖内容包内标题，留空则沿用包内标题）；
     * 注意官网 storageKind 缺省是 EXTERNAL，本地缺省为 HOSTED，故始终显式送该字段。
     *
     * 转发前用同一套体检逻辑做严格校验：不合法直接本地报错（不发任何公网请求），
     * 合法才转发；体检与转发共用同一次暂存，不重复读大文件。
     */
    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> publish(
            @RequestParam(required = false) String center,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String storageKind,
            @RequestParam(required = false) String downloadUrl,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String source) {
        requireLogin();
        String base = checkBase(center);
        checkFile(file);
        String kind = normalizeStorageKind(storageKind);
        String url = checkDownloadUrl(downloadUrl);
        if ("EXTERNAL".equals(kind) && url.isEmpty()) {
            throw new IllegalArgumentException("外链方式需要提供内容包下载链接（http/https）");
        }

        try (Staged staged = stage(file)) {
            // 本地严格体检（与官网 package-meta.ts / upload.post.ts 同口径）：不合法在这里就结束，一字节不出网
            staged.inspect();

            return textJson(forwardMultipart("POST", base + "/api/packs/upload",
                    publishParts(kind, url, title, description, source), staged,
                    file.getOriginalFilename(), file.getContentType()));
        }
    }

    /**
     * POST /api/center/publish-from-path（JSON body，需登录）— 从本地路径直接发布内容包。
     * body：{ filePath, storageKind, downloadUrl?, title?, description?, source?, exportRecordId? }；
     * center 可作查询参数（缺省 https://pickq.cn），与 multipart 发布一致。
     * <p>
     * 存在意义：本地发布中心导出的 .tiku 就在本机磁盘上，没必要让前端把 200MB 读进内存再经
     * HTTP 请求体回传一遍；后端直接读这个路径。除"文件来源"外，本条路径与 multipart 发布
     * <b>完全同一套逻辑</b>：
     * - 体检：同一个 {@link ContentPackageInspector#inspect(Path)}（.tiku 走 zip 中央目录随机读，
     *   只解压 package.json，不把图片读进内存），不合法直接 IllegalArgumentException，一字节不出网；
     * - 字段校验：同一个 storageKind / downloadUrl 口径；
     * - 转发：同一个手写 multipart（文件流式读 + setFixedLengthStreamingMode 定长流式写，大文件不进堆）；
     * - 校验登录：无 token 同样 IllegalStateException("请先登录题库广场账号")，不发远程请求。
     * <p>
     * 成功返回官网响应体原文；带 exportRecordId 时顺带把该导出记录标记为已发布
     * （版本取包内 version，官网登记的就是它）。
     */
    @PostMapping("/publish-from-path")
    public ResponseEntity<String> publishFromPath(@RequestParam(required = false) String center,
                                                  @RequestBody(required = false) PublishFromPathRequest request) {
        requireLogin();
        if (request == null) {
            throw new IllegalArgumentException("缺少请求体（需要 filePath）");
        }
        String base = checkBase(center);
        LocalPackage local = requireLocalPackage(request.filePath());
        String kind = normalizeStorageKind(request.storageKind());
        String url = checkDownloadUrl(request.downloadUrl());
        if ("EXTERNAL".equals(kind) && url.isEmpty()) {
            throw new IllegalArgumentException("外链方式需要提供内容包下载链接（http/https）");
        }

        try (Staged staged = stageFile(local.path(), local.size())) {
            // 与 multipart 发布同一套体检：格式/元数据不合法在这里就结束，一字节不出网
            ContentPackageInspector.Inspection meta = staged.inspect();
            String response = forwardMultipart("POST", base + "/api/packs/upload",
                    publishParts(kind, url, request.title(), request.description(), request.source()),
                    staged, local.path().getFileName().toString(), local.contentType());
            // 上传成功：顺便把导出记录标记为已发布（版本取包内 version，官网登记的就是它）。
            // 标记失败（记录已被删等）不影响"已经发布成功"这个事实：只记日志，响应仍按官网原文返回
            if (request.exportRecordId() != null && exportRecordService != null) {
                try {
                    exportRecordService.markPublished(request.exportRecordId(), meta.version());
                } catch (RuntimeException e) {
                    log.warn("发布成功但标记导出记录失败：recordId={}, {}", request.exportRecordId(), e.getMessage());
                }
            }
            return textJson(response);
        }
    }

    /** 本地内容包文件（发布来源）：绝对路径 + 字节数 + 按魔数判定的 Content-Type */
    private record LocalPackage(Path path, long size, String contentType) {
    }

    /**
     * 本地路径校验：必须存在、是文件、非空且 ≤200MB（与 multipart 上传同为官网 200MB 上限）。
     * 路径问题抛 IllegalArgumentException（400，用户可选个文件重试），读取失败抛 IllegalStateException（磁盘问题）。
     */
    private static LocalPackage requireLocalPackage(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("请提供要发布的内容包文件路径（filePath）");
        }
        Path path;
        try {
            path = Path.of(filePath.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("内容包文件路径不合法：" + filePath);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("内容包文件不存在：" + path);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("内容包文件路径是目录，不是文件：" + path);
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取内容包文件失败：" + e.getMessage());
        }
        if (size <= 0) {
            throw new IllegalArgumentException("内容包文件为空：" + path);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("内容包文件超过 200MB 上限");
        }
        // 按魔数判定 Content-Type（.tiku = zip / 其余按 JSON）：官网不靠它解析（认魔数与内容），
        // 与 multipart 发布保留浏览器上报类型同理，只是让中转请求头如实
        String contentType;
        try {
            contentType = PackageContainer.isZipContainer(path) ? "application/zip" : "application/json";
        } catch (IOException e) {
            contentType = "application/octet-stream";
        }
        return new LocalPackage(path, size, contentType);
    }

    /**
     * 发布表单字段（官网 upload.post.ts 读的就是这几个）：storageKind 始终显式送
     * （官网缺省是 EXTERNAL，本地缺省为 HOSTED），空值字段不送（覆盖值留空即不覆盖）。
     */
    private static List<TextPart> publishParts(String kind, String url, String title,
                                               String description, String source) {
        List<TextPart> parts = new ArrayList<>();
        parts.add(new TextPart("storageKind", kind));
        if (!url.isEmpty()) {
            parts.add(new TextPart("downloadUrl", url));
        }
        addIfPresent(parts, "title", title);
        addIfPresent(parts, "description", description);
        addIfPresent(parts, "source", source);
        return parts;
    }

    /** 空值字段不送（官网 field() 只认长度 > 0 的部件；description/source 属覆盖值，空即不覆盖） */
    private static void addIfPresent(List<TextPart> parts, String name, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(new TextPart(name, value.trim()));
        }
    }

    /**
     * GET /api/center/me/packs?center=&page=&size=（需登录）— 我的作品列表（透传原文）。
     * 官网默认 page=1 size=20，size 上限 50；返回 { records:[...含 versionCount], total, page, size }。
     */
    @GetMapping("/me/packs")
    public ResponseEntity<String> myPacks(
            @RequestParam(required = false) String center,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireLogin();
        String base = checkBase(center);
        StringBuilder url = new StringBuilder(base).append("/api/me/packs");
        if (page != null || size != null) {
            url.append('?');
            if (page != null) {
                url.append("page=").append(Math.max(1, page));
            }
            if (size != null) {
                if (page != null) {
                    url.append('&');
                }
                url.append("size=").append(size);
            }
        }
        return textJson(getText(url.toString()));
    }

    /** DELETE /api/center/packs/{packageKey}?center=（需登录）— 下架整个作品（官网置 REMOVED） */
    @DeleteMapping("/packs/{packageKey}")
    public ResponseEntity<String> removePack(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey) {
        requireLogin();
        String url = checkBase(center) + "/api/packs/" + encode(packageKey);
        return textJson(forwardJson("DELETE", url, null));
    }

    /**
     * PUT /api/center/packs/{packageKey}/{version}?center=（需登录）— 更新版本元数据。
     * body 原样透传（官网只接受 description / source / downloadUrl，
     * 标题与作者身份不可改；HOSTED 作品不能填外链）。
     */
    @PutMapping("/packs/{packageKey}/{version}")
    public ResponseEntity<String> updatePackMeta(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @PathVariable String version,
            @RequestBody(required = false) String body) {
        requireLogin();
        String url = checkBase(center) + "/api/packs/" + encode(packageKey) + "/" + encode(version);
        return textJson(forwardJson("PUT", url, body));
    }

    /**
     * PUT /api/center/packs/{packageKey}/{version}/file?center=（multipart，需登录）
     * — 为 HOSTED 登记补传托管文件（官网校验作者本人 + 文件大小/指纹/内嵌 packageKey、version 与登记一致）。
     *
     * 与发布路径一致：转发前先本地体检（同一套 {@link ContentPackageInspector} 口径、同一份 {@link Staged} 暂存，
     * 不重复读文件），格式/元数据不合法直接抛 IllegalArgumentException，一个字节都不发往公网；
     * 校验通过再转发。补传同样会把用户的文件跨境上传，格式错误要能在本机秒级发现。
     */
    @PutMapping(value = "/packs/{packageKey}/{version}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadPackFile(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @PathVariable String version,
            @RequestPart("file") MultipartFile file) {
        requireLogin();
        String url = checkBase(center) + "/api/packs/" + encode(packageKey) + "/" + encode(version) + "/file";
        checkFile(file);
        try (Staged staged = stage(file)) {
            // 本地严格体检（与 publish 同一套口径）：不合法在这里就结束，一字节不出网
            ContentPackageInspector.Inspection meta = staged.inspect();
            // 官网 file.put.ts 还会核对"文件内 packageKey/version 与登记一致"，本地先查可省掉一次 200MB 跨境传输
            checkRegisteredIdentity(meta, packageKey, version);
            return textJson(forwardMultipart("PUT", url, List.of(), staged,
                    file.getOriginalFilename(), file.getContentType()));
        }
    }

    /**
     * 补传文件与登记的轻量身份核对（官网 file.put.ts：文件内 packageKey/version 必须与登记一致，
     * 不一致时官网返回 400「文件内 packageKey 与登记不一致」）。
     * 本地先核对是纯收益：官网同样会拒绝，不存在"本地拒绝、官网接受"的情况。
     */
    private static void checkRegisteredIdentity(ContentPackageInspector.Inspection meta,
                                                String packageKey, String version) {
        String expectedKey = packageKey == null ? "" : packageKey.trim();
        String expectedVersion = version == null ? "" : version.trim();
        if (!expectedKey.equals(meta.packageKey())) {
            throw new IllegalArgumentException(
                    "文件内 packageKey 与登记不一致（文件 " + meta.packageKey() + "，登记 " + expectedKey + "）");
        }
        if (!expectedVersion.equals(meta.version())) {
            throw new IllegalArgumentException(
                    "文件内 version 与登记不一致（文件 " + meta.version() + "，登记 " + expectedVersion + "）");
        }
    }
}
