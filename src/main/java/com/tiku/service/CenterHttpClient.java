package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 题库广场的 HTTP 基础设施客户端。
 *
 * <p>统一处理会话令牌、连接/读取超时、远端错误格式和流式 multipart 写入。业务服务只描述
 * 要调用的接口和请求内容，不再直接操作 {@link HttpURLConnection}。</p>
 */
@Component
public class CenterHttpClient {

    public static final int CONNECT_TIMEOUT_MS = 8_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    public static final int PUBLISH_READ_TIMEOUT_MS = 300_000;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final CenterAuthStore authStore;
    private final ObjectMapper objectMapper;

    public CenterHttpClient(CenterAuthStore authStore, ObjectMapper objectMapper) {
        this.authStore = authStore;
        this.objectMapper = objectMapper;
    }

    /** 发起 JSON GET 并返回官网响应原文。 */
    public String getText(String url, int readTimeoutMs) {
        try {
            HttpURLConnection connection = open(url, "GET", readTimeoutMs);
            connection.setRequestProperty("Accept", "application/json");
            return readResponse(connection);
        } catch (IOException e) {
            throw connectionFailure(e);
        }
    }

    /** 下载有明确上限的二进制内容，超过上限时立即停止读取。 */
    public byte[] getBytes(String url, int readTimeoutMs, long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("下载大小上限必须大于零");
        }
        try {
            HttpURLConnection connection = open(url, "GET", readTimeoutMs);
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IllegalStateException(extractRemoteError(readText(connection.getErrorStream()), status));
            }
            try (InputStream input = connection.getInputStream();
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (input == null) {
                    return new byte[0];
                }
                byte[] buffer = new byte[8 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IllegalStateException("广场返回的内容包文件过大");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (IOException e) {
            throw connectionFailure(e);
        }
    }

    /** 原样转发 JSON 请求体，成功时返回官网响应原文。 */
    public String forwardJson(String method, String url, String jsonBody, int readTimeoutMs,
                              boolean desktopRequest) {
        try {
            HttpURLConnection connection = open(url, method, readTimeoutMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (desktopRequest) {
                connection.setRequestProperty("X-Desktop", "1");
            }
            if (jsonBody != null && !jsonBody.isBlank()) {
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }
            return readResponse(connection);
        } catch (IOException e) {
            throw connectionFailure(e);
        }
    }

    /**
     * 流式转发 multipart 文件。调用方负责提供可重复打开的输入流，并负责文件的生命周期。
     */
    public String forwardMultipart(String method, String url, List<MultipartTextPart> textParts,
                                   long fileSize, MultipartSource source, String originalFilename,
                                   String contentType) {
        MultipartSkeleton skeleton = buildSkeleton(textParts, asciiFilename(originalFilename),
                safeContentType(contentType));
        HttpURLConnection connection = null;
        try {
            connection = open(url, method, PUBLISH_READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + skeleton.boundary());
            connection.setFixedLengthStreamingMode(skeleton.totalLength(fileSize));
            try (OutputStream output = connection.getOutputStream();
                 InputStream input = source.open()) {
                output.write(skeleton.texts());
                output.write(skeleton.fileHeader());
                copy(input, output);
                output.write(skeleton.tail());
            }
            return readResponse(connection);
        } catch (IOException e) {
            String remoteError = earlyRemoteError(connection);
            throw new IllegalStateException(remoteError == null ? connectionFailure(e).getMessage() : remoteError);
        }
    }

    private HttpURLConnection open(String url, String method, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        attachAuth(connection);
        return connection;
    }

    private void attachAuth(HttpURLConnection connection) {
        String token = authStore == null ? null : authStore.token();
        if (token != null && !token.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
    }

    private String readResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        String body = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status >= 400) {
            throw new IllegalStateException(extractRemoteError(body, status));
        }
        return body;
    }

    private String earlyRemoteError(HttpURLConnection connection) {
        if (connection == null) {
            return null;
        }
        try {
            int status = connection.getResponseCode();
            if (status < 400) {
                return null;
            }
            return extractRemoteError(readText(connection.getErrorStream()), status);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private String extractRemoteError(String body, int status) {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                for (JsonNode candidate : List.of(root.path("message"), root.path("data").path("message"),
                        root.path("statusMessage"))) {
                    if (candidate.isTextual() && !candidate.asText().isBlank()) {
                        return candidate.asText();
                    }
                }
            } catch (IOException ignored) {
                // 非 JSON 错误体使用统一的 HTTP 状态提示，避免直接暴露远端 HTML。
            }
        }
        return "题库广场返回错误（HTTP " + status + "）";
    }

    private static String readText(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static IllegalStateException connectionFailure(IOException exception) {
        return new IllegalStateException("无法连接题库广场：" + exception.getMessage(), exception);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static String asciiFilename(String original) {
        String name = original == null ? "" : original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String extension = "";
        String stem = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1 && name.substring(dot + 1).matches("[A-Za-z0-9]{1,8}")) {
            extension = "." + name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            stem = name.substring(0, dot);
        }
        String safeStem = stem.replaceAll("[^A-Za-z0-9._-]", "");
        if (safeStem.isBlank()) {
            safeStem = "package";
        }
        return (safeStem.length() > 60 ? safeStem.substring(0, 60) : safeStem) + extension;
    }

    private static String safeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "application/octet-stream";
        }
        String contentType = raw.replaceAll("[\r\n]", "").trim();
        return contentType.isEmpty() ? "application/octet-stream" : contentType;
    }

    private static MultipartSkeleton buildSkeleton(List<MultipartTextPart> parts, String fileName,
                                                    String contentType) {
        String boundary = "----TikuDesktopBoundary" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder text = new StringBuilder();
        for (MultipartTextPart part : parts) {
            text.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(part.name()).append("\"\r\n\r\n")
                    .append(part.value()).append("\r\n");
        }
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        return new MultipartSkeleton(boundary, text.toString().getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8), tail.getBytes(StandardCharsets.UTF_8));
    }

    public record MultipartTextPart(String name, String value) {
    }

    @FunctionalInterface
    public interface MultipartSource {
        InputStream open() throws IOException;
    }

    private record MultipartSkeleton(String boundary, byte[] texts, byte[] fileHeader, byte[] tail) {
        long totalLength(long fileSize) {
            return texts.length + fileHeader.length + fileSize + tail.length;
        }
    }
}
