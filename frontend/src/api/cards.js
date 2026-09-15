import http from './http'

/**
 * 闪卡（学习路径引擎阶段 3）：题目测"再认"，卡片测"回忆"。
 *
 * 规则：AI 生成的卡默认未确认，**未确认不参与复习调度**；卡片答错会让已过关的知识点掉回"需重练"，
 * 但答对不足以判定掌握。每张卡都带出处题，可点回原题。
 */

/** 从解析挖空生成闪卡（按批推进：一次最多花 maxAiCalls 次模型调用，可再次点击继续） */
export const generateCards = (bankId, { templateId, nodeId, maxAiCalls = 5 } = {}) =>
  http.post('/cards/generate', null, {
    params: { bankId, templateId, nodeId, maxAiCalls },
    timeout: 0
  })

/** 卡片列表：status = all / unconfirmed / confirmed / due */
export const getCards = (bankId, { status, templateId } = {}) =>
  http.get('/cards', { params: { bankId, status, templateId } })

export const getDueCards = (bankId, { limit = 50, templateId } = {}) =>
  http.get('/cards/due', { params: { bankId, limit, templateId } })

export const getCardStats = (bankId) => http.get('/cards/stats', { params: { bankId } })

export const reviewCard = (bankId, cardId, remembered) =>
  http.post(`/cards/${cardId}/review`, null, { params: { bankId, remembered } })

export const confirmCards = (bankId, cardIds, confirmed = true) =>
  http.post('/cards/confirm', { cardIds, confirmed }, { params: { bankId } })

export const updateCard = (bankId, cardId, data) =>
  http.put(`/cards/${cardId}`, data, { params: { bankId } })

export const deleteCards = (bankId, cardIds) =>
  http.delete('/cards', { data: { cardIds }, params: { bankId } })
