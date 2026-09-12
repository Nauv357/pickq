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
  if (pathname === '/api/banks') return { records: [{ id: 1, name: '冒烟题库', description: 'e2e', questionCount: 3, createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-02T00:00:00' }], total: 1 }
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
