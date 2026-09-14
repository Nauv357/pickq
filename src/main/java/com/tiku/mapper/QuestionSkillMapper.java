package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.QuestionSkill;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 题目标签（question_skill）。 */
public interface QuestionSkillMapper extends BaseMapper<QuestionSkill> {

    /**
     * 清理引用了"已不在技能图里"的节点的 AI 建议（技能图更新后的兜底）。
     *
     * 注意：**不做**"每次分析前删光该题库 AI 建议"的全局删除——打标签会分批续跑
     * （172 题 ≈ 9 次调用），全局删除会把上一批的成果清掉，导致每次从头重算、永远没有进度。
     * 逐题替换旧建议由 {@code QuestionTaggingService} 在写入时按题精确处理。
     */
    @Delete("<script>DELETE FROM question_skill WHERE bank_id = #{bankId} AND template_id = #{templateId} "
            + "AND source = 'ai' AND confirmed = 0 AND node_id NOT IN "
            + "<foreach item='n' collection='nodeIds' open='(' separator=',' close=')'>#{n}</foreach></script>")
    int deleteAiSuggestionsWithUnknownNodes(@Param("bankId") Long bankId,
                                            @Param("templateId") String templateId,
                                            @Param("nodeIds") java.util.Collection<String> nodeIds);

    /** 题库内"有可用标签"的题目数（已确认，或 AI 高置信）——覆盖地图与门控口径一致 */
    @Select("SELECT COUNT(DISTINCT question_id) FROM question_skill WHERE bank_id = #{bankId} "
            + "AND template_id = #{templateId} AND shadowed = 0 "
            + "AND (confirmed = 1 OR (source = 'ai' AND confidence >= " + QuestionSkill.GATE_MIN_CONFIDENCE + "))")
    int countCoveredQuestions(@Param("bankId") Long bankId, @Param("templateId") String templateId);

    /** 已有人工标注（用户本机修正 / 作者标注）的题目 id：这些题不再接受 AI 建议（人工优先） */
    @Select("SELECT DISTINCT question_id FROM question_skill WHERE bank_id = #{bankId} AND shadowed = 0 "
            + "AND source IN ('user', 'author')")
    java.util.List<Long> selectHumanTaggedQuestionIds(@Param("bankId") Long bankId);

    /**
     * 已有任意标签（含低置信 AI 建议）的题目 id：用于**分批续跑**。
     * 打标签可能跨多次请求完成（界面按 N 次调用一批推进、可中断），
     * 续跑时必须跳过已处理过的题，否则每次都会从头重算（白烧 token）。
     */
    @Select("SELECT DISTINCT question_id FROM question_skill WHERE bank_id = #{bankId} "
            + "AND template_id = #{templateId} AND shadowed = 0")
    java.util.List<Long> selectTaggedQuestionIds(@Param("bankId") Long bankId,
                                                 @Param("templateId") String templateId);

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
