/**
 * 检查各组件内联 i18n 词典的重复键。
 * 用法：node scripts/check-i18n-dup.mjs
 * 重复键会让后一个同名键静默覆盖前一个（vue-i18n 不报错），只能靠静态检查发现。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const ROOT = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const SRC = join(ROOT, 'src')
const FILES = []

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p)
    else if (/\.(vue|js|ts|mjs)$/.test(name)) FILES.push(p)
  }
}
walk(SRC)

/** 从 start（指向 '{'）起做花括号配对，返回块内文本 */
function blockAt(text, start) {
  let depth = 0
  let i = start
  let inStr = null
  for (; i < text.length; i++) {
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

/** 收集块内第一层的 `key:` 出现位置（按行首缩进判断层级，稳健且够用） */
function topLevelKeys(block) {
  const keys = []
  let depth = 0
  let inStr = null
  let lineStart = 0
  let token = ''
  let lineIndent = 0
  const lines = block.split('\n')
  for (const raw of lines) {
    const line = raw
    const trimmed = line.trim()
    // 只统计“本层”键：整块第一层的键在消息对象里缩进最浅
    if (/^(['"]?[\w$-]+['"]?)\s*:/.test(trimmed)) {
      const m = trimmed.match(/^(['"]?[\w$-]+['"]?)\s*:/)
      const key = m[1].replace(/^['"]|['"]$/g, '')
      // 计算该键在块内的花括号深度
      keys.push({ key, depth, indent: line.length - line.trimStart().length, line })
    }
    for (let i = 0; i < line.length; i++) {
      const c = line[i]
      if (inStr) {
        if (c === '\\') i++
        else if (c === inStr) inStr = null
        continue
      }
      if (c === "'" || c === '"' || c === '`') inStr = c
      else if (c === '{') depth++
      else if (c === '}') depth--
    }
  }
  void lineStart; void token; void lineIndent; void lines
  return keys
}

let problems = 0
for (const file of FILES) {
  const text = readFileSync(file, 'utf8')
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  const re = /(['"])([a-zA-Z-]{2,10})\1\s*:\s*\{/g
  let m
  while ((m = re.exec(text)) !== null) {
    const locale = m[2]
    if (!/^(zh|en)[-A-Za-z]*$/.test(locale)) continue
    const open = text.indexOf('{', m.index + m[0].length - 1)
    const block = blockAt(text, open)
    const entries = topLevelKeys(block)
    if (entries.length === 0) continue
    const minIndent = Math.min(...entries.map((e) => e.indent))
    const seen = new Map()
    for (const e of entries) {
      if (e.indent !== minIndent) continue
      if (!e.key || /^(if|else|for|return|function|const|let|var)$/.test(e.key)) continue
      if (seen.has(e.key)) {
        console.log(`${rel} [${locale}] 重复键 "${e.key}"`)
        console.log(`    先: ${seen.get(e.key).trim().slice(0, 100)}`)
        console.log(`    后: ${e.line.trim().slice(0, 100)}`)
        problems++
      } else {
        seen.set(e.key, e.line)
      }
    }
  }
}

console.log(problems === 0 ? '\n✓ 未发现 i18n 重复键' : `\n✗ 共 ${problems} 处重复键`)
process.exit(problems === 0 ? 0 : 1)
