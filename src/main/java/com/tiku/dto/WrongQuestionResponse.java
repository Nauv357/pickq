package com.tiku.dto;

import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 错题项：题目（做题格式，不含答案）+ 最近一次作答信息
 * 错题定义：最近一次作答"错误"——客观题判错，或主观题自评非 CORRECT（PARTIAL 视为错）。
 */
public record WrongQuestionResponse(
        Long questionId,
        QuestionType questionType,
        String typeLabel,
        Integer questionNumber,
        String content,
        List<OptionItem> options,
        Double score,
        String category,
        String topic,
        List<String> selectedKeys,
        String userAnswer,
        String selfGrade,
        LocalDateTime lastAnsweredAt,
        Integer wrongCount
) {
    public static WrongQuestionResponse fromEntity(Question question, List<String> selectedKeys,
                                                   String userAnswer, String selfGrade,
                                                   LocalDateTime lastAnsweredAt, Integer wrongCount) {
        return new WrongQuestionResponse(
                question.getId(),
                question.getQuestionType(),
                question.getQuestionType().getLabel(),
                question.getQuestionNumber(),
                question.getContent(),
                question.getOptions(),
                question.getScore(),
                question.getCategory(),
                question.getTopic(),
                selectedKeys,
                userAnswer,
                selfGrade,
                lastAnsweredAt,
                wrongCount
        );
    }
}
