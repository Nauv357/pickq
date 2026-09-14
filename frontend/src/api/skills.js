import http from './http'

/**
 * 技能图与知识点标签（学习路径引擎阶段 0）。
 *
 * 两条链路：
 * - 技能图：受控词表（内置官方模板），只读；
 * - 标签：AI 提建议（suggest）→ 待确认队列（pending）→ 批量确认/改挂/丢弃（apply）；
 *   单题也可由用户直接标注（setQuestionSkills，覆盖 AI 与作者、只影响本机）。
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

export const getPendingSkills = (bankId, templateId) =>
  http.get(`/banks/${bankId}/skills/pending`, { params: { templateId } })

export const applySkills = (bankId, body) => http.post(`/banks/${bankId}/skills/apply`, body)

export const getSkillCoverage = (bankId, templateId) =>
  http.get(`/banks/${bankId}/skills/coverage`, { params: { templateId } })

export const getQuestionSkills = (questionId) => http.get(`/questions/${questionId}/skills`)

export const setQuestionSkills = (questionId, body) => http.put(`/questions/${questionId}/skills`, body)
