package com.tiku.dto;

import java.util.List;

/**
 * 刷题记录文件导入结果
 */
public record RecordImportResultResponse(
        int imported,        //成功导入条数
        int skippedDuplicates, //因与已有记录完全相同而跳过的条数（幂等，防重复导入翻倍）
        List<String> missingBanks,   //找不到对应题库（packageKey+version）而跳过的记录数统计（含包信息）
        List<String> missingQuestions //找不到题目的 questionKey
) {
}
