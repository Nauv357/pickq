package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.SkillNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/** 技能节点（skill_node）：受控词表，随技能模板整体替换。 */
public interface SkillNodeMapper extends BaseMapper<SkillNode> {

    /** 按模板清空节点（导入/更新模板时先清后写，保证与 JSON 完全一致） */
    @Delete("DELETE FROM skill_node WHERE template_id = #{templateId}")
    int deleteByTemplate(@Param("templateId") String templateId);
}
