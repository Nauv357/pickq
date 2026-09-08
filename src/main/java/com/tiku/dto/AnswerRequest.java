package com.tiku.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AnswerRequest(
        @NotNull(message = "答案不能为空")
        List<String> selectedKeys
){

}
