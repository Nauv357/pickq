package com.tiku.dto;

/**
 * AI 私教（阶段 1）的一次请求：提示楼梯 / 答错即问 / 自由追问 / 整场复盘共用。
 *
 * @param bankId             题库
 * @param questionId         单题追问时的题目（复盘时不传）
 * @param practiceSessionId  来自哪场练习（提示楼梯与复盘都要，用来取"我这题怎么答的"）
 * @param templateId         技能模板（决定"这题属于哪个知识点"的说法，可不传）
 * @param kind               POST_REVIEW 表示整场复盘，其余按单题追问
 * @param level              提示级别：1 指方向 / 2 关键一步 / 3 完整解析
 * @param selfReason         答错即问的快捷三选：CARELESS / NO_KNOWLEDGE / NEVER_SEEN
 * @param selfNote           答错即问的一句补充（可选）
 * @param text               自由追问的内容（可为空 = 只按错因回应）
 */
public record TutorAskRequest(Long bankId, Long questionId, Long practiceSessionId, String templateId,
                              String kind, Integer level, String selfReason, String selfNote, String text) {
}
