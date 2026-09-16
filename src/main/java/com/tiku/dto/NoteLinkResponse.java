package com.tiku.dto;

/**
 * 笔记的一条关联（挂到哪个题库 / 哪道题）。
 *
 * label 由服务端拼好（题库名 / 「题库名 · 第 N 题」），界面直接显示，
 * 不用为每条笔记再查一遍题库名与题号。
 *
 * @param type           bank 题库 / question 题目
 * @param targetId       题库 id 或题目 id
 * @param label          展示用文字
 * @param bankId         题目所在题库（type=bank 时等于 targetId）；供界面跳转
 * @param questionNumber 题号（type=bank 时为 null）
 */
public record NoteLinkResponse(String type, Long targetId, String label, Long bankId, Integer questionNumber) {
}
