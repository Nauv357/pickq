import http from './http'

// 刷题会话接口（粉笔式：选范围 + 数量，服务端抽题）

// 创建会话（服务端抽题）→ { sessionId, total, questions[]（做题格式，无答案） }
// mode: ALL 全部随机(未做优先) / SEQUENCE 按题号顺序 / TOPIC 按分类(多选) / REVIEW / WRONG / FAVORITE
// TOPIC 模式下 topic/category 为多选数组（可为空 = 不限；两组之间 AND，组内 OR）
export const createSession = (bankId, data) => http.post(`/banks/${bankId}/sessions`, data)

// 练习历史（状态 IN_PROGRESS/COMPLETED、得分、用时）
export const listSessions = (bankId, params) => http.get(`/banks/${bankId}/sessions`, { params })

// 会话详情（每题对错/用时；交卷后含答案与解析）
export const getSessionDetail = (sessionId) => http.get(`/sessions/${sessionId}`)

// 交卷（统一判分：一次性提交全部最终作答；answers 为空数组 = 无作答直接交卷；幂等）
// body: { answers: [{ questionId, selectedKeys | userAnswer, seconds? }] }
export const finishSession = (sessionId, answers = []) =>
  http.post(`/sessions/${sessionId}/finish`, { answers: answers || [] })

// 题库分类聚合（TOPIC 会话筛选选项）
export const getBankCategories = (bankId) => http.get(`/banks/${bankId}/categories`)
