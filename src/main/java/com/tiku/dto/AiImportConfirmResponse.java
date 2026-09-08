package com.tiku.dto;

/**
 * AI 导入确认结果
 */
public record AiImportConfirmResponse(
        Long bankId,
        Integer importedCount
) {
}
