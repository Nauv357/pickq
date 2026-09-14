import http from './http'

/**
 * 技能图与知识点标签（学习路径引擎阶段 0）。
 *
 * 两条链路：
 * - 技能图：受控词表（内置官方模板），只读；
 * - 标签：AI 提建议（suggest）→ 审阅清单（questions）→ 就地改 / 确认 / 丢弃（apply）；
 *   单题也可由用户直接标注（setQuestionSkills，覆盖 AI 与作者、只影响本机）。
 *
 * 审阅清单以**题**为单位返回，每题带 status 与当前 tags（含来源与把握），
 * 且计数与清单来自后端**同一份快照**——界面上的数字与清单条数不会对不上。
 *
 * 打标签是**按批推进**的：一次请求只花 maxAiCalls 次模型调用（题库大时跑不完），
 * 界面按批循环推进并显示进度；所以 timeout 设为 0（不设客户端超时）。
 */
export const getSkillTemplates = () => http.get('/skills/templates')

export const getSkillTemplate = (templateId) => http.get(`/skills/templates/${encodeURIComponent(templateId)}`)

export const suggestSkills = (bankId, { templateId, includeUntagged = true, maxAiCalls = 5 } = {}) =>
  http.post(`/banks/${bankId}/skills/suggest`, null, {
    params: { templateId, includeUntagged, maxAiCalls },
    timeout: 0
  })

/**
 * 审阅清单：status = all | confirmed | pending | untagged；nodeId 可选（只看某知识点的题）。
 * 返回 { total, page, size, counts, records }。
 */
export const getSkillQuestions = (bankId, { templateId, status = 'all', nodeId, page = 1, size = 50 } = {}) =>
  http.get(`/banks/${bankId}/skills/questions`, { params: { templateId, status, nodeId, page, size } })

/**
 * 标签动作：
 * - confirm / reject：确认或丢弃 AI 建议（可只给 questionIds，也可按 nodeId 整批）
 * - set：把这些题的标签**设定**为 newNodes（就地改标签 / 批量设为知识点；newNodes 为空数组 = 清空）
 * - retag：按节点批量改挂
 */
export const applySkills = (bankId, body) => http.post(`/banks/${bankId}/skills/apply`, body)

export const getSkillCoverage = (bankId, templateId) =>
  http.get(`/banks/${bankId}/skills/coverage`, { params: { templateId } })

export const getQuestionSkills = (questionId) => http.get(`/questions/${questionId}/skills`)

export const setQuestionSkills = (questionId, body) => http.put(`/questions/${questionId}/skills`, body)
