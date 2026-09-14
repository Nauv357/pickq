package com.tiku.dto;

import java.util.List;

/**
 * 知识点标签的操作请求（学习路径引擎阶段 0）。
 *
 * @param action      confirm（确认建议）/ set（把这些题的标签设定为 newNodes，人的判定优先）
 *                    / retag（按节点批量改挂）/ reject（丢弃建议）
 * @param nodeId      目标节点：confirm / reject 只用它来批处理该节点下的题；retag 用它反查题目
 * @param newNodes    set / retag 要设定的节点列表（空数组 = 清空这题的标签）
 * @param questionIds 只作用于这些题（就地改标签、批量设为知识点都走这里；set 不传则不做任何事，
 *                    危险动作必须显式给题）
 * @param templateId  技能模板
 */
public record SkillApplyRequest(String action, String nodeId, List<String> newNodes,
                               List<Long> questionIds, String templateId) {
}
