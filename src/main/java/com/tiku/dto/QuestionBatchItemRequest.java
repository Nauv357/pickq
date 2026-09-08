package com.tiku.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.tiku.model.OptionItem;
import com.tiku.model.enums.QuestionType;

import java.util.List;

/**
 * 批量建题条目（不含 bankId，归属由路径参数决定）。
 */
public record QuestionBatchItemRequest(

        @NotNull(message = "册数不能为空")
        Integer volume,

        @NotNull(message = "题型不能为空")
        QuestionType questionType,

        Integer questionNumber,

        @NotBlank(message = "题干不能为空")
        String content,

        //SUBJECTIVE 无选项；其余题型由 service 校验非空
        List<OptionItem> options,

        String topic,
        String category,

        @NotNull(message = "分值不能为空")
        @Min(value = 1, message = "分值必须大于0")
        Double score,

        //SUBJECTIVE 无 answerKeys；其余题型由 service 校验非空
        List<@NotBlank String> answerKeys,

        String answerText,
        String analysis,

        Long materialId,

        String referenceAnswer
) {
}
