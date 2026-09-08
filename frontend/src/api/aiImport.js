import http from './http'

// AI 辅助导入（文档→题目）与模型配置（BYOK）

// 创建导入任务（multipart：files 多选 + 可选 bankId + 可选 aiSupplement + 可选 thinking + 可选 engine）→ jobId
// aiSupplement=true（默认）：原文答案优先，缺失时 AI 补充答案与解析；false：缺失答案留空待补
// thinking：是否开启 AI 思考模式（缺省 = 用全局设置）
// engine（第十一轮）：AUTO 默认（自动检测本地/MinerU）/ LOCAL 本地解析 / MINERU 云端解析
export const createAiImportJob = (files, bankId, aiSupplement = true, thinking = null, engine = null) => {
  const fd = new FormData()
  for (const f of files) fd.append('files', f)
  if (bankId) fd.append('bankId', String(bankId))
  fd.append('aiSupplement', aiSupplement ? 'true' : 'false')
  if (thinking != null) fd.append('thinking', thinking ? 'true' : 'false')
  if (engine != null) fd.append('engine', engine)
  return http.post('/ai-import/jobs', fd)
}

// 轮询任务状态 → { id, status, stage, progress, fileName, fileType, aiSupplement, questions[], error, ... }
export const getAiImportJob = (jobId) => http.get(`/ai-import/jobs/${jobId}`)

// 进行中的任务列表（全局监控：侧边栏徽标 + 完成通知）
export const listActiveAiJobs = () => http.get('/ai-import/jobs/active')

// 最近未确认导入的任务（createdAt 倒序，侧边栏"最近 AI 导入"入口）
export const getRecentAiJobs = (limit = 5) => http.get('/ai-import/jobs/recent', { params: { limit } })

// 取消/删除任务（两阶段）：进行中标记 CANCELED（可再删一次彻底删除），已完成/失败直接删除
export const deleteAiJob = (jobId) => http.delete(`/ai-import/jobs/${jobId}`)

// SSE 订阅任务进度（事件 update 带任务快照；断开/出错时调用方回退轮询）
// 返回 EventSource 实例，调用方负责 close()
export function subscribeAiJobStream(jobId, { onUpdate, onError } = {}) {
  const es = new EventSource(`/api/ai-import/jobs/${jobId}/stream`)
  es.addEventListener('update', (e) => {
    try {
      onUpdate?.(JSON.parse(e.data))
    } catch (err) {
      /* 忽略坏数据 */
    }
  })
  es.onerror = () => onError?.()
  return es
}

// 预览确认后导入（body { bankId? }，空 = 新建题库）→ { bankId, importedCount }
// 可选 payload.questions/materials：传了非空 questions → 后端以提交内容为准（不再用 result_json）；
// 不传 questions → 旧行为（用 result_json）。引用材料的提交必须同时提交 materials。
export const confirmAiImport = (jobId, bankId, payload = {}) =>
  http.post(`/ai-import/jobs/${jobId}/confirm`, { bankId: bankId || null, ...payload })

// 任务临时图片列表（预览页素材区）→ data: [{num, fileName, ext}]（编号与题目文本 [图片N] 一一对应）
export const listJobImages = (jobId) => http.get(`/ai-import/jobs/${jobId}/images`)

// 单张临时图片 URL（二进制 image/png；编号不存在 → 404）
export const getJobImageUrl = (jobId, num) => `/api/ai-import/jobs/${jobId}/images/${num}`

// 任务材料素材列表（资料分析/阅读材料题共享材料；本地检测或模型输出）→ data: [{materialKey, content}]
export const listMaterialSnippets = (jobId) => http.get(`/ai-import/jobs/${jobId}/material-snippets`)

// 查询 AI 配置（Key 脱敏：hasKey + maskedKey）
export const getAiSettings = () => http.get('/ai/settings')

// 保存 AI 配置（apiKey 为空 = 保留旧 Key；其余字段空 = 保留旧值）
export const saveAiSettings = (data) => http.post('/ai/settings', data)

// 测试连接 → { ok, message, latencyMs }
export const testAiSettings = () => http.post('/ai/settings/test')
