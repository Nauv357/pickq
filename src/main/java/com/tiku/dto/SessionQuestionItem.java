package com.tiku.dto;

import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;

import java.util.List;

/**
 * 会话详情中的题目项：做题格式（无答案）+ 该题作答结果（有作答时）。
 * 交卷后回顾场景展示答案与解析（answerKeys/answerText/analysis/referenceAnswer 仅在交卷后返回）。
 * 主观题（SUBJECTIVE）：无 answerKeys/answerText，参考答案在 referenceAnswer；自评结果在 selfGrade。
 * favorite 为实时值（每次查询从 question 表现取，反映详情页最新收藏，与做题页星标同步）。
 */
public record SessionQuestionItem(
        Long questionId,
        QuestionType questionType,
        String typeLabel,
        Integer questionNumber,
        String content,
        List<com.tiku.model.OptionItem> options,
        Double score,
        String category,
        String topic,
        Boolean favorite,
        List<String> selectedKeys,
        Boolean correct,
        Long recordId,
        Long seconds,             //本题用时（相邻作答时间差；未作答为 null）
        List<String> answerKeys,  //交卷后返回（回顾页展示）
        String answerText,
        String analysis,
        String userAnswer,        //主观题用户作答（始终返回）
        String selfGrade,         //主观题自评派生档 CORRECT/PARTIAL/WRONG；未自评为 null
        Double selfScore,         //主观题自评实得分（0~满分，自由给分；null=旧数据/未自评，按 selfGrade 映射）
        String referenceAnswer,   //主观题参考答案（交卷后返回）
        String materialContent    //共享材料大题干（组内题返回；其余为 null）
) {
    public static SessionQuestionItem fromEntity(Question question) {
        return new SessionQuestionItem(
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
                null, null, null, null, null, null, null, null, null, null, null, null
        );
    }
}
