package com.tiku.dto;

import com.tiku.model.OptionItem;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 草稿 AI 解析请求（编辑/录入面板：基于表单当前内容，题目可能尚未保存无 id） */
public record AnalysisDraftRequest(
        Long bankId,
        String questionTypeLabel,
        @NotBlank(message = "题干不能为空") String content,
        List<OptionItem> options,
        List<String> answerKeys,
        String answerText,
        String referenceAnswer,
        String materialContent
) {
}
