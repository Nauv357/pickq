package com.tiku.dto;

/**
 * 新建 / 更新笔记。
 *
 * @param questionId 关联题目（可空 = 题库级随手记）
 * @param content    正文（纯文本，上限 2000 字）
 * @param source     user（自己写的，默认）/ ai（从讲解一键存进来的）
 */
public record NoteRequest(Long questionId, String content, String source) {
}
