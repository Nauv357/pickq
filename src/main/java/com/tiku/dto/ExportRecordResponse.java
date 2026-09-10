package com.tiku.dto;

import java.time.LocalDateTime;

/**
 * 导出记录（本地发布中心）。
 * GET /api/exports、POST /api/exports/export、POST /api/exports/{id}/mark-published 共用。
 *
 * @param id               记录 ID
 * @param bankId           来源题库 ID
 * @param bankName         导出时的题库名
 * @param packageKey       导出内容包身份
 * @param version          导出内容包版本
 * @param filePath         落盘绝对路径
 * @param fileName         文件名
 * @param sizeBytes        文件字节数
 * @param published        是否已上传题库广场
 * @param publishedVersion 实际上传成功的版本（未发布为 null）
 * @param createdAt        导出时间
 * @param fileExists       磁盘文件当前是否还在（false = 用户已移动/删除，发布前需重新导出）
 */
public record ExportRecordResponse(
        Long id,
        Long bankId,
        String bankName,
        String packageKey,
        String version,
        String filePath,
        String fileName,
        long sizeBytes,
        boolean published,
        String publishedVersion,
        LocalDateTime createdAt,
        boolean fileExists
) {
}
