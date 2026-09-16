package com.tiku.dto;

/**
 * 给一条已有笔记加关联 / 去掉关联。
 *
 * @param type     bank 题库 / question 题目
 * @param targetId 题库 id 或题目 id
 */
public record NoteLinkRequest(String type, Long targetId) {
}
