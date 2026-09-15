package com.tiku.dto;

import java.time.LocalDateTime;

/**
 * 一条笔记（我的；只存本机，不进内容包）。
 *
 * @param questionNumber 关联题目的题号（题库级随手记为 null，界面据此显示"哪道题"）
 * @param source         user 自己写的 / ai 从讲解存进来的
 */
public record NoteResponse(Long id, Long bankId, Long questionId, Integer questionNumber,
                           String content, String source,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
}
