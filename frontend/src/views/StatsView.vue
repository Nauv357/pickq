<template>
  <div class="page stats-page">
    <header class="page-header stats-header">
      <div>
        <h1 class="page-title">学习统计</h1>
        <p class="page-desc text-secondary">坚持看得见 · 每个数字都指向下一步（去做题 / 去复习）</p>
      </div>
      <div class="stats-actions">
        <button v-if="hasData" class="btn btn-primary btn-sm" @click="$router.push('/')">
          <TikuIcon name="play" :size="14" />
          去做题
        </button>
        <span class="text-muted stats-note">数据仅存本机</span>
      </div>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="stats-loading">
      <div v-for="n in 4" :key="n" class="card tiku-skeleton"><div class="sk-line" style="width: 60%"></div><div class="sk-line" style="width: 90%"></div></div>
    </div>

    <!-- 空态：从未做过题 -->
    <div v-else-if="!hasData" class="empty stats-empty">
      <TikuIcon name="chart" :size="40" />
      <h3>还没有学习记录</h3>
      <p class="text-secondary">做完第一轮题后，这里会显示你的坚持天数、正确率趋势与复习健康</p>
      <div class="empty-actions">
        <button class="btn btn-primary" @click="$router.push('/')">
          <TikuIcon name="play" :size="14" />
          去刷第一轮
        </button>
      </div>
    </div>

    <template v-else>
      <!-- ============ KPI 行 ============ -->
      <div class="kpi-row">
        <!-- 今日目标环 -->
        <div class="card kpi-card goal-card">
          <div class="goal-ring-wrap">
            <svg class="goal-ring" :width="76" :height="76" viewBox="0 0 76 76">
              <circle cx="38" cy="38" r="31" fill="none" stroke="var(--bg-hover)" stroke-width="6" />
              <circle
                cx="38" cy="38" r="31" fill="none"
                :stroke="goalDone ? 'var(--success)' : 'var(--accent)'"
                stroke-width="6" stroke-linecap="round"
                :stroke-dasharray="circ"
                :stroke-dashoffset="circ * (1 - goalPct)"
                transform="rotate(-90 38 38)"
              />
            </svg>
            <div class="goal-ring-num">
              <span class="mono">{{ s.todayCount }}</span>
              <span class="goal-ring-label">已做</span>
            </div>
          </div>
          <div class="goal-text">
            <div class="kpi-title">今日目标</div>
            <div class="goal-line">
              <span :class="{ done: goalDone }" class="mono">{{ s.todayCount }} / {{ goal }}</span>
              <span v-if="goalDone" class="goal-ok">✓ 达成</span>
              <span v-else-if="s.todayCount > 0" class="goal-rest text-muted">还差 {{ goal - s.todayCount }} 题</span>
            </div>
            <div v-if="s.todayDecided" class="goal-rate text-muted">今日正确率 {{ pct(s.todayCorrect, s.todayDecided) }}</div>
            <button class="goal-edit-link" @click="goalEditing = !goalEditing">调整目标</button>
            <div v-if="goalEditing" class="goal-editor">
              <el-input-number v-model="goalDraft" :min="1" :max="300" size="small" controls-position="right" style="width: 110px" />
              <button class="btn btn-primary btn-sm" @click="saveGoal">保存</button>
            </div>
          </div>
        </div>

        <!-- 连续学习 -->
        <div class="card kpi-card">
          <div class="kpi-label"><TikuIcon name="flame" :size="15" :class="{ hot: s.streakDays > 0 }" class="flame" />连续学习</div>
          <div class="kpi-num mono">{{ s.streakDays }}<span class="kpi-unit">天</span></div>
          <div class="kpi-sub text-muted">
            最长连胜 {{ s.longestStreak }} 天
            <template v-if="s.streakDays > 0 && s.streakDays >= s.longestStreak && s.streakDays >= 3">· 正在刷新纪录</template>
          </div>
        </div>

        <!-- 累计 -->
        <div class="card kpi-card">
          <div class="kpi-label">累计做题</div>
          <div class="kpi-num mono">{{ s.totalAnswered }}<span class="kpi-unit">题</span></div>
          <div class="kpi-sub text-muted">
            <template v-if="s.decidedTotal">正确率 {{ pct(s.correctTotal, s.decidedTotal) }}</template>
            <template v-else>（暂无判定记录）</template>
            <template v-if="s.totalSeconds >= 60"> · 用时 {{ fmtHours(s.totalSeconds) }}</template>
          </div>
        </div>

        <!-- 复习 -->
        <div class="card kpi-card">
          <div class="kpi-label">今日复习</div>
          <div class="kpi-num mono" :class="{ due: s.dueToday > 0 }">{{ s.dueToday }}<span class="kpi-unit">到期</span></div>
          <div class="kpi-sub text-muted">
            <template v-if="s.overdue > 0">逾期 {{ s.overdue }} 题</template>
            <template v-else-if="s.dueToday > 0">今天到期，宜早不宜迟</template>
            <template v-else>队列清爽</template>
            · 复习题 {{ s.reviewQuestionCount }}
          </div>
        </div>
      </div>

      <!-- ============ 日常看板：日历 / 复习健康 / 正确率趋势（三列等高） ============ -->
      <div class="main-grid">
        <!-- 学习日历（自绘热力图） -->
        <section class="card chart-card">
          <div class="chart-head">
            <h2 class="chart-title">学习日历</h2>
            <span v-if="s.streakDays > 0" class="streak-chip">
              <TikuIcon name="flame" :size="13" class="flame hot" /> 连续 {{ s.streakDays }} 天
            </span>
          </div>
          <StatsHeatmap :daily="s.daily" />
        </section>

        <!-- 复习健康 -->
        <section class="card chart-card">
          <div class="chart-head">
            <h2 class="chart-title">复习健康</h2>
            <span v-if="s.dueToday > 0" class="badge-due">今天 {{ s.dueToday }} 题</span>
          </div>
          <div ref="forecastEl" class="echart-box"></div>
          <div class="level-block">
            <div class="level-title text-muted">
              <span>熟练度分布</span>
              <span>{{ s.reviewQuestionCount }} 题</span>
            </div>
            <div class="level-bar">
              <span v-for="(n, i) in s.levelDist" :key="i" class="level-seg" :class="`lv-${i}`" :style="{ width: levelPct(n) }" :title="`level ${i}：${n} 题`"></span>
            </div>
            <div class="level-legend text-muted">
              <span><i class="lv-dot lv-0"></i>新</span>
              <span><i class="lv-dot lv-1"></i>初学</span>
              <span><i class="lv-dot lv-3"></i>渐稳</span>
              <span><i class="lv-dot lv-5"></i>稳定</span>
            </div>
          </div>
          <div v-if="s.reviewQuestionCount === 0" class="chart-empty text-muted">
            还没有复习队列：做几轮题并开启复习计划后，这里会预测未来到期量
          </div>
        </section>

        <!-- 正确率趋势 -->
        <section class="card chart-card">
          <div class="chart-head">
            <h2 class="chart-title">正确率趋势</h2>
            <span class="text-muted chart-range">近 90 天 · 7 天均线</span>
          </div>
          <div v-if="trendEmpty" class="chart-empty text-muted">
            有判定记录的日期还太少，先多刷几轮再来看趋势
          </div>
          <div v-else ref="trendEl" class="echart-box"></div>
          <div class="trend-mini text-muted">
            <span>今日正确率 <b :class="{ ok: todayRate >= 0.6 }">{{ todayRateText }}</b></span>
            <span>累计正确率 <b>{{ pct(s.correctTotal, s.decidedTotal) }}</b></span>
            <span>总用时 <b>{{ fmtHours(s.totalSeconds) }}</b></span>
          </div>
        </section>
      </div>

      <!-- ============ 深入分析（错题治愈 / 题库掌握度 / 最近练习；默认收起，按需展开） ============ -->
      <section class="deep-section">
        <button class="deep-head" @click="deepOpen = !deepOpen">
          <TikuIcon name="chart" :size="15" />
          <span>深入分析</span>
          <span class="deep-sub text-muted">错题治愈 · 题库掌握度 · 最近练习</span>
          <span class="bar-grow"></span>
          <span class="deep-open text-muted">{{ deepOpen ? '收起' : '展开' }}</span>
          <TikuIcon :name="deepOpen ? 'chevron-up' : 'chevron-down'" :size="14" />
        </button>

        <div v-show="deepOpen" class="second-grid">
        <!-- D 题库 × 掌握度 -->
        <section class="card chart-card">
          <div class="chart-head">
            <h2 class="chart-title">题库 × 掌握度</h2>
            <span class="text-muted chart-range">薄弱优先 · 正确率 &lt; 60% 标红</span>
          </div>
          <div v-if="detailLoading" class="chart-empty text-muted">加载中…</div>
          <div v-else-if="!detail || !weakBanks.length" class="chart-empty text-muted">还没有题库数据</div>
          <div v-else class="bank-stat-list">
            <div v-for="b in weakBanks" :key="b.bankId" class="bank-stat-row">
              <button class="bank-stat-name" :title="`进入「${b.name}」`" @click="$router.push(`/banks/${b.bankId}`)">
                {{ b.name }}
              </button>
              <span class="bank-stat-count text-muted">{{ b.answered }}/{{ b.total }} 题</span>
              <div class="bank-stat-progress" :title="`已做 ${b.answered} / 共 ${b.total} 题`">
                <div class="bank-stat-fill" :style="{ width: b.total ? Math.round((b.answered / b.total) * 100) + '%' : '0%' }"></div>
              </div>
              <span class="bank-stat-rate mono" :class="{ weak: isWeak(b) }">
                {{ rateText(b) }}
              </span>
              <span v-if="isWeak(b)" class="weak-tag">薄弱</span>
              <button class="btn btn-ghost btn-xs" :disabled="!b.total" @click="$router.push(`/banks/${b.bankId}`)">去练</button>
            </div>
          </div>
        </section>

        <!-- E + F 右列 -->
        <div class="chart-col">
          <!-- E 错题治愈 -->
          <section class="card chart-card">
            <div class="chart-head">
              <h2 class="chart-title">错题治愈</h2>
              <span v-if="wrongHeal && wrongHeal.currentWrong > 0" class="badge-due">当前 {{ wrongHeal.currentWrong }} 题</span>
              <span v-else-if="wrongHeal" class="goal-ok">错题本已清空 🎉</span>
            </div>
            <div v-if="detailLoading" class="chart-empty text-muted">加载中…</div>
            <template v-else-if="wrongHeal">
              <div class="heal-top">
                <div class="heal-big">
                  <div class="kpi-num mono" :class="{ due: wrongHeal.currentWrong > 0 }">{{ wrongHeal.currentWrong }}</div>
                  <div class="kpi-sub text-muted">当前错题（最近一次答错）</div>
                </div>
                <div class="heal-repro">
                  <div class="heal-repro-num mono">
                    {{ wrongHeal.reproduceDecided ? pct(wrongHeal.reproduceCorrect, wrongHeal.reproduceDecided) : '—' }}
                  </div>
                  <div class="kpi-sub text-muted">曾错题再考正确率</div>
                </div>
                <div class="heal-trend">
                  <div class="heal-trend-bars">
                    <div
                      v-for="p in wrongHeal.wrongTrend"
                      :key="p.month"
                      class="heal-bar"
                      :class="{ zero: p.count === 0 }"
                      :style="{ height: trendBarH(p.count) }"
                      :title="`${p.month}：错题 ${p.count}`"
                    ></div>
                  </div>
                  <div class="heal-trend-label text-muted">
                    <span>{{ wrongHeal.wrongTrend[0]?.month?.slice(2).replace('-', '/') }}</span>
                    <span>{{ wrongHeal.wrongTrend[wrongHeal.wrongTrend.length - 1]?.month?.slice(2).replace('-', '/') }}</span>
                  </div>
                  <div class="kpi-sub text-muted">近 6 月错题数</div>
                </div>
              </div>
              <div v-if="wrongHeal.recentlyHealed.length" class="heal-list">
                <div class="heal-list-title text-muted">最近治愈（曾错 → 现已做对）</div>
                <div v-for="h in wrongHeal.recentlyHealed" :key="h.questionId" class="heal-item">
                  <span class="heal-check">✓</span>
                  <span class="heal-content">{{ h.content }}</span>
                  <span class="heal-meta text-muted">{{ h.bankName }}{{ h.questionNumber != null ? ' · #' + h.questionNumber : '' }} · {{ fmtDate(h.answeredAt) }}</span>
                </div>
              </div>
              <div v-else-if="!wrongHeal.currentWrong && !wrongHeal.recentlyHealed.length" class="chart-empty text-muted">
                还没有错题历史：做几轮题后这里会追踪错题本是否真的在变小
              </div>
              <div v-else-if="!wrongHeal && !detailLoading" class="chart-empty text-muted">
                统计加载失败，请刷新页面重试
              </div>
            </template>
          </section>

          <!-- F 最近练习 -->
          <section class="card chart-card">
            <div class="chart-head">
              <h2 class="chart-title">最近练习</h2>
              <span class="text-muted chart-range">最近 10 场 · 得分率</span>
            </div>
            <div v-if="detailLoading" class="chart-empty text-muted">加载中…</div>
            <div v-else-if="!detail || !detail.recentSessions.length" class="chart-empty text-muted">
              还没有完成过会话：去刷完第一场吧
            </div>
            <div v-else ref="sessionEl" class="echart-box echart-box-lg"></div>
          </section>
        </div>
        </div>
      </section>

      <p class="stats-footnote text-muted">
        口径：作答数 = 作答记录条数；正确率仅统计有判定记录（无答案客观题作答计入题量不计入正确率；主观题按自评「回答正确」计对）；
        用时与单题耗时仅交卷会话有计时；日历与趋势按本机日期。
      </p>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getStatsDetail, getStatsSummary } from '../api/stats'
import { formatDate } from '../utils/format'
import TikuIcon from '../components/TikuIcon.vue'
import StatsHeatmap from '../components/StatsHeatmap.vue'

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const loading = ref(true)
const s = ref(null)

async function load() {
  loading.value = true
  try {
    s.value = await getStatsSummary()
  } catch (e) {
    s.value = null
  } finally {
    loading.value = false
  }
}
const hasData = computed(() => !!s.value && s.value.totalAnswered > 0)

/* 深入分析区（二期 D/E/F）默认收起——渐进披露，避免首屏信息过载 */
const deepOpen = ref(false)

/* 趋势卡底部 mini 行 */
const todayRate = computed(() => {
  const d = s.value
  if (!d || !d.todayDecided) return null
  return d.todayCorrect / d.todayDecided
})
const todayRateText = computed(() => (todayRate.value == null ? '—' : pct(s.value.todayCorrect, s.value.todayDecided)))

/* ============ 二期 D/E/F（题库掌握度 / 错题治愈 / 最近练习） ============ */
const detail = ref(null)
const detailLoading = ref(true)
async function loadDetail() {
  detailLoading.value = true
  try {
    detail.value = await getStatsDetail()
  } catch (e) {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

const MODE_LABELS = {
  ALL: '全部随机',
  SEQUENCE: '顺序',
  TOPIC: '按分类',
  REVIEW: '复习',
  WRONG: '错题',
  FAVORITE: '收藏'
}

const wrongHeal = computed(() => detail.value?.wrongHeal || null)

function rateOf(b) {
  return b.decided > 0 ? b.correct / b.decided : null
}
function isWeak(b) {
  const r = rateOf(b)
  return r != null && r < 0.6
}
function rateText(b) {
  if (b.decided === 0) return b.answered === 0 ? '未做' : '—'
  return pct(b.correct, b.decided)
}
/* 薄弱优先：未做 → 无判定 → 低正确率 → 高正确率 */
const weakBanks = computed(() => {
  const list = [...(detail.value?.banks || [])]
  list.sort((a, b) => {
    const grp = (x) => (x.answered === 0 ? 0 : x.decided === 0 ? 1 : rateOf(x) < 0.6 ? 2 : 3)
    const g = grp(a) - grp(b)
    if (g !== 0) return g
    const ra = rateOf(a) ?? 1
    const rb = rateOf(b) ?? 1
    return ra - rb || a.name.localeCompare(b.name, 'zh')
  })
  return list
})

function trendBarH(count) {
  const max = Math.max(1, ...(wrongHeal.value?.wrongTrend || []).map((p) => p.count))
  return count > 0 ? `${Math.max(6, Math.round((count / max) * 44))}px` : '3px'
}
const fmtDate = (iso) => (iso ? formatDate(String(iso).replace(' ', 'T')) : '')

function pct(a, b) {
  if (!b) return '—'
  return `${Math.round((a / b) * 100)}%`
}
function fmtHours(sec) {
  const h = Math.floor(sec / 3600)
  const m = Math.round((sec % 3600) / 60)
  return h > 0 ? `${h} 小时${m ? ' ' + m + ' 分' : ''}` : `${m} 分`
}

/* ============ 每日目标（localStorage 可自设） ============ */
const GOAL_KEY = 'tiku:daily-goal'
const goal = ref(20)
const goalEditing = ref(false)
const goalDraft = ref(20)
try {
  const g = Number(localStorage.getItem(GOAL_KEY))
  if (Number.isInteger(g) && g > 0) goal.value = g
} catch (e) {
  /* 忽略 */
}
const circ = 2 * Math.PI * 31
const goalPct = computed(() => (goal.value > 0 ? Math.min(1, s.value?.todayCount / goal.value) : 0))
const goalDone = computed(() => s.value?.todayCount >= goal.value)
function saveGoal() {
  const g = Math.round(goalDraft.value)
  if (!(g >= 1 && g <= 300)) {
    ElMessage.warning('目标需在 1~300 之间')
    return
  }
  goal.value = g
  goalEditing.value = false
  try {
    localStorage.setItem(GOAL_KEY, String(g))
  } catch (e) {
    /* 忽略 */
  }
  ElMessage.success(`每日目标已设为 ${g} 题`)
}

/* ============ 熟练度分布 ============ */
function levelPct(n) {
  const total = s.value?.reviewQuestionCount || 0
  return total ? `${Math.max(0.5, (n / total) * 100)}%` : '0%'
}

/* ============ ECharts：到期预测 + 正确率趋势 + 最近练习得分率 ============ */
const forecastEl = ref(null)
const trendEl = ref(null)
const sessionEl = ref(null)
let forecastChart = null
let trendChart = null
let sessionChart = null
let ro = null

const trendEmpty = computed(() => {
  if (!s.value) return true
  const tail = s.value.daily.slice(-90)
  return tail.filter((d) => d.decided > 0).length < 3
})

function axisColor() {
  return getComputedStyle(document.documentElement).getPropertyValue('--text-muted').trim() || '#a09a8d'
}
/* canvas 不支持 CSS 变量：运行时读主题变量为具体色值 */
function varColor(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function renderForecast() {
  if (!forecastEl.value || !s.value) return
  if (!forecastChart) forecastChart = echarts.init(forecastEl.value)
  const data = s.value.dueForecast
  const borderStrong = varColor('--border-strong', '#c2bdb0')
  const hoverBg = varColor('--bg-hover', 'rgba(0,0,0,0.05)')
  const accent = varColor('--accent', '#1a1a18')
  const danger = varColor('--danger', '#b42318')
  forecastChart.setOption({
    grid: { left: 8, right: 8, top: 18, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => {
        const p = ps[0]
        const i = p.dataIndex
        const d = data[i]
        const over = i === 0 && s.value.overdue > 0 ? `<br/><span style="color:${danger}">其中逾期 ${s.value.overdue} 题</span>` : ''
        return `${d.date}<br/>到期 <b>${d.count}</b> 题${over}`
      }
    },
    xAxis: {
      type: 'category',
      data: data.map((d) => d.date.slice(5).replace('-', '/')),
      axisLine: { lineStyle: { color: borderStrong } },
      axisTick: { show: false },
      axisLabel: { color: axisColor(), fontSize: 10, interval: 6 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: axisColor(), fontSize: 10 },
      splitLine: { lineStyle: { color: hoverBg } }
    },
    series: [
      {
        type: 'bar',
        data: data.map((d, i) => ({
          value: d.count,
          itemStyle: { color: i === 0 ? (d.count > 0 ? danger : hoverBg) : accent }
        })),
        barWidth: '62%'
      }
    ]
  })
}

function renderTrend() {
  if (!trendEl.value || !s.value) return
  if (!trendChart) trendChart = echarts.init(trendEl.value)
  const tail = s.value.daily.slice(-90)
  const labels = tail.map((d) => d.date.slice(5).replace('-', '/'))
  const rates = tail.map((d) => (d.decided > 0 ? Math.round((d.correct / d.decided) * 100) : null))
  //7 天移动平均（有判定日才计）
  const decidedIdx = tail.map((d, i) => (d.decided > 0 ? i : -1)).filter((i) => i >= 0)
  const avg7 = []
  for (let i = 0; i < tail.length; i++) {
    const win = decidedIdx.filter((j) => j <= i && j > i - 7)
    if (win.length >= 3) {
      avg7.push(Math.round(win.reduce((a, j) => a + (tail[j].correct / tail[j].decided) * 100, 0) / win.length))
    } else {
      avg7.push(null)
    }
  }
  const borderStrong = varColor('--border-strong', '#c2bdb0')
  const hoverBg = varColor('--bg-hover', 'rgba(0,0,0,0.05)')
  const success = varColor('--success', '#1e7a4f')
  const accent = varColor('--accent', '#1a1a18')
  trendChart.setOption({
    grid: { left: 8, right: 8, top: 18, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => {
        const i = ps[0].dataIndex
        const d = tail[i]
        let t = `${d.date}<br/>做题 <b>${d.count}</b>`
        if (d.decided) t += ` · 正确率 <b>${Math.round((d.correct / d.decided) * 100)}%</b>`
        return t
      }
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: borderStrong } },
      axisTick: { show: false },
      axisLabel: { color: axisColor(), fontSize: 10, interval: 13 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { color: axisColor(), fontSize: 10, formatter: '{value}%' },
      splitLine: { lineStyle: { color: hoverBg } }
    },
    series: [
      {
        name: '正确率',
        type: 'line',
        data: rates,
        showSymbol: false,
        lineStyle: { width: 2, color: success },
        connectNulls: true
      },
      {
        name: '7 天均线',
        type: 'line',
        data: avg7,
        showSymbol: false,
        lineStyle: { width: 1.5, type: 'dashed', color: accent },
        connectNulls: false
      }
    ]
  })
}

/* 最近练习得分率折线（近 10 场完成会话） */
function renderSessions() {
  if (!sessionEl.value || !detail.value) return
  if (!sessionChart) sessionChart = echarts.init(sessionEl.value)
  const list = detail.value.recentSessions
  const success = varColor('--success', '#1e7a4f')
  const accent = varColor('--accent', '#1a1a18')
  const borderStrong = varColor('--border-strong', '#c2bdb0')
  const hoverBg = varColor('--bg-hover', 'rgba(0,0,0,0.05)')
  const labels = list.map((x) => String(x.finishedAt).slice(5, 10).replace('-', '/'))
  const rates = list.map((x) =>
    x.questionCount > 0 ? Math.round(((x.correct || 0) / x.questionCount) * 100) : null
  )
  sessionChart.setOption({
    grid: { left: 8, right: 8, top: 18, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => {
        const i = ps[0].dataIndex
        const x = list[i]
        const rate = x.questionCount > 0 ? Math.round(((x.correct || 0) / x.questionCount) * 100) : 0
        const mode = MODE_LABELS[x.mode] || x.mode || ''
        return `${String(x.finishedAt).slice(0, 16).replace('T', ' ')}<br/>${x.bankName} · ${mode}<br/>答对 <b>${x.correct || 0}</b>/${x.questionCount}（${rate}%）`
      }
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: borderStrong } },
      axisTick: { show: false },
      axisLabel: { color: axisColor(), fontSize: 10, interval: 0 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { color: axisColor(), fontSize: 10, formatter: '{value}%' },
      splitLine: { lineStyle: { color: hoverBg } }
    },
    series: [
      {
        type: 'line',
        data: rates,
        showSymbol: true,
        symbolSize: 6,
        lineStyle: { width: 2, color: accent },
        itemStyle: { color: accent },
        connectNulls: false
      }
    ]
  })
}

watch([s, trendEmpty], () => {
  nextTick(() => {
    renderForecast()
    if (!trendEmpty.value) renderTrend()
    if (ro) {
      if (forecastEl.value) ro.observe(forecastEl.value)
      if (trendEl.value) ro.observe(trendEl.value)
    }
  })
})

watch(detail, () => {
  nextTick(() => {
    renderSessions()
    if (sessionEl.value) ro?.observe(sessionEl.value)
  })
})

function onResize() {
  forecastChart?.resize()
  trendChart?.resize()
  sessionChart?.resize()
}

//主题切换（html.dark class 变化）时按新主题色重绘
let themeObserver = null

onMounted(() => {
  load()
  loadDetail()
  ro = new ResizeObserver(onResize)
  themeObserver = new MutationObserver(() => {
    if (s.value) {
      renderForecast()
      if (!trendEmpty.value) renderTrend()
    }
    if (detail.value) renderSessions()
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})
onBeforeUnmount(() => {
  ro?.disconnect()
  themeObserver?.disconnect()
  forecastChart?.dispose()
  trendChart?.dispose()
  sessionChart?.dispose()
})
</script>

<style scoped>
.stats-page {
  padding-bottom: 40px;
}
.stats-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.stats-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stats-note {
  font-size: 12px;
}
.stats-loading {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}
.stats-loading .card {
  padding: 18px;
  min-height: 120px;
}
.stats-empty {
  padding: 80px 0;
}

/* ============ KPI 行（4 等宽，紧凑） ============ */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.kpi-card {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  min-height: 96px;
}
.kpi-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}
.flame {
  color: var(--text-muted);
}
.flame.hot {
  color: var(--warning);
}
.kpi-num {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.kpi-num.due {
  color: var(--danger);
}
.kpi-unit {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-secondary);
  margin-left: 5px;
}
.kpi-sub {
  font-size: 11px;
  line-height: 1.5;
}

/* 今日目标环 */
.goal-card {
  flex-direction: row;
  align-items: center;
  gap: 14px;
}
.goal-ring-wrap {
  position: relative;
  flex-shrink: 0;
}
.goal-ring-num {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
}
.goal-ring-num .mono {
  font-size: 17px;
  font-weight: 700;
  line-height: 1;
}
.goal-ring-label {
  font-size: 9px;
  color: var(--text-muted);
}
.goal-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.kpi-title {
  font-size: 12px;
  color: var(--text-secondary);
}
.goal-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  white-space: nowrap;
}
.goal-line .done,
.goal-ok {
  color: var(--success);
}
.goal-rest {
  font-size: 11px;
}
.goal-rate {
  font-size: 11px;
}
.goal-edit-link {
  align-self: flex-start;
  border: none;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--text-secondary);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}
.goal-edit-link:hover {
  color: var(--accent-text);
}
.goal-editor {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}

/* ============ 日常看板（三列等高） ============ */
.main-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  align-items: stretch;
}
.chart-card {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 22px;
}
.chart-title {
  font-size: 14px;
}
.streak-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid color-mix(in srgb, var(--warning) 40%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.badge-due {
  font-size: 11px;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.echart-box {
  width: 100%;
  height: 132px;
}
.chart-empty {
  padding: 14px 6px;
  font-size: 12px;
  text-align: center;
  line-height: 1.6;
  color: var(--text-muted);
}
.chart-range {
  font-size: 11px;
  white-space: nowrap;
}
.trend-mini {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  font-size: 11px;
  border-top: 1px dashed var(--border);
  padding-top: 6px;
  flex-wrap: wrap;
}
.trend-mini b {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.trend-mini b.ok {
  color: var(--success);
}

/* 复习健康：熟练度分布（压缩在卡内） */
.level-block {
  display: flex;
  flex-direction: column;
  gap: 5px;
  border-top: 1px dashed var(--border);
  padding-top: 6px;
}
.level-title {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
}
.level-bar {
  display: flex;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--bg-elev);
}
.level-seg {
  height: 100%;
}
.lv-0 { background: var(--border-strong); }
.lv-1 { background: var(--warning); }
.lv-2 { background: color-mix(in srgb, var(--warning) 70%, var(--success)); }
.lv-3 { background: var(--accent); }
.lv-4 { background: color-mix(in srgb, var(--success) 60%, var(--accent)); }
.lv-5 { background: var(--success); }
.level-legend {
  display: flex;
  gap: 10px;
  font-size: 10px;
}
.level-legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.lv-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

/* ============ 深入分析（默认收起） ============ */
.deep-section {
  margin-top: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: transparent;
}
.deep-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: none;
  background: none;
  padding: 12px 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  cursor: pointer;
  font-family: var(--font-sans);
}
.deep-head:hover {
  background: var(--bg-hover);
}
.deep-head .deep-sub {
  font-size: 11px;
  font-weight: 400;
}
.deep-open {
  font-size: 11px;
  font-weight: 400;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.stats-footnote {
  margin-top: 14px;
  font-size: 11px;
  line-height: 1.7;
}

/* ============ 二期 D/E/F（深入分析展开后） ============ */
.second-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 0 12px 12px;
}
.chart-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bank-stat-list {
  display: flex;
  flex-direction: column;
  max-height: 380px;
  overflow-y: auto;
  gap: 2px;
  padding-right: 2px;
}
.bank-stat-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 8px;
  border-radius: 6px;
}
.bank-stat-row:hover {
  background: var(--bg-hover);
}
.bank-stat-name {
  flex: 0 1 220px;
  min-width: 0;
  border: none;
  background: none;
  padding: 0;
  text-align: left;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.bank-stat-name:hover {
  color: var(--accent-text);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.bank-stat-count {
  flex-shrink: 0;
  font-size: 11px;
  width: 52px;
  text-align: right;
}
.bank-stat-progress {
  flex: 1;
  min-width: 60px;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-elev);
  overflow: hidden;
}
.bank-stat-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--accent);
  transition: width var(--ease);
}
.bank-stat-rate {
  flex-shrink: 0;
  width: 46px;
  text-align: right;
  font-size: 13px;
}
.bank-stat-rate.weak {
  color: var(--danger);
  font-weight: 600;
}
.weak-tag {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: 999px;
  padding: 1px 7px;
}
.btn-xs {
  padding: 3px 9px;
  font-size: 12px;
  flex-shrink: 0;
}

/* E 错题治愈 */
.heal-top {
  display: grid;
  grid-template-columns: 1fr 1fr 1.2fr;
  gap: 14px;
  align-items: start;
  margin-bottom: 8px;
}
.heal-repro-num {
  font-size: 22px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.heal-trend-bars {
  display: flex;
  align-items: flex-end;
  gap: 5px;
  height: 48px;
}
.heal-bar {
  flex: 1;
  border-radius: 3px 3px 0 0;
  background: var(--danger);
  min-height: 3px;
  transition: height var(--ease);
}
.heal-bar.zero {
  background: var(--border);
}
.heal-trend-label {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  margin-top: 2px;
}
.heal-list {
  border-top: 1px dashed var(--border);
  padding-top: 8px;
  margin-top: 6px;
}
.heal-list-title {
  font-size: 11px;
  margin-bottom: 4px;
}
.heal-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  padding: 3px 0;
}
.heal-check {
  color: var(--success);
  font-weight: 700;
  flex-shrink: 0;
}
.heal-content {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.heal-meta {
  flex-shrink: 0;
  font-size: 11px;
}
.echart-box-lg {
  height: 160px;
}

@media (max-width: 1500px) {
  .main-grid {
    grid-template-columns: 1fr 1fr;
  }
  .main-grid .chart-card:first-child {
    grid-column: 1 / -1; /* 日历卡整行，格子可更大 */
  }
}
@media (max-width: 1100px) {
  .kpi-row {
    grid-template-columns: 1fr 1fr;
  }
  .main-grid {
    grid-template-columns: 1fr;
  }
  .main-grid .chart-card:first-child {
    grid-column: auto;
  }
  .second-grid {
    grid-template-columns: 1fr;
  }
}
</style>
