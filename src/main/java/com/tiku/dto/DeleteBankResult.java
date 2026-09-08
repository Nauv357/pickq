package com.tiku.dto;

/**
 * 删除题库结果：级联删除的题目数 + 受影响的刷题记录数
 */
public record DeleteBankResult(
        Long deletedQuestions,
        Long affectedRecords
) {
}
