<template>
  <div class="print-page">
    <!-- 工具条（屏幕显示，打印隐藏） -->
    <div class="print-toolbar">
      <button class="btn btn-secondary btn-sm" @click="$router.push(`/banks/${id}`)">
        <TikuIcon name="arrow-left" :size="13" />
        {{ t('backToBank') }}
      </button>
      <div class="toolbar-right">
        <div class="scope-switch">
          <el-select v-model="scope" size="small" style="width: 100px" @change="reload">
            <el-option :label="t('all')" value="all" />
            <el-option :label="t('wrong')" value="wrong" />
            <el-option :label="t('favorite')" value="favorite" />
            <el-option :label="t('undone')" value="undone" />
          </el-select>
          <el-select v-model="category" size="small" clearable filterable :placeholder="t('category')" style="width: 116px" @change="reload">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </div>
        <div class="answer-switch">
          <span class="switch-label">{{ t('answerArea') }}：</span>
          <button :class="{ active: !showAnswer }" @click="showAnswer = false">{{ t('questionsOnly') }}</button>
          <button :class="{ active: showAnswer }" @click="showAnswer = true">{{ t('withAnswers') }}</button>
        </div>
        <button class="btn btn-primary btn-sm" @click="doPrint">
          <TikuIcon name="file" :size="13" />
          {{ t('printBtn') }}
        </button>
      </div>
    </div>
    <p class="print-tip">{{ t('printTip') }}</p>

    <!-- 加载 / 错误 -->
    <div v-if="loading" class="print-state">
      <div class="stage-spin"><TikuIcon name="refresh" :size="24" /></div>
      <p class="text-secondary">{{ t('generating') }}</p>
    </div>
    <div v-else-if="errorMsg" class="print-state">
      <p class="error-text">{{ errorMsg }}</p>
      <button class="btn btn-secondary btn-sm" @click="$router.push(`/banks/${id}`)">{{ t('backToBank') }}</button>
    </div>

    <!-- 试卷 -->
    <div v-else class="paper">
      <header class="paper-head">
        <h1 class="paper-title">{{ bankName }}</h1>
        <p class="paper-sub">{{ subLine }}</p>
      </header>

      <section v-for="(g, gi) in groups" :key="gi" class="paper-group">
        <!-- 材料置顶（该组题目共用的大题干） -->
        <div v-if="g.material" class="paper-material">
          <h3 class="material-title">{{ t('material') }} {{ g.material.materialKey }}</h3>
          <div class="material-body" v-html="richHtml(g.material.content)"></div>
        </div>

        <div v-for="q in g.questions" :key="q.key" class="paper-question">
          <div class="q-head">
            <span class="q-num mono">{{ q.no }}.</span>
            <span class="q-type">{{ typeLabel(q.type) }}</span>
            <span v-if="q.score != null" class="q-score mono">（{{ formatScore(q.score) }} {{ t('unitPoint') }}）</span>
          </div>
          <div class="q-content" v-html="richHtml(q.content)"></div>

          <!-- 客观题选项竖排 -->
          <div v-if="q.type !== 'SUBJECTIVE'" class="q-options">
            <div v-for="opt in q.options" :key="opt.key" class="q-opt">
              <span class="opt-key">{{ opt.key }}.</span>
              <span class="opt-text" v-html="richHtml(opt.text)"></span>
              <span v-if="showAnswer && isAnswer(q, opt.key)" class="opt-mark">✓</span>
            </div>
          </div>

          <!-- 答案 + 解析（带答案版） -->
          <template v-if="showAnswer">
            <div v-if="q.type === 'SUBJECTIVE'" class="q-answer-line">
              <span class="answer-label">{{ t('referenceAnswer') }}：</span>
              <span v-if="q.referenceAnswer" class="answer-text" v-html="richHtml(q.referenceAnswer)"></span>
              <span v-else class="answer-none">{{ t('none') }}</span>
            </div>
            <div v-else class="q-answer-line">
              <span class="answer-label">{{ t('answer') }}：</span>
              <span v-if="q.answerKeys && q.answerKeys.length" class="answer-text">{{ q.answerKeys.join('、') }}</span>
              <span v-else class="answer-none">{{ t('none') }}</span>
              <span v-if="q.answerSource === 'ORIGINAL'" class="src-tag">{{ t('origAnswer') }}</span>
              <span v-else-if="q.answerSource === 'AI_SUPPLEMENT'" class="src-tag src-ai">{{ t('aiAnswer') }}</span>
            </div>
            <div v-if="q.analysis" class="q-analysis" v-html="richHtml(q.analysis)"></div>
          </template>
        </div>
      </section>

      <footer class="paper-foot">— 试卷结束 —</footer>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      backToBank: '返回题库', all: '全部', wrong: '错题', favorite: '收藏', undone: '未做', category: '分类',
      answerArea: '答案区', questionsOnly: '纯题目', withAnswers: '带答案 + 解析', printBtn: '打印 / 另存为 PDF',
      printTip: '打印对话框可选「另存为 PDF」生成 PDF 文件；纯题目版适合学生作答，带答案版适合教师核对；范围与分类筛选即时生效。',
      generating: '正在生成试卷（导出数据较大时需数秒）…', material: '材料', referenceAnswer: '参考答案', answer: '答案', none: '（无）',
      origAnswer: '原文答案', aiAnswer: 'AI 补充', unitPoint: '分', rangeFallback: '范围筛选暂不可用（后端未支持该参数），已显示全部题目',
    },
    'en-US': {
      backToBank: 'Back to bank', all: 'All', wrong: 'Mistakes', favorite: 'Favorites', undone: 'Undone', category: 'Category',
      answerArea: 'Answers', questionsOnly: 'Questions only', withAnswers: 'With answers + analysis', printBtn: 'Print / Save as PDF',
      printTip: 'The print dialog offers "Save as PDF". Questions-only suits students taking the paper; with-answers suits teachers checking. Scope and category filters apply instantly.',
      generating: 'Generating the paper (may take a few seconds for large exports)…', material: 'Material', referenceAnswer: 'Reference answer', answer: 'Answer', none: '(none)',
      origAnswer: 'Original answer', aiAnswer: 'AI-filled', unitPoint: 'pts', rangeFallback: 'Range filter is unavailable (the server doesn’t support this parameter) — showing all questions',
    }
  }
})
import { useRoute } from 'vue-router'
import { exportBank } from '../api/banks'
import { getBankCategories } from '../api/sessions'
import { richTextToHtml } from '../utils/richText'
import { formatDate, formatScore } from '../utils/format'
import TikuIcon from '../components/TikuIcon.vue'

const route = useRoute()
const id = Number(route.params.id)

const loading = ref(true)
const errorMsg = ref('')
const bankName = ref('')
const bankVersion = ref('')
const authorName = ref('')
const materials = ref([])
const questions = ref([])
const showAnswer = ref(false) // false = 纯题目版（默认）；true = 带答案 + 解析版

/* 打印范围（第十轮：export body 支持 scope/category/topic，材料随引用过滤） */
const scope = ref('all')
const category = ref('')
const categories = ref([])

async function loadCategories() {
  try {
    const data = await getBankCategories(id)
    categories.value = data.categories || []
  } catch (e) {
    categories.value = []
  }
}

function reload() {
  load()
}

const TYPE_LABELS = { SINGLE: '单选', MULTIPLE: '多选', JUDGE: '判断', SUBJECTIVE: '主观' }
const typeLabel = (t) => TYPE_LABELS[t] || t || ''

function isAnswer(q, key) {
  return Array.isArray(q.answerKeys) && q.answerKeys.includes(key)
}

const subLine = computed(() => {
  const parts = []
  if (bankVersion.value) parts.push(`v${bankVersion.value}`)
  if (authorName.value) parts.push(`作者：${authorName.value}`)
  parts.push(`导出日期：${formatDate(new Date().toISOString())}`)
  parts.push(`共 ${questions.value.length} 题`)
  return parts.join('　·　')
})

/* 材料分组：材料置顶 + 组内题目（按材料在 questions 中首次出现顺序；无材料题独立成组） */
const groups = computed(() => {
  const matMap = new Map(materials.value.map((m) => [m.materialKey, m]))
  const order = [] // {key, material, questions}
  const byKey = new Map()
  let no = 0
  for (const q of questions.value) {
    const key = q.materialKey || ''
    if (!byKey.has(key)) {
      const group = { key: key || `__none${byKey.size}__`, material: key ? matMap.get(key) || null : null, questions: [] }
      byKey.set(key, group)
      order.push(group)
    }
    no += 1
    byKey.get(key).questions.push({ ...q, no })
  }
  return order
})

const richHtml = (text) => richTextToHtml(text, id)

function fillData(data) {
  bankName.value = data?.title || data?.name || `题库 #${id}`
  bankVersion.value = data?.version || ''
  authorName.value = data?.authorName || ''
  materials.value = Array.isArray(data?.materials) ? data.materials : []
  questions.value = Array.isArray(data?.questions) ? data.questions : []
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  // POST /api/banks/{id}/export，body 传空对象（纯只读导出，不写版本/不分支）→ 全量题目 + 材料
  // 注意：不能传 null/无 body——后端 @RequestBody(required=false) 对 null 处理有缺陷会 500，空对象 {} 正常
  // 第十轮：body 支持 scope/category/topic 打印范围（材料随引用自动过滤）
  const body = {
    scope: scope.value === 'all' ? undefined : scope.value,
    category: category.value || undefined
  }
  const hasRange = !!(body.scope || body.category)
  try {
    const data = await exportBank(id, body)
    fillData(data)
  } catch (e) {
    // 范围筛选失败（后端未支持/接口异常）→ 降级为全量导出，避免整页报错
    if (hasRange) {
      try {
        const data = await exportBank(id, {})
        fillData(data)
        ElMessage.warning(t('rangeFallback'))
      } catch (e2) {
        errorMsg.value = '试卷数据加载失败，请确认后端已启动后重试'
      }
    } else {
      errorMsg.value = '试卷数据加载失败，请确认后端已启动后重试'
    }
  }
  if (questions.value.length === 0) {
    errorMsg.value = '题库暂无题目，无法生成试卷'
  }
  loading.value = false
}

function doPrint() {
  window.print()
}

onMounted(() => {
  load()
  loadCategories()
})
</script>

<style scoped>
.print-page {
  min-height: 100vh;
  background: var(--bg-base);
  padding: 16px 20px 40px;
}

/* ---------- 工具条（屏幕显示，打印隐藏） ---------- */
.print-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  max-width: 820px;
  margin: 0 auto 6px;
  flex-wrap: wrap;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.scope-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.answer-switch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 3px;
}
.switch-label {
  font-size: 12px;
  color: var(--text-muted);
  padding: 0 6px;
}
.answer-switch button {
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: 13px;
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.answer-switch button.active {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
}
.print-tip {
  max-width: 820px;
  margin: 0 auto 14px;
  font-size: 12px;
  color: var(--text-muted);
}

/* ---------- 加载 / 错误 ---------- */
.print-state {
  max-width: 820px;
  margin: 60px auto;
  text-align: center;
  color: var(--text-secondary);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.error-text {
  color: var(--danger);
}

/* ---------- 试卷（屏幕白纸预览；打印时铺满） ---------- */
.paper {
  max-width: 820px;
  margin: 0 auto;
  background: #fff;
  color: #222;
  padding: 34px 42px 40px;
  border-radius: 4px;
  box-shadow: 0 6px 30px rgba(0, 0, 0, 0.35);
  font-size: 14px;
  line-height: 1.7;
}
.paper-head {
  text-align: center;
  border-bottom: 2px solid #222;
  padding-bottom: 12px;
  margin-bottom: 18px;
}
.paper-title {
  font-size: 22px;
  font-weight: 700;
  margin: 0 0 6px;
  color: #111;
}
.paper-sub {
  font-size: 13px;
  color: #555;
  margin: 0;
}

.paper-group {
  margin-bottom: 6px;
}
.paper-material {
  border: 1px solid #bbb;
  border-radius: 6px;
  padding: 10px 14px;
  margin-bottom: 14px;
  background: #fafafa;
  break-inside: avoid;
}
.material-title {
  font-size: 13px;
  font-weight: 700;
  margin: 0 0 6px;
  color: #333;
}

.paper-question {
  margin-bottom: 16px;
  break-inside: avoid;
}
.q-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}
.q-num {
  font-size: 14px;
  font-weight: 600;
  color: #111;
}
.q-type {
  font-size: 11px;
  color: #666;
  border: 1px solid #bbb;
  border-radius: 4px;
  padding: 0 5px;
  line-height: 16px;
}
.q-score {
  margin-left: auto;
  font-size: 12px;
  color: #666;
}
.q-content {
  margin: 2px 0 8px;
  white-space: normal;
}
.q-options {
  padding-left: 22px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.q-opt {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  break-inside: avoid;
}
.opt-key {
  flex-shrink: 0;
  font-weight: 600;
  min-width: 16px;
}
.opt-mark {
  color: #c0392b;
  font-weight: 700;
  margin-left: 3px;
}
.q-answer-line {
  margin-top: 6px;
  font-size: 13px;
}
.answer-label {
  font-weight: 700;
  color: #111;
}
.answer-text {
  font-weight: 700;
  color: #c0392b;
}
.answer-none {
  color: #999;
  font-style: italic;
}
.src-tag {
  margin-left: 8px;
  font-size: 11px;
  color: #2e7d32;
  border: 1px solid #2e7d32;
  border-radius: 4px;
  padding: 0 5px;
}
.src-tag.src-ai {
  color: #b26a00;
  border-color: #b26a00;
}
.q-analysis {
  margin-top: 5px;
  font-size: 13px;
  color: #666;
  border-left: 2px solid #ccc;
  padding-left: 10px;
}

.paper-foot {
  text-align: center;
  color: #999;
  font-size: 12px;
  margin-top: 26px;
}

/* 纸张内富文本（图片/表格）适配白底 */
.paper :deep(.rich-img) {
  border-radius: 4px;
  margin: 6px 0;
}
.paper :deep(.rich-table-wrap) {
  overflow-x: auto;
  margin: 8px 0;
}
.paper :deep(.rich-table-wrap table) {
  border-collapse: collapse;
  font-size: 13px;
  color: #222;
}
.paper :deep(.rich-table-wrap th),
.paper :deep(.rich-table-wrap td) {
  border: 1px solid #999;
  padding: 4px 8px;
}
.paper :deep(.rich-table-wrap th) {
  background: #f0f0f0;
  font-weight: 700;
}

/* ---------- 打印样式（独立，不影响现有页面） ---------- */
@page {
  size: A4;
  margin: 18mm 16mm;
}
@media print {
  .print-toolbar,
  .print-tip,
  .print-state {
    display: none !important;
  }
  body {
    background: #fff !important;
  }
  .print-page {
    background: #fff;
    padding: 0;
  }
  .paper {
    max-width: none;
    margin: 0;
    padding: 0;
    box-shadow: none;
    border-radius: 0;
  }
  .paper-question,
  .paper-material,
  .q-opt {
    break-inside: avoid;
  }
  .paper-group {
    break-inside: auto;
  }
}
</style>
