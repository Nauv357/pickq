package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.AiImportJob;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiImportJobMapper extends BaseMapper<AiImportJob> {

    /**
     * 行级锁读取（confirm 幂等用）：串行化"读 confirmed → 导入 → 写 confirmed"，
     * 防止并发重复确认（双击/重放）把同一任务重复导入
     */
    @Select("SELECT * FROM ai_import_job WHERE id = #{id} FOR UPDATE")
    AiImportJob selectByIdForUpdate(Long id);

    /**
     * 目标任务题库被删除时解除引用（bank_id = NULL 表示"不再指向某个已存在的题库"）。
     * 不删任务本身：导入记录属于历史，用户仍要在「AI 导入记录」里看到它。
     */
    @Update("UPDATE ai_import_job SET bank_id = NULL WHERE bank_id = #{bankId}")
    int clearBankId(Long bankId);
}
