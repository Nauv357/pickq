package com.tiku.dto;

import java.util.List;

/**
 * 知识点标签的批量操作请求（学习路径引擎阶段 0）。
 *
 * @param action      confirm（确认建议）/ retag（改挂到 newNodes）/ reject（丢弃建议）/ backfill-topic（把主题写回题目）
 * @param nodeId      目标节点（confirm/reject/retag/backfill 都可只用它来批处理该节点下的题）
 * @param newNodes    retag 的目标节点列表
 * @param questionIds 只作用于这些题（用于"逐题确认/逐题改挂/逐题丢弃"；空则按 nodeId 整批）
 * @param templateId  技能模板
 * @param topic       backfill-topic 要写入题目的主题词（默认不覆盖已有主题）
 * @param overwrite   backfill-topic 是否覆盖已有主题
 */
public record SkillApplyRequest(String action, String nodeId, List<String> newNodes,
                               List<Long> questionIds, String templateId,
                               String topic, Boolean overwrite) {
}
