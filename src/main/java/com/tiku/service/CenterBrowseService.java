package com.tiku.service;

import com.tiku.dto.ImportResultResponse;
import com.tiku.util.PackageContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 题库广场浏览、导入与登录后互动用例。 */
@Slf4j
@Service
public class CenterBrowseService {

    private static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;
    /** 内容包下载的单次读超时：60s 对慢网/大文件偏紧（用户实测遇到下载超时），放宽到 3 分钟；
     *  这是"单次 read 无数据"的超时，正常下载中不会触发。 */
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 180_000;

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
        return downloadAndImport(urlPolicy.appendPath(base, path), "从广场下载");
    }

    public ImportResultResponse importExternal(String url) {
        String downloadUrl = urlPolicy.externalDownloadUrl(url);
        if (downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("下载链接需为 http(s) 链接");
        }
        return downloadAndImport(downloadUrl, "从作者外链下载");
    }

    /**
     * 下载内容包并导入。超时/连接失败要给出**能照做**的提示（用户实测反馈：下载超时后界面只报一句
     * 含糊错误，不知道是网速、文件太大还是站方问题，也不知道下一步能做什么）。
     */
    private ImportResultResponse downloadAndImport(String url, String what) {
        long startedAt = System.currentTimeMillis();
        byte[] bytes;
        try {
            bytes = httpClient.getBytes(url, DOWNLOAD_READ_TIMEOUT_MS, MAX_PACKAGE_BYTES);
        } catch (IllegalStateException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.contains("timed out") || detail.contains("Timeout") || detail.contains("Read timed out")) {
                throw new IllegalStateException(what + "超时（网络较慢或文件较大）：可稍后重试，"
                        + "或先用浏览器下载该文件，再在题库页点「导入」选择本地文件", e);
            }
            throw new IllegalStateException(what + "失败：" + detail
                    + "；也可以先用浏览器下载该文件，再在题库页点「导入」选择本地文件", e);
        }
        log.info("{}完成：{} 字节，用时 {} ms", what, bytes.length, System.currentTimeMillis() - startedAt);
        return importContentBytes(bytes);
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
