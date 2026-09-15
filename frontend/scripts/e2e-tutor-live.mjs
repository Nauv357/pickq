/**
 * AI 私教（学习路径引擎阶段 1）+ 一键配题（阶段 3.5）**真后端**端到端：
 *   脚本内起一个假模型（支持 OpenAI 兼容的 SSE 流式）→ 真后端 → 真界面（Vite dev）→ Playwright 操作。
 *
 * 为什么必须真后端：这一轮最关键的技术风险是 **SSE 流式链路**（后端 SseEmitter → 前端 fetch+ReadableStream），
 * 假后端冒烟测不出"是不是真的边生成边显示"。这里用假模型按多段推文本，验证：
 *   1. 复盘诊断：公式统计（含按知识点的错题分布）先出来，AI 诊断再流式补上；
 *   2. 一键配题：「开始练习」拿到的配题结果、练习页顶部的说明、实际练的题三者一致（PLAN 会话）；
 *   3. 错题讲解（主线）：三段结构（错在哪/这类题怎么做/下次防错）分块流式出现，可选错因进 prompt，
 *      下面能就地追问，能「存为解析」写回题库；
 *   4. 提示楼梯：L1/L2/L3 分级、逐级禁用已给过的级别、上一级作为上下文带进下一级；
 *   5. 落库与口径：tutor_session / tutor_message 真的写了（用接口复查），讲解不占提示级别。
 *
 * 前置（脚本不负责起后端，避免动到用户正在用的实例）：
 *   1. 临时数据目录写好模型配置（指向本脚本的假模型端口）：
 *        {"baseUrl":"http://127.0.0.1:18520/v1","model":"fake-model","thinking":false}
 *   2. 起后端：TIKU_DATA_DIR=<临时数据目录> java -jar target/Tiku-0.0.1-SNAPSHOT.jar --server.port=8080
 *   3. 起前端：cd frontend && npm run dev -- --port 5199 --strictPort
 *
 * 用法：node scripts/e2e-tutor-live.mjs [--api http://127.0.0.1:8080] [--ui http://localhost:5199]
 *                                     [--shots <截图目录>] [--ai-port 18520]
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
const AI_PORT = Number(argOf('--ai-port', '18520'))
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

/* ---------------- 假模型：支持流式（SSE），按请求类型给不同内容 ---------------- */
const prompts = []
const aiServer = http.createServer((req, res) => {
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8')
    prompts.push(raw)
    // 三段式回答，便于验证"边生成边显示"（前端应逐段出现，而不是一次出现）
    let pieces
    // 注意判定顺序：追问的上下文里会带上刚才的讲解（含「【错在哪】」），所以「追问」必须排在「讲解」前面判
    if (raw.includes('【我的追问】')) {
      pieces = ['先看年份：', '题干里「比 2023 年」说明 2023 是基期，', '再做 5 道同类题巩固。']
    } else if (raw.includes('第 1 级提示：只指方向')) {
      pieces = ['先看考点', '：这道题问的是增长率，', '方向是「现期 ÷ 基期」']
    } else if (raw.includes('第 2 级提示：关键一步')) {
      pieces = ['关键一步：', '先算出两者的比值', '，再减 1']
    } else if (raw.includes('第 3 级提示：完整解析')) {
      pieces = ['完整解析：120/100 = 1.2，', '所以增长率是 20%，选 A。']
    } else if (raw.includes('【本场练习】')) {
      pieces = ['① 这场做完了，错在两处。', '② 最该补的是增长类。', '③ 第 1 题公式记混。', '④ 先把增长类公式默一遍。']
    } else if (raw.includes('【错在哪】')) {
      // 错题讲解：三段标题原样输出，逐步推给前端（验证分块流式渲染）
      pieces = [
        '【错在哪】', '你选了乙，把基期当成了现期，', '所以算出来的是增长量。\n',
        '【这类题怎么做】', '看到「增长率」先认出现期与基期，', '再套 现期 ÷ 基期 − 1。\n',
        '【下次防错】', '先圈出题干里的年份，再动笔。'
      ]
    } else if (raw.includes('【这道题怎么做】')) {
      // 没作答的题（中性口吻）：标题不同，结构相同
      pieces = [
        '【这道题怎么做】', '先认出条件里的两个量，', '再套公式算出答案。\n',
        '【这类题怎么做】', '见到「两个量比大小」就先算比值。\n',
        '【易错点】', '别把两个量的顺序弄反。'
      ]
    } else {
      pieces = ['你的错因是没掌握公式，', '建议先背熟「增长率 = 现期/基期 - 1」', '再做 5 道同类题巩固。']
    }
    const stream = raw.includes('"stream":true')
    if (!stream) {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: pieces.join('') } }] }))
      return
    }
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
    let i = 0
    const timer = setInterval(() => {
      if (i < pieces.length) {
        res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: pieces[i++] } }] })}\n\n`)
      } else {
        clearInterval(timer)
        res.write('data: [DONE]\n\n')
        res.end()
      }
    }, 120) // 故意慢一点：前端必须"逐段显示"才能通过
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

  /* ---------------- 造数据：题库 + 6 题 + 一场已交卷的练习（含错题） ---------------- */
  const STEMS = [
    ['2024 年该省 GDP 为 120 亿元，2023 年为 100 亿元，增长率是多少？', false],
    ['该市 2024 年第三产业占 GDP 的比重比上年提高了多少个百分点？', false],
    ['从所给的四个选项中，选择最合适的一个填入问号处。', true],
    ['以下哪项如果为真，最能削弱上述结论？', true],
    ['下列日常生活现象与其物理原理对应正确的是：', true],
    ['根据《民法典》的规定，下列说法正确的是：', false]
  ]
  const bankId = await api('/banks', 'POST', { name: 'AI 私教端到端（临时）', description: 'e2e' })
  const qids = []
  let n = 0
  for (const [content] of STEMS) {
    n++
    const qid = await api('/questions', 'POST', {
      bankId, volume: 1, questionType: 'SINGLE', questionNumber: n, content,
      topic: null, category: null, score: 1, analysis: '解析：略（端到端用）。',
      options: [{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }, { key: 'C', text: '丙' }, { key: 'D', text: '丁' }],
      answerKeys: ['A'], answerText: null, referenceAnswer: null
    })
    qids.push(qid)
  }
  const session = await api(`/banks/${bankId}/sessions`, 'POST', { mode: 'ALL', count: 6 })
  const sessionId = session.sessionId
  // 注意：ALL 模式是随机抽题，会话里的顺序与录入顺序无关——所以要按**会话里的题**决定哪两题答错
  const wrongQids = session.questions.slice(0, 2).map((q) => q.questionId)
  await api(`/sessions/${sessionId}/finish`, 'POST', {
    answers: session.questions.map((q) => ({
      questionId: q.questionId,
      selectedKeys: wrongQids.includes(q.questionId) ? ['B'] : ['A'],
      seconds: 30
    }))
  })
  // 给答错的两题打上知识点（复盘分布才有内容）
  await api(`/banks/${bankId}/skills/apply`, 'POST', {
    action: 'set', templateId: 'official.civil-service', questionIds: [wrongQids[0]], newNodes: ['gk.zl.growth']
  })
  await api(`/banks/${bankId}/skills/apply`, 'POST', {
    action: 'set', templateId: 'official.civil-service', questionIds: [wrongQids[1]], newNodes: ['gk.zl.ratio']
  })
  console.log(`  已准备：题库 ${bankId}，练习场次 ${sessionId}，6 题（答错 ${wrongQids.join(', ')}）`)

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
  // 诊断用：接口失败与页面报错都打出来（只在排错时才有内容）
  page.on('response', (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) console.log(`  [http ${r.status()}] ${r.url()}`)
  })
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('favicon')) console.log(`  [console] ${m.text().slice(0, 200)}`)
  })
  const textOf = async (loc) => flat(await loc.innerText())
  /** 等后端把消息落库（流式期间界面已经显示，但落库发生在流结束后） */
  const waitMessages = async (sessionId, min = 1, tries = 20) => {
    for (let i = 0; i < tries; i++) {
      const data = await api(`/tutor/sessions/${sessionId}/messages`)
      if ((data.messages || []).length >= min) return data
      await page.waitForTimeout(300)
    }
    return await api(`/tutor/sessions/${sessionId}/messages`)
  }

  /* ---------------- ① 复盘：公式统计 + 流式诊断 ---------------- */
  await page.goto(`${UI}/banks/${bankId}/practice?sessionId=${sessionId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.review-card', { timeout: 15000 })
  await page.waitForTimeout(1500)
  const reviewStat = await textOf(page.locator('.review-stat'))
  check('报告页出现复盘卡且统计正确（6 题 · 对 4 · 错 2）', /6/.test(reviewStat) && /4/.test(reviewStat) && /2/.test(reviewStat), reviewStat)
  const nodeText = await textOf(page.locator('.review-nodes'))
  check('薄弱知识点由公式算出（不依赖模型）', /增长/.test(nodeText), nodeText.slice(0, 120))

  await page.locator('.review-card button', { hasText: '生成复盘诊断' }).click()
  // 流式：先出现第一段，再逐步变长（证明不是一次性返回）
  await page.waitForFunction(() => {
    const el = document.querySelector('.review-text')
    return el && el.innerText.trim().length > 0
  }, null, { timeout: 20000 })
  const firstChunk = (await textOf(page.locator('.review-text'))).length
  await page.waitForTimeout(400)
  const laterChunk = (await textOf(page.locator('.review-text'))).length
  await page.waitForSelector('.review-card button:not([disabled])', { timeout: 20000 })
  await page.waitForTimeout(800)
  const finalText = await textOf(page.locator('.review-text'))
  check('复盘诊断是流式出现的（先短后长）', laterChunk > firstChunk, `${firstChunk} → ${laterChunk}`)
  check('复盘诊断内容落到了界面上', /增长类|增长率/.test(finalText), finalText.slice(0, 120))
  await page.screenshot({ path: `${SHOTS}/1-复盘诊断.png` })

  const reviewSessions = await api(`/tutor/sessions?questionId=${wrongQids[0]}`)
  const summary = await api(`/tutor/review/${sessionId}/summary?bankId=${bankId}&templateId=official.civil-service`)
  check('复盘统计按知识点分布（后端公式）', summary.byNode.length === 2 && summary.wrong === 2, JSON.stringify(summary.byNode).slice(0, 160))
  check('复盘消息已落库（tutor_session / tutor_message）', Array.isArray(reviewSessions), JSON.stringify(reviewSessions).slice(0, 120))

  /* ---------------- ② 开始练习 = 一键配题（配题结果 / 说明 / 实际练的题三者一致） ---------------- */
  const plan = await api(`/banks/${bankId}/practice-plan?count=20&templateId=official.civil-service`)
  check('配题结果给出可核对的数量与解释', plan.total > 0 && /题/.test(plan.explain), JSON.stringify(plan).slice(0, 200))
  await page.goto(`${UI}/banks/${bankId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.header-actions', { timeout: 15000 })
  const headerText = await textOf(page.locator('.header-actions'))
  check('题库头部只剩「更多」+「开始练习」（学习路线/闪卡已下线）',
    /开始练习/.test(headerText) && !/学习路线|闪卡/.test(headerText), headerText)
  await page.locator('.header-actions button', { hasText: '开始练习' }).click()
  await page.waitForURL(/\/practice\?/, { timeout: 20000 })
  await page.waitForSelector('.plan-banner', { timeout: 15000 })
  const banner = await textOf(page.locator('.plan-banner'))
  check('练习页顶部说明与配题解释一致（同一批题）', banner.includes(plan.explain), `${banner} ‖ ${plan.explain}`)
  const plannedSessionId = Number(new URL(page.url()).searchParams.get('sessionId'))
  const planned = await api(`/sessions/${plannedSessionId}`)
  const plannedIds = (planned.questions || []).map((q) => q.questionId)
  check('练的题就是配题结果那批（顺序即优先级）',
    planned.mode === 'PLAN' && JSON.stringify(plannedIds) === JSON.stringify(plan.items.map((i) => i.questionId)),
    `${planned.mode} [${plannedIds.join(',')}] vs [${plan.items.map((i) => i.questionId).join(',')}]`)
  await page.screenshot({ path: `${SHOTS}/2-一键配题.png` })

  /* ---------------- ③ 错题讲解：三段结构分块流式 + 可选错因 + 追问 + 存为解析 ---------------- */
  await page.goto(`${UI}/banks/${bankId}/practice?sessionId=${sessionId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.report-item', { timeout: 15000 })
  const firstWrong = page.locator('.report-item').first()
  await firstWrong.locator('.report-row').click()
  await page.waitForTimeout(600)
  const coachTrigger = firstWrong.locator('.qx-trigger')
  const coachText = await textOf(coachTrigger)
  check('错题里有「讲给我听」+ 可选错因三选（不选也能讲）',
    /讲给我听/.test(coachText) && /看错/.test(coachText) && /没见过/.test(coachText), coachText.slice(0, 140))
  await coachTrigger.locator('.qx-chip', { hasText: '这个知识点不会' }).click()
  await coachTrigger.locator('button', { hasText: '讲给我听' }).click()

  // 流式：第一段一出现就有内容，随后继续变长（证明不是一次性返回）
  await page.waitForFunction(() => {
    const el = document.querySelector('.qx-sec-text')
    return el && el.innerText.trim().length > 0
  }, null, { timeout: 25000 })
  const coachFirst = (await textOf(firstWrong.locator('.qx-body'))).length
  await page.waitForTimeout(400)
  const coachLater = (await textOf(firstWrong.locator('.qx-body'))).length
  await page.waitForFunction(() => {
    const el = document.querySelector('.qx-body')
    return el && el.innerText.includes('先圈出题干里的年份')
  }, null, { timeout: 25000 })
  check('讲解是流式出现的（先短后长）', coachLater > coachFirst, `${coachFirst} → ${coachLater}`)

  const secTitles = await firstWrong.locator('.qx-sec-title').allInnerTexts()
  check('三段结构各自成块渲染（错在哪 / 这类题怎么做 / 下次防错）',
    secTitles.length === 3 && /错在哪/.test(secTitles[0]) && /这类题怎么做/.test(secTitles[1]) && /下次防错/.test(secTitles[2]),
    JSON.stringify(secTitles))
  const coachBody = await textOf(firstWrong.locator('.qx-body'))
  check('讲解内容针对我的作答（不是通用解析）', /你选了乙/.test(coachBody) && /基期/.test(coachBody), coachBody.slice(0, 160))

  const explainPrompt = prompts.filter((p) => p.includes('【错在哪】')).pop() || ''
  check('讲解 prompt 带上了我的作答与错因',
    explainPrompt.includes('【我的作答】B（错误）') && explainPrompt.includes('这个知识点不会'),
    explainPrompt.slice(0, 240))
  check('讲解 prompt 明确要求讲透而不是照抄解析', explainPrompt.includes('不要照抄'), explainPrompt.slice(-200))

  // 就地追问：不用另开面板
  await firstWrong.locator('.qx-input').fill('那基期怎么快速认出来？')
  await firstWrong.locator('.qx-foot button', { hasText: '追问' }).click()
  await page.waitForFunction(() => {
    const el = document.querySelector('.qx-body')
    return el && el.innerText.includes('再做 5 道同类题')
  }, null, { timeout: 25000 })
  check('讲解下面能直接追问（一问一答都在原位）', (await firstWrong.locator('.qx-follow').count()) >= 2,
    String(await firstWrong.locator('.qx-follow').count()))

  // 落库复查：错因进会话，讲解进消息且不占提示级别
  const whySession = (await api(`/tutor/sessions?questionId=${wrongQids[0]}`))[0]
  const whyMessages = await waitMessages(whySession.id, 2)
  check('错因写进了会话（self_reason）', whySession.selfReason === 'NO_KNOWLEDGE', JSON.stringify(whySession).slice(0, 160))
  check('讲解与追问都落库（tutor_message）', (whyMessages.messages || []).length >= 2, JSON.stringify(whyMessages).slice(0, 160))
  check('讲解不占提示级别（hint_level 为空）',
    (whyMessages.messages || []).every((m) => m.hintLevel == null),
    JSON.stringify((whyMessages.messages || []).map((m) => m.hintLevel)))

  // 存为解析：讲解变成题库的一部分（越用越厚）
  await firstWrong.locator('.qx-foot button', { hasText: '存为解析' }).click()
  await page.waitForTimeout(1500)
  const savedQuestion = await api(`/questions/${wrongQids[0]}`)
  check('「存为解析」把讲解写回题目解析', /【错在哪】/.test(savedQuestion.analysis || ''), String(savedQuestion.analysis).slice(0, 120))

  // 存进笔记：解析是题库的（会随包分享），笔记是我的（只在本机）——两个去处都验证
  await firstWrong.locator('.qx-foot button', { hasText: '存进笔记' }).click()
  await page.waitForTimeout(1200)
  const notesOfQuestion = await api(`/questions/${wrongQids[0]}/notes`)
  check('「存进笔记」把讲解存进我的笔记（source=ai）',
    notesOfQuestion.length === 1 && notesOfQuestion[0].source === 'ai' && /【错在哪】/.test(notesOfQuestion[0].content),
    JSON.stringify(notesOfQuestion).slice(0, 200))
  const bankNotes = await api(`/banks/${bankId}/notes?page=1&size=20`)
  check('笔记按题库可列出（带题号，便于回到那道题）',
    bankNotes.total >= 1 && bankNotes.records[0].questionNumber != null,
    JSON.stringify(bankNotes).slice(0, 200))
  await page.screenshot({ path: `${SHOTS}/3-错题讲解.png` })

  /* ---------------- ③b 统一解析：三处入口同一个组件 + 上次讲过的自动回看 ---------------- */
  const promptsBeforeReopen = prompts.length
  await page.goto(`${UI}/banks/${bankId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.q-row', { timeout: 15000 })
  await page.waitForTimeout(800)
  // 题库列表里对同一道题点「讲解」：不再自动重复讲，而是把上次讲过的内容显示出来
  // 注意：面板是行的**兄弟节点**（v-for 里 row + panel），所以用相邻兄弟选择器定位
  const targetPanel = page.locator(`.q-row[data-qid="${wrongQids[0]}"] + .q-ai-panel`)
  await page.locator(`.q-row[data-qid="${wrongQids[0]}"]`).click({ button: 'right' })
  await page.waitForSelector('.action-menu', { timeout: 5000 })
  await page.locator('.action-menu .action-menu-item', { hasText: '讲解这道题' }).click()
  await targetPanel.locator('.qx-body').waitFor({ state: 'attached', timeout: 15000 })
  await page.waitForTimeout(1200)
  const bankExplain = await textOf(targetPanel.locator('.qx-body'))
  check('题库列表行内用的是同一个讲解组件（三段结构在）',
    /错在哪|这道题怎么做/.test(bankExplain) && /这类题怎么做/.test(bankExplain), bankExplain.slice(0, 160))
  check('打开就显示上次讲过的内容（不用重新花额度）',
    prompts.length === promptsBeforeReopen && /上次讲于/.test(await textOf(targetPanel.locator('.qx-history'))),
    `newPrompts=${prompts.length - promptsBeforeReopen} history=${await textOf(targetPanel.locator('.qx-history'))}`)
  await page.screenshot({ path: `${SHOTS}/3b-回看讲解.png` })

  // 练习历史里也是同一个组件（打过的题显示上次讲过的内容，没讲过的给入口）
  await page.goto(`${UI}/banks/${bankId}/sessions?view=${sessionId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.review-card', { timeout: 15000 })
  await page.waitForTimeout(2000)
  const histCards = page.locator('.review-card')
  const firstCardTrigger = await histCards.first().locator('.qx-trigger').count()
  const firstCardBody = await histCards.first().locator('.qx-body').count()
  check('练习历史每题都有讲解入口（不再是另一套 AI 解析）', firstCardTrigger + firstCardBody > 0,
    `trigger=${firstCardTrigger} body=${firstCardBody}`)
  const histTitles = await page.locator('.qx-sec-title').allInnerTexts()
  check('练习历史里显示的是讲过的三段（回看，不重复花额度）',
    histTitles.length >= 3 && histTitles.includes('这类题怎么做'),
    JSON.stringify(histTitles))

  // 没作答的题：讲"这道题怎么做"，不能讲"你错在哪"（新题，确保从未作答）
  const freshQid = await api('/questions', 'POST', {
    bankId, volume: 1, questionType: 'SINGLE', questionNumber: 99,
    content: '某商品先涨价 10% 再降价 10%，最终价格与原价相比如何变化？',
    topic: null, category: null, score: 1, analysis: null,
    options: [{ key: 'A', text: '不变' }, { key: 'B', text: '变低' }, { key: 'C', text: '变高' }, { key: 'D', text: '无法确定' }],
    answerKeys: ['B'], answerText: null, referenceAnswer: null
  })
  await page.goto(`${UI}/banks/${bankId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector(`.q-row[data-qid="${freshQid}"]`, { timeout: 15000 })
  await page.waitForTimeout(600)
  const freshPanel = page.locator(`.q-row[data-qid="${freshQid}"] + .q-ai-panel`)
  await page.locator(`.q-row[data-qid="${freshQid}"]`).click({ button: 'right' })
  await page.waitForSelector('.action-menu', { timeout: 5000 })
  await page.locator('.action-menu .action-menu-item', { hasText: '讲解这道题' }).click()
  await page.waitForFunction((qid) => {
    const row = document.querySelector(`.q-row[data-qid="${qid}"]`)
    const panel = row && row.nextElementSibling
    // 等第三段也长出来（假模型分段推，只看第二段会读到半截）
    return panel && panel.innerText.includes('别把两个量的顺序弄反')
  }, freshQid, { timeout: 30000 })
  const neutralTitles = await freshPanel.locator('.qx-sec-title').allInnerTexts()
  check('没作答的题讲「这道题怎么做 / 这类题怎么做 / 易错点」',
    neutralTitles.length === 3 && /这道题怎么做/.test(neutralTitles[0]) && /易错点/.test(neutralTitles[2]),
    JSON.stringify(neutralTitles))
  const neutralPrompt = prompts.filter((p) => p.includes('【这道题怎么做】')).pop() || ''
  check('中性口吻的 prompt 明确"我没有作答记录"', neutralPrompt.includes('我没有作答记录'), neutralPrompt.slice(-200))

  /* ---------------- ④ 提示楼梯：做题中要提示，逐级递进 ---------------- */
  const live = await api(`/banks/${bankId}/sessions`, 'POST', { mode: 'ALL', count: 3 })
  await page.goto(`${UI}/banks/${bankId}/practice?sessionId=${live.sessionId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.question-card', { timeout: 15000 })
  await page.waitForTimeout(800)
  await page.locator('.q-head button', { hasText: '提示' }).click()
  await page.waitForSelector('.tutor', { timeout: 8000 })
  check('做题界面有「提示」入口且打开了私教面板', await page.locator('.tutor-ladder').isVisible())
  await page.locator('.tutor-step', { hasText: '提示 1' }).click()
  await page.waitForFunction(() => {
    const el = document.querySelector('.tutor-body')
    return el && el.innerText.includes('先看考点')
  }, null, { timeout: 20000 })
  // 流式结束（done 事件）会把级别计数更新到界面上——以它作为"这一轮写完了"的信号
  await page.waitForFunction(() => {
    const el = document.querySelector('.tutor-level')
    return el && el.innerText.includes('1')
  }, null, { timeout: 20000 })
  const afterL1 = await textOf(page.locator('.tutor-body'))
  check('第 1 级提示流式给出（指方向、不给答案）', /先看考点/.test(afterL1) && !/20%/.test(afterL1), afterL1.slice(0, 120))
  await page.screenshot({ path: `${SHOTS}/4-提示楼梯.png` })

  await page.locator('.tutor-step', { hasText: '提示 2' }).click()
  await page.waitForFunction(() => {
    const el = document.querySelector('.tutor-level')
    return el && el.innerText.includes('2')
  }, null, { timeout: 20000 })
  const promptForL2 = prompts.filter((p) => p.includes('第 2 级提示：关键一步')).pop() || ''
  check('第 2 级的上下文带上了第 1 级说过的话', promptForL2.includes('先看考点'), promptForL2.slice(0, 160))
  check('面板显示已给到第 2 级（界面状态跟着落库级别走）', /2/.test(await textOf(page.locator('.tutor-level'))))

  await page.locator('.tutor-step', { hasText: '提示 3' }).click()
  await page.waitForFunction(() => {
    const el = document.querySelector('.tutor-level')
    return el && el.innerText.includes('3')
  }, null, { timeout: 20000 })
  const afterL3 = await textOf(page.locator('.tutor-body'))
  check('第 3 级给出完整解析（此时才出现答案）', afterL3.includes('20%') && afterL3.includes('完整解析'), afterL3.slice(-140))

  /* ---------------- ⑤ 自由追问：面板里继续聊 ---------------- */
  await page.locator('.tutor-input').fill('那这类题还有什么坑？')
  await page.locator('.tutor-foot button').click()
  await page.waitForFunction(() => {
    const el = document.querySelector('.tutor-body')
    return el && el.innerText.includes('再做 5 道同类题')
  }, null, { timeout: 20000 })
  const askedSession = (await api(`/tutor/sessions?questionId=${live.questions[0].questionId}`))[0]
  const askedMessages = await waitMessages(askedSession.id, 8)
  const roles = (askedMessages.messages || []).map((m) => m.role).join(',')
  const levels = (askedMessages.messages || []).map((m) => m.hintLevel).join(',')
  check('提示与追问都按消息落库（含 hint_level）', roles.includes('user') && roles.includes('assistant') && levels.includes('1') && levels.includes('2'), `roles=${roles} levels=${levels}`)
  await page.screenshot({ path: `${SHOTS}/5-追问.png` })

  check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

  console.log(`\n结果：${pass} 通过 / ${fail} 失败（截图在 ${SHOTS}）`)
} finally {
  if (browser) await browser.close().catch(() => {})
  aiServer.close()
}
process.exit(fail === 0 ? 0 : 1)
