<template>
  <div class="practice-page">
    <!-- 题库不存在 -->
    <div v-if="bankError" class="error-state">
      <TikuIcon name="file" :size="40" />
      <h3>{{ bankError }}</h3>
      <p class="text-secondary">{{ t('notFoundDesc') }}</p>
      <button class="btn btn-secondary" @click="$router.push('/')">{{ t('backToBanks') }}</button>
    </div>

    <!-- ============ 成绩报告（交卷后） ============ -->
    <template v-else-if="report">
      <header class="report-header">
        <div class="report-title-line">
          <button class="bar-btn" @click="$router.push(`/banks/${id}`)">
            <TikuIcon name="arrow-left" :size="14" />
            {{ t('backToBank') }}
          </button>
          <h1 class="report-title">{{ t('reportTitle') }}</h1>
          <span v-if="modeLabel" class="mode-tag">{{ modeLabel }}</span>
        </div>
        <div class="report-score">
          <span class="score-num">{{ formatScore(report.totalScore) }} / {{ formatScore(report.maxScore) }}</span>
          <span class="score-label">{{ t('scoreFull') }} · {{ formatDuration(report.totalSeconds) }}</span>
          <button class="btn btn-secondary btn-sm" @click="goReview">
            <TikuIcon name="list" :size="13" />
            {{ t('viewReview') }}
          </button>
        </div>
      </header>

      <div class="report-body">
        <!-- 待自评主观题（交卷后当场赋分，赋完总分实时更新） -->
        <div v-if="pendingSubjective.length" class="pending-banner">
          <TikuIcon name="sparkle" :size="14" />
          <span>{{ t('pendingSubjective', { n: pendingSubjective.length }) }}</span>
        </div>
        <div v-if="pendingSubjective.length" class="pending-grade">
          <div class="section-title"><h2>{{ t('subjectiveGrade') }}</h2></div>
          <div v-for="q in pendingSubjective" :key="q.questionId" class="pending-item">
            <div class="pending-q">
              <span class="mono">#{{ q.questionNumber ?? '—' }}</span>
              <span class="q-type type-subjective">{{ t('subjectiveType') }}</span>
              <span class="text-muted">{{ formatScore(q.score) }} {{ t('unitPoint') }}</span>
            </div>
            <div class="pending-content" v-html="richHtml(q.content)"></div>
            <div class="pending-row">
              <span class="text-muted">{{ t('myAnswer') }}：</span>
              <span class="pending-answer" v-html="richHtml(q.userAnswer)"></span>
            </div>
            <div class="pending-row">
              <span class="text-muted">{{ t('referenceAnswer') }}：</span>
              <span class="pending-answer" v-html="richHtml(q.referenceAnswer || t('none'))"></span>
            </div>
            <div class="pending-btns">
              <span class="text-muted">{{ t('selfScorePrompt', { s: formatScore(q.score) }) }}：</span>
              <el-slider
                :model-value="gradeVal(q)"
                :min="0"
                :max="q.score"
                :step="0.5"
                :disabled="gradingQid === q.questionId"
                style="width: 190px; margin: 0 4px"
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
                style="width: 92px"
                :disabled="gradingQid === q.questionId"
                @update:model-value="(v) => setDraft(q, v)"
              />
              <span class="text-muted mono">/ {{ formatScore(q.score) }}</span>
              <button class="btn btn-secondary btn-sm" :disabled="gradingQid === q.questionId" @click="quickGrade(q, q.score)">
                {{ t('fullMarks') }}
              </button>
              <button class="btn btn-primary btn-sm" :disabled="gradingQid === q.questionId" @click="gradePending(q)">
                {{ gradingQid === q.questionId ? t('saving') : t('saveScore') }}
              </button>
            </div>
          </div>
        </div>

        <div class="report-card">
          <div class="report-stats">
            <div class="stat">
              <span class="stat-num mono">{{ report.correctCount }} / {{ report.totalQuestions }}</span>
              <span class="stat-label">{{ t('statCorrect') }}</span>
            </div>
            <div class="stat">
              <span class="stat-num mono">{{ report.totalQuestions ? Math.round((report.correctCount / report.totalQuestions) * 100) : 0 }}%</span>
              <span class="stat-label">{{ t('statAccuracy') }}</span>
            </div>
            <div class="stat">
              <span class="stat-num mono">{{ formatDuration(report.totalSeconds) }}</span>
              <span class="stat-label">{{ t('statTime') }}</span>
            </div>
          </div>
        </div>

        <div class="report-questions">
          <div class="section-title"><h2>{{ t('detailTitle') }}</h2></div>
          <div v-for="(q, i) in report.questions" :key="q.questionId" class="report-item">
            <div class="report-row" @click="toggleReportOpen(q.questionId)">
              <span class="q-number mono">#{{ q.questionNumber ?? i + 1 }}</span>
              <span class="report-badge" :class="badgeClass(q)">{{ badgeText(q) }}</span>
              <span class="report-content">{{ q.content }}</span>
              <span class="report-meta text-muted mono">
                {{ formatScore(q.earnedScore ?? 0) }}/{{ formatScore(q.score) }} {{ t('unitPoint') }}
                <span v-if="q.seconds != null"> · {{ q.seconds }}s</span>
                <TikuIcon :name="reportOpen[q.questionId] ? 'chevron-up' : 'chevron-down'" :size="12" />
              </span>
            </div>
            <div v-if="reportOpen[q.questionId]" class="report-detail">
              <div v-if="q.questionType === 'SUBJECTIVE' && q.userAnswer" class="rd-row">
                <b>{{ t('myAnswer') }}：</b><span v-html="richHtml(q.userAnswer)"></span>
              </div>
              <div v-else-if="q.selectedKeys && q.selectedKeys.length" class="rd-row">
                <b>{{ t('myAnswer') }}：</b>{{ formatKeys(q.selectedKeys) }}
              </div>
              <div v-if="q.selfGrade" class="rd-row"><b>{{ t('selfGradeLbl') }}：</b>{{ t('earnedScore') }} {{ formatScore(q.earnedScore ?? 0) }} / {{ formatScore(q.score) }}</div>
              <div v-if="q.answerKeys && q.answerKeys.length" class="rd-row">
                <b>{{ t('correctAnswer') }}：</b>{{ formatKeys(q.answerKeys) }}
              </div>
              <div v-if="q.answerText" class="rd-row"><b>{{ t('answerLbl') }}：</b>{{ q.answerText }}</div>
              <div v-if="q.questionType === 'SUBJECTIVE' && q.referenceAnswer" class="rd-row">
                <b>{{ t('referenceAnswer') }}：</b><span v-html="richHtml(q.referenceAnswer)"></span>
              </div>
              <div v-if="q.analysis" class="rd-row"><b>{{ t('analysisLbl') }}：</b><span v-html="richHtml(q.analysis)"></span></div>
              <p
                v-if="!q.analysis && !q.answerText && !(q.answerKeys && q.answerKeys.length) && !q.referenceAnswer"
                class="text-muted"
              >{{ t('noAnalysis') }}</p>
              <!-- 单题 AI 辅助解析（交卷回顾时对错题/难题按需追问，可保存为正式解析） -->
              <QuestionAiAnalysis
                v-if="q.questionId"
                :question-id="q.questionId"
                :bank-id="Number(id)"
                @saved="onReportAnalysisSaved(q)"
              />
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- ============ 做题中：极简工具条 + 左右分栏 ============ -->
    <template v-else>
      <!-- 工具条（与做题无关信息最小化） -->
      <header class="practice-bar">
        <button class="bar-btn" :title="t('backToBank') + '：' + (bank?.name || '')" @click="$router.push(`/banks/${id}`)">
          <TikuIcon name="chevron-left" :size="16" />
          <span class="bar-back-text">{{ t('back') }}</span>
        </button>
        <span class="bar-name" :title="bank?.name">{{ bank?.name }}</span>
        <span v-if="modeLabel" class="mode-tag">{{ modeLabel }}</span>
        <span class="bar-grow"></span>
        <div class="bar-progress" :title="`已答 ${answeredCount} / ${total}`">
          <div class="bar-track">
            <div class="bar-fill" :style="{ width: progressPct + '%' }"></div>
          </div>
          <span class="bar-count mono">{{ answeredCount }}/{{ total }}</span>
        </div>
        <span v-if="sessionId && !report" class="bar-timer mono" title="本次作答用时">{{ formatDuration(elapsedSeconds) }}</span>
        <button v-if="sessionId" class="btn btn-primary btn-sm bar-finish" :disabled="finishing" @click="finish">
          {{ finishing ? '交卷中…' : '交卷' }}
        </button>
      </header>

      <div v-if="loading" class="body-loading">
        <div class="card tiku-skeleton">
          <div class="sk-line" style="width: 20%"></div>
          <div class="sk-line" style="width: 90%"></div>
          <div class="sk-line" style="width: 60%"></div>
        </div>
      </div>

      <!-- 引导：无会话直连（做题已并入会话制） -->
      <div v-else-if="freeMode" class="body-loading">
        <div class="empty">
          <TikuIcon name="file" :size="40" />
          <h3>请从题库详情开始做题</h3>
          <p class="text-secondary">{{ t('sessionModeTip') }}</p>
          <button class="btn btn-primary" @click="$router.push(`/banks/${id}`)">
            <TikuIcon name="chevron-left" :size="14" />
            {{ t('backToBank') }}
          </button>
        </div>
      </div>

      <!-- 加载失败（会话被删/服务异常） -->
      <div v-else-if="loadError" class="body-loading">
        <div class="empty">
          <TikuIcon name="file" :size="40" />
          <h3>加载失败</h3>
          <p class="text-secondary">{{ loadError }}</p>
          <button class="btn btn-primary" @click="$router.push(`/banks/${id}`)">{{ t('backToBank') }}</button>
        </div>
      </div>

      <!-- 空题库 -->
      <div v-else-if="questions.length === 0" class="body-loading">
        <div class="empty">
          <TikuIcon name="file" :size="40" />
          <h3>这个题库还没有题目</h3>
          <p class="text-secondary">先录入几道题，再来刷题吧</p>
          <button class="btn btn-primary" @click="$router.push({ path: `/banks/${id}`, query: { new: '1' } })">
            <TikuIcon name="plus" :size="14" />
            去录题
          </button>
        </div>
      </div>

      <div v-else class="practice-body">
        <!-- 左栏：材料（独立滚动，可折叠；无材料时整页做题） -->
        <aside
          v-if="current?.materialContent"
          class="material-pane"
          :class="{ collapsed: isMaterialCollapsed(current.materialId) }"
        >
          <div class="material-pane-head" @click="toggleMaterial(current.materialId)">
            <span class="material-pane-title">材料</span>
            <span class="material-pane-toggle">
              <TikuIcon :name="isMaterialCollapsed(current.materialId) ? 'chevron-down' : 'chevron-up'" :size="13" />
              {{ isMaterialCollapsed(current.materialId) ? '展开' : '收起' }}
            </span>
          </div>
          <div v-show="!isMaterialCollapsed(current.materialId)" class="material-pane-body" v-html="richHtml(current.materialContent)"></div>
        </aside>

        <!-- 右栏：题目 -->
        <section class="question-pane">
          <!-- 完成横幅 -->
          <div v-if="answeredCount === total" class="done-banner">
            <TikuIcon name="check" :size="14" />
            <span>
              {{ t('allAnsweredTip') }}
            </span>
          </div>

          <div class="card question-card">
            <div class="q-head">
              <span class="q-index mono">第 {{ current.questionNumber ?? currentIndex + 1 }} 题</span>
              <span class="q-type" :class="`type-${String(current.questionType).toLowerCase()}`">{{ current.typeLabel }}</span>
              <span class="q-score text-muted">{{ formatScore(current.score) }} {{ t('unitPoint') }}</span>
              <span class="bar-grow"></span>
              <button
                class="fav-btn"
                :class="{ active: current.favorite }"
                :title="current.favorite ? '取消收藏' : '收藏此题'"
                @click="toggleFavorite"
              >
                <TikuIcon name="star" :size="16" :filled="current.favorite" />
              </button>
            </div>

            <div class="q-content" v-html="richHtml(current.content)"></div>

            <!-- 选项：单选 / 多选（客观题；做题中不判题，可随时修改） -->
            <div v-if="current.questionType === 'SINGLE' || current.questionType === 'MULTIPLE'" class="options">
              <button
                v-for="opt in current.options"
                :key="opt.key"
                class="opt-row"
                :class="optClass(opt.key)"
                @click="toggleOption(opt.key)"
              >
                <span class="opt-key">{{ opt.key }}</span>
                <span class="opt-text" v-html="richHtml(opt.text)"></span>
                <TikuIcon v-if="isSelected(opt.key)" name="check" :size="15" class="opt-check" />
              </button>
            </div>

            <!-- 判断 -->
            <div v-else-if="current.questionType === 'JUDGE'" class="judge-options">
              <button class="judge-btn" :class="{ selected: isSelected('A') }" @click="setJudge('A')">
                <TikuIcon name="check" :size="16" /> 正确
              </button>
              <button class="judge-btn" :class="{ selected: isSelected('B') }" @click="setJudge('B')">
                <TikuIcon name="x" :size="16" /> 错误
              </button>
            </div>

            <!-- 主观题：textarea 作答（可随时修改，交卷统一判分后自评） -->
            <div v-else class="subjective-box">
              <el-input
                v-model="userAnswers[current.questionId]"
                type="textarea"
                :rows="5"
                placeholder="输入你的作答（支持 [图片:文件名] 标记）"
              />
            </div>

            <!-- 作答状态提示（不判题、不显示答案，交卷后统一呈现） -->
            <div class="submit-row">
              <template v-if="!answeredLocal">
                <span class="hint text-muted">
                  <template v-if="current.questionType === 'MULTIPLE'">{{ t('multiTip') }}</template>
                  <template v-else-if="current.questionType === 'SUBJECTIVE'">{{ t('subjTip') }}</template>
                  <template v-else>未作答，可直接跳过</template>
                </span>
              </template>
              <template v-else>
                <span class="hint text-muted">
                  <template v-if="current.questionType === 'SUBJECTIVE'">已输入作答</template>
                  <template v-else>已选 {{ selection.length }} 项</template>
                  · 交卷前可修改
                </span>
                <span class="bar-grow"></span>
                <button class="btn btn-ghost btn-sm" @click="toggleSuspend">
                  {{ isSuspended ? '已暂停复习 · 点击恢复' : '不再复习此题' }}
                </button>
              </template>
            </div>
          </div>

          <!-- 导航（吸底，常驻视野；切题不判题、不提交） -->
          <div class="pane-nav">
            <button class="btn btn-secondary btn-sm" :disabled="currentIndex === 0" @click="goPrev">
              <TikuIcon name="chevron-left" :size="13" />
              上一题
            </button>
            <span class="bar-grow"></span>
            <button class="btn btn-primary btn-sm" :disabled="currentIndex >= total - 1" @click="goNext">
              下一题
              <TikuIcon name="chevron-right" :size="13" />
            </button>
          </div>
          <div class="kb-hint text-muted" :title="kbHint">{{ kbHint }}</div>
        </section>
      </div>

      <!-- 数字直达浮标（输入题号跳题，800ms 无输入自动跳转） -->
      <div v-if="jumpBuf" class="jump-chip mono">跳转 #{{ jumpBuf }}</div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      notFoundDesc: '题库可能已被删除，或地址有误', backToBanks: '返回题库列表', backToBank: '返回题库',
      reportTitle: '成绩报告', scoreFull: '得分 / 满分', viewReview: '查看详细回顾',
      pendingSubjective: '有 {n} 道主观题待自评赋分，自评后总分自动更新',
      subjectiveGrade: '主观题自评', subjectiveType: '主观题', myAnswer: '我的作答', referenceAnswer: '参考答案',
      none: '（无）', selfScorePrompt: '自评得分（0 ~ {s} 分）', fullMarks: '全对', saving: '保存中…', saveScore: '保存得分',
      unitPoint: '分', sessionModeTip: '做题采用会话制：抽一组题全部作答后，交卷统一判分', allAnsweredTip: '全部作答完成 · 可返回检查修改，点击右上角「交卷」统一判分', multiTip: '可多选 · 作答后交卷统一判分', subjTip: '输入后交卷统一判分，可随时修改',
      statCorrect: '答对 / 总题数', statAccuracy: '正确率', statTime: '总用时', detailTitle: '每题明细（点击展开答案与解析）',
      selfGradeLbl: '自评', earnedScore: '实得', correctAnswer: '正确答案', answerLbl: '答案', analysisLbl: '解析',
      noAnalysis: '本题没有附加答案文字与解析',
      msgUnansweredConfirm: '还有 {n} 道题未作答，交卷后未答题按 0 分计且无法再作答。确定交卷吗？',
      msgSubmitTitle: '交卷确认',
      msgSubmitted: '已交卷',
      msgPendingSelfGrade: '还有 {n} 道主观题待自评，可在成绩页赋分',
      msgSelfGradeSaved: '自评已保存（{earned} / {total} 分），成绩已更新',
      msgAllSelfGraded: '全部主观题已自评，本次成绩完整',
      msgFavorited: '已收藏',
      msgUnfavorited: '已取消收藏',
      msgReviewSuspended: '已暂停此题复习',
      msgReviewResumed: '已恢复此题复习',
      msgNoQuestionN: '会话中没有第 {n} 题'
    },
    'en-US': {
      notFoundDesc: 'This bank may have been deleted, or the link is wrong', backToBanks: 'Back to banks', backToBank: 'Back to bank',
      reportTitle: 'Score Report', scoreFull: 'Score / Full', viewReview: 'View detailed review',
      pendingSubjective: '{n} subjective question(s) await self-grading — the total updates automatically',
      subjectiveGrade: 'Grade subjective questions', subjectiveType: 'Subjective', myAnswer: 'My answer', referenceAnswer: 'Reference answer',
      none: '(none)', selfScorePrompt: 'Self score (0 ~ {s})', fullMarks: 'Full marks', saving: 'Saving…', saveScore: 'Save score',
      unitPoint: 'pts', sessionModeTip: 'Session-based: answer a set, then submit for unified scoring', allAnsweredTip: 'All answered — you can go back to check, then click Submit (top right) for scoring', multiTip: 'Multiple answers allowed · submitted for scoring at the end', subjTip: 'Type your answer; submitted for scoring at the end, editable anytime',
      statCorrect: 'Correct / total', statAccuracy: 'Accuracy', statTime: 'Total time', detailTitle: 'Question details (click to expand answers & analysis)',
      selfGradeLbl: 'Self-grade', earnedScore: 'Earned', correctAnswer: 'Correct answer', answerLbl: 'Answer', analysisLbl: 'Analysis',
      noAnalysis: 'No extra answer text or analysis for this question',
      msgUnansweredConfirm: '{n} question(s) not answered — after submitting they count as 0 and cannot be answered again. Submit anyway?',
      msgSubmitTitle: 'Confirm submission',
      msgSubmitted: 'Submitted',
      msgPendingSelfGrade: '{n} subjective question(s) await self-grading — you can grade them on the report page',
      msgSelfGradeSaved: 'Self-grade saved ({earned} / {total} pts) — score updated',
      msgAllSelfGraded: 'All subjective questions graded — your score for this session is complete',
      msgFavorited: 'Added to favorites',
      msgUnfavorited: 'Removed from favorites',
      msgReviewSuspended: 'Review paused for this question',
      msgReviewResumed: 'Review resumed for this question',
      msgNoQuestionN: 'Question {n} is not in this session'
    }
  }
})
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getBank } from '../api/banks'
import { finishSession, getSessionDetail } from '../api/sessions'
import { selfGradeRecord, setReviewSuspended } from '../api/studyRecords'
import { setFavorite } from '../api/questions'
import { formatScore } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import TikuIcon from '../components/TikuIcon.vue'
import QuestionAiAnalysis from '../components/QuestionAiAnalysis.vue'

const route = useRoute()
const router = useRouter()
const id = route.params.id
const sessionId = route.query.sessionId ? Number(route.query.sessionId) : null
const atIndex = route.query.at ? Number(route.query.at) : 0

const bank = ref(null)
const bankError = ref('')

const questions = ref([])
const loading = ref(true)
const total = ref(0)
const sessionMode = ref('')

/* ---------- 做题计时器（第十轮可选：会话创建时刻起算，实时显示） ---------- */
const elapsedSeconds = ref(0)
let elapsedTimer = null
function startElapsedTimer(startAt) {
  stopElapsedTimer()
  const start = startAt ? new Date(startAt).getTime() : Date.now()
  elapsedSeconds.value = 0
  elapsedTimer = setInterval(() => {
    elapsedSeconds.value = Math.max(0, Math.round((Date.now() - start) / 1000))
  }, 1000)
}
function stopElapsedTimer() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}
onUnmounted(stopElapsedTimer)

const MODE_LABELS = {
  ALL: '全部随机',
  SEQUENCE: '顺序刷题',
  TOPIC: '按分类',
  REVIEW: '复习队列',
  WRONG: '错题重做',
  FAVORITE: '收藏'
}
const modeLabel = computed(() => (sessionMode.value ? MODE_LABELS[sessionMode.value] || sessionMode.value : ''))

const currentIndex = ref(0)
const finishing = ref(false)

// 交卷成绩报告（渲染成绩视图后隐藏做题 UI）
const report = ref(null)
// 交卷后待自评的主观题（题干/作答/参考答案，来自会话详情）
const pendingSubjective = ref([])
const gradingQid = ref(null)
// 报告每题展开（答案与解析）
const reportOpen = reactive({})
function toggleReportOpen(qid) {
  reportOpen[qid] = !reportOpen[qid]
}

// AI 解析保存成功：本地更新该题解析展示
function onReportAnalysisSaved(q, text) {
  if (q && text) {
    q.analysis = text
  }
}

// 无会话直连（旧自由模式已并入会话制）/ 加载失败引导态
const freeMode = ref(false)
const loadError = ref('')

// 每题的作答选择：questionId -> [keys]（客观题；做题中不判题，交卷统一提交）
const selections = reactive({})
// 主观题作答：questionId -> text
const userAnswers = reactive({})
// 每题复习暂停状态：questionId -> bool（本地乐观展示）
const suspendedMap = reactive({})
// 材料折叠状态（左栏材料可收起）
const collapsedMaterials = reactive(new Set())
const isMaterialCollapsed = (mid) => collapsedMaterials.has(mid)
function toggleMaterial(mid) {
  if (collapsedMaterials.has(mid)) collapsedMaterials.delete(mid)
  else collapsedMaterials.add(mid)
}

// 每题累计用时（秒）：进入题目计时、离开结算；交卷时随答案提交落库
const perQuestionSeconds = reactive({})
let questionEnterAt = Date.now()
function leaveCurrentQuestion() {
  if (!current.value) return
  const qid = current.value.questionId
  const dt = Math.round((Date.now() - questionEnterAt) / 1000)
  if (dt > 0) perQuestionSeconds[qid] = (perQuestionSeconds[qid] || 0) + dt
}
watch(currentIndex, () => {
  questionEnterAt = Date.now()
})

const current = computed(() => questions.value[currentIndex.value])
const selection = computed(() => (current.value ? (selections[current.value.questionId] || []) : []))
const isSuspended = computed(() => (current.value ? !!suspendedMap[current.value.questionId] : false))

// 本地"已作答"判定（不依赖服务端判题）：客观题已选、主观题已输入
function isAnsweredQuestion(qid, type) {
  if (type === 'SUBJECTIVE') return !!((userAnswers[qid] || '').trim())
  return !!((selections[qid] || []).length)
}
const answeredLocal = computed(() =>
  current.value ? isAnsweredQuestion(current.value.questionId, current.value.questionType) : false
)
const answeredCount = computed(() =>
  questions.value.filter((q) => isAnsweredQuestion(q.questionId, q.questionType)).length
)
const progressPct = computed(() => (total.value ? Math.round((answeredCount.value / total.value) * 100) : 0))

const isSelected = (key) => selection.value.includes(key)
const richHtml = (text) => richTextToHtml(text, id)

async function loadAll() {
  try {
    bank.value = await getBank(id)
  } catch (e) {
    bankError.value = e.message || '题库不存在'
    return
  }
  try {
    if (!sessionId) {
      //无会话直连：旧"自由模式"已并入会话制，引导从题库详情开始
      freeMode.value = true
      loading.value = false
      return
    }
    const detail = await getSessionDetail(sessionId)
    sessionMode.value = detail.mode || ''
    questions.value = detail.questions || []
    total.value = Number(detail.questionCount || detail.questions?.length || 0)
    if (detail.status !== 'COMPLETED') {
      //做题中：统一判分模型下服务端没有逐题记录，恢复完全依赖本地草稿 + 位置
      startElapsedTimer(detail.createdAt)
      restoreDraft()
      const start = Math.min(atIndex || 0, Math.max(0, total.value - 1))
      currentIndex.value = start
    } else {
      //已交卷会话（重新进入）：直接展示成绩报告（含答案与解析）
      report.value = reportFromDetail(detail)
      pendingSubjective.value = (detail.questions || []).filter(
        (q) => q.questionType === 'SUBJECTIVE' && q.userAnswer && !q.selfGrade
      )
    }
  } catch (e) {
    questions.value = []
    loadError.value = e?.response?.status === 404 ? '会话不存在或已被删除' : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 会话详情 → 报告模型（每题补 earnedScore；主观题按自评：对=满分/部分=一半/错与未评=0） */
function reportFromDetail(detail) {
  return {
    sessionId: detail.id,
    totalQuestions: detail.questionCount,
    answeredCount: detail.answeredCount,
    correctCount: detail.correctCount,
    totalScore: detail.totalScore,
    maxScore: detail.maxScore,
    totalSeconds: detail.totalSeconds,
    questions: (detail.questions || []).map((q) => {
      let earned = 0
      if (q.selfGrade === 'CORRECT') earned = q.score
      else if (q.selfGrade === 'PARTIAL') earned = q.score / 2
      else if (q.correct === true) earned = q.score
      return { ...q, earnedScore: earned }
    })
  }
}

/* ---------- 刷新恢复：当前位置同步到 URL at 参数（会话模式） ---------- */
function syncPosition() {
  if (report.value || !sessionId) return
  router.replace({
    path: route.path,
    query: { ...route.query, at: String(currentIndex.value) }
  })
}
watch(currentIndex, syncPosition)

/* ---------- 刷新恢复：作答草稿（会话模式，sessionStorage） ---------- */
const draftKey = () => (sessionId ? `tiku:practice-draft:${sessionId}` : '')

function saveDraft() {
  if (!sessionId || report.value) return
  try {
    sessionStorage.setItem(
      draftKey(),
      JSON.stringify({ selections: { ...selections }, userAnswers: { ...userAnswers } })
    )
  } catch (e) {
    /* 存储不可用（隐私模式/配额）时静默降级 */
  }
}

function clearDraft() {
  try {
    sessionStorage.removeItem(draftKey())
  } catch (e) {
    /* 忽略 */
  }
}

watch([selections, userAnswers], saveDraft, { deep: true })

function restoreDraft() {
  let raw = null
  try {
    raw = sessionStorage.getItem(draftKey())
  } catch (e) {
    return
  }
  if (!raw) return
  try {
    const d = JSON.parse(raw)
    if (!d || typeof d !== 'object') return
    for (const q of questions.value) {
      const qid = q.questionId
      if (d.selections && Array.isArray(d.selections[qid])) selections[qid] = d.selections[qid]
      if (d.userAnswers && typeof d.userAnswers[qid] === 'string' && d.userAnswers[qid]) {
        userAnswers[qid] = d.userAnswers[qid]
      }
    }
  } catch (e) {
    /* 坏数据直接丢弃 */
  }
}

/* ---------- 作答（客观题；做题中不判题，可随时修改） ---------- */
function toggleOption(key) {
  if (current.value.questionType === 'SINGLE') {
    selections[current.value.questionId] = [key]
  } else {
    const cur = selections[current.value.questionId] || []
    selections[current.value.questionId] = cur.includes(key) ? cur.filter((k) => k !== key) : [...cur, key]
  }
}

function setJudge(key) {
  selections[current.value.questionId] = [key]
}

function optClass(key) {
  return isSelected(key) ? 'selected' : ''
}

const gradeText = (g) => (g === 'CORRECT' ? '自评：回答正确' : g === 'PARTIAL' ? '自评：部分正确' : '自评：回答错误')

/* ---------- 交卷（统一判分：一次性提交全部作答 → 报告） ---------- */
async function finish() {
  if (!sessionId || finishing.value) return
  //交卷确认：未答题交卷后按 0 分计且无法再作答（不可逆操作前提示，防误触空卷交卷）
  const unanswered = total.value - answeredCount.value
  if (unanswered > 0) {
    try {
      await ElMessageBox.confirm(
        t('msgUnansweredConfirm', { n: unanswered }),
        t('msgSubmitTitle'),
        {
          type: 'warning',
          confirmButtonText: '交卷',
          cancelButtonText: '继续做题'
        }
      )
    } catch (e) {
      return // 用户选择继续做题
    }
  }
  finishing.value = true
  try {
    leaveCurrentQuestion() //结算当前题用时
    const answers = collectAnswers()
    //一次性提交全部最终作答（后端事务内原子落库 + 判分），失败整体回滚可安全重试
    await finishSession(sessionId, answers)
    clearDraft() // 交卷成功：清除会话草稿
    ElMessage.success(t('msgSubmitted'))
    //拉取报告（含答案与解析）；偶发失败重试一次，避免"已交卷但停在做题界面"再点交卷被 400 拒绝
    for (let attempt = 0; attempt < 2 && !report.value; attempt++) {
      await refreshReport()
    }
    if (pendingSubjective.value.length) {
      ElMessage.info(t('msgPendingSelfGrade', { n: pendingSubjective.value.length }))
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    finishing.value = false
  }
}

/** 收集全部已作答题目（含本地累计用时） */
function collectAnswers() {
  const answers = []
  for (const q of questions.value) {
    const qid = q.questionId
    const seconds = perQuestionSeconds[qid] ?? null
    if (q.questionType === 'SUBJECTIVE') {
      const text = (userAnswers[qid] || '').trim()
      if (text) answers.push({ questionId: qid, userAnswer: text, seconds })
    } else {
      const keys = selections[qid] || []
      if (keys.length) answers.push({ questionId: qid, selectedKeys: keys, seconds })
    }
  }
  return answers
}

/** 从会话详情重建报告（含答案与解析）与待自评列表 */
async function refreshReport() {
  if (!sessionId) return
  try {
    const detail = await getSessionDetail(sessionId)
    report.value = reportFromDetail(detail)
    pendingSubjective.value = (detail.questions || []).filter(
      (q) => q.questionType === 'SUBJECTIVE' && q.userAnswer && !q.selfGrade
    )
  } catch (e) {
    report.value = null
  }
}

/* 成绩页主观题自评：自由给分（0~满分，0.5 步进）；赋分后刷新总分与待自评列表 */
const gradeDrafts = reactive({})
function gradeVal(q) {
  return gradeDrafts[q.questionId] ?? 0
}
function setDraft(q, v) {
  gradeDrafts[q.questionId] = v
}
async function quickGrade(q, earned) {
  setDraft(q, earned)
  await gradePending(q)
}
async function gradePending(q) {
  if (!q.recordId || gradingQid.value) return
  const earned = gradeVal(q)
  gradingQid.value = q.questionId
  try {
    await selfGradeRecord(q.recordId, earned)
    ElMessage.success(t('msgSelfGradeSaved', { earned, total: q.score }))
    delete gradeDrafts[q.questionId]
    await refreshReport()
    if (!pendingSubjective.value.length) {
      ElMessage.success(t('msgAllSelfGraded'))
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    gradingQid.value = null
  }
}

function goReview() {
  router.push({ path: `/banks/${id}/sessions`, query: { view: report.value?.sessionId } })
}

/* ---------- 收藏 ---------- */
async function toggleFavorite() {
  const q = current.value
  if (!q) return
  const next = !q.favorite
  try {
    await setFavorite(q.questionId, next)
    q.favorite = next
    ElMessage.success(next ? t('msgFavorited') : t('msgUnfavorited'))
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* ---------- 不再复习此题 ---------- */
async function toggleSuspend() {
  const q = current.value
  if (!q) return
  const next = !isSuspended.value
  try {
    await setReviewSuspended(q.questionId, next)
    suspendedMap[q.questionId] = next
    ElMessage.success(next ? t('msgReviewSuspended') : t('msgReviewResumed'))
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* ---------- 导航（切题不判题不提交；只结算当前题用时） ---------- */
function goPrev() {
  if (currentIndex.value === 0) return
  leaveCurrentQuestion()
  currentIndex.value--
}
function goNext() {
  if (currentIndex.value >= questions.value.length - 1) return
  leaveCurrentQuestion()
  currentIndex.value++
}

/* ========== 键盘快捷键（A2）：字母选答案 · ←/→ 与数字切题 · Enter 交卷 ========== */
// 数字直达缓冲（连续输入题号，800ms 无输入自动跳转）
const jumpBuf = ref('')
let jumpTimer = null
const jumpTimeoutMs = 800

const kbHint = computed(() => {
  const q = current.value
  if (!q) return ''
  const sel =
    q.questionType === 'JUDGE'
      ? 'A/B 选 正确/错误'
      : q.questionType === 'MULTIPLE'
        ? 'A~H 勾选选项'
        : 'A~H 选择答案'
  return `${sel} · ←/→ 切题 · 数字直达题号 · Enter 交卷`
})

// 做题可用状态（报告/加载/引导/空态/交卷中都不响应快捷键）
function practiceReady() {
  return (
    !report.value && !loading.value && !freeMode.value && !loadError.value && questions.value.length > 0 && !finishing.value
  )
}

function clearJumpBuf() {
  jumpBuf.value = ''
  if (jumpTimer) {
    clearTimeout(jumpTimer)
    jumpTimer = null
  }
}

function pushJumpDigit(d) {
  jumpBuf.value = (jumpBuf.value + d).slice(0, 4)
  if (jumpTimer) clearTimeout(jumpTimer)
  jumpTimer = setTimeout(execJump, jumpTimeoutMs)
}

function execJump() {
  const n = parseInt(jumpBuf.value, 10)
  clearJumpBuf()
  if (!Number.isFinite(n) || n <= 0) return
  //优先按题号（questionNumber）找；找不到时按会话内顺序第 n 题兜底
  let idx = questions.value.findIndex((q) => q.questionNumber === n)
  if (idx < 0 && n >= 1 && n <= questions.value.length) idx = n - 1
  if (idx < 0) {
    ElMessage.info(t('msgNoQuestionN', { n }))
    return
  }
  if (idx === currentIndex.value) return
  leaveCurrentQuestion()
  currentIndex.value = idx
}

// Enter = 交卷（与「交卷」按钮一致；未答完时 finish 内部会先弹确认）——
// 捕获阶段接管，避免焦点在按钮上时 Enter 触发按钮默认点击造成双动作
let enterLocked = false
async function enterFinish() {
  if (enterLocked || finishing.value || !practiceReady()) return
  enterLocked = true
  try {
    await finish()
  } finally {
    enterLocked = false
  }
}

function isEditableTarget(e) {
  const t = e.target
  if (!t || !t.tagName) return false
  const tag = String(t.tagName)
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || !!t.isContentEditable
}

function onKeydown(e) {
  if (e.ctrlKey || e.metaKey || e.altKey) return
  //输入框/文本域内打字、或任何弹层（确认框等）打开时不抢键盘
  if (isEditableTarget(e)) return
  if (document.querySelector('.el-overlay')) return
  if (!practiceReady()) return
  const q = current.value
  if (!q) return

  const k = e.key
  if (k.length === 1) {
    if (k >= '0' && k <= '9') {
      e.preventDefault()
      pushJumpDigit(k)
      return
    }
    const upper = k.toUpperCase()
    if (q.questionType === 'JUDGE' && (upper === 'A' || upper === 'B')) {
      e.preventDefault()
      setJudge(upper)
      return
    }
    if (
      (q.questionType === 'SINGLE' || q.questionType === 'MULTIPLE') &&
      q.options &&
      q.options.some((o) => o && o.key === upper)
    ) {
      e.preventDefault()
      toggleOption(upper)
      return
    }
    return
  }
  if (k === 'ArrowLeft') {
    e.preventDefault()
    goPrev()
  } else if (k === 'ArrowRight') {
    e.preventDefault()
    goNext()
  } else if (k === 'Enter') {
    e.preventDefault()
    enterFinish()
  } else if (k === 'Escape') {
    clearJumpBuf()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown, true))
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown, true)
  clearJumpBuf()
})

/* ---------- 报告辅助 ---------- */
function badgeClass(q) {
  if (q.correct === true || q.selfGrade === 'CORRECT') return 'badge-ok'
  if (q.correct === false || q.selfGrade === 'WRONG' || q.selfGrade === 'PARTIAL') return 'badge-no'
  return 'badge-skip'
}
function badgeText(q) {
  if (q.selfGrade === 'CORRECT') return '正确'
  if (q.selfGrade === 'PARTIAL') return '部分正确'
  if (q.selfGrade === 'WRONG') return '错误'
  if (q.correct === true) return '正确'
  if (q.correct === false) return '错误'
  if (q.correct === null && q.userAnswer) return '未自评'
  //客观题已作答但题目未配置答案 → 待定（不判对错，可到题库编辑补答案）
  if (q.correct === null && q.selectedKeys?.length && q.questionType !== 'SUBJECTIVE') return '未判'
  return '未答'
}

const formatKeys = (keys) => (keys.length ? keys.join('、') : '—')

function formatDuration(seconds) {
  if (seconds == null) return '—'
  const s = Math.max(0, seconds)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  return rest ? `${m} 分 ${rest} 秒` : `${m} 分钟`
}

loadAll()
</script>

<style scoped>
/* ============ 全屏容器（main.compact 提供无内边距） ============ */
.practice-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-base);
}

/* ============ 极简工具条 ============ */
.practice-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 48px;
  padding: 0 14px;
  background: var(--bg-elev);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.bar-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--ease);
  white-space: nowrap;
}
.bar-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.bar-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mode-tag {
  font-size: 12px;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 1px 8px;
  flex-shrink: 0;
}
.bar-grow {
  flex: 1;
}
.bar-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bar-timer {
  font-size: 12px;
  color: var(--text-muted);
  min-width: 58px;
  text-align: right;
}
.bar-track {
  width: 120px;
  height: 5px;
  border-radius: 999px;
  background: var(--bg-card);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
  transition: width var(--ease-slow);
}
.bar-count {
  font-size: 12px;
  color: var(--text-secondary);
  min-width: 40px;
  text-align: right;
}
.bar-finish {
  height: 30px;
}

/* ============ 主体分栏 ============ */
.practice-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
/* 窄视口兜底：允许做题区内部横向滚动，避免内容被裁 */
@media (max-width: 900px) {
  .practice-body {
    overflow-x: auto;
  }
}
.body-loading {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}

/* ---- 左栏：材料（独立滚动） ---- */
.material-pane {
  width: 42%;
  min-width: 300px;
  max-width: 560px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: var(--bg-elev);
  transition: width 200ms ease;
  flex-shrink: 0;
}
.material-pane.collapsed {
  width: 52px;
  min-width: 52px;
  max-width: 52px;
}
.material-pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
  transition: background var(--ease);
  white-space: nowrap;
}
.material-pane-head:hover {
  background: var(--bg-hover);
}
.material-pane.collapsed .material-pane-head {
  justify-content: center;
  padding: 10px 0;
}
.material-pane-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-text);
}
.material-pane-title::before {
  content: '';
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--accent);
}
.material-pane.collapsed .material-pane-title {
  writing-mode: vertical-rl;
  letter-spacing: 0.2em;
}
.material-pane.collapsed .material-pane-title::before {
  display: none;
}
.material-pane-toggle {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  color: var(--text-muted);
}
.material-pane.collapsed .material-pane-toggle {
  display: none;
}
.material-pane-body {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 14px 16px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.material-pane-body :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 8px 0;
  display: block;
}

/* ---- 右栏：题目 ---- */
.question-pane {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 16px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.done-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--success-soft);
  border: 1px solid var(--success);
  color: var(--success);
  font-size: 13px;
}
.done-banner b {
  font-weight: 600;
}

.card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 20px 22px;
}
.q-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.q-index {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
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
.q-type.type-subjective {
  background: var(--type-subj-bg);
  color: var(--type-subj-fg);
  border-color: var(--type-subj-fg);
}
.q-score {
  font-size: 12px;
}
.fav-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  background: transparent;
  border-radius: 8px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--ease);
}
.fav-btn:hover {
  background: var(--bg-hover);
  color: var(--warning);
}
.fav-btn.active {
  color: var(--warning);
}
.q-content {
  margin: 0 0 18px;
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.q-content :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 8px 0;
  display: block;
}

/* 选项 */
.options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.opt-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 15px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease);
}
.opt-row:hover:not(:disabled) {
  border-color: var(--border-strong);
}
.opt-row:disabled {
  cursor: default;
}
.opt-row.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.opt-key {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
}
.opt-row.selected .opt-key {
  border-color: var(--accent);
  color: var(--accent-text);
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
.opt-check {
  flex-shrink: 0;
  color: var(--accent);
}
/* 旧“逐题判分”遗留死代码区（以下 .is-correct/.is-wrong/.result* 与 .sg-* 等选择器无模板引用，保留待删，见 doc/redesign-audit/03） */
.opt-row.is-correct {
  border-color: var(--success);
  background: var(--success-soft);
}
.opt-row.is-correct .opt-key {
  border-color: var(--success);
  color: var(--success);
}
.opt-row.is-correct .opt-check {
  color: var(--success);
}
.opt-row.is-wrong {
  border-color: var(--danger);
  background: var(--danger-soft);
}
.opt-row.is-wrong .opt-key {
  border-color: var(--danger);
  color: var(--danger);
}
.opt-row.is-wrong .opt-check {
  color: var(--danger);
}

/* 判断题 */
.judge-options {
  display: flex;
  gap: 14px;
}
.judge-btn {
  flex: 1;
  max-width: 180px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 13px 0;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--ease);
}
.judge-btn:hover:not(:disabled) {
  border-color: var(--border-strong);
}
.judge-btn:disabled {
  cursor: default;
}
.judge-btn.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-text);
}
.judge-btn.is-correct {
  border-color: var(--success);
  background: var(--success-soft);
  color: var(--success);
}

/* 主观题 */
.subjective-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.subjective-result {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--bg-elev);
  border: 1px solid var(--border-strong);
}
.sub-ref-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-text);
  margin-bottom: 8px;
}
.sub-ref-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.sub-ref-body :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 6px 0;
  display: block;
}
.self-grade-result {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 13px;
  border-radius: 10px;
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
.sg-badge {
  font-size: 13px;
  font-weight: 600;
}
.sg-score {
  font-size: 13px;
}
.self-grade-hint {
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.7;
}

/* 提交提示行 */
.submit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  gap: 12px;
  flex-wrap: wrap;
}
.hint {
  font-size: 12px;
}

/* 客观题判题结果 */
.result {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid;
}
.result-correct {
  background: var(--success-soft);
  border-color: var(--success);
}
.result-wrong {
  background: var(--danger-soft);
  border-color: var(--danger);
}
.result-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.result-badge {
  font-size: 13px;
  font-weight: 600;
}
.result-correct .result-badge { color: var(--success); }
.result-wrong .result-badge { color: var(--danger); }
.result-keys {
  font-size: 13px;
  color: var(--text-secondary);
}
.result-text,
.result-analysis {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.result-text b,
.result-analysis b {
  color: var(--text-secondary);
  font-weight: 500;
}
.result-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

/* 导航（吸底） */
.pane-nav {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0 2px;
  background: var(--bg-base);
}

/* 快捷键提示（导航下方一行小字，鼠标点击做题的用户可忽略） */
.kb-hint {
  font-size: 12px;
  text-align: right;
  padding: 0 2px 10px;
  user-select: none;
}

/* 数字直达浮标（连续输入题号跳题，800ms 无输入自动跳转） */
.jump-chip {
  position: fixed;
  left: 50%;
  bottom: 96px;
  transform: translateX(-50%);
  z-index: 200;
  padding: 6px 14px;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.22);
  pointer-events: none;
  animation: jump-pop 0.14s ease-out;
}
@keyframes jump-pop {
  from {
    transform: translateX(-50%) scale(0.92);
    opacity: 0.6;
  }
  to {
    transform: translateX(-50%) scale(1);
    opacity: 1;
  }
}

/* ============ 成绩报告 ============ */
/* 待自评主观题 */
.pending-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 14px;
  border-radius: 10px;
  background: var(--warning-soft);
  border: 1px solid var(--warning);
  color: var(--warning);
  font-size: 13px;
  margin-bottom: 14px;
}
.pending-banner b {
  font-weight: 600;
}
.pending-grade {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 20px;
}
.pending-item {
  padding: 14px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  border-radius: 12px;
}
.pending-q {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.pending-content {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 10px;
}
.pending-content :deep(.rich-img),
.pending-answer :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 6px 0;
  display: block;
}
.pending-row {
  display: flex;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 6px;
  align-items: baseline;
}
.pending-answer {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.pending-btns {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.report-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 52px;
  padding: 0 16px;
  background: var(--bg-elev);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.report-title-line {
  display: flex;
  align-items: center;
  gap: 10px;
}
.report-title {
  font-size: 17px;
  font-weight: 600;
}
.report-score {
  display: flex;
  align-items: center;
  gap: 12px;
}
.score-num {
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--accent-text);
}
.score-label {
  font-size: 12px;
  color: var(--text-muted);
}
.report-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
}
.report-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 18px 22px;
  margin-bottom: 20px;
}
.report-stats {
  display: flex;
  gap: 48px;
  flex-wrap: wrap;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-num {
  font-size: 20px;
  font-weight: 600;
  color: var(--accent-text);
}
.stat-label {
  font-size: 12px;
  color: var(--text-muted);
}
.report-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-title h2 {
  font-size: 16px;
  margin-bottom: 10px;
}
.report-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.report-badge {
  flex-shrink: 0;
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
.report-content {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.report-meta {
  flex-shrink: 0;
  font-size: 12px;
}

/* ============ 其他 ============ */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 70px 0;
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
.error-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 90px 0;
  color: var(--text-muted);
  text-align: center;
  overflow-y: auto;
}
.error-state h3 {
  color: var(--text-primary);
  font-size: 17px;
}
.error-state .btn {
  margin-top: 12px;
}
.sk-line {
  height: 12px;
  border-radius: 6px;
  background: var(--bg-hover);
  margin-bottom: 14px;
}
</style>
