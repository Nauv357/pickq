/* ============================================================
   AI 服务商预设目录 + 可用模型列表（远端目录 + 本地缓存 + 内置回退）
   - GET  /api/ai/presets?refresh=  → { updatedAt, presets[], deprecated[] }（后端原样返回）
   - POST /api/ai/models            → { models[], resolvedBaseUrl, count }
   预设拉取失败一律静默（skipErrorMessage），由调用方回退内置/缓存并决定是否提示；
   模型列表失败默认交给全局拦截器展示后端可读 message（silent = 连提示也跳过）。
   ============================================================ */
import http from './http'

/** 预设缓存键（localStorage）：拉取成功后写入，下次启动先用缓存渲染再后台刷新 */
export const AI_PRESETS_CACHE_KEY = 'tiku:ai-presets-cache'

/** 远端预设目录（refresh=true 强制回源刷新）；失败不弹全局提示，调用方自行回退 */
export const getAiPresets = (refresh = false) =>
  http.get('/ai/presets', {
    params: { refresh: refresh ? 'true' : 'false' },
    skipErrorMessage: true
  })

/**
 * 可用模型列表（baseUrl 必填；apiKey 空 = 后端用已保存的 Key，本机/局域网地址允许留空）；
 * silent = 失败完全静默。
 * timeout 单独放宽到 25s：后端探测模型列表的读超时是 20s（AiModelCatalogService），
 * 用 axios 默认的 15s 会让慢服务商先被前端判超时、看不到后端给出的真实原因。
 */
export const listAiModels = ({ baseUrl, apiKey = null, silent = false } = {}) =>
  http.post(
    '/ai/models',
    { baseUrl, apiKey: apiKey || null },
    { timeout: 25000, ...(silent ? { skipErrorMessage: true } : {}) }
  )

/** 读取预设缓存（无缓存 / 结构不合法 / 存储不可用 → null） */
export function readPresetCache() {
  try {
    const raw = localStorage.getItem(AI_PRESETS_CACHE_KEY)
    if (!raw) return null
    const data = JSON.parse(raw)
    if (!data || !Array.isArray(data.presets) || !data.presets.length) return null
    return {
      updatedAt: typeof data.updatedAt === 'string' ? data.updatedAt : '',
      note: typeof data.note === 'string' ? data.note : '',
      presets: data.presets,
      deprecated: Array.isArray(data.deprecated) ? data.deprecated : []
    }
  } catch (e) {
    return null
  }
}

/** 写入预设缓存（存储不可用/超额时忽略，不影响功能） */
export function writePresetCache(data) {
  try {
    localStorage.setItem(
      AI_PRESETS_CACHE_KEY,
      JSON.stringify({
        updatedAt: data?.updatedAt || '',
        note: typeof data?.note === 'string' ? data.note : '',
        presets: Array.isArray(data?.presets) ? data.presets : [],
        deprecated: Array.isArray(data?.deprecated) ? data.deprecated : []
      })
    )
  } catch (e) {
    /* 忽略 */
  }
}

/** 缓存里的下线模型表（未打开设置页的调用点做替代建议用；无缓存 → []） */
export function readCachedDeprecated() {
  const cached = readPresetCache()
  return cached ? cached.deprecated : []
}

/**
 * 预设合并（按 name）：
 * - 内置顺序优先（DeepSeek 仍在最前，UI 稳定）；
 * - 远端条目<b>显式给出的字段一律以远端为准</b>，含空字符串——远端有意不再维护易变的模型名时
 *   会下发 model/visionModel: ""，此时必须让空值生效，不能回落到内置的旧模型名；
 * - 远端没写的字段保留内置值（避免远端精简条目把 label/desc 等清空）；
 * - 内置有、远端没有的项保留（如本地 Ollama）；远端独有的新服务商追加在末尾。
 */
export function mergePresetsByName(builtin, remote) {
  const remoteMap = new Map()
  for (const p of Array.isArray(remote) ? remote : []) {
    if (p && typeof p.name === 'string' && p.name.trim()) remoteMap.set(p.name.trim(), p)
  }
  const out = []
  const seen = new Set()
  for (const b of Array.isArray(builtin) ? builtin : []) {
    if (!b || !b.name || seen.has(b.name)) continue
    seen.add(b.name)
    const r = remoteMap.get(b.name)
    out.push(r ? { ...b, ...r } : b)
  }
  for (const [name, r] of remoteMap) {
    if (!seen.has(name)) out.push(r)
  }
  return out
}
