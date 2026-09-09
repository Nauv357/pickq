<template>
  <div class="page">
    <!-- ============ 回顾视图 ============ -->
    <template v-if="viewSession">
      <header class="page-header">
        <div>
          <button class="back-link" @click="$router.push(`/banks/${id}`)">
            <TikuIcon name="arrow-left" :size="14" />
            {{ t('backToBank') }}
          </button>
          <div class="title-line">
            <h1 class="page-title">{{ t('title') }}</h1>
            <span class="mode-tag">{{ MODE_LABELS[viewSession.mode] || viewSession.mode }}</span>
            <span class="status-tag" :class="viewSession.status === 'COMPLETED' ? 'status-done' : 'status-doing'">
              {{ viewSession.status === 'COMPLETED' ? t('completed') : t('running') }}
            </span>
          </div>
          <div class="page-meta text-muted">
            <span>{{ t('correctOf', { a: viewSession.correctCount, b: viewSession.answeredCount }) }}</span>
            <span class="dot"></span>
            <span>{{ t('scoreOf', { a: viewSession.totalScore, b: viewSession.maxScore }) }}</span>
            <span class="dot"></span>
            <span>{{ t('timeUsed') }} {{ formatDuration(viewSession.totalSeconds) }}</span>
            <span class="dot"></span>
            <span>{{ formatDate(viewSession.createdAt) }}</span>
          </div>
        </div>
        <button v-if="viewSession.status !== 'COMPLETED'" class="btn btn-primary" @click="continuePractice">
          <TikuIcon name="play" :size="14" />
          {{ t('continuePractice') }}
        </button>
      </header>

      <div v-if="viewSession.status !== 'COMPLETED'" class="warn-banner">
        <TikuIcon name="info" :size="15" />
        <span>{{ t('notSubmittedTip') }}</span>
      </div>

      <!-- 每题回顾（全宽；编辑/回顾时右侧悬浮"答题卡"圆钮，不挤占列表宽度） -->
      <div class="review-list">
        <div
          v-for="(q, i) in viewSession.questions"
          :key="q.questionId"
          class="review-card"
          :class="{ 'review-flash': dockFlashId === q.questionId }"
          :data-qid="q.questionId"
        >
          <div class="review-head">
            <span class="q-number mono">#{{ q.questionNumber ?? i + 1 }}</span>
            <span class="q-type" :class="`type-${String(q.questionType).toLowerCase()}`">{{ q.typeLabel }}</span>
            <span class="review-badge" :class="reviewState(q).cls">{{ reviewState(q).text }}</span>
            <span class="review-meta text-muted mono">
              {{ formatScore(reviewState(q).earned) }}/{{ formatScore(q.score) }} {{ t('unitPoint') }}
              <span v-if="q.seconds != null"> · {{ q.seconds }}s</span>
            </span>
          </div>
          <div class="review-content" v-html="richHtml(q.content)"></div>

          <!-- 共享材料（资料分析组内题，回顾页只展示一次，可折叠） -->
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
            <div
              v-for="opt in q.options"
              :key="opt.key"
              class="review-opt"
              :class="reviewOptClass(q, opt.key)"
            >
              <span class="opt-key">{{ opt.key }}</span>
              <span class="opt-text" v-html="richHtml(opt.text)"></span>
              <TikuIcon v-if="q.answerKeys?.includes(opt.key)" name="check" :size="14" class="opt-check" />
              <TikuIcon v-else-if="q.selectedKeys?.includes(opt.key) && !q.correct" name="x" :size="14" class="opt-x" />
            </div>
          </div>

          <!-- 主观题：用户作答 + 自评赋分 -->
          <div v-if="q.questionType === 'SUBJECTIVE'" class="review-subjective">
            <div class="review-answer">
              <span class="text-muted">{{ t('myAnswer') }}：</span>
              <span class="sub-answer" v-html="richHtml(q.userAnswer || t('notAnswered'))"></span>
            </div>
            <div v-if="q.selfGrade" class="self-grade-badge" :class="`sg-${String(q.selfGrade).toLowerCase()}`">
              {{ selfGradeText(q.selfGrade) }}
              <span class="sg-score mono">· {{ t('earnedScore') }} {{ formatScore(selfGradeEarned(q)) }} / {{ formatScore(q.score) }}</span>
            </div>
            <!-- 赋分控件：未自评 或 重新自评中（自由给分 0~满分，0.5 步进） -->
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
            <!-- 已自评：可重新赋分 -->
            <div v-if="q.selfGrade && regradingQid !== q.questionId" class="review-grade">
              <button class="btn btn-ghost btn-sm" @click="regradingQid = q.questionId">{{ t('regrade') }}</button>
            </div>
            <template v-if="q.referenceAnswer">
              <div class="sub-ref-title">{{ t('referenceAnswer') }}</div>
              <div class="sub-ref-body" v-html="richHtml(q.referenceAnswer)"></div>
            </template>
          </div>

          <div class="review-answer">
            <span v-if="q.questionType !== 'SUBJECTIVE'" class="text-muted">{{ t('myAnswer') }}：{{ q.selectedKeys?.length ? q.selectedKeys.join('、') : t('notAnswered') }}</span>
            <template v-if="q.answerKeys">
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

          <!-- 单题 AI 辅助解析（回顾错题时按需追问，可保存为正式解析） -->
          <QuestionAiAnalysis
            v-if="q.questionId"
            :question-id="q.questionId"
            :bank-id="Number(id)"
            @saved="onSavedAnalysis(q, $event)"
          />
        </div>
      </div>

      <!-- 右侧悬浮答题卡：宽视口常显；窄视口（右侧放不下）收成"答题卡"小按钮，点击展开 -->
      <template v-if="viewSession.questions?.length">
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
    </template>

    <!-- ============ 历史列表 ============ -->
    <template v-else>
      <header class="page-header">
        <div>
          <RouterLink :to="`/banks/${id}`" class="back-link">
            <TikuIcon name="arrow-left" :size="14" />
            {{ t('backToBank') }}
          </RouterLink>
          <h1 class="page-title">{{ t('historyTitle') }}</h1>
          <p class="page-desc">{{ t('sessionsTotal', { n: total }) }}</p>
        </div>
      </header>

      <div v-if="loading" class="sess-list">
        <div v-for="n in 4" :key="n" class="sess-row tiku-skeleton">
          <div class="sk-line" style="width: 25%"></div>
          <div class="sk-line" style="width: 40%"></div>
        </div>
      </div>

      <div v-else-if="sessions.length === 0" class="empty">
        <TikuIcon name="clock" :size="40" />
        <h3>{{ t('emptyTitle') }}</h3>
        <p class="text-secondary">{{ t('emptyTip') }}</p>
        <button class="btn btn-primary" @click="$router.push(`/banks/${id}`)">{{ t('goPractice') }}</button>
      </div>

      <div v-else class="sess-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="sess-row"
          @click="openReview(s.id)"
        >
          <span class="mode-tag">{{ MODE_LABELS[s.mode] || s.mode }}</span>
          <span class="status-tag" :class="s.status === 'COMPLETED' ? 'status-done' : 'status-doing'">
            {{ s.status === 'COMPLETED' ? t('completed') : t('running') }}
          </span>
          <span class="sess-score mono">{{ formatScore(s.totalScore) }} / {{ formatScore(s.maxScore) }} {{ t('unitPoint') }}</span>
          <span class="sess-answer text-muted mono">{{ t('correctOf', { a: s.correctCount, b: s.answeredCount }) }}</span>
          <span class="sess-time text-muted mono">{{ formatDuration(s.totalSeconds) }}</span>
          <span class="sess-date text-muted">{{ formatDate(s.createdAt) }}</span>
          <TikuIcon name="chevron-right" :size="14" class="sess-arrow" />
        </div>
      </div>

      <div v-if="total > 0" class="pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="pageSize"
          :current-page="page"
          @current-change="onPageChange"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      backToBank: '返回题库', title: '练习回顾', completed: '已完成', running: '进行中',
      correctOf: '答对 {a} / {b}', scoreOf: '得分 {a} / {b}', timeUsed: '用时', continuePractice: '继续做题',
      notSubmittedTip: '本场尚未交卷，交卷后可查看完整答案与解析',
      material: '材料', expandMaterial: '展开材料', collapseMaterial: '收起材料',
      myAnswer: '我的作答', notAnswered: '未作答', earnedScore: '实得', selfGradePrompt: '对照参考答案自评赋分：', regrade: '重新自评：',
      unitPoint: '分',
      fullMarks: '全对', saving: '保存中…', saveScore: '保存得分', referenceAnswer: '参考答案'
    },
    'en-US': {
      backToBank: 'Back to bank', title: 'Session Review', completed: 'Completed', running: 'In progress',
      correctOf: '{a} / {b} correct', scoreOf: 'Score {a} / {b}', timeUsed: 'Time', continuePractice: 'Continue practicing',
      notSubmittedTip: 'This session is not submitted yet — full answers and explanations appear after submission',
      material: 'Material', expandMaterial: 'Expand material', collapseMaterial: 'Collapse material',
      myAnswer: 'My answer', notAnswered: 'Not answered', earnedScore: 'Earned', selfGradePrompt: 'Grade against the reference answer: ', regrade: 'Regrade: ',
      unitPoint: 'pts',
      fullMarks: 'Full marks', saving: 'Saving…', saveScore: 'Save score', referenceAnswer: 'Reference answer'
    }
  }
})

import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getSessionDetail, listSessions } from '../api/sessions'
import { selfGradeRecord } from '../api/studyRecords'
import { formatDate, formatScore } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import TikuIcon from '../components/TikuIcon.vue'
import QuestionAiAnalysis from '../components/QuestionAiAnalysis.vue'
import QuestionNavDock from '../components/QuestionNavDock.vue'

const route = useRoute()
const router = useRouter()
const id = route.params.id

const MODE_LABELS = {
  ALL: '全部随机',
  SEQUENCE: '顺序刷题',
  TOPIC: '按分类',
  REVIEW: '复习队列',
  WRONG: '错题重做',
  FAVORITE: '收藏'
}

/* ---------- 历史列表 ---------- */
const sessions = ref([])
const loading = ref(true)
const page = ref(1)
const pageSize = 10
const total = ref(0)

async function loadSessions() {
  loading.value = true
  try {
    const data = await listSessions(id, { page: page.value, size: pageSize })
    sessions.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (e) {
    sessions.value = []
  } finally {
    loading.value = false
  }
}

function onPageChange(p) {
  page.value = p
  loadSessions()
}

/* ---------- 回顾 ---------- */
const viewSession = ref(null)

async function openReview(sessionId) {
  try {
    viewSession.value = await getSessionDetail(sessionId)
    seenMaterials.clear()
    // 同步 URL，方便刷新/分享
    router.replace({ path: `/banks/${id}/sessions`, query: { view: sessionId } })
  } catch (e) {
    /* 拦截器已提示 */
  }
}

function continuePractice() {
  if (!viewSession.value) return
  router.push({ path: `/banks/${id}/practice`, query: { sessionId: viewSession.value.id } })
}

/* 回顾页选项样式：交卷后有 answerKeys 才高亮 */
function reviewOptClass(q, key) {
  const classes = []
  if (q.answerKeys?.includes(key)) classes.push('is-correct')
  else if (q.selectedKeys?.includes(key) && q.correct === false) classes.push('is-wrong')
  else if (q.selectedKeys?.includes(key)) classes.push('is-selected')
  return classes
}

/* 共享材料只展示一次（回顾页列表式展示） */
const seenMaterials = new Set()
function showMaterial(q) {
  if (q.materialContent && !seenMaterials.has(q.materialId)) {
    seenMaterials.add(q.materialId)
    return true
  }
  return false
}
const richHtml = (text) => richTextToHtml(text, id)

/* 材料折叠状态 */
const collapsedMaterials = reactive(new Set())
const isMaterialCollapsed = (mid) => collapsedMaterials.has(mid)
function toggleMaterial(mid) {
  if (collapsedMaterials.has(mid)) collapsedMaterials.delete(mid)
  else collapsedMaterials.add(mid)
}

const selfGradeText = (g) => (g === 'CORRECT' ? '自评：回答正确' : g === 'PARTIAL' ? '自评：部分正确' : '自评：回答错误')

//AI 解析保存成功：本地同步该题解析展示
function onSavedAnalysis(q, text) {
  if (q && text) {
    q.analysis = text
  }
}

/* ---------- 答题卡（右侧圆钮：对错色 + 点题号直达回顾卡） ---------- */
//状态色：客观按 correct；主观按自评（CORRECT=对 / PARTIAL=部分 / WRONG=错 / 未自评=未答灰）
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

/* 回顾卡头部状态（含得分）：主观看自评（PARTIAL 按错计红标）；客观看判题；
   客观题已作答但题目未配答案 → "未判"待定（不判对错） */
function reviewState(q) {
  if (q.questionType === 'SUBJECTIVE') {
    if (q.selfGrade === 'CORRECT') return { cls: 'badge-ok', text: '正确', earned: selfGradeEarned(q) }
    if (q.selfGrade === 'PARTIAL') return { cls: 'badge-no', text: '部分正确', earned: selfGradeEarned(q) }
    if (q.selfGrade === 'WRONG') return { cls: 'badge-no', text: '错误', earned: 0 }
    return { cls: 'badge-skip', text: q.userAnswer ? '未自评' : '未作答', earned: 0 }
  }
  if (q.correct === true) return { cls: 'badge-ok', text: '正确', earned: q.score }
  if (q.correct === false) return { cls: 'badge-no', text: '错误', earned: 0 }
  if (q.selectedKeys?.length) return { cls: 'badge-skip', text: '未判', earned: 0 }
  return { cls: 'badge-skip', text: '未作答', earned: 0 }
}

const dockItems = computed(() =>
  (viewSession.value?.questions || []).map((q, i) => ({
    questionId: q.questionId,
    questionNumber: q.questionNumber ?? i + 1,
    status: dockStatus(q)
  }))
)

/* 答题卡窄视口收起（视口 ≤1680 右侧放不下 188px 悬浮盘（内容限宽 1440 联动）→ 收成"答题卡"小按钮，点击展开；
   浏览器缩放/窗口宽度变化都会触发 media query change，展开态自动跟随） */
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
    document
      .querySelector(`.review-card[data-qid="${questionId}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
  dockFlashTimer = setTimeout(() => {
    if (dockFlashId.value === questionId) dockFlashId.value = null
  }, 2600)
}

/* 交卷后自评赋分（对=满分 / 部分=一半 / 错=0） */
const gradingQid = ref(null)
const regradingQid = ref(null)

/* 主观题实得分：自由给分优先（selfScore），旧数据按自评档映射 */
function selfGradeEarned(q) {
  if (q.selfScore != null) return Math.min(q.score ?? 0, Math.max(0, q.selfScore))
  if (q.selfGrade === 'CORRECT') return q.score
  if (q.selfGrade === 'PARTIAL') return q.score / 2
  return 0
}

/* 赋分控件草稿（自由给分 0~满分，0.5 步进）；初始 = 当前实得 */
const gradeDrafts = reactive({})
function gradeVal(q) {
  if (gradeDrafts[q.questionId] !== undefined) return gradeDrafts[q.questionId]
  return selfScoreOf(q)
}
function selfScoreOf(q) {
  if (q.selfScore != null) return Math.min(q.score ?? 0, Math.max(0, q.selfScore))
  return selfGradeEarned(q)
}
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
    ElMessage.success(`自评已保存（${earned} / ${q.score} 分）`)
    delete gradeDrafts[q.questionId]
    regradingQid.value = null
    // 重新拉取会话详情刷新分数（报告/明细实时聚合）
    await openReview(viewSession.value.id, true)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    gradingQid.value = null
  }
}

function formatDuration(seconds) {
  if (seconds == null) return '—'
  const s = Math.max(0, seconds)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  return rest ? `${m} 分 ${rest} 秒` : `${m} 分钟`
}

onMounted(() => {
  // 总是预加载练习历史列表（防止从回顾返回列表时空白加载）
  loadSessions()
  // 支持 ?view=sessionId 直达回顾
  const viewId = route.query.view
  if (viewId) {
    openReview(Number(viewId))
  }
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  padding: 0;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 13px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: color var(--ease);
}
.back-link:hover {
  color: var(--accent-text);
}
.page-title {
  font-size: 26px;
}
.page-desc {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
}
.title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.page-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 13px;
  flex-wrap: wrap;
}
.dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--text-muted);
}

.mode-tag {
  font-size: 12px;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 2px 10px;
  flex-shrink: 0;
}
.status-tag {
  font-size: 12px;
  border-radius: 999px;
  padding: 2px 10px;
  flex-shrink: 0;
}
.status-done {
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success);
}
.status-doing {
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning);
}

/* 列表 */
.sess-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sess-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease);
}
.sess-row:hover {
  background: var(--bg-hover);
  border-color: var(--border-strong);
}
.sess-score {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.sess-answer,
.sess-time,
.sess-date {
  font-size: 12px;
  flex-shrink: 0;
}
.sess-date {
  margin-left: auto;
}
.sess-arrow {
  color: var(--text-muted);
  flex-shrink: 0;
}
.sess-row:hover .sess-arrow {
  color: var(--accent-text);
  transform: translateX(2px);
  transition: all var(--ease);
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 26px;
}

/* 回顾 */
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
/* 右侧悬浮答题卡：fixed 于内容右缘与视口右边界之间，回顾卡列表保持全宽不被挤压 */
.review-dock {
  position: fixed;
  top: 84px;
  right: 24px;
  width: 188px;
  z-index: 40;
}
/* 窄视口展开态（点击"答题卡"小按钮后）：面板下移避开按钮，浮于内容之上 */
.review-dock.dock-pop {
  top: 132px;
}
.review-dock.dock-pop :deep(.qnav-grid) {
  max-height: calc(100vh - 340px);
}
/* 答题卡点题号跳转：目标回顾卡短暂高亮 */
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

/* 共享材料（粉笔式卡片：标题栏 + 折叠） */
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

/* 其他 */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 90px 0;
  color: var(--text-muted);
  text-align: center;
}
.empty h3 {
  margin-top: 6px;
  color: var(--text-primary);
}
.empty .btn {
  margin-top: 10px;
}
.sk-line {
  height: 12px;
  border-radius: 6px;
  background: var(--bg-hover);
  flex: 1;
}
</style>
