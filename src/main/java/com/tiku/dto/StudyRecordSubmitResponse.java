package com.tiku.dto;

import java.util.List;

/**
 * 作答提交结果：判题结论 + 刷题记录 id
 * 客观题：correct 为判题结果（题目未配置答案时为 null，note 提示后补）；
 * 主观题：correct 为 null（不自动判题，需用户自评）。
 */
public record StudyRecordSubmitResponse(
        Boolean correct,
        List<String> correctKeys,
        String correctText,
        String analysis,
        Long recordId,
        String note //可选提示（如"本题尚未配置答案"）；常规为 null
) {
}
