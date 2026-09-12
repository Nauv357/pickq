package com.tiku.dto;


import com.tiku.model.QuestionBank;

import java.time.LocalDateTime;

/**
 * 题库列表项。
 *
 * @param questionCount 题库内题目数（列表页卡片显示「共 N 题」；批量查询后填充，单条查询可为 null）
 * @param answeredCount 已做过的题目数（去重计数，列表页卡片显示「已做 M」；无记录时为 0）
 */
public record QuestionBankResponse(
        Long id,
        String name,
        String description,
        String version,
        String authorName,
        String source,
        LocalDateTime createdAt,
        Long questionCount,
        Long answeredCount
) {
    public static QuestionBankResponse fromEntity(QuestionBank questionBank) {
        return fromEntity(questionBank, null, null);
    }

    public static QuestionBankResponse fromEntity(QuestionBank questionBank, Long questionCount, Long answeredCount) {
        return new QuestionBankResponse(
                questionBank.getId(),
                questionBank.getName(),
                questionBank.getDescription(),
                questionBank.getVersion(),
                questionBank.getAuthorName(),
                questionBank.getSource(),
                questionBank.getCreatedAt(),
                questionCount,
                answeredCount
        );
    }
}
