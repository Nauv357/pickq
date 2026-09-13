/**
 * 批量截图：给若干路由拍图（用假数据，不需要后端），用于人工核对页面骨架。
 * 用法：node scripts/shot-routes.mjs / /stats /banks/1/sessions [--out .shots] [--vp 1440x900]
 * 产物：<out>/<slug>.png（会被 .gitignore 忽略，仅本地核对用）
 */
import { chromium } from 'playwright-core'
import { existsSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

const argv = process.argv.slice(2)
const outArg = argv.indexOf('--out')
const OUT = outArg >= 0 ? argv[outArg + 1] : '.shots'
const vpArg = argv.indexOf('--vp')
const [VW, VH] = (vpArg >= 0 ? argv[vpArg + 1] : '1440x900').split('x').map(Number)
const routes = argv.filter((a, i) => a.startsWith('/') && argv[i - 1] !== '--out' && argv[i - 1] !== '--vp')
const BASE = 'http://localhost:5199'

const CHROME = ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find((p) => existsSync(p))
if (!CHROME) {
  console.error('找不到 Chrome / Edge')
  process.exit(2)
}
if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true })

const Q = (n) => ({ questionId: n, questionNumber: n, questionType: 'SINGLE', content: `第 ${n} 题的题干`, options: [{ key: 'A', text: 'A' }, { key: 'B', text: 'B' }], answerKeys: ['A'], score: 1 })
const ROWS = [101, 102, 103].map(Q)
const SESSIONS = [1, 2, 3].map((i) => ({ sessionId: i, mode: 'ALL', status: 'COMPLETED', correctCount: 8 * i, answeredCount: 10 * i, totalScore: 80, maxScore: 100, totalSeconds: 600 * i, startedAt: '2026-01-0' + i + 'T10:00:00', finishedAt: '2026-01-0' + i + 'T10:10:00' }))

function stub(pathname) {
  if (pathname === '/api/banks/1') return { bankId: 1, name: '冒烟题库', description: 'e2e', questionCount: 3, version: '1.0.0', createdAt: '2026-01-01T00:00:00' }
  if (pathname === '/api/banks/1/questions') return { records: ROWS, total: 3 }
  if (pathname === '/api/banks/1/question-nav') return ROWS.map((q) => ({ questionId: q.questionId, questionType: q.questionType, questionNumber: q.questionNumber }))
  if (pathname === '/api/banks/1/progress') return { totalQuestions: 3, answeredCount: 1, correctCount: 1, recordsCount: 1, accuracy: 1 }
  if (pathname === '/api/banks/1/materials') return []
  if (/^\/api\/banks\/1\/sessions$/.test(pathname)) return { records: SESSIONS, total: 3 }
  if (/^\/api\/questions\/\d+$/.test(pathname)) return ROWS.find((q) => q.questionId === Number(pathname.split('/').pop())) || Q(999)
  if (pathname === '/api/home/overview') return { bankCount: 1, questionCount: 3, dueToday: 0, wrongCount: 0, recent: null }
  if (pathname === '/api/banks') {
    // 贴近真实使用（按用户反馈截图里的形态造数据）：长中文标题、题量差异大、部分带版本/作者、时间跨度大
    const rows = [
      { id: 1, name: '纯图片pdf文件', questionCount: 40, answeredCount: 0, version: '1.0.0', createdAt: '2026-09-12T19:47:00' },
      { id: 2, name: '2026年安徽省公务员录用考试行政职业能力测验模拟卷（第三套）', questionCount: 125, answeredCount: 12, createdAt: '2026-09-12T19:43:00' },
      { id: 3, name: '专项智能练习（判断推理）(3)', questionCount: 40, answeredCount: 40, version: '1.0.0', description: '判断推理专项：图形推理 + 逻辑判断', createdAt: '2026-09-11T23:38:00' },
      { id: 4, name: '专项智能练习（判断推理）(1)', questionCount: 20, answeredCount: 3, createdAt: '2026-09-11T23:19:00' },
      { id: 5, name: '2024安徽高考真题物理（教师版·含解析）', questionCount: 15, answeredCount: 0, version: '1.0.0', createdAt: '2026-09-11T04:44:00' },
      { id: 6, name: '2024安徽高考真题物理', questionCount: 15, answeredCount: 15, version: '1.0.0', authorName: 'Nauv', createdAt: '2026-09-11T01:08:00' },
      { id: 7, name: '2026年安徽省公务员录用考试申论', questionCount: 125, answeredCount: 0, createdAt: '2026-09-10T23:48:00' },
      { id: 8, name: 'sat-practice-test-6-digital', questionCount: 33, answeredCount: 7, createdAt: '2026-09-10T02:16:00' },
      { id: 9, name: '2026语文新高考I卷试题', questionCount: 9, answeredCount: 0, createdAt: '2026-09-07T16:45:00' },
      { id: 10, name: '2026年新高考I卷数学真题', questionCount: 19, answeredCount: 19, version: '1.0.0', createdAt: '2026-09-07T16:40:00' },
      { id: 11, name: '2026年安徽省公务员录用考试行测（模考一）', questionCount: 125, answeredCount: 0, version: '1.0.0', authorName: 'Nauv', createdAt: '2026-09-07T10:06:00' },
      { id: 12, name: '2026年安徽省公务员录用考试行测（模考二）', questionCount: 125, answeredCount: 0, createdAt: '2026-09-07T08:45:00' }
    ]
    return { records: rows, total: rows.length }
  }
  if (pathname.startsWith('/api/stats')) return { todayCount: 0, todayCorrect: 0, todayDecided: 0, streakDays: 0, longestStreak: 0, totalAnswered: 0, correctTotal: 0, decidedTotal: 0, totalSeconds: 0, dueToday: 0, overdue: 0, daily: [], levels: [], trend: [], bankMastery: [], wrongHeal: null, recentSessions: [] }
  if (pathname === '/api/ai/settings') return { hasKey: false, baseUrl: '', model: '', presets: [], mineruEnabled: false, thinkingEnabled: false }
  if (pathname === '/api/ai/presets' || pathname === '/api/ai/models') return []
  if (pathname.startsWith('/api/ai-import/jobs')) return []
  if (pathname.endsWith('/wrong-questions') || pathname.endsWith('/records') || pathname.endsWith('/review/due')) return { records: [], total: 0 }
  return { records: [], total: 0 }
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: VW, height: VH } })
const errs = []
page.on('pageerror', (e) => errs.push(String(e.message || e)))
await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
  const p = new URL(route.request().url()).pathname
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: stub(p) }) })
})

for (const r of routes) {
  errs.length = 0
  await page.goto(BASE + r, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1800)
  const slug = (r === '/' ? 'root' : r.replace(/[^\w]+/g, '_').replace(/^_|_$/g, '')) + `_${VW}x${VH}`
  const file = join(OUT, slug + '.png')
  await page.screenshot({ path: file, fullPage: true })
  console.log(`${r} → ${file}${errs.length ? '  [JS 错误] ' + errs.join(' | ').slice(0, 200) : ''}`)
}
await browser.close()
