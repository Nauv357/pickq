/**
 * AI 配置区冒烟：选服务商后自动获取可用模型并选好默认值 + 文案精简后的表单可用。
 *
 * 覆盖点：
 *   1. 只选服务商、没填 Key 时不去请求模型（避免无谓报错）；
 *   2. 填上 API Key 后自动 POST /api/ai/models，并自动选中一个"对话模型"（排除向量/语音/画图模型）；
 *   3. 多模态模型自动挑带 vl/vision 的那个；
 *   4. 预设自带的模型名若仍在可用列表里，保持不换；
 *   5. 提示文案里说明了自动选择结果。
 *
 * 用法：node scripts/smoke-ai-setup.mjs [--url http://localhost:5199]
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

/** 故意混入向量模型与画图模型，验证自动挑选会跳过它们 */
const MODEL_LIST = ['text-embedding-v3', 'dall-e-3', 'deepseek-reasoner', 'deepseek-chat', 'qwen-vl-max', 'whisper-1']

const calls = []
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
    const req = route.request()
    const p = new URL(req.url()).pathname
    calls.push({ method: req.method(), path: p, body: req.postData() })
    let data = {}
    if (p === '/api/ai/settings') data = { baseUrl: '', model: '', visionModel: '', hasKey: false, maskedKey: '' }
    else if (p === '/api/ai/models') data = { models: MODEL_LIST, count: MODEL_LIST.length, resolvedBaseUrl: JSON.parse(req.postData() || '{}').baseUrl || '' }
    else if (p === '/api/ai/presets') data = { presets: [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data }) })
  }
)

const modelSelects = () => page.locator('.ai-grid .el-select')
/** el-select 的选中值渲染在元素文本里（filterable 的 input 平时是空的），所以读 innerText */
async function selectedValue(i) {
  return (await modelSelects().nth(i).innerText()).replace(/\s+/g, ' ').trim()
}

console.log('打开设置页')
await page.goto(`${BASE}/settings`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.preset-row .el-select', { timeout: 15000 })
check('AI 配置区渲染', await page.locator('.ai-grid').isVisible())

console.log('\n[1] 选服务商（未填 Key）')
await page.locator('.preset-row .el-select').click()
await page.waitForSelector('.el-select-dropdown__item', { timeout: 5000 })
await page.locator('.el-select-dropdown__item', { hasText: 'DeepSeek' }).first().click()
await page.waitForTimeout(900)
const fetchBeforeKey = calls.filter((c) => c.path === '/api/ai/models')
check('未填 Key 时不请求模型列表', fetchBeforeKey.length === 0, JSON.stringify(fetchBeforeKey))
const baseUrlVal = await page.locator('.ai-grid input').first().inputValue()
check('服务商地址已自动填入', /deepseek\.com/.test(baseUrlVal), baseUrlVal)

console.log('\n[2] 填 API Key → 自动获取并选中模型')
await page.locator('.ai-grid input[type="password"]').first().fill('sk-smoke-test-1234567890')
await page.waitForTimeout(1800)
const fetchAfterKey = calls.filter((c) => c.path === '/api/ai/models')
check('填 Key 后自动请求模型列表', fetchAfterKey.length === 1, `${fetchAfterKey.length} 次`)
check('请求体带 Base URL 与 Key', /deepseek\.com/.test(fetchAfterKey[0]?.body || '') && /sk-smoke/.test(fetchAfterKey[0]?.body || ''), fetchAfterKey[0]?.body)
const textModel = await selectedValue(0)
const visionModel = await selectedValue(1)
check('自动选中对话模型（跳过向量/画图/语音模型）', textModel === 'deepseek-chat', textModel)
check('多模态模型自动选中带 vl 的那个', visionModel === 'qwen-vl-max', visionModel)
const msgText = (await page.locator('.el-message').allInnerTexts()).join(' | ')
check('提示里说明自动选择了哪个模型', /deepseek-chat/.test(msgText), msgText)

console.log('\n[3] 重复选另一个服务商：模型列表重新获取')
calls.length = 0
await page.locator('.preset-row .el-select').click()
await page.waitForSelector('.el-select-dropdown__item', { timeout: 5000 })
await page.locator('.el-select-dropdown__item', { hasText: 'OpenAI' }).first().click()
await page.waitForTimeout(1800)
check('换服务商后重新请求模型列表', calls.filter((c) => c.path === '/api/ai/models').length === 1)
const textModel2 = await selectedValue(0)
check('新服务商下模型名已换成可用模型', MODEL_LIST.includes(textModel2), textModel2)

console.log('\n[4] 精简后的说明文案就位')
// 注意：备份区描述里也提到"AI 模型配置"，所以按面板标题 h2 定位，不能按 hasText 找
const aiPanel = page.locator('section.panel').filter({ has: page.locator('h2', { hasText: 'AI 模型配置' }) })
const panelText = (await aiPanel.innerText()).replace(/\s+/g, ' ')
check('面板说明不再堆供应商清单', panelText.length < 900, `长度 ${panelText.length}`)
check('保留"申请 Key"入口', /查看分步教程/.test(panelText), panelText.slice(0, 120))

console.log('\n[5] 运行时错误')
check('无未捕获的 JS 错误', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
