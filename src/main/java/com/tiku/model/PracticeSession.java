package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 刷题会话（粉笔式：从题库选范围+数量组成单次刷题流程）。
 * 成绩不落库，从 study_record 实时聚合。
 */
@Data
@NoArgsConstructor
@TableName("practice_session")
public class PracticeSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("bank_id")
    private Long bankId;

    /** ALL 全部随机 / TOPIC 按分类 / REVIEW 复习队列到期题 / WRONG 错题 / FAVORITE 收藏 */
    private String mode;

    @TableField("scope_topic")
    private String scopeTopic;

    @TableField("scope_category")
    private String scopeCategory;

    @TableField("question_count")
    private Integer questionCount;

    /** 交卷时间（null = 进行中） */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
