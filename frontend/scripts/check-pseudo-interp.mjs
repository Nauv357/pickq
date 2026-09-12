/**
 * 检查 Vue 模板里属性值中的伪插值：attr="{{ ... }}"。
 * Vue 3 不支持属性值内的 mustache，这种写法会把字面量 `{{ t('x') }}` 原样渲染给用户。
 * 正确写法：:attr="t('x')"。
 * 用法：node scripts/check-pseudo-interp.mjs
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

let problems = 0
for (const file of FILES) {
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  const lines = readFileSync(file, 'utf8').split('\n')
  // 定位 <template> 段（只查模板；script/style 里的字符串不算）。
  // 注意：SFC 根 <template> 之内还有大量嵌套 <template #footer> 等，必须按深度计数，
  // 否则遇到第一个 </template> 就会误判为模板结束。
  let depth = 0
  let inHtmlComment = false
  lines.forEach((line, i) => {
    const lineNo = i + 1
    const trimmed = line.trim()
    // 按出现次数计数：同一行可能既开又合（<template #prefix>…</template>）
    depth += (line.match(/<template[ >]/g) || []).length - (line.match(/<\/template>/g) || []).length
    if (!inHtmlComment && depth <= 0) return
    // HTML 注释跨行跟踪
    const opens = (line.match(/<!--/g) || []).length
    const closes = (line.match(/-->/g) || []).length
    const startedInComment = inHtmlComment
    if (opens > closes) inHtmlComment = true
    else if (closes > opens) inHtmlComment = false
    if (startedInComment) return
    // 去掉行尾 HTML 注释后检查属性
    const code = line.replace(/<!--.*?-->/g, '')
    const re = /(:?[\w:@.\-\[\]]+)="([^"]*\{\{[^"]*)"/g
    let m
    while ((m = re.exec(code)) !== null) {
      if (m[1].startsWith(':') && !m[1].startsWith('::')) continue // 绑定属性：值就是 JS 表达式
      console.log(`${rel}:${lineNo} 属性 ${m[1]}= 内含伪插值`)
      console.log(`    ${m[0].slice(0, 140)}`)
      problems++
    }
    // 双冒号属性（::title="..."）是 :title 的手误：Vue 当普通属性编译，tooltip 静默失效
    const dbl = /(^|\s)(::[\w.\-]+)=/g
    while ((m = dbl.exec(code)) !== null) {
      console.log(`${rel}:${lineNo} 双冒号属性 ${m[2]}= （应为单冒号绑定）`)
      console.log(`    ${line.trim().slice(0, 140)}`)
      problems++
    }
  })
}
console.log(problems === 0 ? '\n✓ 未发现属性值伪插值' : `\n✗ 共 ${problems} 处伪插值`)
process.exit(problems === 0 ? 0 : 1)
