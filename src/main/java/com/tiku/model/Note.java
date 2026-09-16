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
 * **笔记不隶属于任何题库或题目**：它只是一段内容 + 写作时间；关联到哪里由
 * {@link NoteLink} 决定（0..N 个题库、0..N 道题，可以一个都不挂）。
 * 题库/题目被删只会删掉关联，笔记本身留着（列表里显示为"未归类"）。
 */
@Data
@NoArgsConstructor
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String content;

    /**
     * 标记色（可空 = 不标色）：y 黄 / g 绿 / b 蓝 / p 粉。
     * 让用户在列表里一眼分出类别，不用读文字；正文里的行内高亮（==文字==）是另一件事。
     */
    private String color;

    /** user 自己写的 / ai 从讲解存进来的（界面上要能一眼看出来） */
    private String source;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_AI = "ai";

    /** 允许的标记色（与前端 utils/noteMarkup.js 的调色板一致） */
    public static final java.util.List<String> COLORS = java.util.List.of("y", "g", "b", "p");

    /** 归一化标记色：非法值一律当成"不标色"（不引入第三种状态） */
    public static String normalizeColor(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.trim().toLowerCase();
        return COLORS.contains(c) ? c : null;
    }
}
