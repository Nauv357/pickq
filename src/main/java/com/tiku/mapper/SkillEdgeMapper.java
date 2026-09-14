package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.SkillEdge;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/** 前置关系（skill_edge）。 */
public interface SkillEdgeMapper extends BaseMapper<SkillEdge> {

    @Delete("DELETE FROM skill_edge WHERE template_id = #{templateId}")
    int deleteByTemplate(@Param("templateId") String templateId);
}
