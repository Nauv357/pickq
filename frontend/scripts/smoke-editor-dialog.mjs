/**
 * BankDetailView 编辑大弹窗冒烟验证（Playwright + 本机 Chrome，不需要后端）。
 *
 * 覆盖点（都是这次改动的真实风险处，靠看图看不出来）：
 *   1. 点题目行 → 打开大弹窗，弹窗内是 QuestionFormPanel（面板头/表单/底部操作都在）；
 *   2. 弹窗样式生效：EP 自带头部隐藏、body 是唯一滚动容器（max-height + overflow-y）、
 *      面板卡片外框被去掉（不出现双边框）、底部操作吸底；
 *   3. z-index 层序：弹窗遮罩 1900 < 题号盘 1990（编辑中仍可直接跳题）< 面板内确认框 2000+；
 *   4. 题号盘跳题：不关弹窗、不滚动页面，直接切到目标题；
 *   5. 关闭：无修改直接关；有修改弹「保存并关闭 / 放弃修改」，选放弃后关闭；
 *   6. 关闭后页面滚动位置不变（旧实现会把页面滚到面板位置，这是被替换掉的旧行为）。
 *
 * 用法：先起前端 dev server，再执行
 *   node scripts/smoke-editor-dialog.mjs [--url http://localhost:5199/banks/1]
 */
import { chromium } from 'playwright-core'
import { existsSync } from 'node:fs'

const argv = process.argv.slice(2)
const urlArg = argv.indexOf('--url')
const URL_ = urlArg >= 0 ? argv[urlArg + 1] : 'http://localhost:5199/banks/1'
const vpArg = argv.indexOf('--viewport')
const [VW, VH] = (vpArg >= 0 ? argv[vpArg + 1] : '1440x900').split('x').map(Number)

const CHROME = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
].find((p) => existsSync(p))
if (!CHROME) {
  console.error('找不到 Chrome / Edge 可执行文件')
  process.exit(2)
}

/* ---------------- 假后端：只喂这个页面需要的形状 ---------------- */
const Q = (n, id, extra = {}) => ({
  questionId: id,
  questionNumber: n,
  questionType: 'SINGLE',
  content: `第 ${n} 题的题干内容`,
  options: [
    { key: 'A', text: '选项 A' },
    { key: 'B', text: '选项 B' },
    { key: 'C', text: '选项 C' },
    { key: 'D', text: '选项 D' }
  ],
  answerKeys: ['A'],
  score: 2,
  analysis: `第 ${n} 题的解析`,
  category: '',
  ...extra
})
const ROWS = [Q(101, 101), Q(102, 102), Q(103, 103)]

function stubFor(pathname) {
  if (/^\/api\/banks\/1$/.test(pathname)) {
    return { bankId: 1, name: '冒烟题库', description: 'e2e', questionCount: 3, version: '1.0.0', createdAt: '2026-01-01T00:00:00' }
  }
  if (/^\/api\/banks\/1\/questions$/.test(pathname)) return { records: ROWS, total: ROWS.length }
  if (/^\/api\/banks\/1\/question-nav$/.test(pathname)) {
    return ROWS.map((q) => ({ questionId: q.questionId, questionType: q.questionType, questionNumber: q.questionNumber }))
  }
  if (/^\/api\/banks\/1\/progress$/.test(pathname)) {
    return { totalQuestions: 3, answeredCount: 0, correctCount: 0, recordsCount: 0, accuracy: 0 }
  }
  if (/^\/api\/banks\/1\/review\/summary$/.test(pathname)) return { dueTotal: 0 }
  if (/^\/api\/banks\/1\/materials$/.test(pathname)) return []
  if (/^\/api\/questions\/\d+$/.test(pathname)) {
    const id = Number(pathname.split('/').pop())
    return ROWS.find((q) => q.questionId === id) || Q(999, id)
  }
  if (/^\/api\/ai-import\/jobs\/(active|recent)$/.test(pathname)) return []
  if (pathname.endsWith('/wrong-questions') || pathname.endsWith('/records')) return { records: [], total: 0 }
  if (pathname.includes('/review/due')) return { records: [], total: 0 }
  return { records: [], total: 0 }
}

/* ---------------- 断言工具 ---------------- */
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

// 注意：不能用 '**/api/**' 通配——那会把 Vite 自己的模块请求 /src/api/aiImport.js 也拦下来，
// 导致入口脚本被当成 JSON 返回、页面白屏。只拦路径以 /api/ 开头的真实后端请求。
await page.route(
  (url) => url.pathname.startsWith('/api/'),
  async (route) => {
    const pathname = new URL(route.request().url()).pathname
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: stubFor(pathname) })
    })
  }
)

console.log(`打开 ${URL_}`)
await page.goto(URL_, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.q-row[data-qid="101"]', { timeout: 15000 })
check('题目列表渲染 3 行', (await page.locator('.q-row').count()) === 3)

// 把页面往下滚一段：旧实现点行后面板在页面顶部，会把页面强行滚上去；新实现不该动滚动位置
await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
await page.waitForTimeout(200)
const scrollBefore = await page.evaluate(() => window.scrollY)

console.log('\n[1] 点题目行 → 大弹窗')
await page.locator('.q-row[data-qid="102"] .q-main, .q-row[data-qid="102"]').first().click()
await page.waitForSelector('.editor-dialog', { state: 'visible', timeout: 10000 })
check('弹窗出现 (.editor-dialog)', await page.locator('.editor-dialog').isVisible())
check('弹窗内是 QuestionFormPanel', await page.locator('.editor-dialog .panel .panel-head').isVisible())
const headText = (await page.locator('.editor-dialog .panel-head').innerText()).replace(/\s+/g, ' ')
check('面板头显示目标题号 102', /102/.test(headText), headText.slice(0, 60))
check('弹窗内表单有题干输入框', await page.locator('.editor-dialog .panel-body textarea').first().isVisible())

console.log('\n[2] 弹窗样式与滚动容器')
const style = await page.evaluate(() => {
  const dlg = document.querySelector('.editor-dialog')
  const header = dlg.querySelector('.el-dialog__header')
  const body = dlg.querySelector('.el-dialog__body')
  const panel = dlg.querySelector('.panel')
  const foot = dlg.querySelector('.panel-foot')
  const cs = (el) => (el ? getComputedStyle(el) : null)
  return {
    width: dlg.getBoundingClientRect().width,
    headerDisplay: cs(header).display,
    bodyMaxH: cs(body).maxHeight,
    bodyOverflow: cs(body).overflowY,
    bodyPadding: cs(body).padding,
    panelBorder: cs(panel).borderTopWidth,
    panelHeadBg: cs(dlg.querySelector('.panel-head')).backgroundColor,
    footPosition: cs(foot).position,
    footBottom: cs(foot).bottom
  }
})
// 编辑态宽度 = min(100vw - 236px, 1040px)，但受全局 `.el-dialog { max-width: 92vw }` 兜底约束
const expectW = Math.min(Math.min(VW - 236, 1040), VW * 0.92)
check(`编辑态弹窗宽度 ≈ ${Math.round(expectW)}（右侧留出题号盘通道）`, Math.abs(style.width - expectW) < 2, `实际 ${Math.round(style.width)}`)
check('EP 自带头部隐藏', style.headerDisplay === 'none', style.headerDisplay)
check('弹窗 body 限高', style.bodyMaxH !== 'none' && style.bodyMaxH !== '', style.bodyMaxH)
check('弹窗 body 纵向可滚', style.bodyOverflow === 'auto', style.bodyOverflow)
check('弹窗 body 无内边距（面板自管）', style.bodyPadding === '0px', style.bodyPadding)
check('面板外框已去（无双层边框）', style.panelBorder === '0px', style.panelBorder)
check('底部操作吸底', style.footPosition === 'sticky' && style.footBottom === '0px', `${style.footPosition}/${style.footBottom}`)
check('打开弹窗未改变页面滚动位置', Math.abs((await page.evaluate(() => window.scrollY)) - scrollBefore) < 2)

console.log('\n[3] z-index 层序与题号盘位置')
// 注意：未打开的 el-dialog 也会渲染隐藏遮罩（本页有 10 个），必须只看可见的那个
const z = await page.evaluate(() => {
  const visibleOverlay = [...document.querySelectorAll('.el-overlay')].find((o) => o.getBoundingClientRect().height > 0)
  const dl = document.querySelector('.editor-dialog')
  const dock = document.querySelector('.edit-dock-side') || document.querySelector('.dock-fab')
  const dRect = dl.getBoundingClientRect()
  const kRect = dock ? dock.getBoundingClientRect() : null
  const overlaps = kRect ? !(kRect.left >= dRect.right || kRect.right <= dRect.left) : null
  return {
    overlay: visibleOverlay ? getComputedStyle(visibleOverlay).zIndex : null,
    dockZ: dock ? getComputedStyle(dock).zIndex : null,
    dockKind: dock ? dock.className : null,
    dockText: dock ? dock.innerText.replace(/\s+/g, ' ').slice(0, 20) : null,
    overlapsDialog: overlaps,
    dialogRight: Math.round(dRect.right),
    dockLeft: kRect ? Math.round(kRect.left) : null
  }
})
check('可见遮罩 z-index = 1900（弹窗层低于后续确认框）', z.overlay === '1900', String(z.overlay))
check('题号盘/题号按钮 z-index = 1990（浮在弹窗之上，可继续跳题）', z.dockZ === '1990', String(z.dockZ))
check('题号盘不与弹窗重叠（右侧通道生效）', z.overlapsDialog === false, `弹窗右缘 ${z.dialogRight} / 题号盘左侧 ${z.dockLeft}`)

console.log('\n[4] 弹窗内用题号盘跳题')
// 窄视口（≤1680px）题号盘收成"题号"小按钮，点开再选题号
if (z.dockKind.includes('dock-fab')) {
  await page.locator('.dock-fab').click()
  await page.waitForSelector('.edit-dock-side .qnav-num', { timeout: 5000 })
  check('点"题号"小按钮展开题号盘', await page.locator('.edit-dock-side .qnav-grid').isVisible())
}
await page.locator('.edit-dock-side .qnav-num').nth(2).click() // 第 3 题（题号 103）
await page.waitForTimeout(800)
const headText2 = (await page.locator('.editor-dialog .panel-head').innerText()).replace(/\s+/g, ' ')
check('弹窗内切到题号 103', /103/.test(headText2), headText2.slice(0, 60))
check('弹窗仍然打开', await page.locator('.editor-dialog').isVisible())
check('跳题未改变页面滚动位置', Math.abs((await page.evaluate(() => window.scrollY)) - scrollBefore) < 2)
// 收起题号盘，避免影响后续断言
if (await page.locator('.dock-fab').count()) await page.locator('.dock-fab').click()

console.log('\n[5] 关闭：无修改直接关')
await page.locator('.editor-dialog .panel-head .head-actions button').last().click()
await page.waitForSelector('.editor-dialog', { state: 'hidden', timeout: 5000 })
check('无未保存修改时点 × 直接关闭', !(await page.locator('.editor-dialog').isVisible()))
check('关闭后无确认框残留', (await page.locator('.el-message-box').count()) === 0)

console.log('\n[6] 关闭：有修改时先确认（放弃修改）')
await page.locator('.q-row[data-qid="101"]').click()
await page.waitForSelector('.editor-dialog', { state: 'visible', timeout: 10000 })
await page.locator('.editor-dialog .panel-body textarea').first().fill('改过的题干内容（冒烟）')
await page.waitForTimeout(300)
await page.locator('.editor-dialog .panel-head .head-actions button').last().click()
await page.waitForSelector('.el-message-box', { state: 'visible', timeout: 5000 })
const mbZ = await page.evaluate(() => getComputedStyle(document.querySelector('.el-overlay:last-of-type') || document.querySelector('.el-message-box').closest('.el-overlay')).zIndex)
const mbText = (await page.locator('.el-message-box').innerText()).replace(/\s+/g, ' ')
check('有修改时弹确认框', /放弃修改/.test(mbText), mbText.slice(0, 80))
check('确认框在弹窗之上（z-index 2000+）', Number(mbZ) > 1990, String(mbZ))
await page.locator('.el-message-box__btns button', { hasText: '放弃修改' }).click()
await page.waitForSelector('.editor-dialog', { state: 'hidden', timeout: 5000 })
check('选「放弃修改」后关闭', !(await page.locator('.editor-dialog').isVisible()))
check('关闭后页面滚动位置不变', Math.abs((await page.evaluate(() => window.scrollY)) - scrollBefore) < 2)
check('关闭后题号盘一并收起', (await page.locator('.edit-dock-side, .dock-fab').count()) === 0)

console.log('\n[7] 运行时错误')
check('无未捕获的 JS 错误', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
