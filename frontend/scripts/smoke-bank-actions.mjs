/**
 * 题库列表 / 题目列表的"操作菜单 + 批量管理 + 导出所选"冒烟（Playwright + 本机 Chrome，不需要后端）。
 *
 * 覆盖点（都是这轮改动的真实风险处）：
 *   1. 系统右键菜单被屏蔽（卡片上 preventDefault 生效），输入框内保留；
 *   2. 卡片「…」与右键打开同一份菜单，菜单项齐备、Esc 可关；
 *   3. 卡片显示「共 N 题 · 已做 M」；
 *   4. 批量管理模式：勾选、Shift 连选、底部条计数；批量删除会逐个发 DELETE；
 *   5. 改名称与描述的弹窗发 PUT；导出所选逐个发 POST /exports/export；
 *   6. 题目行右键菜单齐备；选择条有「导出所选」「删除所选」；
 *   7. 「导出所选」打开导出弹窗且范围默认=已勾选。
 *
 * 用法：node scripts/smoke-bank-actions.mjs [--url http://localhost:5199]
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
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
].find((p) => existsSync(p))
if (!CHROME) {
  console.error('找不到 Chrome / Edge')
  process.exit(2)
}

/* ---------------- 假后端（记录写操作，供断言） ---------------- */
const calls = []
const BANKS = [
  { id: 1, name: '冒烟题库 A', description: 'e2e', version: '1.0.0', authorName: '', createdAt: '2026-01-01T10:00:00', questionCount: 3, answeredCount: 1 },
  { id: 2, name: '冒烟题库 B', description: '', version: '', authorName: '', createdAt: '2026-01-02T10:00:00', questionCount: 5, answeredCount: 0 },
  { id: 3, name: '冒烟题库 C', description: '', version: '', authorName: '', createdAt: '2026-01-03T10:00:00', questionCount: 0, answeredCount: 0 }
]
const Q = (n) => ({ questionId: n, questionNumber: n, questionType: 'SINGLE', content: `第 ${n} 题题干`, options: [{ key: 'A', text: 'A' }, { key: 'B', text: 'B' }], answerKeys: ['A'], score: 1 })
const ROWS = [101, 102, 103].map(Q)
// 做题格式（无答案）：会话详情/创建会话返回的题目
const SESSION_Q = (n) => ({ questionId: n, questionNumber: n, questionType: 'SINGLE', content: `第 ${n} 题题干`, options: [{ key: 'A', text: 'A' }, { key: 'B', text: 'B' }], score: 1, favorite: false, materialId: null })

function stub(pathname) {
  if (pathname === '/api/banks') return { records: BANKS, total: BANKS.length }
  if (pathname === '/api/home/overview') return { bankCount: 3, questionCount: 8, dueTotal: 0, wrongCount: 0, lastSession: null }
  if (/^\/api\/banks\/\d+$/.test(pathname)) return BANKS.find((b) => b.id === Number(pathname.split('/').pop())) || BANKS[0]
  if (/^\/api\/banks\/\d+\/questions$/.test(pathname)) return { records: ROWS, total: ROWS.length }
  if (/^\/api\/banks\/\d+\/question-nav$/.test(pathname)) return ROWS.map((q) => ({ questionId: q.questionId, questionType: q.questionType, questionNumber: q.questionNumber }))
  if (/^\/api\/banks\/\d+\/progress$/.test(pathname)) return { totalQuestions: 3, answeredCount: 1, correctCount: 1, recordsCount: 1, accuracy: 1 }
  if (/^\/api\/banks\/\d+\/materials$/.test(pathname)) return []
  if (/^\/api\/questions\/\d+$/.test(pathname)) return ROWS.find((q) => q.questionId === Number(pathname.split('/').pop())) || Q(999)
  // 配题（「开始练习」一键）：GET practice-plan → POST sessions(mode=PLAN, questionIds)
if (/^\/api\/banks\/\d+\/practice-plan$/.test(pathname)) {
  return {
    requested: 2, total: 2, wrongCount: 1, dueCount: 1, weakCount: 0, newCount: 0,
    weakNodeName: null, weakNodeQuestions: 0,
    items: [{ questionId: 101, reason: 'WRONG' }, { questionId: 102, reason: 'DUE' }],
    explain: '这 2 题：1 题是你之前做错的 · 1 题今天到期复习（题库共 3 题）'
  }
}
if (/^\/api\/banks\/\d+\/sessions$/.test(pathname)) return { sessionId: 77, total: 2, questions: [SESSION_Q(101), SESSION_Q(102)] }
// 我的笔记：列表 + 新建（只存本机，不随题库导出）
// 笔记不隶属于题库/题目：挂在哪里由 links 表达（一条笔记可以挂多处，也可以一个都不挂）
if (pathname === '/api/notes') {
  const today = new Date()
  const iso = (d) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19)
  const longText = '这段是长笔记，用来看列表里的折叠与展开：'.repeat(8)
  return {
    records: [
      {
        id: 1, content: '先看年份再动笔', source: 'ai', color: 'y',
        links: [{ type: 'question', targetId: 101, label: '冒烟题库 A · 第 101 题', bankId: 1, questionNumber: 101 }],
        createdAt: iso(today), updatedAt: iso(today)
      },
      {
        id: 2, content: longText, source: 'user', color: null,
        links: [{ type: 'bank', targetId: 1, label: '冒烟题库 A', bankId: 1, questionNumber: null }],
        createdAt: iso(new Date(today.getTime() - 40 * 86400000)), updatedAt: iso(new Date(today.getTime() - 40 * 86400000))
      }
    ],
    total: 2, page: 1, size: 20, pages: 1
  }
}
if (/^\/api\/notes\/\d+\/links$/.test(pathname)) return { id: 1, content: '先看年份再动笔', source: 'ai', color: null, links: [] }
if (/^\/api\/notes\/\d+\/color$/.test(pathname)) return { id: 1, content: '先看年份再动笔', source: 'user', color: 'g', links: [] }
if (/^\/api\/notes\/\d+$/.test(pathname)) return { id: 1 }
if (/^\/api\/questions\/\d+\/notes$/.test(pathname)) return []
if (pathname === '/api/sessions/77') {
  return {
    id: 77, bankId: 1, mode: 'PLAN', questionCount: 2, answeredCount: 0, correctCount: 0,
    totalScore: 0, maxScore: 2, totalSeconds: 0, status: 'IN_PROGRESS',
    createdAt: '2026-01-01T10:00:00', finishedAt: null, questions: [SESSION_Q(101), SESSION_Q(102)]
  }
}
if (pathname.startsWith('/api/ai-import/jobs')) return []
  if (pathname === '/api/exports/prefs') return { lastDir: 'D:\\题库导出', defaultDir: 'D:\\题库导出' }
  if (pathname === '/api/exports/export') return { id: 1, filePath: 'D:\\题库导出\\冒烟题库 A-1.0.0.tiku' }
  if (/^\/api\/banks\/\d+\/(records|wrong-questions|review\/due)$/.test(pathname)) return { records: [], total: 0 }
  if (pathname === '/api/banks/merge') return { bankId: 99, name: '合并库', questionsCopied: 8 }
  return { records: [], total: 0 }
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

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: VW, height: VH } })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
await page.route(
  (url) => url.pathname.startsWith('/api/'),
  async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const method = req.method()
    if (method !== 'GET') calls.push({ method, path: p, search: new URL(req.url()).search, body: req.postData() })
    else calls.push({ method, path: p, search: new URL(req.url()).search, body: null })
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: method === 'DELETE' ? { deletedQuestions: 3, affectedRecords: 1 } : stub(p) })
    })
  }
)

/* ================= 题库列表 ================= */
console.log('打开题库列表')
await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.bank-card', { timeout: 15000 })
check('渲染 3 张题库卡片', (await page.locator('.bank-card').count()) === 3)

console.log('\n[1] 卡片信息与「…」按钮')
const cardMeta = (await page.locator('.bank-card').first().innerText()).replace(/\s+/g, ' ')
check('卡片显示题数', /\b3\b\s*题/.test(cardMeta), cardMeta.slice(0, 80))
check('已做 >0 时显示已做数', /已做\s*1/.test(cardMeta), cardMeta.slice(0, 80))
const secondMeta = (await page.locator('.bank-card').nth(1).innerText()).replace(/\s+/g, ' ')
check('已做为 0 时不显示「已做 0」（少一点噪音）', !/已做/.test(secondMeta), secondMeta.slice(0, 80))
check('卡片有「…」按钮（默认透明，悬停出现）', (await page.locator('.bank-card .bank-more').count()) === 3)
const moreOpacity = await page.evaluate(() => getComputedStyle(document.querySelector('.bank-card .bank-more')).opacity)
check('「…」默认不显示（卡片保持整洁）', moreOpacity === '0', moreOpacity)
await page.locator('.bank-card').first().hover()
await page.waitForTimeout(300)
const moreOpacityHover = await page.evaluate(() => getComputedStyle(document.querySelector('.bank-card .bank-more')).opacity)
check('悬停后「…」出现', moreOpacityHover === '1', moreOpacityHover)

console.log('\n[2] 系统右键菜单被屏蔽（输入框内保留）')
const prevented = await page.evaluate(() => {
  const card = document.querySelector('.bank-card')
  const ev = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
  const cardResult = card.dispatchEvent(ev)
  const input = document.querySelector('input')
  const ev2 = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
  const inputResult = input ? input.dispatchEvent(ev2) : null
  return { cardResult, inputResult }
})
check('卡片上的右键被 preventDefault（不再弹浏览器菜单）', prevented.cardResult === false)
check('输入框内的右键保留系统菜单', prevented.inputResult === true, String(prevented.inputResult))

console.log('\n[3] 卡片菜单（右键 /「…」同款）')
await page.locator('.bank-card').first().click({ button: 'right' })
await page.waitForSelector('.action-menu', { timeout: 5000 })
const menuItems = await page.locator('.action-menu .action-menu-item').allInnerTexts()
const menuText = menuItems.join(' | ')
check('右键打开菜单', await page.locator('.action-menu').isVisible())
for (const label of ['打开题库', '开始练习', '练习历史', '打印试卷', '改名称与描述', '导出题库文件', '批量管理', '删除题库']) {
  check(`菜单含「${label}」`, menuText.includes(label), menuText)
}
await page.keyboard.press('Escape')
await page.waitForTimeout(200)
check('Esc 关闭菜单', (await page.locator('.action-menu').count()) === 0)

await page.locator('.bank-card').nth(1).hover()
await page.locator('.bank-card').nth(1).locator('.bank-more').click()
await page.waitForSelector('.action-menu', { timeout: 5000 })
check('「…」按钮打开同一份菜单', await page.locator('.action-menu').isVisible())
await page.keyboard.press('Escape')

console.log('\n[4] 改名称与描述（不必进详情页）')
await page.locator('.bank-card').nth(1).hover()
await page.locator('.bank-card').nth(1).locator('.bank-more').click()
await page.locator('.action-menu .action-menu-item', { hasText: '改名称与描述' }).click()
await page.waitForSelector('.el-dialog', { timeout: 5000 })
const renameInput = page.locator('.el-dialog input').first()
await renameInput.fill('改名后的题库')
await page.locator('.el-dialog .btn-primary').click()
await page.waitForTimeout(500)
const putCall = calls.find((c) => c.method === 'PUT' && /\/api\/banks\/2$/.test(c.path))
check('改名称发 PUT /api/banks/2', !!putCall, JSON.stringify(calls.slice(-3)))
check('请求体带新名称', !!putCall && /改名后的题库/.test(putCall.body || ''), putCall?.body)

console.log('\n[5] 删除题库（确认框 + DELETE）')
await page.locator('.bank-card').first().hover()
await page.locator('.bank-card').first().locator('.bank-more').click()
await page.locator('.action-menu .action-menu-item', { hasText: '删除题库' }).click()
await page.waitForSelector('.el-message-box', { timeout: 5000 })
const delText = (await page.locator('.el-message-box').innerText()).replace(/\s+/g, ' ')
check('弹出删除确认', /删除/.test(delText), delText.slice(0, 80))
check('确认文案说明题量', /3 道题/.test(delText), delText.slice(0, 100))
await page.locator('.el-message-box__btns button', { hasText: '取消' }).click()
await page.waitForTimeout(400)
check('取消后没有发 DELETE', !calls.some((c) => c.method === 'DELETE'), JSON.stringify(calls.filter((c) => c.method === 'DELETE')))

console.log('\n[6] 批量管理模式')
await page.locator('.bank-toolbar button', { hasText: '批量管理' }).click()
await page.waitForSelector('.bank-card .sel-box', { timeout: 5000 })
check('进入批量模式后卡片出现勾选框', (await page.locator('.bank-card .sel-box').count()) === 3)
await page.locator('.bank-card').nth(0).locator('.sel-box').click()
await page.locator('.bank-card').nth(2).locator('.sel-box').click()
let barText = (await page.locator('.selection-bar').innerText()).replace(/\s+/g, ' ')
check('批量条显示已选数量', /已选 2 个题库/.test(barText), barText)
check('批量条有导出/合并/删除', /导出所选/.test(barText) && /合并为新题库/.test(barText) && /删除所选/.test(barText), barText)

// Shift 连选：点第 1 张（已选），Shift 点第 3 张 → 全选
await page.locator('.bank-card').nth(0).locator('.sel-box').click({ modifiers: ['Shift'] })
await page.waitForTimeout(200)
barText = (await page.locator('.selection-bar').innerText()).replace(/\s+/g, ' ')
check('Shift 连选生效', /已选 3 个题库/.test(barText), barText)

console.log('\n[7] 批量导出所选')
await page.locator('.selection-bar button', { hasText: '导出所选' }).click()
await page.waitForTimeout(800)
const exportCalls = calls.filter((c) => c.path === '/api/exports/export')
check('批量导出逐个调用 /exports/export', exportCalls.length === 3, `${exportCalls.length} 次`)
check('导出请求带 bankId', exportCalls.every((c) => /bankId/.test(c.body || '')), exportCalls[0]?.body)

console.log('\n[8] 批量删除所选')
await page.locator('.selection-bar button', { hasText: '删除所选' }).click()
await page.waitForSelector('.el-message-box', { timeout: 5000 })
await page.locator('.el-message-box__btns button', { hasText: '删除' }).click()
await page.waitForTimeout(900)
const delCalls = calls.filter((c) => c.method === 'DELETE')
check('批量删除逐个发 DELETE', delCalls.length === 3, JSON.stringify(delCalls.map((c) => c.path)))
check('删除后退出批量模式', (await page.locator('.selection-bar').count()) === 0)

/* ================= 题目列表 ================= */
console.log('\n[9] 题目行菜单与选择条')
calls.length = 0
await page.goto(`${BASE}/banks/1`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.q-row[data-qid="102"]', { timeout: 15000 })
await page.locator('.q-row[data-qid="102"]').click({ button: 'right' })
await page.waitForSelector('.action-menu', { timeout: 5000 })
const qMenuText = (await page.locator('.action-menu .action-menu-item').allInnerTexts()).join(' | ')
check('题目右键菜单打开', await page.locator('.action-menu').isVisible())
for (const label of ['编辑此题', '从这题开始做题', '讲解这道题', '复制题干', '加入批量选择', '删除此题']) {
  check(`题目菜单含「${label}」`, qMenuText.includes(label), qMenuText)
}
check('题目行只有 2 个常驻图标（编辑 + 更多）', (await page.locator('.q-row[data-qid="102"] .q-actions button').count()) === 2)
await page.keyboard.press('Escape')

// 复制题干
await page.locator('.q-row[data-qid="102"]').click({ button: 'right' })
await page.locator('.action-menu .action-menu-item', { hasText: '复制题干' }).click()
await page.waitForTimeout(400)
check('复制题干给出反馈', (await page.locator('.el-message').count()) > 0)

// 选择条：导出所选 / 删除所选（「选题另存」现在收在题目区「更多」里，见 R2）
await page.locator('.section-actions button', { hasText: '更多' }).first().click()
await page.waitForSelector('.action-menu', { timeout: 5000 })
const sectionMenuText = (await page.locator('.action-menu .action-menu-item').allInnerTexts()).join(' | ')
for (const label of ['共用材料', 'AI 补答案', '选题另存']) {
  check(`题目区「更多」含「${label}」`, sectionMenuText.includes(label), sectionMenuText)
}
await page.locator('.action-menu .action-menu-item', { hasText: '选题另存' }).click()
await page.waitForTimeout(300)
check('进入选择模式', await page.locator('.selection-bar').isVisible())
await page.locator('.q-row[data-qid="101"]').click()
await page.locator('.q-row[data-qid="103"]').click()
const selBarText = (await page.locator('.selection-bar').innerText()).replace(/\s+/g, ' ')
check('选择条含「导出所选」', selBarText.includes('导出所选'), selBarText)
check('选择条含「删除所选」', selBarText.includes('删除所选'), selBarText)

console.log('\n[10] 导出所选 → 导出弹窗（范围默认=已勾选）')
await page.locator('.selection-bar button', { hasText: '导出所选' }).click()
await page.waitForSelector('.el-dialog', { timeout: 5000 })
const exportDlgText = (await page.locator('.el-dialog').last().innerText()).replace(/\s+/g, ' ')
check('导出弹窗含「导出范围」', exportDlgText.includes('导出范围'), exportDlgText.slice(0, 120))
check('范围默认选中「已勾选 2 题」', /已勾选 2 题/.test(exportDlgText), exportDlgText.slice(0, 160))
await page.locator('.el-dialog .btn-ghost', { hasText: '取消' }).click()
await page.waitForTimeout(300)

console.log('\n[11] 批量删除题目')
await page.locator('.selection-bar button', { hasText: '删除所选' }).click()
await page.waitForSelector('.el-message-box', { timeout: 5000 })
await page.locator('.el-message-box__btns button', { hasText: '删除' }).click()
await page.waitForTimeout(1000)
const qDel = calls.filter((c) => c.method === 'DELETE' && /\/api\/questions\//.test(c.path))
check('批量删除题目逐个发 DELETE', qDel.length === 2, JSON.stringify(qDel.map((c) => c.path)))

/* ================= 题库详情头部（开始练习 + 更多） ================= */
console.log('\n[12] 题库头部：一个主按钮 + 「更多」')
calls.length = 0
await page.goto(`${BASE}/banks/1`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.header-actions', { timeout: 15000 })
const headerBtns = await page.locator('.header-actions button').allInnerTexts()
const headerText = headerBtns.map((s) => s.replace(/\s+/g, ' ').trim()).join(' | ')
check('头部只有 3 个点击目标（更多 + 开始练习 + 题量箭头）', headerBtns.length === 3, headerText)
check('主按钮是「开始练习」', /开始练习/.test(headerText), headerText)
check('「学习路线」「闪卡」已从头部下线', !/学习路线|闪卡/.test(headerText), headerText)

await page.locator('.header-actions button', { hasText: '更多' }).click()
await page.waitForSelector('.action-menu', { timeout: 5000 })
const bankMenuText = (await page.locator('.action-menu .action-menu-item').allInnerTexts()).join(' | ')
for (const label of ['练习历史', '知识点', '打印试卷', '导出题库文件', '编辑题库', '删除题库']) {
  check(`「更多」含「${label}」`, bankMenuText.includes(label), bankMenuText)
}
await page.keyboard.press('Escape')
await page.waitForTimeout(200)

console.log('\n[12b] 主按钮的箭头：题量与自定义范围（不用到「更多」里翻）')
await page.locator('.header-actions .split-caret').click()
await page.waitForSelector('.action-menu', { timeout: 5000 })
const planMenuText = (await page.locator('.action-menu .action-menu-item').allInnerTexts()).join(' | ')
check('变体菜单给题量档位（10/20/50/100）', /练 10 题/.test(planMenuText) && /练 20 题/.test(planMenuText) && /练 50 题/.test(planMenuText) && /练 100 题/.test(planMenuText), planMenuText)
check('变体菜单里有「自定义练习…」', /自定义练习/.test(planMenuText), planMenuText)
await page.keyboard.press('Escape')
await page.waitForTimeout(200)

console.log('\n[12c] 从变体菜单选题量 → 按该题量配题')
calls.length = 0
await page.locator('.header-actions .split-caret').click()
await page.waitForSelector('.action-menu', { timeout: 5000 })
await page.locator('.action-menu .action-menu-item', { hasText: '练 50 题' }).click()
await page.waitForURL(/\/practice\?/, { timeout: 15000 })
const countGet = calls.find((c) => /\/api\/banks\/1\/practice-plan/.test(c.path))
check('按选中的题量取配题结果（count=50）', !!countGet && /count=50/.test(countGet.search || ''), JSON.stringify(calls.map((c) => c.path + (c.search || ''))))
await page.goto(`${BASE}/banks/1`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.header-actions', { timeout: 15000 })

console.log('\n[13] 开始练习 = 一键配题（practice-plan → PLAN 会话 → 说明横幅）')
calls.length = 0
await page.locator('.header-actions .split-main', { hasText: '开始练习' }).click()
await page.waitForURL(/\/practice\?/, { timeout: 15000 })
const planGet = calls.find((c) => /\/api\/banks\/1\/practice-plan$/.test(c.path))
check('先取配题结果 GET /banks/1/practice-plan', !!planGet, JSON.stringify(calls.map((c) => c.path)))
const planPost = calls.find((c) => c.method === 'POST' && /\/api\/banks\/1\/sessions$/.test(c.path))
check('按配题结果建会话（mode=PLAN）', !!planPost && /"mode":"PLAN"/.test(planPost.body || ''), planPost?.body || '(无请求)')
check('questionIds 原样带上（顺序即优先级）', !!planPost && /"questionIds":\[101,102\]/.test((planPost.body || '').replace(/\s/g, '')), planPost?.body || '')
await page.waitForTimeout(1200)
const bannerText = await page.locator('.plan-banner').innerText().catch(() => '')
check('练习页顶部显示配题说明（可核对的数量）', /这 2 题/.test(bannerText) && /题库共 3 题/.test(bannerText), bannerText)
await page.locator('.plan-close').click()
await page.waitForTimeout(200)
check('说明可关掉（不占做题注意力）', (await page.locator('.plan-banner').count()) === 0)

console.log('\n[14] 卡片菜单「开始练习」= 同一条一键配题路径（R6：同一动作同一个词）')
calls.length = 0
await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.bank-card', { timeout: 15000 })
await page.locator('.bank-card').first().click({ button: 'right' })
await page.waitForSelector('.action-menu', { timeout: 5000 })
await page.locator('.action-menu .action-menu-item', { hasText: '开始练习' }).click()
await page.waitForURL(/\/practice\?/, { timeout: 15000 })
const listPlanGet = calls.find((c) => /\/api\/banks\/1\/practice-plan$/.test(c.path))
check('卡片菜单也先取配题结果', !!listPlanGet, JSON.stringify(calls.map((c) => c.path)))
const listPlanPost = calls.find((c) => c.method === 'POST' && /\/api\/banks\/1\/sessions$/.test(c.path))
check('卡片菜单也按配题结果建会话（mode=PLAN）', !!listPlanPost && /"mode":"PLAN"/.test(listPlanPost.body || ''), listPlanPost?.body || '')
await page.waitForTimeout(1200)
check('直接落到做题页并带上配题说明', /这 2 题/.test(await page.locator('.plan-banner').innerText().catch(() => '')))

console.log('\n[15] 我的笔记（更多 → 我的笔记；顶层页 + 只看这个题库）')
calls.length = 0
await page.goto(`${BASE}/banks/1`, { waitUntil: 'domcontentloaded' })
console.log('\n[15] 笔记页（更多 → 我的笔记）：列表 + 右侧详情')
calls.length = 0
await page.waitForSelector('.header-actions', { timeout: 15000 })
await page.locator('.header-actions button', { hasText: '更多' }).click()
await page.waitForSelector('.action-menu', { timeout: 5000 })
check('「更多」里有「我的笔记」', (await page.locator('.action-menu .action-menu-item', { hasText: '我的笔记' }).count()) > 0,
  (await page.locator('.action-menu .action-menu-item').allInnerTexts()).join(' | '))
await page.locator('.action-menu .action-menu-item', { hasText: '我的笔记' }).click()
await page.waitForURL(/\/notes\?bankId=1$/, { timeout: 10000 })
await page.waitForSelector('.note-row', { timeout: 10000 })
const notesListReq = calls.find((c) => c.path === '/api/notes')
check('笔记列表按题库过滤（GET /notes?bankId=1）', !!notesListReq && /bankId=1/.test(notesListReq.search || ''), notesListReq?.search || '(无请求)')
check('侧边栏有「笔记」入口', (await page.locator('.nav-item', { hasText: '笔记' }).count()) > 0)
const groupHeads = await page.locator('.note-group-name').allInnerTexts()
check('按时间分组（今天 / 更早 都有小标题）', groupHeads.includes('今天') && groupHeads.includes('更早'), JSON.stringify(groupHeads))
const rowTexts = (await page.locator('.note-row').allInnerTexts()).map((s) => s.replace(/\s+/g, ' '))
check('列表一行给出摘要 + 时间 + 归类/来源', /先看年份再动笔/.test(rowTexts[0]) && /第 101 题/.test(rowTexts[0]) && /来自讲解/.test(rowTexts[0]), rowTexts[0])
check('未选任何一条时右侧给出"从左边点一条笔记来看"', /从左边点一条笔记来看/.test(await page.locator('.detail-empty').innerText()))
const rail = await page.evaluate(() => {
  const el = document.querySelector('.note-row-rail[style*="--rail"]')
  return el ? getComputedStyle(el).getPropertyValue('--rail').trim() : ''
})
check('标了色的笔记有左侧色条（--rail 解析成具体颜色）', /^#|rgb/.test(rail), rail)

console.log('\n[15a] 点整行 = 打开这一条（R18）')
await page.locator('.note-row', { hasText: '先看年份再动笔' }).click()
await page.waitForSelector('.detail-text', { timeout: 10000 })
const openedText = (await page.locator('.detail-text').innerText()).replace(/\s+/g, ' ')
check('点卡片本身就能打开（不用去找右侧的小按钮）', /先看年份再动笔/.test(openedText), openedText.slice(0, 60))
check('打开后是"读"状态：只有正文，没有编辑框', (await page.locator('.nc-input').count()) === 0)
await page.locator('.note-row', { hasText: '这段是长笔记' }).click()
await page.waitForTimeout(300)
check('点另一条 → 右侧换成那一条', /这段是长笔记/.test((await page.locator('.detail-text').innerText()).replace(/\s+/g, ' ')))
await page.locator('.note-row', { hasText: '先看年份再动笔' }).click()
await page.waitForTimeout(300)

console.log('\n[15b] 改就是改：只有一个编辑位，保存不会新增（R19/R20，回归用户报的 bug）')
calls.length = 0
await page.locator('.note-act', { hasText: '改' }).first().click()
await page.waitForTimeout(400)
check('页面同一时刻只有一个编辑框（不再"顶部一个 + 卡片里一个"）', (await page.locator('.nc-input').count()) === 1,
  `nc-input=${await page.locator('.nc-input').count()}`)
const editValue = await page.locator('.nc-input').inputValue()
check('编辑框里是这一条的正文', /先看年份再动笔/.test(editValue), editValue.slice(0, 40))
await page.locator('.nc-input').fill('改一下：先看年份再动笔')
await page.locator('.btn-primary', { hasText: '保存' }).first().click()
await page.waitForTimeout(600)
const notePutCall = calls.find((c) => c.method === 'PUT' && /^\/api\/notes\/1$/.test(c.path))
const notePostCall = calls.find((c) => c.method === 'POST' && c.path === '/api/notes')
check('保存走 PUT /notes/1（更新那一条）', !!notePutCall && /先看年份再动笔/.test(notePutCall.body || ''), notePutCall?.body || '(无请求)')
check('保存没有新增笔记（没有 POST /notes）', !notePostCall, notePostCall?.body || '')
check('条数没有变（仍是共 2 条）', /共 2 条/.test(await page.locator('.page-meta').innerText()))
check('保存后回到"读"状态', (await page.locator('.nc-input').count()) === 0)

console.log('\n[15c] 新建只从「写一条」入口发生')
calls.length = 0
await page.locator('.page-header-actions .btn-primary', { hasText: '写一条' }).click()
await page.waitForTimeout(300)
await page.locator('.nc-input').fill('资料分析先看年份')
await page.locator('.detail-foot .btn-primary', { hasText: '保存' }).click()
await page.waitForTimeout(600)
const quickPost = calls.find((c) => c.method === 'POST' && c.path === '/api/notes')
check('「写一条」保存发 POST /notes，并带上当前题库关联',
  !!quickPost && /"content":"资料分析先看年份"/.test(quickPost.body || '') && /"bankId":1/.test((quickPost.body || '').replace(/\s/g, '')),
  quickPost?.body || '(无请求)')

console.log('\n[15d] 标注（高亮/加粗/下划线）与阅读字号')
await page.locator('.page-header-actions .btn-primary', { hasText: '写一条' }).click()
await page.waitForTimeout(300)
await page.locator('.nc-input').fill('资料分析先看年份')
await page.locator('.nc-input').evaluate((el) => {
  el.focus()
  el.setSelectionRange(0, el.value.length)
})
await page.locator('.nc-dot').first().click()
await page.waitForTimeout(300)
check('选中文字点黄色 → 正文写入高亮标记 ==…==', (await page.locator('.nc-input').inputValue()) === '==资料分析先看年份==')
check('有标记时出现"效果预览"', (await page.locator('.nc-preview-body mark.rt-hl').count()) >= 1)
await page.locator('.nc-tool', { hasText: '清除标记' }).click()
await page.waitForTimeout(200)
check('「清除标记」还原成纯文本', (await page.locator('.nc-input').inputValue()) === '资料分析先看年份')
const fontBefore = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--content-font').trim())
await page.locator('.size-btn', { hasText: 'A+' }).click()
await page.waitForTimeout(200)
const fontAfter = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--content-font').trim())
check('笔记页 A+ 真的放大正文（--content-font 变化）', fontBefore !== fontAfter, `${fontBefore} → ${fontAfter}`)
await page.locator('.size-btn', { hasText: 'A−' }).click()
await page.locator('.detail-foot button', { hasText: '取消' }).click()
await page.waitForTimeout(300)

console.log('\n[15e] 搜索与来源筛选')
calls.length = 0
await page.locator('.note-search-input').fill('笔画')
await page.locator('.note-search-input').press('Enter')
await page.waitForTimeout(600)
const searchReq = calls.filter((c) => c.path === '/api/notes').pop()
check('搜索走服务端（GET /notes?keyword=…）', !!searchReq && /keyword/.test(decodeURIComponent(searchReq.search || '')), searchReq?.search || '(无请求)')
await page.locator('.note-search-clear').click()
await page.waitForTimeout(400)
calls.length = 0
await page.locator('.note-tab', { hasText: '从讲解存的' }).click()
await page.waitForTimeout(600)
const srcReq = calls.filter((c) => c.path === '/api/notes').pop()
check('来源筛选走服务端（GET /notes?source=ai）', !!srcReq && /source=ai/.test(srcReq.search || ''), srcReq?.search || '(无请求)')
await page.locator('.note-tab', { hasText: '从讲解存的' }).click()
await page.waitForTimeout(400)

console.log('\n[15f] 关联的题目就地展开（不跳走）/ 去题库 / 解除关联')
calls.length = 0
await page.locator('.note-row', { hasText: '先看年份再动笔' }).click()
await page.waitForTimeout(300)
await page.locator('.note-link-main', { hasText: '第 101 题' }).first().click()
await page.waitForSelector('.qp-stem', { timeout: 10000 })
const qpStem = (await page.locator('.qp-stem').first().innerText()).replace(/\s+/g, ' ')
const qpReq = calls.find((c) => /^\/api\/questions\/101$/.test(c.path))
check('展开就取这道题（GET /questions/101）', !!qpReq, JSON.stringify(calls.map((c) => c.path)))
check('就地显示题干与选项（不用跳题库）', qpStem.length > 4 && (await page.locator('.qp-opt').count()) >= 2, qpStem.slice(0, 60))
check('正确答案在预览里高亮', (await page.locator('.qp-opt.correct').count()) >= 1)
await page.locator('.qp-act', { hasText: '去题库' }).first().click()
await page.waitForSelector('.q-row[data-qid="101"]', { timeout: 15000 })
await page.waitForTimeout(400)
check('「去题库」回到那道题（/banks/1?q=101 → 定位第 101 题，q 参数随即被消费）',
  /\/banks\/1$/.test(page.url()), page.url())
await page.goBack({ waitUntil: 'domcontentloaded' })
await page.waitForSelector('.note-link-x', { timeout: 10000 })
await page.locator('.note-link-x').first().click()
await page.waitForTimeout(600)
const unlinkCall = calls.find((c) => c.method === 'DELETE' && /\/api\/notes\/1\/links$/.test(c.path))
check('点标签上的 ✕ 解除关联（DELETE /notes/1/links）',
  !!unlinkCall && /type=question/.test(unlinkCall.search || '') && /targetId=101/.test(unlinkCall.search || ''),
  unlinkCall ? `${unlinkCall.path}${unlinkCall.search}` : '(无请求)')

console.log('\n[16] 运行时错误')
check('无未捕获的 JS 错误', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
