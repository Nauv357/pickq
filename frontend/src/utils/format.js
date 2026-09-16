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

/* ============================================================
   笔记列表的时间口径（2026-09-16：笔记按"今天/昨天/…"分组，
   一页几十条不再糊成一片；同一处实现，列表与分组头共用）
   ============================================================ */

const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()

/** 分组 key：today / yesterday / week / month / older（按自然日算，不按 24 小时） */
export function timeGroupKey(value, now = new Date()) {
  if (!value) return 'older'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return 'older'
  const days = Math.round((startOfDay(now) - startOfDay(d)) / 86400000)
  if (days <= 0) return 'today'
  if (days === 1) return 'yesterday'
  if (days < 7) return 'week'
  if (days < 30) return 'month'
  return 'older'
}

/** 组顺序（渲染时按这个顺序输出小标题） */
export const TIME_GROUPS = ['today', 'yesterday', 'week', 'month', 'older']

/**
 * 单条笔记的时间显示：今天/昨天给时分，"最近 7 天"给"N 天前"，
 * 更早给"9月4日"（跨年才带年份）——比一律 "2026-09-16 21:12" 好扫。
 */
export function noteTimeLabel(value, now = new Date()) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const group = timeGroupKey(value, now)
  if (group === 'today') return hm
  if (group === 'yesterday') return `昨天 ${hm}`
  if (group === 'week') {
    const days = Math.round((startOfDay(now) - startOfDay(d)) / 86400000)
    return `${days} 天前`
  }
  const sameYear = d.getFullYear() === now.getFullYear()
  return sameYear ? `${d.getMonth() + 1}月${d.getDate()}日` : `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}
