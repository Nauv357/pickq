/* ============================================================
   桌面版自动更新（自建频道 https://pickq.cn/updates/）
   Rust 命令：app_version / check_update / download_update / install_update
   浏览器版无更新能力（isDesktop = false，各函数安全降级）
   ============================================================ */
import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'

export const isDesktop = () =>
  typeof window !== 'undefined' && Boolean(window.__TAURI_INTERNALS__)

/** 当前应用版本号（桌面版；浏览器返回 null） */
export async function getAppVersion() {
  if (!isDesktop()) return null
  try {
    return (await invoke('app_version')) || null
  } catch {
    return null
  }
}

/**
 * 检查更新。返回：
 * { supported:false }（浏览器）
 * { supported:true, info: UpdateInfo|null, error:'' }（已最新 info=null）
 * { supported:true, info:null, error:'原因' }（检查失败）
 */
export async function checkForUpdate() {
  if (!isDesktop()) return { supported: false }
  try {
    const info = await invoke('check_update')
    return { supported: true, info: info || null, error: '' }
  } catch (e) {
    return { supported: true, info: null, error: String(e?.message || e || '检查更新失败') }
  }
}

/** 订阅下载进度（payload: { downloaded, total }），返回取消函数 */
export function onUpdateProgress(cb) {
  if (!isDesktop()) return () => {}
  let off = () => {}
  listen('shiti://update-progress', (e) => cb(e.payload || {}))
    .then((un) => {
      off = un
    })
    .catch(() => {})
  return () => off()
}

/** 下载安装包（带进度回调 onProgress(pct 0-100)），返回 { ok, error } */
export async function downloadUpdate(info, onProgress) {
  if (!isDesktop()) return { ok: false, error: '浏览器版不支持自动更新' }
  const un = onUpdateProgress((p) => {
    const total = Number(p.total) || 0
    const downloaded = Number(p.downloaded) || 0
    if (total > 0 && typeof onProgress === 'function') {
      onProgress(Math.min(100, Math.round((downloaded / total) * 100)))
    }
  })
  try {
    await invoke('download_update', {
      url: info.url,
      sha256: info.sha256,
      size: Number(info.size) || 0,
      version: info.version
    })
    if (typeof onProgress === 'function') onProgress(100)
    return { ok: true, error: '' }
  } catch (e) {
    return { ok: false, error: String(e?.message || e || '下载失败') }
  } finally {
    un()
  }
}

/** 安装并重启（应用进程将退出，由更新脚本接管） */
export async function installUpdate(version) {
  if (!isDesktop()) return
  await invoke('install_update', { version })
}
