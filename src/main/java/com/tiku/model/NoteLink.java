package com.tiku.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记关联：这条笔记关联到哪里（题库 / 题目）。
 *
 * 为什么是独立表而不是笔记上的两个外键列：用户的要求是"笔记别硬绑在某道题上，
 * 但一定要能挂上去，一条笔记要能同时挂在多个题库、多道题上"——
 * 那是多对多，只能用关联表表达（一条笔记 0..N 个题库、0..N 道题）。
 *
 * 复合主键 (note_id, target_type, target_id)：重复挂同一处不会产生第二行（天然幂等）。
 * 因此这里**没有 `@TableId`**（MyBatis-Plus 启动时会对本类打一条 "Can not find table primary key" 警告，
 * 属预期：本表只用 `insert/delete/selectList`，从不按单列主键 `xxxById`）。
 * 笔记本身没有"主人题库"字段，所以题库/题目被删时这里只删关联行，笔记内容保留。
 */
@Data
@NoArgsConstructor
@TableName("note_link")
public class NoteLink {

    public static final String TYPE_BANK = "bank";
    public static final String TYPE_QUESTION = "question";

    @TableField("note_id")
    private Long noteId;

    /** bank 题库 / question 题目 */
    @TableField("target_type")
    private String targetType;

    @TableField("target_id")
    private Long targetId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public static NoteLink of(Long noteId, String targetType, Long targetId) {
        NoteLink link = new NoteLink();
        link.setNoteId(noteId);
        link.setTargetType(targetType);
        link.setTargetId(targetId);
        link.setCreatedAt(LocalDateTime.now());
        return link;
    }
}
