package com.tiku.dto;

/**
 * 新建 / 更新笔记。
 *
 * 新建时可以顺手带上要关联的地方（都不是必填——"什么都还没挂"的随手记是合法状态）：
 * 给了 questionId 与 bankId 就是"这道题上记的、同时属于这个题库"；
 * 只给 bankId 就是挂在题库上的随手记。
 *
 * 更新（`PUT /api/notes/{id}`）时两个字段都是"给了才改"：只给 color 就是只标色、不动正文。
 *
 * @param bankId     关联题库（可空）
 * @param questionId 关联题目（可空）
 * @param content    正文（纯文本 + 轻量标注，上限 2000 字）
 * @param source     user（自己写的，默认）/ ai（从讲解一键存进来的）
 * @param color      标记色 y/g/b/p（可空 = 不标色）
 */
public record NoteRequest(Long bankId, Long questionId, String content, String source, String color) {

    /** 常用形态（不标色）：题库/题目关联 + 正文 + 来源 */
    public NoteRequest(Long bankId, Long questionId, String content, String source) {
        this(bankId, questionId, content, source, null);
    }
}
