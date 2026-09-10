<template>
  <section class="panel" :class="{ 'panel-edit': mode === 'edit' }">
    <!-- 面板头（sticky：滚动长题内容时仍可切换 / 关闭） -->
    <div class="panel-head">
      <div class="panel-title">
        <TikuIcon :name="mode === 'edit' ? 'edit' : 'plus'" :size="15" />
        <span>{{ mode === 'edit' ? t('editNth', { n: questionNumber ?? '—' }) : t('addQuestion') }}</span>
        <span v-if="dirty" class="dirty-dot" :title="t('unsavedTip')"></span>
      </div>
      <div class="head-actions">
        <!-- 编辑模式：上一题 / 下一题（沿当前列表顺序，跨页自动翻页） -->
        <template v-if="mode === 'edit' && nav">
          <div class="nav-arrows">
            <button
              class="icon-btn"
              :title="t('prevTip')"
              :disabled="!nav.hasPrev || aiGenerating || submitting"
              @click="goNav(-1)"
            >
              <TikuIcon name="chevron-left" :size="15" />
            </button>
            <span
              class="nav-pos mono"
              :title="t('posTip', { p: nav.pos, t: nav.total })"
            >{{ nav.pos > 0 ? nav.pos : '—' }} / {{ nav.total }}</span>
            <button
              class="icon-btn"
              :title="t('nextTip')"
              :disabled="!nav.hasNext || aiGenerating || submitting"
              @click="goNav(1)"
            >
              <TikuIcon name="chevron-right" :size="15" />
            </button>
          </div>
          <span class="head-divider"></span>
        </template>
        <button
          class="icon-btn"
          :class="{ active: showPreview }"
          title="切换实时渲染预览（公式 / 图片 / 表格效果）"
          @click="showPreview = !showPreview"
        >
          <TikuIcon name="eye" :size="15" />
        </button>
        <button class="icon-btn" title="关闭（返回题目列表）" @click="requestClose">
          <TikuIcon name="x" :size="15" />
        </button>
      </div>
    </div>

    <div class="panel-body">
      <!-- 题型切换 -->
      <div class="field">
        <label class="field-label">题型</label>
        <div class="type-tabs">
          <button
            v-for="t in typeOptions"
            :key="t.value"
            class="type-tab"
            :class="{ active: form.questionType === t.value }"
            @click="switchType(t.value)"
          >
            {{ t.label }}
          </button>
        </div>
      </div>

      <!-- 题干（支持插图） -->
      <div class="field">
        <div class="field-head">
          <label class="field-label">题干 <span class="required">*</span></label>
          <button class="img-btn" @click="insertImage('content')">
            <TikuIcon name="file" :size="12" />
            插图
          </button>
        </div>
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="4"
          placeholder="请输入题目内容（可用 [图片:文件名] 标记插图）"
        />
        <div v-if="showPreview" class="live-preview">
          <span v-if="!form.content" class="preview-empty">（输入内容后此处实时渲染公式 / 图片效果）</span>
          <span v-else v-html="richHtml(form.content)"></span>
        </div>
      </div>

      <!-- 关联材料（资料分析组内题） -->
      <div class="field">
        <label class="field-label">关联材料（资料分析大题干，可选）</label>
        <el-select
          v-model="form.materialId"
          placeholder="不关联材料"
          clearable
          style="width: 100%"
          filterable
        >
          <el-option v-for="m in materials" :key="m.id" :label="materialLabel(m)" :value="m.id" />
        </el-select>
      </div>

      <!-- 选项（判断/主观题隐藏） -->
      <div v-if="form.questionType !== 'JUDGE' && form.questionType !== 'SUBJECTIVE'" class="field">
        <div class="field-head">
          <label class="field-label">选项 <span class="required">*</span></label>
          <span class="field-hint text-muted">支持插图：选项内容可含 [图片:文件名] 标记</span>
        </div>
        <div class="option-rows">
          <template v-for="(opt, i) in form.options" :key="i">
            <div class="option-row">
              <span class="option-key">{{ keyOf(i) }}</span>
              <el-input
                v-model="opt.text"
                :placeholder="`选项 ${keyOf(i)}`"
                maxlength="300"
                @keyup.enter="focusNext(i)"
              />
              <button
                class="img-btn"
                :disabled="!!uploadingField"
                :title="'给选项 ' + keyOf(i) + ' 插图'"
                @click="insertOptionImage(i)"
              >
                <TikuIcon name="file" :size="12" />
                插图
              </button>
              <button
                class="icon-btn"
                :disabled="form.options.length <= 2"
                title="删除选项"
                @click="removeOption(i)"
              >
                <TikuIcon name="x" :size="14" />
              </button>
            </div>
            <div v-if="showPreview && opt.text" class="opt-preview" v-html="richHtml(opt.text)"></div>
          </template>
          <button v-if="form.options.length < 10" class="btn btn-ghost btn-sm add-option" @click="addOption">
            <TikuIcon name="plus" :size="13" />
            添加选项
          </button>
        </div>
      </div>

      <!-- 正确答案（主观题无） -->
      <div v-if="form.questionType !== 'SUBJECTIVE'" class="field">
        <label class="field-label">正确答案 <span class="required">*</span></label>

        <!-- 单选 -->
        <div v-if="form.questionType === 'SINGLE'" class="answer-rows">
          <button
            v-for="opt in form.options"
            :key="opt.key"
            class="answer-row"
            :class="{ selected: isSingleSelected(opt.key) }"
            @click="selectSingle(opt.key)"
          >
            <span class="answer-dot"></span>
            <span class="option-key">{{ opt.key }}</span>
            <span class="answer-text" v-html="opt.text ? richHtml(opt.text) : `选项 ${opt.key}`"></span>
          </button>
        </div>

        <!-- 多选 -->
        <div v-else-if="form.questionType === 'MULTIPLE'" class="answer-rows">
          <button
            v-for="opt in form.options"
            :key="opt.key"
            class="answer-row"
            :class="{ selected: isMultiSelected(opt.key) }"
            @click="toggleMulti(opt.key)"
          >
            <span class="answer-check"></span>
            <span class="option-key">{{ opt.key }}</span>
            <span class="answer-text" v-html="opt.text ? richHtml(opt.text) : `选项 ${opt.key}`"></span>
          </button>
        </div>

        <!-- 判断 -->
        <div v-else class="judge-answer">
          <button
            class="judge-btn"
            :class="{ selected: form.answerKeys.includes('A') }"
            @click="selectJudge('A')"
          >
            <TikuIcon name="check" :size="16" /> 正确
          </button>
          <button
            class="judge-btn"
            :class="{ selected: form.answerKeys.includes('B') }"
            @click="selectJudge('B')"
          >
            <TikuIcon name="x" :size="16" /> 错误
          </button>
        </div>
      </div>

      <!-- 主观题参考答案 -->
      <div v-if="form.questionType === 'SUBJECTIVE'" class="field">
        <div class="field-head">
          <label class="field-label">参考答案（可选）</label>
          <button class="img-btn" @click="insertImage('referenceAnswer')">
            <TikuIcon name="file" :size="12" />
            插图
          </button>
        </div>
        <el-input
          v-model="form.referenceAnswer"
          type="textarea"
          :rows="3"
          placeholder="主观题参考答案（文字 + [图片:文件名] 标记）"
        />
        <div v-if="showPreview && form.referenceAnswer" class="live-preview" v-html="richHtml(form.referenceAnswer)"></div>
        <p class="form-tip text-muted">主观题不做自动判题，做题后由用户对照参考答案自评（对=满分 / 部分对=一半 / 错=0 分）</p>
      </div>

      <!-- 附属字段 -->
      <div class="field-grid">
        <div class="field">
          <label class="field-label">册数</label>
          <el-input-number v-model="form.volume" :min="1" :max="999" controls-position="right" style="width: 100%" />
        </div>
        <div class="field">
          <label class="field-label">题号</label>
          <el-input-number v-model="form.questionNumber" :min="1" :max="99999" controls-position="right" style="width: 100%" />
        </div>
        <div class="field">
          <label class="field-label">分值{{ form.questionType === 'SUBJECTIVE' ? '（默认 5）' : '' }}</label>
          <el-input-number v-model="form.score" :min="0.5" :max="100" :step="0.5" controls-position="right" style="width: 100%" />
        </div>
        <div class="field">
          <label class="field-label">主题</label>
          <el-input v-model="form.topic" placeholder="如：交通信号" maxlength="100" />
        </div>
        <div class="field">
          <label class="field-label">分类</label>
          <el-input v-model="form.category" placeholder="如：基础题" maxlength="100" />
        </div>
      </div>

      <!-- 答案文字 / 解析（客观题答案文字；主观题可填解析） -->
      <div v-if="form.questionType !== 'SUBJECTIVE'" class="field">
        <label class="field-label">答案文字（可选）</label>
        <el-input v-model="form.answerText" placeholder="如：答案选 B，因为……" maxlength="500" />
      </div>
      <div class="field">
        <div class="field-head">
          <label class="field-label">解析（可选）</label>
          <div class="field-head-btns">
            <button class="img-btn" :disabled="aiGenerating" @click="aiGenerateAnalysis">
              <TikuIcon name="sparkle" :size="12" />
              {{ aiGenerating ? 'AI 生成中…' : 'AI 解析' }}
            </button>
            <button class="img-btn" @click="insertImage('analysis')">
              <TikuIcon name="file" :size="12" />
              插图
            </button>
          </div>
        </div>
        <el-input v-model="form.analysis" type="textarea" :rows="3" placeholder="答案解析，做题判题后会展示" />
        <div v-if="showPreview && form.analysis" class="live-preview" v-html="richHtml(form.analysis)"></div>
      </div>
    </div>

    <!-- 面板底部操作 -->
    <div class="panel-foot">
      <span class="foot-hint text-muted">
        <template v-if="mode === 'edit'">「保存修改」后停留本页，可直接用右上角箭头切上一题 / 下一题；改完点「保存并返回列表」或右上角 ×</template>
        <template v-else>保存后清空表单，可继续录入下一题</template>
      </span>
      <div class="foot-actions">
        <button class="btn btn-ghost" @click="requestClose">取消</button>
        <template v-if="mode === 'create'">
          <button class="btn btn-secondary" :disabled="submitting" @click="submit">
            {{ submitting ? '保存中…' : '保存并继续' }}
          </button>
          <button class="btn btn-primary" :disabled="submitting" @click="submitAndFinish">
            {{ submitting ? '保存中…' : '保存并完成' }}
          </button>
        </template>
        <template v-else>
          <button class="btn btn-secondary" :disabled="submitting" @click="saveAndClose">
            {{ submitting ? '保存中…' : '保存并返回列表' }}
          </button>
          <button class="btn btn-primary" :disabled="submitting" @click="submit">
            {{ submitting ? '保存中…' : '保存修改' }}
          </button>
        </template>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      editNth: '编辑第 {n} 题', addQuestion: '添加题目', unsavedTip: '有未保存的修改', prevTip: '上一题（若有未保存修改会先询问）',
      nextTip: '下一题（若有未保存修改会先询问）', posTip: '当前位置：第 {p} 行 / 共 {t} 行（按当前列表顺序）',
      aiNeedStem: '请先填写题干', aiEmptyRet: 'AI 未返回内容，请稍后重试', aiFilled: 'AI 解析已填入（可修改后保存）',
      imgInserted: '图片已插入（[图片:…] 标记）', imgInsertedOpt: '图片已插入选项（[图片:…] 标记）',
      stemRequired: '请输入题干', need2Options: '至少需要 2 个选项', optionEmpty: '选项内容不能为空', pickCorrect: '请选择正确答案',
      multiPick2: '多选题请至少选择 2 个正确答案', scorePositive: '分值必须大于 0',
      savedContinue: '第 {n} 题已保存，继续录入下一题', savedDone: '第 {n} 题已保存', updatedStay: '题目已更新，可继续编辑或切换题目',
      aiBusyNav: 'AI 解析生成中，稍候再切换题目', aiBusyJump: 'AI 解析生成中，稍候再跳转', aiBusyClose: 'AI 解析生成中，稍候再关闭',
      modelRecoverToast: '看起来是模型已下线或不存在，建议重新获取可用模型', modelRecoverTitle: '模型可能已下线',
      modelRecoverAsk: '当前模型可能已下线或不存在。是否现在前往设置页重新获取可用模型？', modelRecoverConfirm: '获取可用模型', modelRecoverCancel: '暂不',
      modelSuggest: '建议改用 {model}（{note}）', modelSuggestPlain: '建议改用 {model}',
      closeAskMsg: '当前题目还有未保存的修改，先保存吗？选择「放弃修改」将丢失这些改动。', closePanelTitle: '关闭编辑面板',
      leaveAskMsg: '当前题目还有未保存的修改，离开前要保存吗？选择「放弃修改」将丢失这些改动。', leavePageTitle: '离开当前页面'
    },
    'en-US': {
      editNth: 'Editing question #{n}', addQuestion: 'Add question', unsavedTip: 'Unsaved changes', prevTip: 'Previous (asks first if there are unsaved changes)',
      nextTip: 'Next (asks first if there are unsaved changes)', posTip: 'Position: row {p} / {t} (current list order)',
      aiNeedStem: 'Please fill in the question stem first', aiEmptyRet: 'AI returned nothing — please try again later', aiFilled: 'AI analysis filled in (you can edit it before saving)',
      imgInserted: 'Image inserted ([图片:…] marker)', imgInsertedOpt: 'Image inserted into the option ([图片:…] marker)',
      stemRequired: 'Please enter the question stem', need2Options: 'At least 2 options are required', optionEmpty: 'Option text cannot be empty', pickCorrect: 'Please select the correct answer',
      multiPick2: 'For multiple choice, select at least 2 correct answers', scorePositive: 'The score must be greater than 0',
      savedContinue: 'Question #{n} saved — continue with the next one', savedDone: 'Question #{n} saved', updatedStay: 'Question updated — keep editing or switch questions',
      aiBusyNav: 'AI analysis is generating; wait a moment before switching questions', aiBusyJump: 'AI analysis is generating; wait a moment before jumping', aiBusyClose: 'AI analysis is generating; wait a moment before closing',
      modelRecoverToast: 'Looks like this model is retired or no longer exists — fetch the available models', modelRecoverTitle: 'Model may be retired',
      modelRecoverAsk: 'The current model may be retired or no longer exist. Open Settings and fetch the available models now?', modelRecoverConfirm: 'Fetch available models', modelRecoverCancel: 'Not now',
      modelSuggest: 'Suggested replacement: {model} ({note})', modelSuggestPlain: 'Suggested replacement: {model}',
      closeAskMsg: 'This question has unsaved changes. Save first? Choosing “Discard changes” will lose them.', closePanelTitle: 'Close edit panel',
      leaveAskMsg: 'This question has unsaved changes. Save before leaving? Choosing “Discard changes” will lose them.', leavePageTitle: 'Leave this page'
    }
  }
})
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createQuestion, updateQuestion, aiAnalysisDraft } from '../api/questions'
import { listMaterials, uploadImage } from '../api/materials'
import { imageMarker } from '../api/materials'
import { richTextToHtml } from '../utils/richText'
import { isModelError, offerModelRecovery } from '../utils/aiModelHelp'
import TikuIcon from './TikuIcon.vue'

const props = defineProps({
  bankId: { type: Number, required: true },
  mode: { type: String, default: 'create' }, // create | edit
  initial: { type: Object, default: null }, // 编辑模式：题目详情
  nextNumber: { type: Number, default: 1 }, // 创建模式建议题号
  // 编辑模式导航状态（来自列表页）：{ hasPrev, hasNext, pos, total }；null = 不显示
  nav: { type: Object, default: null },
  // 外部（右侧题号盘）请求跳题：{ seq: 递增序号, questionId }；seq 变化即触发（未保存修改会先询问）
  jumpRequest: { type: Object, default: null }
})

const emit = defineEmits(['saved', 'closed', 'navigate', 'jump-to'])

// 实时渲染预览默认开启（公式/图片/表格效果所见即所得；用户可点眼睛收起）
const showPreview = ref(true)

const typeOptions = [
  { value: 'SINGLE', label: '单选' },
  { value: 'MULTIPLE', label: '多选' },
  { value: 'JUDGE', label: '判断' },
  { value: 'SUBJECTIVE', label: '主观' }
]

const JUDGE_OPTIONS = [
  { key: 'A', text: '正确' },
  { key: 'B', text: '错误' }
]

const keyOf = (i) => String.fromCharCode(65 + i)

function defaultForm() {
  return {
    volume: 1,
    questionType: 'SINGLE',
    questionNumber: props.nextNumber,
    content: '',
    options: [{ key: 'A', text: '' }, { key: 'B', text: '' }],
    answerKeys: [],
    score: 1,
    topic: '',
    category: '',
    answerText: '',
    analysis: '',
    materialId: null,
    referenceAnswer: ''
  }
}

const form = reactive(defaultForm())
const submitting = ref(false)
const materials = ref([])

/* ---------- 编辑模式回填 ---------- */
if (props.mode === 'edit' && props.initial) {
  const d = props.initial
  form.volume = d.volume ?? 1
  form.questionType = d.questionType || 'SINGLE'
  form.questionNumber = d.questionNumber ?? props.nextNumber
  form.content = d.content || ''
  form.options = (d.options && d.options.length ? d.options : [{ key: 'A', text: '' }, { key: 'B', text: '' }])
    .map((o) => ({ key: o.key, text: o.text }))
  form.answerKeys = [...(d.answerKeys || [])]
  form.score = d.score ?? 1
  form.topic = d.topic || ''
  form.category = d.category || ''
  form.answerText = d.answerText || ''
  form.analysis = d.analysis || ''
  form.materialId = d.materialId ?? null
  form.referenceAnswer = d.referenceAnswer || ''
}

/* ---------- 未保存修改检测（编辑模式：保存 / 切换 / 关闭前询问） ---------- */
const isEdit = computed(() => props.mode === 'edit')
// 头部标题用的题号（此前模板引用了未定义的 questionNumber，恒显示"—"）
const questionNumber = computed(() => form.questionNumber)
const baseline = ref('')
const dirty = ref(false)
// watch 的 getter 深度访问整个表单（含选项 / 答案数组），任何字段变化都会触发重算。
// 注意：注册于回填之后，回填期间的修改不会被当成"未保存"。
watch(
  () => JSON.stringify(form),
  (s) => {
    if (isEdit.value) dirty.value = s !== baseline.value
  }
)
// 打开面板（编辑回填完成）后的初始状态即"已保存"基准
baseline.value = JSON.stringify(form)

/* ---------- 材料列表（关联下拉） ---------- */
async function loadMaterials() {
  try {
    materials.value = (await listMaterials(props.bankId)) || []
  } catch (e) {
    materials.value = []
  }
}

const materialLabel = (m) => {
  const text = (m.content || '').replace(/\[图片:[^\]]+\]/g, '［图］').replace(/\s+/g, ' ').trim()
  return `材料 ${m.id}${text ? '：' + text.slice(0, 30) : ''}`
}

/* ---------- AI 解析（基于当前表单内容生成草稿，填入解析字段） ---------- */
const aiGenerating = ref(false)
const router = useRouter()

/**
 * 模型失效自愈：AI 解析失败且命中「模型不存在/已下线」时才提示并询问是否去设置页
 * 获取可用模型（其它失败保持原有静默，仅由拦截器提示）。
 */
async function handleModelError(msg) {
  if (!isModelError(msg)) return false
  ElMessage.warning(t('modelRecoverToast'))
  return offerModelRecovery(msg, {
    router,
    texts: {
      ask: t('modelRecoverAsk'),
      title: t('modelRecoverTitle'),
      confirm: t('modelRecoverConfirm'),
      cancel: t('modelRecoverCancel')
    },
    describe: (hit) =>
      hit.note
        ? t('modelSuggest', { model: hit.replacement, note: hit.note })
        : t('modelSuggestPlain', { model: hit.replacement })
  })
}

async function aiGenerateAnalysis() {
  if (aiGenerating.value) return
  if (!form.content || !form.content.trim()) {
    ElMessage.warning(t('aiNeedStem'))
    return
  }
  //材料内容（关联材料时取文本供参考）
  let materialContent = null
  if (form.materialId != null) {
    const m = materials.value.find((x) => x.id === form.materialId)
    materialContent = m ? m.content : null
  }
  aiGenerating.value = true
  try {
    const typeLabels = { SINGLE: '单选题', MULTIPLE: '多选题', JUDGE: '判断题', SUBJECTIVE: '主观题' }
    const resp = await aiAnalysisDraft({
      bankId: props.bankId,
      questionTypeLabel: typeLabels[form.questionType] || form.questionType,
      content: form.content,
      options: form.options
        .filter((o) => o.text && o.text.trim())
        .map((o) => ({ key: o.key, text: o.text })),
      answerKeys: form.answerKeys,
      answerText: form.answerText || null,
      referenceAnswer: form.referenceAnswer || null,
      materialContent
    })
    //拦截器已解包 body.data → resp 即解析文本字符串
    const text = resp ?? ''
    if (!text) {
      ElMessage.warning(t('aiEmptyRet'))
      return
    }
    form.analysis = form.analysis ? `${form.analysis}\n\n${text}` : text
    ElMessage.success(t('aiFilled'))
  } catch (e) {
    /* 拦截器已提示 */
    // 模型失效 → 追一条带操作指引的提示（普通错误不打扰）
    handleModelError(e?.response?.data?.message || e?.message || '')
  } finally {
    aiGenerating.value = false
  }
}

/* ---------- 插图：上传后把 [图片:name] 追加到字段 ---------- */
const uploadingField = ref('')
async function insertImage(field) {
  if (uploadingField.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async () => {
    const file = input.files && input.files[0]
    if (!file) return
    uploadingField.value = field
    try {
      const res = await uploadImage(props.bankId, file)
      const marker = imageMarker(res.name)
      form[field] = form[field] ? `${form[field]}\n${marker}` : marker
      ElMessage.success(t('imgInserted'))
    } catch (e) {
      /* 拦截器已提示 */
    } finally {
      uploadingField.value = ''
    }
  }
  input.click()
}

/* 选项插图：上传后把 [图片:name] 追加到该选项文本 */
async function insertOptionImage(i) {
  if (uploadingField.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async () => {
    const file = input.files && input.files[0]
    if (!file) return
    uploadingField.value = `option-${i}`
    try {
      const res = await uploadImage(props.bankId, file)
      const marker = imageMarker(res.name)
      const opt = form.options[i]
      if (!opt) return
      opt.text = opt.text ? `${opt.text}\n${marker}` : marker
      ElMessage.success(t('imgInsertedOpt'))
    } catch (e) {
      /* 拦截器已提示 */
    } finally {
      uploadingField.value = ''
    }
  }
  input.click()
}

/* 选项文本富文本渲染（[图片:…] → <img>） */
const richHtml = (text) => richTextToHtml(text, props.bankId)

/* ---------- 题型切换 ---------- */
function switchType(type) {
  if (form.questionType === type) return
  form.questionType = type
  form.answerKeys = []
  if (type === 'SUBJECTIVE') {
    // 主观题默认 5 分
    if (form.score === 1) form.score = 5
  } else {
    if (form.score === 5 && form.answerKeys.length === 0 && !form.referenceAnswer) form.score = 1
  }
}

/* ---------- 选项操作 ---------- */
function addOption() {
  if (form.options.length >= 10) return
  form.options.push({ key: keyOf(form.options.length), text: '' })
}
function removeOption(i) {
  if (form.options.length <= 2) return
  form.options.splice(i, 1)
  // 重排 key：A/B/C/D 按当前顺序
  form.options.forEach((o, idx) => { o.key = keyOf(idx) })
  // 清理失效的答案 key
  const valid = new Set(form.options.map((o) => o.key))
  form.answerKeys = form.answerKeys.filter((k) => valid.has(k))
}
function focusNext(i) {
  const next = document.querySelectorAll('.option-row .el-input__inner')[i + 1]
  next?.focus()
}

/* ---------- 答案选择 ---------- */
const isSingleSelected = (key) => form.answerKeys[0] === key
function selectSingle(key) {
  form.answerKeys = [key]
}
const isMultiSelected = (key) => form.answerKeys.includes(key)
function toggleMulti(key) {
  form.answerKeys = form.answerKeys.includes(key)
    ? form.answerKeys.filter((k) => k !== key)
    : [...form.answerKeys, key]
}
function selectJudge(key) {
  form.answerKeys = [key]
}

/* ---------- 提交 ---------- */
function buildPayload() {
  const isSubjective = form.questionType === 'SUBJECTIVE'
  const payload = {
    volume: form.volume,
    questionType: form.questionType,
    questionNumber: form.questionNumber,
    content: form.content,
    topic: form.topic || null,
    category: form.category || null,
    score: form.score,
    analysis: form.analysis || null
  }
  if (isSubjective) {
    // 主观题：无选项无答案，带参考答案
    payload.options = null
    payload.answerKeys = null
    payload.answerText = null
    payload.referenceAnswer = form.referenceAnswer || null
    // 编辑时 materialId null = 不修改（保持原关联）
    if (props.mode === 'create' || form.materialId != null) payload.materialId = form.materialId
  } else {
    payload.options = form.options.map((o) => ({ key: o.key, text: o.text }))
    payload.answerKeys = form.answerKeys
    payload.answerText = form.answerText || null
    payload.referenceAnswer = null
    if (props.mode === 'create' || form.materialId != null) payload.materialId = form.materialId
  }
  if (props.mode === 'create') payload.bankId = props.bankId
  return payload
}

function validate() {
  if (!form.content.trim()) {
    ElMessage.warning(t('stemRequired'))
    return false
  }
  if (form.questionType !== 'JUDGE' && form.questionType !== 'SUBJECTIVE') {
    if (form.options.length < 2) {
      ElMessage.warning(t('need2Options'))
      return false
    }
    if (form.options.some((o) => !o.text.trim())) {
      ElMessage.warning(t('optionEmpty'))
      return false
    }
  }
  if (form.questionType !== 'SUBJECTIVE' && !form.answerKeys.length) {
    ElMessage.warning(t('pickCorrect'))
    return false
  }
  if (form.questionType === 'MULTIPLE' && form.answerKeys.length < 2) {
    ElMessage.warning(t('multiPick2'))
    return false
  }
  if (!form.score || form.score < 1) {
    ElMessage.warning(t('scorePositive'))
    return false
  }
  return true
}

async function doSave() {
  const payload = buildPayload()
  if (props.mode === 'create') {
    await createQuestion(payload)
  } else {
    await updateQuestion(props.initial.id, payload)
  }
}

async function submit() {
  if (props.mode === 'create') {
    if (!validate()) return
    submitting.value = true
    try {
      const savedNumber = form.questionNumber
      await doSave()
      ElMessage.success(t('savedContinue', { n: savedNumber }))
      // 清空表单继续录下一题（保留题型，方便连续录同题型）；题号顺延
      const keepType = form.questionType
      Object.assign(form, defaultForm())
      form.questionType = keepType
      form.questionNumber = (savedNumber ?? 0) + 1
      emit('saved', { questionNumber: savedNumber })
    } catch (e) {
      /* 错误提示由拦截器统一处理 */
    } finally {
      submitting.value = false
    }
  } else {
    await saveCurrent()
  }
}

/* 编辑模式保存：成功后停留本页（支持连续校对下一题），并刷新"已保存"基准。
   silent = true 用于"切换题目时自动保存"（不弹成功提示，避免连续校对刷屏） */
async function saveCurrent(silent = false) {
  if (submitting.value) return false
  if (!validate()) return false
  submitting.value = true
  try {
    await doSave()
    baseline.value = JSON.stringify(form)
    if (!silent) ElMessage.success(t('updatedStay'))
    emit('saved', { questionNumber: form.questionNumber })
    return true
  } catch (e) {
    /* 错误提示由拦截器统一处理 */
    return false
  } finally {
    submitting.value = false
  }
}

/** 保存并返回列表 */
async function saveAndClose() {
  if (await saveCurrent()) emit('closed')
}

/* ========== 切换（上一题/下一题/题号盘跳题）：有未保存修改时静默自动保存，失败才停下提示。
   不再逐次弹"是否保存"——校对连续切换保持流畅；保存失败（校验不过/网络错）会停在当前题并提示原因 */
async function switchAway(act) {
  if (!isEdit.value || !dirty.value) {
    act()
    return
  }
  if (await saveCurrent(true)) act()
}

/* ========== 关闭面板（× / 取消）：有未保存修改 → 弹"保存并关闭 / 放弃修改"（× / Esc 留在当前题） */
async function closeWithConfirm(act) {
  if (!isEdit.value || !dirty.value) {
    act()
    return
  }
  try {
    await ElMessageBox.confirm(
      t('closeAskMsg'),
      t('closePanelTitle'),
      {
        confirmButtonText: '保存并关闭',
        cancelButtonText: '放弃修改',
        distinguishCancelAndClose: true,
        type: 'warning'
      }
    )
    // 保存成功才关闭；保存失败（如校验不过）会停在当前题
    if (await saveCurrent(true)) act()
  } catch (e) {
    // 'cancel' = 放弃修改直接关闭；'close'（弹窗 × / Esc）= 留在当前题继续编辑
    if (e === 'cancel') act()
  }
}

function goNav(dir) {
  if (aiGenerating.value) {
    ElMessage.warning(t('aiBusyNav'))
    return
  }
  switchAway(() => emit('navigate', dir))
}

/* 右侧题号盘点击跳题：seq 变化触发（父组件在点击时自增 seq） */
watch(
  () => props.jumpRequest?.seq,
  (seq) => {
    if (!seq || !isEdit.value) return
    const qid = props.jumpRequest?.questionId
    if (qid == null) return
    if (aiGenerating.value) {
      ElMessage.warning(t('aiBusyJump'))
      return
    }
    switchAway(() => emit('jump-to', qid))
  }
)

function requestClose() {
  if (aiGenerating.value) {
    ElMessage.warning(t('aiBusyClose'))
    return
  }
  closeWithConfirm(() => emit('closed'))
}

/* ========== 页面级离开保护：切到别的页面 / 刷新 / 关标签时统一询问（编辑中且有未保存修改） ========== */
//路由离开（点侧栏 / 返回题库等）：弹"保存并离开 / 放弃修改"
onBeforeRouteLeave(async () => {
  if (!isEdit.value || !dirty.value) return true
  try {
    await ElMessageBox.confirm(
      t('leaveAskMsg'),
      t('leavePageTitle'),
      {
        confirmButtonText: '保存并离开',
        cancelButtonText: '放弃修改',
        distinguishCancelAndClose: true,
        type: 'warning'
      }
    )
    // 保存成功才放行；保存失败（校验不过等）留在本页
    return await saveCurrent(true)
  } catch (e) {
    // 'cancel' = 放弃修改直接离开；'close'（× / Esc）= 留在本页
    if (e === 'cancel') return true
    return false
  }
})

//浏览器刷新 / 关闭标签页：交给浏览器原生"离开确认"（仅编辑中且有未保存时拦截）
function handleBeforeUnload(e) {
  if (isEdit.value && dirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

/** 创建模式：保存并关闭（返回题目列表） */
async function submitAndFinish() {
  if (!validate()) return
  submitting.value = true
  try {
    await doSave()
    ElMessage.success(t('savedDone', { n: form.questionNumber }))
    emit('saved', { questionNumber: form.questionNumber })
    emit('closed')
  } catch (e) {
    /* 错误提示由拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadMaterials()
  window.addEventListener('beforeunload', handleBeforeUnload)
})
onUnmounted(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<style scoped>
.panel {
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-card);
  margin-bottom: 24px;
  /* 注意：不能设 overflow: hidden，否则头部 position: sticky 失效 */
}

.panel-head {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-card);
  border-radius: var(--radius-card) var(--radius-card) 0 0;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.nav-arrows {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.nav-pos {
  font-size: 12px;
  color: var(--text-muted);
  min-width: 62px;
  text-align: center;
  user-select: none;
  font-variant-numeric: tabular-nums;
}
.head-divider {
  width: 1px;
  height: 16px;
  background: var(--border);
  margin: 0 8px;
}
.dirty-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  flex-shrink: 0;
  margin-left: 2px;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--accent-text);
}

.panel-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.field-head-btns {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.field-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.required {
  color: var(--danger);
}
.form-tip {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

/* 插图按钮 */
.img-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elev);
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
  margin-bottom: 8px;
  transition: all var(--ease);
}
.img-btn:hover {
  border-color: var(--accent);
  color: var(--accent-text);
}
.img-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 题型切换 */
.type-tabs {
  display: inline-flex;
  gap: 4px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  padding: 4px;
}
.type-tab {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 13px;
  padding: 6px 18px;
  border-radius: 7px;
  cursor: pointer;
  transition: all var(--ease);
}
.type-tab:hover {
  color: var(--text-primary);
}
.type-tab.active {
  background: var(--accent);
  color: #fff;
  font-weight: 500;
}

/* 选项编辑行 */
.option-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.option-row .img-btn {
  margin-bottom: 0;
  flex-shrink: 0;
}
.option-key {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
}
.option-row .el-input {
  flex: 1;
}
.add-option {
  align-self: flex-start;
}

/* 答案选择行 */
.answer-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.answer-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  font-size: 14px;
  cursor: pointer;
  text-align: left;
  transition: border-color var(--ease), background var(--ease);
}
.answer-row:hover {
  border-color: var(--border-strong);
}
.answer-row.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.answer-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--text-muted);
  flex-shrink: 0;
  transition: all var(--ease);
}
.answer-row.selected .answer-dot {
  border-color: var(--accent);
  background: var(--accent);
  box-shadow: inset 0 0 0 3px var(--bg-elev);
}
.answer-check {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 2px solid var(--text-muted);
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all var(--ease);
}
.answer-row.selected .answer-check {
  border-color: var(--accent);
  background: var(--accent);
}
.answer-row.selected .answer-check::after {
  content: '';
  width: 6px;
  height: 3px;
  border-left: 2px solid #fff;
  border-bottom: 2px solid #fff;
  transform: rotate(-45deg) translateY(-1px);
}
.answer-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.answer-text :deep(.rich-img) {
  max-width: 140px;
  max-height: 90px;
  width: auto;
  height: auto;
  border-radius: 6px;
  vertical-align: middle;
  display: inline-block;
}

/* 判断题对错按钮 */
.judge-answer {
  display: flex;
  gap: 12px;
}
.judge-btn {
  flex: 1;
  max-width: 180px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 0;
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
.judge-btn:hover {
  border-color: var(--border-strong);
}
.judge-btn.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-text);
}

/* 附属字段 */
.field-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

/* 底部 */
.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  background: var(--bg-elev);
  border-radius: 0 0 var(--radius-card) var(--radius-card);
}
.foot-hint {
  flex: 1;
  font-size: 12px;
  line-height: 1.6;
}
.foot-actions {
  display: flex;
  gap: 10px;
}

/* 图标按钮 */
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  border-radius: 7px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--ease);
}
.icon-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.icon-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.icon-btn.active {
  color: var(--accent, #409eff);
  background: color-mix(in srgb, var(--accent, #409eff) 12%, transparent);
}
/* 实时渲染预览（编辑面板）：公式 KaTeX + [图片:…] + 表格 */
.live-preview,
.opt-preview {
  margin-top: 6px;
  padding: 7px 10px;
  background: var(--bg-elev);
  border: 1px dashed var(--border-color, #ddd);
  border-radius: 7px;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
}
.live-preview img,
.opt-preview img {
  max-width: 260px;
  max-height: 200px;
  object-fit: contain;
  border-radius: 6px;
  margin: 2px 0;
  background: #fff;
}
.opt-preview {
  margin: 2px 0 8px 26px;
}
.preview-empty {
  color: var(--text-muted);
  font-size: 12px;
  font-style: italic;
}

@media (max-width: 900px) {
  .field-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

/* 窄视口：头部隐藏"x / y"位置文本只留箭头；底部提示换行；整体收紧凑 */
@media (max-width: 1100px) {
  .nav-pos {
    display: none;
  }
  .panel-head {
    padding: 12px 14px;
    gap: 8px;
  }
  .panel-body {
    padding: 16px 14px;
  }
  .panel-foot {
    flex-wrap: wrap;
  }
  .foot-hint {
    min-width: 100%;
    flex-basis: 100%;
  }
}
@media (max-width: 700px) {
  .field-grid {
    grid-template-columns: 1fr;
  }
  .foot-actions {
    flex-wrap: wrap;
  }
  .option-row {
    flex-wrap: wrap;
  }
}
</style>
