package com.tiku.model;

import com.baomidou.mybatisplus.annotation.*;
import com.tiku.model.enums.QuestionType;
import com.tiku.model.handler.OptionItemTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@TableName(value = "question", autoResultMap = true)
public class Question {
    //数据库主键
    @TableId(type = IdType.AUTO)
    private Long id;

    //业务编号
    @TableField("external_id")
    private String externalId;

    //基础属性
    private Integer volume;     //第几册
    // 枚举的注解比较特殊：`@EnumValue` 要加在枚举类里，这里用 `@TableField` 标明即可
    @TableField("question_type")
    private QuestionType questionType;
    @TableField("question_number")
    private Integer questionNumber;
    private String content;

    // options 需要用 TypeHandler 处理 JSON
    @TableField(typeHandler = OptionItemTypeHandler.class)
    private List<OptionItem> options;

    @TableField("answer_keys")
    private String answerKeys;  //正确答案的 key ，如“B”或“ABC”
    @TableField("answer_text")
    private String answerText;
    private String topic;
    private String category;
    private Double score = 1.0;
    private String analysis;    //解析

    //共享材料引用（资料分析组内题；见 material 表）
    @TableField("material_id")
    private Long materialId;

    //主观题参考答案（文字 + 图片标记 [图片:文件名]，可空）
    @TableField("reference_answer")
    private String referenceAnswer;

    private String source;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @TableField("bank_id")
    private Long bankId;

    //收藏（做对也想二刷的题）
    private Boolean favorite;
}
