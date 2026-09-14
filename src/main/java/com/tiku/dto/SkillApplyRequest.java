package com.tiku.dto;

import java.util.List;

/**
 * 标签批量操作请求（学习路径引擎阶段 0）。
 *
 * @param action      confirm（确认该节点下的 AI 建议）/ retag（改挂到 newNodes）/ reject（丢弃建议）
 * @param nodeId      待操作节点（confirm/reject 必填；retag 时可空，空则用 questionIds）
 * @param newNodes    retag 的目标节点列表
 * @param questionIds retag 只作用于这些题（空则取该节点下所有 AI 建议题）
 * @param templateId  技能模板
 */
public record SkillApplyRequest(String action, String nodeId, List<String> newNodes,
                                List<Long> questionIds, String templateId) {
}
