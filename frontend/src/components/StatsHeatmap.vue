<template>
  <div class="heat-wrap">
    <div class="heat-head">
      <span class="heat-title text-muted">
        {{ t('heatTitle') }} · {{ view === 'year' ? t('year') : view === '30' ? t('d30') : t('d90') }}
      </span>
      <div class="heat-toggle">
        <button class="heat-tab" :class="{ on: view === '30' }" @click="view = '30'">{{ t('d30') }}</button>
        <button class="heat-tab" :class="{ on: view === '90' }" @click="view = '90'">{{ t('d90') }}</button>
        <button class="heat-tab" :class="{ on: view === 'year' }" @click="view = 'year'">{{ t('year') }}</button>
      </div>
    </div>

    <div class="heat-scroll">
      <div
        class="heat-grid"
        :class="{ 'heat-year': view === 'year' }"
        :style="{ gridTemplateColumns: `repeat(${weekCols}, ${cellSize}px)`, gap: cellGap + 'px' }"
      >
        <button
          v-for="c in cells"
          :key="c.date"
          class="heat-cell"
          :class="[`heat-l${c.level}`, { sel: c.date === selDate, today: c.date === todayStr }]"
          :title="cellTitle(c)"
          @click="selDate = c.date === selDate ? null : c.date"
        ></button>
      </div>
    </div>

    <div v-if="selDate" class="heat-detail">
      <span class="mono heat-date">{{ selDate }}</span>
      <template v-if="selStat">
        <b>{{ selStat.count }}</b> {{ t('qUnit') }}
        <span v-if="selStat.decided" class="text-muted">
          · {{ t('accuracy') }} {{ pct(selStat.correct, selStat.decided) }}
        </span>
        <span v-else-if="selStat.count" class="text-muted">{{ t('notJudged') }}</span>
      </template>
      <template v-else>{{ t('noRecords') }}</template>
      <button class="heat-clear" :title="t('clearSel')" @click="selDate = null">
        <TikuIcon name="x" :size="11" />
      </button>
    </div>

    <div class="heat-legend text-muted">
      <span>{{ t('less') }}</span>
      <i class="heat-cell heat-l0"></i>
      <i class="heat-cell heat-l1"></i>
      <i class="heat-cell heat-l2"></i>
      <i class="heat-cell heat-l3"></i>
      <i class="heat-cell heat-l4"></i>
      <i class="heat-cell heat-l5"></i>
      <span>{{ t('more') }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': { heatTitle: '每日做题量', year: '全年', d30: '近 30 天', d90: '近 90 天', qUnit: '题', accuracy: '正确率', notJudged: '（未判定作答）', noRecords: '没有学习记录', clearSel: '清除选择', less: '少', more: '多' },
    'en-US': { heatTitle: 'Daily activity', year: 'Year', d30: 'Last 30 days', d90: 'Last 90 days', qUnit: 'q', accuracy: 'Accuracy', notJudged: '(unscored answers)', noRecords: 'No learning records', clearSel: 'Clear selection', less: 'Less', more: 'More' }
  }
})
import TikuIcon from './TikuIcon.vue'

const props = defineProps({
  // [{ date: 'yyyy-MM-dd', count, decided, correct }] 最近 365 天（含今天）
  daily: { type: Array, default: () => [] }
})

const view = ref('90')
const selDate = ref(null)
//切换视图时清掉可能已不在视图内的选中日
watch(view, () => {
  selDate.value = null
})
const todayStr = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const statByDate = computed(() => {
  const m = new Map()
  for (const s of props.daily) m.set(s.date, s)
  return m
})

const DAY = 86400000
function dateStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/* 视图配置：30 天 5 周列（大格）/ 90 天 13 周列（中格）/ 全年 53 周列（小格，横向滚动） */
const cfg = computed(() => {
  if (view.value === '30') return { days: 30, cols: 5, size: 26, gap: 4 }
  if (view.value === 'year') return { days: 365, cols: 53, size: 9, gap: 3 }
  return { days: 90, cols: 13, size: 22, gap: 4 }
})
const weekCols = computed(() => cfg.value.cols)
const cellSize = computed(() => cfg.value.size)
const cellGap = computed(() => cfg.value.gap)

/* 生成格子：按周列（列=周，行=周一..周日），从区间首日对齐到所在周的周一 */
const cells = computed(() => {
  const { days, gap } = cfg.value
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const start = new Date(today.getTime() - (days - 1) * DAY)
  //对齐到 start 所在周的周一（周一为一周之始）
  const dow = (start.getDay() + 6) % 7 // 周一=0
  const gridStart = new Date(start.getTime() - dow * DAY)
  const out = []
  const colCount = Math.ceil((days + dow) / 7)
  for (let c = 0; c < colCount; c++) {
    for (let r = 0; r < 7; r++) {
      const d = new Date(gridStart.getTime() + (c * 7 + r) * DAY)
      const ds = dateStr(d)
      if (d < start || d > today) continue
      const s = statByDate.value.get(ds)
      out.push({ date: ds, count: s ? s.count : 0, level: levelOf(s ? s.count : 0) })
    }
  }
  return out
})

function levelOf(count) {
  if (count <= 0) return 0
  if (count <= 4) return 1
  if (count <= 9) return 2
  if (count <= 19) return 3
  if (count <= 34) return 4
  return 5
}

const selStat = computed(() => (selDate.value ? statByDate.value.get(selDate.value) : null))

function cellTitle(c) {
  const s = statByDate.value.get(c.date)
  if (!s || s.count === 0) return `${c.date}：未学习`
  let t = `${c.date}：${s.count} 题`
  if (s.decided) t += `，正确率 ${pct(s.correct, s.decided)}`
  return t
}

function pct(a, b) {
  if (!b) return '—'
  return `${Math.round((a / b) * 100)}%`
}
</script>

<style scoped>
.heat-wrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.heat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.heat-title {
  font-size: 12px;
}
.heat-toggle {
  display: flex;
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}
.heat-tab {
  border: none;
  background: transparent;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.heat-tab.on {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
}
.heat-scroll {
  overflow-x: auto;
  padding-bottom: 2px;
}
.heat-grid {
  display: grid;
  grid-auto-flow: column;
  grid-template-rows: repeat(7, 1fr);
  width: max-content;
  /* 列总宽不足容器时列组居中（左右对称留白，视觉整齐） */
  margin: 0 auto;
}
.heat-year {
  margin: 0; /* 全年列超宽：靠左并横向滚动 */
}
.heat-cell {
  width: var(--hc, 14px);
  height: var(--hc, 14px);
  border: none;
  border-radius: 3px;
  background: var(--bg-elev);
  border: 1px solid transparent;
  padding: 0;
  cursor: pointer;
}
.heat-year .heat-cell {
  --hc: 9px;
}
/* 色阶：无色→浅绿→深绿（color-mix 随主题 success 自适应） */
.heat-l0 {
  background: var(--bg-elev);
}
.heat-l1 {
  background: color-mix(in srgb, var(--success) 18%, transparent);
}
.heat-l2 {
  background: color-mix(in srgb, var(--success) 36%, transparent);
}
.heat-l3 {
  background: color-mix(in srgb, var(--success) 55%, transparent);
}
.heat-l4 {
  background: color-mix(in srgb, var(--success) 75%, transparent);
}
.heat-l5 {
  background: var(--success);
}
.heat-cell.today {
  border-color: var(--accent);
}
.heat-cell.sel {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}
.heat-detail {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 8px;
  font-size: 13px;
}
.heat-date {
  font-weight: 600;
}
.heat-clear {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  border-radius: 4px;
  cursor: pointer;
}
.heat-clear:hover {
  background: var(--bg-hover);
}
.heat-legend {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}
.heat-legend .heat-cell {
  cursor: default;
  --hc: 12px;
  height: 12px;
  width: 12px;
}
</style>
