/**
 * 「导入题库文件（.tiku）」冒烟（Playwright + 本机 Chrome，不需要后端）。
 *
 * 锁的是一个**从首个版本起就存在、且完全静默**的缺陷（用户实测："点导入没反应，
 * 刷新页面后其实是导入了的"）：
 *   1. `utils/files.js` 的 `readArrayBuffer` 漏了 `reader.onload` → Promise 永不 settle
 *      → 点「别人发来了题库文件」后**一个请求都不会发出去**，界面毫无变化；
 *   2. 因为 import 的 finally 不执行，`importing` 一直卡在 true → 之后每次点「导入」
 *      都被守卫 `if (importing.value) return` 直接吞掉（"点了没反应"）；
 *   3. 导入请求还走全局 axios timeout 15s，慢机器上会被客户端中止（后端其实在跑）。
 *
 * 断言（对应修法）：
 *   A. 选完文件后**确实发出了** POST /api/banks/import-tiku，且 body 非空（核心回归）；
 *   B. 请求进行中：主按钮变成"正在导入题库…"且禁用、全屏遮罩出现，
 *      3 秒后遮罩文案带上"已用 N 秒"（用户看得见"它在干活"）；
 *   C. 结束后：成功提示出现、按钮恢复可用（守卫不会把后续点击吞掉）；
 *   D. 无 JS 报错。
 *
 * 用法：node scripts/smoke-import-file.mjs [--url http://localhost:5199]
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
const BANKS = [
  { id: 1, name: '已有题库', description: '', version: '1.0.0', authorName: '', createdAt: '2026-01-01T10:00:00', questionCount: 3, answeredCount: 1 }
]
const importCalls = [] // 每次导入请求的 body 字节数
const IMPORT_DELAY_MS = 3800 // 超过 3 秒，才能验证"已用 N 秒"提示

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
    const json = (data) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data }) })

    if (method === 'POST' && p === '/api/banks/import-tiku') {
      // 关键：记录是否真的收到了请求（历史 bug 是一个请求都发不出）
      importCalls.push((req.postDataBuffer() || Buffer.alloc(0)).length)
      await new Promise((r) => setTimeout(r, IMPORT_DELAY_MS))
      return json({ result: 'CREATED', bankId: 9, message: '导入成功' })
    }
    if (p === '/api/banks') return json({ records: BANKS, total: BANKS.length })
    if (p === '/api/home/overview') return json({ bankCount: 1, questionCount: 3, dueTotal: 0, wrongCount: 0, lastSession: null })
    if (p.startsWith('/api/ai-import/jobs')) return json([])
    if (p === '/api/ai/settings') return json({ hasKey: true, baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' })
    return json({ records: [], total: 0 })
  }
)

console.log('打开题库列表并走「导入 → 别人发来了题库文件」')
await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.bank-card', { timeout: 15000 })

const importBtn = page.locator('button.btn-primary', { hasText: '导入' }).first()
await importBtn.click()
await page.waitForSelector('.el-dialog', { timeout: 5000 })
check('导入入口先给出二选一（AI 整理 / 题库文件）', (await page.locator('.el-dialog .onboard-card').count()) === 2)

// 选文件：Playwright 直接喂给隐藏的 <input type="file">（pickFile 用 document.createElement 建的）
const chooserPromise = page.waitForEvent('filechooser', { timeout: 5000 })
await page.locator('.el-dialog .onboard-card', { hasText: '别人发来了题库文件' }).click()
const chooser = await chooserPromise
const tikuBytes = Buffer.concat([Buffer.from('PK\u0003\u0004', 'latin1'), Buffer.alloc(2048, 7)])
await chooser.setFiles({ name: 'DDDDDD-000001-1.0.0.tiku', mimeType: 'application/zip', buffer: tikuBytes })

// B. 进行中的可见状态（请求被假后端压住 3.8s）
await page.waitForTimeout(600)
check('请求已发出（读文件 Promise 会 resolve）', importCalls.length === 1, `importCalls=${importCalls.length}`)
check('请求体非空（zip 字节真的传上去了）', (importCalls[0] || 0) === tikuBytes.length, `bytes=${importCalls[0]}`)
const btnText = (await importBtn.innerText()).trim()
check('主按钮显示"正在导入题库…"', btnText.includes('正在导入题库'), btnText)
check('导入期间主按钮禁用（防重复提交）', await importBtn.isDisabled())
const mask = page.locator('.el-loading-mask')
check('出现全屏"正在导入…"遮罩', (await mask.count()) >= 1)
const maskTextEarly = (await mask.first().innerText().catch(() => '')).replace(/\s+/g, ' ')
check('遮罩文案说明在导入题库', maskTextEarly.includes('正在导入题库'), maskTextEarly)
await page.waitForTimeout(3200)
const maskTextLate = (await mask.first().innerText().catch(() => '')).replace(/\s+/g, ' ')
check('3 秒后补充"已用 N 秒"（让用户知道它在干活）', /已用\s*\d+\s*秒/.test(maskTextLate), maskTextLate)

// C. 结束状态
await page.waitForSelector('.el-message--success', { timeout: 15000 })
const okText = (await page.locator('.el-message--success').first().innerText()).replace(/\s+/g, ' ')
check('导入成功后给出结果提示', okText.includes('导入成功'), okText)
await page.waitForTimeout(400)
check('遮罩已关闭', (await page.locator('.el-loading-mask').count()) === 0)
check('按钮恢复可用（后续点击不会被守卫吞掉）', !(await importBtn.isDisabled()))
const btnTextAfter = (await importBtn.innerText()).trim()
check('按钮文案复原为「导入」', btnTextAfter === '导入', btnTextAfter)
check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
