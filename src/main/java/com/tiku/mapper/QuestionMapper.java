package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.Question;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 题目（question）。
 *
 * question 带 @TableLogic（deleted 列），BaseMapper 的 delete/select 只作用于 deleted=0 的行。
 * 下面两个注解 SQL 刻意绕开逻辑删除：
 * - 删题库时级联"物理"删除题目（V1 表注释即如此约定：「题库为物理删除（删除即级联删题删记录）」），
 *   否则会留下 bank_id 指向已不存在题库的孤儿行，且那些行的 (bank_id, external_id, deleted) 唯一键
 *   仍占位，导致该 bank_id 复用时同 questionKey 再次入库直接撞唯一键报错；
 * - 导入去重时需要看到"未被删除"的行里是否已有同 questionKey。
 */
public interface QuestionMapper extends BaseMapper<Question> {

    /** 物理删除某题库全部题目（含已软删除行）：删题库时级联清理 */
    @Delete("DELETE FROM question WHERE bank_id = #{bankId}")
    int deletePhysicallyByBankId(@Param("bankId") Long bankId);

    /**
     * 同题库内是否已有"未被删除"的同 questionKey 题目；有则返回其 id。
     * 用于导入幂等去重（同题库同 questionKey 已存在时跳过，而不是插入后撞唯一键）。
     */
    @Select("SELECT id FROM question WHERE bank_id = #{bankId} AND external_id = #{externalId} AND deleted = 0 LIMIT 1")
    Long selectActiveIdByBankAndExternalId(@Param("bankId") Long bankId, @Param("externalId") String externalId);
}
