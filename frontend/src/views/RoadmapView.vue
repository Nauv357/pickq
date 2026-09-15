<template>
  <div class="roadmap-page">
    <!-- 头部：技能图 + 目标 + 每日题量（个体输入，阶段 2 只用于公式，不调模型） -->
    <header class="rm-head">
      <button class="btn btn-ghost btn-sm" @click="$router.push(`/banks/${id}`)">
        <TikuIcon name="chevron-left" :size="14" />
        {{ t('back') }}
      </button>
      <h1 class="rm-title">{{ t('title') }}</h1>
      <span class="text-muted rm-sub">{{ bank?.name }}</span>
      <span class="grow"></span>
      <span v-if="profile" class="text-muted rm-goal" :title="profile.goalText || ''">
        {{ profile.goalText || t('noGoal') }}
      </span>
      <button class="btn btn-ghost btn-sm" @click="settingsOpen = true">
        <TikuIcon name="settings" :size="14" />
        {{ t('goalBtn') }}
      </button>
    </header>

    <p class="rm-note text-muted">{{ t('zeroToken') }}</p>

    <div v-if="loading" class="card tiku-skeleton rm-skeleton">
      <div class="sk-line" style="width: 30%"></div>
      <div class="sk-line" style="width: 80%"></div>
    </div>

    <template v-else>
      <!-- 今天做什么 -->
      <section class="card rm-today">
        <div class="rm-card-head">
          <h2>{{ t('today') }}</h2>
          <span class="text-muted">{{ today?.date }}</span>
          <span class="grow"></span>
          <span v-if="today && today.plannedQuestions" class="rm-progress-text mono">
            {{ today.doneQuestions }} / {{ today.plannedQuestions }}
          </span>
        </div>
        <div v-if="today && today.plannedQuestions" class="rm-bar">
          <div class="rm-bar-fill" :style="{ width: progressPct + '%' }"></div>
        </div>
        <div v-if="today && today.tasks.length" class="rm-tasks">
          <div v-for="task in today.tasks" :key="task.kind + task.nodeId" class="rm-task">
            <span class="rm-task-title">{{ task.title }}</span>
            <span class="text-muted rm-task-meta">
              {{ t('taskMeta', { done: task.done, total: task.total }) }}
            </span>
            <button class="btn btn-primary btn-sm" @click="startTask(task)">{{ t('start') }}</button>
          </div>
        </div>
        <p v-else class="text-muted rm-empty">{{ today?.note }}</p>
        <p v-if="startError" class="rm-err">{{ startError }}</p>
      </section>

      <!-- 下一步（外缘） -->
      <section class="card rm-next">
        <div class="rm-card-head">
          <h2>{{ t('next') }}</h2>
          <span class="text-muted">{{ t('nextHint') }}</span>
        </div>
        <div v-if="roadmap && roadmap.nextBatch.length" class="rm-nodes">
          <div v-for="n in roadmap.nextBatch" :key="n.nodeId" class="rm-node">
            <div class="rm-node-main">
              <span class="rm-node-name">{{ n.name }}</span>
              <span class="text-muted rm-node-meta">
                {{ n.stageName }} · {{ t('nodeQuestions', { n: n.questionCount }) }}
                <template v-if="n.attempts"> · {{ t('nodeAccuracy', { r: accuracy(n) }) }}</template>
              </span>
            </div>
            <div class="rm-node-bar" :title="t('masteryTip')">
              <div class="rm-node-bar-fill" :style="{ width: Math.round(n.mastery * 100) + '%' }"></div>
            </div>
            <span class="text-muted rm-mastery mono">{{ Math.round(n.mastery * 100) }}%</span>
            <button class="btn btn-secondary btn-sm" :disabled="n.questionCount === 0" @click="practiceNode(n)">
              {{ t('practiceNode') }}
            </button>
          </div>
        </div>
        <p v-else class="text-muted rm-empty">{{ roadmap?.note }}</p>
      </section>

      <!-- 还缺什么（缺口） -->
      <section class="card rm-gaps">
        <div class="rm-card-head">
          <h2>{{ t('gaps') }}</h2>
          <span class="text-muted">{{ t('gapHint') }}</span>
          <span class="grow"></span>
          <span class="text-muted rm-cover">{{ t('coverage', { covered: roadmap?.coveredQuestions || 0, total: roadmap?.totalNodes || 0, cleared: roadmap?.clearedNodes || 0 }) }}</span>
        </div>
        <div v-if="roadmap && roadmap.gaps.length" class="rm-gap-list">
          <div v-for="g in roadmap.gaps" :key="g.nodeId" class="rm-gap" :title="t('gapRowTip', { n: g.questionCount })">
            <span class="rm-gap-name">{{ g.name }}</span>
            <span class="text-muted rm-gap-stage">{{ g.stageName }}</span>
            <span class="rm-gap-reason" :class="{ warn: g.questionCount === 0 }">{{ g.reason }}</span>
            <button class="btn btn-ghost btn-sm" @click="$router.push(`/banks/${id}`)">{{ t('goAdd') }}</button>
          </div>
        </div>
        <p v-else class="text-muted rm-empty">{{ t('noGaps') }}</p>
      </section>

      <!-- 路线图（阶段 → 节点） -->
      <section class="card rm-map">
        <div class="rm-card-head">
          <h2>{{ t('map') }}</h2>
          <span class="text-muted">{{ t('mapHint') }}</span>
        </div>
        <div v-for="stage in roadmap?.stages || []" :key="stage.stageId" class="rm-stage">
          <div class="rm-stage-head">
            <span class="rm-stage-name">{{ stage.name }}</span>
            <span class="text-muted">{{ t('stageMeta', { cleared: stage.clearedCount, total: stage.nodeCount }) }}</span>
          </div>
          <div class="rm-stage-nodes">
            <button
              v-for="n in stage.nodes"
              :key="n.nodeId"
              class="rm-chip"
              :class="[n.status.toLowerCase(), { blocked: !n.prereqReady }]"
              :title="chipTitle(n)"
              @click="practiceNode(n)"
            >
              {{ n.name }}
              <span class="rm-chip-sub">{{ n.questionCount }}</span>
            </button>
          </div>
        </div>
      </section>
    </template>

    <!-- 目标设置 -->
    <el-dialog v-model="settingsOpen" :title="t('goalBtn')" width="min(92vw, 460px)" align-center>
      <div class="rm-form">
        <div class="field">
          <label class="field-label">{{ t('template') }}</label>
          <el-select v-model="form.templateId" style="width: 100%" @change="reload">
            <el-option v-for="tpl in templates" :key="tpl.templateId" :label="tpl.name" :value="tpl.templateId" />
          </el-select>
        </div>
        <div class="field">
          <label class="field-label">{{ t('goal') }}</label>
          <el-input v-model="form.goalText" class="rm-goal-input" :placeholder="t('goalPh')" maxlength="200" />
        </div>
        <div class="field">
          <label class="field-label">{{ t('dailyQuestions') }}</label>
          <el-input-number v-model="form.dailyQuestions" class="rm-daily-input" :min="1" :max="200" controls-position="right" style="width: 160px" />
        </div>
      </div>
      <template #footer>
        <button class="btn btn-secondary" @click="settingsOpen = false">{{ t('cancel') }}</button>
        <button class="btn btn-primary" @click="saveProfile">{{ t('save') }}</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import TikuIcon from '../components/TikuIcon.vue'
import { getBank } from '../api/banks'
import { createSession } from '../api/sessions'
import { getRoadmap, getRoadmapProfile, getTodayTasks, saveRoadmapProfile } from '../api/roadmap'
import { getSkillTemplates } from '../api/skills'

/**
 * 学习路线（阶段 2）：今天做什么 / 下一步 / 还缺什么 / 路线图。
 *
 * 三条设计约束：
 * 1. **零 token**：页面上每个数字都由后端公式算出来，刷新多少次都不花额度；
 * 2. **状态可解释**：节点状态（已过关/在练/证据不足/前置未满足）都能点开看依据，
 *    不让用户面对一个"AI 觉得你该学这个"的黑盒；
 * 3. **不自动出题**：这里只给建议与一键入口，真正的练习仍然走已有的会话流程。
 */
const route = useRoute()
const router = useRouter()
const id = route.params.id

const { t } = useI18n({
  messages: {
    'zh-CN': {
      title: '学习路线',
      back: '返回题库',
      goalBtn: '目标与题量',
      noGoal: '还没有设目标',
      zeroToken: '这一页全部由公式算出（前置关系 + 你的作答），刷新多少次都不消耗 AI 额度。',
      today: '今天做什么',
      next: '下一步',
      nextHint: '前置已过关、你还没过关、而且你确实有题可练的知识点',
      gaps: '还缺什么',
      gapHint: '题库里没有题（或题太少）的知识点——这些位置刷不到，会导致"以为练完了"',
      gapRowTip: '这个知识点你只有 {n} 道题：少于 3 道就无法确认掌握（防漏刷）。可以导入/录入更多题，或换一张更贴合你题库的技能图。',
      map: '路线图',
      mapHint: '点任意知识点可直接开始练它',
      start: '开始练习',
      practiceNode: '练这个知识点',
      goAdd: '去补题',
      taskMeta: '{done}/{total} 题',
      nodeQuestions: '{n} 题',
      nodeAccuracy: '正确率 {r}%',
      masteryTip: '掌握度（公式：独立度 × 时间衰减 × 间隔加分，近 8 次作答）',
      coverage: '覆盖 {covered}/{total} 个知识点 · 已过关 {cleared}',
      stageMeta: '{cleared}/{total} 已过关',
      noGaps: '技能图上的知识点都有题了',
      template: '技能图',
      goal: '目标（写一句就够）',
      goalPh: '如：两个月内行测上 70',
      dailyQuestions: '每日题量',
      save: '保存',
      cancel: '取消',
      saved: '已保存',
      started: '已开始练习',
      statusCleared: '已过关',
      statusLearning: '在练',
      statusUnverified: '证据不足（题库缺题）',
      statusRegressed: '抽测掉下来了（要重新练）',
      blocked: '前置未过关：',
      errStart: '无法开始练习：这个知识点目前没有可练的题'
    },
    'en-US': {
      title: 'Learning path',
      back: 'Back',
      goalBtn: 'Goal & daily load',
      noGoal: 'No goal yet',
      zeroToken: 'Everything on this page is computed by formulas (prerequisites + your answers). Refreshing costs no AI budget.',
      today: 'Today',
      next: 'Next up',
      nextHint: 'prerequisites cleared, not cleared yet, and you actually have questions for it',
      gaps: 'What is missing',
      gapHint: 'Nodes with no (or too few) questions — these spots cannot be practised',
      gapRowTip: 'Only {n} question(s) for this node: fewer than 3 means mastery cannot be verified. Import or add more questions, or switch to a map that fits your bank.',
      map: 'Roadmap',
      mapHint: 'Click any node to practise it',
      start: 'Start',
      practiceNode: 'Practise this node',
      goAdd: 'Add questions',
      taskMeta: '{done}/{total} questions',
      nodeQuestions: '{n} questions',
      nodeAccuracy: '{r}% correct',
      masteryTip: 'Mastery (independence × recency × spacing bonus, last 8 attempts)',
      coverage: '{covered}/{total} nodes covered · {cleared} cleared',
      stageMeta: '{cleared}/{total} cleared',
      noGaps: 'Every node in this map has questions',
      template: 'Skill map',
      goal: 'Goal (one line)',
      goalPh: 'e.g. reach 70 in two months',
      dailyQuestions: 'Questions per day',
      save: 'Save',
      cancel: 'Cancel',
      saved: 'Saved',
      started: 'Practice started',
      statusCleared: 'Cleared',
      statusLearning: 'Learning',
      statusUnverified: 'Not enough evidence (few questions)',
      statusRegressed: 'Regressed after a spot check (practise again)',
      blocked: 'Blocked by: ',
      errStart: 'Cannot start: no practiceable questions for this node yet'
    }
  }
})

const bank = ref(null)
const roadmap = ref(null)
const today = ref(null)
const profile = ref(null)
const templates = ref([])
const loading = ref(true)
const settingsOpen = ref(false)
const startError = ref('')
const form = reactive({ templateId: '', goalText: '', dailyQuestions: 20 })

const progressPct = computed(() => {
  const planned = today.value?.plannedQuestions || 0
  return planned ? Math.round(((today.value?.doneQuestions || 0) / planned) * 100) : 0
})

const accuracy = (n) => (n.attempts ? Math.round((n.correct / n.attempts) * 100) : 0)

function chipTitle(n) {
  const parts = [t(`status${n.status.charAt(0)}${n.status.slice(1).toLowerCase()}`)]
  if (!n.prereqReady) parts.push(t('blocked') + n.blockedBy.join('、'))
  if (n.attempts) parts.push(`${n.correct}/${n.attempts}`)
  if (n.hintCount) parts.push(`${n.hintCount} 次用了提示`)
  return parts.filter(Boolean).join(' · ')
}

const templateId = () => form.templateId || localStorage.getItem('tiku.skillTemplateId') || ''

async function reload() {
  if (!templateId()) return
  loading.value = true
  startError.value = ''
  try {
    localStorage.setItem('tiku.skillTemplateId', templateId())
    const [map, todo] = await Promise.all([getRoadmap(Number(id), templateId()), getTodayTasks(Number(id), templateId())])
    roadmap.value = map
    today.value = todo
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  try {
    profile.value = await saveRoadmapProfile({
      templateId: form.templateId,
      goalText: form.goalText,
      dailyQuestions: form.dailyQuestions
    })
    settingsOpen.value = false
    ElMessage.success(t('saved'))
    await reload()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 按知识点开一场练习：mode=SKILL 让后端按节点抽题（与知识点页、标签共用同一份标签） */
async function startNode(nodeId, count) {
  try {
    const data = await createSession(Number(id), { mode: 'SKILL', nodeIds: [nodeId], count })
    if (!data?.total || !data.questions?.length) {
      startError.value = t('errStart')
      return
    }
    ElMessage.success(t('started'))
    router.push({ path: `/banks/${id}/practice`, query: { sessionId: data.sessionId } })
  } catch (e) {
    startError.value = e?.message || t('errStart')
  }
}

const practiceNode = (n) => startNode(n.nodeId, today.value?.targetQuestions || 20)

function startTask(task) {
  if (task.kind === 'REVIEW') {
    // 到期复习走复习队列（保持与"复习计划"一致的口径）
    router.push({ path: `/banks/${id}/practice`, query: { mode: 'REVIEW' } })
    return
  }
  if (task.kind === 'CARD') {
    // 闪卡复习有自己的界面（先想再翻、记得/忘了）
    router.push({ path: `/banks/${id}/cards` })
    return
  }
  // PRACTICE / SPOT_CHECK 都是"按知识点开一场练习"（抽测 1–2 题、主攻按每日题量）
  startNode(task.nodeId, task.total || 20)
}

onMounted(async () => {
  try {
    const [bankData, tpls, prof] = await Promise.all([getBank(id), getSkillTemplates(), getRoadmapProfile()])
    bank.value = bankData
    templates.value = tpls || []
    profile.value = prof
    form.templateId = prof?.templateId || localStorage.getItem('tiku.skillTemplateId') || tpls?.[0]?.templateId || ''
    form.goalText = prof?.goalText || ''
    form.dailyQuestions = prof?.dailyQuestions || 20
  } catch (e) {
    /* 拦截器已提示 */
  }
  await reload()
})
</script>

<style scoped>
.roadmap-page {
  padding: 4px 2px 40px;
}
.rm-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.rm-title {
  font-size: 20px;
  margin: 0;
}
.rm-sub,
.rm-goal {
  font-size: 13px;
}
.rm-goal {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.grow {
  flex: 1;
}
.rm-note {
  font-size: 12px;
  margin: 6px 0 14px;
}
.rm-skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.card {
  margin-bottom: 14px;
}
.rm-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.rm-card-head h2 {
  font-size: 15px;
  margin: 0;
}
.rm-progress-text,
.rm-cover {
  font-size: 12px;
}
.rm-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--bg-hover);
  overflow: hidden;
  margin-bottom: 10px;
}
.rm-bar-fill {
  height: 100%;
  background: var(--success);
  transition: width 0.3s;
}
.rm-tasks {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rm-task {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
}
.rm-task-title {
  font-size: 14px;
}
.rm-task-meta {
  font-size: 12px;
}
.rm-task .btn {
  margin-left: auto;
}
.rm-nodes {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rm-node {
  display: flex;
  align-items: center;
  gap: 10px;
}
.rm-node-main {
  min-width: 220px;
  display: flex;
  flex-direction: column;
}
.rm-node-name {
  font-size: 14px;
}
.rm-node-meta {
  font-size: 12px;
}
.rm-node-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-hover);
  overflow: hidden;
}
.rm-node-bar-fill {
  height: 100%;
  background: var(--accent);
}
.rm-mastery {
  font-size: 12px;
  width: 38px;
  text-align: right;
}
.rm-gap-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 6px 12px;
  max-height: 260px;
  overflow: auto;
}
.rm-gap {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 4px 0;
}
.rm-gap-name {
  min-width: 120px;
}
.rm-gap-stage {
  font-size: 12px;
}
.rm-gap-reason {
  font-size: 12px;
  color: var(--warning);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rm-gap-reason.warn {
  color: var(--danger);
}
.rm-stage {
  margin-bottom: 12px;
}
.rm-stage-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}
.rm-stage-name {
  font-weight: 600;
  color: var(--text-primary);
}
.rm-stage-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.rm-chip {
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-secondary);
  border-radius: 14px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.rm-chip:hover {
  border-color: var(--accent);
}
.rm-chip.cleared {
  border-color: var(--success);
  color: var(--success);
}
.rm-chip.learning {
  border-color: var(--accent);
  color: var(--accent-text);
}
.rm-chip.unverified {
  border-style: dashed;
  color: var(--warning);
}
.rm-chip.regressed {
  border-color: var(--danger);
  color: var(--danger);
  border-style: dashed;
}
.rm-chip.blocked {
  opacity: 0.55;
}
.rm-chip-sub {
  font-size: 11px;
  opacity: 0.75;
}
.rm-empty,
.rm-err {
  font-size: 13px;
}
.rm-err {
  color: var(--danger);
}
.rm-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
