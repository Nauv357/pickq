package com.tiku.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiku.model.enums.QuestionType;

import java.util.List;

/**
 * 创建刷题会话请求：POST /api/banks/{id}/sessions
 *
 * @param mode            ALL 未做优先 / SEQUENCE 顺序 / TOPIC 按分类 / REVIEW 复习队列 / WRONG 错题 / FAVORITE 收藏
 * @param topic           主题过滤（mode=TOPIC 时可选，**多选**，数组或单值均可）
 * @param category        分类过滤（mode=TOPIC 时可选，**多选**，数组或单值均可）
 * @param count           抽取数量（缺省 = 范围内全部）
 * @param startQuestionId 起点题目（仅 mode=SEQUENCE：从该题按题号顺序往后做，缺省 = 从第一题开始）
 * @param keyword         题干/选项关键词过滤（题库列表筛选联动，行内 ▶ 顺序刷题用）
 * @param questionType    题型过滤（同上）
 * @param scope           范围过滤：all/favorite/wrong/undone（同上，默认不限）
 */
public record SessionCreateRequest(
        String mode,

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> topic,

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> category,

        Integer count,

        Long startQuestionId,

        String keyword,

        QuestionType questionType,

        String scope
) {
}
