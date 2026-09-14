package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.QuestionSkill;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 题目标签（question_skill）。
 *
 * ⚠️ 这里**只保留写路径与两个 id 查询**：所有计数与清单都由
 * {@code QuestionTaggingService} 的同一份内存快照派生（见该类 snapshot 方法）。
 * 之前计数用一套 SQL、清单用另一套 SQL，用户实测看到过「26 待标注 vs 16 待确认」自相矛盾的数字——
 * 口径只允许有一个来源，所以不再往这个 Mapper 里加 count/列表 SQL。
 */
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

    /** 已有人工标注（用户本机修正 / 作者标注）的题目 id：这些题不再接受 AI 建议（人工优先） */
    @Select("SELECT DISTINCT question_id FROM question_skill WHERE bank_id = #{bankId} AND shadowed = 0 "
            + "AND source IN ('user', 'author')")
    List<Long> selectHumanTaggedQuestionIds(@Param("bankId") Long bankId);

    /**
     * 已有任意标签（含低置信 AI 建议）的题目 id：用于**分批续跑**。
     * 打标签可能跨多次请求完成（界面按 N 次调用一批推进、可中断），
     * 续跑时必须跳过已处理过的题，否则每次都会从头重算（白烧 token）。
     */
    @Select("SELECT DISTINCT question_id FROM question_skill WHERE bank_id = #{bankId} "
            + "AND template_id = #{templateId} AND shadowed = 0")
    List<Long> selectTaggedQuestionIds(@Param("bankId") Long bankId,
                                       @Param("templateId") String templateId);
}
