package com.tiku.dto;

import jakarta.validation.constraints.NotBlank;

public record QuestionBankCreateRequest(
        @NotBlank(message = "题库名不能为空")
        String name,

        String description
) {
}
