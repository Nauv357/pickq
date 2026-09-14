package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.SkillGroupMap;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 分组映射缓存（skill_group_map）。 */
public interface SkillGroupMapMapper extends BaseMapper<SkillGroupMap> {

    /** 取某模板下某分组的映射（含来源，调用方判断 graphVersion 是否过期） */
    @Select("SELECT * FROM skill_group_map WHERE template_id = #{templateId} AND group_key = #{groupKey} LIMIT 1")
    SkillGroupMap selectByGroup(@Param("templateId") String templateId, @Param("groupKey") String groupKey);

    /** 模板更新（graphVersion 变化）后清理旧映射：避免用旧图的结果标新图 */
    @Delete("DELETE FROM skill_group_map WHERE template_id = #{templateId} AND graph_version <> #{graphVersion}")
    int deleteStaleVersions(@Param("templateId") String templateId, @Param("graphVersion") String graphVersion);
}
