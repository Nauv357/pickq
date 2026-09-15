<template>
  <div class="tutor">
    <div class="tutor-head">
      <span class="tutor-title">
        <TikuIcon name="sparkle" :size="14" />
        {{ kind === 'POST_REVIEW' ? t('reviewTitle') : t('tutorTitle') }}
      </span>
      <span v-if="hintLevel > 0 && kind !== 'POST_REVIEW'" class="tutor-level text-muted">
        {{ t('levelShown', { n: hintLevel }) }}
      </span>
      <button class="icon-btn" :title="t('close')" @click="$emit('close')">
        <TikuIcon name="x" :size="14" />
      </button>
    </div>

    <!-- 提示楼梯：做题中才有（复盘没有"这道题的提示"） -->
    <div v-if="kind !== 'POST_REVIEW'" class="tutor-ladder">
      <button
        v-for="lv in [1, 2, 3]"
        :key="lv"
        class="tutor-step"
        :class="{ done: hintLevel >= lv, next: hintLevel + 1 === lv }"
        :disabled="busy || hintLevel >= lv"
        @click="askHint(lv)"
      >
        {{ t('hintN', { n: lv, label: LEVEL_LABELS[lv] }) }}
      </button>
      <span class="text-muted tutor-ladder-tip">{{ t('ladderTip') }}</span>
    </div>

    <!-- 答错即问：快捷三选（复盘里点错题时出现） -->
    <div v-if="showReasons" class="tutor-reasons">
      <span class="text-muted">{{ t('whyWrong') }}</span>
      <button
        v-for="r in SELF_REASONS"
        :key="r.value"
        class="tutor-reason"
        :class="{ on: reason === r.value }"
        :disabled="busy"
        @click="pickReason(r.value)"
      >
        {{ r.label }}
      </button>
    </div>

    <div ref="scrollEl" class="tutor-body">
      <p v-if="!messages.length && !streaming" class="text-muted tutor-empty">{{ emptyTip }}</p>
      <div v-for="(m, i) in messages" :key="i" class="tutor-msg" :class="m.role">
        <div class="tutor-bubble" v-html="richHtml(m.content)"></div>
        <span v-if="m.hintLevel" class="tutor-tag">{{ t('hintTag', { n: m.hintLevel }) }}</span>
      </div>
      <div v-if="streaming" class="tutor-msg assistant">
        <div class="tutor-bubble" v-html="richHtml(streaming)"></div>
        <span class="tutor-cursor"></span>
      </div>
      <p v-if="error" class="tutor-error">{{ error }}</p>
    </div>

    <div class="tutor-foot">
      <textarea
        v-model="draft"
        class="tutor-input"
        rows="2"
        :placeholder="t('askPh')"
        :disabled="busy"
        @keydown.enter.exact.prevent="send"
      ></textarea>
      <button class="btn btn-primary btn-sm" :disabled="busy || !draft.trim()" @click="send">
        {{ busy ? t('generating') : t('send') }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import TikuIcon from './TikuIcon.vue'
import { SELF_REASONS, getTutorMessages, getTutorSessions, streamTutor } from '../api/tutor'
import { richTextToHtml } from '../utils/richText'

/**
 * AI 私教面板（做题中要提示 / 复盘里问"为什么错"）。
 *
 * 设计边界（见 docs/learning-path-design.md §7.4）：
 * - 只在这个面板里说话，做题过程不会自动弹窗；
 * - 提示分三级（指方向 → 关键一步 → 完整解析），已在后端落库，换设备/重进也能接着；
 * - 答错即问先让用户选错因（看错/不会/没见过），错因是比模型猜更准的第一手数据。
 */
const props = defineProps({
  bankId: { type: [Number, String], required: true },
  questionId: { type: [Number, String], default: null },
  practiceSessionId: { type: [Number, String], default: null },
  templateId: { type: String, default: '' },
  kind: { type: String, default: 'PER_QUESTION' },
  /** 会话 id（复盘由报告页先建好；不传则由第一次请求的 session 事件带回来） */
  sessionId: { type: [Number, String], default: null },
  /** 打开时就带上的错因（答错即问的三选） */
  selfReason: { type: String, default: '' },
  selfNote: { type: String, default: '' },
  /** 是否显示"为什么错"三选（复盘里点开某道错题时） */
  showReasons: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'session'])

const { t } = useI18n({
  messages: {
    zh: {
      tutorTitle: '问老师',
      reviewTitle: '这场复盘',
      close: '关闭',
      levelShown: '已给到第 {n} 级',
      hintN: '提示 {n}·{label}',
      hintTag: '第 {n} 级提示',
      ladderTip: '按需取用：先看方向，再要关键一步，实在不会再看完整解析。',
      whyWrong: '这题为什么错？',
      askPh: '还有哪里不明白？（Enter 发送，可留空直接让老师针对错因说一句）',
      send: '追问',
      generating: '正在写…',
      hintEmpty: '要不要先要一级提示？点上面的「提示 1」即可，不用直接看答案。',
      reviewEmpty: '点「生成复盘诊断」，我会把这场错题按知识点梳理一遍。',
      errNoAi: '尚未配置 AI 模型：请在「设置 → AI」里填好 Key 再回来。'
    },
    en: {
      tutorTitle: 'Ask the tutor',
      reviewTitle: 'Session review',
      close: 'Close',
      levelShown: 'hint level {n}',
      hintN: 'Hint {n}·{label}',
      hintTag: 'Hint level {n}',
      ladderTip: 'Take hints gradually: direction → key step → full solution.',
      whyWrong: 'Why was it wrong?',
      askPh: 'What is still unclear? (Enter to send; leave empty to let the tutor react to your reason)',
      send: 'Ask',
      generating: 'Writing…',
      hintEmpty: 'Want a hint first? Click “Hint 1” — no need to jump to the answer.',
      reviewEmpty: 'Click “Generate review” to go through this session’s mistakes by topic.',
      errNoAi: 'AI is not configured yet: set your key in Settings → AI.'
    }
  }
})

const LEVEL_LABELS = { 1: '指方向', 2: '关键一步', 3: '完整解析' }

const messages = ref([])
const streaming = ref('')
const busy = ref(false)
const error = ref('')
const draft = ref('')
const hintLevel = ref(0)
const sessionId = ref(props.sessionId)
const scrollEl = ref(null)

const emptyTip = computed(() => (props.kind === 'POST_REVIEW' ? t('reviewEmpty') : t('hintEmpty')))
const richHtml = (text) => richTextToHtml(text || '')

async function scrollToEnd() {
  await nextTick()
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 打开时把已有历史拉回来（提示用了几级、上次聊到哪） */
async function loadHistory() {
  if (!sessionId.value && props.questionId) {
    // 后端会复用"同题 + 同场练习"的会话，先找一下有没有历史
    try {
      const sessions = await getTutorSessions(props.questionId)
      const hit = (sessions || []).find((s) => String(s.practiceSessionId ?? '') === String(props.practiceSessionId ?? ''))
      if (hit) sessionId.value = hit.id
    } catch (e) {
      /* 没有历史也很正常 */
    }
  }
  if (!sessionId.value) return
  try {
    const data = await getTutorMessages(sessionId.value)
    messages.value = (data?.messages || []).map((m) => ({ role: m.role, content: m.content, hintLevel: m.hintLevel }))
    hintLevel.value = data?.maxHintLevel || 0
    await scrollToEnd()
  } catch (e) {
    /* 忽略：历史拉不到不影响重新提问 */
  }
}

onMounted(loadHistory)

watch(
  () => props.questionId,
  () => {
    messages.value = []
    streaming.value = ''
    hintLevel.value = 0
    sessionId.value = props.sessionId
    loadHistory()
  }
)

function pickReason(value) {
  reason.value = reason.value === value ? '' : value
}
const reason = ref(props.selfReason)

function baseBody() {
  return {
    bankId: Number(props.bankId),
    questionId: props.questionId ? Number(props.questionId) : null,
    practiceSessionId: props.practiceSessionId ? Number(props.practiceSessionId) : null,
    templateId: props.templateId || null,
    kind: props.kind,
    selfReason: reason.value || null,
    selfNote: props.selfNote || null
  }
}

/** 统一跑一次流式生成：把增量接到界面上，完成后把助手消息收进列表 */
async function run(path, extra) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  streaming.value = ''
  const result = await streamTutor(path, { ...baseBody(), ...extra }, {
    onSession: (payload) => {
      sessionId.value = payload?.sessionId
      if (payload?.maxHintLevel != null) hintLevel.value = payload.maxHintLevel
      emit('session', payload)
    },
    onDelta: (text) => {
      streaming.value += text
      scrollToEnd()
    },
    onDone: (payload) => {
      if (payload?.maxHintLevel != null) hintLevel.value = payload.maxHintLevel
    },
    onError: (message) => {
      error.value = /尚未配置 AI/.test(message) ? t('errNoAi') : message
    }
  })
  if (streaming.value.trim()) {
    messages.value.push({ role: 'assistant', content: streaming.value, hintLevel: path === '/tutor/hint' ? extra.level : null })
  }
  streaming.value = ''
  busy.value = false
  await scrollToEnd()
  return result
}

const askHint = (level) => run('/tutor/hint', { level })

async function send() {
  const text = draft.value.trim()
  if (!text && !reason.value) return
  if (text) {
    messages.value.push({ role: 'user', content: text })
    await scrollToEnd()
  } else if (reason.value) {
    const label = SELF_REASONS.find((r) => r.value === reason.value)?.label || ''
    messages.value.push({ role: 'user', content: `（${label}）` })
    await scrollToEnd()
  }
  draft.value = ''
  await run('/tutor/ask', { text })
}

/** 复盘：不需要额外输入，直接让后端按本场统计生成诊断 */
const diagnose = () => run('/tutor/review', {})

defineExpose({ diagnose, run })
</script>

<style scoped>
.tutor {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.tutor-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}
.tutor-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 14px;
}
.tutor-level {
  font-size: 12px;
}
.tutor-head .icon-btn {
  margin-left: auto;
}
.tutor-ladder,
.tutor-reasons {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.tutor-ladder-tip {
  font-size: 12px;
  width: 100%;
}
.tutor-step,
.tutor-reason {
  border: 1px solid var(--border);
  background: var(--bg-card);
  border-radius: 12px;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.tutor-step.next {
  border-color: var(--accent);
  color: var(--accent-text);
}
.tutor-step.done {
  color: var(--success);
  border-color: var(--success);
}
.tutor-step:disabled,
.tutor-reason:disabled {
  opacity: 0.6;
  cursor: default;
}
.tutor-reason.on {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.tutor-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px;
}
.tutor-empty {
  font-size: 13px;
  line-height: 1.8;
}
.tutor-msg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}
.tutor-msg.user {
  align-items: flex-end;
}
.tutor-bubble {
  max-width: 92%;
  border-radius: 10px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.8;
  background: var(--bg-elev);
  word-break: break-word;
}
.tutor-msg.user .tutor-bubble {
  background: var(--accent-soft);
}
.tutor-tag {
  font-size: 11px;
  color: var(--text-muted);
}
.tutor-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: var(--accent);
  animation: tutor-blink 1s step-end infinite;
}
@keyframes tutor-blink {
  50% {
    opacity: 0;
  }
}
.tutor-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger);
}
.tutor-foot {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--border);
}
.tutor-input {
  flex: 1;
  resize: none;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 8px;
  font-size: 13px;
  font-family: inherit;
  background: var(--bg-card);
  color: var(--text-primary);
}
</style>
