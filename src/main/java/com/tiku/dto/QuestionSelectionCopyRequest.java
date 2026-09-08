package com.tiku.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 选题另存 / 并入请求：POST /api/banks/{id}/questions/selection-copy
 * questionIds：源题库中勾选的题目；targetBankId 为空 = 新建题库（name 必填），
 * 非空 = 并入该题库（源库保留，题目/图片/关联材料复制）。
 */
public record QuestionSelectionCopyRequest(
        @NotEmpty(message = "请先勾选要复制的题目")
        List<Long> questionIds,
        String name,
        String description,
        Long targetBankId
) {
}
