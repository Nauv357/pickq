/**
 * 知识点标签弹窗（学习路径引擎阶段 0）冒烟：Playwright + 本机 Chrome + 假后端。
 *
 * 这一版按用户实测反馈重写，覆盖点都在用户明确抱怨过的地方：
 *   1. 覆盖口径必须自洽：已确认 / 待确认 / 未匹配 **三段不重叠、相加 = 总题数**，
 *      另单列"可用于判定掌握"，且概览数字必须等于清单条数（用户看到过"26 vs 16"）；
 *   2. 清单以**题**为单位（题号 + 题型 + 题干 + 状态 + 当前标签），一眼能看出是哪一题；
 *   3. **标签就地可改**：行内多选下拉直接改 → 发出 action=set（不必跳编辑器）；
 *   4. 行内还能：确认 / 丢弃建议 / 看题干（就地展开，不跳转）/ 详情（可选跳转）；
 *   5. 状态筛选是"可点击的数字"（不是页签），并可按知识点筛选（下拉里带题量，缺口一眼可见）；
 *   6. 勾选后批量：设为知识点 / 确认 / 丢弃；
 *   7. 「分析并标注」按批发请求（maxAiCalls 有值、includeUntagged=true）。
 *
 * 用法：node scripts/smoke-skills.mjs [--url http://localhost:5199]
 */
import { chromium } from 'playwright-core'
import { existsSync } from 'node:fs'

const argv = process.argv.slice(2)
const urlArg = argv.indexOf('--url')
const BASE = urlArg >= 0 ? argv[urlArg + 1] : 'http://localhost:5199'
const vpArg = argv.indexOf('--viewport')
const [VW, VH] = (vpArg >= 0 ? argv[vpArg + 1] : '1440x900').split('x').map(Number)

const CHROME = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
].find((p) => existsSync(p))
if (!CHROME) {
  console.error('找不到 Chrome / Edge')
  process.exit(2)
}

let pass = 0
let fail = 0
function check(name, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`  ✓ ${name}`)
  } else {
    fail++
    console.log(`  ✗ ${name}${detail ? '  →  ' + detail : ''}`)
  }
}

/* ---------------- 假后端 ---------------- */
const calls = []
const TEMPLATES = [
  { templateId: 'official.civil-service', name: '公务员行测（通用）', version: '2026.09', graphVersion: '2026.09-aabb', nodeCount: 3, stageCount: 2 },
  { templateId: 'official.cs-ai-fullstack', name: 'AI 时代的现代化全栈', version: '2026.09', graphVersion: '2026.09-ccdd', nodeCount: 30, stageCount: 7 }
]
const NODES = [
  { nodeId: 'gk.pd.figure.num', name: '图形推理·数量与属性', stageId: 'm4', stageName: '判断推理', level: 3, weight: 1.2, optional: false, keywords: ['笔画数'], prereq: [] },
  { nodeId: 'gk.pd.argue', name: '逻辑判断·加强削弱', stageId: 'm4', stageName: '判断推理', level: 3, weight: 1.2, optional: false, keywords: [], prereq: [] },
  { nodeId: 'gk.zl.growth', name: '资料分析·增长', stageId: 'm5', stageName: '资料分析', level: 3, weight: 1.2, optional: false, keywords: [], prereq: [] }
]
const COUNTS = { total: 164, confirmed: 100, pending: 12, untagged: 52, usable: 108 }
const COVERAGE = {
  templateId: 'official.civil-service',
  graphVersion: '2026.09-aabb',
  totalQuestions: COUNTS.total,
  confirmedQuestions: COUNTS.confirmed,
  pendingQuestions: COUNTS.pending,
  untaggedQuestions: COUNTS.untagged,
  usableQuestions: COUNTS.usable,
  nodes: [
    { nodeId: 'gk.pd.figure.num', name: '图形推理·数量与属性', stageName: '判断推理', questionCount: 24, evidenceEnough: true, confirmed: true },
    { nodeId: 'gk.pd.argue', name: '逻辑判断·加强削弱', stageName: '判断推理', questionCount: 2, evidenceEnough: false, confirmed: false },
    { nodeId: 'gk.zl.growth', name: '资料分析·增长', stageName: '资料分析', questionCount: 0, evidenceEnough: false, confirmed: false }
  ]
}
const ROWS = [
  {
    questionId: 11, questionNumber: 11, type: 'SINGLE', preview: '从所给的四个选项中，选择最合适的一个填入问号处（笔画数）。', status: 'pending',
    tags: [{ nodeId: 'gk.pd.figure.num', name: '图形推理·数量与属性', source: 'ai', confidence: 0.91, confirmed: false, origin: 'ai-direct' }]
  },
  {
    questionId: 12, questionNumber: 12, type: 'SINGLE', preview: '以下哪项如果为真，最能削弱上述结论？', status: 'pending',
    tags: [{ nodeId: 'gk.pd.argue', name: '逻辑判断·加强削弱', source: 'ai', confidence: 0.62, confirmed: false, origin: 'ai-direct' }]
  },
  {
    questionId: 31, questionNumber: 31, type: 'SINGLE', preview: '某市 2023 年 GDP 增长率为多少？', status: 'untagged', tags: []
  },
  {
    questionId: 41, questionNumber: 41, type: 'SINGLE', preview: '根据《民法典》的规定，下列说法正确的是：', status: 'confirmed',
    tags: [{ nodeId: 'gk.pd.figure.num', name: '图形推理·数量与属性', source: 'user', confidence: 1, confirmed: true, origin: 'manual' }]
  }
]

function reviewPage(status) {
  const records = status === 'all' ? ROWS : ROWS.filter((r) => r.status === status)
  return { total: records.length, page: 1, size: 50, counts: COUNTS, records }
}

function questionDetail(id) {
  return {
    questionId: Number(id), bankId: 1, volume: 1, questionType: 'SINGLE', questionNumber: Number(id),
    content: `冒烟题干 #${id}`, options: [{ key: 'A', text: '选项一' }, { key: 'B', text: '选项二' }],
    answerKeys: ['A'], score: 1, topic: '', category: '', answerText: '', analysis: '这是解析内容',
    materialId: null, referenceAnswer: '', images: []
  }
}

function stub(pathname) {
  if (pathname === '/api/banks/1') return { bankId: 1, name: '冒烟题库', description: 'e2e', questionCount: 4, version: '1.0.0', createdAt: '2026-01-01T00:00:00' }
  if (pathname === '/api/banks/1/questions') return { records: ROWS, total: ROWS.length }
  if (pathname === '/api/banks/1/question-nav') return []
  if (pathname === '/api/banks/1/progress') return { totalQuestions: 164, answeredCount: 10, correctCount: 8, recordsCount: 10, accuracy: 0.8 }
  if (pathname === '/api/banks/1/materials') return []
  if (pathname === '/api/skills/templates') return TEMPLATES
  if (pathname.startsWith('/api/skills/templates/') && pathname.endsWith('/sync')) return { nodesWritten: 3 }
  if (pathname.startsWith('/api/skills/templates/')) {
    const id = decodeURIComponent(pathname.split('/').pop())
    const tpl = TEMPLATES.find((t) => t.templateId === id) || TEMPLATES[0]
    return { ...tpl, nodes: NODES }
  }
  if (pathname === '/api/banks/1/skills/coverage') return COVERAGE
  if (pathname === '/api/banks/1/skills/suggest') {
    return { templateId: 'official.civil-service', graphVersion: 'x', groupCount: 0, mappedGroups: 0, cachedGroups: 0, aiCalls: 5, taggedQuestions: 52, withoutUsableTag: 0, truncated: false, message: '分析完成' }
  }
  if (pathname === '/api/banks/1/skills/apply') return { affected: 1 }
  const q = pathname.match(/^\/api\/questions\/(\d+)$/)
  if (q) return questionDetail(q[1])
  if (/^\/api\/questions\/\d+\/skills$/.test(pathname)) return []
  if (pathname.startsWith('/api/ai-import/jobs')) return []
  if (pathname === '/api/home/overview') return { bankCount: 1, questionCount: 4, dueToday: 0, wrongCount: 0, recent: null }
  return { records: [], total: 0 }
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: VW, height: VH } })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))

await page.route(
  (url) => url.pathname.startsWith('/api/'),
  async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    const method = req.method()
    calls.push({ method, path: p, body: req.postData() || '', query: url.search })
    const data = p === '/api/banks/1/skills/questions' ? reviewPage(url.searchParams.get('status') || 'all') : stub(p)
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data }) })
  }
)

/** 等页面上的 element-plus 提示全部消失：避免读到上一步的旧 toast */
async function waitToastsGone() {
  await page
    .waitForFunction(() => document.querySelectorAll('.el-message').length === 0, null, { timeout: 8000 })
    .catch(() => {})
}
const flat = (s) => String(s).replace(/\s+/g, ' ')
const textOf = async (loc) => flat(await loc.innerText())
const lastApply = (pred) => [...calls].reverse().find((c) => c.path === '/api/banks/1/skills/apply' && pred(c.body))
/** 多选下拉选完不会自动收起（Element Plus 行为），按 Esc 关掉，否则它会挡住下一次点击 */
async function closeDropdown() {
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
}

console.log('打开题库详情 → 「知识点」')
await page.goto(`${BASE}/banks/1`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1500)

const entry = page.locator('button', { hasText: '知识点' }).first()
check('详情页有「知识点」入口', (await entry.count()) > 0)
await entry.click()
await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
check('点开是独立弹窗', (await page.locator('.el-dialog .skill-toolbar').count()) === 1)

/* ---------- 1. 覆盖口径 ---------- */
const coverText = await textOf(page.locator('.skill-cover'))
check('三段口径齐全（已确认/待确认/未匹配）', /已确认/.test(coverText) && /待确认/.test(coverText) && /未匹配/.test(coverText), coverText)
check('三段相加 = 总题数（100+12+52=164）', /100/.test(coverText) && /12/.test(coverText) && /52/.test(coverText) && /164/.test(coverText), coverText)
check('单列「可用于判定掌握」（108）', /108/.test(coverText), coverText)
check('进度条按已确认占比（61%）', (await page.locator('.skill-bar-ok').evaluate((el) => el.style.width)) === '61%')

/* ---------- 2. 状态筛选器（可点击的数字，不是页签） ---------- */
const chips = page.locator('.skill-chip')
check('状态筛选有 4 个（全部/待确认/未匹配/已确认）', (await chips.count()) === 4, String(await chips.count()))
check('筛选器带计数（未匹配 52）', (await textOf(chips.nth(2))).includes('52'), await textOf(chips.nth(2)))
await chips.nth(1).click()
await page.waitForTimeout(400)
const pendingReq = [...calls].reverse().find((c) => c.path === '/api/banks/1/skills/questions')
check('点「待确认」按 status=pending 重新取清单', pendingReq?.query.includes('status=pending'), pendingReq?.query)
check('清单只剩待确认的题（2 行）', (await page.locator('.skill-row').count()) === 2, String(await page.locator('.skill-row').count()))

/* ---------- 3. 清单以题为单位 + 标签就地改 ---------- */
const row0 = page.locator('.skill-row').first()
const row0Text = await textOf(row0)
check('行内有题号 + 状态 + 题干', row0Text.includes('11') && row0Text.includes('待确认') && row0Text.includes('问号处'), row0Text.slice(0, 120))
check('行内显示当前标签与来源（AI 0.91）', row0Text.includes('图形推理·数量与属性') && /AI/.test(row0Text), row0Text.slice(0, 140))
check('未匹配之外的行有「确认」「丢弃建议」', (await row0.locator('button', { hasText: '确认' }).count()) > 0 && (await row0.locator('button', { hasText: '丢弃建议' }).count()) > 0)

// 就地改标签：改成另一个知识点（下拉就在行里，不用跳转）。
// 注意：多选下拉里已选的标签占了左侧，点中间会点到标签上的 ✕ —— 要点右侧的输入区。
async function openRowTagSelect(row) {
  const box = await row.locator('.skill-tag-select .el-select__wrapper').boundingBox()
  await page.mouse.click(box.x + box.width - 10, box.y + box.height / 2)
}
await openRowTagSelect(row0)
const option = page.locator('.el-select-dropdown__item:visible', { hasText: '资料分析·增长' }).first()
await option.waitFor({ state: 'visible', timeout: 8000 })
await option.click()
await closeDropdown()
await page.waitForTimeout(600)
const setCall = lastApply((b) => b.includes('"set"'))
check(
  '行内改标签发出 action=set + questionIds=[11]（不需要打开题目）',
  !!setCall && setCall.body.includes('[11]') && setCall.body.includes('gk.zl.growth'),
  setCall?.body || '(无请求)'
)

await waitToastsGone()
await row0.locator('button', { hasText: '确认' }).first().click()
await page.waitForTimeout(500)
const confirmCall = lastApply((b) => b.includes('confirm'))
check('行内「确认」发出 action=confirm + questionIds', !!confirmCall && confirmCall.body.includes('[11]'), confirmCall?.body || '(无请求)')

await waitToastsGone()
await row0.locator('button', { hasText: '丢弃建议' }).first().click()
await page.waitForTimeout(500)
const rejectCall = lastApply((b) => b.includes('reject'))
check('行内「丢弃建议」发出 action=reject + questionIds', !!rejectCall && rejectCall.body.includes('[11]'), rejectCall?.body || '(无请求)')

/* ---------- 4. 就地展开题干（不跳转） ---------- */
await waitToastsGone()
await row0.locator('button', { hasText: '看题干' }).click()
await page.waitForTimeout(600)
const detailText = await textOf(page.locator('.skill-detail').first())
check('「看题干」就地展开（选项/答案/解析）', detailText.includes('选项一') && detailText.includes('解析'), detailText.slice(0, 140))
check('展开不跳转（弹窗仍在）', await page.locator('.el-dialog .skill-toolbar').isVisible())

/* ---------- 5. 按知识点筛选（下拉带题量，缺口可见） ---------- */
await waitToastsGone()
await page.locator('.skill-node-filter').click()
const nodeOpts = await page.locator('.el-select-dropdown__item:visible').allInnerTexts()
check('知识点下拉带题量', nodeOpts.some((x) => /24/.test(x)), nodeOpts.join(' | ').slice(0, 140))
check('没有题的知识点在下拉里标出来（缺口）', nodeOpts.some((x) => /没有题/.test(x)), nodeOpts.join(' | ').slice(0, 160))
await page.locator('.el-select-dropdown__item:visible', { hasText: '图形推理·数量与属性' }).first().click()
await closeDropdown()
await page.waitForTimeout(500)
const nodeReq = [...calls].reverse().find((c) => c.path === '/api/banks/1/skills/questions')
check('按知识点筛选带上 nodeId', nodeReq?.query.includes('nodeId=gk.pd.figure.num'), nodeReq?.query)
check('缺口提示可见', /个知识点你还没有题/.test(await textOf(page.locator('.skill-gap'))), await textOf(page.locator('.skill-gap')))

/* ---------- 6. 批量 ---------- */
await waitToastsGone()
await chips.nth(0).click() // 全部
await page.waitForTimeout(500)
await page.locator('.skill-row').nth(0).locator('.el-checkbox').click()
await page.locator('.skill-row').nth(2).locator('.el-checkbox').click()
await page.waitForTimeout(200)
const batchText = await textOf(page.locator('.skill-batch'))
check('勾选后出现批量条并显示数量', batchText.includes('已选 2 题'), batchText.slice(0, 80))
await page.locator('.skill-batch .el-select').click()
await page.locator('.el-select-dropdown__item:visible', { hasText: '资料分析·增长' }).first().click()
await closeDropdown()
await page.waitForTimeout(200)
await page.locator('.skill-batch button', { hasText: '设为知识点' }).click()
await page.waitForTimeout(600)
const batchCall = lastApply((b) => b.includes('"set"') && b.includes('gk.zl.growth'))
check('批量「设为知识点」一次带上多题', !!batchCall && /\[11,\s*31\]/.test(batchCall.body), batchCall?.body || '(无请求)')

/* ---------- 7. 详情（可选跳转）与按批分析 ---------- */
await waitToastsGone()
await page.locator('.skill-row').first().locator('button', { hasText: '详情' }).click()
await page.waitForTimeout(800)
check('「详情」关掉弹窗并跳题目编辑器', !(await page.locator('.el-dialog .skill-toolbar').isVisible().catch(() => false)))

await page.locator('.editor-dialog .panel-head button.icon-btn[title^="关闭"]').first().click().catch(() => {})
await page.waitForTimeout(600)
await page.locator('button', { hasText: '知识点' }).first().click()
await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
await waitToastsGone()
await page.locator('.skill-toolbar-right button').first().click()
await page.waitForSelector('.el-message--success', { timeout: 20000 })
const suggestCall = calls.find((c) => c.path === '/api/banks/1/skills/suggest')
check('「分析并标注」按批发请求（maxAiCalls 有值）', !!suggestCall && /maxAiCalls=\d+/.test(suggestCall.query), suggestCall?.query || '(无请求)')
check('分析请求带 includeUntagged=true', !!suggestCall && suggestCall.query.includes('includeUntagged=true'), suggestCall?.query)

check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
