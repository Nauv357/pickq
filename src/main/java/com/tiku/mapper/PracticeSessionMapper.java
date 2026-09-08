package com.tiku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tiku.model.PracticeSession;
import org.apache.ibatis.annotations.Select;

public interface PracticeSessionMapper extends BaseMapper<PracticeSession> {

    /**
     * 行级锁读取（交卷用）：串行化"提交作答 + 标记完成"，防并发重复交卷/重复落库
     */
    @Select("SELECT * FROM practice_session WHERE id = #{id} FOR UPDATE")
    PracticeSession selectByIdForUpdate(Long id);
}
