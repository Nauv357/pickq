<template>
  <div
    ref="rootEl"
    class="qnav-dock"
    :class="{ dragging, 'accent-fill': activeFill }"
    :style="dockStyle"
  >
    <div
      class="qnav-head"
      :class="{ grabbable: draggable }"
      ::title="draggable ? t('dragTip') : undefined"
      @pointerdown="onHeadDown"
    >
      <span class="qnav-title">
        <TikuIcon name="list" :size="13" />
        {{ title }}
      </span>
      <span class="qnav-count text-muted">{{ items.length }} {{ t('qUnit') }}</span>
      <span class="bar-grow"></span>
      <div class="qnav-tools" @pointerdown.stop>
        <button
          class="qnav-tool"
          :class="{ on: goOpen }"
          ::title="goOpen ? t('collapseJump') : t('openJump')"
          @click="toggleGo"
        >
          <TikuIcon name="search" :size="12" />
        </button>
        <button
          class="qnav-tool qnav-size"
          :title="t('sizeTip') + '：' + sizeLabel + '（' + t('clickToggle') + '）'"
          @click="cycleSize"
        >Aa</button>
      </div>
    </div>
    <el-input
      v-if="goOpen"
      ref="goInputEl"
      v-model="goText"
      size="small"
      :placeholder="t('jumpPh')"
      clearable
      style="width: 100%"
      @keyup.enter="goByNumber"
      @keyup.esc="goOpen = false"
    >
      <template #prefix><TikuIcon name="search" :size="12" /></template>
    </el-input>
    <div class="qnav-grid">
      <button
        v-for="it in items"
        :key="it.questionId"
        class="qnav-num mono"
        :class="[`st-${it.status || 'plain'}`, { active: it.questionId === activeId }]"
        :title="t('jumpTo') + ' ' + (it.questionNumber ?? '—')"
        @click="$emit('select', it.questionId)"
      >{{ it.questionNumber ?? '·' }}</button>
    </div>
    <div v-if="showLegend" class="qnav-legend">
      <span><i class="lg st-ok"></i>{{ t('stOk') }}</span>
      <span><i class="lg st-no"></i>{{ t('stNo') }}</span>
      <span v-if="hasPartial"><i class="lg st-partial"></i>{{ t('stPartial') }}</span>
      <span><i class="lg st-skip"></i>{{ t('stSkip') }}</span>
    </div>
    <p v-if="hint" class="qnav-hint text-muted">{{ hint }}</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': { dragTip: '按住空白处可拖动题号盘位置', qUnit: '题', collapseJump: '收起跳转框', openJump: '打开输入题号直达跳转', sizeTip: '题号盘大小', jumpPh: '输入题号回车直达', jumpTo: '跳转第', warnNumber: '请输入有效的题号', noNumberIn: '当前 {total} 题中没有第 {n} 题', stOk: '对', stNo: '错', stPartial: '部分', stSkip: '未答' },
    'en-US': { dragTip: 'Drag the blank area to move the number dock', qUnit: 'q', collapseJump: 'Collapse jump box', openJump: 'Jump by question number', sizeTip: 'Dock size', jumpPh: 'Type a number and press Enter', jumpTo: 'Jump to #', warnNumber: 'Enter a valid question number', noNumberIn: 'Question {n} is not in the current {total} questions', stOk: 'Right', stNo: 'Wrong', stPartial: 'Partial', stSkip: 'Skipped' }
  }
})
import { ElMessage } from 'element-plus'
import TikuIcon from './TikuIcon.vue'

const props = defineProps({
  title: { type: String, default: '题号' },
  // { questionId, questionNumber, status?: 'ok' | 'no' | 'partial' | 'skip' }
  items: { type: Array, default: () => [] },
  activeId: { type: Number, default: null },
  showLegend: { type: Boolean, default: false },
  hint: { type: String, default: '' },
  //是否可按住头部拖动位置（fixed 悬浮场景；抽屉内嵌场景传 false）
  draggable: { type: Boolean, default: false },
  //位置/尺寸持久化键（同页面的 wide/pop 形态共用同一键）
  storageKey: { type: String, default: '' },
  //选中题高亮方式：true = accent 实底白字（无状态色场景：编辑/预览）；
  //false = 主题色环（回顾页需保留对错状态底色）
  activeFill: { type: Boolean, default: false }
})
const emit = defineEmits(['select'])

const rootEl = ref(null)
const goText = ref('')
const goOpen = ref(false)
const goInputEl = ref(null)
const hasPartial = computed(() => props.items.some((i) => i.status === 'partial'))

/* ============ 尺寸三档（24 紧凑 / 28 标准 / 34 大）持久化 ============ */
const SIZES = [24, 28, 34]
const sizeKey = () => `tiku:dock-size:${props.storageKey || 'default'}`
let sizeIdx = 1
try {
  const saved = Number(localStorage.getItem(sizeKey()))
  const found = SIZES.indexOf(saved)
  if (found >= 0) sizeIdx = found
} catch (e) {
  /* 忽略 */
}
const numSize = ref(SIZES[sizeIdx])
const sizeLabel = computed(() => (numSize.value === 24 ? '紧凑' : numSize.value === 34 ? '大' : '标准'))
function cycleSize() {
  numSize.value = SIZES[(SIZES.indexOf(numSize.value) + 1) % SIZES.length]
  try {
    localStorage.setItem(sizeKey(), String(numSize.value))
  } catch (e) {
    /* 忽略 */
  }
}

/* ============ 拖动（fixed 悬浮场景）：pointer 事件 + 视口坐标 clamp + 持久化 ============ */
const posKey = () => `tiku:dock-pos:${props.storageKey || 'default'}`
const dockStyle = ref({})
//初始化与切换时都把尺寸档写入 CSS 变量 --ns（圆钮/网格列宽跟随）
dockStyle.value['--ns'] = numSize.value + 'px'
watch(numSize, (v) => {
  dockStyle.value['--ns'] = v + 'px'
})
const dragging = ref(false)
let dragState = null

function clampPos(left, top) {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const w = rootEl.value?.offsetWidth || 188
  const h = rootEl.value?.offsetHeight || 200
  return {
    left: Math.min(Math.max(8, left), Math.max(8, vw - w - 8)),
    top: Math.min(Math.max(8, top), Math.max(8, vh - h - 8))
  }
}

function applyStoredPos() {
  if (!props.draggable || !props.storageKey) return
  try {
    const raw = localStorage.getItem(posKey())
    if (!raw) return
    const p = JSON.parse(raw)
    if (typeof p.left === 'number' && typeof p.top === 'number') {
      const c = clampPos(p.left, p.top)
      dockStyle.value.left = c.left + 'px'
      dockStyle.value.top = c.top + 'px'
      dockStyle.value.right = 'auto'
    }
  } catch (e) {
    /* 坏数据忽略 */
  }
}

function onHeadDown(e) {
  if (!props.draggable || !props.storageKey) return
  //按钮/输入框上不触发拖动
  if (e.target.closest('button, .el-input, input')) return
  if (e.button !== 0) return
  const el = rootEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  dragState = { px: e.clientX, py: e.clientY, left: rect.left, top: rect.top }
  dragging.value = true
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

function onMove(e) {
  if (!dragState) return
  const c = clampPos(dragState.left + (e.clientX - dragState.px), dragState.top + (e.clientY - dragState.py))
  dockStyle.value.left = c.left + 'px'
  dockStyle.value.top = c.top + 'px'
  dockStyle.value.right = 'auto'
}

function onUp() {
  if (!dragState) return
  dragState = null
  dragging.value = false
  document.body.style.userSelect = ''
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  try {
    const raw = dockStyle.value.left && dockStyle.value.top
      ? JSON.stringify({ left: parseFloat(dockStyle.value.left), top: parseFloat(dockStyle.value.top) })
      : null
    if (raw) localStorage.setItem(posKey(), raw)
  } catch (e) {
    /* 忽略 */
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
})

/* 输入题号回车：按题号命中并触发跳转 */
function goByNumber() {
  const n = Number(goText.value)
  if (!Number.isInteger(n) || n < 1) {
    ElMessage.warning(t('warnNumber'))
    return
  }
  const hit = props.items.find((x) => x.questionNumber === n)
  if (!hit) {
    ElMessage.info(t('noNumberIn', { total: props.items.length, n }))
    return
  }
  goText.value = ''
  emit('select', hit.questionId)
  //命中后收起跳转框，避免遮挡题号盘
  goOpen.value = false
}

function toggleGo() {
  goOpen.value = !goOpen.value
  if (goOpen.value) {
    nextTick(() => goInputEl.value?.focus?.())
  }
}

applyStoredPos()
</script>

<style scoped>
.qnav-dock {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08);
  /* 尺寸档：--ns 作用于圆钮边长与网格最小列宽（页面无需感知） */
  --ns: 28px;
}
.qnav-dock.dragging {
  cursor: grabbing;
  opacity: 0.94;
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.16);
}
.qnav-head {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.qnav-head.grabbable {
  cursor: grab;
  border-radius: 6px;
}
.qnav-head.grabbable:active {
  cursor: grabbing;
}
.qnav-title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.qnav-count {
  font-size: 11px;
  flex-shrink: 0;
}
.bar-grow {
  flex: 1;
  min-width: 0;
}
.qnav-tools {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.qnav-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
  transition: all var(--ease);
}
.qnav-tool:hover,
.qnav-tool.on {
  background: var(--accent-soft);
  color: var(--accent-text);
}
/* 圆形题号盘（粉笔答题卡式）：可滚动；列宽/圆钮尺寸跟随 --ns */
.qnav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(calc(var(--ns) * 0.92), 1fr));
  gap: 5px;
  max-height: calc(100vh - 260px);
  overflow-y: auto;
  padding-right: 2px;
}
.qnav-num {
  width: var(--ns);
  height: var(--ns);
  justify-self: center;
  border-radius: 50%;
  border: 1px solid var(--border-strong);
  background: var(--bg-elev);
  color: var(--text-secondary);
  font-size: calc(var(--ns) * 0.46);
  cursor: pointer;
  transition: all var(--ease);
  padding: 0;
  line-height: 1;
}
/* 点击不产生浏览器默认黑框；键盘聚焦用主题色环 */
.qnav-num:focus {
  outline: none;
}
.qnav-num:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}
.qnav-num:hover {
  border-color: var(--accent);
  color: var(--accent-text);
}
/* 答题状态色（回顾场景）：绿对 / 红错 / 黄部分 / 灰未答 */
.qnav-num.st-ok {
  background: var(--success-soft);
  color: var(--success);
  border-color: var(--success);
}
.qnav-num.st-no {
  background: var(--danger-soft);
  color: var(--danger);
  border-color: var(--danger);
}
.qnav-num.st-partial {
  background: var(--warning-soft);
  color: var(--warning);
  border-color: var(--warning);
}
.qnav-num.st-skip {
  color: var(--text-muted);
  border-style: dashed;
}
/* 当前所在题高亮：主题色双环（不占布局、不遮文字与状态底色，
   无黑框/无大外扩 outline） */
.qnav-num.active {
  outline: none;
  border-color: var(--accent);
  color: var(--accent-text);
  font-weight: 700;
  box-shadow:
    0 0 0 2px var(--bg-card),
    0 0 0 3px var(--accent);
}
/* activeFill 场景（编辑/预览题号盘）：选中题直接 accent 实底白字，视觉最清晰 */
.qnav-dock.accent-fill .qnav-num.active {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
  box-shadow: 0 2px 8px color-mix(in srgb, var(--accent) 45%, transparent);
}
.qnav-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 11px;
  color: var(--text-muted);
  padding-top: 2px;
}
.qnav-legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.qnav-legend .lg {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 1px solid transparent;
}
.qnav-legend .lg.st-ok {
  background: var(--success-soft);
  border-color: var(--success);
}
.qnav-legend .lg.st-no {
  background: var(--danger-soft);
  border-color: var(--danger);
}
.qnav-legend .lg.st-partial {
  background: var(--warning-soft);
  border-color: var(--warning);
}
.qnav-legend .lg.st-skip {
  background: var(--bg-elev);
  border-color: var(--border-strong);
  border-style: dashed;
}
.qnav-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
