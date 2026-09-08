import http from './http'

// 题库接口
export const createBank = (data) => http.post('/banks', data)

export const getBanks = (params) => http.get('/banks', { params })

// 主页概览（学习首页卡带）：题库数/总题数/今日待复习/错题数/最近练习
export const getHomeOverview = () => http.get('/home/overview')

export const getBank = (id) => http.get(`/banks/${id}`)

export const updateBank = (id, data) => http.put(`/banks/${id}`, data)

// data = { deletedQuestions, affectedRecords }
export const deleteBank = (id) => http.delete(`/banks/${id}`)

// 题目简略列表（无选项、无答案）
export const getBankQuestions = (id, params) => http.get(`/banks/${id}/questions`, { params })

// 题号导航（详情页右侧目录）：与题目列表同过滤同排序，全量轻量字段 → [{ questionId, questionType, questionNumber }]
export const getBankQuestionNav = (id, params) => http.get(`/banks/${id}/question-nav`, { params })

// 做题数据（有选项、无答案）
export const getPracticeQuestions = (id, params) => http.get(`/banks/${id}/questions/practice`, { params })

// 导入内容包（body = 内容包 JSON 原文，v1）→ data = { result, bankId, message }
export const importBank = (jsonText) =>
  http.post('/banks/import', jsonText, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } })

// 导入 .tiku 容器（body = zip 字节，v2）→ data = { result, bankId, message }
export const importTikuBank = (bytes) =>
  http.post('/banks/import-tiku', bytes, { headers: { 'Content-Type': 'application/octet-stream' } })

// 导出内容包 JSON（v1，兼容格式）→ data = 内容包 JSON
export const exportBank = (id, data) => http.post(`/banks/${id}/export`, data)

// 导出 .tiku 容器（v2：zip = package.json + media/）→ Blob
export const exportTikuBank = (id, data) =>
  http.post(`/banks/${id}/export-tiku`, data, { responseType: 'blob' })

// 批量建题（AI 整理数据）→ data = 插入数量
export const batchCreateQuestions = (id, questions) =>
  http.post(`/banks/${id}/questions/batch`, { questions })

// 选题另存/并入：勾选题目复制到新题库（targetBankId 空）或并入现有题库（源库保留）→ data = { bankId, name, questionsCopied, materialsCopied }
export const copyQuestionSelection = (id, data) =>
  http.post(`/banks/${id}/questions/selection-copy`, data)

// AI 批量补答案：库内全部无答案客观题 → 思考模式判定回填（分钟级，勿关页面）
// data = { questionType, withAnalysis } → data = { total, filled, undetermined, failed }
export const aiFillAnswers = (id, data) =>
  http.post(`/banks/${id}/questions/ai-fill-answers`, data, { timeout: 0 })

// 开启/关闭复习计划（默认关；进度/错题/待复习队列见 studyRecords.js）
export const setReviewEnabled = (id, enabled) => http.put(`/banks/${id}/review-enabled`, { enabled })

// 合并多个题库为新题库（复制 + 血缘；源库保留）→ data = { bankId, name, questionsCopied, materialsCopied }
export const mergeBanks = (data) => http.post('/banks/merge', data)
