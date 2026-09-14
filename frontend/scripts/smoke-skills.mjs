/**
 * 知识点标签弹窗（学习路径引擎阶段 0）冒烟：Playwright + 本机 Chrome + 假后端。
 *
 * 覆盖点（都是这轮新增、且会真影响用户判断的地方）：
 *   1. 题库详情页有「知识点」入口，点开是独立弹窗（不挤在详情页里）；
 *   2. 技能图可切换，覆盖概览（总题/已有标签/已确认/待标注）与进度条按数据渲染；
 *   3. 待确认队列按节点聚合，**带样例题干**（用户要能核对，而不是盲点"确认"）；
 *   4. 「全部确认」发出 action=confirm、「改挂」发出 action=retag、「丢弃」发出 action=reject；
 *   5. 「分析并标注」按批发请求（maxAiCalls 有值、timeout=0），完成后给出结果提示；
 *   6. 覆盖地图里"没有这个知识点的题 / 题太少"要显式标出来（防漏刷的核心提示）。
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
  { templateId: 'official.civil-service', name: '公务员行测（通用）', version: '2026.09', graphVersion: '2026.09-aabb', nodeCount: 17, stageCount: 5 },
  { templateId: 'official.cs-ai-fullstack', name: 'AI 时代的现代化全栈', version: '2026.09', graphVersion: '2026.09-ccdd', nodeCount: 30, stageCount: 7 }
]
const NODES = [
  { nodeId: 'gk.pd.figure', name: '图形推理', stageId: 'm4', stageName: '判断推理', level: 3, weight: 1.2, optional: false, keywords: [], prereq: [] },
  { nodeId: 'gk.pd.logic', name: '逻辑判断', stageId: 'm4', stageName: '判断推理', level: 3, weight: 1.2, optional: false, keywords: [], prereq: [] },
  { nodeId: 'gk.zl.concept', name: '核心概念与公式', stageId: 'm5', stageName: '资料分析', level: 3, weight: 1.2, optional: false, keywords: [], prereq: [] }
]
const COVERAGE = {
  templateId: 'official.civil-service',
  graphVersion: '2026.09-aabb',
  totalQuestions: 172,
  coveredQuestions: 120,
  confirmedQuestions: 100,
  untaggedQuestions: 52,
  nodes: [
    { nodeId: 'gk.pd.figure', name: '图形推理', stageName: '判断推理', questionCount: 24, evidenceEnough: true, confirmed: true },
    { nodeId: 'gk.pd.logic', name: '逻辑判断', stageName: '判断推理', questionCount: 2, evidenceEnough: false, confirmed: false },
    { nodeId: 'gk.zl.concept', name: '核心概念与公式', stageName: '资料分析', questionCount: 0, evidenceEnough: false, confirmed: false }
  ]
}
const PENDING = [
  {
    nodeId: 'gk.pd.figure', name: '图形推理', questionCount: 12, avgConfidence: 0.91, groups: ['ai-direct'],
    samples: [
      { questionId: 11, preview: '从所给的四个选项中，选择最合适的一个填入问号处。' },
      { questionId: 12, preview: '下列图形中，不同类的是哪一个？' }
    ]
  },
  {
    nodeId: 'gk.pd.logic', name: '逻辑判断', questionCount: 2, avgConfidence: 0.62, groups: ['ai-direct'],
    samples: [{ questionId: 21, preview: '由此可以推出：' }]
  }
]

function stub(pathname) {
  if (pathname === '/api/banks/1') return { bankId: 1, name: '冒烟题库', description: 'e2e', questionCount: 3, version: '1.0.0', createdAt: '2026-01-01T00:00:00' }
  if (pathname === '/api/banks/1/questions') return { records: [], total: 0 }
  if (pathname === '/api/banks/1/question-nav') return []
  if (pathname === '/api/banks/1/progress') return { totalQuestions: 172, answeredCount: 10, correctCount: 8, recordsCount: 10, accuracy: 0.8 }
  if (pathname === '/api/banks/1/materials') return []
  if (pathname === '/api/skills/templates') return TEMPLATES
  if (pathname.startsWith('/api/skills/templates/') && pathname.endsWith('/sync')) return { nodesWritten: 17 }
  if (pathname.startsWith('/api/skills/templates/')) {
    const id = decodeURIComponent(pathname.split('/').pop())
    const tpl = TEMPLATES.find((t) => t.templateId === id) || TEMPLATES[0]
    return { ...tpl, nodes: NODES }
  }
  if (pathname === '/api/banks/1/skills/coverage') return COVERAGE
  if (pathname === '/api/banks/1/skills/pending') return PENDING
  if (pathname === '/api/banks/1/skills/suggest') return { templateId: 'official.civil-service', graphVersion: 'x', groupCount: 0, mappedGroups: 0, cachedGroups: 0, aiCalls: 5, taggedQuestions: 52, untaggedQuestions: 0, truncated: false, message: '分析完成' }
  if (pathname === '/api/banks/1/skills/apply') return { affected: 12 }
  if (pathname.startsWith('/api/ai-import/jobs')) return []
  if (pathname === '/api/home/overview') return { bankCount: 1, questionCount: 3, dueToday: 0, wrongCount: 0, recent: null }
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
    const p = new URL(req.url()).pathname
    const method = req.method()
    if (method !== 'GET') {
      calls.push({ method, path: p, body: req.postData() || '', query: new URL(req.url()).search })
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: stub(p) })
    })
  }
)

console.log('打开题库详情 → 「知识点」')
await page.goto(`${BASE}/banks/1`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1500)

const entry = page.locator('button', { hasText: '知识点' }).first()
check('详情页有「知识点」入口', (await entry.count()) > 0)
await entry.click()
await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
check('点开是独立弹窗（不是挤在页面里）', (await page.locator('.el-dialog .skill-toolbar').count()) === 1)

// 覆盖概览
const coverText = (await page.locator('.skill-cover').innerText()).replace(/\s+/g, ' ')
check('覆盖概览显示四个口径', /172/.test(coverText) && /120/.test(coverText) && /100/.test(coverText) && /52/.test(coverText), coverText)
check('覆盖百分比按数据计算（120/172≈70%）', coverText.includes('70'), coverText)
const barWidth = await page.locator('.skill-bar-fill').first().evaluate((el) => el.style.width)
check('进度条宽度与覆盖一致', barWidth === '70%', barWidth)

// 待确认队列
const items = page.locator('.skill-item')
check('待确认队列按节点聚合（2 个节点）', (await items.count()) === 2)
const firstItem = (await items.first().innerText()).replace(/\s+/g, ' ')
check('队列显示题量与平均把握', firstItem.includes('图形推理') && firstItem.includes('12') && firstItem.includes('0.91'), firstItem.slice(0, 90))
check('队列带样例题干（用户要能核对）', (await page.locator('.skill-sample').count()) >= 3)
check('低置信建议也显示出来（0.62）', firstItem.length > 0 && (await items.nth(1).innerText()).includes('0.62'))

// 批量确认
await items.first().locator('button', { hasText: '全部确认' }).click()
await page.waitForTimeout(400)
const confirmCall = calls.find((c) => c.path === '/api/banks/1/skills/apply' && c.body.includes('confirm'))
check('「全部确认」发出 action=confirm + nodeId', !!confirmCall && confirmCall.body.includes('gk.pd.figure'), confirmCall?.body || '(无请求)')

/** 等页面上的 element-plus 提示全部消失：避免读到上一步的旧 toast（断言就会假通过/假失败） */
async function waitToastsGone() {
  await page
    .waitForFunction(() => document.querySelectorAll('.el-message').length === 0, null, { timeout: 8000 })
    .catch(() => {})
}

// 改挂（页面上有两个 el-select、下拉是 teleport 出来的 → 限定可见下拉；先等旧 toast 消失并滚动到可视区）
await waitToastsGone()
let retagOk = true
let retagDetail = ''
try {
  await items.nth(1).scrollIntoViewIfNeeded()
  await items.nth(1).locator('.el-select__wrapper').click()
  const option = page.locator('.el-select-dropdown__item:visible').first()
  await option.waitFor({ state: 'visible', timeout: 5000 })
  await option.click()
  await page.waitForTimeout(200)
  await items.nth(1).locator('button', { hasText: '改挂' }).click()
  await page.waitForTimeout(500)
} catch (e) {
  retagOk = false
  retagDetail = String(e.message || e).replace(/\s+/g, ' ').slice(0, 400)
}
const retagCall = calls.find((c) => c.path === '/api/banks/1/skills/apply' && c.body.includes('retag'))
check('「改挂」发出 action=retag + newNodes', retagOk && !!retagCall && retagCall.body.includes('newNodes'),
  retagDetail || retagCall?.body || '(无请求)')

// 丢弃
await waitToastsGone()
await items.first().locator('button', { hasText: '丢弃' }).click()
await page.waitForTimeout(400)
check('「丢弃」发出 action=reject', calls.some((c) => c.body.includes('reject')))

// 分析（按批 + 不设超时）
await waitToastsGone()
await page.locator('.skill-toolbar-right button').first().click()
await page.waitForSelector('.el-message--success', { timeout: 20000 })
const suggestCall = calls.find((c) => c.path === '/api/banks/1/skills/suggest')
check('「分析并标注」按批发请求（maxAiCalls 有值）', !!suggestCall && /maxAiCalls=\d+/.test(suggestCall.query), suggestCall?.query || '(无请求)')
check('分析请求带 includeUntagged=true（当前数据没有 topic，必须逐题判定）', !!suggestCall && suggestCall.query.includes('includeUntagged=true'), suggestCall?.query)
const okMsg = (await page.locator('.el-message--success').first().innerText()).replace(/\s+/g, ' ')
check('分析完成后给出结果提示', okMsg.includes('标注') || okMsg.includes('Done'), okMsg)

// 覆盖地图
await page.locator('.el-tabs__item', { hasText: '覆盖地图' }).click()
await page.waitForTimeout(300)
const mapText = (await page.locator('.skill-map').innerText()).replace(/\s+/g, ' ')
check('覆盖地图按阶段分组', mapText.includes('判断推理') && mapText.includes('资料分析'), mapText.slice(0, 80))
check('没有题的节点显式标出（防漏刷核心提示）', mapText.includes('你没有这个知识点的题'), mapText.slice(0, 120))
check('题量不足的节点显式标出', mapText.includes('题太少'), mapText.slice(0, 120))

check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
