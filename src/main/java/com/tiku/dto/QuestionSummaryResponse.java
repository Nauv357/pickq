package com.tiku.dto;

import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;


public record QuestionSummaryResponse(
        Long questionId,
        QuestionType questionType,
        String typeLabel,
        Integer questionNumber,
        String content,
        Double score,
        String category,
        String topic,
        Boolean favorite,
        String answerKeys,
        String analysis,
        String materialContent
) {
        public static QuestionSummaryResponse fromEntity(Question question) {
            return fromEntity(question, null);
        }

        public static QuestionSummaryResponse fromEntity(Question question, String materialContent) {
            return new QuestionSummaryResponse(
                    question.getId(),
                    question.getQuestionType(),
                    question.getQuestionType().getLabel(),
                    question.getQuestionNumber(),
                    question.getContent(),
                    question.getScore(),
                    question.getCategory(),
                    question.getTopic(),
                    question.getFavorite(),
                    question.getAnswerKeys(),
                    question.getAnalysis(),
                    materialContent
            );
        }
    }
