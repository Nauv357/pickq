<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(92vw, 540px)"
    :close-on-click-modal="false"
    align-center
  >
    <!-- 未配置 AI 模型 -->
    <div v-if="!configOk && !configLoading" class="no-config">
      <TikuIcon name="info" :size="34" />
      <p class="text-secondary">{{ t('noModelTip') }}</p>
      <p class="text-muted no-config-local">{{ t('noModelLocalTip') }}</p>
      <button class="btn btn-primary btn-sm" @click="goSettings">{{ t('goSettings') }}</button>
    </div>

    <!-- 表单：选文件（多选）+ 目标 + 补充开关 -->
    <div v-else-if="!running" class="ai-form">
      <div class="field">
        <label class="field-label">{{ t('pickDocs') }}</label>
        <div class="file-pick" :class="{ picked: files.length > 0 }" @click="pick">
          <TikuIcon name="file" :size="22" />
          <div class="file-info">
            <span v-if="files.length" class="file-name">{{ t('filesChosen', { n: files.length }) }}</span>
            <span v-else class="text-muted">{{ t('pickHint') }}</span>
            <span class="file-size text-muted">{{ t('multiFileTip') }}</span>
          </div>
          <span class="file-clear" :title="t('repick')" @click.stop="pick">
            <TikuIcon name="refresh" :size="14" />
          </span>
        </div>
        <div v-if="files.length" class="file-list">
          <div v-for="(f, i) in files" :key="i" class="file-row">
            <span class="file-name">{{ f.name }}</span>
            <span class="file-size text-muted">{{ formatSize(f.size) }}</span>
            <button class="file-clear" :title="t('remove')" @click="removeFile(i)">
              <TikuIcon name="x" :size="13" />
            </button>
          </div>
        </div>
      </div>

      <div class="field">
        <label class="field-label">{{ t('target') }}</label>
        <div v-if="lockedBankId" class="target-locked text-secondary">
          {{ t('appendToBank', { name: lockedBankName, id: lockedBankId }) }}
        </div>
        <div v-else class="target-options">
          <button
            class="target-option"
            :class="{ active: targetMode === 'new' }"
            @click="targetMode = 'new'"
          >
            <span class="mode-title">{{ t('newBank') }}</span>
            <span class="mode-desc">{{ t('newBankDesc') }}</span>
          </button>
          <button
            class="target-option"
            :class="{ active: targetMode === 'existing' }"
            @click="targetMode = 'existing'"
          >
            <span class="mode-title">{{ t('appendBank') }}</span>
            <span class="mode-desc">{{ t('appendBankDesc') }}</span>
          </button>
        </div>
        <el-select
          v-if="targetMode === 'existing'"
          v-model="targetBankId"
          :placeholder="t('pickBank')"
          style="width: 100%; margin-top: 10px"
          filterable
        >
          <el-option v-for="b in banks" :key="b.id" :label="b.name" :value="b.id" />
        </el-select>
      </div>

      <!-- AI 补充开关 -->
      <div class="field">
        <button class="supplement-option" :class="{ checked: aiSupplement }" @click="aiSupplement = !aiSupplement">
          <span class="supplement-check">
            <TikuIcon v-if="aiSupplement" name="check" :size="12" />
          </span>
          <span class="supplement-text">
            <span class="mode-title">{{ t('aiSupplement') }}</span>
            <span class="mode-desc">
              {{ t('aiSupplementTip') }}
            </span>
          </span>
        </button>
      </div>

      <!-- 处理模式预设（单选：引擎与思考打包成意图；避免用户自行组合踩坑） -->
      <div class="field">
        <label class="field-label">{{ t('processMode') }}</label>
        <div class="mode-options">
          <button
            v-for="m in MODE_PRESETS"
            :key="m.value"
            class="mode-option"
            :class="{ active: modePreset === m.value }"
            @click="modePreset = m.value"
          >
            <span class="mode-title">{{ m.title }}</span>
            <span class="mode-desc">{{ m.desc }}</span>
          </button>
        </div>

        <!-- 纯图 MinerU 增强（选了 PDF/图片文件即出现：拍照/扫描试卷的图形题需要它裁题图；未配 Key 显示禁用态） -->
        <button
          v-if="showMineruOption"
          class="supplement-option"
          :class="{ checked: mineruEnhance, disabled: !hasMineruKey }"
          @click="toggleMineruEnhance"
        >
          <span class="supplement-check">
            <TikuIcon v-if="mineruEnhance" name="check" :size="12" />
          </span>
          <span class="supplement-text">
            <span class="mode-title">{{ t('mineruEnhance') }}</span>
            <span class="mode-desc">
              {{ hasMineruKey
                ? t('mineruOnTip')
                : t('mineruOffTip') }}
            </span>
          </span>
        </button>
        <p v-if="imageHint" class="form-tip text-muted img-hint">{{ imageHint }}</p>
      </div>

      <div class="field">
        <label class="field-label">{{ t('formats') }}</label>
        <p class="form-tip text-muted">
          {{ t('formatsTip1') }}
          {{ t('formatsTip2') }}
        </p>
      </div>
    </div>

    <!-- 进度（SSE 实时 / 断开自动回退轮询） -->
    <div v-else class="ai-progress">
      <div class="stage-text">
        <span class="stage-spin" v-if="!failed"><TikuIcon name="refresh" :size="16" /></span>
        <TikuIcon v-else name="x" :size="16" class="stage-fail" />
        <span>{{ stageText }}</span>
      </div>
      <div class="progress-track">
        <div class="progress-fill" :style="{ width: progress + '%' }"></div>
      </div>
      <p class="progress-num text-muted mono">{{ progress }}%</p>
      <p class="form-tip text-muted">{{ t('backgroundTip') }}</p>
      <p v-if="errorMsg" class="error-text">{{ errorMsg }}</p>
    </div>

    <template #footer>
      <template v-if="!running">
        <button class="btn btn-ghost" @click="visible = false">{{ t('cancel') }}</button>
        <button class="btn btn-primary" :disabled="!canSubmit || submitting" @click="submit">
          {{ submitting ? t('submitting') : t('startParse') }}
        </button>
      </template>
      <template v-else>
        <button class="btn btn-secondary" @click="close">
          {{ failed ? t('close') : t('closeBg') }}
        </button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      titleImport: 'AI 导入', titleAppend: 'AI 追加题目',
      noModelTip: '还没有配置 AI 模型，AI 导入需要模型 Key（BYOK）', goSettings: '去设置页配置',
      noModelLocalTip: '本机/局域网服务（Ollama 等）无需 API Key：在设置页填好 Base URL 与模型名即可',
      pickDocs: '选择文档（可多选）', filesChosen: '{n} 个文件已选择', pickHint: '点击选择文件（txt / md / docx / pdf / 图片）',
      multiFileTip: '题目和答案分文件时一起选上，后端自动拼接', repick: '重新选择', remove: '移除',
      target: '导入目标', appendToBank: '追加到题库「{name}」（{id}）', newBank: '新建题库', newBankDesc: '以第一个文件名作为题库名称',
      appendBank: '追加到现有题库', appendBankDesc: 'AI 生成的题目并入所选题库', pickBank: '选择题库',
      aiSupplement: 'AI 补充缺失的答案和解析', aiSupplementTip: '勾选 = 原文答案优先、缺失时 AI 补充（预览页会标记"AI 补充"）；不勾选 = 只用原文信息，缺失答案留空待补',
      processMode: '处理模式',
      mineruEnhance: 'MinerU 增强（拍照/扫描/纯图试卷）',
      mineruOnTip: '把照片/扫描件版面里的图形题裁成独立题图（默认视觉直读只能看整张图）。文字型 PDF/Word 无需开启',
      mineruOffTip: '未配置 MinerU Key：点击后到「设置-AI 配置」填写（拍照试卷的图形题需要它裁题图）',
      formats: '支持格式与处理方式',
      formatsTip1: 'txt / md 直接读取；Word、文字版 PDF（可选中文字的）由 AI 看图整理（公式/插图自动归位，最推荐）；',
      formatsTip2: '扫描件 PDF 与图片由多模态模型直读，含图形图表的扫描件可勾选上方「MinerU 增强」；doc 老格式请先另存为 .docx',
      backgroundTip: '可关闭本窗口继续做其他事，任务在后台进行，完成后侧边栏与系统通知都会提醒你',
      cancel: '取消', submitting: '提交中…', startParse: '开始解析', close: '关闭', closeBg: '关闭（后台继续）',
      mineruKeyNeeded: '未配置 MinerU 解析 Key，请先到「设置-AI 配置」填写',
      parseDone: '解析完成，共 {n} 题，进入预览',
      // 模型失效自愈（仅在命中「模型不存在/已下线」类错误时出现）
      modelRecoverToast: '看起来是模型已下线或不存在，建议重新获取可用模型',
      modelRecoverTitle: '模型可能已下线',
      modelRecoverAsk: '当前模型可能已下线或不存在。是否现在前往设置页重新获取可用模型？',
      modelRecoverConfirm: '获取可用模型',
      modelRecoverCancel: '暂不',
      modelSuggest: '建议改用 {model}（{note}）',
      modelSuggestPlain: '建议改用 {model}'
    },
    'en-US': {
      titleImport: 'AI Import', titleAppend: 'AI Append',
      noModelTip: 'No AI model configured — AI Import needs your own model key', goSettings: 'Set up in Settings',
      noModelLocalTip: 'Local/LAN services (Ollama etc.) need no API key: just set the Base URL and model in Settings',
      pickDocs: 'Choose documents (multiple allowed)', filesChosen: '{n} files selected', pickHint: 'Click to choose files (txt / md / docx / pdf / images)',
      multiFileTip: 'If questions and answers are in separate files, select them together — they will be combined automatically',
      repick: 'Re-choose', remove: 'Remove',
      target: 'Import target', appendToBank: 'Append to bank “{name}” ({id})', newBank: 'Create a new bank', newBankDesc: 'Named after the first file',
      appendBank: 'Append to an existing bank', appendBankDesc: 'AI-generated questions are merged into the selected bank', pickBank: 'Select a bank',
      aiSupplement: 'AI fills missing answers & explanations', aiSupplementTip: 'Checked = use original answers first, AI fills gaps (marked "AI" in preview); unchecked = only original content, blanks stay empty',
      processMode: 'Processing mode',
      mineruEnhance: 'MinerU enhance (photos / scans / pure-image papers)',
      mineruOnTip: 'Crops figure questions from photo/scan layouts into separate images (default vision reads the whole page only). Not needed for text PDFs/Word',
      mineruOffTip: 'MinerU key not configured: click to add it in Settings → AI Setup (needed to crop figures from photographed papers)',
      formats: 'Supported formats & how they are handled',
      formatsTip1: 'txt / md are read directly; Word and text-layer PDFs are organized by AI vision (formulas/images placed back automatically — recommended);',
      formatsTip2: 'Scanned PDFs and images are read by the vision model; for scans with figures/charts enable "MinerU enhance" above; old .doc format: save as .docx first',
      backgroundTip: 'You can close this window — the task keeps running in the background; the sidebar and system notification will remind you when done',
      cancel: 'Cancel', submitting: 'Submitting…', startParse: 'Start parsing', close: 'Close', closeBg: 'Close (keep running)',
      mineruKeyNeeded: 'MinerU parse key not configured — add it first in Settings → AI Setup',
      parseDone: 'Parsing done — {n} questions, opening the preview',
      // Model self-heal (only when the error looks like a retired / missing model)
      modelRecoverToast: 'Looks like this model is retired or no longer exists — fetch the available models',
      modelRecoverTitle: 'Model may be retired',
      modelRecoverAsk: 'The current model may be retired or no longer exist. Open Settings and fetch the available models now?',
      modelRecoverConfirm: 'Fetch available models',
      modelRecoverCancel: 'Not now',
      modelSuggest: 'Suggested replacement: {model} ({note})',
      modelSuggestPlain: 'Suggested replacement: {model}'
    }
  }
})
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createAiImportJob, getAiImportJob, getAiSettings, subscribeAiJobStream } from '../api/aiImport'
import { getBanks } from '../api/banks'
import { isModelError, offerModelRecovery } from '../utils/aiModelHelp'
import { isLocalOrPrivate } from '../utils/netAddress'
import TikuIcon from './TikuIcon.vue'

const props = defineProps({
  // 非空 = 锁定追加到该题库（详情页"AI 追加"）；空 = 可选手动目标（列表页"AI 导入"）
  bankId: { type: [Number, String], default: null },
  bankName: { type: String, default: '' }
})
const emit = defineEmits(['done'])

const router = useRouter()
const visible = ref(false)
const title = computed(() => (props.bankId ? t('titleAppend') : t('titleImport')))

const configLoading = ref(true)
const configOk = ref(false)
const hasMineruKey = ref(false) // 是否已配置 MinerU 云端解析（影响图片识别提示文案）

/* 处理模式预设说明（随所选文件动态提示） */
const MODE_PRESETS = [
  {
    value: 'smart',
    title: '智能推荐（默认）',
    desc: '系统按文件类型自动选最佳路径 + 开启思考：文字版 PDF/Word 直传视觉看图整理；扫描件/图片交视觉模型直读。大多数情况选它'
  },
  {
    value: 'fast',
    title: '最快（纯文字文档）',
    desc: '纯文字文档（无图、公式少）用文本直读并关闭思考，速度最快；含图文档不推荐'
  },
  {
    value: 'precise',
    title: '精细（复杂卷）',
    desc: '图多/公式多的打字版卷子满配：直传视觉 + 思考，最稳最全（大卷约几分钟）'
  }
]
const modePreset = ref('smart')
/* 扫描件/纯图 MinerU 增强：选了 PDF 或图片文件即显示（用户拍的照片/纯图卷专用——
   MinerU 能把照片版面里的图形题裁成独立图片；文字型 PDF/Word 无需）。
   未配 Key 时仍显示（禁用态），提示去设置页配置 */
const mineruEnhance = ref(false)
const hasImageLikeFile = computed(() =>
  files.value.some((f) => /\.(png|jpe?g|webp|bmp)$/i.test(f.name) || /\.pdf$/i.test(f.name))
)
const showMineruOption = computed(() => hasImageLikeFile.value)
const imageHint = computed(() => {
  const hasImageFile = files.value.some((f) => /\.(png|jpe?g|webp|bmp)$/i.test(f.name))
  if (!hasMineruKey.value) {
    return '提示：拍照/扫描的试卷建议使用「MinerU 增强」提取题图——可在「设置-AI 配置」填写 MinerU Key 后开启'
  }
  if (hasImageFile && !mineruEnhance.value) {
    return '提示：图片/拍照试卷中的图形题需要裁剪题图，建议开启「MinerU 增强」（默认视觉直读只能看到整张图）'
  }
  return ''
})

/* ---------- 表单 ---------- */
const files = ref([])
const aiSupplement = ref(true)
const targetMode = ref('new')
const targetBankId = ref(null)
const banks = ref([])
const lockedBankId = computed(() => (props.bankId ? Number(props.bankId) : null))
const lockedBankName = computed(() => props.bankName || `#${props.bankId}`)
const canSubmit = computed(() => files.value.length > 0 && (lockedBankId.value || targetMode.value === 'new' || targetBankId.value))
const submitting = ref(false)

/* ---------- 进度（SSE + 轮询回退） ---------- */
const running = ref(false)
const failed = ref(false)
const progress = ref(0)
const stageText = ref('')
const errorMsg = ref('')
const fileCount = ref(0)
const currentFileIndex = ref(null)
let eventSource = null
let timer = null
// 模型失效自愈只提示一次（避免失败任务重复弹窗打断）
let modelRecoveryAsked = false

const STAGE_TEXT = {
  PARSING: '解析文档中…',
  AI_GENERATING: 'AI 整理题目中…',
  VALIDATING: '校验题目中…',
  DONE: '处理完成'
}

/* 阶段文案：多文件解析时显示"解析 currentFileIndex/fileCount" */
function buildStageText(job) {
  const base = STAGE_TEXT[job.stage] || job.stage || ''
  if (job.stage === 'PARSING' && job.fileCount > 1 && job.currentFileIndex != null) {
    return `${base}（${job.currentFileIndex}/${job.fileCount}）`
  }
  return base
}

async function checkConfig() {
  configLoading.value = true
  try {
    const s = await getAiSettings()
    // 可用性判断：有 Key 即可用；本机/局域网服务（Ollama 等）后端不校验 Key，配好 Base URL 即为可用
    // （与后端 isConfigured() 口径一致，见 frontend/src/utils/netAddress.js）
    configOk.value = !!s.hasKey || isLocalOrPrivate(s.baseUrl)
    hasMineruKey.value = !!s.hasMineruKey
  } catch (e) {
    configOk.value = false
    hasMineruKey.value = false
  } finally {
    configLoading.value = false
  }
}

function open() {
  visible.value = true
  files.value = []
  aiSupplement.value = true
  modePreset.value = 'smart'
  mineruEnhance.value = false
  targetMode.value = 'new'
  targetBankId.value = null
  running.value = false
  failed.value = false
  progress.value = 0
  stageText.value = ''
  errorMsg.value = ''
  modelRecoveryAsked = false
  checkConfig()
  if (!lockedBankId.value && !banks.value.length) loadBanks()
}

/**
 * 模型失效自愈：失败信息命中「模型不存在/已下线」类错误时，补一条带操作指引的提示，
 * 并询问是否现在去设置页获取可用模型（普通失败不打扰）。同一任务只问一次。
 */
async function handleModelError(msg) {
  if (modelRecoveryAsked || !isModelError(msg)) return false
  modelRecoveryAsked = true
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

async function loadBanks() {
  try {
    const data = await getBanks({ page: 1, size: 200 })
    banks.value = data.records || []
  } catch (e) {
    banks.value = []
  }
}

function pick() {
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.accept = '.txt,.md,.docx,.pdf,.jpg,.jpeg,.png,.webp'
  input.onchange = () => {
    const picked = [...(input.files || [])]
    if (picked.length) files.value = picked
  }
  input.click()
}

function removeFile(index) {
  files.value.splice(index, 1)
}

/* MinerU 增强勾选：未配置 Key 时点击提示去设置页 */
function toggleMineruEnhance() {
  if (!hasMineruKey.value) {
    ElMessage.warning(t('mineruKeyNeeded'))
    return
  }
  mineruEnhance.value = !mineruEnhance.value
}

async function submit() {
  if (!files.value.length) return
  submitting.value = true
  try {
    const target = lockedBankId.value || (targetMode.value === 'existing' ? targetBankId.value : null)
    // 预设 → 引擎/思考：智能推荐=AUTO+思考开；最快=LOCAL+关思考；精细=LOCAL+思考开；
    // MinerU 增强勾选（仅扫描/图片文件）时强制 MINERU（思考按预设保留）
    const engine = mineruEnhance.value ? 'MINERU' : modePreset.value === 'smart' ? 'AUTO' : 'LOCAL'
    const thinking = modePreset.value !== 'fast'
    const jobId = await createAiImportJob(files.value, target, aiSupplement.value, thinking, engine)
    // 任务入口由后端"最近任务"接口提供（侧边栏），无需本地存储
    submitting.value = false
    running.value = true
    watchJob(jobId)
  } catch (e) {
    submitting.value = false
    /* 400（未配置模型等）由拦截器提示 */
    // 模型名失效同样给自愈入口（例如刚下线的模型）
    handleModelError(e?.response?.data?.message || e?.message || '')
  }
}

/* SSE 优先，断开自动回退轮询。
   看门狗语义：SSE 建立后若长时间（8s）无任何事件（个别环境 EventSource 不工作/代理吞事件）才回退轮询；
   收到 update 事件必须重置看门狗——否则任何耗时 >8s 的任务都会被强切，SSE 实时通道形同虚设 */
function watchJob(jobId) {
  eventSource = subscribeAiJobStream(jobId, {
    onUpdate: (job) => handleSnapshot(jobId, job),
    onError: () => {
      // SSE 断开（代理/网络）：回退轮询
      eventSource?.close()
      eventSource = null
      poll(jobId)
    }
  })
  armWatchdog(jobId)
}

function armWatchdog(jobId) {
  clearTimeout(timer)
  timer = setTimeout(() => {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    poll(jobId)
  }, 8000)
}

function handleSnapshot(jobId, job) {
  progress.value = job.progress || 0
  fileCount.value = job.fileCount || 0
  currentFileIndex.value = job.currentFileIndex ?? null
  if (job.status === 'CANCELED') {
    // 任务被其他入口取消（如侧边栏删除按钮）
    failed.value = true
    stageText.value = '任务已取消'
    errorMsg.value = '任务已被取消'
    cleanup()
    return
  }
  if (job.status === 'FAILED') {
    failed.value = true
    stageText.value = '处理失败'
    errorMsg.value = job.error || '未知错误'
    cleanup()
    // 命中模型失效 → 提示 + 询问是否去设置页获取可用模型（其余失败保持静默）
    handleModelError(errorMsg.value)
    return
  }
  stageText.value = buildStageText(job)
  if (job.status === 'SUCCESS') {
    stageText.value = '处理完成'
    cleanup()
    emit('done', jobId)
    visible.value = false
    ElMessage.success(t('parseDone', { n: job.questions?.length || 0 }))
    return
  }
  //进行中：收到实时事件 → 重置 SSE 看门狗（见 watchJob 注释）
  if (eventSource) armWatchdog(jobId)
}

function poll(jobId) {
  clearInterval(timer)
  timer = setInterval(async () => {
    try {
      const job = await getAiImportJob(jobId)
      handleSnapshot(jobId, job)
    } catch (e) {
      /* 网络抖动时继续轮询 */
    }
  }, 1500)
}

function cleanup() {
  clearInterval(timer)
  eventSource?.close()
  eventSource = null
}

function goSettings() {
  cleanup()
  visible.value = false
  router.push('/settings')
}

function close() {
  cleanup()
  visible.value = false
}

//路由切换/弹窗宿主卸载：停止 SSE 与轮询，防止离开页面后任务空转到终态、完成事件丢失
onBeforeUnmount(cleanup)

const formatSize = (bytes) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

defineExpose({ open })
</script>

<style scoped>
.no-config {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 26px 0;
  color: var(--text-muted);
  text-align: center;
}
.no-config .btn {
  margin-top: 8px;
}
.no-config-local {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

.ai-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.field-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.form-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}
.img-hint {
  margin-top: 8px;
}
.file-pick {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-control);
  color: var(--text-secondary);
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease);
}
.file-pick:hover,
.file-pick.picked {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.file-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.file-name {
  font-size: 14px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-size {
  font-size: 12px;
}
.file-clear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: var(--text-muted);
  flex-shrink: 0;
  background: none;
  border: none;
  cursor: pointer;
  transition: all var(--ease);
}
.file-clear:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.file-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 9px;
}
.file-row .file-name {
  flex: 1;
  font-size: 13px;
}

.target-options {
  display: flex;
  gap: 10px;
}
.target-option {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 10px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--ease), background var(--ease);
}
.target-option:hover {
  border-color: var(--border-strong);
}
.target-option.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.target-locked {
  font-size: 13px;
  padding: 10px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
}

/* AI 补充开关 */
.supplement-option {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 12px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--ease), background var(--ease);
}
.supplement-option:hover {
  border-color: var(--border-strong);
}
.supplement-option.checked {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.supplement-option.disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.supplement-check {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 5px;
  border: 2px solid var(--text-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  transition: all var(--ease);
}
.supplement-option.checked .supplement-check {
  border-color: var(--accent);
  background: var(--accent);
}
.supplement-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.mode-title {
  font-size: 14px;
  font-weight: 500;
}
.mode-desc {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}

/* 导入模式三选（第十一轮：快速/标准/深度） */
.mode-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mode-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  width: 100%;
  text-align: left;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-card);
  color: var(--text-primary);
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease);
}
.mode-option:hover {
  border-color: var(--border-strong);
}
.mode-option.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.mode-option.active .mode-title {
  color: var(--accent-text);
}
.mode-badge {
  display: inline-block;
  margin-left: 6px;
  font-size: 11px;
  font-weight: 600;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 16px;
  vertical-align: 1px;
}

.ai-progress {
  padding: 10px 4px 4px;
}
.stage-text {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: var(--text-primary);
  margin-bottom: 16px;
}
.stage-spin {
  display: inline-flex;
  color: var(--accent-text);
  animation: ai-spin 1.2s linear infinite;
}
.stage-fail {
  color: var(--danger);
}
@keyframes ai-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.progress-track {
  height: 6px;
  border-radius: 999px;
  background: var(--bg-elev);
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
  transition: width 0.6s ease;
}
.progress-num {
  margin: 8px 0 6px;
  font-size: 12px;
}
.error-text {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--danger);
  line-height: 1.6;
}
</style>
