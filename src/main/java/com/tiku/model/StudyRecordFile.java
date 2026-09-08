package com.tiku.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 刷题记录文件结构（study-record-spec.md 格式 v1）。
 * 用于本地备份与换设备迁移；与内容包文件一样，不参与云端存储。
 */
@Data
@NoArgsConstructor
public class StudyRecordFile {

    /** 文件格式版本 */
    private Integer schemaVersion;

    /** 导出时间 */
    private LocalDateTime exportedAt;

    /** 记录数组 */
    private List<StudyRecordFileItem> records;
}
