/**
 * 富文本渲染：文本 + [图片:文件名] 标记 → <img src="/api/banks/{bankId}/images/{name}">
 * + <table>…</table>（MinerU 表格 HTML）→ 真实表格（白名单渲染，防 XSS）
 * + LaTeX 公式（MinerU 输出如 \frac { 5 } { 1 2 }，无 $ 定界）→ KaTeX 渲染（失败回退源码）。
 * 其余文本做 HTML 转义（防 XSS），换行转 <br>。
 * 文件名由后端生成（含日期目录如 260829/ab12.png），直接拼接进 URL。
 */
import katex from 'katex'

/** 表格白名单标签（MinerU 实测只含 table/tr/td；容错保留 th/tbody 等） */
const TABLE_TAG_RE = /^(table|tr|td|th|tbody|thead|tfoot|caption)$/i
/** 表格属性白名单（MinerU 偶发合并单元格） */
const TABLE_ATTR_RE = /^(colspan|rowspan)$/i
/** 危险标签：整体删除（含内容） */
const DANGEROUS_TAG_RE =
  /<(script|style|iframe|object|embed|link|meta)[\s\S]*?<\/\1>|<(script|style|iframe|object|embed|link|meta)[^>]*\/?>/gi
/**
 * LaTeX 命令片段（MinerU 输出带空格、无 $ 定界）：\命令 + 0..n 个 {参数} 组
 * 如 \frac { 5 } { 1 2 }、\sqrt{3}、\times。^/_ 作为 \^ \_ 转义命令被覆盖。
 * 含捕获组：split 时匹配片段保留在结果中（奇数位 = 公式）。
 */
const LATEX_FRAGMENT_RE = /(\\[a-zA-Z]+(?:\s*\{[^{}]*\})*)/g

/** $...$ 定界公式（MD 模板/模型输出风格，如 $mgh-\frac{1}{2}mv^2$）→ 整段 KaTeX */
const DOLLAR_LATEX_RE = /(\$[^$]+\$)/g

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/**
 * 表格块白名单清洗：
 * - 删除危险标签（script/style/iframe 等，连同内容）；
 * - 白名单标签（table/tr/td/th…）保留，属性仅保留 colspan/rowspan；
 * - 其余标签剥除（标签丢弃、内容保留为文本）。
 */
export function sanitizeTableHtml(html) {
  return String(html)
    .replace(DANGEROUS_TAG_RE, '')
    .replace(/<[^>]+>/g, (tag) => {
      const m = tag.match(/^<\s*(\/?)\s*([a-zA-Z0-9]+)([^>]*)>$/)
      if (!m) return ''
      const name = m[2].toLowerCase()
      if (!TABLE_TAG_RE.test(name)) return '' // 非白名单标签：剥除（内容保留）
      if (m[1]) return `</${name}>`
      const kept = []
      const attrs = m[3] || ''
      for (const a of attrs.matchAll(/([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) {
        if (TABLE_ATTR_RE.test(a[1])) {
          kept.push(`${a[1]}="${(a[2] ?? a[3] ?? a[4] ?? '').replace(/"/g, '&quot;')}"`)
        }
      }
      return `<${name}${kept.length ? ' ' + kept.join(' ') : ''}>`
    })
}

/** LaTeX 片段 → KaTeX HTML；渲染失败或未知命令（katex-error / 红色 #cc0000 错误输出）→ 回退源码（等宽，不报错不空白） */
function katexHtml(latex) {
  try {
    const html = katex.renderToString(latex, { throwOnError: false, strict: false, displayMode: false })
    if (html.includes('katex-error') || html.includes('#cc0000')) {
      return `<span class="latex-fallback">${escapeHtml(latex)}</span>`
    }
    return html
  } catch (e) {
    return `<span class="latex-fallback">${escapeHtml(latex)}</span>`
  }
}

/** 在已转义文本中把 LaTeX 命令片段渲染为公式（MinerU 风格，无 $ 定界；其余文本原样返回） */
function renderLatexFragments(escaped) {
  const parts = escaped.split(LATEX_FRAGMENT_RE)
  if (parts.length === 1) return escaped
  let out = ''
  for (let i = 0; i < parts.length; i++) {
    out += i % 2 === 1 ? katexHtml(parts[i]) : parts[i]
  }
  return out
}

/** 反转义（escapeHtml 已转义 & < > "；LaTeX 源码中的 &（对齐符）等需还原后交给 KaTeX） */
function unescapeHtml(s) {
  return s.replace(/&quot;/g, '"').replace(/&gt;/g, '>').replace(/&lt;/g, '<').replace(/&amp;/g, '&')
}

/**
 * 在已转义文本中渲染 LaTeX 公式：
 * 1) 先按 $...$ 定界拆分（MD 模板/模型输出风格）——定界内整段交给 KaTeX；
 * 2) 定界外的剩余文本再按 MinerU 无定界命令片段渲染（兼容旧路径）。
 */
function renderLatex(escaped) {
  const parts = escaped.split(DOLLAR_LATEX_RE)
  if (parts.length === 1) return renderLatexFragments(escaped)
  let out = ''
  for (let i = 0; i < parts.length; i++) {
    if (i % 2 === 1) {
      out += katexHtml(unescapeHtml(parts[i].slice(1, -1)))
    } else {
      out += renderLatexFragments(parts[i])
    }
  }
  return out
}

/**
 * 图片名白名单（服务端生成的引用名形如 260829/ab12.png，只含日期目录 + 字母数字点横线斜杠）。
 * renderImages 可能作用于未转义的表格块文本（sanitizeTableHtml 不清洗单元格文本），
 * 恶意内容可构造 [图片:a" onerror="alert(1)] 逃逸 src 属性 → 属性注入 XSS。
 * 因此这里做双保险：① 名字必须匹配白名单；② 拼进 HTML 前 encodeURI（引号/尖括号等全部转义）。
 * 不合法名字保留原标记文本（在转义分支会按纯文本展示，不渲染图片）。
 */
const IMAGE_NAME_RE = /^[a-zA-Z0-9._/-]+$/

/** 在已转义文本中把 [图片:name] 替换为 <img>（name 已 HTML 转义，安全） */
function renderImages(escaped, bankId) {
  return escaped.replace(/\[图片:([^\]]+)\]/g, (m, name) => {
    const n = name.trim()
    if (!n || !IMAGE_NAME_RE.test(n)) return m
    return `<img class="rich-img" src="/api/banks/${bankId}/images/${encodeURI(n)}" alt="图片" loading="lazy">`
  })
}

/**
 * 富文本 → HTML：文本（转义）+ LaTeX（KaTeX）+ 图片（[图片:name] → img）+ 表格（<table> → 白名单真实表格）混排。
 * 表格块包 `.rich-table-wrap`（横向滚动容器，样式在 main.css 全局定义）。
 * 渲染顺序：文本块 = 转义 → LaTeX 公式 → 图片 → 换行（公式片段含图片标记时由 KaTeX 容错回退）。
 */
export function richTextToHtml(text, bankId) {
  if (!text) return ''
  const segments = String(text).split(/(<table[\s>][\s\S]*?<\/table>)/gi)
  let html = ''
  for (const seg of segments) {
    if (!seg) continue
    if (/^<table[\s>]/i.test(seg)) {
      // 表格块：白名单清洗后渲染（表格内 [图片:name] 同样替换为 img）
      const cleaned = sanitizeTableHtml(seg)
      html += `<div class="rich-table-wrap">${renderImages(cleaned, bankId)}</div>`
    } else {
      // 普通文本：转义 → LaTeX 公式 → 图片 → 换行
      html += renderImages(renderLatex(escapeHtml(seg)), bankId).replace(/\n/g, '<br>')
    }
  }
  return html
}

/** 判断文本是否包含图片标记 */
export function hasImageMarker(text) {
  return /\[图片:([^\]]+)\]/.test(text || '')
}

/**
 * 纯文本 → HTML：转义 + LaTeX 公式渲染（$...$ 定界 + MinerU 无定界片段），不处理图片/表格。
 * 供需要自定义图片渲染的场合使用（如 AI 导入预览页：图片标记是 [图片N] 任务临时图）。
 */
export function latexOnlyHtml(text) {
  if (!text) return ''
  return renderLatex(escapeHtml(String(text))).replace(/\n/g, '<br>')
}

/** 判断文本是否包含 LaTeX 命令片段 */
export function hasLatex(text) {
  return /\\[a-zA-Z]+/.test(text || '')
}
