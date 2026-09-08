package com.tiku.dto;

import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;

import java.util.List;

/**
 * AI 导入确认请求：
 * - bankId 为空 = 新建题库，否则追加到该题库
 * - questions/materials 可选：预览页编辑后的题目与材料（不传 = 用后端保存的 AI 结果导入）。
 *   编辑后的题目 content 仍使用 [图片N] 标记（与后端结果一致），导入时统一替换为正式文件名。
 */
public record AiImportConfirmRequest(
        Long bankId,
        List<ContentPackageQuestion> questions,
        List<ContentPackageMaterial> materials
) {
}
