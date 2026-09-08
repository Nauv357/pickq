package com.tiku.dto;

import java.util.List;

public record PageResult<T>(
        List<T> records,    //数据列表
        Long total,         //总条数
        Long pageNum,       //当前页码
        Long pageSize,      //每页大小
        Long pages          //总页数（前端通常用这个判断是否最后一页）
) {
    // 静态工厂：将 MyBatis-Plus 的 IPage 安全转换为标准 POJO
    public static <T> PageResult<T> from(com.baomidou.mybatisplus.core.metadata.IPage<T> page) {
        return new PageResult<>(
                page.getRecords(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                page.getPages()
        );
    }

    // 空结果（筛选无匹配时）
    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), 0L, 1L, 0L, 0L);
    }
}
