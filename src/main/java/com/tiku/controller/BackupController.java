package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 一键完整备份：GET /api/backup → tiku-backup-<时间戳>.zip
 * （H2 一致性快照 database.sql + images/ + ai-config.json + 恢复说明）。
 * 打包为流式响应，图片量大也不占内存；备份属耗时操作，前端请勿设短超时。
 * <p>
 * 一键恢复：POST /api/backup/restore-prepare（multipart file=备份 zip）→ 校验并解压到
 * {dataDir}/restore/staged，返回数据目录；桌面壳随后带 --tiku.restore-stage 重启后端，
 * 由 RestoreRunner 执行恢复（自动重启后完成）。
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    public ResponseEntity<StreamingResponseBody> downloadBackup() {
        String filename = "tiku-backup-" + LocalDateTime.now().format(TS) + ".zip";
        log.info("备份下载开始：{}", filename);
        StreamingResponseBody body = out -> {
            try {
                backupService.writeBackupZip(out);
            } catch (Exception e) {
                log.error("备份打包失败", e);
                throw e;
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /** 一键恢复·准备：上传备份 zip，校验并解压到暂存区；返回数据目录供壳重启恢复 */
    @PostMapping("/restore-prepare")
    public ApiResponse<Map<String, String>> prepareRestore(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择备份文件");
        }
        try {
            String dataDir = backupService.prepareRestore(file.getBytes());
            log.info("一键恢复准备完成：{}（{} 字节）", file.getOriginalFilename(), file.getSize());
            return ApiResponse.success(Map.of("dataDir", dataDir));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("备份文件处理失败：" + e.getMessage());
        }
    }
}
