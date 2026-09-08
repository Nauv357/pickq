package com.tiku.dto;


import com.tiku.model.QuestionBank;

import java.time.LocalDateTime;

public record QuestionBankDetailResponse(
        Long id,
        String name,
        String description,
        String packageKey,
        String version,
        Integer schemaVersion,
        Long authorId,
        String authorName,
        String source,
        String parentKey,
        LocalDateTime createdAt,
        Boolean reviewEnabled
) {
    public static QuestionBankDetailResponse fromEntity(QuestionBank questionBank) {
        return new QuestionBankDetailResponse(
                questionBank.getId(),
                questionBank.getName(),
                questionBank.getDescription(),
                questionBank.getPackageKey(),
                questionBank.getVersion(),
                questionBank.getSchemaVersion(),
                questionBank.getAuthorId(),
                questionBank.getAuthorName(),
                questionBank.getSource(),
                questionBank.getParentKey(),
                questionBank.getCreatedAt(),
                questionBank.getReviewEnabled()
        );
    }
}
