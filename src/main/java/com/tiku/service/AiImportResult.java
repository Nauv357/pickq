package com.tiku.service;

import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;

import java.util.List;

/** AI 导入整理阶段的内部结果：题目及可选的共享材料。 */
public record AiImportResult(List<ContentPackageQuestion> questions, List<ContentPackageMaterial> materials) {

    public AiImportResult {
        questions = questions == null ? List.of() : questions;
        materials = materials == null ? List.of() : materials;
    }
}
