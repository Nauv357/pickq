package com.tiku.dto;

/**
 * 导出偏好（上次导出目录记忆）：GET /api/exports/prefs
 *
 * @param lastDir    上次使用的导出目录（未设置/配置损坏为 null）
 * @param defaultDir 默认导出目录：文档\拾题
 * @param dirExists  目录存在性：仅当 lastDir 有值时含 "lastDir" 键
 */
public record ExportPrefsResponse(
        String lastDir,
        String defaultDir,
        java.util.Map<String, Boolean> dirExists
) {
}
