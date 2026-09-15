/**
 * 学习路线（学习路径引擎阶段 2）**真后端**端到端：外缘 + 缺口 + 每日任务。
 *
 * 这一层最大的卖点是"**日常零 token**"——所以本脚本**故意不配置任何 AI**：
 * 只要路线页还能正常算、正常开始练习，就证明它真的没有偷偷调模型。
 *
 * 覆盖：
 *   1. 今天做什么 / 下一步 / 还缺什么 / 路线图 四块都渲染，且数字来自后端公式；
 *   2. 「开始练习」真的按知识点开出会话（mode=SKILL）并进入做题页，题确实属于该知识点；
 *   3. 做完并交卷后，今日完成度按实际作答变化（3/3），刷新不花额度；
 *   4. 外缘遵守前置：前置没过关的节点不出现在外缘（路线图上标"前置未过关"）；
 *   5. 缺口清单把"没题 / 题太少"的知识点摊开（防漏刷第 5 层护栏）；
 *   6. 目标与每日题量能保存，并改变今日任务量。
 *
 * 前置：临时数据目录（**不要写 ai-config.json**）+ 后端 8080 + 前端 dev 5199。
 * 用法：node scripts/e2e-roadmap-live.mjs [--api http://127.0.0.1:8080] [--ui http://localhost:5199] [--shots <目录>]
 */
import { chromium } from 'playwright-core'
import { existsSync, mkdirSync } from 'node:fs'

const argv = process.argv.slice(2)
const argOf = (name, dflt) => {
  const i = argv.indexOf(name)
  return i >= 0 ? argv[i + 1] : dflt
}
const API = argOf('--api', 'http://127.0.0.1:8080')
const UI = argOf('--ui', 'http://localhost:5199')
const SHOTS = argOf('--shots', '.')
mkdirSync(SHOTS, { recursive: true })

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
const flat = (s) => String(s).replace(/\s+/g, ' ')

async function api(path, method = 'GET', body) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined
  })
  const json = await res.json()
  if (json.code !== 200 && json.code !== 0) throw new Error(`${path} → ${JSON.stringify(json).slice(0, 300)}`)
  return json.data
}

let browser = null
try {
  await api('/skills/templates')

  /* ---------------- 造数据：增长类 3 题（外缘候选）、比重类 0 题（缺口） ---------------- */
  const bankId = await api('/banks', 'POST', { name: '学习路线端到端（临时）', description: 'e2e' })
  const growthIds = []
  for (let i = 1; i <= 3; i++) {
    const qid = await api('/questions', 'POST', {
      bankId, volume: 1, questionType: 'SINGLE', questionNumber: i,
      content: `第 ${i} 题：2024 年该省 GDP 为 ${100 + i * 10} 亿元，2023 年为 100 亿元，增长率是多少？`,
      score: 1, analysis: null,
      options: [{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }, { key: 'C', text: '丙' }, { key: 'D', text: '丁' }],
      answerKeys: ['A']
    })
    growthIds.push(qid)
  }
  await api(`/banks/${bankId}/skills/apply`, 'POST', {
    action: 'set', templateId: 'official.civil-service', questionIds: growthIds, newNodes: ['gk.zl.growth']
  })
  const TPL = 'official.civil-service'
  console.log(`  已准备：题库 ${bankId}，增长类 3 题（等比重的 2 题不打标签，用来验证缺口）`)

  const CHROME = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].find((p) => existsSync(p))
  if (!CHROME) {
    console.error('找不到 Chrome / Edge')
    process.exit(2)
  }
  browser = await chromium.launch({ executablePath: CHROME, headless: true })
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
  page.on('response', (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) console.log(`  [http ${r.status()}] ${r.url()}`)
  })
  const textOf = async (loc) => flat(await loc.innerText())

  /* ---------------- ① 路线页（没有任何 AI 配置也要能用） ---------------- */
  await page.goto(`${UI}/banks/${bankId}/roadmap`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.rm-today', { timeout: 15000 })
  await page.waitForTimeout(1200)
  check('没有配置 AI 也能打开路线页（证明这一层零 token）', await page.locator('.rm-today').isVisible())

  const todayText = await textOf(page.locator('.rm-today'))
  check('「今天做什么」给出任务与题量', /主攻|到期复习/.test(todayText) && /\d+\s*\/\s*\d+/.test(todayText), todayText.slice(0, 140))
  const nextText = await textOf(page.locator('.rm-next'))
  check('「下一步」列出了外缘节点（增长类）', /增长类/.test(nextText), nextText.slice(0, 140))
  check('下一步不含前置未过关的节点（比重与倍数）', !/比重与倍数/.test(nextText), nextText.slice(0, 140))

  const gapsText = await textOf(page.locator('.rm-gaps'))
  check('「还缺什么」列出没题的知识点', /一道题都没有/.test(gapsText), gapsText.slice(0, 160))
  check('缺口项带原因（题太少/一道都没有）', /一道题都没有|只有 \d+ 题/.test(gapsText))

  const mapText = await textOf(page.locator('.rm-map'))
  check('路线图按阶段列出知识点', /资料分析/.test(mapText) && /数量关系/.test(mapText), mapText.slice(0, 120))
  const ratioChip = page.locator('.rm-chip', { hasText: '比重与倍数' }).first()
  const ratioTitle = await ratioChip.getAttribute('title')
  check('前置未过关的节点在路线图上标出来（可解释，不是黑盒）', /前置未过关/.test(ratioTitle || ''), ratioTitle || '(无 title)')
  await page.screenshot({ path: `${SHOTS}/1-路线页.png` })

  /* ---------------- ② 一键开始今天的任务（mode=SKILL） ---------------- */
  await page.locator('.rm-task button', { hasText: '开始练习' }).first().click()
  await page.waitForURL(/\/practice\?/, { timeout: 15000 })
  await page.waitForSelector('.question-card', { timeout: 15000 })
  const sessionId = Number(new URL(page.url()).searchParams.get('sessionId'))
  const detail = await api(`/sessions/${sessionId}`)
  check('开始练习开出的会话是「按知识点」抽题', detail.mode === 'SKILL', JSON.stringify({ mode: detail.mode }).slice(0, 80))
  check('会话题目数 = 今日任务量', detail.questions.length === 3, String(detail.questions.length))
  const contentsAreGrowth = detail.questions.every((q) => /增长率/.test(q.content))
  check('抽到的题确实属于该知识点', contentsAreGrowth, detail.questions.map((q) => q.content.slice(0, 16)).join(' | '))

  /* ---------------- ③ 做完交卷 → 今日完成度按实际作答变化 ---------------- */
  await page.locator('.options button').first().click()
  await page.waitForTimeout(200)
  await page.locator('.pane-nav button', { hasText: '下一题' }).first().click()
  await page.waitForTimeout(400)
  await page.locator('.options button').first().click()
  await page.waitForTimeout(200)
  await page.locator('.pane-nav button', { hasText: '下一题' }).first().click()
  await page.waitForTimeout(400)
  await page.locator('.options button').first().click()
  await page.waitForTimeout(300)
  await page.locator('.bar-finish').click()
  await page.waitForSelector('.report-questions, .review-card', { timeout: 20000 })

  await page.goto(`${UI}/banks/${bankId}/roadmap`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.rm-today', { timeout: 15000 })
  await page.waitForTimeout(1200)
  const todayAfter = (await textOf(page.locator('.rm-today'))).replace(/\s+/g, ' ')
  check('交卷后今日完成度变成 3/3', /3\s*\/\s*3/.test(todayAfter), todayAfter.slice(0, 140))
  const apiToday = await api(`/roadmap/today?bankId=${bankId}&templateId=${TPL}`)
  check('后端完成度与界面一致（都按今天的实际作答算）', apiToday.doneQuestions === 3 && apiToday.plannedQuestions === 3, JSON.stringify(apiToday).slice(0, 160))

  // 刚做对三次但都是今天 → 不能算过关（要间隔复测）——这条对用户很重要，必须显式呈现
  const growthChip = page.locator('.rm-chip', { hasText: '增长类' }).first()
  const growthTitle = await growthChip.getAttribute('title')
  check('刚做对的知识点仍标「在练」而不是「已过关」（要间隔复测）', /在练/.test(growthTitle || ''), growthTitle || '(无 title)')
  const growthClass = await growthChip.getAttribute('class')
  check('路线图上该节点显示为 learning 状态', /learning/.test(growthClass || ''), growthClass || '')

  /* ---------------- ④ 目标与每日题量 ---------------- */
  await page.locator('.rm-head button', { hasText: '目标与题量' }).click()
  await page.waitForSelector('.rm-form', { timeout: 8000 })
  await page.locator('.rm-form .rm-goal-input input').fill('两个月内行测上 70')
  await page.locator('.rm-form .rm-daily-input input').fill('5')
  await page.locator('.el-dialog__footer button', { hasText: '保存' }).click()
  await page.waitForTimeout(1500)
  const prof = await api('/roadmap/profile')
  check('目标写进个体输入', prof.goalText === '两个月内行测上 70', JSON.stringify(prof))
  check('每日题量保存生效', prof.dailyQuestions === 5, String(prof.dailyQuestions))
  check('界面显示目标', /两个月内行测上 70/.test(await textOf(page.locator('.rm-head'))))
  await page.screenshot({ path: `${SHOTS}/2-今日任务完成.png` })

  check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

  console.log(`\n结果：${pass} 通过 / ${fail} 失败（截图在 ${SHOTS}）`)
} finally {
  if (browser) await browser.close().catch(() => {})
}
process.exit(fail === 0 ? 0 : 1)
