package com.tiku.dto;

import java.util.List;

/**
 * 知识点标签的操作请求（学习路径引擎阶段 0）。
 *
 * @param action       confirm（确认建议）/ set（把这些题的标签设定为 newNodes，人的判定优先）
 *                     / retag（按节点批量改挂）/ reject（丢弃建议）
 * @param nodeId       目标节点：confirm / reject 只用它来批处理该节点下的题；retag 用它反查题目；
 *                     给了 filterStatus 时它表示"再按知识点筛一层"
 * @param newNodes     set / retag 要设定的节点列表（空数组 = 清空这题的标签）
 * @param questionIds  只作用于这些题（就地改标签、勾选批量都走这里）
 * @param templateId   技能模板
 * @param filterStatus 没给 questionIds 时用它（+ nodeId）在**后端**解析作用范围，取值同审阅清单：
 *                     all / confirmed / pending / untagged。界面上的"全选 N 题"走这条路，
 *                     不必把上千个 id 传到前端再传回来。
 */
public record SkillApplyRequest(String action, String nodeId, List<String> newNodes,
                               List<Long> questionIds, String templateId, String filterStatus) {
}
