package com.tiku.dto;

/**
 * 从本地路径直接发布：POST /api/center/publish-from-path
 * <p>
 * 本地发布中心导出的 .tiku 就在本机磁盘上，没必要经前端读进内存再走 HTTP 请求体回传一遍，
 * 后端直接读该路径转发官网（字段与 multipart 发布完全一致）。
 *
 * @param filePath       内容包文件绝对路径（.tiku 或 .json，≤200MB）
 * @param storageKind    托管方式：HOSTED（缺省）/ EXTERNAL
 * @param downloadUrl    EXTERNAL 必填：内容包下载直链（http/https）
 * @param title          发布标题（空则沿用包内标题）
 * @param description    作品描述（空则不覆盖）
 * @param source         来源声明（空则不覆盖）
 * @param exportRecordId 可选：对应的导出记录 ID，上传成功后就地标记为已发布
 */
public record PublishFromPathRequest(
        String filePath,
        String storageKind,
        String downloadUrl,
        String title,
        String description,
        String source,
        Long exportRecordId
) {
}
