package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ApiResponse;
import com.tiku.dto.ImportResultResponse;
import com.tiku.service.CenterAuthStore;
import com.tiku.service.CenterBrowseService;
import com.tiku.service.CenterHttpClient;
import com.tiku.service.CenterUrlPolicy;
import com.tiku.service.ContentPackageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库广场浏览和互动的 HTTP 边界。
 * 远端请求、下载上限和本地内容包导入均由 {@link CenterBrowseService} 处理。
 */
@RestController
@RequestMapping("/api/center")
public class CenterProxyController {

    private final CenterBrowseService browseService;

    @Autowired
    public CenterProxyController(CenterBrowseService browseService) {
        this.browseService = browseService;
    }

    /** 保留给既有独立测试和手工构造使用的便捷构造。 */
    public CenterProxyController(ContentPackageService contentPackageService, CenterAuthStore authStore) {
        this(new CenterBrowseService(contentPackageService, new CenterUrlPolicy(),
                new CenterHttpClient(authStore, new ObjectMapper())));
    }

    @GetMapping("/packs")
    public ResponseEntity<String> listPacks(
            @RequestParam(required = false) String center,
            @RequestParam(defaultValue = "new") String sort,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return textJson(browseService.listPacks(center, sort, q, page, size));
    }

    @GetMapping("/packs/{packageKey}")
    public ResponseEntity<String> getPackDetail(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey) {
        return textJson(browseService.getPackDetail(center, packageKey));
    }

    @GetMapping("/packs/{packageKey}/comments")
    public ResponseEntity<String> getPackComments(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey) {
        return textJson(browseService.getPackComments(center, packageKey));
    }

    @GetMapping("/authors/{id}")
    public ResponseEntity<String> getAuthor(
            @RequestParam(required = false) String center,
            @PathVariable long id) {
        return textJson(browseService.getAuthor(center, id));
    }

    @PostMapping("/import")
    public ApiResponse<ImportResultResponse> importFromCenter(@RequestBody ImportFromCenterRequest request) {
        return ApiResponse.success(browseService.importFromCenter(request.center(), request.packageKey(), request.version()));
    }

    @PostMapping("/import-external")
    public ApiResponse<ImportResultResponse> importExternal(@RequestBody ImportExternalRequest request) {
        return ApiResponse.success(browseService.importExternal(request.url()));
    }

    @PostMapping("/packs/{packageKey}/favorite")
    public ResponseEntity<String> setFavorite(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @RequestBody(required = false) String body) {
        return forward("POST", center, "/api/packs/" + encode(packageKey) + "/favorite", body);
    }

    @PostMapping("/packs/{packageKey}/comments")
    public ResponseEntity<String> postComment(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @RequestBody(required = false) String body) {
        return forward("POST", center, "/api/packs/" + encode(packageKey) + "/comments", body);
    }

    @DeleteMapping("/packs/{packageKey}/comments/{commentId}")
    public ResponseEntity<String> deleteComment(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @PathVariable long commentId) {
        return forward("DELETE", center, "/api/packs/" + encode(packageKey) + "/comments/" + commentId, null);
    }

    @PostMapping("/packs/{packageKey}/comments/{commentId}/like")
    public ResponseEntity<String> likeComment(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @PathVariable long commentId,
            @RequestBody(required = false) String body) {
        return forward("POST", center,
                "/api/packs/" + encode(packageKey) + "/comments/" + commentId + "/like", body);
    }

    @PostMapping("/authors/{authorId}/follow")
    public ResponseEntity<String> followAuthor(
            @RequestParam(required = false) String center,
            @PathVariable long authorId,
            @RequestBody(required = false) String body) {
        return forward("POST", center, "/api/authors/" + authorId + "/follow", body);
    }

    private ResponseEntity<String> forward(String method, String center, String path, String body) {
        return textJson(browseService.forwardJson(method, center, path, body));
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ResponseEntity<String> textJson(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    public record ImportFromCenterRequest(String center, String packageKey, String version) {
    }

    public record ImportExternalRequest(String url) {
    }
}
