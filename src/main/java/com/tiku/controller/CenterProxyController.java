package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.ImportResultResponse;
import com.tiku.service.ContentPackageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 题库广场（内容包中心）只读代理。
 * 桌面端「发现题库」页通过本控制器匿名浏览广场列表，并把中心托管的
 * 内容包"拉取即导入"（字节不经过浏览器）。
 * 中心地址由前端传入（设置页可配，默认 http://localhost:3000）；
 * 仅允许 http/https，服务器到服务器转发，无跨域问题。
 * 注：用 HttpURLConnection 而非 JDK HttpClient（RestClient 默认底层）——
 * 实测前者与 Nuxt dev server 兼容（后者连接被服务端立即断开）。
 */
@RestController
@RequestMapping("/api/center")
public class CenterProxyController {

    private final ContentPackageService contentPackageService;

    public CenterProxyController(ContentPackageService contentPackageService) {
        this.contentPackageService = contentPackageService;
    }

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

    /** GET 转发：返回响应体文本 */
    private String getText(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = stream == null ? ""
                    : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
            if (code >= 400) {
                throw new IllegalStateException("远程返回错误（HTTP " + code + "）："
                        + (body.length() > 200 ? body.substring(0, 200) : body));
            }
            return body;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法连接题库广场：" + e.getMessage());
        }
    }

    /** GET 转发：返回响应体字节（内容包文件：.tiku zip 或 v1 .json，原样读取） */
    private byte[] getBytes(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code >= 400) {
                InputStream err = conn.getErrorStream();
                String body = err == null ? ""
                        : new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))
                                .lines().collect(Collectors.joining("\n"));
                throw new IllegalStateException("远程返回错误（HTTP " + code + "）："
                        + (body.length() > 200 ? body.substring(0, 200) : body));
            }
            InputStream stream = conn.getInputStream();
            if (stream == null) {
                return new byte[0];
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = stream.read(buf)) != -1) {
                total += n;
                if (total > 512L * 1024 * 1024) {
                    throw new IllegalStateException("广场返回的内容包文件过大");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("无法连接题库广场：" + e.getMessage());
        }
    }

    /** 内容包字节 → 导入：PK 魔数 = .tiku zip 容器（v2），否则视为 v1 纯 JSON */
    private ImportResultResponse importContentBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("广场未返回内容包文件");
        }
        if (com.tiku.util.PackageContainer.isZipContainer(bytes)) {
            return contentPackageService.importTikuPackage(bytes);
        }
        return contentPackageService.importContentPackage(new String(bytes, StandardCharsets.UTF_8));
    }

    /** GET /api/center/packs?center=&sort=&q=&page=&size= — 广场作品列表（透传） */
    @GetMapping("/packs")
    public ResponseEntity<String> listPacks(
            @RequestParam(required = false) String center,
            @RequestParam(defaultValue = "new") String sort,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        String base = checkBase(center);
        String url = base + "/api/packs?sort=" + sort + "&page=" + page + "&size=" + size
                + (q != null && !q.isBlank() ? "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) : "");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(getText(url));
    }

    /**
     * GET /api/center/packs/{packageKey} — 作品详情（版本历史/衍生/作者摘要，透传）
     */
    @GetMapping("/packs/{packageKey}")
    public ResponseEntity<String> getPackDetail(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey) {
        String base = checkBase(center);
        String url = base + "/api/packs/" + URLEncoder.encode(packageKey, StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(getText(url));
    }

    /**
     * GET /api/center/packs/{packageKey}/comments — 作品评论（只读展示，透传）
     */
    @GetMapping("/packs/{packageKey}/comments")
    public ResponseEntity<String> getPackComments(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey) {
        String base = checkBase(center);
        String url = base + "/api/packs/" + URLEncoder.encode(packageKey, StandardCharsets.UTF_8) + "/comments";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(getText(url));
    }

    /**
     * GET /api/center/authors/{id} — 作者主页（简介/粉丝/作品，透传）
     */
    @GetMapping("/authors/{id}")
    public ResponseEntity<String> getAuthor(
            @RequestParam(required = false) String center,
            @PathVariable long id) {
        String base = checkBase(center);
        String url = base + "/api/authors/" + id;
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(getText(url));
    }

    /**
     * POST /api/center/import { center?, packageKey, version }
     * — 拉取中心托管内容包并直接导入本地（复用内容包导入管线，四态结果）。
     */
    @PostMapping("/import")
    public ApiResponse<ImportResultResponse> importFromCenter(@RequestBody ImportFromCenterRequest req) {
        String base = checkBase(req.center());
        String url = base + "/api/packs/" + URLEncoder.encode(req.packageKey(), StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(req.version(), StandardCharsets.UTF_8) + "/file";
        return ApiResponse.success(importContentBytes(getBytes(url)));
    }

    /**
     * POST /api/center/import-external { url }
     * — 拉取作者外链（EXTERNAL 作品）的内容包并直接导入本地。
     * 仅支持 http/https 直链；网盘等网页链接会导入失败（前端回退为浏览器下载）。
     */
    @PostMapping("/import-external")
    public ApiResponse<ImportResultResponse> importExternal(@RequestBody ImportExternalRequest req) {
        String url = req.url() == null ? "" : req.url().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("下载链接需为 http(s) 链接");
        }
        if (url.contains("@")) {
            throw new IllegalArgumentException("下载链接不合法");
        }
        return ApiResponse.success(importContentBytes(getBytes(url)));
    }

    /** 导入请求体 */
    public record ImportFromCenterRequest(String center, String packageKey, String version) {
    }

    /** 外链导入请求体 */
    public record ImportExternalRequest(String url) {
    }
}
