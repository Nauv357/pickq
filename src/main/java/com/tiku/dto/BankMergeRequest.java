package com.tiku.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 题库合并请求：POST /api/banks/merge */
public record BankMergeRequest(
        @NotBlank(message = "新题库名称不能为空")
        String name,

        String description,

        @NotEmpty(message = "请至少选择两个题库")
        @Size(min = 2, message = "请至少选择两个题库进行合并")
        List<Long> sourceBankIds
) {
}
