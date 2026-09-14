<template>
  <el-dialog
    v-model="visible"
    :title="t('title')"
    width="min(94vw, 880px)"
    align-center
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <!-- 技能图选择 + 分析按钮 -->
    <div class="skill-toolbar">
      <div class="skill-toolbar-left">
        <span class="skill-label">{{ t('template') }}</span>
        <el-select v-model="templateId" size="small" style="width: 260px" :disabled="running">
          <el-option v-for="tpl in templates" :key="tpl.templateId" :label="tpl.name" :value="tpl.templateId">
            <span>{{ tpl.name }}</span>
            <span class="skill-opt-meta">{{ tpl.nodeCount }} {{ t('nodes') }}</span>
          </el-option>
        </el-select>
        <span v-if="templateVersion" class="skill-version text-muted">{{ templateVersion }}</span>
      </div>
      <div class="skill-toolbar-right">
        <span v-if="running" class="skill-running text-muted">{{ t('running', { done: progressDone, total: progressTotal }) }}</span>
        <button v-else-if="stopped" class="btn btn-secondary btn-sm" @click="startTagging">
          {{ t('continue') }}
        </button>
        <button v-else class="btn btn-primary btn-sm" :disabled="!templateId || loading" @click="startTagging">
          {{ hasSuggestions || hasTags ? t('retag') : t('start') }}
        </button>
      </div>
    </div>

    <p class="skill-hint text-muted">{{ t('hint') }}</p>

    <!-- 覆盖概览 -->
    <div v-if="coverage" class="skill-cover">
      <div class="skill-cover-row">
        <span class="skill-stat"><b>{{ coverage.totalQuestions }}</b> {{ t('total') }}</span>
        <span class="skill-stat"><b>{{ coverage.coveredQuestions }}</b> {{ t('covered') }}</span>
        <span class="skill-stat"><b>{{ coverage.confirmedQuestions }}</b> {{ t('confirmed') }}</span>
        <span class="skill-stat warn"><b>{{ coverage.untaggedQuestions }}</b> {{ t('untagged') }}</span>
      </div>
      <div class="skill-bar">
        <div class="skill-bar-fill" :style="{ width: coverPercent + '%' }"></div>
      </div>
      <p class="skill-cover-note text-muted">{{ t('coverNote', { p: coverPercent }) }}</p>
    </div>

    <el-tabs v-model="tab" class="skill-tabs">
      <!-- 待确认（AI 建议） -->
      <el-tab-pane :label="t('tabPending', { n: pending.length })" name="pending">
        <div v-if="loading" class="skill-empty text-muted">{{ t('loading') }}</div>
        <div v-else-if="pending.length === 0" class="skill-empty text-muted">{{ t('noPending') }}</div>
        <div v-else class="skill-list">
          <div v-for="p in pending" :key="p.nodeId" class="skill-item">
            <div class="skill-item-head">
              <span class="skill-node">{{ p.name }}</span>
              <span class="text-muted skill-count">{{ t('questions', { n: p.questionCount }) }}</span>
              <span class="text-muted skill-conf">{{ t('confidence', { c: p.avgConfidence }) }}</span>
            </div>
            <div class="skill-samples text-muted">
              <div v-for="s in p.samples" :key="s.questionId" class="skill-sample">· {{ s.preview }}</div>
            </div>
            <div class="skill-item-actions">
              <button class="btn btn-primary btn-sm" @click="confirmNode(p)">{{ t('confirm') }}</button>
              <el-select
                v-model="retagTarget[p.nodeId]"
                size="small"
                clearable
                :placeholder="t('retagTo')"
                style="width: 200px"
              >
                <el-option v-for="n in allNodes" :key="n.nodeId" :label="n.name" :value="n.nodeId" />
              </el-select>
              <button class="btn btn-secondary btn-sm" :disabled="!retagTarget[p.nodeId]" @click="retagNode(p)">
                {{ t('move') }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="rejectNode(p)">{{ t('reject') }}</button>
            </div>
          </div>
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
import { applySkills, getPendingSkills, getSkillCoverage, getSkillTemplate, getSkillTemplates, suggestSkills } from '../api/skills'
import { startBusy, stopBusy } from '../utils/busy'

const props = defineProps({
  bankId: { type: [Number, String], required: true }
})

const { t } = useI18n({
  messages: {
    zh: {
      title: '知识点标签',
      template: '技能图',
      nodes: '个知识点',
      start: '分析并标注',
      retag: '重新分析',
      continue: '继续分析',
      running: '正在分析…（已处理 {done} / 约 {total} 题）',
      hint: 'AI 会把题目对应到技能图的知识点，用于后面的学习路线、缺口分析与掌握度判定。结果先作为建议，你确认后才生效；置信度低的建议只作参考，不会用来判断"已掌握"。',
      total: '题',
      covered: '已有标签',
      confirmed: '你已确认',
      untagged: '待标注',
      coverNote: '覆盖 {p}%：标签越全，后面的"还缺什么"越准。题库里的题不会被排除在练习之外，缺标签只影响知识点维度的统计。',
      tabPending: '待确认（{n}）',
      tabMap: '覆盖地图',
      loading: '加载中…',
      noPending: '没有待确认的建议（先点右上角「分析并标注」）',
      questions: '{n} 题',
      confidence: '平均把握 {c}',
      confirm: '全部确认',
      retagTo: '改挂到…',
      move: '改挂',
      reject: '丢弃',
      noQuestions: '你没有这个知识点的题',
      thin: '题太少，暂不能判定掌握',
      enough: '题量足够',
      footNote: '标签只保存在本机；分享题库时可以带上（作者确认过的标签会随包分发）。',
      close: '关闭',
      msgStarted: '分析完成：已标注 {tagged} 题，剩余 {left} 题',
      msgTruncated: '本次已处理到上限，可继续分析',
      msgConfirmed: '已确认 {n} 条建议',
      msgRetagged: '已改挂 {n} 题',
      msgRejected: '已丢弃该节点的建议',
      msgFailed: '操作失败，请稍后再试'
    },
    en: {
      title: 'Knowledge tags',
      template: 'Skill map',
      nodes: 'nodes',
      start: 'Analyze & tag',
      retag: 'Re-analyze',
      continue: 'Continue',
      running: 'Analyzing… ({done} / ~{total} questions)',
      hint: 'The AI maps questions onto knowledge nodes, which later power the learning path, gap analysis and mastery checks. Results are suggestions until you confirm them; low-confidence ones are only used as hints, never to decide "mastered".',
      total: 'questions',
      covered: 'tagged',
      confirmed: 'confirmed',
      untagged: 'untagged',
      coverNote: 'Coverage {p}%: the more complete the tags, the more accurate the "what am I missing" view. Untagged questions still appear in practice — tags only affect knowledge-level stats.',
      tabPending: 'To confirm ({n})',
      tabMap: 'Coverage map',
      loading: 'Loading…',
      noPending: 'No suggestions yet (click "Analyze & tag" first)',
      questions: '{n} questions',
      confidence: 'avg confidence {c}',
      confirm: 'Confirm all',
      retagTo: 'Move to…',
      move: 'Move',
      reject: 'Discard',
      noQuestions: 'You have no questions for this node',
      thin: 'Too few questions to judge mastery',
      enough: 'Enough evidence',
      footNote: 'Tags are stored locally; they can travel with the bank file (author-confirmed tags ship with the package).',
      close: 'Close',
      msgStarted: 'Done: tagged {tagged}, {left} left',
      msgTruncated: 'Reached this round’s limit — you can continue',
      msgConfirmed: 'Confirmed {n} suggestions',
      msgRetagged: 'Moved {n} questions',
      msgRejected: 'Suggestions discarded',
      msgFailed: 'Action failed, please retry later'
    }
  }
})

const visible = ref(false)
const loading = ref(false)
const running = ref(false)
const stopped = ref(false)
const tab = ref('pending')
const templates = ref([])
const templateId = ref('')
const templateVersion = ref('')
const coverage = ref(null)
const pending = ref([])
const allNodes = ref([])
const retagTarget = reactive({})
const progressDone = ref(0)
const progressTotal = ref(0)

const coverPercent = computed(() => {
  if (!coverage.value || !coverage.value.totalQuestions) return 0
  return Math.round((coverage.value.coveredQuestions / coverage.value.totalQuestions) * 100)
})
const hasTags = computed(() => (coverage.value?.coveredQuestions ?? 0) > 0)
const hasSuggestions = computed(() => pending.value.length > 0)

/** 覆盖地图按阶段分组（阶段顺序来自技能图） */
const stages = computed(() => {
  if (!coverage.value) return []
  const order = []
  const byStage = new Map()
  for (const n of coverage.value.nodes) {
    const key = n.stageName || '—'
    if (!byStage.has(key)) {
      byStage.set(key, [])
      order.push(key)
    }
    byStage.get(key).push(n)
  }
  return order.map((name) => ({ name, nodes: byStage.get(name) }))
})

async function open() {
  visible.value = true
  if (templates.value.length === 0) {
    await loadTemplates()
  }
  await refresh()
}

defineExpose({ open })

async function loadTemplates() {
  loading.value = true
  try {
    templates.value = (await getSkillTemplates()) || []
    if (!templateId.value && templates.value.length) {
      templateId.value = templates.value[0].templateId
    }
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
    allNodes.value = (tpl?.nodes || []).map((n) => ({ nodeId: n.nodeId, name: n.stageName ? `${n.stageName} / ${n.name}` : n.name }))
    templateVersion.value = tpl?.version ? `v${tpl.version}` : ''
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

watch(templateId, () => {
  if (visible.value) refresh()
})

/**
 * 按批推进分析：一次请求只花 maxAiCalls 次模型调用（大题库跑不完），
 * 循环直到没有进展为止；进度用"未标注题数"减少来体现，可随时停止。
 */
const BATCH_CALLS = 5

async function startTagging() {
  if (running.value || !templateId.value) return
  running.value = true
  stopped.value = false
  progressDone.value = 0
  progressTotal.value = coverage.value?.untaggedQuestions || coverage.value?.totalQuestions || 0
  startBusy(t('running', { done: 0, total: progressTotal.value }))
  try {
    let lastUntagged = coverage.value?.untaggedQuestions ?? Number.MAX_SAFE_INTEGER
    for (let round = 0; round < 200; round++) {
      const res = await suggestSkills(props.bankId, { templateId: templateId.value, includeUntagged: true, maxAiCalls: BATCH_CALLS })
      progressDone.value = Math.max(0, progressTotal.value - (res?.untaggedQuestions ?? 0))
      stopBusy()
      startBusy(t('running', { done: progressDone.value, total: progressTotal.value }))
      await refresh()
      const left = res?.untaggedQuestions ?? 0
      if (!res?.truncated || left >= lastUntagged) {
        // 跑完（没有截断）或没有进展（例如模型都给不出标签）→ 收尾
        ElMessage[res?.truncated ? 'warning' : 'success'](
          res?.truncated
            ? t('msgTruncated')
            : t('msgStarted', { tagged: res?.taggedQuestions ?? 0, left })
        )
        break
      }
      lastUntagged = left
    }
  } catch (e) {
    /* 拦截器已提示（含"尚未配置 AI"这类可照做的提示） */
  } finally {
    stopBusy()
    running.value = false
    stopped.value = (coverage.value?.untaggedQuestions ?? 0) > 0
  }
}

async function confirmNode(p) {
  await apply('confirm', { nodeId: p.nodeId })
  ElMessage.success(t('msgConfirmed', { n: p.questionCount }))
}

async function retagNode(p) {
  const target = retagTarget[p.nodeId]
  if (!target) return
  await apply('retag', { nodeId: p.nodeId, newNodes: [target] })
  ElMessage.success(t('msgRetagged', { n: p.questionCount }))
}

async function rejectNode(p) {
  await apply('reject', { nodeId: p.nodeId })
  ElMessage.success(t('msgRejected'))
}

async function apply(action, extra) {
  try {
    await applySkills(props.bankId, { action, templateId: templateId.value, ...extra })
    await refresh()
  } catch (e) {
    ElMessage.error(t('msgFailed'))
  }
}

function onClosed() {
  coverage.value = null
  pending.value = []
  stopped.value = false
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
.skill-version {
  font-size: 12px;
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
  gap: 18px;
  font-size: 13px;
  color: var(--text-secondary);
}
.skill-stat b {
  color: var(--text-primary);
  font-size: 15px;
}
.skill-stat.warn b {
  color: var(--warning);
}
.skill-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--bg-hover);
  margin: 10px 0 6px;
  overflow: hidden;
}
.skill-bar-fill {
  height: 100%;
  background: var(--accent);
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
.skill-map {
  max-height: 46vh;
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
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.skill-node {
  font-weight: 600;
}
.skill-count,
.skill-conf {
  font-size: 12px;
}
.skill-samples {
  margin: 6px 0 8px;
  font-size: 12px;
  line-height: 1.7;
}
.skill-sample {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skill-item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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
