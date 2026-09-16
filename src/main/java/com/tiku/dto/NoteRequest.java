package com.tiku.dto;

/**
 * 新建 / 更新笔记。
 *
 * 新建时可以顺手带上要关联的地方（都不是必填——"什么都还没挂"的随手记是合法状态）：
 * 给了 questionId 会自动把题所属题库也关联上（"我在这道题上记的"= 也属于这个库的笔记）；
 * 只给 bankId 就是挂在题库上的随手记。
 *
 * @param bankId     关联题库（可空）
 * @param questionId 关联题目（可空）
 * @param content    正文（纯文本，上限 2000 字）
 * @param source     user（自己写的，默认）/ ai（从讲解一键存进来的）
 */
public record NoteRequest(Long bankId, Long questionId, String content, String source) {
}
