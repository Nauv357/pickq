package com.tiku.service;

import com.tiku.dto.PublishFromPathRequest;
import com.tiku.util.PackageContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 题库广场发布与作者管理用例。
 *
 * <p>该服务集中“本地体检 → 流式转发 → 发布记录联动”的编排，Controller 不再处理临时文件、
 * multipart 协议或远端 HTTP 细节。</p>
 */
@Service
public class CenterPublishService {

    private static final Logger log = LoggerFactory.getLogger(CenterPublishService.class);
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    private static final long SPOOL_THRESHOLD_BYTES = 4L * 1024 * 1024;

    private final CenterAuthStore authStore;
    private final ExportRecordService exportRecordService;
    private final CenterUrlPolicy urlPolicy;
    private final CenterHttpClient httpClient;

    public CenterPublishService(CenterAuthStore authStore, ExportRecordService exportRecordService,
                                CenterUrlPolicy urlPolicy, CenterHttpClient httpClient) {
        this.authStore = authStore;
        this.exportRecordService = exportRecordService;
        this.urlPolicy = urlPolicy;
        this.httpClient = httpClient;
    }

    public ContentPackageInspector.Inspection inspect(MultipartFile file) {
        requireLogin();
        checkFile(file);
        try (Staged staged = stage(file)) {
            return staged.inspect();
        }
    }

    public String publish(String center, MultipartFile file, String storageKind, String downloadUrl,
                          String title, String description, String source) {
        requireLogin();
        checkFile(file);
        String base = urlPolicy.centerBase(center);
        String kind = normalizeStorageKind(storageKind);
        String externalUrl = urlPolicy.externalDownloadUrl(downloadUrl);
        requireExternalUrl(kind, externalUrl);

        try (Staged staged = stage(file)) {
            staged.inspect();
            return httpClient.forwardMultipart("POST", urlPolicy.appendPath(base, "/api/packs/upload"),
                    publishParts(kind, externalUrl, title, description, source), staged.size(), staged::open,
                    file.getOriginalFilename(), file.getContentType());
        }
    }

    public String publishFromPath(String center, PublishFromPathRequest request) {
        requireLogin();
        if (request == null) {
            throw new IllegalArgumentException("缺少请求体（需要 filePath）");
        }
        String base = urlPolicy.centerBase(center);
        LocalPackage local = requireLocalPackage(request.filePath());
        String kind = normalizeStorageKind(request.storageKind());
        String externalUrl = urlPolicy.externalDownloadUrl(request.downloadUrl());
        requireExternalUrl(kind, externalUrl);

        try (Staged staged = stageFile(local.path(), local.size())) {
            ContentPackageInspector.Inspection meta = staged.inspect();
            String response = httpClient.forwardMultipart("POST", urlPolicy.appendPath(base, "/api/packs/upload"),
                    publishParts(kind, externalUrl, request.title(), request.description(), request.source()),
                    staged.size(), staged::open, local.path().getFileName().toString(), local.contentType());
            markExportRecordPublished(request.exportRecordId(), meta.version());
            return response;
        }
    }

    public String myPacks(String center, Integer page, Integer size) {
        requireLogin();
        StringBuilder path = new StringBuilder("/api/me/packs");
        if (page != null || size != null) {
            path.append('?');
            if (page != null) {
                path.append("page=").append(Math.max(1, page));
            }
            if (size != null) {
                if (page != null) {
                    path.append('&');
                }
                path.append("size=").append(size);
            }
        }
        String base = urlPolicy.centerBase(center);
        return httpClient.getText(urlPolicy.appendPath(base, path.toString()), CenterHttpClient.PUBLISH_READ_TIMEOUT_MS);
    }

    public String removePack(String center, String packageKey) {
        requireLogin();
        String base = urlPolicy.centerBase(center);
        String path = "/api/packs/" + encode(packageKey);
        return httpClient.forwardJson("DELETE", urlPolicy.appendPath(base, path), null,
                CenterHttpClient.PUBLISH_READ_TIMEOUT_MS, false);
    }

    public String updatePackMeta(String center, String packageKey, String version, String body) {
        requireLogin();
        String base = urlPolicy.centerBase(center);
        String path = "/api/packs/" + encode(packageKey) + "/" + encode(version);
        return httpClient.forwardJson("PUT", urlPolicy.appendPath(base, path), body,
                CenterHttpClient.PUBLISH_READ_TIMEOUT_MS, false);
    }

    public String uploadPackFile(String center, String packageKey, String version, MultipartFile file) {
        requireLogin();
        checkFile(file);
        String base = urlPolicy.centerBase(center);
        String path = "/api/packs/" + encode(packageKey) + "/" + encode(version) + "/file";
        try (Staged staged = stage(file)) {
            ContentPackageInspector.Inspection meta = staged.inspect();
            checkRegisteredIdentity(meta, packageKey, version);
            return httpClient.forwardMultipart("PUT", urlPolicy.appendPath(base, path), List.of(), staged.size(),
                    staged::open, file.getOriginalFilename(), file.getContentType());
        }
    }

    private void requireLogin() {
        if (authStore == null || !authStore.isLoggedIn()) {
            throw new IllegalStateException("请先登录题库广场账号");
        }
    }

    private static void requireExternalUrl(String kind, String url) {
        if ("EXTERNAL".equals(kind) && url.isEmpty()) {
            throw new IllegalArgumentException("外链方式需要提供内容包下载链接（http/https）");
        }
    }

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

    private static void checkFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的内容包文件（.tiku 或 .json）");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("内容包文件超过 200MB 上限");
        }
    }

    private static Staged stage(MultipartFile file) {
        try {
            if (file.getSize() > SPOOL_THRESHOLD_BYTES) {
                Path tempFile = Files.createTempFile("tiku-center-upload-", ".part");
                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Files.deleteIfExists(tempFile);
                    throw e;
                }
                return new Staged(null, tempFile, Files.size(tempFile), true);
            }
            byte[] bytes = file.getBytes();
            return new Staged(bytes, null, bytes.length, true);
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage(), e);
        }
    }

    private static Staged stageFile(Path file, long size) {
        return new Staged(null, file, size, false);
    }

    private static LocalPackage requireLocalPackage(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("请提供要发布的内容包文件路径（filePath）");
        }
        final Path path;
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
        final long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取内容包文件失败：" + e.getMessage(), e);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("内容包文件为空：" + path);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("内容包文件超过 200MB 上限");
        }
        String contentType;
        try {
            contentType = PackageContainer.isZipContainer(path) ? "application/zip" : "application/json";
        } catch (IOException e) {
            contentType = "application/octet-stream";
        }
        return new LocalPackage(path, size, contentType);
    }

    private static List<CenterHttpClient.MultipartTextPart> publishParts(String kind, String url, String title,
                                                                           String description, String source) {
        List<CenterHttpClient.MultipartTextPart> parts = new ArrayList<>();
        parts.add(new CenterHttpClient.MultipartTextPart("storageKind", kind));
        if (!url.isEmpty()) {
            parts.add(new CenterHttpClient.MultipartTextPart("downloadUrl", url));
        }
        addIfPresent(parts, "title", title);
        addIfPresent(parts, "description", description);
        addIfPresent(parts, "source", source);
        return parts;
    }

    private static void addIfPresent(List<CenterHttpClient.MultipartTextPart> parts, String name, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(new CenterHttpClient.MultipartTextPart(name, value.trim()));
        }
    }

    private void markExportRecordPublished(Long recordId, String version) {
        if (recordId == null || exportRecordService == null) {
            return;
        }
        try {
            exportRecordService.markPublished(recordId, version);
        } catch (RuntimeException e) {
            log.warn("发布成功但标记导出记录失败：recordId={}, {}", recordId, e.getMessage());
        }
    }

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

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
    }

    private record LocalPackage(Path path, long size, String contentType) {
    }

    private record Staged(byte[] bytes, Path tempFile, long size, boolean owned) implements AutoCloseable {
        InputStream open() throws IOException {
            return bytes != null ? new ByteArrayInputStream(bytes) : Files.newInputStream(tempFile);
        }

        ContentPackageInspector.Inspection inspect() {
            return bytes != null ? ContentPackageInspector.inspect(bytes) : ContentPackageInspector.inspect(tempFile);
        }

        @Override
        public void close() {
            if (tempFile != null && owned) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // 系统临时目录会在后续回收，不能掩盖已完成的发布结果。
                }
            }
        }
    }
}
