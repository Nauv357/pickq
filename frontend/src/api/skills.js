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
 * 作用范围优先 questionIds；没给题时可以给 filterStatus（all/confirmed/pending/untagged）+ nodeId，
 * 由后端解析成题目列表——界面的"全选 N 题"走这条路，不把上千个 id 传到前端再传回来。
 */
export const applySkills = (bankId, body) => http.post(`/banks/${bankId}/skills/apply`, body)

export const getSkillCoverage = (bankId, templateId) =>
  http.get(`/banks/${bankId}/skills/coverage`, { params: { templateId } })

/** 清理失效标签：指向"当前技能图里已不存在节点"的旧标签（技能图升级 / 删掉自定义节点之后留下的） */
export const cleanupOrphanTags = (bankId, templateId) =>
  http.post(`/banks/${bankId}/skills/cleanup-orphans`, null, { params: { templateId } })

/**
 * 单题标签。必须带 templateId：与知识点页同口径——否则会把别的模板、或技能图升级后
 * 已不存在的旧节点以原始 id（如 gk.zl.concept）的形式显示在题目详情里。
 */
export const getQuestionSkills = (questionId, templateId) =>
  http.get(`/questions/${questionId}/skills`, { params: { templateId } })

export const setQuestionSkills = (questionId, body) => http.put(`/questions/${questionId}/skills`, body)

/**
 * 新增自定义知识点（自己的词表，只存本机）：同名会复用已有节点，不会造出两个看起来一样的。
 * stageId 省略 = 挂到「自定义知识点」阶段；也可以挂到官方阶段下。
 */
export const createSkillNode = (templateId, name, stageId) =>
  http.post(`/skills/templates/${encodeURIComponent(templateId)}/nodes`, { name, stageId })

/** 改名 / 换阶段（只针对自定义节点）：**改名不动 nodeId**，所以已打的标签不会失效 */
export const updateSkillNode = (templateId, nodeId, { name, stageId } = {}) =>
  http.put(`/skills/templates/${encodeURIComponent(templateId)}/nodes/${encodeURIComponent(nodeId)}`, { name, stageId })

/** 删除自定义知识点（只能删 custom.*）；它上面的标签会变成失效标签，可在题库里一键清理 */
export const deleteSkillNode = (templateId, nodeId) =>
  http.delete(`/skills/templates/${encodeURIComponent(templateId)}/nodes/${encodeURIComponent(nodeId)}`)

/** 停用 / 恢复官方节点（只影响本机：从词表、下拉与 AI 提示里隐藏；不改官方模板本身） */
export const setSkillNodeDisabled = (templateId, nodeId, disabled) =>
  http.put(`/skills/templates/${encodeURIComponent(templateId)}/nodes/${encodeURIComponent(nodeId)}/disabled`, { disabled })

/** 本机对这张图的改动（自定义节点 / 已停用的官方节点 / 阶段列表），「管理知识点」界面用 */
export const getSkillCustomizations = (templateId) =>
  http.get(`/skills/templates/${encodeURIComponent(templateId)}/customizations`)

/** 恢复官方模板：清掉这张图的全部自定义节点与停用记录（标签会变成失效标签，可一键清理） */
export const resetSkillCustomizations = (templateId) =>
  http.delete(`/skills/templates/${encodeURIComponent(templateId)}/customizations`)
