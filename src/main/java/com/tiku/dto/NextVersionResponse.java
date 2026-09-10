package com.tiku.dto;

/**
 * 下一版本号建议：GET /api/exports/next-version?bankId=
 *
 * @param suggested     建议版本号：有已发布记录时 = 已发布版本补丁号 +1（1.0.0 → 1.0.1 / 1.2 → 1.2.1，
 *                      无法解析回退 1.0.0）；无已发布记录时 = 题库当前版本
 * @param lastPublished 该题库最近一次已发布的版本（无则为 null，供 UI 提示"尚未发布过"）
 */
public record NextVersionResponse(
        String suggested,
        String lastPublished
) {
}
