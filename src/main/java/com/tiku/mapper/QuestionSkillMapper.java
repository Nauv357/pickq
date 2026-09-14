package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.QuestionSkill;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 题目标签（question_skill）。 */
public interface QuestionSkillMapper extends BaseMapper<QuestionSkill> {

    /** 删除某题库在指定模板下的全部 AI 建议（重新分析前清理；已确认与用户标注保留） */
    @Delete("DELETE FROM question_skill WHERE bank_id = #{bankId} AND template_id = #{templateId} "
            + "AND source = 'ai' AND confirmed = 0")
    int deleteAiSuggestions(@Param("bankId") Long bankId, @Param("templateId") String templateId);

    /** 题库内"有可用标签"的题目数（已确认，或 AI 高置信）——覆盖地图与门控口径一致 */
    @Select("SELECT COUNT(DISTINCT question_id) FROM question_skill WHERE bank_id = #{bankId} "
            + "AND template_id = #{templateId} AND shadowed = 0 "
            + "AND (confirmed = 1 OR (source = 'ai' AND confidence >= " + QuestionSkill.GATE_MIN_CONFIDENCE + "))")
    int countCoveredQuestions(@Param("bankId") Long bankId, @Param("templateId") String templateId);

    /** 已被人工标注（用户本机修正 / 作者标注）的题目 id：这些题不再接受 AI 建议（人工优先） */
    @Select("SELECT DISTINCT question_id FROM question_skill WHERE bank_id = #{bankId} AND shadowed = 0 "
            + "AND source IN ('user', 'author')")
    java.util.List<Long> selectHumanTaggedQuestionIds(@Param("bankId") Long bankId);

    /** 题库内"已确认"标签覆盖的题目数（按题去重） */
    @Select("SELECT COUNT(DISTINCT question_id) FROM question_skill WHERE bank_id = #{bankId} "
            + "AND template_id = #{templateId} AND confirmed = 1 AND shadowed = 0")
    int countConfirmedQuestions(@Param("bankId") Long bankId, @Param("templateId") String templateId);

    /**
     * 题库内**没有任何可用标签**的题目数（未删除题）。
     * 口径与 {@link #countCoveredQuestions} 严格互补：covered + untagged = 总题数。
     * 用 SQL 现算而不是"处理过就算数"——模型可能漏答、返回无法解析、节点被丢弃，
     * 那些题必须**如实算作未标注**（否则用户会以为覆盖完整，正是"漏刷"的来源）。
     */
    @Select("SELECT COUNT(*) FROM question q WHERE q.bank_id = #{bankId} AND q.deleted = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM question_skill s WHERE s.question_id = q.id "
            + "AND s.template_id = #{templateId} AND s.shadowed = 0 "
            + "AND (s.confirmed = 1 OR (s.source = 'ai' AND s.confidence >= " + QuestionSkill.GATE_MIN_CONFIDENCE + ")))")
    int countQuestionsWithoutSkills(@Param("bankId") Long bankId, @Param("templateId") String templateId);

    /** 题库内在该节点下有可用标签的题目数（用于"证据是否充足"判断） */
    @Select("SELECT COUNT(DISTINCT question_id) FROM question_skill WHERE bank_id = #{bankId} "
            + "AND template_id = #{templateId} AND node_id = #{nodeId} AND shadowed = 0 "
            + "AND (confirmed = 1 OR (source = 'ai' AND confidence >= " + QuestionSkill.GATE_MIN_CONFIDENCE + "))")
    int countCoveredQuestionsInNode(@Param("bankId") Long bankId, @Param("templateId") String templateId,
                                    @Param("nodeId") String nodeId);
}
