package com.tiku.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 提交作答（判题 + 记录）：POST /api/study-records
 * sessionId 可选：会话内提交时传入（记录挂到该会话，校验题目属于会话题库）
 * 客观题（SINGLE/MULTIPLE/JUDGE）：selectedKeys 必填
 * 主观题（SUBJECTIVE）：selectedKeys 忽略，userAnswer 必填（服务端按题型校验）
 */
public record StudyRecordSubmitRequest(
        @NotNull(message = "题目不能为空")
        Long questionId,

        List<String> selectedKeys,

        String userAnswer,

        Long sessionId
) {
}
