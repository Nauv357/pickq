package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.service.ImageStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 题目图片：上传返回引用文件名（content 内 [图片:文件名]），按名读取。
 */
@RestController
@RequestMapping("/api/banks/{bankId}/images")
public class ImageController {

    private final ImageStorageService imageStorageService;

    public ImageController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    /** 上传图片 → {name}（content 引用 [图片:name]） */
    @PostMapping
    public ApiResponse<Map<String, String>> upload(@PathVariable Long bankId,
                                                   @RequestParam("file") MultipartFile file) {
        String name = imageStorageService.upload(bankId, file);
        return ApiResponse.success(Map.of("name", name));
    }

    /** 读取图片（题目 content / 参考答案 / 材料内的 [图片:name] 渲染用；name = 日期目录/文件名） */
    @GetMapping("/{date}/{file:.+}")
    public ResponseEntity<byte[]> read(@PathVariable Long bankId,
                                       @PathVariable String date,
                                       @PathVariable String file) {
        String name = date + "/" + file;
        byte[] bytes = imageStorageService.read(bankId, name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, imageStorageService.contentType(name))
                .body(bytes);
    }
}
