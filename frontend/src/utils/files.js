/**
 * 保存 JSON 对象为本地 .json 文件
 * 桌面版（Tauri）：弹系统"另存为"对话框 → 返回 { saved, path }；取消返回 { saved: false }
 * 浏览器版：触发浏览器下载 → 返回 null（浏览器自带下载栏提示位置）
 */
export async function saveJsonFile(data, filename) {
  const blob = new Blob([JSON.stringify(data, null, 2)], {
    type: 'application/json;charset=utf-8'
  })
  return saveBlob(blob, filename)
}

/**
 * 保存二进制 Blob 为本地文件（.tiku 容器 / zip 备份等）
 * 桌面版走 Tauri「另存为」：用户自选保存位置（记住上次目录），成功后返回完整路径；
 * 浏览器版保持原下载行为。
 */
export async function saveBlob(blob, filename) {
  //桌面壳：Tauri v2 远程页面经 __TAURI_INTERNALS__.invoke 调 Rust 命令（无全局 __TAURI__）
  const invoke = window.__TAURI_INTERNALS__?.invoke
  if (typeof invoke === 'function') {
    const buf = await blob.arrayBuffer()
    const b64 = base64FromArrayBuffer(buf)
    const path = await invoke('save_dialog_file', { filename, dataBase64: b64 })
    if (path) {
      return { saved: true, path }
    }
    return { saved: false, path: null } //用户取消
  }
  //浏览器版：原逻辑（浏览器下载栏会提示保存位置）
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  return null
}

function base64FromArrayBuffer(buf) {
  const bytes = new Uint8Array(buf)
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

/**
 * 读取文件文本（UTF-8）
 */
export function readTextFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsText(file, 'utf-8')
  })
}

/**
 * 读取文件为 ArrayBuffer（.tiku 容器等二进制）
 */
export function readArrayBuffer(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsArrayBuffer(file)
  })
}

/**
 * 触发隐藏的 <input type="file"> 选择文件
 */
export function pickFile(accept = '.json,application/json') {
  return new Promise((resolve, reject) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = accept
    input.onchange = () => {
      const file = input.files && input.files[0]
      if (file) resolve(file)
      else reject(new Error('未选择文件'))
    }
    input.click()
  })
}
