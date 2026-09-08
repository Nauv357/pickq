package com.tiku.dto;

import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public record QuestionDetailResponse(
        Long id,
        String externalId,
        Integer volume,
        QuestionType questionType,
        String typeLabel,
        Integer questionNumber,
        String content,
        List<OptionItem> options,
        String topic,
        String category,
        Double score,
        List<String> answerKeys,
        String answerText,
        String analysis,
        Boolean favorite,
        Long materialId,
        String referenceAnswer,
        String materialContent
){
    public static QuestionDetailResponse fromEntity(Question question, String materialContent) {
        String keys = question.getAnswerKeys();
        //按逗号 , 分割 keys，对每个分割后的子串执行 trim() 去除首尾空白，最后收集为一个列表（toList() 从 Java 16 开始返回不可变列表）
        List<String> answerKeyList = keys == null || keys.isBlank() ?
                List.of() :  Arrays.stream(keys.split(",")).map(String::trim).toList();

        return new QuestionDetailResponse(
                question.getId(),
                question.getExternalId(),
                question.getVolume(),
                question.getQuestionType(),
                question.getQuestionType().getLabel(),
                question.getQuestionNumber(),
                question.getContent(),
                question.getOptions(),
                question.getTopic(),
                question.getCategory(),
                question.getScore(),
                answerKeyList,
                question.getAnswerText(),
                question.getAnalysis(),
                question.getFavorite(),
                question.getMaterialId(),
                question.getReferenceAnswer(),
                materialContent
        );
    }
}
