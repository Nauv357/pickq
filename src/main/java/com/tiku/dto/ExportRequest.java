package com.tiku.dto;

import java.util.List;

/**
 * 导出内容包参数：均可选，缺省时使用题库已记录的值。
 * POST /api/banks/{id}/export
 *
 * @param version    版本号：请求值优先，其次题库已记录，缺省 1.0.0
 * @param authorName 作者名：请求值优先，其次题库 authorName
 * @param mode       身份决策：UPGRADE / BRANCH / null（AUTO 智能判断）
 *                   - UPGRADE：沿用当前 packageKey（作者迭代自己的包，可填新版本号）
 *                   - BRANCH：生成新 packageKey + parentKey 指向当前身份（派生/修改他人包）
 *                   - AUTO（默认）：自建题库生成新身份；内容与导入一致则原样沿用；
 *                     内容被修改过则默认分支（安全默认，防冒用，作者可显式传 UPGRADE）
 * @param scope      题目范围（打印 / 组卷 / 导出所选复用）：all 全部（默认）/ favorite 收藏 /
 *                   wrong 答错 / undone 未做过
 * @param category   按分类过滤（可选）
 * @param topic      按主题过滤（可选）
 * @param questionIds 按题目 id 精确导出（可选；「勾选若干题后导出」用）。
 *                    非空时与 scope/category/topic 取交集，并且视为**非完整导出**：
 *                    不认领题库身份（子集内容不能登记成整个题库的指纹），本次文件用临时身份。
 */
public record ExportRequest(
        String version,
        String authorName,
        String mode,
        String scope,
        String category,
        String topic,
        List<Long> questionIds
) {
}
