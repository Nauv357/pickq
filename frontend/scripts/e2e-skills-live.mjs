/**
 * 知识点标签（学习路径引擎阶段 0）**真后端**端到端：
 *   脚本内起一个假模型（OpenAI 兼容）→ 真后端 → 真界面（Vite dev）→ Playwright 真实操作。
 *
 * 为什么要有这一层：假后端冒烟（smoke-skills.mjs）只能证明界面按约定渲染，
 * 证明不了"后端算出来的口径和界面显示的口径是同一套"——用户实测的
 * 「26 待标注 vs 16 待确认」正是两边口径不一致造成的。这里用真数据对账。
 *
 * 前置（三步，脚本不负责起后端，避免动到用户正在用的实例）：
 *   1. 临时数据目录里写好模型配置：
 *        {"baseUrl":"http://127.0.0.1:18510/v1","model":"fake-model","thinking":false}
 *      存成 <临时数据目录>/ai-config.json；
 *   2. 起后端：TIKU_DATA_DIR=<临时数据目录> java -jar target/Tiku-0.0.1-SNAPSHOT.jar --server.port=8080
 *   3. 起前端：cd frontend && npm run dev -- --port 5199 --strictPort
 *
 * 用法：node scripts/e2e-skills-live.mjs [--api http://127.0.0.1:8080] [--ui http://localhost:5199]
 *                                      [--shots <截图目录>] [--ai-port 18510] [--dump-body <文件>]
 */
import { chromium } from 'playwright-core'
import { appendFileSync, existsSync, mkdirSync } from 'node:fs'
import http from 'node:http'

const argv = process.argv.slice(2)
const argOf = (name, dflt) => {
  const i = argv.indexOf(name)
  return i >= 0 ? argv[i + 1] : dflt
}
const API = argOf('--api', 'http://127.0.0.1:8080')
const UI = argOf('--ui', 'http://localhost:5199')
const AI_PORT = Number(argOf('--ai-port', '18510'))
const SHOTS = argOf('--shots', '.')
const DUMP_BODY = argOf('--dump-body', '')
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
let aiCalls = []

/* ---------------- 假模型：按题干关键词给节点，故意留一个编造的节点 ---------------- */
function pickNodes(text) {
  if (/图形|问号处|折叠|纸盒|笔画/.test(text)) return [{ nodeId: 'gk.pd.figure.num', confidence: 0.93 }]
  if (/增长|增速|百分点/.test(text)) return [{ nodeId: 'gk.zl.growth', confidence: 0.9 }]
  if (/比重|占比/.test(text)) return [{ nodeId: 'gk.zl.ratio', confidence: 0.62 }] // 低置信：只是建议
  if (/推出|削弱|如果为真|最能/.test(text)) return [{ nodeId: 'gk.pd.argue', confidence: 0.91 }]
  if (/关系最为相似|类比/.test(text)) {
    return [
      { nodeId: 'gk.pd.analogy', confidence: 0.88 },
      { nodeId: 'gk.pd.truth', confidence: 0.55 }
    ]
  }
  if (/宪法|法律|民法典|行政/.test(text)) return [{ nodeId: 'gk.cs.law', confidence: 0.95 }]
  if (/平均数|平均/.test(text)) return [{ nodeId: 'gk.zl.avg', confidence: 0.66 }]
  return [{ nodeId: 'ai.nope.unknown', confidence: 0.4 }] // 编造节点 → 后端应丢弃 → 未匹配
}

const aiServer = http.createServer((req, res) => {
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    // 必须按字节拼接再解码：一个汉字 3 字节，分块边界落在字中间时逐块 toString 会得到乱码
    const raw = Buffer.concat(chunks).toString('utf8')
    if (DUMP_BODY) appendFileSync(DUMP_BODY, `${raw}\n\n=====\n\n`)
    const mappings = []
    // 题干里的引号在 JSON 里是 \"，行尾是转义的 \n；两种都不能当"题干结束"。
    // 早先版本用 [^\n\\]* 会在转义引号处提前截断（带引号的题被判成"编不出来"），
    // 而单纯的 .* 又会一路吞到 JSON 末尾，所以用"转义引号当整体 + 行尾前瞻"。
    const re = /- id=(\d+) 题型=\S+ 题干=((?:\\"|[^"])*?)(?=\\n|")/g
    let m
    while ((m = re.exec(raw)) !== null) {
      mappings.push({ id: Number(m[1]), nodes: pickNodes(m[2].replace(/\\"/g, '"')) })
    }
    if (mappings.length === 0) {
      for (const g of [...raw.matchAll(/- (.+?)（/g)]) mappings.push({ group: g[1], nodes: pickNodes(g[1]) })
    }
    aiCalls.push({ mappings: mappings.length, groups: mappings.filter((x) => x.group).length })
    const content = JSON.stringify({ mappings })
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
  } catch (e) {
    console.error(`后端不可用（${API}）：${e.message}\n请先按文件头部说明起后端与前端，并确认 ai-config.json 指向 http://127.0.0.1:${AI_PORT}/v1`)
    aiServer.close()
    process.exit(2)
  }

  /* ---------------- 造数据：24 题，一半命中关键词、一半"AI 编不出来" ---------------- */
  try {
  const STEMS = [
    '从所给的四个选项中，选择最合适的一个填入问号处，使之呈现一定规律。',
    '左边给定的是纸盒的外表面，下面哪一项能由它折叠而成？',
    '2019—2023 年该省 GDP 年均增长率为多少？',
    '以下哪项如果为真，最能削弱上述结论？',
    '下列选项中，与"教师：教室"关系最为相似的是：',
    '根据《民法典》的规定，下列说法正确的是：',
    '该市 2023 年第三产业增加值占 GDP 的比重约为：',
    '甲乙两地相距 120 公里，两车相向而行，几小时后相遇？',
    '下列关于行政复议与行政诉讼关系的表述，正确的是：',
    '这 10 个数据的平均数是多少？',
    '依次填入下列横线处的词语，最恰当的一项是：',
    '根据这段文字，作者接下来最可能讲述的是：',
    '下列日常生活现象与其物理原理对应正确的是：',
    '文中画线句子在结构上起到的作用是：',
    '该公文标题的规范写法是：',
    '下列有关细胞结构的说法，错误的是：',
    '填入问号处最恰当的是（图形按笔画数排列）：',
    '某商品先涨价 10% 再降价 10%，最终价格与原价相比：',
    '下列关于世界地理之最的说法，正确的是：',
    '这段话主要说明的道理是：',
    '2023 年该地区城镇居民人均可支配收入同比增长了约几个百分点？',
    '"十一五"规划期间，我国经济总量跃居世界第几位？',
    '下列哪一项不属于行政处罚的种类？',
    '按照时间先后顺序排列，正确的是：'
  ]

  const created = await api('/banks', 'POST', { name: '知识点端到端（临时）', description: 'e2e' })
  // 建库接口直接返回 bankId（数字），这里兼容将来改成对象的情况
  const bankId = created && typeof created === 'object' ? created.bankId ?? created.id : created
  check('建库成功', !!bankId, JSON.stringify(created).slice(0, 120))

  let i = 0
  for (const content of STEMS) {
    i++
    await api('/questions', 'POST', {
      bankId,
      volume: 1,
      questionType: 'SINGLE',
      questionNumber: i,
      content,
      topic: null,
      category: null,
      score: 1,
      analysis: null,
      options: [
        { key: 'A', text: '选项甲' },
        { key: 'B', text: '选项乙' },
        { key: 'C', text: '选项丙' },
        { key: 'D', text: '选项丁' }
      ],
      answerKeys: ['A'],
      answerText: null,
      referenceAnswer: null
    })
  }
  console.log(`  已写入 ${i} 题（bankId=${bankId}）`)

  const TPL = 'official.civil-service'
  const before = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  check('初始覆盖：全部未匹配', before.untaggedQuestions === i && before.confirmedQuestions === 0 && before.pendingQuestions === 0, JSON.stringify(before).slice(0, 160))

  /* ---------------- 浏览器：真界面操作 ---------------- */
  const CHROME = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].find((p) => existsSync(p))
  if (!CHROME) {
    console.error('找不到 Chrome / Edge')
    aiServer.close()
    process.exit(2)
  }
  browser = await chromium.launch({ executablePath: CHROME, headless: true })
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
  // 题目编辑器打开时会请求 /api/questions/{id}/skills —— 顺便记下"打开的是哪一题"，
  // 后面要按 id 复查"编辑页选的标签有没有真的写库"
  let openedQuestionId = null
  page.on('request', (r) => {
    const m = new URL(r.url()).pathname.match(/^\/api\/questions\/(\d+)\/skills$/)
    if (m) openedQuestionId = Number(m[1])
  })

  await page.goto(`${UI}/banks/${bankId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2000)
  await page.locator('button', { hasText: '知识点' }).first().click()
  await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
  await page.waitForTimeout(800)

  console.log('点「分析并标注」（真后端 + 假模型）')
  await page.locator('.skill-toolbar-right button').first().click()
  await page.waitForSelector('.el-message--success', { timeout: 180000 })
  await page.waitForTimeout(1500)
  await page.screenshot({ path: `${SHOTS}/1-待确认.png` })

  const cover = flat(await page.locator('.skill-cover').innerText())
  console.log(`  界面概览：${cover}`)
  check('覆盖概览显示三段口径', /已确认/.test(cover) && /待确认/.test(cover) && /未匹配/.test(cover), cover)

  const cov = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  console.log(`  模型调用：${aiCalls.map((c) => `${c.groups ? '分组' : '逐题'}×${c.mappings}`).join(' / ') || '(无)'}`)
  console.log(`  后端口径：总 ${cov.totalQuestions} / 已确认 ${cov.confirmedQuestions} / 待确认 ${cov.pendingQuestions} / 未匹配 ${cov.untaggedQuestions} / 可用于判定 ${cov.usableQuestions}`)
  check('三段相加 = 总题数（真数据对账）', cov.confirmedQuestions + cov.pendingQuestions + cov.untaggedQuestions === cov.totalQuestions, JSON.stringify(cov).slice(0, 200))
  // 用户实测的「26 待标注 vs 16 待确认」本质是"概览数字"和"清单长度"两套口径打架，
  // 所以这里强制对账：概览里的每个数字，都必须等于按状态查出来的清单条数。
  const [pendList, untagList, confList] = await Promise.all([
    api(`/banks/${bankId}/skills/questions?templateId=${TPL}&status=pending&size=200`),
    api(`/banks/${bankId}/skills/questions?templateId=${TPL}&status=untagged&size=200`),
    api(`/banks/${bankId}/skills/questions?templateId=${TPL}&status=confirmed&size=200`)
  ])
  check(
    '概览数字 = 清单条数（待确认/未匹配/已确认逐项对账）',
    cov.pendingQuestions === pendList.length && cov.untaggedQuestions === untagList.length && cov.confirmedQuestions === confList.length,
    `概览 ${cov.pendingQuestions}/${cov.untaggedQuestions}/${cov.confirmedQuestions} vs 清单 ${pendList.length}/${untagList.length}/${confList.length}`
  )
  check('标签数符合预期（假模型 12 题命中、12 题编不出节点）', cov.pendingQuestions + cov.confirmedQuestions === 12 && cov.untaggedQuestions === 12, `tagged=${cov.pendingQuestions + cov.confirmedQuestions} untagged=${cov.untaggedQuestions}`)
  check('低置信建议也在队列里（不参与判定掌握）', cov.usableQuestions < cov.pendingQuestions + cov.confirmedQuestions, `usable=${cov.usableQuestions}`)
  check('有题拿到了知识点（不是全未匹配）', cov.pendingQuestions + cov.confirmedQuestions > 0, `pending=${cov.pendingQuestions}`)
  check('编造节点的题落到未匹配', cov.untaggedQuestions > 0, `untagged=${cov.untaggedQuestions}`)
  check('界面数字与后端一致（总题数、未匹配、可用于判定）', cover.includes(String(cov.totalQuestions)) && cover.includes(String(cov.untaggedQuestions)) && cover.includes(String(cov.usableQuestions)), cover)

  const items = page.locator('.skill-item')
  check('待确认队列有节点', (await items.count()) > 0, String(await items.count()))
  const rowCount = await items.first().locator('.skill-q').count()
  check('默认展开、逐题列出真实题目', rowCount > 0, String(rowCount))
  const rowText = flat(await items.first().locator('.skill-q').first().innerText())
  check('单题带题号与真实题干片段', /^\d+/.test(rowText) && rowText.length > 12, rowText.slice(0, 120))
  console.log(`  首题行：${rowText.slice(0, 140)}`)
  await page.screenshot({ path: `${SHOTS}/2-逐题清单.png` })

  await page.locator('.el-tabs__item', { hasText: '未匹配' }).click()
  await page.waitForTimeout(1200)
  const untaggedRows = page.locator('.el-tab-pane:visible .skill-q')
  const uCount = await untaggedRows.count()
  check('未匹配页签列出题目（真数据）', uCount > 0, String(uCount))
  console.log(`  未匹配前 3 行：${(await untaggedRows.allInnerTexts()).slice(0, 3).map(flat).join(' | ').slice(0, 200)}`)
  await page.screenshot({ path: `${SHOTS}/3-未匹配.png` })

  /* 逐题确认 → 复查真写库 */
  await page.locator('.el-tabs__item', { hasText: '待确认' }).click()
  await page.waitForTimeout(700)
  const targetRow = page.locator('.skill-item').first().locator('.skill-q').first()
  const targetText = flat(await targetRow.innerText())
  await targetRow.locator('button', { hasText: '确认此题' }).click()
  await page.waitForTimeout(1800)
  const afterConfirm = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  check('逐题确认真的写库（已确认 +1）', afterConfirm.confirmedQuestions === cov.confirmedQuestions + 1, `before=${cov.confirmedQuestions} after=${afterConfirm.confirmedQuestions}`)
  check('确认后三段仍然对得上账', afterConfirm.confirmedQuestions + afterConfirm.pendingQuestions + afterConfirm.untaggedQuestions === afterConfirm.totalQuestions, JSON.stringify(afterConfirm).slice(0, 200))
  console.log(`  已确认的那题：${targetText.slice(0, 110)}`)

  /* 设为主题 → 复查题目 topic */
  const topicName = flat(await page.locator('.skill-item').first().locator('.skill-node').first().innerText())
  await page.locator('.skill-item').first().locator('button', { hasText: '设为主题' }).click()
  await page.waitForTimeout(1800)
  const list = await api(`/banks/${bankId}/questions?page=1&size=200`)
  const records = list.records || list
  const withTopic = records.filter((q) => q.topic === topicName).length
  check(`「设为主题」把「${topicName}」写进了题目主题`, withTopic > 0, `withTopic=${withTopic}/${records.length}`)

  /* 打开题目 */
  await page.locator('.el-tabs__item', { hasText: '未匹配' }).click()
  await page.waitForTimeout(1200)
  const firstUntagged = page.locator('.el-tab-pane:visible .skill-q').first()
  const openText = flat(await firstUntagged.innerText())
  await firstUntagged.locator('button', { hasText: '打开题目' }).click()
  await page.waitForTimeout(1200)
  const focusCount = await page.locator('.editor-dialog .skill-focus').count()
  await page.waitForTimeout(1300)
  check('「打开题目」跳到编辑器且弹窗已关闭', !(await page.locator('.el-dialog .skill-toolbar').isVisible().catch(() => false)))
  const editorText = flat(await page.locator('.editor-dialog').innerText().catch(() => ''))
  const stem = openText.replace(/^\d+\s*/, '').slice(0, 10)
  check('编辑器里打开的正是那一题', editorText.includes(stem), `找「${stem}」于：${editorText.slice(0, 120)}`)
  await page.screenshot({ path: `${SHOTS}/4-打开题目.png` })

  /* 编辑页里手动指定知识点（用户反馈："甚至编辑页面都没有显示 tag"）→ 保存后复查写库 */
  const skillField = page.locator('.editor-dialog .field').filter({ has: page.locator('label.field-label', { hasText: '知识点' }) })
  check('编辑器里有「知识点」字段', (await skillField.count()) > 0, String(await skillField.count()))
  check('知识点栏被滚到视野内并高亮（不用自己找）', focusCount > 0, String(focusCount))
  check('空标签时给出可照做的提示', /还没有知识点标签/.test(editorText), editorText.slice(0, 160))
  await skillField.locator('.el-select__wrapper').click()
  await page.waitForTimeout(300)
  await page.keyboard.type('增长')
  await page.waitForTimeout(900)
  if (process.env.E2E_DEBUG) {
    const info = await page.evaluate(() => ({
      wrappers: document.querySelectorAll('.editor-dialog .el-select__wrapper').length,
      dropdowns: [...document.querySelectorAll('.el-select-dropdown')].map((d) => ({
        visible: !!(d.offsetWidth || d.offsetHeight),
        items: [...d.querySelectorAll('.el-select-dropdown__item')].map((i) => i.textContent).slice(0, 6)
      }))
    }))
    console.log('  [debug]', JSON.stringify(info))
    await page.screenshot({ path: `${SHOTS}/debug-select.png` })
  }
  const opt = page.locator('.el-select-dropdown__item:visible').first()
  const optName = flat(await opt.innerText())
  await opt.click()
  await page.waitForTimeout(300)
  await page.locator('.editor-dialog .foot-actions button', { hasText: '保存并关闭' }).click()
  await page.waitForTimeout(2500)
  const tags = openedQuestionId ? await api(`/questions/${openedQuestionId}/skills`) : []
  check(
    `编辑页选的「${optName}」真的写库了（source=user）`,
    tags.some((t) => t.source === 'user' && t.confirmed),
    `questionId=${openedQuestionId} → ${JSON.stringify(tags).slice(0, 200)}`
  )
  const afterEditor = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  check('补完标签后概览里它不再算未匹配', afterEditor.untaggedQuestions === afterConfirm.untaggedQuestions - 1, `${afterConfirm.untaggedQuestions} → ${afterEditor.untaggedQuestions}`)

  /* 再看"已经有 AI 建议的题"：编辑页要能显示现有标签（用户反馈的第二半："看不到 tag"） */
  await page.locator('button', { hasText: '知识点' }).first().click()
  await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
  await page.waitForTimeout(800)
  // 弹窗会停在上次看的页签上，先切回「待确认」再取行
  await page.locator('.el-tabs__item', { hasText: '待确认' }).click()
  await page.waitForTimeout(700)
  const taggedRow = page.locator('.el-tab-pane:visible .skill-item').first().locator('.skill-q').first()
  const taggedStem = flat(await taggedRow.innerText())
  await taggedRow.locator('button', { hasText: '打开题目' }).click()
  await page.waitForTimeout(2500)
  const taggedEditor = flat(await page.locator('.editor-dialog').innerText().catch(() => ''))
  check('编辑页显示已有标签（当前：xxx（AI 建议/你标注的））', /当前：/.test(taggedEditor) && /(AI 建议|你标注的|作者标注)/.test(taggedEditor), taggedEditor.slice(0, 200))
  const taggedField = page.locator('.editor-dialog .field').filter({ has: page.locator('label.field-label', { hasText: '知识点' }) })
  const picked = flat(await taggedField.locator('.el-select__wrapper').first().innerText().catch(() => ''))
  check('下拉里已回填该标签（不用重新选）', picked.length > 0 && !/请选择/.test(picked), picked)
  console.log(`  带标签的题：${taggedStem.slice(0, 90)} → 下拉显示「${picked}」`)
  await page.screenshot({ path: `${SHOTS}/5-编辑页标签.png` })

  check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

  console.log(`\n结果：${pass} 通过 / ${fail} 失败（截图在 ${SHOTS}）`)
} finally {
  if (browser) await browser.close().catch(() => {})
  aiServer.close()
}
process.exit(fail === 0 ? 0 : 1)
