/**
 * 知识点标签弹窗（学习路径引擎阶段 0）冒烟：Playwright + 本机 Chrome + 假后端。
 *
 * 这一版是**按用户实测反馈重写**的，覆盖点都在用户明确抱怨过的地方：
 *   1. 覆盖口径必须一致：已确认 / 待确认 / 未匹配 **三段不重叠、相加 = 总题数**，
 *      另外单列"可用于判定掌握"（高置信）——不能再出现"26 待标注 vs 16 待确认"这种自相矛盾；
 *   2. 待确认队列要能看出**到底是哪几题**：按节点聚合 + 展开后逐题列出题号/题型/题干/置信度，
 *      并且能**逐题**确认或丢弃（不是只能整节点一刀切）；
 *   3. 「未匹配」单独一个页签，列出该状态下**全部题目**并可「打开题目」跳去补标签；
 *   4. 节点级操作齐备：全部确认 / 改挂（限定下拉）/ 全部丢弃 / 设为主题（回填 topic）；
 *   5. 「分析并标注」按批发请求（maxAiCalls 有值、timeout=0、includeUntagged=true），完成后给结果提示；
 *   6. 覆盖地图：空节点与题量不足的节点显式标出（防漏刷的核心提示）。
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
  { nodeId: 'gk.pd.figure.num', name: '图形推理·数量类', stageId: 'm4', stageName: '判断推理', level: 3, weight: 1.2, optional: false, keywords: ['笔画数'], prereq: [] },
  { nodeId: 'gk.pd.argue', name: '逻辑判断·论证', stageId: 'm4', stageName: '判断推理', level: 3, weight: 1.2, optional: false, keywords: [], prereq: [] },
  { nodeId: 'gk.zl.growth', name: '资料分析·增长', stageId: 'm5', stageName: '资料分析', level: 3, weight: 1.2, optional: false, keywords: [], prereq: [] }
]
// 三段不重叠：100 + 12 + 52 = 164（界面必须能对上账）
const COVERAGE = {
  templateId: 'official.civil-service',
  graphVersion: '2026.09-aabb',
  totalQuestions: 164,
  confirmedQuestions: 100,
  pendingQuestions: 12,
  untaggedQuestions: 52,
  usableQuestions: 108,
  nodes: [
    { nodeId: 'gk.pd.figure.num', name: '图形推理·数量类', stageName: '判断推理', questionCount: 24, evidenceEnough: true, confirmed: true },
    { nodeId: 'gk.pd.argue', name: '逻辑判断·论证', stageName: '判断推理', questionCount: 2, evidenceEnough: false, confirmed: false },
    { nodeId: 'gk.zl.growth', name: '资料分析·增长', stageName: '资料分析', questionCount: 0, evidenceEnough: false, confirmed: false }
  ]
}
const PENDING = [
  {
    nodeId: 'gk.pd.figure.num',
    name: '图形推理·数量类',
    questionCount: 12,
    avgConfidence: 0.91,
    groups: ['ai-direct'],
    samples: [{ questionId: 11, preview: '从所给的四个选项中，选择最合适的一个填入问号处。' }],
    questions: [
      { questionId: 11, questionNumber: 11, type: 'SINGLE', preview: '从所给的四个选项中，选择最合适的一个填入问号处（笔画数）。', confidence: 0.91 },
      { questionId: 12, questionNumber: 12, type: 'SINGLE', preview: '下列图形中，不同类的是哪一个？', confidence: 0.88 },
      { questionId: 13, questionNumber: 13, type: 'SINGLE', preview: '数一数，问号处应填哪个图形？', confidence: 0.83 }
    ]
  },
  {
    nodeId: 'gk.pd.argue',
    name: '逻辑判断·论证',
    questionCount: 2,
    avgConfidence: 0.62,
    groups: ['ai-direct'],
    samples: [{ questionId: 21, preview: '由此可以推出：' }],
    questions: [
      { questionId: 21, questionNumber: 21, type: 'SINGLE', preview: '由此可以推出：', confidence: 0.62 },
      { questionId: 22, questionNumber: 22, type: 'SINGLE', preview: '以下哪项如果为真，最能削弱上述结论？', confidence: 0.58 }
    ]
  }
]
const UNTAGGED = [
  { questionId: 31, questionNumber: 31, type: 'SINGLE', preview: '某市 2023 年 GDP 增长率为多少？', confidence: 0 },
  { questionId: 32, questionNumber: 32, type: 'SINGLE', preview: '下列关于行政复议的说法正确的是：', confidence: 0 },
  { questionId: 33, questionNumber: 33, type: 'ESSAY', preview: '请简述公文写作的基本要求。', confidence: 0 }
]

function questionDetail(id) {
  return {
    questionId: Number(id),
    bankId: 1,
    volume: 1,
    questionType: 'SINGLE',
    questionNumber: Number(id),
    content: `冒烟题干 #${id}`,
    options: [{ key: 'A', text: '选项一' }, { key: 'B', text: '选项二' }],
    answerKeys: ['A'],
    score: 1,
    topic: '',
    category: '',
    answerText: '',
    analysis: '',
    materialId: null,
    referenceAnswer: '',
    images: []
  }
}

function stub(pathname) {
  if (pathname === '/api/banks/1') return { bankId: 1, name: '冒烟题库', description: 'e2e', questionCount: 3, version: '1.0.0', createdAt: '2026-01-01T00:00:00' }
  if (pathname === '/api/banks/1/questions') return { records: [], total: 0 }
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
  if (pathname === '/api/banks/1/skills/pending') return PENDING
  if (pathname === '/api/banks/1/skills/questions') return UNTAGGED
  if (pathname === '/api/banks/1/skills/suggest') {
    return { templateId: 'official.civil-service', graphVersion: 'x', groupCount: 0, mappedGroups: 0, cachedGroups: 0, aiCalls: 5, taggedQuestions: 52, withoutUsableTag: 0, truncated: false, message: '分析完成' }
  }
  if (pathname === '/api/banks/1/skills/apply') return { affected: 12 }
  const q = pathname.match(/^\/api\/questions\/(\d+)$/)
  if (q) return questionDetail(q[1])
  if (/^\/api\/questions\/\d+\/skills$/.test(pathname)) return []
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
    } else {
      calls.push({ method, path: p, body: '', query: new URL(req.url()).search })
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: stub(p) })
    })
  }
)

/** 等页面上的 element-plus 提示全部消失：避免读到上一步的旧 toast（断言就会假通过/假失败） */
async function waitToastsGone() {
  await page
    .waitForFunction(() => document.querySelectorAll('.el-message').length === 0, null, { timeout: 8000 })
    .catch(() => {})
}

/** 关掉题目编辑器面板（它盖住了「知识点」入口按钮）；万一弹了"未保存"确认框就选放弃 */
async function closeEditorPanel() {
  const close = page.locator('.editor-dialog .panel-head button.icon-btn[title^="关闭"]').first()
  if (await close.isVisible().catch(() => false)) {
    await close.click()
    await page.waitForTimeout(400)
    const discard = page.locator('.el-message-box__btns button', { hasText: '放弃' }).first()
    if (await discard.isVisible().catch(() => false)) {
      await discard.click()
      await page.waitForTimeout(500)
    }
  }
  await page.waitForTimeout(400)
}
const flat = (s) => String(s).replace(/\s+/g, ' ')
const textOf = async (loc) => flat(await loc.innerText())

console.log('打开题库详情 → 「知识点」')
await page.goto(`${BASE}/banks/1`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1500)

const entry = page.locator('button', { hasText: '知识点' }).first()
check('详情页有「知识点」入口', (await entry.count()) > 0)
await entry.click()
await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
check('点开是独立弹窗（不是挤在页面里）', (await page.locator('.el-dialog .skill-toolbar').count()) === 1)

/* ---------- 1. 覆盖口径：三段不重叠 + 相加 = 总题数 ---------- */
const coverText = await textOf(page.locator('.skill-cover'))
check('三段口径齐全（已确认/待确认/未匹配）', /已确认/.test(coverText) && /待确认/.test(coverText) && /未匹配/.test(coverText), coverText)
check(
  '三段相加 = 总题数（100+12+52=164，不再自相矛盾）',
  /100/.test(coverText) && /12/.test(coverText) && /52/.test(coverText) && /164/.test(coverText),
  coverText
)
check('单列「可用于判定掌握」（高置信 108）', /108/.test(coverText), coverText)
const barOk = await page.locator('.skill-bar-ok').evaluate((el) => el.style.width)
const barMid = await page.locator('.skill-bar-mid').evaluate((el) => el.style.width)
check('进度条按已确认占比（100/164≈61%）', barOk === '61%', barOk)
check('进度条按待确认占比（12/164≈7%）', barMid === '7%', barMid)

/* ---------- 2. 待确认：能看出是哪几题 + 逐题处理 ---------- */
const items = page.locator('.skill-item')
check('待确认队列按节点聚合（2 个节点）', (await items.count()) === 2)
const firstItem = await textOf(items.first())
check('队列显示题量与平均把握', firstItem.includes('图形推理·数量类') && firstItem.includes('12 题') && firstItem.includes('0.91'), firstItem.slice(0, 100))
const qRows = items.first().locator('.skill-q')
check('默认展开、逐题列出（不再只有 3 条样例让人猜是哪题）', (await qRows.count()) === 3)
const q0 = await textOf(qRows.first())
check('单题显示题号 + 题干 + 置信度', q0.includes('11') && q0.includes('笔画数') && q0.includes('0.91'), q0)
check('未列全时提示还有多少题', firstItem.includes('还有 9 题未列出'), firstItem.slice(0, 160))
check('低置信节点也列出（0.62）', (await textOf(items.nth(1))).includes('0.62'))

await waitToastsGone()
await qRows.first().locator('button', { hasText: '确认此题' }).click()
await page.waitForTimeout(500)
const confirmOneCall = calls.find((c) => c.body.includes('confirm') && c.body.includes('[11]'))
check('逐题「确认此题」发出 confirm + questionIds=[11]', !!confirmOneCall, confirmOneCall?.body || '(无请求)')

await waitToastsGone()
await items.first().locator('.skill-q').nth(1).locator('button', { hasText: '丢弃建议' }).click()
await page.waitForTimeout(500)
const rejectOneCall = calls.find((c) => c.body.includes('reject') && c.body.includes('[12]'))
check('逐题「丢弃建议」发出 reject + questionIds=[12]', !!rejectOneCall, rejectOneCall?.body || '(无请求)')

/* ---------- 3. 节点级：全部确认 / 改挂 / 全部丢弃 / 设为主题 ---------- */
await waitToastsGone()
await items.first().locator('button', { hasText: '本节点全部确认' }).click()
await page.waitForTimeout(500)
const confirmNodeCall = calls.find((c) => c.body.includes('confirm') && c.body.includes('gk.pd.figure.num') && !c.body.includes('questionIds'))
check('「本节点全部确认」发出 confirm + nodeId（不带 questionIds）', !!confirmNodeCall, confirmNodeCall?.body || '(无请求)')

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
  retagDetail = flat(String(e.message || e)).slice(0, 400)
}
const retagCall = calls.find((c) => c.body.includes('retag'))
check('「改挂」发出 action=retag + newNodes（按钮不再误显示成"重新分析"）', retagOk && !!retagCall && retagCall.body.includes('newNodes'), retagDetail || retagCall?.body || '(无请求)')

await waitToastsGone()
await items.first().locator('button', { hasText: '全部丢弃' }).click()
await page.waitForTimeout(500)
check('「全部丢弃」发出 action=reject + nodeId', calls.some((c) => c.body.includes('reject') && c.body.includes('gk.pd.figure.num')), '(无请求)')

await waitToastsGone()
await items.first().locator('button', { hasText: '设为主题' }).click()
await page.waitForTimeout(500)
const backfillCall = calls.find((c) => c.body.includes('backfill-topic'))
check('「设为主题」发出 backfill-topic + topic（把 AI 分组顺手写回题目主题）', !!backfillCall && backfillCall.body.includes('图形推理·数量类'), backfillCall?.body || '(无请求)')

/* ---------- 4. 未匹配页签：列出全部题并可跳去补 ---------- */
await waitToastsGone()
await page.locator('.el-tabs__item', { hasText: '未匹配' }).click()
await page.waitForTimeout(600)
const untaggedPane = page.locator('.el-tab-pane:visible')
const untaggedRows = untaggedPane.locator('.skill-q')
check('未匹配页签列出该状态的全部题（3 题）', (await untaggedRows.count()) === 3)
const u0 = await textOf(untaggedRows.first())
check('未匹配清单显示题号与题干', u0.includes('31') && u0.includes('GDP'), u0)
const untaggedReq = calls.find((c) => c.path === '/api/banks/1/skills/questions' && c.query.includes('status=untagged'))
check('未匹配清单来自按状态查询接口', !!untaggedReq, untaggedReq?.query || '(无请求)')
check('未匹配页签的标签数来自覆盖口径（52）', (await textOf(page.locator('.el-tabs__item').nth(1))).includes('52'), await textOf(page.locator('.el-tabs__item').nth(1)))

await untaggedRows.first().locator('button', { hasText: '打开题目' }).click()
await page.waitForTimeout(800)
check('「打开题目」关掉弹窗并跳到题目编辑器', !(await page.locator('.el-dialog .skill-toolbar').isVisible().catch(() => false)))

/* ---------- 5. 重新打开 → 分析（按批）与覆盖地图 ---------- */
await closeEditorPanel()
await page.locator('button', { hasText: '知识点' }).first().click()
await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
await waitToastsGone()
await page.locator('.skill-toolbar-right button').first().click()
await page.waitForSelector('.el-message--success', { timeout: 20000 })
const suggestCall = calls.find((c) => c.path === '/api/banks/1/skills/suggest')
check('「分析并标注」按批发请求（maxAiCalls 有值）', !!suggestCall && /maxAiCalls=\d+/.test(suggestCall.query), suggestCall?.query || '(无请求)')
check('分析请求带 includeUntagged=true（当前数据没有 topic，必须逐题判定）', !!suggestCall && suggestCall.query.includes('includeUntagged=true'), suggestCall?.query)
const okMsg = await textOf(page.locator('.el-message--success').first())
check('分析完成后给出结果提示', okMsg.includes('标注') || okMsg.includes('Done'), okMsg)

await page.locator('.el-tabs__item', { hasText: '覆盖地图' }).click()
await page.waitForTimeout(300)
const mapText = await textOf(page.locator('.skill-map'))
check('覆盖地图按阶段分组', mapText.includes('判断推理') && mapText.includes('资料分析'), mapText.slice(0, 80))
check('没有题的节点显式标出（防漏刷核心提示）', mapText.includes('你没有这个知识点的题'), mapText.slice(0, 140))
check('题量不足的节点显式标出', mapText.includes('题太少'), mapText.slice(0, 140))

check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await browser.close()
console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
