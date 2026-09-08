package com.tiku.dto;

/**
 * 题库更新请求：null 字段不更新（部分更新）。
 * package_key / version / checksum / parent_key 为系统管理字段，不可通过本接口修改。
 */
public record QuestionBankUpdateRequest(
        String name,
        String description,
        String source,
        String authorName
) {
}
