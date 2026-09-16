package com.tiku.dto;

/**
 * 笔记列表的过滤条件（一处定义，列表与计数共用）。
 *
 * 收成一个 record 是因为过滤维度已经到 4 个（题库 / 题目 / 未归类 / 关键词 / 来源），
 * 再往下加就会变成一排 `Long, Long, boolean, String, String, int, int` —— 谁都能传错位置，
 * 而这类错误编译器不会提醒（见 docs/conventions.md §1.0 的同一条思路）。
 *
 * @param bankId     只看"这个题库的笔记"：挂在库上的 + 挂在这个库题目上的（可空）
 * @param questionId 只看关联到该题的笔记（可空）
 * @param unlinked   true = 只看"未归类"（一条关联都没有）
 * @param keyword    正文关键词（可空）
 * @param source     来源筛选：`user` 我写的 / `ai` 从讲解存的（可空 = 不筛）
 */
public record NoteQuery(Long bankId, Long questionId, boolean unlinked, String keyword, String source) {

    public static NoteQuery all() {
        return new NoteQuery(null, null, false, null, null);
    }

    /** 来源值归一化：只认 user / ai，其它一律不筛（不引入第三种状态） */
    public String normalizedSource() {
        if (source == null) {
            return null;
        }
        String s = source.trim().toLowerCase();
        return ("user".equals(s) || "ai".equals(s)) ? s : null;
    }
}
