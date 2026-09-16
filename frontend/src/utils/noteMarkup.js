/**
 * 笔记的"轻量标注"（圈画/高亮）——2026-09-16 用户反馈："笔记这种东西本身就不该是纯文字，
 * 纸面上我们会用不同颜色的笔圈画批注"。
 *
 * 我们不做富文本编辑器（那会变成第二个 Word），只做**纸面直觉的那几样**，
 * 用最朴素的标记写在正文里（存进数据库的还是纯文本，导出/搜索/迁移都不受影响）：
 *
 *   ==文字==      黄底高亮（默认色）
 *   ==g:文字==    绿色   ==b:文字== 蓝色   ==p:文字== 粉色
 *   **文字**      加粗（相当于用笔描重一遍）
 *   __文字__      下划线（相当于划线）
 *
 * 渲染在 `utils/richText.js`（同一套规则，因此"效果预览"与列表里显示的一致）；
 * 这里的函数负责**在光标选区上套/去标记**，供编辑框上方的工具条使用。
 */

/** 标记调色板（key 与后端 note.color 的取值一致，前端只用这一份定义） */
export const MARK_COLORS = [
  { key: 'y', label: '黄', labelEn: 'Yellow' },
  { key: 'g', label: '绿', labelEn: 'Green' },
  { key: 'b', label: '蓝', labelEn: 'Blue' },
  { key: 'p', label: '粉', labelEn: 'Pink' }
]

export const isMarkColor = (key) => MARK_COLORS.some((c) => c.key === key)

/** 高亮标记：`==y:文字==`（黄是默认色，不写前缀，正文更干净） */
export const highlightMark = (color) => (color && color !== 'y' ? `==${color}:` : '==')

/** 是否含任何标记（决定要不要给"效果预览"） */
export const hasMarks = (text) => /==[^=\n]*==|\*\*[^*\n]+\*\*|__[^_\n]+__/.test(String(text || ''))

/** 在 [start,end) 选区外套一对标记；无选区时在光标处插入一对空标记并把光标放中间 */
export function wrapSelection(text, start, end, open, close = open) {
  const src = String(text ?? '')
  const s = Math.max(0, Math.min(start ?? 0, src.length))
  const e = Math.max(s, Math.min(end ?? s, src.length))
  const selected = src.slice(s, e)
  const next = src.slice(0, s) + open + selected + close + src.slice(e)
  // 选中原文时把选区留在原文上（方便接着套第二层），没选中时把光标放进标记中间
  const caret = selected ? [s + open.length, s + open.length + selected.length] : [s + open.length, s + open.length]
  return { text: next, start: caret[0], end: caret[1] }
}

/** 高亮：`==文字==` / `==g:文字==` */
export function markHighlight(text, start, end, color = 'y') {
  const open = highlightMark(color)
  // 绿色/蓝色/粉色是 `==g:` 前缀 + `==` 结尾（前缀不是独立标记）
  if (open.endsWith(':')) {
    const src = String(text ?? '')
    const s = Math.max(0, Math.min(start ?? 0, src.length))
    const e = Math.max(s, Math.min(end ?? s, src.length))
    const selected = src.slice(s, e)
    const next = src.slice(0, s) + open + selected + '==' + src.slice(e)
    const from = s + open.length
    return { text: next, start: from, end: from + selected.length }
  }
  return wrapSelection(text, start, end, open, '==')
}

/** 加粗：`**文字**` */
export const markBold = (text, start, end) => wrapSelection(text, start, end, '**')

/** 下划线：`__文字__` */
export const markUnderline = (text, start, end) => wrapSelection(text, start, end, '__')

/**
 * 清除标记：把正文里所有标记符号去掉（内容保留）。
 * 只做"整条清除"而不做"选区清除"：选区清除要处理嵌套与半截标记，规则一旦含糊
 * 就会出现"点了一下格式更乱了"的体验，宁可给一个确定的结果。
 */
export function stripMarks(text) {
  return String(text ?? '')
    .replace(/==([ygbp]):([^=\n]*)==/g, '$2')
    .replace(/==([^=\n]*)==/g, '$1')
    .replace(/\*\*([^*\n]+)\*\*/g, '$1')
    .replace(/__([^_\n]+)__/g, '$1')
}
