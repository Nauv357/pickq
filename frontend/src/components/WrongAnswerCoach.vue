<template>
  <div class="coach">
    <!-- 未开始：一个按钮 + 可选错因（不选也能讲，绝不挡路） -->
    <div v-if="!text && !busy" class="coach-trigger">
      <button class="btn btn-primary btn-sm" @click="explain()">
        <TikuIcon name="sparkle" :size="13" />
        {{ t('explainBtn') }}
      </button>
      <span class="text-muted coach-reason-label">{{ t('reasonLabel') }}</span>
      <button
        v-for="r in SELF_REASONS"
        :key="r.value"
        class="coach-chip"
        :class="{ on: reason === r.value }"
        @click="reason = reason === r.value ? '' : r.value"
      >
        {{ r.label }}
      </button>
      <span class="text-muted coach-tip">{{ t('tip') }}</span>
    </div>

    <div v-if="busy && !text" class="coach-loading">
      <span class="coach-spinner" />
      <span class="text-muted">{{ t('thinking') }}</span>
    </div>

    <div v-if="text" class="coach-body">
      <p v-if="lead" class="coach-lead" v-html="richHtml(lead)"></p>
      <!-- 三段结构：做完就先给"错在哪"，再给可复用做法，最后给一句能执行的检查 -->
      <section v-for="s in blocks" :key="s.key" class="coach-sec" :class="`sec-${s.key}`">
        <h4 class="coach-sec-title">
          <TikuIcon :name="s.icon" :size="13" />
          {{ s.title }}
        </h4>
        <div v-if="s.body" class="coach-sec-text" v-html="richHtml(s.body)"></div>
        <p v-else class="coach-sec-text text-muted">…</p>
      </section>
      <!-- 兜底：模型没按标题分段（极少数），至少别把内容吞掉 -->
      <div v-if="!blocks.length" class="coach-sec">
        <div class="coach-sec-text" v-html="richHtml(text)"></div>
      </div>
      <span v-if="busy" class="coach-cursor"></span>

      <!-- 追问：接着这场讲解问，不用另开面板 -->
      <div v-for="(f, i) in followUps" :key="`f${i}`" class="coach-follow" :class="f.role">
        <div class="coach-bubble" v-html="richHtml(f.content)"></div>
      </div>

      <p v-if="error" class="coach-error">{{ error }}</p>

      <div v-if="!busy" class="coach-foot">
        <input
          v-model="draft"
          class="coach-input"
          :placeholder="t('askPh')"
          @keydown.enter.exact.prevent="ask"
        />
        <button class="btn btn-secondary btn-sm" :disabled="!draft.trim()" @click="ask">{{ t('askBtn') }}</button>
        <button class="btn btn-secondary btn-sm" :disabled="saving || saved" @click="save">
          {{ saved ? t('saved') : saving ? t('saving') : t('saveBtn') }}
        </button>
        <button class="btn btn-secondary btn-sm" @click="explain(true)">{{ t('again') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 错题讲解（2026-09-16 产品收口后的主线）：把"这道题我错哪了"一次讲清楚。
 *
 * 为什么单独做一块而不是丢进聊天抽屉：
 * - 讲解是**一次性交付**，不该要求用户先学会怎么跟 AI 聊天；
 * - 三段结构（错在哪 / 这类题怎么做 / 下次防错）让长回答可扫读，而不是一坨文字；
 * - 错因三选是**可选**的（选了讲解更贴你的真实原因，不选也能直接讲）。
 *
 * 数据来源：后端 /api/tutor/explain（流式），它已经把「我的作答 / 我的错因 / 这道题错过几次 /
 * 这个知识点上的历史表现」拼进上下文——所以讲的不是通用解析，而是"你这次为什么错"。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import TikuIcon from './TikuIcon.vue'
import { SELF_REASONS, streamTutor } from '../api/tutor'
import { saveQuestionAnalysis } from '../api/questions'
import { richTextToHtml } from '../utils/richText'

const props = defineProps({
  bankId: { type: [Number, String], required: true },
  questionId: { type: [Number, String], required: true },
  practiceSessionId: { type: [Number, String], default: null },
  templateId: { type: String, default: '' },
  /** 展开时就自动讲（例如复盘里直接点开这道错题） */
  autoStart: { type: Boolean, default: false }
})

const emit = defineEmits(['saved'])

const { t } = useI18n({
  messages: {
    'zh-CN': {
      explainBtn: '讲给我听',
      reasonLabel: '为什么错？',
      tip: '（可不选）：选了会按你的真实原因讲，不是通用解析。',
      thinking: '老师正在看你的作答…（约 10~40 秒）',
      headWhere: '错在哪',
      headHow: '这类题怎么做',
      headGuard: '下次防错',
      askPh: '还有哪里不明白？',
      askBtn: '追问',
      saveBtn: '存为解析',
      saved: '已存为解析',
      saving: '保存中…',
      again: '重新讲',
      savedToast: '已存为本题解析，下次打开就能看到',
      errNoAi: '尚未配置 AI 模型：请在「设置 → AI」里填好 Key 再回来。',
      errEmpty: 'AI 没有返回内容，可再点一次「重新讲」'
    },
    'en-US': {
      explainBtn: 'Explain it to me',
      reasonLabel: 'Why wrong?',
      tip: '(optional): pick a reason and the explanation follows your actual mistake.',
      thinking: 'The tutor is reading your answer… (about 10–40 s)',
      headWhere: 'Where you went wrong',
      headHow: 'How to handle this type',
      headGuard: 'Avoid it next time',
      askPh: 'Anything still unclear?',
      askBtn: 'Ask',
      saveBtn: 'Save as analysis',
      saved: 'Saved',
      saving: 'Saving…',
      again: 'Explain again',
      savedToast: 'Saved as this question’s analysis — it will show up next time',
      errNoAi: 'AI is not configured yet: set your key in Settings → AI.',
      errEmpty: 'The AI returned nothing — click “Explain again”.'
    }
  }
})

const HEADS = [
  { key: 'where', marker: '【错在哪】', titleKey: 'headWhere', icon: 'target' },
  { key: 'how', marker: '【这类题怎么做】', titleKey: 'headHow', icon: 'list' },
  { key: 'guard', marker: '【下次防错】', titleKey: 'headGuard', icon: 'bell' }
]

const text = ref('')
const busy = ref(false)
const error = ref('')
const reason = ref('')
const sessionId = ref(null)
const draft = ref('')
const followUps = ref([])
const saving = ref(false)
const saved = ref(false)
const everStarted = ref(false)

const richHtml = (s) => richTextToHtml(s || '', props.bankId)

/**
 * 把流式文本切成三段。模型被要求原样输出三个小标题，所以按标记切；
 * 标题之前若有内容（例如一句开场）放在最后当引子，不丢内容。
 */
const parsed = computed(() => {
  const src = text.value
  const hits = HEADS
    .map((h) => ({ ...h, at: src.indexOf(h.marker) }))
    .filter((h) => h.at >= 0)
    .sort((a, b) => a.at - b.at)
  if (!hits.length) {
    return { lead: '', blocks: [] }
  }
  const lead = hits[0].at > 0 ? src.slice(0, hits[0].at).trim() : ''
  const blocks = hits.map((cur, i) => {
    const end = i + 1 < hits.length ? hits[i + 1].at : src.length
    return {
      key: cur.key,
      icon: cur.icon,
      title: t(cur.titleKey),
      body: src.slice(cur.at + cur.marker.length, end).trim()
    }
  })
  return { lead, blocks }
})
const blocks = computed(() => parsed.value.blocks)
const lead = computed(() => parsed.value.lead)

watch(
  () => props.questionId,
  () => reset()
)

watch(
  () => props.autoStart,
  (on) => {
    if (on && !everStarted.value) explain()
  },
  { immediate: true }
)

function reset() {
  text.value = ''
  error.value = ''
  reason.value = ''
  followUps.value = []
  draft.value = ''
  saved.value = false
  everStarted.value = false
}

async function explain(force = false) {
  if (busy.value || !props.questionId) return
  if (force) {
    text.value = ''
    followUps.value = []
    saved.value = false
  }
  everStarted.value = true
  busy.value = true
  error.value = ''
  await streamTutor('/tutor/explain', body(), {
    onSession: (payload) => {
      sessionId.value = payload?.sessionId ?? sessionId.value
    },
    onDelta: (delta) => {
      text.value += delta
    },
    onError: (message) => {
      error.value = /尚未配置 AI/.test(message) ? t('errNoAi') : message
    }
  })
  if (!text.value.trim()) error.value = error.value || t('errEmpty')
  busy.value = false
}

async function ask() {
  const q = draft.value.trim()
  if (!q || busy.value) return
  followUps.value.push({ role: 'user', content: q })
  draft.value = ''
  busy.value = true
  error.value = ''
  let reply = ''
  const idx = followUps.value.length
  followUps.value.push({ role: 'assistant', content: '' })
  await streamTutor('/tutor/ask', { ...body(), text: q }, {
    onSession: (payload) => {
      sessionId.value = payload?.sessionId ?? sessionId.value
    },
    onDelta: (delta) => {
      reply += delta
      followUps.value[idx].content = reply
    },
    onError: (message) => {
      error.value = /尚未配置 AI/.test(message) ? t('errNoAi') : message
    }
  })
  if (!reply.trim()) followUps.value.splice(idx, 1)
  busy.value = false
}

/** 存为本题解析：讲解变成题库的一部分，下次打开就在（不再是"聊过就没了"） */
async function save() {
  if (saving.value || saved.value || !text.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    await saveQuestionAnalysis(props.questionId, text.value)
    saved.value = true
    ElMessage.success(t('savedToast'))
    emit('saved', text.value)
  } catch (e) {
    error.value = e?.message || t('saveBtn')
  } finally {
    saving.value = false
  }
}

function body() {
  return {
    bankId: Number(props.bankId),
    questionId: Number(props.questionId),
    practiceSessionId: props.practiceSessionId ? Number(props.practiceSessionId) : null,
    templateId: props.templateId || null,
    kind: 'PER_QUESTION',
    selfReason: reason.value || null
  }
}

defineExpose({ explain })
</script>

<style scoped>
.coach {
  margin-top: 8px;
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.coach-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.coach-reason-label {
  font-size: 12px;
  margin-left: 4px;
}
.coach-tip {
  font-size: 12px;
  width: 100%;
}
.coach-chip {
  border: 1px solid var(--border);
  background: var(--bg-card);
  border-radius: 12px;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.coach-chip.on {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.coach-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.coach-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: coach-rot 0.8s linear infinite;
  display: inline-block;
}
@keyframes coach-rot {
  to {
    transform: rotate(360deg);
  }
}
.coach-body {
  margin-top: 4px;
}
.coach-sec {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
}
.coach-sec-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
}
.sec-where .coach-sec-title {
  color: var(--danger);
}
.sec-how .coach-sec-title {
  color: var(--accent-text);
}
.sec-guard .coach-sec-title {
  color: var(--success);
}
.coach-sec-text {
  font-size: 13px;
  line-height: 1.8;
  word-break: break-word;
}
.coach-lead {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.7;
}
.coach-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: var(--accent);
  animation: coach-blink 1s step-end infinite;
}
@keyframes coach-blink {
  50% {
    opacity: 0;
  }
}
.coach-follow {
  display: flex;
  margin: 6px 0;
}
.coach-follow.user {
  justify-content: flex-end;
}
.coach-bubble {
  max-width: 92%;
  border-radius: 10px;
  padding: 6px 10px;
  font-size: 13px;
  line-height: 1.8;
  background: var(--bg-elev);
  word-break: break-word;
}
.coach-follow.user .coach-bubble {
  background: var(--accent-soft);
}
.coach-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.coach-input {
  flex: 1;
  min-width: 160px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 5px 8px;
  font-size: 13px;
  font-family: inherit;
  background: var(--bg-card);
  color: var(--text-primary);
}
.coach-error {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--danger);
}
</style>
