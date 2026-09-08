package com.tiku.dto;

/**
 * 复习队列摘要（徽标与"开启复习计划"提示用）：
 * - dueTotal：当前到期总数（due_at <= now 且未暂停；含逾期积压）
 * - overdueTotal：其中"已逾期"的题数（到期日早于今天 00:00 的历史欠账）
 * 复习开关关闭时两者均为 0（队列暂停，不展示）。
 */
public record ReviewSummaryResponse(
        long dueTotal,
        long overdueTotal
) {
}
