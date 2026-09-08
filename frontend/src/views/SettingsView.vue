<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">设置</h1>
        <p class="page-desc">刷题记录的备份与迁移（换设备 / 重装恢复）</p>
      </div>
    </header>

    <!-- 完整备份：一键打包全部本机数据（数据库一致性快照 + 图片 + AI 配置） -->
    <section class="panel">
      <h2 class="panel-title">完整备份</h2>
      <p class="panel-desc text-secondary">
        一键下载本机<b>全部数据</b>的备份包：题库与题目、刷题记录与复习进度（数据库一致性快照）、
        题目图片、AI 模型配置（含 Key，等同钥匙，请妥善保管备份文件）。
        换电脑 / 重装 / 误删恢复都靠它。
      </p>
      <div class="panel-actions">
        <button class="btn btn-primary" :disabled="backingUp" @click="doBackup">
          <TikuIcon name="download" :size="15" />
          {{ backingUp ? '打包中…' : '一键备份并下载' }}
        </button>
        <button v-if="isDesktopEnv" class="btn btn-secondary" :disabled="backingUp || restoring" @click="doRestore">
          <TikuIcon name="upload" :size="15" />
          {{ restoring ? '处理中…' : '从备份恢复…' }}
        </button>
        <span class="form-tip text-muted backup-tip">
          恢复 = 选择备份包 → 自动重启应用并还原数据，无需手动操作
        </span>
      </div>
    </section>
    <!-- 外观:双主题(Soft UI 白天 / 护眼暖黑夜,跟随系统) -->
    <section class="panel">
      <h2 class="panel-title">外观</h2>
      <p class="panel-desc text-secondary">
        主题选择即时生效并保存在本机。黑夜为低蓝光护眼底色，适合长时间刷题。
        当前生效：<span class="theme-state">{{ effectiveLabel }}</span><template v-if="themePref === 'system'">（跟随系统，系统切换时自动跟随）</template>
      </p>
      <el-radio-group v-model="themePref" @change="onThemeChange">
        <el-radio-button value="system">跟随系统</el-radio-button>
        <el-radio-button value="light">白天</el-radio-button>
        <el-radio-button value="dark">黑夜</el-radio-button>
      </el-radio-group>
    </section>

    <section class="panel">
      <h2 class="panel-title">刷题记录</h2>
      <p class="panel-desc text-secondary">
        刷题记录保存在本机。导出为文件后，可在其他设备上先导入对应的题库文件，再导入记录文件恢复进度；
        找不到对应题库文件的记录会被跳过并提示，不会静默丢弃。
      </p>
      <div class="panel-actions">
        <button class="btn btn-secondary" :disabled="exporting" @click="doExport">
          <TikuIcon name="download" :size="15" />
          {{ exporting ? '导出中…' : '导出记录文件' }}
        </button>
        <button class="btn btn-primary" :disabled="importing" @click="doImport">
          <TikuIcon name="upload" :size="15" />
          {{ importing ? '导入中…' : '导入记录文件' }}
        </button>
      </div>

      <!-- 导入结果 -->
      <div v-if="importResult" class="import-result">
        <p v-if="importResult.imported > 0" class="ok-line">
          <TikuIcon name="check" :size="14" />
          成功导入 {{ importResult.imported }} 条记录
        </p>
        <p v-if="importResult.missingBanks?.length" class="warn-line">
          <TikuIcon name="info" :size="14" />
          有 {{ importResult.missingBanks.length }} 条记录因找不到对应的题库文件（packageKey + version）被跳过，请先导入对应版本的题库文件
        </p>
        <p v-if="importResult.missingQuestions?.length" class="warn-line">
          <TikuIcon name="info" :size="14" />
          {{ importResult.missingQuestions.length }} 个题目在当前题库中不存在：
          {{ importResult.missingQuestions.slice(0, 5).join('、') }}{{ importResult.missingQuestions.length > 5 ? '…' : '' }}
        </p>
      </div>
    </section>

    <section class="panel">
      <h2 class="panel-title">AI 模型配置</h2>
      <p class="panel-desc text-secondary">
        用于「AI 导入」（文档 / 图片 → 题目）。Key 仅保存在本机配置文件，前端只显示脱敏后的 Key；
        支持 DeepSeek / 通义 / Kimi / OpenAI / 本地 Ollama 等 OpenAI 兼容端点。
      </p>
      <p class="form-tip text-muted role-tip">
        三者角色：① 文本模型 = 题目整理与思考的主力；② 多模态模型（识图）= 含图文档必需——
        文字版 Word/PDF 的直传视觉、扫描件的直读都靠它，有图文档务必配置；③ MinerU（可选）=
        仅"扫描件/纯图片且含图形图表"需提取版面与题图时使用，导入对话框会按文件提示。
        纯文字 txt/md 只需文本模型。
      </p>
      <p class="key-guide-entry">
        第一次配置、不知道 Key 去哪弄？
        <button class="key-guide-link" @click="openKeyGuide">点这里看「如何获取 API Key」分步教程 →</button>
      </p>
      <el-form label-position="top" @submit.prevent>
        <!-- 服务商预设：选择后自动填充 Base URL / 模型名（仍可手动修改），只需填 API Key -->
        <div class="preset-row">
          <el-form-item label="选择服务商（自动填充配置，可手动修改）">
            <el-select v-model="presetAgent" placeholder="选择服务商…" clearable style="width: 320px" @change="applyPreset">
              <el-option
                v-for="p in presetAgents"
                :key="p.name"
                :label="p.label"
                :value="p.name"
              >
                <span>{{ p.label }}</span>
                <span class="preset-desc">{{ p.desc }}</span>
              </el-option>
            </el-select>
            <p class="form-tip text-muted">选择后自动填入 Base URL 与模型名，只需再填 API Key；也可不选，全部手动填写</p>
          </el-form-item>
        </div>
        <div class="ai-grid">
          <el-form-item label="Base URL">
            <el-input v-model="aiForm.baseUrl" placeholder="https://api.deepseek.com/v1" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input
              v-model="aiForm.apiKey"
              type="password"
              show-password
              :placeholder="maskedKey || 'sk-…'"
            />
            <p class="form-tip text-muted">留空 = 保留原有 Key{{ hasKey ? '（当前：' + maskedKey + '）' : '' }}</p>
          </el-form-item>
          <el-form-item label="模型（文本整理）">
            <el-input v-model="aiForm.model" placeholder="deepseek-chat" />
          </el-form-item>
          <el-form-item label="多模态模型（图片/扫描件，可留空 = 同文本模型）">
            <el-input v-model="aiForm.visionModel" placeholder="qwen-vl-plus" />
          </el-form-item>
          <el-form-item label="MinerU 解析 API Key（可选）">
            <el-input
              v-model="aiForm.mineruKey"
              type="password"
              show-password
              :placeholder="maskedMineruKey || 'sk-…'"
            />
            <p class="form-tip text-muted">
              用于 pdf/图片/docx 的结构化解析（版面/OCR/公式/表格→增强文本），AI 整理仍用上方模型；留空 = 使用本地解析路径。申请：
              <a href="https://mineru.net/apiManage/token" target="_blank" rel="noopener">mineru.net/apiManage/token</a>
              {{ hasMineruKey ? '（当前：' + maskedMineruKey + '）' : '' }}
            </p>
          </el-form-item>
        </div>
        <p class="form-tip text-muted">「思考模式」在 AI 导入的「处理模式」中按需选择：智能推荐与精细默认开启（更稳更准），最快模式关闭（更快）</p>
      </el-form>

      <!-- 测试结果 -->
      <div v-if="testResult" class="test-result" :class="testResult.ok ? 'ok' : 'fail'">
        <TikuIcon :name="testResult.ok ? 'check' : 'x'" :size="14" />
        <span>{{ testResult.message }}</span>
        <span v-if="testResult.ok" class="mono">（{{ testResult.latencyMs }}ms）</span>
      </div>

      <div class="panel-actions">
        <button class="btn btn-secondary" :disabled="testing" @click="doTest">
          <TikuIcon name="refresh" :size="14" />
          {{ testing ? '测试中…' : '测试连接' }}
        </button>
        <button class="btn btn-primary" :disabled="saving" @click="doSave">
          {{ saving ? '保存中…' : '保存配置' }}
        </button>
        <span class="form-tip text-muted test-tip">测试连接会先保存当前表单内容</span>
      </div>
    </section>

    <!-- API Key 获取指引弹窗（分服务商分步；Ollama 无需 Key） -->
    <el-dialog v-model="keyGuideVisible" :title="`如何获取 API Key：${guideFor.label}`" width="min(92vw, 540px)" align-center>
      <p class="guide-what text-secondary">
        配置只需填三样：<b>Base URL</b> 与 <b>模型名</b> 选择服务商后会自动填入，
        你只需拿到并填好 <b>API Key</b>。Key 只在创建页完整显示一次，且只保存在你的本机。
      </p>
      <ol class="key-steps">
        <li v-for="(s, i) in guideFor.steps" :key="i">{{ s }}</li>
      </ol>
      <template #footer>
        <button class="btn btn-ghost" @click="keyGuideVisible = false">关闭</button>
        <button
          v-if="guideFor.url"
          class="btn btn-primary"
          @click="openGuideUrl"
        >
          打开 {{ guideFor.label }} 创建页
        </button>
      </template>
    </el-dialog>

    <section class="panel">
      <h2 class="panel-title">作者信息</h2>
      <p class="panel-desc text-secondary">
        导出题库文件时自动带入的默认作者展示名（本地记忆）。题库广场上线后，登录账号身份会取代这里的名字；
        未填写时导出弹窗可手动输入。
      </p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="默认作者名">
          <div class="author-row" style="display: flex; gap: 10px; align-items: center; width: 100%">
            <el-input v-model="authorName" placeholder="例如：小明老师" maxlength="100" style="max-width: 320px" />
            <button class="btn btn-primary" :disabled="savingAuthor" @click="saveAuthor">
              {{ savingAuthor ? '保存中…' : '保存' }}
            </button>
          </div>
        </el-form-item>
      </el-form>
    </section>

    <section class="panel">
      <h2 class="panel-title">关于</h2>
      <p class="panel-desc text-secondary">
        拾题 · 自建题库刷题应用（离线优先）。数据存放于本机数据目录，核心功能不依赖登录和网络。
      </p>

      <!-- 桌面版：版本 + 自动更新 -->
      <div v-if="isDesktopEnv" class="update-box">
        <div class="update-meta">
          <span class="text-secondary">当前版本</span>
          <span class="mono update-ver">v{{ versionLabel }}</span>
          <span v-if="updateInfo" class="update-new mono">新版本 v{{ updateInfo.version }} 可更新</span>
        </div>
        <div class="update-actions">
          <button class="btn btn-secondary" :disabled="busy" @click="doCheckUpdate">
            <TikuIcon name="refresh" :size="14" />
            {{ checking ? '检查中…' : '检查更新' }}
          </button>
          <button v-if="updateInfo && !downloading && !installing" class="btn btn-primary" @click="doDownload">
            下载并安装
          </button>
        </div>
        <div v-if="downloading" class="update-progress">
          <el-progress :percentage="progressPct" :stroke-width="10" />
          <p class="form-tip text-muted">正在下载新版本安装包（约 {{ sizeMB }} MB）…</p>
        </div>
        <p v-if="installing" class="form-tip">
          即将安装并自动重启应用，请稍候…
        </p>
        <p v-if="updateInfo?.notes" class="form-tip text-muted update-notes">{{ updateInfo.notes }}</p>
        <p v-if="updateError" class="form-tip update-error">{{ updateError }}</p>
      </div>
      <p v-else class="form-tip text-muted">网页版不提供自动更新，请从官网下载桌面版。</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportStudyRecords, importStudyRecords } from '../api/studyRecords'
import { downloadBackup, prepareRestore } from '../api/backup'
import { getAiSettings, saveAiSettings, testAiSettings } from '../api/aiImport'
import { pickFile, readTextFile, saveBlob, saveJsonFile } from '../utils/files'
import { invoke } from '@tauri-apps/api/core'
import TikuIcon from '../components/TikuIcon.vue'
import { getTheme, setTheme, currentTheme } from '../utils/theme'
import { isDesktop, getAppVersion, checkForUpdate, downloadUpdate, installUpdate } from '../utils/updater'
import { openExternal } from '../utils/external'

/* ---------- 版本与自动更新（桌面版；自建频道 pickq.cn/updates） ---------- */
const isDesktopEnv = ref(isDesktop())
const versionLabel = ref('—')
const checking = ref(false)
const downloading = ref(false)
const installing = ref(false)
const progressPct = ref(0)
const updateInfo = ref(null)
const updateError = ref('')
const busy = computed(() => checking.value || downloading.value || installing.value)
const sizeMB = computed(() => {
  const s = Number(updateInfo.value?.size) || 0
  return s > 0 ? (s / 1024 / 1024).toFixed(0) : '—'
})

onMounted(async () => {
  if (!isDesktopEnv.value) return
  versionLabel.value = (await getAppVersion()) || '—'
  // 静默自动检查：有新版则填充状态（无打扰），用户可点「下载并安装」
  const r = await checkForUpdate()
  if (r.supported && r.info) updateInfo.value = r.info
})

async function doCheckUpdate() {
  checking.value = true
  updateError.value = ''
  try {
    const r = await checkForUpdate()
    if (!r.supported) return
    if (r.error) {
      updateError.value = r.error
      return
    }
    updateInfo.value = r.info
    if (!r.info) ElMessage.success('已是最新版本')
  } finally {
    checking.value = false
  }
}

async function doDownload() {
  const info = updateInfo.value
  if (!info || downloading.value) return
  downloading.value = true
  updateError.value = ''
  progressPct.value = 0
  const r = await downloadUpdate(info, (pct) => {
    progressPct.value = pct
  })
  downloading.value = false
  if (!r.ok) {
    updateError.value = r.error
    return
  }
  // 下载完成：询问是否安装（安装会关闭并重启应用）
  try {
    await ElMessageBox.confirm(
      `新版本 v${info.version} 已下载完成。\n\n安装过程中应用会自动关闭并重新启动，你的题库与记录不会丢失。是否立即安装？`,
      '安装更新',
      { confirmButtonText: '立即安装', cancelButtonText: '稍后再说', type: 'info', closeOnClickModal: false }
    )
  } catch {
    return // 用户选择稍后
  }
  installing.value = true
  updateError.value = ''
  try {
    await installUpdate(info.version)
  } catch (e) {
    installing.value = false
    updateError.value = String(e?.message || e || '启动安装失败')
  }
}

/* ---------- 完整备份（B4：数据库一致性快照 + 图片 + AI 配置 → zip） ---------- */
const backingUp = ref(false)

function backupFilename() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `tiku-backup-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.zip`
}

async function doBackup() {
  backingUp.value = true
  try {
    const blob = await downloadBackup()
    if (!blob || blob.size === 0) {
      ElMessage.error('备份失败：返回内容为空，请重试')
      return
    }
    const res = await saveBlob(blob, backupFilename())
    if (res && !res.saved) return //用户取消另存为
    ElMessage.success(
      res?.path
        ? `备份包已保存到：${res.path}（含 AI Key 请妥善保管）`
        : '备份包已下载：请妥善保管（含 AI Key）'
    )
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    backingUp.value = false
  }
}

/* ---------- 一键恢复（桌面版）：选备份包 → 后端校验解压 → 壳带参数重启自动恢复 ---------- */
const restoring = ref(false)

async function doRestore() {
  if (!isDesktopEnv.value) {
    ElMessage.warning('网页版不支持一键恢复，请使用桌面版')
    return
  }
  let file
  try {
    file = await pickFile('.zip,application/zip')
  } catch (e) {
    return // 用户取消选择
  }
  if (!file) return
  restoring.value = true
  try {
    const res = await prepareRestore(file)
    const dataDir = res?.dataDir
    if (!dataDir) {
      throw new Error('恢复准备失败：未返回数据目录')
    }
    try {
      await ElMessageBox.confirm(
        '备份文件校验通过。\n\n恢复会用备份内容替换当前全部数据（题库、刷题记录、图片与 AI 配置），随后应用会自动重启完成恢复，期间请勿关闭应用窗口。确定继续吗？',
        '从备份恢复',
        {
          confirmButtonText: '开始恢复',
          cancelButtonText: '取消',
          type: 'warning',
          confirmButtonClass: 'el-button--danger'
        }
      )
    } catch (e) {
      return // 用户取消
    }
    ElMessage.info('正在恢复数据，应用会自动重启，请稍候…')
    try {
      await invoke('restart_with_restore', { dataDir })
    } catch (e) {
      ElMessage.error('启动恢复失败：' + String(e?.message || e || '未知错误'))
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '备份文件处理失败')
  } finally {
    restoring.value = false
  }
}

/* ---------- 外观(双主题,规范 v2.1 §4.10) ---------- */
const themePref = ref(getTheme())
const effectiveLabel = ref(currentTheme() === 'dark' ? '黑夜' : '白天')

function onThemeChange(v) {
  setTheme(v)
  effectiveLabel.value = currentTheme() === 'dark' ? '黑夜' : '白天'
}

/* ---------- 作者信息（默认作者名，本地记忆；导出弹窗默认带入） ---------- */
const DEFAULT_AUTHOR_KEY = 'tiku:default-author'
const authorName = ref('')
const savingAuthor = ref(false)
try {
  authorName.value = localStorage.getItem(DEFAULT_AUTHOR_KEY) || ''
} catch (e) {
  /* 忽略 */
}

async function saveAuthor() {
  savingAuthor.value = true
  try {
    const name = authorName.value.trim()
    if (name) {
      localStorage.setItem(DEFAULT_AUTHOR_KEY, name)
    } else {
      localStorage.removeItem(DEFAULT_AUTHOR_KEY)
    }
    ElMessage.success(name ? '默认作者名已保存' : '已清除默认作者名')
  } catch (e) {
    ElMessage.error('保存失败（浏览器存储不可用）')
  } finally {
    savingAuthor.value = false
  }
}

/* ---------- AI 模型配置 ---------- */
const aiForm = ref({ baseUrl: '', apiKey: '', model: '', visionModel: '', mineruKey: '' })
const maskedKey = ref('')
const hasKey = ref(false)
const maskedMineruKey = ref('')
const hasMineruKey = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

/* 常用服务商预设（OpenAI 兼容端点）：选择后自动回填 baseUrl/model/visionModel，仅需填 Key */
const presetAgents = [
  { name: 'deepseek', label: 'DeepSeek（推荐）', desc: '文本+识图', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', visionModel: 'deepseek-v4-flash-vision-exp' },
  { name: 'qwen', label: '通义千问（阿里云百炼）', desc: '识图较强', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-max', visionModel: 'qwen-vl-max' },
  { name: 'kimi', label: 'Kimi（月之暗面）', desc: '长文本', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k', visionModel: 'moonshot-v1-8k-vision-preview' },
  { name: 'openai', label: 'OpenAI', desc: 'GPT 系列', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', visionModel: 'gpt-4o' },
  { name: 'zhipu', label: '智谱清言', desc: 'GLM 系列', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash', visionModel: 'glm-4v-flash' },
  { name: 'ollama', label: '本地 Ollama', desc: '离线本地模型', baseUrl: 'http://localhost:11434/v1', model: 'qwen2.5', visionModel: 'qwen2.5-vl' }
]
const presetAgent = ref('')

/* API Key 获取指引（分服务商步骤；"填什么"一句话讲清：Base URL 与模型名自动填，只需 Key） */
const PROVIDER_GUIDES = {
  deepseek: {
    label: 'DeepSeek',
    url: 'https://platform.deepseek.com/api_keys',
    steps: [
      '用手机号注册 / 登录 DeepSeek 开放平台（platform.deepseek.com）',
      '左侧菜单进入「API Keys」，点「创建 API Key」',
      '复制生成的密钥（sk- 开头）——只完整显示这一次，先粘贴到记事本或直接填入下方',
      '回到本页把密钥填入「API Key」，点「保存配置」即可',
      '新账号需先在「充值」页购买少量额度，Key 才能正常调用'
    ]
  },
  qwen: {
    label: '通义千问',
    url: 'https://bailian.console.aliyun.com/',
    steps: [
      '用支付宝 / 淘宝账号登录阿里云，进入「百炼」控制台（首次进入按提示开通）',
      '右上角头像菜单里选「API-KEY」→ 创建我的 API-KEY（新用户通常有免费额度）',
      '复制生成的密钥（sk- 开头）——只完整显示这一次，请先保存',
      '回到本页填入「API Key」并保存配置'
    ]
  },
  kimi: {
    label: 'Kimi',
    url: 'https://platform.moonshot.cn/console/api-keys',
    steps: [
      '注册 / 登录 Kimi 开放平台（platform.moonshot.cn）',
      '左侧「API Key 管理」→ 新建 API Key',
      '复制生成的密钥（sk- 开头）并保存（只显示一次）',
      '回到本页填入「API Key」并保存配置'
    ]
  },
  openai: {
    label: 'OpenAI',
    url: 'https://platform.openai.com/api-keys',
    steps: [
      '注册 / 登录 OpenAI 平台（国内网络需自行解决访问）',
      '进入「API keys」→ Create new secret key',
      '复制生成的密钥（sk- 开头）并妥善保存（只显示一次）',
      '回到本页填入「API Key」并保存配置；新账号需先充值'
    ]
  },
  zhipu: {
    label: '智谱清言',
    url: 'https://open.bigmodel.cn/usercenter/apikeys',
    steps: [
      '注册 / 登录智谱开放平台（open.bigmodel.cn）',
      '进入「API 密钥」页面 → 创建 API Key',
      '复制生成的密钥并保存（只显示一次）',
      '回到本页填入「API Key」并保存配置；GLM 系列有免费模型可用'
    ]
  },
  ollama: {
    label: '本地 Ollama',
    url: 'https://ollama.com/download',
    steps: [
      'Ollama 是本地模型，不需要 API Key，也不用联网',
      '官网下载安装 Ollama（Windows 版），命令行执行 ollama pull qwen2.5 等拉取模型',
      'Base URL 保持 http://localhost:11434/v1，模型名填你拉取的模型',
      '识图模型（扫描件/图片）需另拉 qwen2.5-vl 等视觉模型'
    ]
  }
}
const keyGuideVisible = ref(false)
const guideFor = computed(() => {
  const g = PROVIDER_GUIDES[presetAgent.value]
  return g || PROVIDER_GUIDES.deepseek
})
function openKeyGuide() {
  keyGuideVisible.value = true
}

/** 打开服务商创建页：成功/失败都给明确反馈（浏览器可能把新标签开在已有窗口里，避免用户以为没反应） */
async function openGuideUrl() {
  const ok = await openExternal(guideFor.value.url)
  if (ok) {
    ElMessage.success(`已用系统浏览器打开 ${guideFor.value.label}，请到浏览器中查看`)
  } else {
    ElMessage.warning('无法打开链接')
  }
}

/** 选择预设：回填 Base URL / 模型名（不清空 Key），用户仍可手动修改 */
function applyPreset(name) {
  const p = presetAgents.find((x) => x.name === name)
  if (!p) return
  aiForm.value.baseUrl = p.baseUrl
  aiForm.value.model = p.model
  aiForm.value.visionModel = p.visionModel
}

async function loadAiSettings() {
  try {
    const s = await getAiSettings()
    aiForm.value.baseUrl = s.baseUrl || ''
    aiForm.value.model = s.model || ''
    aiForm.value.visionModel = s.visionModel || ''
    maskedKey.value = s.maskedKey || ''
    hasKey.value = !!s.hasKey
    maskedMineruKey.value = s.maskedMineruKey || ''
    hasMineruKey.value = !!s.hasMineruKey
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function doSave() {
  saving.value = true
  testResult.value = null
  try {
    await saveAiSettings({
      baseUrl: aiForm.value.baseUrl.trim() || null,
      apiKey: aiForm.value.apiKey.trim() || null, // 空 = 保留旧 Key
      model: aiForm.value.model.trim() || null,
      visionModel: aiForm.value.visionModel.trim() || null,
      mineruKey: aiForm.value.mineruKey.trim() || null // 空 = 保留旧 Key
    })
    ElMessage.success('配置已保存')
    aiForm.value.apiKey = ''
    aiForm.value.mineruKey = ''
    await loadAiSettings()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function doTest() {
  testing.value = true
  testResult.value = null
  try {
    // 测试基于当前表单而非旧配置：先保存（空 Key = 保留旧值），再发起测试请求
    await saveAiSettings({
      baseUrl: aiForm.value.baseUrl.trim() || null,
      apiKey: aiForm.value.apiKey.trim() || null,
      model: aiForm.value.model.trim() || null,
      visionModel: aiForm.value.visionModel.trim() || null,
      mineruKey: aiForm.value.mineruKey.trim() || null
    })
    aiForm.value.apiKey = ''
    aiForm.value.mineruKey = ''
    await loadAiSettings()
    testResult.value = await testAiSettings()
  } catch (e) {
    /* 拦截器已提示（含 baseUrl 校验、未填 Key 等） */
  } finally {
    testing.value = false
  }
}

loadAiSettings()

const exporting = ref(false)
const importing = ref(false)
const importResult = ref(null)

async function doExport() {
  exporting.value = true
  try {
    const data = await exportStudyRecords()
    const now = new Date()
    const pad = (n) => String(n).padStart(2, '0')
    const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`
    const res = await saveJsonFile(data, `tiku-study-records-${stamp}.json`)
    if (res && !res.saved) return //用户取消另存为
    ElMessage.success(res?.path ? `记录文件已保存到：${res.path}` : '记录文件已导出')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    exporting.value = false
  }
}

async function doImport() {
  let file
  try {
    file = await pickFile('.json,application/json')
  } catch (e) {
    return // 用户取消
  }
  importing.value = true
  importResult.value = null
  try {
    const text = await readTextFile(file)
    if (!text.trim()) {
      ElMessage.warning('文件内容为空')
      return
    }
    const res = await importStudyRecords(text)
    importResult.value = res
    ElMessage.success(`导入完成：${res.imported} 条记录`)
  } catch (e) {
    /* 400（文件格式错误等）由拦截器提示 */
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.page-header {
  margin-bottom: 28px;
}
.page-title {
  font-size: 24px;
}
.page-desc {
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
}
.theme-state {
  color: var(--accent-text);
  font-weight: 600;
}

.panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 22px 24px;
  margin-bottom: 16px;
}
.panel-title {
  font-size: 16px;
  margin-bottom: 8px;
}
.panel-desc {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.7;
  max-width: 640px;
}
.panel-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.test-tip {
  font-size: 12px;
}

.import-result {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  font-size: 13px;
}
.import-result p {
  margin: 4px 0;
  display: flex;
  align-items: flex-start;
  gap: 7px;
  line-height: 1.6;
}
.ok-line {
  color: var(--success);
}
.warn-line {
  color: var(--warning);
}

/* AI 配置 */
.ai-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
  max-width: 720px;
}
.preset-row {
  max-width: 720px;
  margin-bottom: 14px;
}
.preset-desc {
  float: right;
  color: var(--text-muted);
  font-size: 12px;
  margin-left: 12px;
}
.form-tip {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
.test-result {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 10px 14px;
  border-radius: 9px;
  border: 1px solid;
  margin-bottom: 14px;
}
.test-result.ok {
  color: var(--success);
  background: var(--success-soft);
  border-color: var(--success);
}
.test-result.fail {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: var(--danger);
}

/* ---------- 版本与自动更新 ---------- */
.update-box {
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px dashed var(--border);
  border-radius: 10px;
  background: var(--surface-2);
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 640px;
}
.update-meta {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.update-ver {
  font-weight: 600;
  color: var(--text);
}
.update-new {
  color: var(--primary);
  font-weight: 600;
}
.update-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
.update-progress {
  max-width: 420px;
}
.update-notes {
  white-space: pre-wrap;
}
.update-error {
  color: var(--danger);
}

/* ---------- API Key 获取指引 ---------- */
.key-guide-entry {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--text-secondary);
}
.key-guide-link {
  border: none;
  background: transparent;
  padding: 0;
  color: var(--accent-text);
  font-family: var(--font-sans);
  font-size: 13px;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.key-guide-link:hover {
  opacity: 0.85;
}
.guide-what {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.9;
}
.key-steps {
  margin: 0;
  padding-left: 1.4em;
  font-size: 13.5px;
  line-height: 2;
  color: var(--text-primary);
}
.key-steps li::marker {
  color: var(--accent-text);
  font-weight: 600;
}

@media (max-width: 720px) {
  .ai-grid {
    grid-template-columns: 1fr;
  }
}
</style>
