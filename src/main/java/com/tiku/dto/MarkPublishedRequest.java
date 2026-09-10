package com.tiku.dto;

/**
 * 标记导出记录已发布：POST /api/exports/{id}/mark-published
 *
 * @param version 实际上传成功的版本；留空则用该记录导出时的 version
 */
public record MarkPublishedRequest(
        String version
) {
}
