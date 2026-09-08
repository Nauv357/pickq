package com.tiku.model;


import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName(value = "question_bank", autoResultMap = true)
public class QuestionBank {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    // ===== 内容包身份（合并模型：题库 = 内容包本地形态）=====

    //内容包稳定身份：自建题库为 null，导入时来自文件，导出时生成
    @TableField("package_key")
    private String packageKey;

    //内容包版本号（如 1.0.0）；自建题库为 null
    private String version;

    //内容包格式版本
    @TableField("schema_version")
    private Integer schemaVersion;

    //内容包文件指纹（题目内容 SHA-256），用于重复导入检测与修改判断
    private String checksum;

    //作者账号 ID（可空，本地不校验登录）
    @TableField("author_id")
    private Long authorId;

    //作者展示名
    @TableField("author_name")
    private String authorName;

    //来源声明（如"整理自公开教材"）
    private String source;

    //混编来源数组（JSON 文本）
    private String sources;

    //派生来源：分支导入时记录原内容包身份
    @TableField("parent_key")
    private String parentKey;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    //是否启用复习计划（默认关闭，用户显式开启；控制"今日待复习"队列）
    @TableField("review_enabled")
    private Boolean reviewEnabled;
}
