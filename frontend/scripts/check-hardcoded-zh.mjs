/**
 * 统计模板里未走 i18n 的硬编码中文（英文界面下会直接显示中文）。
 * 用法：node scripts/check-hardcoded-zh.mjs [--list] [文件过滤子串]
 * 判定：<template> 段内、非注释、去掉 {{ ... }} 插值与 t('...') 调用后仍含中文的行。
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
    else if (name.endsWith('.vue')) FILES.push(p)
  }
}
walk(SRC)

const list = process.argv.includes('--list')
const filter = process.argv.slice(2).find((a) => !a.startsWith('--')) || ''
const ZH = /[\u4e00-\u9fff]/

const report = []
for (const file of FILES) {
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  if (filter && !rel.includes(filter)) continue
  const lines = readFileSync(file, 'utf8').split('\n')
  let depth = 0
  let inHtmlComment = false
  let hits = 0
  const samples = []
  lines.forEach((line, i) => {
    const trimmed = line.trim()
    // 按出现次数计数：同一行可能既开又合（<template #prefix>…</template>）
    depth += (line.match(/<template[ >]/g) || []).length - (line.match(/<\/template>/g) || []).length
    if (!inHtmlComment && depth <= 0) return
    const opens = (line.match(/<!--/g) || []).length
    const closes = (line.match(/-->/g) || []).length
    const startedInComment = inHtmlComment
    if (opens > closes) inHtmlComment = true
    else if (closes > opens) inHtmlComment = false
    if (startedInComment) return
    let code = line.replace(/<!--.*?-->/g, '')
    code = code.replace(/\{\{[^}]*\}\}/g, '') // 去掉插值
    code = code.replace(/t\(\s*'[^']*'[^)]*\)/g, '') // 去掉 t() 调用
    code = code.replace(/t\(\s*"[^"]*"[^)]*\)/g, '')
    if (ZH.test(code)) {
      hits++
      if (samples.length < 6) samples.push(`${i + 1}: ${trimmed.slice(0, 110)}`)
    }
  })
  if (hits) report.push({ rel, hits, samples })
}

report.sort((a, b) => b.hits - a.hits)
for (const r of report) {
  console.log(`${String(r.hits).padStart(4)}  ${r.rel}`)
  if (list) for (const s of r.samples) console.log(`        ${s}`)
}
const total = report.reduce((a, r) => a + r.hits, 0)
console.log(`\n模板硬编码中文行合计 ${total} 行，涉及 ${report.length} 个文件`)
