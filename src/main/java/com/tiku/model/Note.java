package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记（我的，不是题库的）。
 *
 * 与"解析"的边界：解析写进 `question.analysis`，**会随题库文件导出、会给别人看**；
 * 笔记只存本机、不进内容包，是"我自己的记忆钩子与体会"（AI 讲解也能一键存进来）。
 *
 * `question_id` 可空：既能挂在某道题上，也能只挂在题库上（没有具体题时的随手记）。
 */
@Data
@NoArgsConstructor
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("bank_id")
    private Long bankId;

    /** 关联题目（可空 = 题库级随手记） */
    @TableField("question_id")
    private Long questionId;

    private String content;

    /** user 自己写的 / ai 从讲解存进来的（界面上要能一眼看出来） */
    private String source;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_AI = "ai";
}
