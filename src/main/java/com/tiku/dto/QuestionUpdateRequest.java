package com.tiku.dto;

import com.tiku.model.OptionItem;
import com.tiku.model.enums.QuestionType;
import jakarta.validation.constraints.Min;

import java.util.List;

public record QuestionUpdateRequest(

        Integer volume,

        QuestionType questionType,

        Integer questionNumber,

        String content,

        List<OptionItem> options,

        String topic,
        String category,

        @Min(value = 1, message = "分值必须大于0")
        Double score,

        List<@jakarta.validation.constraints.NotBlank String> answerKeys,

        String answerText,
        String analysis,

        //共享材料引用（资料分析组内题，null=不修改）
        Long materialId,

        //主观题参考答案（null=不修改；""=清空）
        String referenceAnswer
) {
}
