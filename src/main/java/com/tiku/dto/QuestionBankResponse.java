package com.tiku.dto;


import com.tiku.model.QuestionBank;

import java.time.LocalDateTime;

public record QuestionBankResponse(
        Long id,
        String name,
        String description,
        String version,
        String authorName,
        String source,
        LocalDateTime createdAt
) {
    public static QuestionBankResponse fromEntity(QuestionBank questionBank) {
        return new QuestionBankResponse(
                questionBank.getId(),
                questionBank.getName(),
                questionBank.getDescription(),
                questionBank.getVersion(),
                questionBank.getAuthorName(),
                questionBank.getSource(),
                questionBank.getCreatedAt()
        );
    }
}
