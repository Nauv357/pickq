package com.tiku.dto;

import java.util.List;

/**
 * 题库分类聚合（会话抽题的筛选选项，前端从题库题目去重提取改为后端聚合）
 */
public record BankCategoriesResponse(
        List<String> topics,
        List<String> categories
) {
}
