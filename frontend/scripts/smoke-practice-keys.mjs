/**
 * 做题页键盘快捷键（回归）：A~H 选答案 · ←/→ 切题 · 数字直达题号 · Enter 交卷。
 *
 * 为什么单独一个冒烟：这套快捷键是**全局 window 监听 + 多层守卫**（输入框/弹层/报告态都不抢键盘），
 * 守卫条件里任何一个（例如"页面上存在 .el-overlay"）被页面其它元素命中，快捷键就会**静默全失效**——
 * 用户实测报过"方向键/字母/数字全都没反应"。这里用假后端把四种键都按一遍，
 * 并把守卫依赖的 DOM 事实打出来，便于定位。
 *
 * 用法：node scripts/smoke-practice-keys.mjs [--url http://localhost:5199]
 */
import { chromium } from 'playwright-core'
import { existsSync } from 'node:fs'

const argv = process.argv.slice(2)
const urlArg = argv.indexOf('--url')
const BASE = urlArg >= 0 ? argv[urlArg + 1] : 'http://localhost:5199'

const CHROME = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
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

const SESSION_ID = 55
const Q = (n) => ({
  questionId: 100 + n,
  questionNumber: n,
  questionType: 'SINGLE',
  content: `第 ${n} 题：2024 年该省 GDP 为 120 亿，2023 年为 100 亿，增长率是多少？`,
  options: [{ key: 'A', text: '20%' }, { key: 'B', text: '16.7%' }, { key: 'C', text: '120%' }, { key: 'D', text: '2%' }],
  score: 1,
  favorite: false,
  materialId: null
})
const QUESTIONS = [1, 2, 3, 4, 5].map(Q)

function stub(pathname) {
  if (/^\/api\/banks\/\d+$/.test(pathname)) {
    return { id: 1, name: '键盘冒烟题库', description: 'e2e', version: '', authorName: '', createdAt: '2026-01-01T10:00:00', questionCount: 5, answeredCount: 0, reviewEnabled: false }
  }
  if (pathname === `/api/sessions/${SESSION_ID}`) {
    return {
      id: SESSION_ID, bankId: 1, mode: 'PLAN', questionCount: 5, answeredCount: 0, correctCount: 0,
      totalScore: 0, maxScore: 5, totalSeconds: 0, status: 'IN_PROGRESS',
      createdAt: new Date().toISOString(), finishedAt: null, questions: QUESTIONS
    }
  }
  if (pathname === '/api/skills/templates') return []
  if (/^\/api\/banks\/\d+\/(records|wrong-questions|review\/due|progress|materials|question-nav)$/.test(pathname)) {
    return { records: [], total: 0, totalQuestions: 5, answeredCount: 0 }
  }
  return { records: [], total: 0 }
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
await page.route(
  (url) => url.pathname.startsWith('/api/'),
  async (route) => {
    const p = new URL(route.request().url()).pathname
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: stub(p) })
    })
  }
)

/** 当前题号（题头里的「第 N 题」） */
async function currentNumber() {
  const text = await page.locator('.question-card').innerText()
  const m = text.match(/第\s*(\d+)\s*题/)
  return m ? Number(m[1]) : null
}
const selectedKeys = () => page.locator('.opt-row.selected').allInnerTexts()

console.log('打开做题页（假后端，5 题）')
await page.goto(`${BASE}/banks/1/practice?sessionId=${SESSION_ID}`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.question-card', { timeout: 15000 })
await page.waitForTimeout(800)

console.log('\n[1] 守卫依赖的 DOM 事实（任一命中都会让快捷键静默失效）')
const guards = await page.evaluate(() => ({
  overlays: document.querySelectorAll('.el-overlay').length,
  visibleOverlays: [...document.querySelectorAll('.el-overlay')].filter((el) => getComputedStyle(el).display !== 'none').length,
  drawers: document.querySelectorAll('.el-drawer').length,
  activeTag: document.activeElement ? document.activeElement.tagName : '(none)',
  kbHint: document.querySelector('.kb-hint') ? document.querySelector('.kb-hint').innerText : ''
}))
console.log(`    .el-overlay 总数=${guards.overlays}（可见 ${guards.visibleOverlays}） drawer=${guards.drawers} activeElement=${guards.activeTag}`)
// 关着的抽屉会留下 display:none 的 .el-overlay——这是正常的；不正常的只有"可见的弹层"。
// 曾经的守卫写的是"存在 .el-overlay 就 return"，于是快捷键被永久挡掉（本用例就是为它写的）。
check('没有可见的弹层（做题中不该有）', guards.visibleOverlays === 0, JSON.stringify(guards))
check('快捷键提示条在位', /切题/.test(guards.kbHint) && /选.*答案|选择答案/.test(guards.kbHint), guards.kbHint)

console.log('\n[2] ←/→ 切题')
const start = await currentNumber()
await page.keyboard.press('ArrowRight')
await page.waitForTimeout(300)
const afterRight = await currentNumber()
check('→ 下一题', afterRight === start + 1, `${start} → ${afterRight}`)
await page.keyboard.press('ArrowLeft')
await page.waitForTimeout(300)
check('← 上一题', (await currentNumber()) === start, `回到 ${await currentNumber()}`)

console.log('\n[3] A~H 选答案')
await page.keyboard.press('B')
await page.waitForTimeout(300)
const picked = await selectedKeys()
check('按 B 选中选项 B', picked.some((s) => /^\s*B/.test(s.trim())), JSON.stringify(picked))
await page.keyboard.press('C')
await page.waitForTimeout(300)
const picked2 = await selectedKeys()
check('单选再按 C 改选 C（不是叠加）', picked2.length === 1 && /^\s*C/.test(picked2[0].trim()), JSON.stringify(picked2))

console.log('\n[4] 数字直达题号')
await page.keyboard.press('4')
await page.waitForTimeout(1100)
check('按 4 跳到第 4 题', (await currentNumber()) === 4, String(await currentNumber()))

console.log('\n[5] 打开私教抽屉时不再抢键盘（真弹层要挡住）')
await page.locator('.q-head button', { hasText: '提示' }).click()
await page.waitForSelector('.tutor-input', { timeout: 8000 })
await page.locator('.tutor-input').fill('')
await page.locator('.tutor-input').type('ab12')
await page.waitForTimeout(400)
check('私教面板输入框里打字时题号没被数字键带走', (await currentNumber()) === 4, String(await currentNumber()))
check('输入框内容完整（按键没被拦截）', (await page.locator('.tutor-input').inputValue()) === 'ab12', await page.locator('.tutor-input').inputValue())
// 抽屉开着：方向键也不该切题（否则输入时会误切）
await page.keyboard.press('ArrowRight')
await page.waitForTimeout(300)
check('抽屉开着时方向键不切题', (await currentNumber()) === 4, String(await currentNumber()))
await page.keyboard.press('Escape')
await page.waitForTimeout(600)
await page.keyboard.press('ArrowRight')
await page.waitForTimeout(300)
check('关掉抽屉后方向键恢复可用', (await currentNumber()) === 5, String(await currentNumber()))

console.log('\n[6] 运行时错误')
check('无未捕获的 JS 错误', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
