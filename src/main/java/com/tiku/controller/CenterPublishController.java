package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ApiResponse;
import com.tiku.dto.PublishFromPathRequest;
import com.tiku.service.CenterAuthStore;
import com.tiku.service.CenterHttpClient;
import com.tiku.service.CenterPublishService;
import com.tiku.service.CenterUrlPolicy;
import com.tiku.service.ContentPackageInspector;
import com.tiku.service.ExportRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 题库广场的发布 HTTP 边界。
 *
 * <p>文件暂存、内容包体检、远端调用和发布记录联动均在 {@link CenterPublishService} 中完成；
 * 本类只负责请求绑定和统一的 JSON 响应映射。</p>
 */
@RestController
@RequestMapping("/api/center")
public class CenterPublishController {

    private final CenterPublishService publishService;

    @Autowired
    public CenterPublishController(CenterPublishService publishService) {
        this.publishService = publishService;
    }

    /** 保留给现有独立 HTTP 测试和手工构造使用的便捷构造。 */
    public CenterPublishController(CenterAuthStore authStore) {
        this(authStore, null);
    }

    /** 保留给现有独立 HTTP 测试和手工构造使用的便捷构造。 */
    public CenterPublishController(CenterAuthStore authStore, ExportRecordService exportRecordService) {
        this(new CenterPublishService(authStore, exportRecordService, new CenterUrlPolicy(),
                new CenterHttpClient(authStore, new ObjectMapper())));
    }

    @PostMapping(value = "/publish/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContentPackageInspector.Inspection> inspectPublishFile(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success(publishService.inspect(file));
    }

    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> publish(
            @RequestParam(required = false) String center,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String storageKind,
            @RequestParam(required = false) String downloadUrl,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String source) {
        return textJson(publishService.publish(center, file, storageKind, downloadUrl, title, description, source));
    }

    @PostMapping("/publish-from-path")
    public ResponseEntity<String> publishFromPath(@RequestParam(required = false) String center,
                                                  @RequestBody(required = false) PublishFromPathRequest request) {
        return textJson(publishService.publishFromPath(center, request));
    }

    @GetMapping("/me/packs")
    public ResponseEntity<String> myPacks(
            @RequestParam(required = false) String center,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return textJson(publishService.myPacks(center, page, size));
    }

    @DeleteMapping("/packs/{packageKey}")
    public ResponseEntity<String> removePack(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey) {
        return textJson(publishService.removePack(center, packageKey));
    }

    @PutMapping("/packs/{packageKey}/{version}")
    public ResponseEntity<String> updatePackMeta(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @PathVariable String version,
            @RequestBody(required = false) String body) {
        return textJson(publishService.updatePackMeta(center, packageKey, version, body));
    }

    @PutMapping(value = "/packs/{packageKey}/{version}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadPackFile(
            @RequestParam(required = false) String center,
            @PathVariable String packageKey,
            @PathVariable String version,
            @RequestPart("file") MultipartFile file) {
        return textJson(publishService.uploadPackFile(center, packageKey, version, file));
    }

    private static ResponseEntity<String> textJson(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
