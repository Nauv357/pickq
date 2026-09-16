<template>
  <div class="qx">
    <!-- 未开始：一个按钮（有作答的题可以先点错因，不选也能讲） -->
    <div v-if="!text && !busy" class="qx-trigger">
      <button class="btn btn-primary btn-sm" @click="explain()">
        <TikuIcon name="sparkle" :size="13" />
        {{ explainLabel }}
      </button>
      <template v-if="reasonChips">
        <span class="text-muted qx-reason-label">{{ t('reasonLabel') }}</span>
        <button
          v-for="r in SELF_REASONS"
          :key="r.value"
          class="qx-chip"
          :class="{ on: reason === r.value }"
          @click="reason = reason === r.value ? '' : r.value"
        >
          {{ r.label }}
        </button>
        <span class="text-muted qx-tip">{{ t('tip') }}</span>
      </template>
      <span v-else-if="lastAt" class="text-muted qx-tip">{{ t('lastAt', { d: lastAt }) }}</span>
    </div>

    <div v-if="busy && !text" class="qx-loading">
      <span class="qx-spinner" />
      <span class="text-muted">{{ t('thinking') }}</span>
    </div>

    <div v-if="text" class="qx-body">
      <!-- 回看：这次展示的是上次讲过的内容（不是刚生成的），给一句时间提示 -->
      <p v-if="fromHistory && !busy" class="qx-history text-muted">
        <TikuIcon name="clock" :size="12" />
        {{ t('historyTip', { d: lastAt }) }}
      </p>
      <p v-if="lead" class="qx-lead" v-html="richHtml(lead)"></p>
      <!-- 三段结构：讲完就先给"错在哪/这道题怎么做"，再给可复用做法，最后给能执行的提醒 -->
      <section v-for="s in blocks" :key="s.key" class="qx-sec" :class="`sec-${s.key}`">
        <h4 class="qx-sec-title">
          <TikuIcon :name="s.icon" :size="13" />
          {{ s.title }}
        </h4>
        <div v-if="s.body" class="qx-sec-text" v-html="richHtml(s.body)"></div>
        <p v-else class="qx-sec-text text-muted">…</p>
      </section>
      <!-- 兜底：模型没按标题分段（极少数），至少别把内容吞掉 -->
      <div v-if="!blocks.length" class="qx-sec">
        <div class="qx-sec-text" v-html="richHtml(text)"></div>
      </div>
      <span v-if="busy" class="qx-cursor"></span>

      <!-- 追问：接着这场讲解问，不用另开面板 -->
      <div v-for="(f, i) in followUps" :key="`f${i}`" class="qx-follow" :class="f.role">
        <div class="qx-bubble" v-html="richHtml(f.content)"></div>
      </div>

      <p v-if="error" class="qx-error">{{ error }}</p>

      <div v-if="!busy" class="qx-foot">
        <input
          v-model="draft"
          class="qx-input"
          :placeholder="t('askPh')"
          @keydown.enter.exact.prevent="ask"
        />
        <button class="btn btn-secondary btn-sm" :disabled="!draft.trim()" @click="ask">{{ t('askBtn') }}</button>
        <button class="btn btn-secondary btn-sm" :disabled="saving || saved" @click="save">
          {{ saved ? t('saved') : saving ? t('saving') : t('saveBtn') }}
        </button>
        <!-- 讲得好的部分直接进"我的笔记"：解析是题库的（会给别人看），笔记是我的（只在本机） -->
        <button class="btn btn-secondary btn-sm" :disabled="noting || noted" @click="saveToNote">
          {{ noted ? t('noted') : noting ? t('noting') : t('noteBtn') }}
        </button>
        <button class="btn btn-secondary btn-sm" @click="explain(true)">{{ t('again') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 单题**讲解**（做题后唯一的解析入口，2026-09-16 用户拍板统一）。
 *
 * 为什么只有一种解析：过去"答错即问"与题库里的"AI 解析"是两套 prompt、两种风格，
 * 同一道错题会出现两段说法不同的话。现在统一成这一个组件、一个后端引擎
 * （`POST /api/tutor/explain`），**两个去处**：① 就地看+追问 ②「存为解析」写回题库。
 *
 * 两个口吻（由后端按"这题有没有作答记录"自动判定，调用方也可以显式指定）：
 * - `WRONG`（有作答）：错在哪 / 这类题怎么做 / 下次防错  —— 可以先点错因，讲得更贴你的真实原因；
 * - `NEUTRAL`（没作答）：这道题怎么做 / 这类题怎么做 / 易错点。
 * 前端按**标题标记**分块渲染，所以两种口吻共用一套解析逻辑（标题从文本里来）。
 *
 * 回看：组件挂载时会把这题上次讲过的内容拉回来（`tutor_message` 已落库），
 * 所以"做完题返回后还能回到那个环节"，也省掉一次模型调用。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import TikuIcon from './TikuIcon.vue'
import { SELF_REASONS, getTutorMessages, getTutorSessions, streamTutor } from '../api/tutor'
import { saveQuestionAnalysis } from '../api/questions'
import { createNote } from '../api/notes'
import { richTextToHtml } from '../utils/richText'

const props = defineProps({
  bankId: { type: [Number, String], required: true },
  questionId: { type: [Number, String], required: true },
  practiceSessionId: { type: [Number, String], default: null },
  templateId: { type: String, default: '' },
  /** 口吻：WRONG（有作答）/ NEUTRAL（没作答）/ 空 = 由后端按有无作答记录判定 */
  mode: { type: String, default: '' },
  /** 打开时就自动讲（例如题库列表里点「讲解」展开） */
  autoStart: { type: Boolean, default: false }
})

const emit = defineEmits(['saved', 'noted'])

const { t } = useI18n({
  messages: {
    'zh-CN': {
      explainBtn: '讲给我听',
      explainBtnNeutral: '讲解这道题',
      reasonLabel: '为什么错？',
      tip: '（可不选）：选了会按你的真实原因讲，不是通用解析。',
      thinking: '老师正在看这道题…（约 10~40 秒）',
      historyTip: '上次讲于 {d}（下面可以直接接着追问）',
      lastAt: '上次讲过（点按钮可以再看一次）',
      headWhere: '错在哪',
      headHowTo: '这道题怎么做',
      headHow: '这类题怎么做',
      headGuard: '下次防错',
      headPitfall: '易错点',
      askPh: '还有哪里不明白？',
      askBtn: '追问',
      saveBtn: '存为解析',
      saved: '已存为解析',
      saving: '保存中…',
      again: '重新讲',
      savedToast: '已存为本题解析，下次打开就能看到',
      noteBtn: '存进笔记',
      noted: '已存进笔记',
      noting: '保存中…',
      notedToast: '已存进我的笔记（只在本机）',
      errNoAi: '尚未配置 AI 模型：请在「设置 → AI」里填好 Key 再回来。',
      errEmpty: 'AI 没有返回内容，可再点一次「重新讲」'
    },
    'en-US': {
      explainBtn: 'Explain it to me',
      explainBtnNeutral: 'Explain this question',
      reasonLabel: 'Why wrong?',
      tip: '(optional): pick a reason and the explanation follows your actual mistake.',
      thinking: 'The tutor is reading this question… (about 10–40 s)',
      historyTip: 'Explained {d} — you can keep asking below',
      lastAt: 'Explained before (click to see it again)',
      headWhere: 'Where you went wrong',
      headHowTo: 'How to solve this one',
      headHow: 'How to handle this type',
      headGuard: 'Avoid it next time',
      headPitfall: 'Common pitfalls',
      askPh: 'Anything still unclear?',
      askBtn: 'Ask',
      saveBtn: 'Save as analysis',
      saved: 'Saved',
      saving: 'Saving…',
      again: 'Explain again',
      savedToast: 'Saved as this question’s analysis — it will show up next time',
      noteBtn: 'Save to my notes',
      noted: 'Saved to notes',
      noting: 'Saving…',
      notedToast: 'Saved to your notes (this machine only)',
      errNoAi: 'AI is not configured yet: set your key in Settings → AI.',
      errEmpty: 'The AI returned nothing — click “Explain again”.'
    }
  }
})

/**
 * 标题标记 → 段落（后端两种口吻共用这套标记；顺序按文本里出现的位置排）。
 * 与后端常量一一对应：TutorService.EXPLAIN_HEAD_*。
 */
const HEADS = [
  { key: 'where', marker: '【错在哪】', titleKey: 'headWhere', icon: 'target' },
  { key: 'howto', marker: '【这道题怎么做】', titleKey: 'headHowTo', icon: 'target' },
  { key: 'how', marker: '【这类题怎么做】', titleKey: 'headHow', icon: 'list' },
  { key: 'guard', marker: '【下次防错】', titleKey: 'headGuard', icon: 'bell' },
  { key: 'pitfall', marker: '【易错点】', titleKey: 'headPitfall', icon: 'bell' }
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
const noting = ref(false)
const noted = ref(false)
const everStarted = ref(false)
/** 当前展示的是"上次讲过的"（回看）还是刚生成的 */
const fromHistory = ref(false)
const lastAt = ref('')

const richHtml = (s) => richTextToHtml(s || '', props.bankId)
/** 错因三选只在"有作答"的场景出现（没作答时问"为什么错"没有意义） */
const reasonChips = computed(() => props.mode === 'WRONG')
const explainLabel = computed(() => (props.mode === 'NEUTRAL' ? t('explainBtnNeutral') : t('explainBtn')))

/** 把文本切成段落：按标记定位，标题之前的内容当引子（不丢内容） */
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

const hasMarkers = (s) => HEADS.some((h) => String(s || '').includes(h.marker))

function reset() {
  text.value = ''
  error.value = ''
  reason.value = ''
  followUps.value = []
  draft.value = ''
  saved.value = false
  noted.value = false
  everStarted.value = false
  fromHistory.value = false
  lastAt.value = ''
  sessionId.value = null
}

function humanAt(iso) {
  if (!iso) return ''
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  const days = Math.floor((Date.now() - at.getTime()) / 86400000)
  if (days <= 0) {
    return `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`
  }
  return `${at.getMonth() + 1} 月 ${at.getDate()} 日`
}

/**
 * 回看：把这题上次讲过的内容拉回来（不调模型）。
 * 找到"最后一条含三段标记的助手消息"当讲解，它之后的对话当追问历史。
 */
async function loadHistory() {
  if (!props.questionId) return
  try {
    const sessions = await getTutorSessions(props.questionId)
    const list = sessions || []
    // 优先同一场练习的会话（"刚做完这题"的上下文最准），否则取最近一条
    const hit = list.find((s) => String(s.practiceSessionId ?? '') === String(props.practiceSessionId ?? '')) || list[0]
    if (!hit) return
    const data = await getTutorMessages(hit.id)
    const msgs = data?.messages || []
    let idx = -1
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'assistant' && hasMarkers(msgs[i].content)) {
        idx = i
        break
      }
    }
    if (idx < 0) return
    sessionId.value = hit.id
    text.value = msgs[idx].content
    fromHistory.value = true
    lastAt.value = humanAt(msgs[idx].createdAt || hit.createdAt)
    followUps.value = msgs.slice(idx + 1).map((m) => ({ role: m.role, content: m.content }))
    everStarted.value = true
  } catch (e) {
    /* 拉不到历史不影响重新讲 */
  }
}

onMounted(async () => {
  await loadHistory()
  if (props.autoStart && !everStarted.value && !text.value) {
    explain()
  }
})

watch(
  () => props.questionId,
  async () => {
    reset()
    await loadHistory()
  }
)

async function explain(force = false) {
  if (busy.value || !props.questionId) return
  if (force) {
    text.value = ''
    followUps.value = []
    saved.value = false
    fromHistory.value = false
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

/** 存为解析：讲解变成题库的一部分，下次打开就在（不再是"聊过就没了"） */
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
    mode: props.mode || null,
    selfReason: reason.value || null
  }
}

/**
 * 存进笔记：整段讲解写进"我的笔记"（source=ai，界面上标出来）。
 * 与「存为解析」的分工：解析是题库的（会随包分享），笔记是我的（只在本机）。
 */
async function saveToNote() {
  if (noting.value || noted.value || !text.value.trim()) return
  noting.value = true
  error.value = ''
  try {
    await createNote({
      bankId: Number(props.bankId),
      questionId: Number(props.questionId),
      content: text.value,
      source: 'ai'
    })
    noted.value = true
    ElMessage.success(t('notedToast'))
    emit('noted')
  } catch (e) {
    error.value = e?.response?.data?.message || t('noteBtn')
  } finally {
    noting.value = false
  }
}

defineExpose({ explain, loadHistory, explainLabel })
</script>

<style scoped>
.qx {
  margin-top: 8px;
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.qx-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.qx-reason-label {
  font-size: 12px;
  margin-left: 4px;
}
.qx-tip {
  font-size: 12px;
  width: 100%;
}
.qx-chip {
  border: 1px solid var(--border);
  background: var(--bg-card);
  border-radius: 12px;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.qx-chip.on {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.qx-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.qx-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: qx-rot 0.8s linear infinite;
  display: inline-block;
}
@keyframes qx-rot {
  to {
    transform: rotate(360deg);
  }
}
.qx-body {
  margin-top: 4px;
}
.qx-history {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 6px;
  font-size: 12px;
}
.qx-sec {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
}
.qx-sec-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
}
.sec-where .qx-sec-title {
  color: var(--danger);
}
.sec-howto .qx-sec-title,
.sec-how .qx-sec-title {
  color: var(--accent-text);
}
.sec-guard .qx-sec-title,
.sec-pitfall .qx-sec-title {
  color: var(--success);
}
.qx-sec-text {
  font-size: 13px;
  line-height: 1.8;
  word-break: break-word;
}
.qx-lead {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.7;
}
.qx-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: var(--accent);
  animation: qx-blink 1s step-end infinite;
}
@keyframes qx-blink {
  50% {
    opacity: 0;
  }
}
.qx-follow {
  display: flex;
  margin: 6px 0;
}
.qx-follow.user {
  justify-content: flex-end;
}
.qx-bubble {
  max-width: 92%;
  border-radius: 10px;
  padding: 6px 10px;
  font-size: 13px;
  line-height: 1.8;
  background: var(--bg-elev);
  word-break: break-word;
}
.qx-follow.user .qx-bubble {
  background: var(--accent-soft);
}
.qx-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.qx-input {
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
.qx-error {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--danger);
}
</style>
