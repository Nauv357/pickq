<template>
  <div class="srev">
    <!-- 未交卷：说清"现在看到的还不是最终答案" -->
    <div v-if="session.status !== 'COMPLETED'" class="warn-banner">
      <TikuIcon name="info" :size="15" />
      <span>{{ t('notSubmittedTip') }}</span>
    </div>

    <!-- 成绩三项（纯公式统计，不做评价） -->
    <div v-if="showStats" class="srev-stats">
      <div class="stat">
        <span class="stat-num mono">{{ session.correctCount }} / {{ session.questionCount || session.questions?.length || 0 }}</span>
        <span class="stat-label">{{ t('statCorrect') }}</span>
      </div>
      <div class="stat">
        <span class="stat-num mono">{{ accuracy }}%</span>
        <span class="stat-label">{{ t('statAccuracy') }}</span>
      </div>
      <div class="stat">
        <span class="stat-num mono">{{ formatScore(session.totalScore) }} / {{ formatScore(session.maxScore) }}</span>
        <span class="stat-label">{{ t('statScore') }}</span>
      </div>
      <div class="stat">
        <span class="stat-num mono">{{ formatDuration(session.totalSeconds) }}</span>
        <span class="stat-label">{{ t('statTime') }}</span>
      </div>
    </div>

    <!-- 每题回顾（全宽；宽视口右侧悬浮"答题卡"圆钮，不挤占列表宽度） -->
    <div class="review-list">
      <div
        v-for="(q, i) in session.questions || []"
        :key="q.questionId"
        class="review-card"
        :class="{ 'review-flash': dockFlashId === q.questionId }"
        :data-qid="q.questionId"
      >
        <div class="review-head">
          <span class="q-number mono">#{{ q.questionNumber ?? i + 1 }}</span>
          <span class="q-type" :class="`type-${String(q.questionType).toLowerCase()}`">{{ typeLabel(q) }}</span>
          <span class="review-badge" :class="reviewState(q).cls">{{ reviewState(q).text }}</span>
          <span class="review-meta text-muted mono">
            {{ formatScore(reviewState(q).earned) }}/{{ formatScore(q.score) }} {{ t('unitPoint') }}
            <span v-if="q.seconds != null"> · {{ q.seconds }}s</span>
          </span>
        </div>
        <div class="review-content" v-html="richHtml(q.content)"></div>

        <!-- 共用材料（多题共用，只展示一次，可折叠） -->
        <div v-if="showMaterial(q)" class="review-material" :class="{ collapsed: isMaterialCollapsed(q.materialId) }">
          <div class="material-head" @click="toggleMaterial(q.materialId)">
            <span class="material-title">{{ t('material') }}</span>
            <span class="material-toggle">
              <TikuIcon :name="isMaterialCollapsed(q.materialId) ? 'chevron-down' : 'chevron-up'" :size="13" />
              {{ isMaterialCollapsed(q.materialId) ? t('expandMaterial') : t('collapseMaterial') }}
            </span>
          </div>
          <div v-show="!isMaterialCollapsed(q.materialId)" class="material-body" v-html="richHtml(q.materialContent)"></div>
        </div>

        <!-- 选项（交卷后高亮正确/错选） -->
        <div v-if="q.options?.length" class="review-options">
          <div v-for="opt in q.options" :key="opt.key" class="review-opt" :class="reviewOptClass(q, opt.key)">
            <span class="opt-key">{{ opt.key }}</span>
            <span class="opt-text" v-html="richHtml(opt.text)"></span>
            <TikuIcon v-if="q.answerKeys?.includes(opt.key)" name="check" :size="14" class="opt-check" />
            <TikuIcon v-else-if="q.selectedKeys?.includes(opt.key) && q.correct === false" name="x" :size="14" class="opt-x" />
          </div>
        </div>

        <!-- 主观题：我的作答 + 自由给分（0~满分，0.5 步进） -->
        <div v-if="q.questionType === 'SUBJECTIVE'" class="review-subjective">
          <div class="review-answer">
            <span class="text-muted">{{ t('myAnswer') }}：</span>
            <span class="sub-answer" v-html="richHtml(q.userAnswer || t('notAnswered'))"></span>
          </div>
          <div v-if="q.selfGrade" class="self-grade-badge" :class="`sg-${String(q.selfGrade).toLowerCase()}`">
            {{ selfGradeText(q.selfGrade) }}
            <span class="sg-score mono">· {{ t('earnedScore') }} {{ formatScore(earnedOf(q)) }} / {{ formatScore(q.score) }}</span>
          </div>
          <div v-if="(!q.selfGrade && q.userAnswer) || regradingQid === q.questionId" class="review-grade">
            <span class="text-muted">{{ q.selfGrade ? t('regrade') : t('selfGradePrompt') }}</span>
            <div class="review-grade-btns">
              <el-slider
                :model-value="gradeVal(q)"
                :min="0"
                :max="q.score"
                :step="0.5"
                :disabled="gradingQid === q.questionId"
                style="width: 170px; margin: 0 6px"
                @update:model-value="(v) => setDraft(q, v)"
              />
              <el-input-number
                :model-value="gradeVal(q)"
                :min="0"
                :max="q.score"
                :step="0.5"
                :precision="1"
                size="small"
                controls-position="right"
                style="width: 88px"
                :disabled="gradingQid === q.questionId"
                @update:model-value="(v) => setDraft(q, v)"
              />
              <span class="text-muted mono">/ {{ formatScore(q.score) }}</span>
              <button class="btn btn-secondary btn-sm" :disabled="gradingQid === q.questionId" @click="quickGrade(q, q.score)">{{ t('fullMarks') }}</button>
              <button class="btn btn-primary btn-sm" :disabled="gradingQid === q.questionId" @click="doGradeScore(q)">
                {{ gradingQid === q.questionId ? t('saving') : t('saveScore') }}
              </button>
            </div>
          </div>
          <div v-if="q.selfGrade && regradingQid !== q.questionId" class="review-grade">
            <button class="btn btn-ghost btn-sm" @click="regradingQid = q.questionId">{{ t('regrade') }}</button>
          </div>
          <template v-if="q.referenceAnswer">
            <div class="sub-ref-title">{{ t('referenceAnswer') }}</div>
            <div class="sub-ref-body" v-html="richHtml(q.referenceAnswer)"></div>
          </template>
        </div>

        <div class="review-answer">
          <span v-if="q.questionType !== 'SUBJECTIVE'" class="text-muted">
            {{ t('myAnswer') }}：{{ q.selectedKeys?.length ? q.selectedKeys.join('、') : t('notAnswered') }}
          </span>
          <template v-if="q.answerKeys?.length">
            <span class="dot"></span>
            <span class="text-secondary">{{ t('correctAnswer') }}：{{ q.answerKeys.join('、') }}</span>
          </template>
          <span v-if="q.seconds != null" class="dot"></span>
          <span v-if="q.seconds != null" class="text-muted">{{ t('timeUsed') }} {{ q.seconds }}s</span>
        </div>

        <template v-if="q.answerText || q.analysis">
          <p v-if="q.answerText" class="review-text"><b>{{ t('answerLbl') }}：</b>{{ q.answerText }}</p>
          <p v-if="q.analysis" class="review-text"><b>{{ t('analysisLbl') }}：</b><span v-html="richHtml(q.analysis)"></span></p>
        </template>

        <!-- 讲解（做题后唯一的解析入口）：对错都用同一个组件，自动回看上次讲过的内容 -->
        <QuestionExplain
          v-if="q.questionId"
          :question-id="q.questionId"
          :bank-id="Number(bankId)"
          :practice-session-id="session.id"
          :template-id="templateId"
          :mode="isWrong(q) ? 'WRONG' : 'NEUTRAL'"
          @saved="onSavedAnalysis(q, $event)"
        />
        <!-- 我的笔记（只在本机） -->
        <QuestionNotes v-if="q.questionId" :bank-id="Number(bankId)" :question-id="q.questionId" />
      </div>
    </div>

    <!-- 右侧悬浮答题卡：宽视口常显；窄视口收成"答题卡"小按钮 -->
    <template v-if="showDock && session.questions?.length">
      <QuestionNavDock
        v-if="!dockNarrow || dockFabOpen"
        class="review-dock"
        :class="{ 'dock-pop': dockNarrow }"
        :title="t('answerSheet')"
        :items="dockItems"
        :show-legend="true"
        :draggable="!dockNarrow"
        :storage-key="dockNarrow ? '' : 'review-dock'"
        @select="onDockSelect"
      />
      <button
        v-if="dockNarrow"
        class="dock-fab"
        :class="{ open: dockFabOpen }"
        :title="dockFabOpen ? t('collapseSheet') : t('openSheet')"
        @click="dockFabOpen = !dockFabOpen"
      >
        <TikuIcon :name="dockFabOpen ? 'x' : 'list'" :size="14" />
        <span>{{ dockFabOpen ? t('collapse') : t('answerSheet') }}</span>
      </button>
    </template>
  </div>
</template>

<script setup>
/**
 * 一场练习的逐题回顾（组件化，2026-09-16 用户反馈）。
 *
 * 为什么必须是一个组件而不是两套代码：做完成绩页与「练习历史」里看同一次练习，
 * 过去是两份实现，于是"刚做完能看到的题干，历史里看不到"这类不一致反复出现。
 * 现在两处渲染的是同一个组件、同一份数据（都用 `GET /sessions/{id}`）：
 * 刚交卷的那一屏 = 历史里点开那一次，逐题部分一字不差。
 *
 * 边界：这里只呈现"用户自己的作答与题库自带的答案/解析"，不对掌握度下结论；
 * 讲解与笔记各自的边界见 QuestionExplain / QuestionNotes。
 */
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { selfGradeRecord } from '../api/studyRecords'
import { formatScore } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import TikuIcon from './TikuIcon.vue'
import QuestionExplain from './QuestionExplain.vue'
import QuestionNotes from './QuestionNotes.vue'
import QuestionNavDock from './QuestionNavDock.vue'

const props = defineProps({
  /** 会话详情（`GET /sessions/{id}` 的返回；两处都用这一份数据） */
  session: { type: Object, required: true },
  bankId: { type: [Number, String], required: true },
  /** 成绩三项统计（成绩页要，历史页也要——默认都显示） */
  showStats: { type: Boolean, default: true },
  /** 右侧答题卡（回顾时快速跳题） */
  showDock: { type: Boolean, default: true },
  /** 知识点口径（复盘诊断选的技能图），透传给讲解组件 */
  templateId: { type: String, default: '' }
})

/** 自评赋分后通知父级重新拉取会话（分数要跟着变） */
const emit = defineEmits(['changed'])

const { t } = useI18n({
  messages: {
    'zh-CN': {
      notSubmittedTip: '本场尚未交卷，交卷后可查看完整答案与解析',
      statCorrect: '答对', statAccuracy: '正确率', statScore: '得分', statTime: '用时',
      material: '材料', expandMaterial: '展开材料', collapseMaterial: '收起材料',
      myAnswer: '我的作答', notAnswered: '未作答', earnedScore: '实得',
      selfGradePrompt: '对照参考答案自评赋分：', regrade: '重新自评：',
      unitPoint: '分', fullMarks: '全对', saving: '保存中…', saveScore: '保存得分',
      referenceAnswer: '参考答案', correctAnswer: '正确答案', answerLbl: '答案', analysisLbl: '解析',
      timeUsed: '用时',
      answerSheet: '答题卡', collapseSheet: '收起答题卡', openSheet: '打开答题卡', collapse: '收起',
      selfSaveDone: '自评已保存（{earned} / {score} 分）',
      gradeCorrect: '自评：回答正确', gradePartial: '自评：部分正确', gradeWrong: '自评：回答错误',
      qCorrect: '正确', qWrong: '错误', qPartial: '部分正确', qPending: '未自评', qUndecided: '未判', qSkipped: '未作答'
    },
    'en-US': {
      notSubmittedTip: 'This session is not submitted yet — full answers and explanations appear after submission',
      statCorrect: 'Correct', statAccuracy: 'Accuracy', statScore: 'Score', statTime: 'Time',
      material: 'Material', expandMaterial: 'Expand material', collapseMaterial: 'Collapse material',
      myAnswer: 'My answer', notAnswered: 'Not answered', earnedScore: 'Earned',
      selfGradePrompt: 'Grade against the reference answer: ', regrade: 'Regrade: ',
      unitPoint: 'pts', fullMarks: 'Full marks', saving: 'Saving…', saveScore: 'Save score',
      referenceAnswer: 'Reference answer', correctAnswer: 'Correct answer', answerLbl: 'Answer', analysisLbl: 'Analysis',
      timeUsed: 'Time',
      answerSheet: 'Answer sheet', collapseSheet: 'Collapse answer sheet', openSheet: 'Open answer sheet', collapse: 'Collapse',
      selfSaveDone: 'Self-grade saved ({earned} / {score} pts)',
      gradeCorrect: 'Self-graded: correct', gradePartial: 'Self-graded: partial', gradeWrong: 'Self-graded: wrong',
      qCorrect: 'Correct', qWrong: 'Wrong', qPartial: 'Partial', qPending: 'Not graded', qUndecided: 'Undecided', qSkipped: 'Skipped'
    }
  }
})

const accuracy = computed(() => {
  const total = Number(props.session.questionCount || props.session.questions?.length || 0)
  return total ? Math.round((Number(props.session.correctCount || 0) / total) * 100) : 0
})

const richHtml = (text) => richTextToHtml(text, props.bankId)
const formatDuration = (seconds) => {
  if (seconds == null) return '—'
  const s = Math.max(0, seconds)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  return rest ? `${m} 分 ${rest} 秒` : `${m} 分钟`
}

const TYPE_LABELS = { SINGLE: '单选题', MULTIPLE: '多选题', JUDGE: '判断题', SUBJECTIVE: '主观题' }
const typeLabel = (q) => q.typeLabel || TYPE_LABELS[q.questionType] || q.questionType

/** 讲解口吻：答错（含自评错/部分对）要讲"错在哪"，没作答讲"这道题怎么做" */
const isWrong = (q) => q.correct === false || q.selfGrade === 'WRONG' || q.selfGrade === 'PARTIAL'

/* ---------- 选项高亮 / 状态 ---------- */
function reviewOptClass(q, key) {
  if (q.answerKeys?.includes(key)) return 'is-correct'
  if (q.selectedKeys?.includes(key) && q.correct === false) return 'is-wrong'
  if (q.selectedKeys?.includes(key)) return 'is-selected'
  return ''
}

function earnedOf(q) {
  if (q.selfScore != null) return Math.min(q.score ?? 0, Math.max(0, q.selfScore))
  if (q.selfGrade === 'CORRECT') return q.score
  if (q.selfGrade === 'PARTIAL') return q.score / 2
  if (q.correct === true) return q.score
  return 0
}

/* 头部状态（含得分）：主观看自评（部分对按错计红标）；客观看判题；
   客观题已作答但题目未配答案 → "未判"待定（不判对错） */
function reviewState(q) {
  if (q.questionType === 'SUBJECTIVE') {
    if (q.selfGrade === 'CORRECT') return { cls: 'badge-ok', text: t('qCorrect'), earned: earnedOf(q) }
    if (q.selfGrade === 'PARTIAL') return { cls: 'badge-no', text: t('qPartial'), earned: earnedOf(q) }
    if (q.selfGrade === 'WRONG') return { cls: 'badge-no', text: t('qWrong'), earned: 0 }
    return { cls: 'badge-skip', text: q.userAnswer ? t('qPending') : t('qSkipped'), earned: 0 }
  }
  if (q.correct === true) return { cls: 'badge-ok', text: t('qCorrect'), earned: q.score }
  if (q.correct === false) return { cls: 'badge-no', text: t('qWrong'), earned: 0 }
  if (q.selectedKeys?.length) return { cls: 'badge-skip', text: t('qUndecided'), earned: 0 }
  return { cls: 'badge-skip', text: t('qSkipped'), earned: 0 }
}

/* ---------- 共用材料只展示一次 ---------- */
const seenMaterials = new Set()
function showMaterial(q) {
  if (q.materialContent && !seenMaterials.has(q.materialId)) {
    seenMaterials.add(q.materialId)
    return true
  }
  return false
}

const collapsedMaterials = reactive(new Set())
const isMaterialCollapsed = (mid) => collapsedMaterials.has(mid)
function toggleMaterial(mid) {
  if (collapsedMaterials.has(mid)) collapsedMaterials.delete(mid)
  else collapsedMaterials.add(mid)
}

/* ---------- 主观题自由给分 ---------- */
const gradingQid = ref(null)
const regradingQid = ref(null)
const gradeDrafts = reactive({})
const gradeVal = (q) => (gradeDrafts[q.questionId] !== undefined ? gradeDrafts[q.questionId] : earnedOf(q))
function setDraft(q, v) {
  gradeDrafts[q.questionId] = v
}
async function quickGrade(q, earned) {
  setDraft(q, earned)
  await doGradeScore(q)
}
async function doGradeScore(q) {
  if (!q.recordId || gradingQid.value) return
  const earned = gradeVal(q)
  gradingQid.value = q.questionId
  try {
    await selfGradeRecord(q.recordId, earned)
    ElMessage.success(t('selfSaveDone', { earned, score: q.score }))
    delete gradeDrafts[q.questionId]
    regradingQid.value = null
    emit('changed')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    gradingQid.value = null
  }
}
const selfGradeText = (g) => (g === 'CORRECT' ? t('gradeCorrect') : g === 'PARTIAL' ? t('gradePartial') : t('gradeWrong'))

/* AI 解析保存成功：本地同步该题解析展示（不必整页重拉） */
function onSavedAnalysis(q, text) {
  if (q && text) q.analysis = text
}

/* ---------- 答题卡 ---------- */
function dockStatus(q) {
  if (q.questionType === 'SUBJECTIVE') {
    if (!q.selfGrade) return 'skip'
    if (q.selfGrade === 'CORRECT') return 'ok'
    if (q.selfGrade === 'PARTIAL') return 'partial'
    return 'no'
  }
  if (q.correct === true) return 'ok'
  if (q.correct === false) return 'no'
  return 'skip'
}

const dockItems = computed(() =>
  (props.session.questions || []).map((q, i) => ({
    questionId: q.questionId,
    questionNumber: q.questionNumber ?? i + 1,
    status: dockStatus(q)
  }))
)

const dockNarrow = ref(false)
const dockFabOpen = ref(false)
const dockMq = window.matchMedia('(max-width: 1680px)')
function syncDockNarrow(e) {
  dockNarrow.value = e.matches
}
dockNarrow.value = dockMq.matches
dockMq.addEventListener('change', syncDockNarrow)
onBeforeUnmount(() => dockMq.removeEventListener('change', syncDockNarrow))

const dockFlashId = ref(null)
let dockFlashTimer = null
function onDockSelect(questionId) {
  dockFlashId.value = questionId
  clearTimeout(dockFlashTimer)
  nextTick(() => {
    document.querySelector(`.review-card[data-qid="${questionId}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
  dockFlashTimer = setTimeout(() => {
    if (dockFlashId.value === questionId) dockFlashId.value = null
  }, 2600)
}
</script>

<style scoped>
.srev {
  width: 100%;
}
.warn-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 16px;
  border-radius: 12px;
  background: var(--warning-soft);
  border: 1px solid var(--warning);
  color: var(--warning);
  font-size: 13px;
  margin-bottom: 16px;
}
/* 成绩三项：纯公式统计（不算掌握度、不给结论） */
.srev-stats {
  display: flex;
  gap: 28px;
  padding: 14px 18px;
  margin-bottom: 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.stat-num {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}
.stat-label {
  font-size: 12px;
  color: var(--text-muted);
}

/* 右侧悬浮答题卡：fixed 于内容右缘与视口右边界之间，回顾卡列表保持全宽不被挤压 */
.review-dock {
  position: fixed;
  top: 84px;
  right: 24px;
  width: 188px;
  z-index: 40;
}
.review-dock.dock-pop {
  top: 132px;
}
.review-dock.dock-pop :deep(.qnav-grid) {
  max-height: calc(100vh - 340px);
}
.review-flash {
  box-shadow: inset 0 0 0 2px var(--accent);
}

.review-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.review-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 18px 22px;
}
.review-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.q-number {
  color: var(--text-muted);
  font-size: 13px;
}
.q-type {
  font-size: 12px;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent-text);
  border: 1px solid var(--accent);
}
.q-type.type-multiple {
  background: var(--success-soft);
  color: var(--success);
  border-color: var(--success);
}
.q-type.type-judge {
  background: var(--warning-soft);
  color: var(--warning);
  border-color: var(--warning);
}
.review-badge {
  font-size: 12px;
  padding: 2px 9px;
  border-radius: 999px;
  font-weight: 500;
}
.badge-ok {
  background: var(--success-soft);
  color: var(--success);
  border: 1px solid var(--success);
}
.badge-no {
  background: var(--danger-soft);
  color: var(--danger);
  border: 1px solid var(--danger);
}
.badge-skip {
  background: var(--bg-elev);
  color: var(--text-muted);
  border: 1px solid var(--border);
}
.review-meta {
  margin-left: auto;
  font-size: 12px;
}
.review-content {
  margin: 12px 0 14px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.review-content :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 6px 0;
  display: block;
}

/* 共用材料（标题栏 + 折叠） */
.review-material {
  margin-bottom: 16px;
  background: var(--bg-elev);
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  overflow: hidden;
}
.review-material.collapsed .material-body {
  display: none;
}
.material-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 14px;
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid var(--border);
  transition: background var(--ease);
}
.material-head:hover {
  background: var(--bg-hover);
}
.material-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-text);
}
.material-title::before {
  content: '';
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--accent);
}
.material-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
  transition: color var(--ease);
}
.material-head:hover .material-toggle {
  color: var(--text-secondary);
}
.material-body {
  max-height: 42vh;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 14px 16px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.material-body :deep(.rich-img),
.review-subjective :deep(.rich-img),
.sub-ref-body :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 8px 0;
  display: block;
}

/* 主观题回顾 */
.review-subjective {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}
.sub-answer {
  font-size: 13px;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.self-grade-badge {
  align-self: flex-start;
  font-size: 12px;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 999px;
  border: 1px solid;
}
.sg-correct {
  background: var(--success-soft);
  border-color: var(--success);
  color: var(--success);
}
.sg-partial {
  background: var(--warning-soft);
  border-color: var(--warning);
  color: var(--warning);
}
.sg-wrong {
  background: var(--danger-soft);
  border-color: var(--danger);
  color: var(--danger);
}
.sg-none {
  background: var(--bg-elev);
  border-color: var(--border);
  color: var(--text-muted);
}
.review-grade {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin-top: 8px;
}
.review-grade-btns {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.sg-score {
  font-size: 12px;
}
.sub-ref-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-text);
  margin-top: 6px;
}
.sub-ref-body {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 选项 */
.review-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.review-opt {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 9px;
  font-size: 13px;
  color: var(--text-primary);
}
.opt-key {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}
.opt-text {
  flex: 1;
  min-width: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
.opt-text :deep(.rich-img) {
  max-width: 100%;
  max-height: 180px;
  width: auto;
  height: auto;
  border-radius: 8px;
  margin: 4px 0;
  vertical-align: middle;
  display: inline-block;
}
.review-opt.is-correct {
  border-color: var(--success);
  background: var(--success-soft);
}
.review-opt.is-correct .opt-key {
  border-color: var(--success);
  color: var(--success);
}
.review-opt.is-correct .opt-check {
  color: var(--success);
}
.review-opt.is-wrong {
  border-color: var(--danger);
  background: var(--danger-soft);
}
.review-opt.is-wrong .opt-key {
  border-color: var(--danger);
  color: var(--danger);
}
.review-opt.is-wrong .opt-x {
  color: var(--danger);
}
.review-opt.is-selected .opt-key {
  border-color: var(--accent);
  color: var(--accent-text);
}
.opt-check,
.opt-x {
  flex-shrink: 0;
}
.review-answer {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  font-size: 13px;
}
.review-text {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.review-text b {
  color: var(--text-secondary);
  font-weight: 500;
}
.dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--text-muted);
}
</style>
