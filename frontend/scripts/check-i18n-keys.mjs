/**
 * 检查「用了但没定义」的 i18n key：t('x') / $t('x') 里的 key 必须能在本组件的局部词典
 * 或全局词典（common.*，见 src/i18n/index.js）里找到；否则界面上直接显示裸 key
 * （例如 SessionHistoryView 的标题曾长期显示 historyTitle / sessionsTotal）。
 *
 * 做法：把 useI18n({ messages: {...} }) 里的 messages 对象字面量取出来直接 eval
 * （这些词典是纯字面量，没有变量引用），再据此递归展开 a.b.c 形式的 key 集合。
 * 比手写花括号扫描可靠得多——三目表达式、数组、嵌套对象都不会误判。
 *
 * 用法：node scripts/check-i18n-keys.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const ROOT = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const SRC = join(ROOT, 'src')

const FILES = []
function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p)
    else if (/\.(vue|js)$/.test(name)) FILES.push(p)
  }
}
walk(SRC)

/** 取出 text 中 start（指向 '{'）开始的花括号配对内容（跳过字符串与注释） */
function blockAt(text, start) {
  let depth = 0
  let inStr = null
  for (let i = start; i < text.length; i++) {
    const c = text[i]
    if (inStr) {
      if (c === '\\') i++
      else if (c === inStr) inStr = null
      continue
    }
    if (c === "'" || c === '"' || c === '`') { inStr = c; continue }
    if (c === '/' && text[i + 1] === '/') { while (i < text.length && text[i] !== '\n') i++; continue }
    if (c === '/' && text[i + 1] === '*') { i = text.indexOf('*/', i) + 1; continue }
    if (c === '{') depth++
    else if (c === '}') { depth--; if (depth === 0) return text.slice(start + 1, i) }
  }
  return text.slice(start + 1)
}

/** 递归展开词典为 a.b.c 集合 */
function flatten(obj, prefix, out) {
  for (const [k, v] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, path, out)
    else out.add(path)
  }
}

/** 从一份源码里取出所有 locale 词典的 key 集合（eval 失败则返回 null 表示无法解析） */
function keysOf(text) {
  const out = new Set()
  const idx = text.indexOf('messages:')
  if (idx < 0) return out
  const open = text.indexOf('{', idx)
  if (open < 0) return out
  const inner = blockAt(text, open)
  let obj
  try {
    // eslint-disable-next-line no-new-func
    obj = new Function(`return ({${inner}})`).call(null)
  } catch {
    return null
  }
  for (const v of Object.values(obj)) {
    if (v && typeof v === 'object') flatten(v, '', out)
  }
  return out
}

const globalKeys = keysOf(readFileSync(join(SRC, 'i18n', 'index.js'), 'utf8')) || new Set()
let problems = 0
let unparsed = 0

for (const file of FILES) {
  const text = readFileSync(file, 'utf8')
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  const defined = keysOf(text)
  if (defined === null) {
    unparsed++
    console.log(`${rel}  ⚠ 词典无法静态解析（含变量引用？），已跳过`)
    continue
  }
  if (defined.size === 0) continue // 没有局部词典的模块（工具/入口）不参与检查

  const used = new Map()
  const re = /(?<![\w$.])\$?t\(\s*(['"])([^'"]+)\1/g
  let m
  while ((m = re.exec(text)) !== null) {
    const key = m[2]
    if (!used.has(key)) used.set(key, text.slice(0, m.index).split('\n').length)
  }
  const missing = []
  for (const [key, line] of used) {
    if (defined.has(key) || globalKeys.has(key)) continue
    missing.push({ key, line })
  }
  if (missing.length) {
    problems += missing.length
    console.log(`${rel}  （局部词典 ${defined.size} 键）`)
    for (const x of missing) console.log(`    :${x.line}  未定义 key  →  ${x.key}`)
  }
}

if (unparsed) console.log(`\n（${unparsed} 个文件跳过）`)
console.log(problems === 0 ? '\n✓ 未发现使用了但未定义的 i18n key' : `\n✗ 共 ${problems} 个未定义 key`)
process.exit(problems === 0 ? 0 : 1)
