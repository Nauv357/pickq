/**
 * 通用小工具
 */

/** 后端 LocalDateTime 形如 2026-08-13T00:00:00（ISO-8601） */
export function formatDate(value, withTime = true) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  if (!withTime) return date
  return `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 分数格式化：整数显示整数，小数保留（如 1、2.5） */
export function formatScore(value) {
  if (value == null) return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return String(value)
  return Number.isInteger(n) ? String(n) : String(Math.round(n * 10) / 10)
}
