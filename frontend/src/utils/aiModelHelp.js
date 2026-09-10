/* ============================================================
   AI 模型失效自愈（「模型不存在 / 已下线」类错误的识别与引导）
   - isModelError(msg)                 判断错误信息是否为模型失效类（中英文）
   - findReplacement(model, list)      查该模型名是否有替代建议
   - findDeprecatedInText(text, list)  从错误文本里找出命中的下线模型
   - mergeDeprecated(remoteList)       远端下线表 + 内置下线表合并
   - offerModelRecovery(msg, options)  命中模型错误时才询问「是否去获取可用模型」
   设计口径：宁可不报，也不误报——普通网络/Key/额度类错误一律不判为模型失效。
   ============================================================ */
import { ElMessageBox } from 'element-plus'
import { readCachedDeprecated } from '../api/aiConfig'

/** 内置下线模型表（远端目录不可用时回退；远端 deprecated 优先） */
export const BUILTIN_DEPRECATED = [
  {
    model: 'deepseek-v4-flash-vision-exp',
    replacement: 'deepseek-chat',
    note: '该模型已合并，改用 deepseek-chat'
  }
]

/** 强特征：与模型名强相关，单独出现即可判定 */
const STRONG_MODEL_PATTERNS = [
  /model[\s_-]*not[\s_-]*found/i, // model not found / model_not_found
  /model[\s_-]*not[\s_-]*exist/i, // Model Not Exist（DeepSeek 等）
  /unknown[\s_-]+model/i, // unknown model
  /no[\s_-]+such[\s_-]+model/i, // no such model
  /invalid[\s_-]+model|model[\s_-]+invalid/i, // invalid model id / model_invalid
  /model[\s_-]*(?:deprecated|retired|decommissioned|unavailable|sunset)/i,
  /(?:不支持|无效|未知|不存在|已下线|已停用|已废弃|已合并|已被移除)的?模型/, // 中文：无效的模型
  /模型[^\n。；;]{0,12}?(?:不存在|无效|未知|已下线|已停用|已废弃|不可用|未找到|找不到|已被?移除|不支持)/ // 模型 xxx 不存在
]

/** 泛特征：句中同时出现「模型」与「不存在/无效/未找到」类措辞（限制同段距离，避免误报） */
const LOOSE_MODEL_PATTERN =
  /(?:model|模型)[^\n。；;]{0,32}?(?:not[\s_-]+found|does[\s_-]+not[\s_-]+exist|doesn'?t[\s_-]+exist|not[\s_-]+exist|invalid|unknown|unavailable|deprecated|retired|decommissioned|no[\s_-]+longer|不存在|无效|未找到|不可用|已下线|不支持)/i

/** 与模型无关的错误特征（Key / 鉴权 / 额度 / 网络）：命中且无强模型特征时不判为模型失效 */
const NON_MODEL_PATTERN =
  /(api[\s_-]?key|apikey|密钥|鉴权|认证|unauthorized|invalid[\s_-]+key|incorrect[\s_-]+api[\s_-]+key|配额|额度|余额|欠费|quota|insufficient|rate[\s_-]?limit|限流|请求过于频繁|timeout|超时|network|网络|连接被拒绝|econnrefused|ssl|certificate)/i

/** Key 本身无效类措辞（即使同句出现 model 字样也不算模型失效，如 invalid api key for model x） */
const KEY_ERROR_PATTERN =
  /(?:api[\s_-]?key|apikey|密钥)[^\n。；;]{0,20}?(?:无效|不正确|错误|非法|过期|invalid|incorrect|not[\s_-]+found|不存在)/i

/**
 * 判断错误信息是否为「模型不存在 / 已下线」类。
 * @param {string|Error|any} msg 错误信息（字符串，或带 message 的对象）
 * @returns {boolean}
 */
export function isModelError(msg) {
  const text = typeof msg === 'string' ? msg : String(msg?.message || msg || '')
  if (!text.trim()) return false
  if (STRONG_MODEL_PATTERNS.some((re) => re.test(text))) {
    // 「Key 无效」优先：invalid api key for model gpt-4 这类不能误判成模型下线
    return !KEY_ERROR_PATTERN.test(text)
  }
  if (NON_MODEL_PATTERN.test(text) || KEY_ERROR_PATTERN.test(text)) return false
  return LOOSE_MODEL_PATTERN.test(text)
}

/** 规范化下线表：丢弃缺 model/replacement 的条目 */
function normalizeDeprecated(list) {
  return (Array.isArray(list) ? list : [])
    .filter((d) => d && typeof d.model === 'string' && d.model.trim() && typeof d.replacement === 'string' && d.replacement.trim())
    .map((d) => ({
      model: d.model.trim(),
      replacement: d.replacement.trim(),
      note: typeof d.note === 'string' ? d.note.trim() : ''
    }))
}

/**
 * 查某个模型名的替代建议（大小写不敏感、去空格）。
 * @returns {{ model:string, replacement:string, note:string }|null}
 */
export function findReplacement(modelName, deprecatedList) {
  const name = String(modelName || '').trim().toLowerCase()
  if (!name) return null
  const hit = normalizeDeprecated(deprecatedList).find((d) => d.model.toLowerCase() === name)
  return hit ? { ...hit } : null
}

/**
 * 从错误文本里找出命中的下线模型（错误信息常带模型名，比只比对表单里的模型名更准）。
 * @returns {{ model:string, replacement:string, note:string }|null}
 */
export function findDeprecatedInText(text, deprecatedList) {
  const s = String(text || '').toLowerCase()
  if (!s) return null
  const hits = normalizeDeprecated(deprecatedList).filter((d) => s.includes(d.model.toLowerCase()))
  if (!hits.length) return null
  // 多个命中时取模型名最长的（更精确）
  const hit = hits.sort((a, b) => b.model.length - a.model.length)[0]
  return { ...hit }
}

/** 远端下线表 + 内置下线表合并（同名去重，远端优先） */
export function mergeDeprecated(remoteList) {
  const remote = normalizeDeprecated(remoteList)
  const names = new Set(remote.map((d) => d.model.toLowerCase()))
  const builtin = normalizeDeprecated(BUILTIN_DEPRECATED).filter((d) => !names.has(d.model.toLowerCase()))
  return [...remote, ...builtin]
}

/**
 * 模型失效自愈提示：仅在命中「模型不存在 / 已下线」类错误时询问是否去设置页获取可用模型
 * （普通失败不弹窗，避免打断）。确认后跳转设置页并自动触发一次「获取可用模型」。
 * @param {string|Error} message 原始错误信息
 * @param {object} options
 * @param {object} options.router vue-router 实例
 * @param {{ask:string,title:string,confirm:string,cancel:string}} options.texts 组件内 i18n 文案
 * @param {Array} [options.deprecated] 下线模型表（缺省 = 本地缓存 + 内置）
 * @param {(hit:object)=>string} [options.describe] 生成替代建议附加文案（可空）
 * @returns {Promise<boolean>} true = 用户确认并已跳转设置页
 */
export async function offerModelRecovery(message, { router, texts, deprecated, describe } = {}) {
  if (!isModelError(message) || !router || !texts?.ask) return false
  const list = Array.isArray(deprecated) ? deprecated : mergeDeprecated(readCachedDeprecated())
  const hit = findDeprecatedInText(message, list)
  const hint = hit && typeof describe === 'function' ? String(describe(hit) || '') : ''
  const body = hint ? `${texts.ask}\n\n${hint}` : texts.ask
  try {
    await ElMessageBox.confirm(body, texts.title || texts.ask, {
      confirmButtonText: texts.confirm,
      cancelButtonText: texts.cancel,
      type: 'warning',
      closeOnClickModal: false
    })
  } catch (e) {
    return false // 用户选择「暂不」
  }
  try {
    await router.push({ name: 'settings', query: { fetchModels: '1' } })
  } catch (e) {
    /* 重复导航等异常忽略：设置页仍会自行加载预设与模型 */
  }
  return true
}
