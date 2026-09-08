package com.tiku.dto;

import com.tiku.model.StudyRecord;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 刷题记录项（列表展示用）
 */
public record StudyRecordResponse(
        Long id,
        Long bankId,
        Long questionId,
        String questionKey,
        List<String> selectedKeys,
        Boolean correct,
        String userAnswer,
        String selfGrade,
        LocalDateTime answeredAt
) {
    public static StudyRecordResponse fromEntity(StudyRecord record) {
        return new StudyRecordResponse(
                record.getId(),
                record.getBankId(),
                record.getQuestionId(),
                record.getQuestionKey(),
                record.getSelectedKeys() == null || record.getSelectedKeys().isBlank()
                        ? List.of()
                        : Arrays.stream(record.getSelectedKeys().split(",")).map(String::trim).toList(),
                record.getCorrect(),
                record.getUserAnswer(),
                record.getSelfGrade(),
                record.getAnsweredAt()
        );
    }
}
