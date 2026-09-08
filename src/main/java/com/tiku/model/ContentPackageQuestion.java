package com.tiku.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 内容包文件中的题目结构。
 * 字段顺序固定，参与 checksum 规范化计算。
 */
@Data
@NoArgsConstructor
public class ContentPackageQuestion {

    /** 题目唯一标识（= 本地 question.external_id），同一内容包内唯一，跨版本保持不变 */
    private String questionKey;

    /** 册数（可选，本地字段，传播时保留） */
    private Integer volume;

    /** SINGLE / MULTIPLE / JUDGE */
    private String type;

    /** 题干 */
    private String content;

    /** 选项数组 */
    private List<OptionItem> options;

    /** 正确答案 key 数组 */
    private List<String> answerKeys;

    /** 答案文字 */
    private String answerText;

    /** 解析 */
    private String analysis;

    /** 主题 */
    private String topic;

    /** 分类 */
    private String category;

    /** 分值，默认 1（主观题默认 5） */
    private Double score;

    /**
     * 答案来源标记（AI 导入专用）：ORIGINAL=原文提供的答案 / AI_SUPPLEMENT=AI 补充。
     * 非 null 才参与序列化（保证内容包 checksum 对旧数据不变）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String answerSource;

    /** 主观题参考答案（文字 + 图片标记 [图片:文件名]，可空；SUBJECTIVE 专用） */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String referenceAnswer;

    /** 共享材料引用（内容包内 materialKey；导入后映射为本地 material_id） */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String materialKey;

    /** 题号（AI 导入后按源文题号回填；列表/做题按题号排序）。null 不参与序列化（checksum 兼容） */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer questionNumber;
}
