package com.tiku.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiku.model.enums.QuestionType;

import java.util.List;

/**
 * 创建刷题会话请求：POST /api/banks/{id}/sessions
 *
 * @param mode            ALL 未做优先 / PLAN 按配题结果（★"开始练习"的默认）/ SEQUENCE 顺序 /
 *                        TOPIC 按试卷章节 / SKILL 按知识点 / REVIEW 复习队列 / WRONG 错题 / FAVORITE 收藏
 * @param questionIds     mode=PLAN 时必填：直接练这几题（顺序即配题优先级，界面拿 practice-plan 的结果来）
 * @param topic           主题过滤（mode=TOPIC 时可选，**多选**，数组或单值均可）
 * @param category        分类过滤（mode=TOPIC 时可选，**多选**，数组或单值均可）
 * @param nodeIds         知识点过滤（mode=SKILL 时必填：按技能节点抽题）
 * @param count           抽取数量（缺省 = 范围内全部）
 * @param startQuestionId 起点题目（仅 mode=SEQUENCE：从该题按题号顺序往后做，缺省 = 从第一题开始）
 * @param keyword         题干/选项关键词过滤（题库列表筛选联动，行内 ▶ 顺序刷题用）
 * @param questionType    题型过滤（同上）
 * @param scope           范围过滤：all/favorite/wrong/undone（同上，默认不限）
 */
public record SessionCreateRequest(
        String mode,

        List<Long> questionIds,

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> topic,

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> category,

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> nodeIds,

        Integer count,

        Long startQuestionId,

        String keyword,

        QuestionType questionType,

        String scope
) {
}
