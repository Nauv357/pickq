package com.tiku.dto;

import com.tiku.model.OptionItem;
import com.tiku.model.enums.QuestionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record QuestionCreateRequest(

        @NotNull(message = "册数不能为空")
        Integer volume,

        @NotNull(message = "题型不能为空")
        QuestionType questionType,

        Integer questionNumber,

        @NotBlank(message = "题干不能为空")
        String content,

        //SUBJECTIVE（主观题）无选项；其余题型由 service 校验非空
        List<OptionItem> options,

        String topic,
        String category,

        @NotNull(message = "分值不能为空")
        @Min(value = 1, message = "分值必须大于0")
        Double score,

        //SUBJECTIVE 无 answerKeys（答案 = 参考答案 referenceAnswer）；其余题型由 service 校验非空
        List<@NotBlank String> answerKeys,

        String answerText,
        String analysis,

        //共享材料引用（资料分析组内题，可空）
        Long materialId,

        //主观题参考答案（文字 + 图片标记 [图片:文件名]，可空）
        String referenceAnswer,

        @NotNull(message = "所属题库不能为空")
        Long bankId
){

}
