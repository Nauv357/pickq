package com.tiku.dto;

import java.util.List;

/**
 * 一条笔记（我的；只存本机，不进内容包）。
 *
 * 笔记本身不属于任何题库/题目，`links` 是它关联到的地方（0..N）：
 * 界面按 links 显示"挂在哪"，一个都没关联就是"未归类"。
 *
 * @param source user 自己写的 / ai 从讲解存进来的
 */
public record NoteResponse(Long id, String content, String source, List<NoteLinkResponse> links,
                           java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
}
