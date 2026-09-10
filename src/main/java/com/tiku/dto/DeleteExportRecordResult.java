package com.tiku.dto;

/**
 * 删除导出记录结果：DELETE /api/exports/{id}?deleteFile=
 *
 * @param id          被移除的记录 ID
 * @param fileDeleted 是否真的删掉了磁盘文件（deleteFile=false、文件已不存在、路径异常时均为 false）
 */
public record DeleteExportRecordResult(
        Long id,
        boolean fileDeleted
) {
}
