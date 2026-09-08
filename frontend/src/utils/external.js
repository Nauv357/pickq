/* ============================================================
   桌面版外部链接打开（统一出口）
   WebView2 默认拦截 window.open / <a target="_blank">——桌面版一律
   调 Rust open_url 命令用系统默认浏览器打开；浏览器版退回 window.open。
   ============================================================ */
import { invoke } from '@tauri-apps/api/core'

const isDesktop = () =>
  typeof window !== 'undefined' && Boolean(window.__TAURI_INTERNALS__)

/** 在系统浏览器打开外链（http/https）；返回是否已处理 */
export async function openExternal(url) {
  const u = String(url || '').trim()
  if (!/^https?:\/\//i.test(u)) {
    console.warn('[openExternal] 仅支持 http(s) 链接，已忽略:', u)
    return false
  }
  if (isDesktop()) {
    try {
      await invoke('open_url', { url: u })
      return true
    } catch (e) {
      console.warn('[openExternal] invoke 失败，退回 window.open:', e)
    }
  }
  window.open(u, '_blank', 'noopener')
  return true
}

export { isDesktop }
