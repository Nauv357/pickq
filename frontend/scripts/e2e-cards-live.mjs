/**
 * 闪卡（学习路径引擎阶段 3）**真后端**端到端。
 *
 * 为什么这一层必须有：闪卡的价值全在"**未确认不排期 → 确认后才进队列 → 记得拉长间隔 / 忘了立刻回来**"
 * 这条链路上，任何一环断了（比如未确认的卡也进了复习队列）用户都会被一堆没校对过的卡淹没。
 *
 * 覆盖：
 *   1. 从解析生成挖空卡（假模型返回按题号对应的卡），**未确认的卡不进复习队列**；
 *   2. 确认后才排期；复习「记得」→ 间隔变长、移出队列；「忘了」→ 归零、立刻回到队列、lapses+1；
 *   3. 手动编辑的卡视为人工确认（自己写的总不能不进队列）；
 *   4. 路线页「今天做什么」出现「复习闪卡 N 张」并点进闪卡页（与学习路线联动）。
 *
 * 说明：**抽测降级 / REGRESSED** 需要"8 天前的正确作答"才能构造出已过关节点，
 * 而本机作答记录只有真实作答才能写入（记录导入要求题库带内容包身份），
 * 所以那条路径由 `RoadmapServiceTest.clearedNodeDemotesOnFailedSpotCheckOrForgottenCard` 与
 * `spotCheckIsScheduledAfterTheIntervalLadder` 覆盖，这里不重复造假数据。
 *
 * 前置：临时数据目录 + ai-config.json 指向本脚本的假模型端口(18530) + 后端 8080 + 前端 dev 5199。
 * 用法：node scripts/e2e-cards-live.mjs [--api http://127.0.0.1:8080] [--ui http://localhost:5199] [--shots <目录>]
 */
import { chromium } from 'playwright-core'
import { existsSync, mkdirSync } from 'node:fs'
import http from 'node:http'

const argv = process.argv.slice(2)
const argOf = (name, dflt) => {
  const i = argv.indexOf(name)
  return i >= 0 ? argv[i + 1] : dflt
}
const API = argOf('--api', 'http://127.0.0.1:8080')
const UI = argOf('--ui', 'http://localhost:5199')
const AI_PORT = Number(argOf('--ai-port', '18530'))
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

/* ---------------- 假模型：按提示里的 questionId 返回挖空卡 ---------------- */
const prompts = []
const aiServer = http.createServer((req, res) => {
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8')
    prompts.push(raw)
    const cards = []
    // 提示里形如：【第 1 题】questionId=12\n题干：…\n解析：…（JSON 里换行是转义的，按 questionId= 切段）
    // 注意：切出来的段会一直延伸到 JSON 末尾，所以不能在里面找关键词判断题型——按顺序给固定公式即可
    // （本题库里第 1/2/3 题的解析分别讲增长率、人均、比重）。
    const FORMULAS = [
      ['增长率 = 现期 / ____ − 1', '基期'],
      ['人均可支配收入 = 可支配收入 / ____', '人口数'],
      ['比重 = 部分 / ____', '整体']
    ]
    const parts = raw.split(/questionId=/).slice(1)
    parts.forEach((part, i) => {
      const idMatch = part.match(/^(\d+)/)
      if (!idMatch) return
      const [front, back] = FORMULAS[Math.min(i, FORMULAS.length - 1)]
      cards.push({ questionId: Number(idMatch[1]), front, back })
    })
    const content = JSON.stringify({ cards })
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content } }] }))
  })
})
await new Promise((r) => aiServer.listen(AI_PORT, '127.0.0.1', r))

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
  const TPL = 'official.civil-service'

  /* ---------------- 造数据：3 道带解析的题（都打到同一个知识点） ---------------- */
  const bankId = await api('/banks', 'POST', { name: '闪卡端到端（临时）', description: 'e2e' })
  const qids = []
  const analyses = [
    '增长率 = 现期 / 基期 − 1：先算比值再减一，注意"增长了几倍"与"增长率"不是一回事，百分点用于比重差。',
    '人均可支配收入 = 可支配收入 / 人口数：注意分子分母口径要一致，不能用总收入除以就业人口。',
    '比重 = 部分 / 整体：比重的变化用"百分点"表示，而不是百分比，两者差 100 倍。'
  ]
  for (let i = 0; i < analyses.length; i++) {
    const qid = await api('/questions', 'POST', {
      bankId, volume: 1, questionType: 'SINGLE', questionNumber: i + 1,
      content: `第 ${i + 1} 题：请计算相关指标。`, analysis: analyses[i],
      options: [{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }], answerKeys: ['A'], score: 1
    })
    qids.push(qid)
  }
  await api(`/banks/${bankId}/skills/apply`, 'POST', {
    action: 'set', templateId: TPL, questionIds: qids, newNodes: ['gk.zl.growth']
  })
  console.log(`  已准备：题库 ${bankId}，3 道带解析的题`)

  const CHROME = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].find((p) => existsSync(p))
  if (!CHROME) {
    console.error('找不到 Chrome / Edge')
    process.exit(2)
  }
  browser = await chromium.launch({ executablePath: CHROME, headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 950 } })
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
  page.on('response', (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) console.log(`  [http ${r.status()}] ${r.url()}`)
  })
  const textOf = async (loc) => flat(await loc.innerText())

  /* ---------------- ① 生成：未确认、不进队列 ---------------- */
  await page.goto(`${UI}/banks/${bankId}/cards`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.cd-head', { timeout: 15000 })
  await page.locator('.cd-head button', { hasText: '从解析生成' }).click()
  await page.waitForTimeout(2500)
  const listText = await textOf(page.locator('.cd-list-card'))
  check('生成的卡出现在列表里（3 张）', (await page.locator('.cd-item').count()) === 3, String(await page.locator('.cd-item').count()))
  check('卡片显示挖空提示与答案要点', /增长率 = 现期 \/ ____/.test(listText) && /基期/.test(listText), listText.slice(0, 160))
  check('卡片显示出处题号（可溯源）', /出自第 \d+ 题/.test(listText), listText.slice(0, 160))
  check('AI 生成的卡标为待确认（不参与复习）', /待确认/.test(listText) && (await textOf(page.locator('.cd-head'))).includes('待确认 3'), listText.slice(0, 120))

  const statsBefore = await api(`/cards/stats?bankId=${bankId}`)
  check('后端：3 张卡全部未确认、0 张到期', statsBefore.total === 3 && statsBefore.unconfirmed === 3 && statsBefore.due === 0, JSON.stringify(statsBefore))
  const startBtn = page.locator('.cd-start')
  check('没有可复习的卡时按钮为禁用态', await startBtn.isDisabled())
  await page.screenshot({ path: `${SHOTS}/1-生成待确认.png` })

  /* ---------------- ② 确认后才排期 ---------------- */
  await page.locator('.cd-item').first().locator('button', { hasText: '确认' }).click()
  await page.waitForTimeout(1200)
  const afterConfirm = await api(`/cards/stats?bankId=${bankId}`)
  check('确认一张后立刻可复习（到期 +1）', afterConfirm.due === 1 && afterConfirm.unconfirmed === 2, JSON.stringify(afterConfirm))
  // 确认剩下的（列表会重排，所以循环点"第一个未确认的"）
  for (let i = 0; i < 5; i++) {
    const btn = page.locator('.cd-item button', { hasText: '确认' }).first()
    if ((await btn.count()) === 0) break
    await btn.click()
    await page.waitForTimeout(900)
  }
  check('全部确认后 3 张到期', (await api(`/cards/stats?bankId=${bankId}`)).due === 3)

  /* ---------------- ③ 复习：先想再翻，记得/忘了两档 ---------------- */
  await page.locator('.cd-start').click()
  await page.waitForSelector('.cd-review', { timeout: 8000 })
  const frontText = await textOf(page.locator('.cd-front'))
  check('复习时先只给挖空提示（不剧透答案）', /____/.test(frontText) && !/答案/.test(frontText), frontText.slice(0, 120))
  await page.locator('.cd-review-actions button', { hasText: '显示答案' }).click()
  await page.waitForTimeout(300)
  const revealed = await textOf(page.locator('.cd-back'))
  check('点「显示答案」才出现答案', /答案/.test(revealed), revealed.slice(0, 120))
  await page.locator('.cd-review-actions button', { hasText: '忘了' }).click()
  await page.waitForTimeout(600)
  await page.locator('.cd-review-actions button', { hasText: '显示答案' }).click().catch(() => {})
  await page.waitForTimeout(300)
  await page.locator('.cd-review-actions button', { hasText: '记得' }).click()
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${SHOTS}/2-复习中.png` })

  // 复习完一轮 → 退出，看调度结果
  await page.locator('.cd-review-head button', { hasText: '退出复习' }).click().catch(() => {})
  await page.waitForTimeout(1200)
  const cards = await api(`/cards?bankId=${bankId}&status=all`)
  const forgot = cards.find((c) => c.lapses > 0)
  const remembered = cards.find((c) => c.level > 0)
  check('「忘了」的卡：lapses+1、level 归零、立刻到期', !!forgot && forgot.level === 0 && forgot.lapses === 1, JSON.stringify(forgot || {}).slice(0, 140))
  check('「记得」的卡：升级、间隔变长、不再到期', !!remembered && remembered.intervalDays >= 2, JSON.stringify(remembered || {}).slice(0, 140))

  /* ---------------- ④ 编辑 = 人工确认 ---------------- */
  await page.locator('.cd-item').first().locator('button', { hasText: '编辑' }).click()
  await page.waitForSelector('.cd-form', { timeout: 8000 })
  const editedFront = '自己改的提示：增长率 = 现期 / ____ − 1'
  await page.locator('.cd-form textarea').first().fill(editedFront)
  await page.locator('.el-dialog__footer button', { hasText: '保存' }).click()
  await page.waitForTimeout(1200)
  const edited = (await api(`/cards?bankId=${bankId}&status=all`)).find((c) => c.front === editedFront)
  check('编辑后的卡被标为人工内容并保持已确认', !!edited && edited.source === 'user' && edited.confirmed, JSON.stringify(edited || {}).slice(0, 140))

  /* ---------------- ⑤ 与学习路线联动：今天要做闪卡 ---------------- */
  await page.goto(`${UI}/banks/${bankId}/roadmap`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.rm-today', { timeout: 15000 })
  await page.waitForTimeout(1200)
  const todayText = await textOf(page.locator('.rm-today'))
  check('路线页「今天做什么」出现闪卡任务', /闪卡/.test(todayText), todayText.slice(0, 160))
  await page.locator('.rm-task', { hasText: '闪卡' }).locator('button', { hasText: '开始练习' }).click()
  await page.waitForURL(/\/cards$/, { timeout: 10000 })
  check('点闪卡任务跳到闪卡页', /\/cards$/.test(new URL(page.url()).pathname))
  await page.screenshot({ path: `${SHOTS}/3-路线联动.png` })

  check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

  console.log(`\n结果：${pass} 通过 / ${fail} 失败（截图在 ${SHOTS}）`)
} finally {
  if (browser) await browser.close().catch(() => {})
  aiServer.close()
}
process.exit(fail === 0 ? 0 : 1)
