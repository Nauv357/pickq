package com.tiku.dto;

/**
 * 更新导出偏好：PUT /api/exports/prefs
 *
 * @param lastDir 导出目录（需绝对路径、可创建）；留空表示清除记忆（下次回到默认目录）
 */
public record ExportPrefsRequest(
        String lastDir
) {
}
