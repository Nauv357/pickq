/* ============================================================
   主题管理(Soft UI Evolution 双主题 · v2.1 规范 §4.10)
   偏好三态: system | light | dark(存 localStorage 'tiku:theme')
   生效方式: document.documentElement 加/去 'dark' class(与 EP dark css-vars 同开关)
   ============================================================ */

const STORAGE_KEY = 'tiku:theme'

/** 读取偏好(非法值回退 system) */
export function getTheme() {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch (e) { /* 隐私模式等场景静默 */ }
  return 'system'
}

/** 偏好 → 实际主题 */
export function resolveTheme(pref) {
  if (pref === 'system') {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return pref
}

/** 应用主题,返回实际生效值('light'|'dark') */
export function applyTheme(pref) {
  const t = resolveTheme(pref)
  document.documentElement.classList.toggle('dark', t === 'dark')
  return t
}

/** 应用当前偏好并订阅系统变化(仅 system 时生效) */
export function initTheme() {
  const pref = getTheme()
  applyTheme(pref)
  const mq = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)')
  if (mq && typeof mq.addEventListener === 'function') {
    mq.addEventListener('change', () => {
      if (getTheme() === 'system') applyTheme('system')
    })
  }
  return pref
}

/** 显式设置偏好(light/dark/system),持久化并即时生效,返回实际主题 */
export function setTheme(pref) {
  try { localStorage.setItem(STORAGE_KEY, pref) } catch (e) { /* 静默 */ }
  return applyTheme(pref)
}

/** 当前实际生效主题 */
export function currentTheme() {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}
