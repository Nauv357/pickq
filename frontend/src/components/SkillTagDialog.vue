<template>
  <el-dialog
    v-model="visible"
    :title="t('title')"
    width="min(94vw, 900px)"
    align-center
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <!-- 技能图选择 + 分析按钮 -->
    <div class="skill-toolbar">
      <div class="skill-toolbar-left">
        <span class="skill-label">{{ t('template') }}</span>
        <el-select v-model="templateId" size="small" style="width: 250px" :disabled="running">
          <el-option v-for="tpl in templates" :key="tpl.templateId" :label="tpl.name" :value="tpl.templateId">
            <span>{{ tpl.name }}</span>
            <span class="skill-opt-meta">{{ tpl.nodeCount }} {{ t('nodes') }}</span>
          </el-option>
        </el-select>
      </div>
      <div class="skill-toolbar-right">
        <span v-if="running" class="skill-running text-muted">{{ t('running', { done: progressDone, total: progressTotal }) }}</span>
        <button v-else class="btn btn-primary btn-sm" :disabled="!templateId || loading" @click="startTagging">
          {{ hasTags || hasSuggestions ? t('retag') : t('start') }}
        </button>
      </div>
    </div>

    <p class="skill-hint text-muted">{{ t('hint') }}</p>

    <!-- 覆盖概览：三段不重叠（已确认 / 待确认 / 未匹配），相加 = 总题数 -->
    <div v-if="coverage" class="skill-cover">
      <div class="skill-cover-row">
        <span class="skill-stat ok"><b>{{ coverage.confirmedQuestions }}</b> {{ t('confirmed') }}</span>
        <span class="skill-stat mid"><b>{{ coverage.pendingQuestions }}</b> {{ t('pending') }}</span>
        <span class="skill-stat warn"><b>{{ coverage.untaggedQuestions }}</b> {{ t('untagged') }}</span>
        <span class="skill-stat total">{{ t('totalN', { n: coverage.totalQuestions }) }}</span>
      </div>
      <div class="skill-bar">
        <div class="skill-bar-ok" :style="{ width: pct(coverage.confirmedQuestions) + '%' }"></div>
        <div class="skill-bar-mid" :style="{ width: pct(coverage.pendingQuestions) + '%' }"></div>
      </div>
      <p class="skill-cover-note text-muted">{{ t('coverNote', { usable: coverage.usableQuestions }) }}</p>
    </div>

    <el-tabs v-model="tab" class="skill-tabs">
      <!-- 待确认（AI 建议）：按节点聚合 + 该节点下的**全部单题**，可逐题处理 -->
      <el-tab-pane :label="t('tabPending', { n: coverage?.pendingQuestions ?? 0 })" name="pending">
        <div v-if="loading" class="skill-empty text-muted">{{ t('loading') }}</div>
        <div v-else-if="pending.length === 0" class="skill-empty text-muted">{{ t('noPending') }}</div>
        <div v-else class="skill-list">
          <div v-for="p in pending" :key="p.nodeId" class="skill-item">
            <div class="skill-item-head">
              <button class="skill-expand" @click="toggleExpand(p.nodeId)">
                <TikuIcon :name="expanded[p.nodeId] ? 'chevron-down' : 'chevron-right'" :size="12" />
              </button>
              <span class="skill-node">{{ p.name }}</span>
              <span class="text-muted skill-count">{{ t('questions', { n: p.questionCount }) }}</span>
              <span class="text-muted skill-conf">{{ t('confidence', { c: p.avgConfidence }) }}</span>
            </div>
            <div class="skill-item-actions">
              <button class="btn btn-primary btn-sm" @click="confirmNode(p)">{{ t('confirmAll') }}</button>
              <el-select
                v-model="retagTarget[p.nodeId]"
                size="small"
                clearable
                filterable
                :placeholder="t('retagTo')"
                style="width: 190px"
              >
                <el-option-group v-for="g in nodeGroups" :key="g.name" :label="g.name">
                  <el-option v-for="n in g.nodes" :key="n.nodeId" :label="n.name" :value="n.nodeId" />
                </el-option-group>
              </el-select>
              <button class="btn btn-secondary btn-sm" :disabled="!retagTarget[p.nodeId]" @click="retagNode(p)">
                {{ t('move') }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="rejectNode(p)">{{ t('rejectAll') }}</button>
              <button class="btn btn-ghost btn-sm" @click="backfill(p)">{{ t('backfillTopic') }}</button>
            </div>

            <!-- 单题清单：让用户看清到底是哪几题，并能单独处理 -->
            <div v-if="expanded[p.nodeId]" class="skill-questions">
              <div v-for="q in p.questions" :key="q.questionId" class="skill-q">
                <span class="skill-q-no">{{ q.questionNumber ?? '—' }}</span>
                <span class="skill-q-text" :title="q.preview">{{ q.preview }}</span>
                <span class="text-muted skill-q-conf">{{ q.confidence.toFixed(2) }}</span>
                <span class="skill-q-actions">
                  <button class="btn btn-ghost btn-sm" @click="confirmOne(p, q)">{{ t('confirmOne') }}</button>
                  <button class="btn btn-ghost btn-sm" @click="rejectOne(p, q)">{{ t('rejectOne') }}</button>
                  <button class="btn btn-ghost btn-sm" @click="openQuestion(q.questionId)">{{ t('openQuestion') }}</button>
                </span>
              </div>
              <p v-if="p.questionCount > p.questions.length" class="text-muted skill-more">
                {{ t('moreQuestions', { n: p.questionCount - p.questions.length }) }}
              </p>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 未匹配：AI 没给出可用知识点的题（用户要能逐题打开去补） -->
      <el-tab-pane :label="t('tabUntagged', { n: coverage?.untaggedQuestions ?? 0 })" name="untagged">
        <p class="skill-hint text-muted">{{ t('untaggedHint') }}</p>
        <div v-if="loadingUntagged" class="skill-empty text-muted">{{ t('loading') }}</div>
        <div v-else-if="untagged.length === 0" class="skill-empty text-muted">{{ t('noUntagged') }}</div>
        <div v-else class="skill-questions">
          <div v-for="q in untagged" :key="q.questionId" class="skill-q">
            <span class="skill-q-no">{{ q.questionNumber ?? '—' }}</span>
            <span class="skill-q-text" :title="q.preview">{{ q.preview }}</span>
            <span class="skill-q-actions">
              <button class="btn btn-ghost btn-sm" @click="openQuestion(q.questionId)">{{ t('openQuestion') }}</button>
            </span>
          </div>
          <p v-if="(coverage?.untaggedQuestions ?? 0) > untagged.length" class="text-muted skill-more">
            {{ t('moreQuestions', { n: (coverage?.untaggedQuestions ?? 0) - untagged.length }) }}
          </p>
        </div>
      </el-tab-pane>

      <!-- 覆盖地图 -->
      <el-tab-pane :label="t('tabMap')" name="map">
        <div v-if="loading" class="skill-empty text-muted">{{ t('loading') }}</div>
        <div v-else class="skill-map">
          <div v-for="stage in stages" :key="stage.name" class="skill-stage">
            <div class="skill-stage-name">{{ stage.name }}</div>
            <div
              v-for="n in stage.nodes"
              :key="n.nodeId"
              class="skill-map-row"
              :class="{ empty: n.questionCount === 0, thin: n.questionCount > 0 && !n.evidenceEnough }"
            >
              <span class="skill-map-name">{{ n.name }}</span>
              <span class="skill-map-count text-muted">{{ t('questions', { n: n.questionCount }) }}</span>
              <span v-if="n.questionCount === 0" class="skill-map-tag warn">{{ t('noQuestions') }}</span>
              <span v-else-if="!n.evidenceEnough" class="skill-map-tag warn">{{ t('thin') }}</span>
              <span v-else class="skill-map-tag ok">{{ t('enough') }}</span>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <span class="skill-foot text-muted">{{ t('footNote') }}</span>
      <button class="btn btn-secondary" @click="visible = false">{{ t('close') }}</button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import TikuIcon from './TikuIcon.vue'
import {
  applySkills,
  getPendingSkills,
  getSkillCoverage,
  getSkillQuestions,
  getSkillTemplate,
  getSkillTemplates,
  suggestSkills
} from '../api/skills'
import { startBusy, stopBusy, updateBusy } from '../utils/busy'

const props = defineProps({
  bankId: { type: [Number, String], required: true }
})
/** 打开某道题的编辑器（由题库详情页处理），用于"未匹配的题逐题去补" */
const emit = defineEmits(['open-question'])

const { t } = useI18n({
  messages: {
    zh: {
      title: '知识点标签',
      template: '技能图',
      nodes: '个知识点',
      start: '分析并标注',
      retag: '重新分析',
      running: '正在分析…（已处理 {done} / 约 {total} 题）',
      hint: 'AI 把题目对应到技能图的知识点，用于后面的学习路线、缺口分析与掌握度判定。结果先作为建议，你确认后才生效；置信度低的建议只作参考，不会用来判断"已掌握"。',
      confirmed: '已确认',
      pending: '待确认',
      untagged: '未匹配',
      totalN: '共 {n} 题',
      coverNote: '「待确认」= AI 给了建议但你还没确认；「未匹配」= AI 没给出可用知识点（可在题目里手动指定）。其中可用于判定掌握的已有 {usable} 题；没有标签的题照常练，不影响刷题与复习。',
      tabPending: '待确认（{n}）',
      tabUntagged: '未匹配（{n}）',
      tabMap: '覆盖地图',
      loading: '加载中…',
      noPending: '没有待确认的建议（点右上角「分析并标注」试试）',
      questions: '{n} 题',
      confidence: '平均把握 {c}',
      confirmAll: '本节点全部确认',
      retagTo: '改挂到…',
      move: '改挂',
      rejectAll: '全部丢弃',
      backfillTopic: '设为主题',
      confirmOne: '确认此题',
      rejectOne: '丢弃建议',
      openQuestion: '打开题目',
      moreQuestions: '还有 {n} 题未列出（可分批处理或直接在题目里改）',
      untaggedHint: '这些题 AI 没给出可用的知识点：点「打开题目」逐题手动指定，或换一张技能图再分析一次。',
      noUntagged: '所有题都有知识点归属了',
      noQuestions: '你没有这个知识点的题',
      thin: '题太少，暂不能判定掌握',
      enough: '题量足够',
      footNote: '标签只保存在本机；导出题库时随包带走（作者确认过的标签）。',
      close: '关闭',
      msgDone: '分析完成：已标注 {tagged} 题',
      msgTruncated: '本次已处理到上限，可再次点击继续',
      msgConfirmed: '已确认 {n} 题',
      msgRetagged: '已改挂 {n} 题',
      msgRejected: '已丢弃建议',
      msgBackfilled: '已把「{topic}」写为 {n} 题的主题',
      msgFailed: '操作失败，请稍后再试'
    },
    en: {
      title: 'Knowledge tags',
      template: 'Skill map',
      nodes: 'nodes',
      start: 'Analyze & tag',
      retag: 'Re-analyze',
      running: 'Analyzing… ({done} / ~{total} questions)',
      hint: 'The AI maps questions onto knowledge nodes used later for the learning path, gap analysis and mastery checks. Results are suggestions until confirmed; low-confidence ones are only hints, never used to decide "mastered".',
      confirmed: 'confirmed',
      pending: 'to confirm',
      untagged: 'unmatched',
      totalN: '{n} questions in total',
      coverNote: '"To confirm" = the AI suggested nodes you have not confirmed yet; "unmatched" = no usable node was found (assign manually in the question). Usable for mastery: {usable}. Untagged questions still work normally in practice and review.',
      tabPending: 'To confirm ({n})',
      tabUntagged: 'Unmatched ({n})',
      tabMap: 'Coverage map',
      loading: 'Loading…',
      noPending: 'No suggestions yet (try "Analyze & tag")',
      questions: '{n} questions',
      confidence: 'avg confidence {c}',
      confirmAll: 'Confirm all in node',
      retagTo: 'Move to…',
      move: 'Move',
      rejectAll: 'Discard all',
      backfillTopic: 'Set as topic',
      confirmOne: 'Confirm',
      rejectOne: 'Discard',
      openQuestion: 'Open',
      moreQuestions: '{n} more not listed (process in batches or edit the question)',
      untaggedHint: 'No usable node for these questions: open each one and assign manually, or try another skill map.',
      noUntagged: 'Every question has a knowledge node now',
      noQuestions: 'You have no questions for this node',
      thin: 'Too few questions to judge mastery',
      enough: 'Enough evidence',
      footNote: 'Tags are stored locally and travel with the bank file (author-confirmed tags).',
      close: 'Close',
      msgDone: 'Done: tagged {tagged}',
      msgTruncated: 'Reached this round’s limit — you can continue',
      msgConfirmed: 'Confirmed {n} questions',
      msgRetagged: 'Moved {n} questions',
      msgRejected: 'Suggestions discarded',
      msgBackfilled: 'Topic “{topic}” written to {n} questions',
      msgFailed: 'Action failed, please retry later'
    }
  }
})

const visible = ref(false)
const loading = ref(false)
const loadingUntagged = ref(false)
const running = ref(false)
const tab = ref('pending')
const templates = ref([])
const templateId = ref('')
const coverage = ref(null)
const pending = ref([])
const untagged = ref([])
const allNodes = ref([])
const retagTarget = reactive({})
const expanded = reactive({})
const progressDone = ref(0)
const progressTotal = ref(0)

const hasTags = computed(() => (coverage.value?.confirmedQuestions ?? 0) > 0)
const hasSuggestions = computed(() => (coverage.value?.pendingQuestions ?? 0) > 0)

const pct = (n) => {
  const total = coverage.value?.totalQuestions || 0
  return total ? Math.round((n / total) * 100) : 0
}

/** 下拉里 30+ 个节点要按阶段分组才挑得动 */
const nodeGroups = computed(() => {
  const order = []
  const byStage = new Map()
  for (const n of allNodes.value) {
    const key = n.stageName || '—'
    if (!byStage.has(key)) { byStage.set(key, []); order.push(key) }
    byStage.get(key).push(n)
  }
  return order.map((name) => ({ name, nodes: byStage.get(name) }))
})

const stages = computed(() => {
  if (!coverage.value) return []
  const order = []
  const byStage = new Map()
  for (const n of coverage.value.nodes) {
    const key = n.stageName || '—'
    if (!byStage.has(key)) { byStage.set(key, []); order.push(key) }
    byStage.get(key).push(n)
  }
  return order.map((name) => ({ name, nodes: byStage.get(name) }))
})

async function open() {
  visible.value = true
  if (templates.value.length === 0) await loadTemplates()
  await refresh()
}

defineExpose({ open })

async function loadTemplates() {
  loading.value = true
  try {
    templates.value = (await getSkillTemplates()) || []
    if (!templateId.value && templates.value.length) templateId.value = templates.value[0].templateId
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function refresh() {
  if (!templateId.value) return
  loading.value = true
  try {
    const [cov, pen, tpl] = await Promise.all([
      getSkillCoverage(props.bankId, templateId.value),
      getPendingSkills(props.bankId, templateId.value),
      getSkillTemplate(templateId.value)
    ])
    coverage.value = cov
    pending.value = pen || []
    allNodes.value = tpl?.nodes || []
    for (const p of pending.value) {
      if (expanded[p.nodeId] === undefined) expanded[p.nodeId] = true // 默认展开：用户要能直接看到是哪几题
    }
    if (tab.value === 'untagged') await loadUntagged()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function loadUntagged() {
  if (!templateId.value) return
  loadingUntagged.value = true
  try {
    untagged.value = (await getSkillQuestions(props.bankId, { templateId: templateId.value, status: 'untagged', size: 100 })) || []
  } catch (e) {
    untagged.value = []
  } finally {
    loadingUntagged.value = false
  }
}

watch(tab, (v) => {
  if (v === 'untagged' && visible.value) loadUntagged()
})
watch(templateId, () => {
  if (visible.value) refresh()
})

function toggleExpand(nodeId) {
  expanded[nodeId] = !expanded[nodeId]
}

/**
 * 按批推进分析：一次请求只花 maxAiCalls 次模型调用（大题库跑不完），
 * 循环到没有进展为止；进度用「还没有可用标签的题数」减少来体现，可随时停止。
 */
const BATCH_CALLS = 5

async function startTagging() {
  if (running.value || !templateId.value) return
  running.value = true
  progressTotal.value = coverage.value?.untaggedQuestions ?? 0
  progressDone.value = 0
  startBusy(t('running', { done: 0, total: progressTotal.value }))
  try {
    let lastLeft = Number.MAX_SAFE_INTEGER
    for (let round = 0; round < 200; round++) {
      const res = await suggestSkills(props.bankId, {
        templateId: templateId.value,
        includeUntagged: true,
        maxAiCalls: BATCH_CALLS
      })
      const left = res?.withoutUsableTag ?? 0
      progressDone.value = Math.max(0, progressTotal.value - left)
      updateBusy(t('running', { done: progressDone.value, total: progressTotal.value }))
      await refresh()
      if (!res?.truncated || left >= lastLeft) {
        ElMessage[res?.truncated ? 'warning' : 'success'](
          res?.truncated ? t('msgTruncated') : t('msgDone', { tagged: res?.taggedQuestions ?? 0 })
        )
        break
      }
      lastLeft = left
    }
  } catch (e) {
    /* 拦截器已提示（含"尚未配置 AI"这类可照做的提示） */
  } finally {
    stopBusy()
    running.value = false
  }
}

async function apply(action, extra, okMsg) {
  try {
    const res = await applySkills(props.bankId, { action, templateId: templateId.value, ...extra })
    await refresh()
    if (okMsg) ElMessage.success(okMsg(res?.affected ?? 0))
    return res
  } catch (e) {
    ElMessage.error(t('msgFailed'))
    return null
  }
}

const confirmNode = (p) => apply('confirm', { nodeId: p.nodeId }, (n) => t('msgConfirmed', { n }))
const rejectNode = (p) => apply('reject', { nodeId: p.nodeId }, () => t('msgRejected'))
const confirmOne = (p, q) => apply('confirm', { nodeId: p.nodeId, questionIds: [q.questionId] }, (n) => t('msgConfirmed', { n }))
const rejectOne = (p, q) => apply('reject', { nodeId: p.nodeId, questionIds: [q.questionId] }, () => t('msgRejected'))
const retagNode = (p) =>
  apply('retag', { nodeId: p.nodeId, newNodes: [retagTarget[p.nodeId]] }, (n) => t('msgRetagged', { n }))
/** 把该节点的名称写回题目主题：题库没填主题时顺手变规整（默认不覆盖已有主题） */
const backfill = (p) => apply('backfill-topic', { nodeId: p.nodeId, topic: p.name }, (n) => t('msgBackfilled', { topic: p.name, n }))

function openQuestion(questionId) {
  emit('open-question', questionId)
  visible.value = false
}

function onClosed() {
  coverage.value = null
  pending.value = []
  untagged.value = []
  Object.keys(expanded).forEach((k) => delete expanded[k])
}
</script>

<style scoped>
.skill-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.skill-toolbar-left,
.skill-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.skill-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.skill-opt-meta {
  float: right;
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.skill-running {
  font-size: 12px;
}
.skill-hint {
  margin: 8px 0 12px;
  font-size: 12px;
  line-height: 1.7;
}
.skill-cover {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--bg-elev);
}
.skill-cover-row {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: var(--text-secondary);
  flex-wrap: wrap;
}
.skill-stat b {
  font-size: 15px;
  color: var(--text-primary);
}
.skill-stat.ok b {
  color: var(--success);
}
.skill-stat.mid b {
  color: var(--warning);
}
.skill-stat.warn b {
  color: var(--danger);
}
.skill-stat.total {
  margin-left: auto;
}
.skill-bar {
  display: flex;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-hover);
  margin: 10px 0 6px;
  overflow: hidden;
}
.skill-bar-ok {
  background: var(--success);
  transition: width 0.3s;
}
.skill-bar-mid {
  background: var(--warning);
  transition: width 0.3s;
}
.skill-cover-note {
  font-size: 12px;
  margin: 0;
  line-height: 1.7;
}
.skill-tabs {
  margin-top: 8px;
}
.skill-list,
.skill-map,
.skill-questions {
  max-height: 44vh;
  overflow: auto;
}
.skill-item {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.skill-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.skill-expand {
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  color: var(--text-secondary);
  display: inline-flex;
}
.skill-node {
  font-weight: 600;
}
.skill-count,
.skill-conf {
  font-size: 12px;
}
.skill-item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 6px;
}
.skill-q {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 8px;
  border-radius: 8px;
  font-size: 13px;
}
.skill-q:nth-child(odd) {
  background: var(--bg-elev);
}
.skill-q-no {
  min-width: 30px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.skill-q-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skill-q-conf {
  font-size: 12px;
}
.skill-q-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}
.skill-more {
  font-size: 12px;
  margin: 6px 0 0;
}
.skill-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
}
.skill-stage {
  margin-bottom: 12px;
}
.skill-stage-name {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}
.skill-map-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 13px;
}
.skill-map-row:nth-child(even) {
  background: var(--bg-elev);
}
.skill-map-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skill-map-count {
  font-size: 12px;
}
.skill-map-tag {
  font-size: 12px;
}
.skill-map-tag.ok {
  color: var(--success);
}
.skill-map-tag.warn {
  color: var(--warning);
}
.skill-foot {
  float: left;
  font-size: 12px;
  line-height: 32px;
}
</style>
