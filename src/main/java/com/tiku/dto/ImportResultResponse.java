package com.tiku.dto;

/**
 * 内容包导入结果。
 * result 取值：
 * - CREATED           全新导入，新建题库
 * - ALREADY_IMPORTED  同 packageKey + 同 version + 同 checksum，已导入过
 * - VERSION_ADDED     同 packageKey 的新版本，并存导入（多版本共存）
 * - BRANCHED          同 version 但内容已被修改，分支导入（新 packageKey + parentKey）
 */
public record ImportResultResponse(
        String result,
        Long bankId,
        String message
) {
}
