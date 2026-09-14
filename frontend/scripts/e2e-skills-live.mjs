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
    process.exit(2)
  }
  browser = await chromium.launch({ executablePath: CHROME, headless: true })
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
  // 题目编辑器打开时会请求 /api/questions/{id}/skills —— 顺便记下"打开的是哪一题"，后面按 id 复查
  let openedQuestionId = null
  page.on('request', (r) => {
    const m = new URL(r.url()).pathname.match(/^\/api\/questions\/(\d+)\/skills$/)
    if (m) openedQuestionId = Number(m[1])
  })

  const flat = (s) => String(s).replace(/\s+/g, ' ')
  const textOf = async (loc) => flat(await loc.innerText())
  const reviewApi = (status, extra = '') =>
    api(`/banks/${bankId}/skills/questions?templateId=${TPL}&status=${status}&size=200${extra}`)
  /** 多选下拉选完不会自动收起，按 Esc 关掉，否则会挡住下一次点击 */
  const closeDropdown = async () => {
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)
  }
  /** 点行内标签下拉的右端空白处（中间是已选标签上的 ✕，会变成删除标签） */
  const openTagSelect = async (row) => {
    const box = await row.locator('.skill-tag-select .el-select__wrapper').boundingBox()
    await page.mouse.click(box.x + box.width - 10, box.y + box.height / 2)
  }
  const pickNode = async (name) => {
    const option = page.locator('.el-select-dropdown__item:visible', { hasText: name }).first()
    await option.waitFor({ state: 'visible', timeout: 8000 })
    await option.click()
    await closeDropdown()
  }

  await page.goto(`${UI}/banks/${bankId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2000)
  await page.locator('button', { hasText: '知识点' }).first().click()
  await page.waitForSelector('.skill-toolbar', { timeout: 8000 })
  await page.waitForTimeout(800)

  console.log('点「分析并标注」（真后端 + 假模型）')
  await page.locator('.skill-toolbar-right button').first().click()
  await page.waitForSelector('.el-message--success', { timeout: 180000 })
  await page.waitForTimeout(1500)
  await page.screenshot({ path: `${SHOTS}/1-审阅清单.png` })

  const cover = await textOf(page.locator('.skill-cover'))
  console.log(`  界面概览：${cover}`)
  check('覆盖概览显示三段口径', /已确认/.test(cover) && /待确认/.test(cover) && /未匹配/.test(cover), cover)

  const cov = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  console.log(`  模型调用：${aiCalls.map((c) => `${c.groups ? '分组' : '逐题'}×${c.mappings}`).join(' / ') || '(无)'}`)
  console.log(`  后端口径：总 ${cov.totalQuestions} / 已确认 ${cov.confirmedQuestions} / 待确认 ${cov.pendingQuestions} / 未匹配 ${cov.untaggedQuestions} / 可用于判定 ${cov.usableQuestions}`)
  check('三段相加 = 总题数（真数据对账）', cov.confirmedQuestions + cov.pendingQuestions + cov.untaggedQuestions === cov.totalQuestions, JSON.stringify(cov).slice(0, 200))

  // 用户实测的「26 待标注 vs 16 待确认」本质是"概览数字"和"清单长度"两套口径打架，
  // 所以这里强制对账：概览里的每个数字，都必须等于按状态查出来的清单条数。
  const [allList, pendList, untagList, confList] = await Promise.all([
    reviewApi('all'),
    reviewApi('pending'),
    reviewApi('untagged'),
    reviewApi('confirmed')
  ])
  check(
    '概览数字 = 清单条数（待确认/未匹配/已确认逐项对账）',
    cov.pendingQuestions === pendList.total && cov.untaggedQuestions === untagList.total && cov.confirmedQuestions === confList.total,
    `概览 ${cov.pendingQuestions}/${cov.untaggedQuestions}/${cov.confirmedQuestions} vs 清单 ${pendList.total}/${untagList.total}/${confList.total}`
  )
  check('概览数字 = 清单条数（全部）', cov.totalQuestions === allList.total, `${cov.totalQuestions} vs ${allList.total}`)
  check('标签数符合预期（假模型 12 题命中、12 题编不出节点）', cov.pendingQuestions + cov.confirmedQuestions === 12 && cov.untaggedQuestions === 12, `tagged=${cov.pendingQuestions + cov.confirmedQuestions} untagged=${cov.untaggedQuestions}`)
  check('低置信建议也在队列里（不参与判定掌握）', cov.usableQuestions < cov.pendingQuestions + cov.confirmedQuestions, `usable=${cov.usableQuestions}`)
  check('界面数字与后端一致（总题数、未匹配、可用于判定）', cover.includes(String(cov.totalQuestions)) && cover.includes(String(cov.untaggedQuestions)) && cover.includes(String(cov.usableQuestions)), cover)

  /* ---------- 清单：以题为单位，标签就地可改 ---------- */
  const rows = page.locator('.skill-row')
  check('清单以题为单位（24 题全列出）', (await rows.count()) === 24, String(await rows.count()))
  const rowText = await textOf(rows.first())
  check('行内有题号 + 状态 + 真实题干', /^\d+/.test(rowText) && rowText.length > 12, rowText.slice(0, 120))
  console.log(`  首行：${rowText.slice(0, 140)}`)

  // 状态筛选器（可点击的数字，不是页签）
  await page.locator('.skill-chip', { hasText: '待确认' }).click()
  await page.waitForTimeout(800)
  check('点「待确认」后清单只剩待确认的题', (await rows.count()) === cov.pendingQuestions, String(await rows.count()))
  await page.screenshot({ path: `${SHOTS}/2-待确认.png` })

  /* ---------- 就地改标签 → 真写库（用户要的核心） ---------- */
  const targetRow = rows.first()
  const targetText = await textOf(targetRow)
  await openTagSelect(targetRow)
  await pickNode('计算问题')
  await page.waitForTimeout(1500)
  const afterEdit = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  check('就地改标签真的写库了（待确认 → 已确认 +1）', afterEdit.confirmedQuestions === cov.confirmedQuestions + 1 && afterEdit.pendingQuestions === cov.pendingQuestions - 1, `${JSON.stringify(cov)} → ${JSON.stringify(afterEdit)}`)
  console.log(`  就地改成「计算问题」的是：${targetText.slice(0, 100)}`)
  await page.screenshot({ path: `${SHOTS}/3-就地改标签.png` })

  /* ---------- 行内确认 AI 建议 ---------- */
  await page.locator('.skill-chip', { hasText: '待确认' }).click()
  await page.waitForTimeout(700)
  const confirmRow = rows.first()
  await confirmRow.locator('button', { hasText: '确认' }).first().click()
  await page.waitForTimeout(1500)
  const afterConfirm = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  check('行内「确认」把 AI 建议转为已确认', afterConfirm.confirmedQuestions === afterEdit.confirmedQuestions + 1, `${afterEdit.confirmedQuestions} → ${afterConfirm.confirmedQuestions}`)
  check('确认后三段仍然对得上账', afterConfirm.confirmedQuestions + afterConfirm.pendingQuestions + afterConfirm.untaggedQuestions === afterConfirm.totalQuestions, JSON.stringify(afterConfirm).slice(0, 200))

  /* ---------- 看题干：就地展开，不跳转 ---------- */
  const docStem = await textOf(rows.first().locator('.skill-stem'))
  await rows.first().locator('button', { hasText: '看题干' }).click()
  await page.waitForTimeout(1200)
  const detailText = await textOf(page.locator('.skill-detail').first())
  check('「看题干」就地展开（能看到选项/解析）', detailText.includes('选项甲') || detailText.length > 20, detailText.slice(0, 120))
  check('展开不跳转（弹窗仍在）', await page.locator('.el-dialog .skill-toolbar').isVisible())
  console.log(`  展开的题：${docStem.slice(0, 80)}`)

  /* ---------- 未匹配：就地补标签 ---------- */
  await page.locator('.skill-chip', { hasText: '未匹配' }).click()
  await page.waitForTimeout(800)
  check('「未匹配」清单条数与概览一致', (await rows.count()) === afterConfirm.untaggedQuestions, String(await rows.count()))
  await page.screenshot({ path: `${SHOTS}/4-未匹配.png` })
  const untaggedText = await textOf(rows.first())
  await openTagSelect(rows.first())
  await pickNode('法律常识')
  await page.waitForTimeout(1500)
  const afterFill = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  check('未匹配的题可以就地指定知识点（未匹配 -1）', afterFill.untaggedQuestions === afterConfirm.untaggedQuestions - 1, `${afterConfirm.untaggedQuestions} → ${afterFill.untaggedQuestions}`)
  console.log(`  就地补标签的题：${untaggedText.slice(0, 100)}`)

  /* ---------- 批量 ---------- */
  await page.locator('.skill-chip', { hasText: '未匹配' }).click()
  await page.waitForTimeout(700)
  await rows.nth(0).locator('.el-checkbox').click()
  await rows.nth(1).locator('.el-checkbox').click()
  await page.waitForTimeout(300)
  await page.locator('.skill-batch .el-select').click()
  await pickNode('人文历史与地理')
  await page.locator('.skill-batch button', { hasText: '设为知识点' }).click()
  await page.waitForTimeout(1800)
  const afterBatch = await api(`/banks/${bankId}/skills/coverage?templateId=${TPL}`)
  check('批量设定：两题一起从未匹配变成已确认', afterBatch.untaggedQuestions === afterFill.untaggedQuestions - 2 && afterBatch.confirmedQuestions === afterFill.confirmedQuestions + 2, `${afterFill.untaggedQuestions}→${afterBatch.untaggedQuestions}`)
  await page.screenshot({ path: `${SHOTS}/5-批量.png` })

  /* ---------- 详情（可选跳转）：编辑器里能看到已有标签 ---------- */
  await page.locator('.skill-chip', { hasText: '已确认' }).click()
  await page.waitForTimeout(800)
  const detailRowStem = await textOf(rows.first().locator('.skill-stem'))
  await rows.first().locator('button', { hasText: '详情' }).click()
  await page.waitForTimeout(3000)
  check('「详情」跳编辑器且弹窗已关闭', !(await page.locator('.el-dialog .skill-toolbar').isVisible().catch(() => false)))
  const editorText = await textOf(page.locator('.editor-dialog'))
  check('编辑器里能看到这题的知识点标签', /当前：/.test(editorText) && /(你标注的|AI 建议|作者标注)/.test(editorText), editorText.slice(0, 200))
  console.log(`  详情打开的题：${detailRowStem.slice(0, 80)}（id=${openedQuestionId}）`)
  await page.screenshot({ path: `${SHOTS}/6-编辑页标签.png` })

  check('无 JS 报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300))

  console.log(`\n结果：${pass} 通过 / ${fail} 失败（截图在 ${SHOTS}）`)
} finally {
  if (browser) await browser.close().catch(() => {})
  aiServer.close()
}
process.exit(fail === 0 ? 0 : 1)
