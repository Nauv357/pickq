<template>
  <el-dialog
    v-model="visible"
    :title="t('title')"
    width="min(96vw, 1000px)"
    align-center
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <!-- 技能图选择 + 分析按钮 -->
    <div class="skill-toolbar">
      <div class="skill-toolbar-left">
        <span class="skill-label">{{ t('template') }}</span>
        <el-select v-model="templateId" size="small" style="width: 230px" :disabled="running">
          <el-option v-for="tpl in templates" :key="tpl.templateId" :label="tpl.name" :value="tpl.templateId">
            <span>{{ tpl.name }}</span>
            <span class="skill-opt-meta">{{ tpl.nodeCount }} {{ t('nodes') }}</span>
          </el-option>
        </el-select>
      </div>
      <div class="skill-toolbar-right">
        <button class="btn btn-secondary btn-sm" :disabled="!templateId || loading" @click="managerOpen = true">
          <TikuIcon name="settings" :size="13" />
          {{ t('manageNodes') }}
        </button>
        <span v-if="running" class="skill-running text-muted">{{ t('running', { done: progressDone, total: progressTotal }) }}</span>
        <button v-else class="btn btn-primary btn-sm" :disabled="!templateId || loading" @click="startTagging">
          {{ counts.confirmed + counts.pending > 0 ? t('retag') : t('start') }}
        </button>
      </div>
    </div>

    <p class="skill-hint text-muted">{{ t('hint') }}</p>

    <!-- 覆盖概览：三个口径互不重叠（相加 = 总题数），另单列"可用于判定掌握" -->
    <div v-if="coverage" class="skill-cover">
      <div class="skill-cover-row">
        <span class="skill-stat ok"><b>{{ counts.confirmed }}</b> {{ t('confirmed') }}</span>
        <span class="skill-stat mid"><b>{{ counts.pending }}</b> {{ t('pending') }}</span>
        <span class="skill-stat warn"><b>{{ counts.untagged }}</b> {{ t('untagged') }}</span>
        <span class="skill-stat total">{{ t('totalN', { n: counts.total }) }}</span>
      </div>
      <div class="skill-bar">
        <div class="skill-bar-ok" :style="{ width: pct(counts.confirmed) + '%' }"></div>
        <div class="skill-bar-mid" :style="{ width: pct(counts.pending) + '%' }"></div>
      </div>
      <p class="skill-cover-note text-muted">{{ t('coverNote', { usable: counts.usable }) }}</p>
    </div>

    <!-- 状态筛选（不是页签：同一件事的不同状态，随时点、随时叠加知识点筛选） -->
    <div class="skill-filters">
      <button
        v-for="f in statusFilters"
        :key="f.value"
        class="skill-chip"
        :class="{ on: status === f.value }"
        @click="setStatus(f.value)"
      >
        {{ f.label }}
      </button>
      <el-select
        v-model="nodeFilter"
        class="skill-node-filter"
        size="small"
        clearable
        filterable
        :placeholder="t('filterNode')"
        style="width: 230px"
        @change="reload(1)"
      >
        <el-option-group v-for="g in nodeGroupsWithCounts" :key="g.name" :label="g.name">
          <el-option v-for="n in g.nodes" :key="n.nodeId" :label="n.label" :value="n.nodeId" />
        </el-option-group>
      </el-select>
      <span class="text-muted skill-count-hint">{{ t('listCount', { n: total }) }}</span>
    </div>
    <!-- 缺口提示（原来在"覆盖地图"页签里；现在并入知识点筛选，一眼能看到哪些知识点还没有题） -->
    <p v-if="emptyNodes.length" class="skill-gap text-muted">
      {{ t('gapHint', { n: emptyNodes.length, names: emptyNodes.slice(0, 3).map((x) => x.name).join('、') }) }}
    </p>
    <!-- 失效标签：技能图升级（或删掉自定义知识点）后，指向已不存在节点的旧标签。
         它们不显示、也不参与统计，但会留在库里（题目详情里也不会再出现了）→ 给一个一键清理。 -->
    <p v-if="orphan.rows > 0" class="skill-gap warn">
      {{ t('orphanHint', { rows: orphan.rows, questions: orphan.questions }) }}
      <button class="btn btn-ghost btn-sm" @click="cleanupOrphans">{{ t('orphanClean') }}</button>
    </p>

    <!-- 题目清单：标签就地可改，不必跳转编辑器 -->
    <div class="skill-list-head">
      <el-checkbox
        :model-value="allPageSelected"
        :indeterminate="somePageSelected"
        @change="toggleSelectPage"
      >
        {{ t('selectPage') }}
      </el-checkbox>
      <button v-if="total > records.length" class="btn btn-ghost btn-sm" @click="selectAllMatching">
        {{ t('selectAllN', { n: total }) }}
      </button>
      <span v-if="selectAllFlag" class="skill-selall text-muted">
        {{ t('selectedAllHint', { n: total }) }}
        <button class="btn btn-ghost btn-sm" @click="clearSelection">{{ t('clearSelection') }}</button>
      </span>
    </div>
    <div v-loading="loading" class="skill-list">
      <div v-if="!loading && records.length === 0" class="skill-empty text-muted">
        {{ status === 'untagged' ? t('noUntagged') : t('noRecords') }}
      </div>
      <div v-for="row in records" :key="row.questionId" class="skill-row" :class="{ sel: selected.includes(row.questionId) }">
        <el-checkbox
          :model-value="selected.includes(row.questionId)"
          class="skill-check"
          @change="(v) => toggleSelect(row.questionId, v)"
        />
        <div class="skill-row-main">
          <div class="skill-row-head">
            <span class="skill-no">{{ row.questionNumber ?? '—' }}</span>
            <span class="skill-type text-muted">{{ typeLabel(row.type) }}</span>
            <span class="skill-status" :class="row.status">{{ statusLabel(row.status) }}</span>
            <span v-if="row.tags.length === 0" class="skill-none text-muted">{{ t('noTagYet') }}</span>
          </div>
          <div class="skill-row-body">
            <span class="skill-stem" :title="row.preview" @click="toggleExpand(row)">{{ row.preview }}</span>
            <!-- 标签就地改：多选下拉（按阶段分组、可直接输入新名称新建自定义知识点），改完立刻生效 -->
            <el-select
              class="skill-tag-select"
              :model-value="row.tags.map((x) => x.nodeId)"
              multiple
              filterable
              allow-create
              default-first-option
              collapse-tags
              collapse-tags-tooltip
              size="small"
              :placeholder="t('pickTag')"
              @change="(v) => setRowTags(row, v)"
            >
              <el-option-group v-for="g in nodeGroups" :key="g.name" :label="g.name">
                <el-option v-for="n in g.nodes" :key="n.nodeId" :label="n.name" :value="n.nodeId" />
              </el-option-group>
            </el-select>
          </div>
          <div class="skill-row-foot">
            <!-- 标签本身就在上面的下拉里，这里只说"这条标签是谁定的、把不把握"，避免同一个标签显示两遍 -->
            <span class="skill-src text-muted">{{ sourceSummary(row) }}</span>
            <span class="skill-row-actions">
              <button v-if="hasPending(row)" class="btn btn-primary btn-sm" @click="confirmRow(row)">{{ t('confirmRow') }}</button>
              <button v-if="hasPending(row)" class="btn btn-ghost btn-sm" @click="rejectRow(row)">{{ t('rejectRow') }}</button>
              <button class="btn btn-ghost btn-sm" @click="toggleExpand(row)">
                {{ expanded[row.questionId] ? t('collapse') : t('expand') }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="openQuestion(row.questionId)">{{ t('detail') }}</button>
            </span>
          </div>

          <!-- 就地展开题干：审阅时最需要"这题到底是什么题" -->
          <div v-if="expanded[row.questionId]" class="skill-detail">
            <div v-if="detailLoading[row.questionId]" class="text-muted skill-detail-loading">{{ t('loading') }}</div>
            <template v-else-if="details[row.questionId]">
              <p class="skill-detail-stem">{{ details[row.questionId].content }}</p>
              <p v-for="opt in details[row.questionId].options || []" :key="opt.key" class="skill-detail-opt">
                <b>{{ opt.key }}.</b> {{ opt.text }}
              </p>
              <p v-if="answerText(row)" class="skill-detail-line">
                <span class="text-muted">{{ t('answer') }}</span>{{ answerText(row) }}
              </p>
              <p v-if="details[row.questionId].analysis" class="skill-detail-line">
                <span class="text-muted">{{ t('analysis') }}</span>{{ details[row.questionId].analysis }}
              </p>
            </template>
          </div>
        </div>
      </div>
    </div>

    <div class="skill-pager">
      <el-pagination
        v-if="total > pageSize"
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        small
        @current-change="reload"
      />
    </div>

    <!-- 批量条：勾选后统一处理 -->
    <div v-if="selected.length || selectAllFlag" class="skill-batch">
      <span class="skill-batch-label">{{ t('selectedN', { n: effectiveCount }) }}</span>
      <el-select
        v-model="batchNode"
        size="small"
        clearable
        filterable
        allow-create
        default-first-option
        :placeholder="t('batchPick')"
        style="width: 210px"
      >
        <el-option-group v-for="g in nodeGroups" :key="g.name" :label="g.name">
          <el-option v-for="n in g.nodes" :key="n.nodeId" :label="n.name" :value="n.nodeId" />
        </el-option-group>
      </el-select>
      <button class="btn btn-primary btn-sm" :disabled="!batchNode" @click="applyBatch('set')">{{ t('batchSet') }}</button>
      <button class="btn btn-secondary btn-sm" @click="applyBatch('confirm')">{{ t('batchConfirm') }}</button>
      <button class="btn btn-ghost btn-sm" @click="applyBatch('reject')">{{ t('batchReject') }}</button>
      <button class="btn btn-ghost btn-sm" @click="clearSelection">{{ t('clearSelection') }}</button>
    </div>

    <template #footer>
      <span class="skill-foot text-muted">{{ t('footNote') }}</span>
      <button class="btn btn-secondary" @click="visible = false">{{ t('close') }}</button>
    </template>

    <!-- 词表管理：自定义知识点增删改 + 官方节点停用/恢复 + 恢复官方模板（只影响本机） -->
    <SkillNodeManager
      v-model="managerOpen"
      :template-id="templateId"
      :template-name="currentTemplateName"
      :bank-id="Number(bankId)"
      @changed="onNodesChanged"
    />
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { applySkills, cleanupOrphanTags, createSkillNode, getSkillCoverage, getSkillQuestions, getSkillTemplate, getSkillTemplates, suggestSkills } from '../api/skills'
import { getQuestion } from '../api/questions'
import { startBusy, stopBusy, updateBusy } from '../utils/busy'
import TikuIcon from './TikuIcon.vue'
import SkillNodeManager from './SkillNodeManager.vue'

const props = defineProps({
  bankId: { type: [Number, String], required: true }
})
/** 「详情」= 打开题目编辑器（由题库详情页处理）：可选动作，不是改标签的唯一路径 */
const emit = defineEmits(['open-question'])

const { t } = useI18n({
  messages: {
    zh: {
      title: '知识点标签',
      template: '技能图',
      nodes: '个知识点',
      start: '分析并标注',
      retag: '重新分析',
      manageNodes: '管理知识点',
      running: '正在分析…（已处理 {done} / 约 {total} 题）',
      hint: 'AI 把题目对应到技能图的知识点，用于筛选、讲解与"下一步练什么"。结果先作为建议，你确认后才生效；低把握的建议只作参考。标签可以在下面直接改，不必打开题目；词表本身也能改（右上「管理知识点」）。',
      confirmed: '已确认',
      pending: '待确认',
      untagged: '未匹配',
      totalN: '共 {n} 题',
      coverNote: '三段互不重叠（相加 = 总题数）；「未匹配」= AI 连建议都没给出，可在下面直接指定。其中可用于判定掌握的已有 {usable} 题；没有标签的题照常练，不影响刷题与复习。',
      filterNode: '按知识点筛选',
      nodeCount: '（{n} 题）',
      nodeEmpty: '（你没有题）',
      gapHint: '有 {n} 个知识点你还没有题（{names}…）——这是"还缺什么"的信号，点筛选里的名字只看某个知识点的题。',
      listCount: '当前 {n} 题',
      noRecords: '这里还没有题目',
      noUntagged: '所有题都有标签了',
      noTagYet: '还没有标签',
      selectPage: '全选本页',
      selectAllN: '选中全部 {n} 题',
      selectedAllHint: '已选中当前筛选下的全部 {n} 题（含未显示的页）',
      orphanHint: '有 {rows} 条旧标签指向的知识点已经不在当前技能图里（技能图升级或自定义知识点被删），它们不显示也不参与统计，涉及 {questions} 题。',
      orphanClean: '清理这些旧标签',
      msgOrphanCleaned: '已清理 {n} 条旧标签',
      srcHuman: '你标注的（已生效）',
      srcAiPending: 'AI 建议 AI {c}（待确认）',
      srcAiConfirmed: 'AI 建议 {c}（已确认）',
      pickTag: '选择知识点（可多选）',
      confirmRow: '确认',
      rejectRow: '丢弃建议',
      expand: '看题干',
      collapse: '收起',
      detail: '详情',
      loading: '加载中…',
      answer: '答案：',
      analysis: '解析：',
      selectedN: '已选 {n} 题：',
      batchPick: '设为知识点…',
      batchSet: '设为知识点',
      batchConfirm: '确认建议',
      batchReject: '丢弃建议',
      clearSelection: '清空选择',
      footNote: '标签只保存在本机；导出题库时随包带走（作者确认过的标签）。',
      close: '关闭',
      statusAll: '全部 {n}',
      statusConfirmed: '已确认 {n}',
      statusPending: '待确认 {n}',
      statusUntagged: '未匹配 {n}',
      typeSingle: '单选',
      typeMultiple: '多选',
      typeJudge: '判断',
      typeSubjective: '主观',
      msgDone: '分析完成：已标注 {tagged} 题',
      msgTruncated: '本次已处理到上限，可再次点击继续',
      msgConfirmed: '已确认 {n} 题的建议',
      msgRejected: '已丢弃建议',
      msgTagSet: '已把 {n} 题的知识点设为「{name}」',
      msgTagCleared: '已清空这些题的知识点',
      msgFailed: '操作失败，请稍后再试'
    },
    en: {
      title: 'Knowledge tags',
      template: 'Skill map',
      nodes: 'nodes',
      start: 'Analyze & tag',
      retag: 'Re-analyze',
      manageNodes: 'Manage topics',
      running: 'Analyzing… ({done} / ~{total} questions)',
      hint: 'The AI maps questions onto knowledge nodes, used for filtering, explanations and picking what to practise next. Results are suggestions until confirmed; low-confidence ones are hints only. You can edit tags right here — no need to open the question.',
      confirmed: 'confirmed',
      pending: 'to confirm',
      untagged: 'unmatched',
      totalN: '{n} questions in total',
      coverNote: 'The three segments do not overlap (they sum to the total). "Unmatched" = the AI produced no suggestion at all; assign one right here. Usable for mastery: {usable}. Untagged questions still work normally in practice and review.',
      filterNode: 'Filter by node',
      nodeCount: ' ({n} q)',
      nodeEmpty: ' (no questions)',
      gapHint: '{n} knowledge nodes have no questions yet ({names}…) — use the filter to see any single node.',
      listCount: '{n} here',
      noRecords: 'Nothing here yet',
      noUntagged: 'Every question has a tag now',
      noTagYet: 'no tag yet',
      selectPage: 'Select page',
      selectAllN: 'Select all {n}',
      selectedAllHint: 'All {n} questions under the current filter are selected (including other pages)',
      orphanHint: '{rows} legacy tags point to nodes that no longer exist in this skill map (graph upgrade or a deleted custom node). They are invisible and unused, affecting {questions} questions.',
      orphanClean: 'Clean them up',
      msgOrphanCleaned: 'Cleaned {n} legacy tags',
      srcHuman: 'yours (active)',
      srcAiPending: 'AI suggestion {c} (to confirm)',
      srcAiConfirmed: 'AI suggestion {c} (confirmed)',
      pickTag: 'Pick knowledge nodes',
      confirmRow: 'Confirm',
      rejectRow: 'Discard',
      expand: 'Show stem',
      collapse: 'Collapse',
      detail: 'Open',
      loading: 'Loading…',
      answer: 'Answer: ',
      analysis: 'Analysis: ',
      selectedN: '{n} selected:',
      batchPick: 'Set nodes…',
      batchSet: 'Set nodes',
      batchConfirm: 'Confirm',
      batchReject: 'Discard',
      clearSelection: 'Clear',
      footNote: 'Tags are stored locally and travel with the bank file (author-confirmed tags).',
      close: 'Close',
      statusAll: 'All {n}',
      statusConfirmed: 'Confirmed {n}',
      statusPending: 'To confirm {n}',
      statusUntagged: 'Unmatched {n}',
      typeSingle: 'Single',
      typeMultiple: 'Multiple',
      typeJudge: 'Judge',
      typeSubjective: 'Essay',
      msgDone: 'Done: tagged {tagged}',
      msgTruncated: 'Reached this round’s limit — you can continue',
      msgConfirmed: 'Confirmed suggestions for {n} questions',
      msgRejected: 'Suggestions discarded',
      msgTagSet: 'Set knowledge nodes of {n} questions to “{name}”',
      msgTagCleared: 'Knowledge nodes cleared',
      msgFailed: 'Action failed, please retry later'
    }
  }
})

const visible = ref(false)
const loading = ref(false)
const running = ref(false)
const templates = ref([])
const templateId = ref('')
const coverage = ref(null)
const listCounts = ref(null)
const orphan = ref({ rows: 0, questions: 0 })
const records = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(50)
const status = ref('all')
const nodeFilter = ref('')
const allNodes = ref([])
const selected = ref([])
/** 全选当前筛选下的全部题（跨页）：后端按筛选解析，不必把上千个 id 传到前端 */
const selectAllFlag = ref(false)
const batchNode = ref('')
const expanded = reactive({})
const details = reactive({})
const detailLoading = reactive({})
const progressDone = ref(0)
const progressTotal = ref(0)
/** 词表管理弹窗（自定义知识点增删改 / 官方节点停用 / 恢复官方模板） */
const managerOpen = ref(false)

const currentTemplateName = computed(
  () => templates.value.find((x) => x.templateId === templateId.value)?.name || templateId.value
)

/**
 * 计数直接用清单接口返回的 counts——它和 records 是后端**同一份快照**算出来的，
 * 所以"概览数字"和"清单条数"不可能对不上（用户实测过 26 vs 16 的口径打架）。
 */
const counts = computed(() => listCounts.value || { total: 0, confirmed: 0, pending: 0, untagged: 0, usable: 0 })

const statusFilters = computed(() => [
  { value: 'all', label: t('statusAll', { n: counts.value.total }) },
  { value: 'pending', label: t('statusPending', { n: counts.value.pending }) },
  { value: 'untagged', label: t('statusUntagged', { n: counts.value.untagged }) },
  { value: 'confirmed', label: t('statusConfirmed', { n: counts.value.confirmed }) }
])

const pct = (n) => {
  const t0 = counts.value.total || 0
  return t0 ? Math.round((n / t0) * 100) : 0
}

/* ---------- 选择（全选本页 / 全选当前筛选） ---------- */
const pageIds = computed(() => records.value.map((r) => r.questionId))
const allPageSelected = computed(() => pageIds.value.length > 0 && pageIds.value.every((id) => selected.value.includes(id)))
const somePageSelected = computed(() => pageIds.value.some((id) => selected.value.includes(id)) && !allPageSelected.value)
/** 批量条上显示的数量：全选模式下就是清单总条数 */
const effectiveCount = computed(() => (selectAllFlag.value ? total.value : selected.value.length))

function toggleSelectPage(checked) {
  selectAllFlag.value = false
  selected.value = checked
    ? [...new Set([...selected.value, ...pageIds.value])]
    : selected.value.filter((id) => !pageIds.value.includes(id))
}

function selectAllMatching() {
  selectAllFlag.value = true
  selected.value = [...pageIds.value]
}

function clearSelection() {
  selectAllFlag.value = false
  selected.value = []
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

/**
 * 知识点筛选里带题量：这样"哪些知识点我一道题都没有"不用另开一个覆盖地图页签，
 * 在筛选下拉里就能看见（阶段 0 的防漏刷提示不能丢）。
 */
const nodeGroupsWithCounts = computed(() => {
  const counts = new Map((coverage.value?.nodes || []).map((n) => [n.nodeId, n.questionCount]))
  return nodeGroups.value.map((g) => ({
    name: g.name,
    nodes: g.nodes.map((n) => {
      const c = counts.get(n.nodeId)
      const suffix = c === undefined ? '' : c === 0 ? t('nodeEmpty') : t('nodeCount', { n: c })
      return { nodeId: n.nodeId, name: n.name, label: `${n.name}${suffix}` }
    })
  }))
})

/** 一道题都没有的知识点（缺口） */
const emptyNodes = computed(() => (coverage.value?.nodes || []).filter((n) => n.questionCount === 0))

const TYPE_LABEL = { SINGLE: 'typeSingle', MULTIPLE: 'typeMultiple', JUDGE: 'typeJudge', SUBJECTIVE: 'typeSubjective' }
const typeLabel = (type) => (TYPE_LABEL[type] ? t(TYPE_LABEL[type]) : type || '')
const statusLabel = (s) => t(s === 'confirmed' ? 'confirmed' : s === 'pending' ? 'pending' : 'untagged')
const hasPending = (row) => row.tags.some((x) => x.source === 'ai' && !x.confirmed)

/** 一行文字说清这题的标签来自哪、把不把握（标签内容本身显示在上面的下拉里） */
function sourceSummary(row) {
  if (!row.tags.length) return t('noTagYet')
  const parts = []
  const human = row.tags.filter((x) => x.source === 'user' || x.source === 'author')
  const ai = row.tags.filter((x) => x.source === 'ai')
  if (human.length) parts.push(t('srcHuman'))
  const pendingAi = ai.filter((x) => !x.confirmed)
  const confirmedAi = ai.filter((x) => x.confirmed)
  if (pendingAi.length) {
    const best = Math.max(...pendingAi.map((x) => x.confidence ?? 0))
    parts.push(t('srcAiPending', { c: best.toFixed(2) }))
  }
  if (confirmedAi.length) {
    const best = Math.max(...confirmedAi.map((x) => x.confidence ?? 0))
    parts.push(t('srcAiConfirmed', { c: best.toFixed(2) }))
  }
  return parts.join(' · ')
}

async function open() {
  visible.value = true
  if (templates.value.length === 0) await loadTemplates()
  await refresh()
}

defineExpose({ open })

async function loadTemplates() {
  try {
    templates.value = (await getSkillTemplates()) || []
    if (!templateId.value && templates.value.length) templateId.value = templates.value[0].templateId
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 概览 + 清单 + 技能图一起刷新（概览与清单来自后端同一份快照，数字不会打架） */
async function refresh() {
  if (!templateId.value) return
  loading.value = true
  try {
    const [cov, pageData, tpl] = await Promise.all([
      getSkillCoverage(props.bankId, templateId.value),
      getSkillQuestions(props.bankId, {
        templateId: templateId.value,
        status: status.value,
        nodeId: nodeFilter.value || undefined,
        page: page.value,
        size: pageSize.value
      }),
      getSkillTemplate(templateId.value)
    ])
    coverage.value = cov
    listCounts.value = pageData?.counts || null
    records.value = pageData?.records || []
    total.value = pageData?.total || 0
    orphan.value = pageData?.orphan || { rows: 0, questions: 0 }
    allNodes.value = tpl?.nodes || []
    // 翻页/换筛选后保留勾选（跨页批量是正常需求），只丢掉"已经不在库里"的
    const alive = new Set(records.value.map((r) => r.questionId))
    selected.value = selected.value.filter((id) => alive.has(id) || !selectAllFlag.value)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function reload(nextPage = page.value) {
  page.value = nextPage
  refresh()
}

/** 词表被改过（自定义节点 / 停用官方节点）→ 下拉与统计口径都要跟着刷新 */
function onNodesChanged() {
  refresh()
}

function setStatus(value) {
  status.value = value
  selectAllFlag.value = false
  selected.value = []
  reload(1)
}

watch(templateId, () => {
  if (visible.value) { page.value = 1; refresh() }
})

function toggleSelect(questionId, checked) {
  if (checked) {
    if (!selected.value.includes(questionId)) selected.value = [...selected.value, questionId]
  } else {
    selected.value = selected.value.filter((id) => id !== questionId)
  }
}

async function toggleExpand(row) {
  const id = row.questionId
  if (expanded[id]) { expanded[id] = false; return }
  expanded[id] = true
  if (details[id]) return
  detailLoading[id] = true
  try {
    details[id] = await getQuestion(id)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    detailLoading[id] = false
  }
}

function answerText(row) {
  const d = details[row.questionId]
  if (!d) return ''
  if (d.answerKeys && d.answerKeys.length) return d.answerKeys.join(' ')
  return d.answerText || d.referenceAnswer || ''
}

/**
 * 用户在下拉里直接输入了一个图里没有的名字 → 新建自定义知识点（本机私有词表）。
 * 同名会复用已有节点（后端保证），所以"输两次同样的名字"不会造出两个节点。
 */
async function resolveNodeIds(values) {
  const known = allNodes.value.map((n) => n.nodeId)
  const picked = []
  const typed = []
  for (const v of values || []) {
    if (known.includes(v)) {
      picked.push(v)
    } else if (String(v).trim()) {
      typed.push(String(v).trim())
    }
  }
  if (!typed.length) return picked
  let createdAny = false
  for (const name of typed) {
    const node = await createSkillNode(templateId.value, name)
    if (node?.nodeId) {
      picked.push(node.nodeId)
      createdAny = true
    }
  }
  if (createdAny) {
    // 重新拉图：新节点要立刻出现在下拉里（否则这一行显示的是个"不存在的值"）
    await refresh()
  }
  return picked
}

/**
 * 就地改标签：把这一题的标签设定为选中的节点（写成"你标注的"，覆盖 AI 建议）。
 * 清空下拉 = 这题没有知识点（会回到"未匹配"）；输入新名字 = 新建自定义知识点并打上。
 */
async function setRowTags(row, values) {
  try {
    const nodeIds = await resolveNodeIds(values)
    const res = await applySkills(props.bankId, {
      action: 'set',
      templateId: templateId.value,
      questionIds: [row.questionId],
      newNodes: nodeIds
    })
    await refresh()
    if (nodeIds.length) {
      const name = allNodes.value.find((n) => n.nodeId === nodeIds[nodeIds.length - 1])?.name || ''
      ElMessage.success(t('msgTagSet', { n: 1, name }))
    } else {
      ElMessage.success(t('msgTagCleared'))
    }
    return res
  } catch (e) {
    await refresh()
    return null
  }
}

async function confirmRow(row) {
  const pendingIds = row.tags.filter((x) => x.source === 'ai' && !x.confirmed).map((x) => x.nodeId)
  let n = 0
  for (const nodeId of pendingIds) {
    const res = await applySkills(props.bankId, {
      action: 'confirm',
      templateId: templateId.value,
      nodeId,
      questionIds: [row.questionId]
    })
    n += res?.affected || 0
  }
  await refresh()
  ElMessage.success(t('msgConfirmed', { n: Math.max(n, 1) }))
}

async function rejectRow(row) {
  const pendingIds = row.tags.filter((x) => x.source === 'ai' && !x.confirmed).map((x) => x.nodeId)
  for (const nodeId of pendingIds) {
    await applySkills(props.bankId, {
      action: 'reject',
      templateId: templateId.value,
      nodeId,
      questionIds: [row.questionId]
    })
  }
  await refresh()
  ElMessage.success(t('msgRejected'))
}

/**
 * 批量：设为知识点 / 确认建议 / 丢弃建议。
 * 全选模式下只把**筛选条件**发给后端（由它解析成题目），避免把上千个 id 传到前端再传回去。
 */
async function applyBatch(action) {
  if (!selected.value.length && !selectAllFlag.value) return
  const scope = selectAllFlag.value
    ? { filterStatus: status.value, nodeId: nodeFilter.value || undefined }
    : { questionIds: selected.value }
  try {
    if (action === 'set') {
      if (!batchNode.value) return
      const nodeIds = await resolveNodeIds([batchNode.value])
      const res = await applySkills(props.bankId, {
        action: 'set',
        templateId: templateId.value,
        newNodes: nodeIds,
        ...scope
      })
      const name = allNodes.value.find((n) => n.nodeId === nodeIds[0])?.name || batchNode.value
      ElMessage.success(t('msgTagSet', { n: res?.affected ?? effectiveCount.value, name }))
    } else if (action === 'confirm') {
      const res = await applySkills(props.bankId, {
        action: 'confirm',
        templateId: templateId.value,
        ...scope
      })
      ElMessage.success(t('msgConfirmed', { n: res?.affected ?? 0 }))
    } else {
      await applySkills(props.bankId, {
        action: 'reject',
        templateId: templateId.value,
        ...scope
      })
      ElMessage.success(t('msgRejected'))
    }
    clearSelection()
    batchNode.value = ''
    await refresh()
  } catch (e) {
    ElMessage.error(t('msgFailed'))
    await refresh()
  }
}

/** 清理失效标签（节点已不在当前技能图里） */
async function cleanupOrphans() {
  try {
    const res = await cleanupOrphanTags(props.bankId, templateId.value)
    ElMessage.success(t('msgOrphanCleaned', { n: res?.affected ?? 0 }))
    await refresh()
  } catch (e) {
    ElMessage.error(t('msgFailed'))
  }
}

/**
 * 按批推进分析：一次请求只花 maxAiCalls 次模型调用（大题库跑不完），
 * 循环到没有进展为止；进度用「还没有可用标签的题数」减少来体现，可随时停止。
 */
const BATCH_CALLS = 5

async function startTagging() {
  if (running.value || !templateId.value) return
  running.value = true
  progressTotal.value = Math.max(0, counts.value.total - counts.value.usable)
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

function openQuestion(questionId) {
  emit('open-question', questionId)
  visible.value = false
}

function onClosed() {
  coverage.value = null
  listCounts.value = null
  records.value = []
  selected.value = []
  Object.keys(expanded).forEach((k) => delete expanded[k])
  Object.keys(details).forEach((k) => delete details[k])
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
.skill-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0 8px;
  flex-wrap: wrap;
}
.skill-chip {
  border: 1px solid var(--border);
  background: var(--bg-card);
  border-radius: 14px;
  padding: 3px 12px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
}
.skill-chip:hover {
  border-color: var(--accent);
  color: var(--text-primary);
}
.skill-chip.on {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.skill-count-hint {
  margin-left: auto;
  font-size: 12px;
}
.skill-gap {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.7;
}
.skill-list {
  min-height: 220px;
  max-height: 46vh;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 10px;
}
.skill-list-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 2px 6px;
  font-size: 12px;
}
.skill-selall {
  margin-left: auto;
}
.skill-gap.warn {
  color: var(--warning);
}
.skill-empty {
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
}
.skill-row {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}
.skill-row:last-child {
  border-bottom: none;
}
.skill-row.sel {
  background: var(--accent-soft);
}
.skill-check {
  margin-top: 3px;
}
.skill-row-main {
  flex: 1;
  min-width: 0;
}
.skill-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.skill-no {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--text-primary);
}
.skill-status.ok {
  color: var(--success);
}
.skill-status.pending {
  color: var(--warning);
}
.skill-status.untagged {
  color: var(--danger);
}
.skill-row-body {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0;
}
.skill-stem {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  font-size: 13px;
}
.skill-stem:hover {
  color: var(--accent-text);
}
.skill-tag-select {
  width: 300px;
  flex-shrink: 0;
}
.skill-row-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.skill-src {
  font-size: 12px;
}
.skill-row-actions {
  margin-left: auto;
  display: flex;
  gap: 4px;
}
.skill-detail {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--bg-elev);
  font-size: 13px;
  line-height: 1.75;
}
.skill-detail-stem {
  margin: 0 0 4px;
  white-space: pre-wrap;
}
.skill-detail-opt {
  margin: 0;
}
.skill-detail-line {
  margin: 4px 0 0;
}
.skill-detail-loading {
  font-size: 12px;
}
.skill-pager {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}
.skill-batch {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-elev);
  flex-wrap: wrap;
}
.skill-batch-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.skill-foot {
  float: left;
  font-size: 12px;
  line-height: 32px;
}
</style>
