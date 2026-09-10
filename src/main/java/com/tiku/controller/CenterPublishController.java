package com.tiku.controller;

import com.tiku.service.CenterAuthStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * - POST   /api/packs/upload                       直传发布（multipart，服务端解析元数据自动登记）
 * - GET    /api/me/packs                           我的作品（含已下架，每作品最新一行 + 版本数）
 * - DELETE /api/packs/{packageKey}                 下架整个作品
 * - PUT    /api/packs/{packageKey}/{version}       更新版本元数据（描述/来源/外链）
 * - PUT    /api/packs/{packageKey}/{version}/file  补传托管文件（multipart）
 *
 * 与 {@link CenterProxyController}（只读浏览）同样用 HttpURLConnection 转发：
 * 上传走手写 multipart body + setFixedLengthStreamingMode 流式写出，200MB 内容包不进堆；
 * 已登录自动带 Authorization: Bearer（token 存 CenterAuthStore，前端不接触 token）；
 * 本地无 token 直接拒绝，不发远程请求——官网会话失效时官网返回 401，message 原样透传前端。
 */
@RestController
@RequestMapping("/api/center")
public class CenterPublishController {

    /** 内容包文件上限，与官网 upload.post.ts / file.put.ts 的 MAX_BYTES 一致 */
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    /** 超过该阈值先把上传内容落到临时文件，再流式转发（小文件直接留在内存，省一次磁盘往返） */
    private static final long SPOOL_THRESHOLD_BYTES = 4L * 1024 * 1024;
    private static final int COPY_BUFFER = 64 * 1024;
    /** 连接 8s；读取 300s（上传/发布可能较慢，含官网解包内容包与落盘的时间） */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 300_000;

    private final CenterAuthStore authStore;

    public CenterPublishController(CenterAuthStore authStore) {
        this.authStore = authStore;
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
     * - 大于 {@link #SPOOL_THRESHOLD_BYTES} 的上传内容先 Files.createTempFile 落盘，再边读边写请求体；
     *   setFixedLengthStreamingMode(总长度) 让 JDK 不做内存缓冲 → 200MB 只有 64KB 级的内存峰值；
     * - 临时文件在 finally 删除（官网响应读取完成后才清理）；
     * - 文本字段保留原字段名、值以 UTF-8 原样写出；文件字段名固定 file（官网读的是 file）。
     */
    private String forwardMultipart(String method, String url, List<TextPart> textParts, MultipartFile file) {
        MultipartSkeleton skeleton = buildSkeleton(textParts, asciiFilename(file.getOriginalFilename()),
                safeContentType(file.getContentType()));
        Path tempFile = null;
        HttpURLConnection conn = null;
        try {
            byte[] smallBytes = null;
            long fileSize;
            if (file.getSize() > SPOOL_THRESHOLD_BYTES) {
                tempFile = Files.createTempFile("tiku-center-upload-", ".part");
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                fileSize = Files.size(tempFile);
            } else {
                smallBytes = file.getBytes();
                fileSize = smallBytes.length;
            }

            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            attachAuth(conn);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + skeleton.boundary());
            conn.setFixedLengthStreamingMode(skeleton.totalLength(fileSize));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(skeleton.texts());
                os.write(skeleton.fileHeader());
                if (smallBytes != null) {
                    os.write(smallBytes);
                } else {
                    try (InputStream in = Files.newInputStream(tempFile)) {
                        copy(in, os);
                    }
                }
                os.write(skeleton.tail());
            }
            return readResponse(conn);
        } catch (IOException e) {
            // 大文件写到一半被官网提前拒绝（401/409/400）时只会看到 broken pipe 之类的 IO 异常，
            // 先尽力把官网已返回的 message 捞出来透传，捞不到才报连接失败
            String remote = earlyRemoteError(conn);
            throw new IllegalStateException(remote != null ? remote : "无法连接题库广场：" + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    /* 临时文件清理失败可忽略（系统临时目录会回收） */
                }
            }
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
     * POST /api/center/publish（multipart/form-data，需登录）— 应用内发布作品。
     * 表单字段：file（内容包 .tiku / .json，≤200MB）、storageKind（HOSTED 缺省 / EXTERNAL）、
     * downloadUrl（EXTERNAL 必填）、title、description、source；center 可作查询参数或表单字段。
     * 官网 upload.post.ts 读的字段正是 file / storageKind / downloadUrl / description / source
     * （title 会覆盖内容包内标题，留空则沿用包内标题）；
     * 注意官网 storageKind 缺省是 EXTERNAL，本地缺省为 HOSTED，故始终显式送该字段。
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

        List<TextPart> parts = new ArrayList<>();
        parts.add(new TextPart("storageKind", kind));
        if (!url.isEmpty()) {
            parts.add(new TextPart("downloadUrl", url));
        }
        addIfPresent(parts, "title", title);
        addIfPresent(parts, "description", description);
        addIfPresent(parts, "source", source);
        return textJson(forwardMultipart("POST", base + "/api/packs/upload", parts, file));
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
        return textJson(forwardMultipart("PUT", url, List.of(), file));
    }
}
