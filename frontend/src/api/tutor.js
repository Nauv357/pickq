import http from './http'

/**
 * AI 私教（学习路径引擎阶段 1）：提示楼梯 / 答错即问 / 自由追问 / 整场复盘。
 * 设计见 docs/learning-path-design.md §7.4。
 *
 * 生成类接口是 **SSE 流式**（`text/event-stream`），事件：session / delta / done / error。
 * 浏览器里 axios 拿不到流（XHR 不支持增量读取），所以这里用 fetch + ReadableStream 自己解 SSE。
 */

const API_BASE = '/api'

/**
 * 发起一次流式生成。
 * @param path      /tutor/hint | /tutor/ask | /tutor/review
 * @param body      请求体（见后端 TutorAskRequest）
 * @param handlers  { onSession, onDelta, onDone, onError }
 * @returns {Promise<{ok:boolean, message?:string}>}
 */
export async function streamTutor(path, body, handlers = {}) {
  const { onSession, onDelta, onDone, onError } = handlers
  let res
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
  } catch (e) {
    onError?.('无法连接后端服务，请确认应用仍在运行')
    return { ok: false, message: String(e?.message || e) }
  }
  if (!res.ok || !res.body) {
    // 非流式错误（如参数校验失败）：尽量把后端的可照做提示读出来
    let message = `请求失败（HTTP ${res.status}）`
    try {
      const text = await res.text()
      const parsed = JSON.parse(text)
      if (parsed?.message) message = parsed.message
    } catch (e) {
      /* 保持默认文案 */
    }
    onError?.(message)
    return { ok: false, message }
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let failed = null
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        let name = 'message'
        const dataLines = []
        for (const line of frame.split('\n')) {
          if (line.startsWith('event:')) name = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (!dataLines.length) continue
        let payload = null
        try {
          payload = JSON.parse(dataLines.join('\n'))
        } catch (e) {
          continue
        }
        if (name === 'delta') onDelta?.(payload.text || '')
        else if (name === 'session') onSession?.(payload)
        else if (name === 'done') onDone?.(payload)
        else if (name === 'error') {
          failed = payload.message || '生成失败'
          onError?.(failed)
        }
      }
    }
  } catch (e) {
    // 用户中途关闭面板/切页会中断读取，这不是错误
    if (e?.name !== 'AbortError') {
      failed = failed || '连接中断，可重试'
      onError?.(failed)
    }
  }
  return failed ? { ok: false, message: failed } : { ok: true }
}

/** 一场会话的全部消息 + 已提示到第几级 */
export const getTutorMessages = (sessionId) => http.get(`/tutor/sessions/${sessionId}/messages`)

/** 某题的历史追问会话（接着上次聊） */
export const getTutorSessions = (questionId) => http.get('/tutor/sessions', { params: { questionId } })

/** 本场复盘的公式统计（成绩 / 按知识点的错题分布 / 错题清单） */
export const getReviewSummary = (practiceSessionId, bankId, templateId) =>
  http.get(`/tutor/review/${practiceSessionId}/summary`, { params: { bankId, templateId } })

/** 答错即问的快捷三选（与后端 TutorSession 常量一致） */
export const SELF_REASONS = [
  { value: 'CARELESS', label: '看错 / 蒙的' },
  { value: 'NO_KNOWLEDGE', label: '这个知识点不会' },
  { value: 'NEVER_SEEN', label: '完全没见过' }
]
