<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('pageTitle') }}</h1>
        <p class="page-desc">{{ t('pageDesc') }}</p>
      </div>
    </header>

    <!-- 完整备份：一键打包全部本机数据（数据库一致性快照 + 图片 + AI 配置） -->
    <section class="panel">
      <h2 class="panel-title">{{ t('backup.title') }}</h2>
      <p class="panel-desc text-secondary" v-html="t('backup.descHtml')"></p>
      <div class="panel-actions">
        <button class="btn btn-primary" :disabled="backingUp" @click="doBackup">
          <TikuIcon name="download" :size="15" />
          {{ backingUp ? t('backup.busy') : t('backup.btn') }}
        </button>
        <button v-if="isDesktopEnv" class="btn btn-secondary" :disabled="backingUp || restoring" @click="doRestore">
          <TikuIcon name="upload" :size="15" />
          {{ restoring ? t('backup.restoreBusy') : t('backup.restore') }}
        </button>
        <span class="form-tip text-muted backup-tip">
          {{ t('backup.tip') }}
        </span>
      </div>
    </section>
    <!-- 外观:双主题(Soft UI 白天 / 护眼暖黑夜,跟随系统) + 界面语言 -->
    <section class="panel">
      <h2 class="panel-title">{{ t('appearance.title') }}</h2>
      <p class="panel-desc text-secondary">
        {{ t('appearance.desc1') }}<span class="theme-state">{{ effectiveLabel }}</span><template v-if="themePref === 'system'">{{ t('appearance.systemNote') }}</template>
      </p>
      <el-radio-group v-model="themePref" @change="onThemeChange">
        <el-radio-button value="system">{{ t('appearance.themeSystem') }}</el-radio-button>
        <el-radio-button value="light">{{ t('appearance.themeLight') }}</el-radio-button>
        <el-radio-button value="dark">{{ t('appearance.themeDark') }}</el-radio-button>
      </el-radio-group>
      <p class="panel-desc text-secondary lang-desc">
        {{ t('appearance.langLead') }}<template v-if="langPref === 'system'">{{ t('appearance.langSystemNow', { v: currentLang === 'en-US' ? 'English' : '中文' }) }}</template>
      </p>
      <el-radio-group :model-value="langPref" @change="onLangChange">
        <el-radio-button value="system">{{ t('appearance.langSystem') }}</el-radio-button>
        <el-radio-button value="zh-CN">{{ t('appearance.langZh') }}</el-radio-button>
        <el-radio-button value="en-US">{{ t('appearance.langEn') }}</el-radio-button>
      </el-radio-group>
    </section>

    <section class="panel">
      <h2 class="panel-title">{{ t('records.title') }}</h2>
      <p class="panel-desc text-secondary">{{ t('records.desc') }}</p>
      <div class="panel-actions">
        <button class="btn btn-secondary" :disabled="exporting" @click="doExport">
          <TikuIcon name="download" :size="15" />
          {{ exporting ? t('records.exporting') : t('records.export') }}
        </button>
        <button class="btn btn-primary" :disabled="importing" @click="doImport">
          <TikuIcon name="upload" :size="15" />
          {{ importing ? t('records.importing') : t('records.import') }}
        </button>
      </div>

      <!-- 导入结果 -->
      <div v-if="importResult" class="import-result">
        <p v-if="importResult.imported > 0" class="ok-line">
          <TikuIcon name="check" :size="14" />
          {{ t('records.ok', { n: importResult.imported }) }}
        </p>
        <p v-if="importResult.missingBanks?.length" class="warn-line">
          <TikuIcon name="info" :size="14" />
          {{ t('records.missing', { n: importResult.missingBanks.length }) }}
        </p>
        <p v-if="importResult.missingQuestions?.length" class="warn-line">
          <TikuIcon name="info" :size="14" />
          {{ t('records.missingQ', { n: importResult.missingQuestions.length }) }}
          {{ importResult.missingQuestions.slice(0, 5).join('、') }}{{ importResult.missingQuestions.length > 5 ? '…' : '' }}
        </p>
      </div>
    </section>

    <section class="panel">
      <h2 class="panel-title">{{ t('ai.title') }}</h2>
      <p class="panel-desc text-secondary">{{ t('ai.desc') }}</p>
      <p class="form-tip text-muted role-tip">{{ t('ai.roleTip') }}</p>
      <p class="key-guide-entry">
        {{ t('ai.guideEntry') }}
        <button class="key-guide-link" @click="openKeyGuide">{{ t('ai.guideLink') }}</button>
      </p>
      <el-form label-position="top" @submit.prevent>
        <!-- 服务商预设：选择后自动填充 Base URL / 模型名（仍可手动修改），只需填 API Key -->
        <div class="preset-row">
          <el-form-item :label="t('ai.presetLabel')">
            <el-select v-model="presetAgent" :placeholder="t('ai.presetPh')" clearable style="width: 320px" @change="applyPreset">
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
            <p class="form-tip text-muted">{{ t('ai.presetTip') }}</p>
          </el-form-item>
        </div>
        <div class="ai-grid">
          <el-form-item :label="t('ai.baseUrl')">
            <el-input v-model="aiForm.baseUrl" placeholder="https://api.deepseek.com/v1" />
          </el-form-item>
          <el-form-item :label="t('ai.apiKey')">
            <el-input
              v-model="aiForm.apiKey"
              type="password"
              show-password
              :placeholder="maskedKey || 'sk-…'"
            />
            <p class="form-tip text-muted">{{ t('ai.keyKeep') }}{{ hasKey ? t('ai.keyCurrent', { v: maskedKey }) : '' }}</p>
          </el-form-item>
          <el-form-item :label="t('ai.modelText')">
            <el-input v-model="aiForm.model" placeholder="deepseek-chat" />
          </el-form-item>
          <el-form-item :label="t('ai.modelVision')">
            <el-input v-model="aiForm.visionModel" placeholder="qwen-vl-plus" />
          </el-form-item>
          <el-form-item :label="t('ai.mineru')">
            <el-input
              v-model="aiForm.mineruKey"
              type="password"
              show-password
              :placeholder="maskedMineruKey || 'sk-…'"
            />
            <p class="form-tip text-muted">
              {{ t('ai.mineruTip') }}
              <a href="https://mineru.net/apiManage/token" target="_blank" rel="noopener">mineru.net/apiManage/token</a>
              {{ hasMineruKey ? t('ai.keyCurrent', { v: maskedMineruKey }) : '' }}
            </p>
          </el-form-item>
        </div>
        <p class="form-tip text-muted">{{ t('ai.thinkingTip') }}</p>
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
          {{ testing ? t('ai.testing') : t('ai.test') }}
        </button>
        <button class="btn btn-primary" :disabled="saving" @click="doSave">
          {{ saving ? t('ai.saving') : t('ai.save') }}
        </button>
        <span class="form-tip text-muted test-tip">{{ t('ai.testTip') }}</span>
      </div>
    </section>

    <!-- API Key 获取指引弹窗（分服务商分步；Ollama 无需 Key） -->
    <el-dialog v-model="keyGuideVisible" :title="`${t('ai.guideTitle')}：${guideFor.label}`" width="min(92vw, 540px)" align-center>
      <p class="guide-what text-secondary" v-html="t('ai.guideWhat')"></p>
      <ol class="key-steps">
        <li v-for="(s, i) in guideFor.steps" :key="i">{{ s }}</li>
      </ol>
      <template #footer>
        <button class="btn btn-ghost" @click="keyGuideVisible = false">{{ t('ai.close') }}</button>
        <button
          v-if="guideFor.url"
          class="btn btn-primary"
          @click="openGuideUrl"
        >
          {{ t('ai.open', { label: guideFor.label }) }}
        </button>
      </template>
    </el-dialog>

    <section class="panel">
      <h2 class="panel-title">{{ t('author.title') }}</h2>
      <p class="panel-desc text-secondary">{{ t('author.desc') }}</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('author.name')">
          <div class="author-row" style="display: flex; gap: 10px; align-items: center; width: 100%">
            <el-input v-model="authorName" :placeholder="t('author.namePh')" maxlength="100" style="max-width: 320px" />
            <button class="btn btn-primary" :disabled="savingAuthor" @click="saveAuthor">
              {{ savingAuthor ? t('author.save') + '…' : t('author.save') }}
            </button>
          </div>
        </el-form-item>
      </el-form>
    </section>

    <section class="panel">
      <h2 class="panel-title">{{ t('about.title') }}</h2>
      <p class="panel-desc text-secondary">{{ t('about.appDesc') }}</p>

      <!-- 桌面版：版本 + 自动更新 -->
      <div v-if="isDesktopEnv" class="update-box">
        <div class="update-meta">
          <span class="text-secondary">{{ t('about.currentVer') }}</span>
          <span class="mono update-ver">v{{ versionLabel }}</span>
          <span v-if="updateInfo" class="update-new mono">{{ t('about.newVer', { v: updateInfo.version }) }}</span>
        </div>
        <div class="update-actions">
          <button class="btn btn-secondary" :disabled="busy" @click="doCheckUpdate">
            <TikuIcon name="refresh" :size="14" />
            {{ checking ? t('about.checking') : t('about.check') }}
          </button>
          <button v-if="updateInfo && !downloading && !installing" class="btn btn-primary" @click="doDownload">
            {{ t('about.download') }}
          </button>
        </div>
        <div v-if="downloading" class="update-progress">
          <el-progress :percentage="progressPct" :stroke-width="10" />
          <p class="form-tip text-muted">{{ t('about.downloading', { p: progressPct }) }}</p>
        </div>
        <p v-if="installing" class="form-tip">
          {{ t('about.installing') }}
        </p>
        <p v-if="updateInfo?.notes" class="form-tip text-muted update-notes">{{ updateInfo.notes }}</p>
        <p v-if="updateError" class="form-tip update-error">{{ updateError }}</p>
      </div>
      <p v-else class="form-tip text-muted">{{ t('about.webNoUpdate') }}</p>
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
import { setLang, getLangPref, currentLang } from '../i18n/lang'
import { isDesktop, getAppVersion, checkForUpdate, downloadUpdate, installUpdate } from '../utils/updater'
import { openExternal } from '../utils/external'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      pageTitle: '设置',
      pageDesc: '刷题记录的备份与迁移（换设备 / 重装恢复）',
      backup: {
        title: '完整备份',
        descHtml:
          '一键下载本机<b>全部数据</b>的备份包：题库与题目、刷题记录与复习进度（数据库一致性快照）、题目图片、AI 模型配置（含 Key，等同钥匙，请妥善保管备份文件）。换电脑 / 重装 / 误删恢复都靠它。',
        btn: '一键备份并下载',
        busy: '打包中…',
        restore: '从备份恢复…',
        restoreBusy: '处理中…',
        tip: '恢复 = 选择备份包 → 自动重启应用并还原数据，无需手动操作',
        failEmpty: '备份失败：返回内容为空，请重试',
        savedPath: '备份包已保存到：{path}（含 AI Key 请妥善保管）',
        savedDownloaded: '备份包已下载：请妥善保管（含 AI Key）',
        webNoRestore: '网页版不支持一键恢复，请使用桌面版',
        restoreAsk: '备份文件校验通过。\n\n恢复会用备份内容替换当前全部数据（题库、刷题记录、图片与 AI 配置），随后应用会自动重启完成恢复，期间请勿关闭应用窗口。确定继续吗？',
        restoreTitle: '从备份恢复',
        restoring: '正在恢复数据，应用会自动重启，请稍候…',
        restoreStartFail: '启动恢复失败：{err}',
        unknownError: '未知错误',
        fileFail: '备份文件处理失败'
      },
      appearance: {
        title: '外观与语言',
        desc1: '主题选择即时生效并保存在本机。黑夜为低蓝光护眼底色，适合长时间刷题。当前生效：',
        systemNote: '（跟随系统，系统切换时自动跟随）',
        themeSystem: '跟随系统',
        themeLight: '白天',
        themeDark: '黑夜',
        langLead: '界面语言：',
        langSystem: '跟随系统',
        langSystemNow: '跟随系统（当前：{v}）',
        langZh: '中文',
        langEn: 'English',
        langSwitched: '已切换为中文',
        langFollowSystem: '已跟随系统语言'
      },
      records: {
        title: '刷题记录',
        desc: '刷题记录保存在本机。导出为文件后，可在其他设备上先导入对应的题库文件，再导入记录文件恢复进度；找不到对应题库文件的记录会被跳过并提示，不会静默丢弃。',
        export: '导出记录文件',
        exporting: '导出中…',
        import: '导入记录文件',
        importing: '导入中…',
        ok: '成功导入 {n} 条记录',
        missing: '有 {n} 条记录因找不到对应的题库文件（packageKey + version）被跳过，请先导入对应版本的题库文件',
        missingQ: '{n} 个题目在当前题库中不存在：',
        exportPath: '记录文件已保存到：{path}',
        exportDone: '记录文件已导出',
        fileEmpty: '文件内容为空',
        importDone: '导入完成：{n} 条记录'
      },
      ai: {
        title: 'AI 模型配置',
        desc: '用于「AI 导入」（文档 / 图片 → 题目）。Key 仅保存在本机配置文件，前端只显示脱敏后的 Key；支持 DeepSeek / 通义 / Kimi / OpenAI / 本地 Ollama 等 OpenAI 兼容端点。',
        roleTip:
          '三者角色：① 文本模型 = 题目整理与思考的主力；② 多模态模型（识图）= 含图文档必需——文字版 Word/PDF 的直传视觉、扫描件的直读都靠它，有图文档务必配置；③ MinerU（可选）= 仅"扫描件/纯图片且含图形图表"需提取版面与题图时使用。纯文字 txt/md 只需文本模型。',
        guideEntry: '第一次配置、不知道 Key 去哪弄？',
        guideLink: '点这里看「如何获取 API Key」分步教程 →',
        presetLabel: '选择服务商（自动填充配置，可手动修改）',
        presetPh: '选择服务商…',
        presetTip: '选择后自动填入 Base URL 与模型名，只需再填 API Key；也可不选，全部手动填写',
        baseUrl: 'Base URL',
        apiKey: 'API Key',
        keyKeep: '留空 = 保留原有 Key',
        keyCurrent: '（当前：{v}）',
        modelText: '模型（文本整理）',
        modelVision: '多模态模型（图片/扫描件，可留空 = 同文本模型）',
        mineru: 'MinerU 解析 API Key（可选）',
        mineruTip: '用于 pdf/图片/docx 的结构化解析（版面/OCR/公式/表格→增强文本），AI 整理仍用上方模型；留空 = 使用本地解析路径。申请：',
        test: '测试连接',
        testing: '测试中…',
        save: '保存配置',
        saving: '保存中…',
        testTip: '测试连接会先保存当前表单内容',
        thinkingTip: '「思考模式」在 AI 导入的「处理模式」中按需选择：智能推荐与精细默认开启（更稳更准），最快模式关闭（更快）',
        guideTitle: '如何获取 API Key',
        guideWhat:
          '配置只需填三样：<b>Base URL</b> 与 <b>模型名</b> 选择服务商后会自动填入，你只需拿到并填好 <b>API Key</b>。Key 只在创建页完整显示一次，且只保存在你的本机。',
        close: '关闭',
        open: '打开 {label} 创建页',
        openGuideOk: '已用系统浏览器打开 {label}，请到浏览器中查看',
        openGuideFail: '无法打开链接',
        configSaved: '配置已保存'
      },
      author: {
        title: '作者信息',
        desc: '导出题库文件时自动带入的默认作者展示名（本地记忆）。题库广场上线后，登录账号身份会取代这里的名字；未填写时导出弹窗可手动输入。',
        name: '默认作者名',
        namePh: '例如：小明老师',
        save: '保存',
        saved: '默认作者名已保存',
        cleared: '已清除默认作者名',
        storageFail: '保存失败（浏览器存储不可用）'
      },
      about: {
        title: '关于',
        appDesc: '拾题 · 自建题库刷题应用（离线优先）。数据存放于本机数据目录，核心功能不依赖登录和网络。',
        currentVer: '当前版本',
        newVer: '新版本 v{v} 可更新',
        check: '检查更新',
        checking: '检查中…',
        download: '下载并安装',
        downloading: '下载中… {p}%',
        installing: '即将安装并自动重启应用，请稍候…',
        webNoUpdate: '网页版不提供自动更新，请从官网下载桌面版。',
        latestVer: '已是最新版本',
        downloadedAsk: '新版本 v{v} 已下载完成。\n\n安装过程中应用会自动关闭并重新启动，你的题库与记录不会丢失。是否立即安装？',
        installTitle: '安装更新'
      }
    },
    'en-US': {
      pageTitle: 'Settings',
      pageDesc: 'Backup & migration of your data (new device / reinstall)',
      backup: {
        title: 'Full Backup',
        descHtml:
          'Download a backup of <b>everything</b> on this machine: banks & questions, practice records and review progress (consistent DB snapshot), images and AI config (includes your API keys — treat it as your key). Backup is how you move to a new computer, recover from reinstalls or accidents.',
        btn: 'Backup & download',
        busy: 'Packing…',
        restore: 'Restore from backup…',
        restoreBusy: 'Working…',
        tip: 'Restore = pick a backup file; the app restarts itself and restores automatically — no manual steps',
        failEmpty: 'Backup failed: the server returned nothing, please retry',
        savedPath: 'Backup saved to: {path} (includes your AI keys — keep it safe)',
        savedDownloaded: 'Backup downloaded — keep it safe (includes your AI keys)',
        webNoRestore: 'One-click restore is only available in the desktop app',
        restoreAsk: 'The backup file passed validation.\n\nRestoring will replace ALL current data (banks, practice records, images and AI config) with the backup contents, then the app will restart itself to finish. Please keep the app window open. Continue?',
        restoreTitle: 'Restore from backup',
        restoring: 'Restoring data — the app will restart itself, please wait…',
        restoreStartFail: 'Failed to start the restore: {err}',
        unknownError: 'Unknown error',
        fileFail: 'Failed to process the backup file'
      },
      appearance: {
        title: 'Appearance & Language',
        desc1: 'Theme applies instantly and is saved locally. Dark mode is a low-blue-light theme for long sessions. Currently:',
        systemNote: '(Follows system; updates when the system changes)',
        themeSystem: 'System',
        themeLight: 'Light',
        themeDark: 'Dark',
        langLead: 'Language: ',
        langSystem: 'System',
        langSystemNow: 'Follows system (currently {v})',
        langZh: '中文',
        langEn: 'English',
        langSwitched: 'Language switched to English',
        langFollowSystem: 'Now following the system language'
      },
      records: {
        title: 'Practice Records',
        desc: 'Practice records are stored locally. After exporting, import the matching bank file first on the other device, then import the records file. Records whose bank file is missing are skipped with a notice — never silently dropped.',
        export: 'Export records file',
        exporting: 'Exporting…',
        import: 'Import records file',
        importing: 'Importing…',
        ok: 'Imported {n} records',
        missing: '{n} records were skipped because their bank file (packageKey + version) was not found. Import the matching bank file first.',
        missingQ: '{n} questions do not exist in the current bank: ',
        exportPath: 'Records file saved to: {path}',
        exportDone: 'Records file exported',
        fileEmpty: 'The file is empty',
        importDone: 'Import finished: {n} records'
      },
      ai: {
        title: 'AI Model Setup',
        desc: 'Used by AI Import (documents / images → questions). Keys are stored only in a local config file; the UI shows masked keys. Works with any OpenAI-compatible endpoint (OpenAI, DeepSeek, Anthropic, Gemini, Groq, Mistral, Ollama…).',
        roleTip:
          'Three roles: ① text model = the main engine for organizing questions; ② vision (multimodal) model = required for image-bearing documents — direct vision for Word/PDF text and reading scans rely on it; ③ MinerU (optional) = only needed for scans / pure images containing figures & charts. Plain txt/md only needs a text model.',
        guideEntry: 'New here and don\'t know where to get a key?',
        guideLink: 'See the step-by-step “How to get an API key” guide →',
        presetLabel: 'Provider preset (auto-fills config; you can still edit)',
        presetPh: 'Select a provider…',
        presetTip: 'Selecting a provider fills Base URL and model names — you only need to add the API key. Or leave it blank and fill everything manually.',
        baseUrl: 'Base URL',
        apiKey: 'API Key',
        keyKeep: 'Leave empty to keep the existing key',
        keyCurrent: ' (current: {v})',
        modelText: 'Model (text)',
        modelVision: 'Vision model (images / scans; leave empty = same as text model)',
        mineru: 'MinerU API Key (optional)',
        mineruTip: 'Used for structured parsing of pdf/images/docx (layout/OCR/formulas/tables → richer text); question organizing still uses the models above. Leave empty for the local parsing path. Apply at: ',
        test: 'Test connection',
        testing: 'Testing…',
        save: 'Save config',
        saving: 'Saving…',
        testTip: 'Testing saves the current form first',
        thinkingTip: '“Thinking mode” is chosen per job in the AI import dialog: Smart / Careful default to on (more stable), Fastest turns it off (faster).',
        guideTitle: 'How to get an API key',
        guideWhat:
          'You only need three things: <b>Base URL</b> and <b>model name</b> are auto-filled when you pick a provider — you just need an <b>API key</b>. Keys are shown in full only once on the provider page, and are stored only on your machine.',
        close: 'Close',
        open: 'Open the {label} key page',
        openGuideOk: 'Opened {label} in your system browser — check the browser window',
        openGuideFail: 'Could not open the link',
        configSaved: 'Config saved'
      },
      author: {
        title: 'Author Info',
        desc: 'Default author display name written into exported bank files (saved locally). Once the plaza account system is live, your account name will take over. Leave empty to type it manually in the export dialog.',
        name: 'Default author name',
        namePh: 'e.g. Ms. Zhang',
        save: 'Save',
        saved: 'Default author name saved',
        cleared: 'Default author name cleared',
        storageFail: 'Save failed (browser storage unavailable)'
      },
      about: {
        title: 'About',
        appDesc: 'PickQ · self-hosted question bank app (offline-first). Data lives in a local folder; core features need no login or network.',
        currentVer: 'Current version',
        newVer: 'v{v} is available',
        check: 'Check for updates',
        checking: 'Checking…',
        download: 'Download & install',
        downloading: 'Downloading… {p}%',
        installing: 'Installing — the app will restart itself, please wait…',
        webNoUpdate: 'The web version has no auto-update; download the desktop app from the website.',
        latestVer: 'You are up to date',
        downloadedAsk: 'Version v{v} has been downloaded.\n\nDuring installation the app will close and restart itself — your banks and records are safe. Install now?',
        installTitle: 'Install update'
      }
    }
  }
})

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
    if (!r.info) ElMessage.success(t('about.latestVer'))
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
      t('about.downloadedAsk', { v: info.version }),
      t('about.installTitle'),
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
      ElMessage.error(t('backup.failEmpty'))
      return
    }
    const res = await saveBlob(blob, backupFilename())
    if (res && !res.saved) return //用户取消另存为
    ElMessage.success(
      res?.path
        ? t('backup.savedPath', { path: res.path })
        : t('backup.savedDownloaded')
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
    ElMessage.warning(t('backup.webNoRestore'))
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
        t('backup.restoreAsk'),
        t('backup.restoreTitle'),
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
    ElMessage.info(t('backup.restoring'))
    try {
      await invoke('restart_with_restore', { dataDir })
    } catch (e) {
      ElMessage.error(t('backup.restoreStartFail', { err: String(e?.message || e || t('backup.unknownError')) }))
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || t('backup.fileFail'))
  } finally {
    restoring.value = false
  }
}

/* ---------- 外观(双主题,规范 v2.1 §4.10) ---------- */
const themePref = ref(getTheme())
// 随语言与主题响应式（useI18n 的 t 随 locale 变化触发重算）
const effectiveLabel = computed(() =>
  currentTheme() === 'dark' ? t('appearance.themeDark') : t('appearance.themeLight')
)

function onThemeChange(v) {
  setTheme(v)
}

/* ---------- 界面语言（跟随系统 / 中文 / English） ---------- */
const langPref = ref(getLangPref())

function onLangChange(v) {
  setLang(v)
  langPref.value = getLangPref()
  ElMessage.success(v === 'system' ? t('appearance.langFollowSystem') : t('appearance.langSwitched'))
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
    ElMessage.success(name ? t('author.saved') : t('author.cleared'))
  } catch (e) {
    ElMessage.error(t('author.storageFail'))
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
  { name: 'openai', label: 'OpenAI', desc: 'GPT 系列', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', visionModel: 'gpt-4o' },
  { name: 'anthropic', label: 'Anthropic Claude', desc: 'Claude 系列·识图强', baseUrl: 'https://api.anthropic.com/v1', model: 'claude-3-5-sonnet-20241022', visionModel: 'claude-3-5-sonnet-20241022' },
  { name: 'gemini', label: 'Google Gemini', desc: '多模态', baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai/', model: 'gemini-2.5-flash', visionModel: 'gemini-2.5-flash' },
  { name: 'qwen', label: '通义千问（阿里云百炼）', desc: '识图较强', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-max', visionModel: 'qwen-vl-max' },
  { name: 'kimi', label: 'Kimi（月之暗面）', desc: '长文本', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k', visionModel: 'moonshot-v1-8k-vision-preview' },
  { name: 'groq', label: 'Groq', desc: 'Llama 极速推理', baseUrl: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile', visionModel: 'llama-3.2-90b-vision-preview' },
  { name: 'mistral', label: 'Mistral AI', desc: '欧洲开源模型', baseUrl: 'https://api.mistral.ai/v1', model: 'mistral-large-latest', visionModel: 'pixtral-large-latest' },
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
  anthropic: {
    label: 'Anthropic Claude',
    url: 'https://console.anthropic.com/settings/keys',
    steps: [
      '注册 / 登录 Anthropic Console（console.anthropic.com）',
      '进入「API Keys」→ Create Key',
      '复制生成的密钥（sk-ant- 开头）并妥善保存（只显示一次）',
      '回到本页填入「API Key」并保存配置；新账号需先充值'
    ]
  },
  gemini: {
    label: 'Google Gemini',
    url: 'https://aistudio.google.com/apikey',
    steps: [
      '用 Google 账号登录 AI Studio（aistudio.google.com）',
      '左侧「Get API key」→ Create API key（新账号有免费额度）',
      '复制生成的密钥（AIza 开头）并保存',
      '回到本页填入「API Key」并保存配置'
    ]
  },
  groq: {
    label: 'Groq',
    url: 'https://console.groq.com/keys',
    steps: [
      '注册 / 登录 Groq Console（console.groq.com，可用 Google 账号）',
      '进入「API Keys」→ Create API Key',
      '复制生成的密钥（gsk_ 开头）并保存（只显示一次）',
      '回到本页填入「API Key」并保存配置；Groq 目前有免费额度'
    ]
  },
  mistral: {
    label: 'Mistral AI',
    url: 'https://console.mistral.ai/api-keys/',
    steps: [
      '注册 / 登录 Mistral Console（console.mistral.ai）',
      '进入「API Keys」→ Create new key',
      '复制生成的密钥并保存（只显示一次）',
      '回到本页填入「API Key」并保存配置'
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
    ElMessage.success(t('ai.openGuideOk', { label: guideFor.value.label }))
  } else {
    ElMessage.warning(t('ai.openGuideFail'))
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
    ElMessage.success(t('ai.configSaved'))
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
    ElMessage.success(res?.path ? t('records.exportPath', { path: res.path }) : t('records.exportDone'))
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
      ElMessage.warning(t('records.fileEmpty'))
      return
    }
    const res = await importStudyRecords(text)
    importResult.value = res
    ElMessage.success(t('records.importDone', { n: res.imported }))
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
.lang-desc {
  margin-top: 16px;
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
