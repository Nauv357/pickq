/**
 * 题库列表 / 题目列表的"操作菜单 + 批量管理 + 导出所选"冒烟（Playwright + 本机 Chrome，不需要后端）。
 *
 * 覆盖点（都是这轮改动的真实风险处）：
 *   1. 系统右键菜单被屏蔽（卡片上 preventDefault 生效），输入框内保留；
 *   2. 卡片「…」与右键打开同一份菜单，菜单项齐备、Esc 可关；
 *   3. 卡片显示「共 N 题 · 已做 M」；
 *   4. 批量管理模式：勾选、Shift 连选、底部条计数；批量删除会逐个发 DELETE；
 *   5. 重命名弹窗发 PUT；导出所选逐个发 POST /exports/export；
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

function stub(pathname) {
  if (pathname === '/api/banks') return { records: BANKS, total: BANKS.length }
  if (pathname === '/api/home/overview') return { bankCount: 3, questionCount: 8, dueTotal: 0, wrongCount: 0, lastSession: null }
  if (/^\/api\/banks\/\d+$/.test(pathname)) return BANKS.find((b) => b.id === Number(pathname.split('/').pop())) || BANKS[0]
  if (/^\/api\/banks\/\d+\/questions$/.test(pathname)) return { records: ROWS, total: ROWS.length }
  if (/^\/api\/banks\/\d+\/question-nav$/.test(pathname)) return ROWS.map((q) => ({ questionId: q.questionId, questionType: q.questionType, questionNumber: q.questionNumber }))
  if (/^\/api\/banks\/\d+\/progress$/.test(pathname)) return { totalQuestions: 3, answeredCount: 1, correctCount: 1, recordsCount: 1, accuracy: 1 }
  if (/^\/api\/banks\/\d+\/materials$/.test(pathname)) return []
  if (/^\/api\/questions\/\d+$/.test(pathname)) return ROWS.find((q) => q.questionId === Number(pathname.split('/').pop())) || Q(999)
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
    if (method !== 'GET') calls.push({ method, path: p, body: req.postData() })
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
check('卡片显示「共 N 题 · 已做 M」', /共 3 题/.test(cardMeta) && /已做 1/.test(cardMeta), cardMeta.slice(0, 80))
check('卡片右上角有「…」按钮', (await page.locator('.bank-card .bank-more').count()) === 3)

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
for (const label of ['打开题库', '开始做题', '练习历史', '打印试卷', '重命名', '导出题库文件', '批量管理', '删除题库']) {
  check(`菜单含「${label}」`, menuText.includes(label), menuText)
}
await page.keyboard.press('Escape')
await page.waitForTimeout(200)
check('Esc 关闭菜单', (await page.locator('.action-menu').count()) === 0)

await page.locator('.bank-card').nth(1).locator('.bank-more').click()
await page.waitForSelector('.action-menu', { timeout: 5000 })
check('「…」按钮打开同一份菜单', await page.locator('.action-menu').isVisible())
await page.keyboard.press('Escape')

console.log('\n[4] 重命名（不必进详情页）')
await page.locator('.bank-card').nth(1).locator('.bank-more').click()
await page.locator('.action-menu .action-menu-item', { hasText: '重命名' }).click()
await page.waitForSelector('.el-dialog', { timeout: 5000 })
const renameInput = page.locator('.el-dialog input').first()
await renameInput.fill('改名后的题库')
await page.locator('.el-dialog .btn-primary').click()
await page.waitForTimeout(500)
const putCall = calls.find((c) => c.method === 'PUT' && /\/api\/banks\/2$/.test(c.path))
check('重命名发出 PUT /api/banks/2', !!putCall, JSON.stringify(calls.slice(-3)))
check('请求体带新名称', !!putCall && /改名后的题库/.test(putCall.body || ''), putCall?.body)

console.log('\n[5] 删除题库（确认框 + DELETE）')
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
for (const label of ['编辑此题', '从这题开始做题', 'AI 解析', '复制题干', '加入批量选择', '删除此题']) {
  check(`题目菜单含「${label}」`, qMenuText.includes(label), qMenuText)
}
check('题目行只有 2 个常驻图标（编辑 + 更多）', (await page.locator('.q-row[data-qid="102"] .q-actions button').count()) === 2)
await page.keyboard.press('Escape')

// 复制题干
await page.locator('.q-row[data-qid="102"]').click({ button: 'right' })
await page.locator('.action-menu .action-menu-item', { hasText: '复制题干' }).click()
await page.waitForTimeout(400)
check('复制题干给出反馈', (await page.locator('.el-message').count()) > 0)

// 选择条：导出所选 / 删除所选
await page.locator('.section-actions button', { hasText: '选题另存' }).first().click()
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

console.log('\n[12] 运行时错误')
check('无未捕获的 JS 错误', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
