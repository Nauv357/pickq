package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.AiImportJob;
import org.apache.ibatis.annotations.Select;

public interface AiImportJobMapper extends BaseMapper<AiImportJob> {

    /**
     * 行级锁读取（confirm 幂等用）：串行化"读 confirmed → 导入 → 写 confirmed"，
     * 防止并发重复确认（双击/重放）把同一任务重复导入
     */
    @Select("SELECT * FROM ai_import_job WHERE id = #{id} FOR UPDATE")
    AiImportJob selectByIdForUpdate(Long id);
}
