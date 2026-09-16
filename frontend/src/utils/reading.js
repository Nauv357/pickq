/**
 * 阅读字号（2026-09-16 用户反馈："我们的主要内容字体是否太小？可否让用户自行调整？"）。
 *
 * 只调**正文类**内容：题目题干、选项、解析、笔记正文、回顾卡里的作答与答案，
 * 不动标题、标签、按钮、页码等界面元素——否则放大字号会让界面结构散架。
 *
 * 实现只有一处：这个模块把档位写进 `<html>` 上的 CSS 变量 `--content-font` / `--content-lh`，
 * 所有"正文类"样式引用这两个变量；设置页与笔记页的 A−/A+ 都调这里（同一个真相）。
 */
import { computed, ref } from 'vue'

const KEY = 'tiku.readingScale'

/** 四档：标准即默认（14px 时代偏小，这里整体上调一档） */
export const READING_SCALES = [
  { key: 's', px: 14, lh: 1.75, label: '小', labelEn: 'Small' },
  { key: 'm', px: 15.5, lh: 1.8, label: '标准', labelEn: 'Standard' },
  { key: 'l', px: 17, lh: 1.85, label: '大', labelEn: 'Large' },
  { key: 'xl', px: 19, lh: 1.9, label: '特大', labelEn: 'Extra large' }
]

const DEFAULT_KEY = 'm'
const current = ref(DEFAULT_KEY)

const scaleOf = (key) => READING_SCALES.find((s) => s.key === key) || READING_SCALES.find((s) => s.key === DEFAULT_KEY)

/** 把当前档位写到 :root（CSS 变量），页面里所有正文类样式都引用它 */
export function applyReadingScale() {
  const scale = scaleOf(current.value)
  const root = document.documentElement
  root.style.setProperty('--content-font', `${scale.px}px`)
  root.style.setProperty('--content-lh', String(scale.lh))
}

export function setReadingScale(key) {
  current.value = READING_SCALES.some((s) => s.key === key) ? key : DEFAULT_KEY
  try {
    localStorage.setItem(KEY, current.value)
  } catch (e) {
    /* 隐私模式下写不了也不影响本次使用 */
  }
  applyReadingScale()
}

/** 启动时调用：读回上次的档位 */
export function initReadingScale() {
  let saved = null
  try {
    saved = localStorage.getItem(KEY)
  } catch (e) {
    saved = null
  }
  current.value = READING_SCALES.some((s) => s.key === saved) ? saved : DEFAULT_KEY
  applyReadingScale()
}

/** 当前档位（响应式）与"还能不能更大/更小" */
export const readingScale = computed(() => current.value)
export const readingScaleIndex = computed(() => READING_SCALES.findIndex((s) => s.key === current.value))
export const canGrowReading = computed(() => readingScaleIndex.value < READING_SCALES.length - 1)
export const canShrinkReading = computed(() => readingScaleIndex.value > 0)

/** A−/A+ 的快捷调整（到边界就停住，不循环） */
export function stepReadingScale(delta) {
  const next = READING_SCALES[readingScaleIndex.value + delta]
  if (next) setReadingScale(next.key)
}
