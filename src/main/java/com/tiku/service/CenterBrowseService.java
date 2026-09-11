package com.tiku.service;

import com.tiku.dto.ImportResultResponse;
import com.tiku.util.PackageContainer;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 题库广场浏览、导入与登录后互动用例。 */
@Service
public class CenterBrowseService {

    private static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;

    private final ContentPackageService contentPackageService;
    private final CenterUrlPolicy urlPolicy;
    private final CenterHttpClient httpClient;

    public CenterBrowseService(ContentPackageService contentPackageService, CenterUrlPolicy urlPolicy,
                               CenterHttpClient httpClient) {
        this.contentPackageService = contentPackageService;
        this.urlPolicy = urlPolicy;
        this.httpClient = httpClient;
    }

    public String listPacks(String center, String sort, String query, int page, int size) {
        String base = urlPolicy.centerBase(center);
        String path = "/api/packs?sort=" + sort + "&page=" + page + "&size=" + size
                + (query != null && !query.isBlank() ? "&q=" + encode(query) : "");
        return httpClient.getText(urlPolicy.appendPath(base, path), CenterHttpClient.DEFAULT_READ_TIMEOUT_MS);
    }

    public String getPackDetail(String center, String packageKey) {
        return getText(center, "/api/packs/" + encode(packageKey));
    }

    public String getPackComments(String center, String packageKey) {
        return getText(center, "/api/packs/" + encode(packageKey) + "/comments");
    }

    public String getAuthor(String center, long authorId) {
        return getText(center, "/api/authors/" + authorId);
    }

    public ImportResultResponse importFromCenter(String center, String packageKey, String version) {
        String base = urlPolicy.centerBase(center);
        String path = "/api/packs/" + encode(packageKey) + "/" + encode(version) + "/file";
        return importContentBytes(httpClient.getBytes(urlPolicy.appendPath(base, path), 60_000, MAX_PACKAGE_BYTES));
    }

    public ImportResultResponse importExternal(String url) {
        String downloadUrl = urlPolicy.externalDownloadUrl(url);
        if (downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("下载链接需为 http(s) 链接");
        }
        return importContentBytes(httpClient.getBytes(downloadUrl, 60_000, MAX_PACKAGE_BYTES));
    }

    public String forwardJson(String method, String center, String path, String body) {
        String base = urlPolicy.centerBase(center);
        return httpClient.forwardJson(method, urlPolicy.appendPath(base, path), body,
                CenterHttpClient.DEFAULT_READ_TIMEOUT_MS, false);
    }

    private String getText(String center, String path) {
        String base = urlPolicy.centerBase(center);
        return httpClient.getText(urlPolicy.appendPath(base, path), CenterHttpClient.DEFAULT_READ_TIMEOUT_MS);
    }

    private ImportResultResponse importContentBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("广场未返回内容包文件");
        }
        if (PackageContainer.isZipContainer(bytes)) {
            return contentPackageService.importTikuPackage(bytes);
        }
        return contentPackageService.importContentPackage(new String(bytes, StandardCharsets.UTF_8));
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
