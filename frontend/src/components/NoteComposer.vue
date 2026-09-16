<template>
  <div class="nc" :class="{ compact }">
    <textarea
      ref="box"
      class="nc-input"
      :value="modelValue"
      rows="1"
      :placeholder="placeholder"
      @input="onInput"
      @keydown.enter.exact.prevent="onEnter"
      @keydown.esc="onEsc"
      @select="syncSelection"
      @keyup="syncSelection"
      @click="syncSelection"
    ></textarea>

    <!-- 纸面上的"圈画"：选中文字 → 点颜色/B/U。标记写进正文（==文字== / **文字** / __文字__），
         存进数据库的还是纯文本，搜索、导出、迁移都不受影响。 -->
    <div class="nc-tools">
      <span class="text-muted nc-tools-label">{{ t('mark') }}</span>
      <button
        v-for="c in MARK_COLORS"
        :key="c.key"
        class="nc-dot"
        :class="`dot-${c.key}`"
        :title="t('markTitle', { c: isEn ? c.labelEn : c.label })"
        :disabled="saving"
        @mousedown.prevent="applyMark('hl', c.key)"
      ></button>
      <button class="nc-tool" :title="t('boldTitle')" :disabled="saving" @mousedown.prevent="applyMark('b')">
        <b>B</b>
      </button>
      <button class="nc-tool" :title="t('ulTitle')" :disabled="saving" @mousedown.prevent="applyMark('u')">
        <u>U</u>
      </button>
      <button class="nc-tool" :title="t('clearTitle')" :disabled="saving" @mousedown.prevent="clearMarks">
        {{ t('clear') }}
      </button>
    </div>

    <!-- 有标记/公式/图片时才占地方：给一块"效果预览"，写的时候就能看到最终样子 -->
    <div v-if="showPreview" class="nc-preview">
      <span class="text-muted nc-preview-label">{{ t('preview') }}</span>
      <div class="nc-preview-body" v-html="previewHtml"></div>
    </div>

    <slot name="footer"></slot>
  </div>
</template>

<script setup>
/**
 * 笔记编辑框（写一条 / 就地改一条都用它）。
 *
 * 为什么要有这个组件：笔记的输入框在三个地方出现（笔记页随手记、笔记页就地编辑、
 * 做题页每题「记一笔」），输入框跟着内容长高、标记工具条、"效果预览"这些行为
 * 只能有一份实现，否则三处迟早长歪（见 docs/conventions.md §1.0）。
 *
 * 组件自己不持有正文：正文归调用方（v-model），这里只负责"把标记套到选区上"并回报新文本。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { richTextToHtml } from '../utils/richText'
import { MARK_COLORS, hasMarks, markBold, markHighlight, markUnderline, stripMarks } from '../utils/noteMarkup'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  /** 预览里的图片要按题库取（笔记挂的题库），没有就只渲染公式与标记 */
  bankId: { type: [Number, String], default: null },
  saving: { type: Boolean, default: false },
  /** 就地编辑时更紧凑（少一点留白） */
  compact: { type: Boolean, default: false },
  /** 就地编辑才需要「取消」 */
  cancelable: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'save', 'cancel'])

const { t, locale } = useI18n({
  messages: {
    'zh-CN': {
      mark: '标记',
      markTitle: '{c}色高亮（选中文字再点）',
      boldTitle: '加粗（选中文字再点）',
      ulTitle: '下划线（选中文字再点）',
      clearTitle: '清除整条笔记里的标记（内容保留）',
      clear: '清除标记',
      preview: '效果预览',
      cancel: '取消'
    },
    'en-US': {
      mark: 'Mark',
      markTitle: '{c} highlight (select text first)',
      boldTitle: 'Bold (select text first)',
      ulTitle: 'Underline (select text first)',
      clearTitle: 'Clear all marks in this note (text stays)',
      clear: 'Clear marks',
      preview: 'Preview',
      cancel: 'Cancel'
    }
  }
})

const isEn = computed(() => String(locale.value).startsWith('en'))
const box = ref(null)
/** 最近一次选区（点工具条按钮会先 mousedown.prevent，所以选区不会丢） */
const sel = ref({ start: 0, end: 0 })
/** 套完标记后要把光标放回哪里（等父级回填文本后再设） */
let pendingCaret = null

const previewHtml = computed(() => richTextToHtml(props.modelValue || '', props.bankId))
const showPreview = computed(() => hasMarks(props.modelValue) || /\$[^$]+\$|\[图片:|<table[\s>]/i.test(props.modelValue || ''))

function onInput(e) {
  emit('update:modelValue', e.target.value)
  syncSelection(e)
  grow()
}

function onEnter() {
  if (!props.saving) emit('save')
}

function onEsc() {
  if (props.cancelable) emit('cancel')
}

function syncSelection(e) {
  const el = e?.target || box.value
  if (!el) return
  sel.value = { start: el.selectionStart ?? 0, end: el.selectionEnd ?? 0 }
}

/** 输入框跟着内容长高（超过 420px 才内部滚动） */
function grow() {
  const el = box.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 420)}px`
}

/** 套标记：把新文本回给父级，并把光标/选区放回原处（可以接着再套一层） */
function applyWith(fn) {
  const el = box.value
  const src = props.modelValue || ''
  const { start, end } = el ? { start: el.selectionStart ?? 0, end: el.selectionEnd ?? 0 } : sel.value
  const result = fn(src, start, end)
  if (result.text === src) return
  pendingCaret = [result.start, result.end]
  emit('update:modelValue', result.text)
}

function applyMark(kind, color) {
  if (kind === 'hl') applyWith((s, a, b) => markHighlight(s, a, b, color))
  else if (kind === 'b') applyWith(markBold)
  else applyWith(markUnderline)
}

function clearMarks() {
  const next = stripMarks(props.modelValue || '')
  if (next !== props.modelValue) emit('update:modelValue', next)
}

/** 父级回填文本后：恢复光标、重新测量高度 */
watch(
  () => props.modelValue,
  async () => {
    await nextTick()
    if (pendingCaret && box.value) {
      box.value.focus()
      box.value.setSelectionRange(pendingCaret[0], pendingCaret[1])
      syncSelection()
      pendingCaret = null
    }
    grow()
  }
)

onMounted(grow)
defineExpose({ focus: () => box.value?.focus(), grow })
</script>

<style scoped>
.nc-input {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: var(--content-font);
  font-family: inherit;
  line-height: var(--content-lh);
  background: var(--bg-elev);
  color: var(--text-primary);
  resize: none;
  overflow-y: auto;
}
.nc-input:focus {
  outline: none;
  border-color: var(--accent);
}
.nc-tools {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.nc-tools-label {
  font-size: 11.5px;
  margin-right: 2px;
}
/* 荧光笔色点：颜色与笔记标记色一致（--hl-*） */
.nc-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 1px solid var(--border-strong);
  cursor: pointer;
  padding: 0;
}
.nc-dot:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}
.dot-y { background: var(--hl-y); }
.dot-g { background: var(--hl-g); }
.dot-b { background: var(--hl-b); }
.dot-p { background: var(--hl-p); }
.nc-tool {
  height: 24px;
  min-width: 26px;
  padding: 0 7px;
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-secondary);
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.nc-tool:hover:not(:disabled) {
  border-color: var(--border-strong);
  color: var(--text-primary);
}
.nc-preview {
  margin-top: 8px;
  border-left: 2px solid var(--accent);
  padding: 4px 0 4px 9px;
  background: var(--bg-elev);
  border-radius: 0 8px 8px 0;
}
.nc-preview-label {
  display: block;
  font-size: 11px;
  margin-bottom: 2px;
}
.nc-preview-body {
  font-size: var(--content-font);
  line-height: var(--content-lh);
  white-space: pre-wrap;
  word-break: break-word;
}
.nc-preview-body :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 6px 0;
  display: block;
}
.compact .nc-tools {
  margin-top: 4px;
}
</style>
