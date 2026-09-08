package com.tiku.dto;

import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;

import java.util.List;

/**
 * 做题用题目数据：包含选项供用户作答，
 * 但不含 answerKeys / answerText / analysis（避免做题时剧透答案与解析）。
 * 主观题（SUBJECTIVE）：referenceAnswer 随题返回（无自动判题，提交后由前端展示参考答案供自评）。
 * 组内题（资料分析）：materialId + materialContent 返回共享大题干。
 */
public record QuestionPracticeResponse(
        Long questionId,
        QuestionType questionType,
        String typeLabel,
        Integer questionNumber,
        String content,
        List<OptionItem> options,
        Double score,
        String category,
        String topic,
        Boolean favorite,
        Long materialId,
        String materialContent,
        String referenceAnswer
) {
    public static QuestionPracticeResponse fromEntity(Question question, String materialContent) {
        return new QuestionPracticeResponse(
                question.getId(),
                question.getQuestionType(),
                question.getQuestionType().getLabel(),
                question.getQuestionNumber(),
                question.getContent(),
                question.getOptions(),
                question.getScore(),
                question.getCategory(),
                question.getTopic(),
                question.getFavorite(),
                question.getMaterialId(),
                materialContent,
                question.getReferenceAnswer()
        );
    }
}
