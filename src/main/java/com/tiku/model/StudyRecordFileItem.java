package com.tiku.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 刷题记录文件中的单条记录（study-record-spec.md 格式）。
 * 通过 packageKey + packageVersion + questionKey 定位题目。
 */
@Data
@NoArgsConstructor
public class StudyRecordFileItem {

    /** 所属内容包身份（自建题库导出时为空串，导入时无法定位） */
    private String packageKey;

    /** 刷题时的内容包版本 */
    private String packageVersion;

    /** 题目业务键（= question.external_id） */
    private String questionKey;

    /** 用户提交的答案 */
    private List<String> selectedKeys;

    /** 是否答对（客观题判题结果；主观题为 null） */
    private Boolean isCorrect;

    /** 主观题用户作答文本 */
    private String userAnswer;

    /** 主观题自评 CORRECT / PARTIAL / WRONG（可空） */
    private String selfGrade;

    /** 作答时间（ISO 8601） */
    private LocalDateTime answeredAt;
}
