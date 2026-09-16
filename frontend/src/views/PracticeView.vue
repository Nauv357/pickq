<template>
  <div class="practice-page">
    <!-- 题库不存在 -->
    <div v-if="bankError" class="error-state">
      <TikuIcon name="file" :size="40" />
      <h3>{{ bankError }}</h3>
      <p class="text-secondary">{{ t('notFoundDesc') }}</p>
      <button class="btn btn-secondary" @click="$router.push('/')">{{ t('backToBanks') }}</button>
    </div>

    <!-- ============ 成绩报告（交卷后） ============
         逐题回顾由 SessionReview 渲染（与「练习历史」里点开同一次练习是**同一个组件**），
         这里只多两块刚交卷才有意义的东西：成绩抬头 + 本场复盘诊断。 -->
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
          <span class="score-label">{{ t('scoreFull') }}</span>
          <button class="btn btn-secondary btn-sm" @click="goHistory">
            <TikuIcon name="clock" :size="13" />
            {{ t('viewHistory') }}
          </button>
        </div>
      </header>

      <div class="report-body">
        <div class="report-questions">
          <!-- 复盘：公式统计 + AI 诊断（流式）；模型只负责把结论讲成人话 -->
          <div class="diag-card">
            <div class="diag-head">
              <span class="diag-title">
                <TikuIcon name="sparkle" :size="14" />
                {{ t('reviewTitle') }}
              </span>
              <span v-if="reviewSummary" class="text-muted diag-stat">
                {{ t('reviewStat', { total: reviewSummary.total, correct: reviewSummary.correct, wrong: reviewSummary.wrong }) }}
              </span>
              <span class="bar-grow"></span>
              <button class="btn btn-secondary btn-sm" :disabled="tutorBusy || !reviewSummary" @click="runDiagnose">
                {{ tutorBusy ? t('reviewGenerating') : t('reviewBtn') }}
              </button>
            </div>
            <!-- 本场错得最多的知识点（按作答统计） -->
            <div v-if="reviewSummary && reviewSummary.byNode.length" class="diag-nodes">
              <span class="text-muted">{{ t('weakNodes') }}</span>
              <span v-for="n in reviewSummary.byNode.slice(0, 5)" :key="n.nodeId" class="diag-node">
                {{ n.name }} · {{ t('wrongN', { n: n.wrong }) }}
                <span v-if="n.answered >= 3" class="text-muted">（{{ t('histRate', { r: n.correctRate }) }}）</span>
                <span v-else class="text-muted">（{{ t('noHistory') }}）</span>
              </span>
            </div>
            <div v-if="diagnosis" class="diag-text" v-html="richHtml(diagnosis)"></div>
            <p v-else-if="!tutorBusy" class="text-muted diag-tip">{{ t('reviewTip') }}</p>
            <p v-if="tutorError" class="diag-err">{{ tutorError }}</p>
          </div>

          <div class="section-title"><h2>{{ t('detailTitle') }}</h2></div>
          <SessionReview :session="report" :bank-id="id" :template-id="skillTemplateId" @changed="refreshReport" />
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

      <!-- 本次配题说明（"开始练习"一键配的题）：只讲可核对的事实，随时可关掉 -->
      <div v-if="planExplain && !planHidden" class="plan-banner">
        <TikuIcon name="target" :size="13" />
        <span class="plan-text">{{ planExplain }}</span>
        <button class="plan-close" :title="t('planHide')" @click="hidePlan">
          <TikuIcon name="x" :size="12" />
        </button>
      </div>

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
              <!-- 提示楼梯（阶段 1）：只在用户主动要的时候才给，做题过程不自动打扰 -->
              <button
                class="bar-btn"
                :title="t('hintBtnTip')"
                @click="openTutor('hint', current.questionId)"
              >
                <TikuIcon name="sparkle" :size="15" />
                <span class="bar-back-text">{{ t('hintBtn') }}</span>
              </button>
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

    <!-- AI 私教抽屉（阶段 1）：提示楼梯 / 答错即问 / 自由追问。
         只在用户点了「提示」或某道错题的「为什么错」时才打开，做题过程不自动打扰。 -->
    <el-drawer
      v-model="tutorOpen"
      :title="tutorMode === 'hint' ? '问老师 · 要提示' : '问老师'"
      direction="rtl"
      size="420px"
      :with-header="false"
      append-to-body
    >
      <TutorPanel
        v-if="tutorOpen"
        :bank-id="Number(id)"
        :question-id="tutorQuestionId"
        :practice-session-id="report?.sessionId || sessionId"
        :template-id="skillTemplateId"
        :kind="'PER_QUESTION'"
        :self-reason="tutorReason"
        @close="tutorOpen = false"
        @session="onTutorSession"
      />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      notFoundDesc: '题库可能已被删除，或地址有误', backToBanks: '返回题库列表', backToBank: '返回题库', back: '返回',
      reportTitle: '成绩报告', scoreFull: '得分 / 满分', viewHistory: '练习历史',
      msgPendingSelfGrade: '还有 {n} 道主观题待自评赋分（在下面那一题里给分）',
      unitPoint: '分', sessionModeTip: '做题采用会话制：抽一组题全部作答后，交卷统一判分', allAnsweredTip: '全部作答完成 · 可返回检查修改，点击右上角「交卷」统一判分', multiTip: '可多选 · 作答后交卷统一判分', subjTip: '输入后交卷统一判分，可随时修改',
      detailTitle: '每题明细',
      msgFavorited: '已收藏',
      msgUnfavorited: '已取消收藏',
      msgReviewSuspended: '已暂停此题复习',
      msgReviewResumed: '已恢复此题复习',
      msgNoQuestionN: '会话中没有第 {n} 题',
      hintBtn: '提示',
      hintBtnTip: '不会做？要一级提示',
      reviewTitle: '这场复盘',
      reviewStat: '共 {total} 题 · 对 {correct} · 错 {wrong}',
      reviewBtn: '生成复盘诊断',
      reviewGenerating: '正在写…',
      reviewTip: '点「生成复盘诊断」看这场错在哪。',
      weakNodes: '本场错得最多的知识点：',
      wrongN: '错 {n} 题',
      histRate: '历史正确率 {r}%',
      noHistory: '题量还不够',
      planHide: '收起这次的配题说明',
      kbHint: '{sel} · ←/→ 切题 · 数字直达题号 · Enter 交卷',
      kbJudge: 'A/B 选 正确/错误',
      kbMulti: '选项字母勾选（可多选）',
      kbSingle: '选项字母选择答案'
    },
    'en-US': {
      notFoundDesc: 'This bank may have been deleted, or the link is wrong', backToBanks: 'Back to banks', back: 'Back', backToBank: 'Back to bank',
      reportTitle: 'Score Report', scoreFull: 'Score / Full', viewHistory: 'Practice history',
      msgPendingSelfGrade: '{n} subjective question(s) await self-grading (grade them in the question below)',
      unitPoint: 'pts', sessionModeTip: 'Session-based: answer a set, then submit for unified scoring', allAnsweredTip: 'All answered — you can go back to check, then click Submit (top right) for scoring', multiTip: 'Multiple answers allowed · submitted for scoring at the end', subjTip: 'Type your answer; submitted for scoring at the end, editable anytime',
      detailTitle: 'Question details',
      msgUnansweredConfirm: '{n} question(s) not answered — after submitting they count as 0 and cannot be answered again. Submit anyway?',
      msgSubmitTitle: 'Confirm submission',
      msgSubmitted: 'Submitted',
      msgFavorited: 'Added to favorites',
      msgUnfavorited: 'Removed from favorites',
      msgReviewSuspended: 'Review paused for this question',
      msgReviewResumed: 'Review resumed for this question',
      msgNoQuestionN: 'Question {n} is not in this session',
      hintBtn: 'Hint',
      hintBtnTip: 'Stuck? Ask for one hint level (direction → key step → full solution; never the answer first)',
      reviewTitle: 'Session review',
      reviewStat: '{total} questions · {correct} correct · {wrong} wrong',
      reviewBtn: 'Generate review',
      reviewGenerating: 'Writing…',
      reviewTip: 'Click “Generate review” to see what went wrong in this session.',
      weakNodes: 'Most-missed topics:',
      wrongN: '{n} wrong',
      histRate: '{r}% historical accuracy',
      noHistory: 'no history yet',
      planHide: 'Hide this session’s question mix',
      kbHint: '{sel} · ←/→ to switch · digits jump to a number · Enter to submit',
      kbJudge: 'A/B for true/false',
      kbMulti: 'option letters to tick (multi-select)',
      kbSingle: 'option letter to answer'
    }
  }
})
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getBank } from '../api/banks'
import { finishSession, getSessionDetail } from '../api/sessions'
import { setReviewSuspended } from '../api/studyRecords'
import { setFavorite } from '../api/questions'
import { getReviewSummary, streamTutor } from '../api/tutor'
import { getSkillTemplates } from '../api/skills'
import { formatScore } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import TikuIcon from '../components/TikuIcon.vue'
import SessionReview from '../components/SessionReview.vue'
import TutorPanel from '../components/TutorPanel.vue'

const route = useRoute()
const router = useRouter()
const id = route.params.id
const sessionId = route.query.sessionId ? Number(route.query.sessionId) : null
const atIndex = route.query.at ? Number(route.query.at) : 0

const bank = ref(null)
const bankError = ref('')

/* ---------- AI 私教（阶段 1）：提示楼梯 / 答错即问 / 复盘诊断 ---------- */
const tutorOpen = ref(false)
const tutorMode = ref('hint')          // hint（提示楼梯）| ask（答错即问/追问）
const tutorQuestionId = ref(null)
const tutorReason = ref('')
const tutorBusy = ref(false)
const tutorError = ref('')
const diagnosis = ref('')              // 复盘诊断文本（流式累积）
const reviewSummary = ref(null)        // 公式统计（成绩 + 按知识点的错题分布）
const skillTemplateId = ref('')        // 当前技能图（决定"这题属于哪个知识点"的说法）

/* ---------- 本次配题说明（"开始练习"一键配的题；从题库详情带过来，只展示不参与判分） ---------- */
const planExplain = ref('')
const planHidden = ref(false)
function loadPlanExplain() {
  if (!sessionId) return
  try {
    planExplain.value = sessionStorage.getItem(`tiku.plan.${sessionId}`) || ''
  } catch (e) {
    planExplain.value = ''
  }
}
function hidePlan() {
  planHidden.value = true
  if (sessionId) {
    try {
      sessionStorage.removeItem(`tiku.plan.${sessionId}`)
    } catch (e) {
      /* 隐私模式下写不了也不影响做题 */
    }
  }
}

/** 打开私教抽屉：hint = 要提示（做题中），ask = 追问某道错题 */
function openTutor(mode, questionId, reason = '') {
  tutorMode.value = mode
  tutorQuestionId.value = questionId ? Number(questionId) : null
  tutorReason.value = reason
  tutorOpen.value = true
}

function onTutorSession(payload) {
  if (payload?.sessionId) tutorSessionIds.value[tutorQuestionId.value] = payload.sessionId
}
const tutorSessionIds = ref({})

/** 交卷后：拉公式统计（成绩 + 薄弱知识点），并记住当前技能图 */
async function loadReviewSummary() {
  if (!report.value?.id) return
  try {
    const templates = await getSkillTemplates().catch(() => [])
    const saved = localStorage.getItem('tiku.skillTemplateId')
    skillTemplateId.value = (templates || []).find((x) => x.templateId === saved)?.templateId
      || templates?.[0]?.templateId || ''
  } catch (e) {
    /* 技能图拉不到也能用（只是没有知识点维度的统计） */
  }
  try {
    reviewSummary.value = await getReviewSummary(report.value.id, Number(id), skillTemplateId.value)
  } catch (e) {
    reviewSummary.value = null
  }
}

/** 生成复盘诊断（流式） */
async function runDiagnose() {
  if (tutorBusy.value || !report.value?.id) return
  tutorBusy.value = true
  tutorError.value = ''
  diagnosis.value = ''
  const result = await streamTutor('/tutor/review', {
    bankId: Number(id),
    practiceSessionId: report.value.id,
    templateId: skillTemplateId.value || null,
    kind: 'POST_REVIEW'
  }, {
    onDelta: (text) => { diagnosis.value += text },
    onError: (message) => {
      tutorError.value = /尚未配置 AI/.test(message)
        ? '尚未配置 AI 模型：请在「设置 → AI」里填好 Key 再回来。'
        : message
    }
  })
  tutorBusy.value = false
  return result
}

/* 逐题讲解/笔记由 SessionReview 内部渲染（与练习历史同一个组件） */

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
  PLAN: '智能配题',
  ALL: '全部随机',
  SEQUENCE: '顺序刷题',
  TOPIC: '按试卷 / 章节',
  REVIEW: '复习队列',
  WRONG: '错题重做',
  FAVORITE: '收藏'
}
const modeLabel = computed(() => (sessionMode.value ? MODE_LABELS[sessionMode.value] || sessionMode.value : ''))

const currentIndex = ref(0)
const finishing = ref(false)

// 交卷成绩报告（渲染成绩视图后隐藏做题 UI）；内容就是会话详情，逐题交给 SessionReview 渲染
const report = ref(null)

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
    loadPlanExplain()
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
      report.value = detail
      // 复盘统计随报告一起准备（公式算的，不耗 token）；AI 诊断由用户点按钮才生成
      await loadReviewSummary()
    }
  } catch (e) {
    questions.value = []
    loadError.value = e?.response?.status === 404 ? '会话不存在或已被删除' : '加载失败，请稍后重试'
  } finally {
    loading.value = false
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
    if (pendingSelfGradeCount.value) {
      ElMessage.info(t('msgPendingSelfGrade', { n: pendingSelfGradeCount.value }))
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

/** 从会话详情重建成绩页（含答案与解析）；自评赋分也走这里刷新分数 */
async function refreshReport() {
  if (!sessionId) return
  try {
    const detail = await getSessionDetail(sessionId)
    report.value = detail
    // 复盘统计随报告一起准备（公式算的，不耗 token）；AI 诊断由用户点按钮才生成
    await loadReviewSummary()
  } catch (e) {
    report.value = null
  }
}

/** 待自评的主观题数（交卷后提示用；赋分控件在 SessionReview 的每题卡里） */
const pendingSelfGradeCount = computed(() =>
  (report.value?.questions || []).filter((q) => q.questionType === 'SUBJECTIVE' && q.userAnswer && !q.selfGrade).length
)

function goHistory() {
  router.push(`/banks/${id}/sessions`)
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

/**
 * 快捷键提示：**文案必须与实现一致**（2026-09-16 审计：原先写死「A~H」，但实现是按题目的实际选项键判断，
 * 选项超过 8 个时提示是错的；这套提示也没走 i18n，英文界面下仍是中文）。
 */
const kbHint = computed(() => {
  const q = current.value
  if (!q) return ''
  const sel =
    q.questionType === 'JUDGE'
      ? t('kbJudge')
      : q.questionType === 'MULTIPLE'
        ? t('kbMulti')
        : t('kbSingle')
  return t('kbHint', { sel })
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

/**
 * 是否有**真正打开着**的弹层（确认框 / 抽屉 / 大弹窗）。
 *
 * 坑（用户实测报过"方向键、字母选题、数字跳题全部失效"）：
 * el-drawer / el-dialog 关闭后元素**仍留在 DOM 里**，只是 `display:none`，
 * 所以判断"存在 .el-overlay"会把做题页的快捷键**永久**挡掉——这里必须看计算样式。
 */
function hasOpenLayer() {
  return [...document.querySelectorAll('.el-overlay')].some((el) => {
    const style = getComputedStyle(el)
    return style.display !== 'none' && style.visibility !== 'hidden'
  })
}

function onKeydown(e) {
  if (e.ctrlKey || e.metaKey || e.altKey) return
  //输入框/文本域内打字、或任何弹层（确认框等）打开时不抢键盘
  if (isEditableTarget(e)) return
  if (hasOpenLayer()) return
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
/* 逐题对错标签与得分口径都在 SessionReview 里（与练习历史共用一份），这里只留计时文案 */
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
/* 本次配题说明：一行、可关掉，不占做题注意力 */
.plan-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  background: var(--accent-soft);
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 12.5px;
  line-height: 1.6;
  flex-shrink: 0;
}
.plan-text {
  flex: 1;
  min-width: 0;
}
.plan-close {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 2px;
  display: inline-flex;
  border-radius: 4px;
}
.plan-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
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
  /* 正文类内容统一跟着"阅读字号"档位（设置 / 笔记页可调） */
  font-size: var(--content-font);
  line-height: var(--content-lh);
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
  /* 题干是做题时最该看清的正文：跟着"阅读字号"档位 */
  font-size: var(--content-font);
  line-height: var(--content-lh);
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
  font-size: var(--content-font);
  line-height: var(--content-lh);
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
/* 逐题部分（题干/选项/答案/解析/讲解/笔记）全部由 SessionReview 渲染，样式在组件内；
   这里只保留成绩抬头与本场复盘卡（交卷当屏独有的两块）。 */
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
.report-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-title h2 {
  font-size: 16px;
  margin-bottom: 10px;
}
/* 本场复盘诊断卡：公式统计 + 按需生成的 AI 讲解（逐题部分在 SessionReview 里） */
.diag-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 14px 16px;
  margin-bottom: 18px;
}
.diag-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.diag-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--accent-text);
}
.diag-stat {
  font-size: 12px;
}
.diag-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  font-size: 12.5px;
}
.diag-node {
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 10px;
  background: var(--bg-elev);
  color: var(--text-secondary);
}
.diag-text {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.85;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.diag-tip {
  margin: 8px 0 0;
  font-size: 12.5px;
}
.diag-err {
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--danger);
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
