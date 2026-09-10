package com.tiku.dto;

/**
 * 导出到本地目录请求：POST /api/exports/export
 *
 * @param bankId  题库 ID（必填）
 * @param version 目标版本号（可选）：提供时覆盖内容包内的 version 字段，用于"以新版本发布"
 *                （口径与发布端体检一致：字母/数字/点/下划线/连字符，1-100 字符）
 * @param dir     目标目录（可选，需绝对路径）：缺省用上次记忆目录，再缺省用 文档\拾题；不存在则创建
 */
public record ExportToDirRequest(
        Long bankId,
        String version,
        String dir
) {
}
