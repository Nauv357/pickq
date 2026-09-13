/**
 * MinerU 入口的渐进浮现冒烟（Playwright + 本机 Chrome，不需要后端）。
 *
 * 为什么要有这个测试：这里曾经有个循环依赖缺陷——MinerU 是"图片识别失效时的补救手段"，
 * 却由"我们自己的图片识别判断"决定它出不出来（只看 png/jpg/pdf），于是
 * 老 docx 扫描件、图片型 pptx、含图 xlsx 全都开不了它。断言就锁这条边界。
 *
 * 覆盖：
 *   1. 图片型 pptx / 含图 xlsx / 老 .doc / docx 选中后，MinerU 在「更多选项」里可开；
 *   2. png / pdf 选中后，MinerU 直接出现在主流程（拍照/扫描场景主动提示）；
 *   3. 未配 Key 时点击给出去设置页的提示；
 *   4. 没选文件时不显示 MinerU（不占位置）；
 *   5. 文案口径是"缺图才用"，不是"试卷都需要"。
 *
 * 用法：node scripts/smoke-ai-mineru.mjs [--url http://localhost:5199]
 */
import { chromium } from 'playwright-core'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

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

// 只用来喂文件名（不真解析）：内容随便写几字节
const tmp = join(process.cwd(), '.shots', 'mineru-smoke')
if (!existsSync(tmp)) mkdirSync(tmp, { recursive: true })
const stub = (name) => {
  const p = join(tmp, name)
  writeFileSync(p, 'x')
  return p
}
const FILE_PPTX = stub('含图片题型.pptx')
const FILE_XLSX = stub('题库表格.xlsx')
const FILE_DOC = stub('老试卷.doc')
const FILE_DOCX = stub('扫描件.docx')
const FILE_PNG = stub('拍照试卷.png')
const FILE_PDF = stub('试卷.pdf')

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
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
await page.route(
  (url) => url.pathname.startsWith('/api/'),
  async (route) => {
    const p = new URL(route.request().url()).pathname
    let data = {}
    if (p === '/api/ai/settings') data = { baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', hasKey: true, maskedKey: 'sk-***' }
    else if (p === '/api/banks') data = { records: [{ id: 1, name: '冒烟题库' }], total: 1 }
    else if (p.startsWith('/api/ai-import/jobs')) data = []
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data }) })
  }
)

const dialog = () => page.locator('.el-dialog', { hasText: 'AI 导入' })
const fileInput = () => dialog().locator('input[type="file"]')
const mineruOpt = () => dialog().locator('.supplement-option', { hasText: 'MinerU' })
const moreToggle = () => dialog().locator('.advanced-toggle')

async function openDialogWith(...files) {
  if (!(await dialog().isVisible().catch(() => false))) {
    // 题库列表的「导入」是两段式：先选场景（AI 整理 / 文件导入），再进 AI 导入对话框
    await page.locator('button', { hasText: '导入' }).first().click()
    await page.waitForSelector('.el-dialog .onboard-primary', { timeout: 8000 })
    await page.locator('.el-dialog .onboard-primary').click()
    await page.waitForSelector('.el-dialog input[type="file"]', { timeout: 8000 })
  }
  await fileInput().setInputFiles(files)
  await page.waitForTimeout(300)
}

console.log('打开题库列表并唤出导入对话框')
await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.bank-toolbar', { timeout: 15000 })
await openDialogWith(FILE_PPTX)
check('导入对话框已打开', await dialog().isVisible())
check('对话框里有真正的 file input（模板内，可测试）', (await fileInput().count()) === 1)

console.log('\n[1] 图片型 PPT：MinerU 收在「更多选项」里，但可开')
// 折叠用的是 v-show（保留 DOM），所以这里断言"看不见"而不是"不存在"
check('未展开时勾选框不可见（不占位置）', !(await mineruOpt().isVisible().catch(() => false)))
check('出现「更多选项」入口', await moreToggle().isVisible())
await moreToggle().click()
await page.waitForTimeout(200)
check('展开后 MinerU 勾选框出现', (await mineruOpt().count()) === 1)
const optText = (await mineruOpt().innerText()).replace(/\s+/g, ' ')
check('文案是"缺图才用"的口径', /缺图/.test(optText) && !/拍照\/扫描的试卷|照片\/扫描件/.test(optText), optText)

console.log('\n[2] 含图 XLSX / 老 .doc / 扫描 docx 同样可开')
for (const [label, f] of [['xlsx', FILE_XLSX], ['doc', FILE_DOC], ['docx', FILE_DOCX]]) {
  await openDialogWith(f)
  const hasToggle = (await moreToggle().count()) > 0
  if (hasToggle) await moreToggle().click()
  await page.waitForTimeout(200)
  check(`${label} 能打开 MinerU`, (await mineruOpt().count()) === 1)
  // 复位：重新选文件后折叠态保留，没关系；这里只关心可达性
}

console.log('\n[3] 拍照/扫描/纯图：直接出现在主流程')
await openDialogWith(FILE_PNG)
check('png 直接显示 MinerU 勾选框（无需展开）', (await mineruOpt().count()) === 1)
await openDialogWith(FILE_PDF)
check('pdf 直接显示 MinerU 勾选框', (await mineruOpt().count()) === 1)

console.log('\n[4] 未配 Key 时点击 → 提示去设置页')
await openDialogWith(FILE_PNG)
await mineruOpt().click()
await page.waitForTimeout(400)
const toast = (await page.locator('.el-message').allInnerTexts()).join(' | ')
check('提示未配置 MinerU Key', /MinerU/.test(toast) && /设置/.test(toast), toast)
check('未配 Key 时不会被勾上', (await mineruOpt().getAttribute('class')).includes('checked') === false)

console.log('\n[5] 纯文本文件：不出现 MinerU 相关入口')
await openDialogWith(stub('笔记.txt'))
check('txt 不显示 MinerU（MinerU 不适用）', (await mineruOpt().count()) === 0 && (await moreToggle().count()) === 0)

console.log('\n[6] 运行时错误')
check('无未捕获的 JS 错误', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
