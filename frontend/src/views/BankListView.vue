<template>
  <div class="page">
    <!-- 页面头部 -->
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('pageTitle') }}</h1>
        <p class="page-desc">{{ t('pageDesc') }}</p>
      </div>
      <div class="header-actions">
        <button ref="importMainBtn" class="btn btn-primary" @click="openImportChoice">
          <TikuIcon name="upload" :size="15" />
          {{ t('act.import') }}
        </button>
        <button class="btn btn-secondary" :disabled="total < 2" @click="openMerge">
          <TikuIcon name="package" :size="15" />
          {{ t('act.merge') }}
        </button>
        <button class="btn btn-secondary" @click="openCreate">
          <TikuIcon name="plus" :size="15" />
          {{ t('act.create') }}
        </button>
      </div>
    </header>

    <!-- AI 导入弹窗 -->
    <AiImportDialog ref="aiDialog" @done="onAiDone" />

    <!-- 导入选择层：按场景二选一（入口只留一个"导入"，避免新用户猜错） -->
    <el-dialog v-model="importChoiceVisible" :title="t('choice.title')" width="min(92vw, 560px)" align-center>
      <p class="text-secondary choice-lead">{{ t('choice.lead') }}</p>
      <div class="choice-grid">
        <button class="onboard-card onboard-primary" @click="pickAiImport">
          <span class="onboard-icon"><TikuIcon name="sparkle" :size="20" /></span>
          <span class="onboard-body">
            <span class="onboard-title">
              {{ t('choice.aiTitle') }}
              <em class="onboard-tag">{{ t('choice.aiTag') }}</em>
            </span>
            <span class="onboard-desc">{{ t('choice.aiDesc') }}</span>
          </span>
          <span class="onboard-go"><TikuIcon name="chevron-right" :size="15" /></span>
        </button>
        <button class="onboard-card" @click="pickFileImport">
          <span class="onboard-icon onboard-icon-plain"><TikuIcon name="upload" :size="20" /></span>
          <span class="onboard-body">
            <span class="onboard-title">{{ t('choice.fileTitle') }}</span>
            <span class="onboard-desc">{{ t('choice.fileDesc') }}</span>
          </span>
          <span class="onboard-go"><TikuIcon name="chevron-right" :size="15" /></span>
        </button>
      </div>
    </el-dialog>

    <!-- 加载中 -->
    <div v-if="loading" class="grid">
      <div v-for="n in 6" :key="n" class="bank-card tiku-skeleton">
        <div class="sk-line" style="width: 55%"></div>
        <div class="sk-line" style="width: 90%"></div>
        <div class="sk-line" style="width: 40%"></div>
      </div>
    </div>

    <!-- 空状态（无任何题库）：首次上手的场景化入口 -->
    <div v-else-if="banks.length === 0 && !searchActive" class="empty">
      <h3>{{ t('empty.title') }}</h3>
      <p class="text-secondary empty-lead">{{ t('empty.lead') }}</p>

      <div class="onboard-grid">
        <!-- 主入口：AI 整理（最常用） -->
        <button class="onboard-card onboard-primary" @click="openAiDirect">
          <span class="onboard-icon"><TikuIcon name="sparkle" :size="20" /></span>
          <span class="onboard-body">
            <span class="onboard-title">
              {{ t('empty.aiTitle') }}
              <em class="onboard-tag">{{ t('empty.aiTag') }}</em>
            </span>
            <span class="onboard-desc">{{ t('empty.aiDesc') }}</span>
          </span>
          <span class="onboard-go"><TikuIcon name="chevron-right" :size="15" /></span>
        </button>

        <!-- 次级入口 -->
        <div class="onboard-subrow">
          <button class="onboard-sub" @click="doImport">
            <TikuIcon name="upload" :size="16" />
            <span class="onboard-sub-body">
              <span class="onboard-sub-title">{{ t('empty.fileTitle') }}</span>
              <span class="onboard-sub-desc">{{ t('empty.fileDesc') }}</span>
            </span>
          </button>
          <button class="onboard-sub" @click="openCreate">
            <TikuIcon name="plus" :size="16" />
            <span class="onboard-sub-body">
              <span class="onboard-sub-title">{{ t('empty.createTitle') }}</span>
              <span class="onboard-sub-desc">{{ t('empty.createDesc') }}</span>
            </span>
          </button>
        </div>

        <p class="onboard-foot text-muted">
          {{ t('empty.foot') }}<RouterLink to="/discover" class="onboard-link">{{ t('empty.discoverLink') }}</RouterLink>
        </p>
      </div>
    </div>

    <!-- 学习概览卡带 + 题库卡片列表 -->
    <template v-else>
      <!-- 搜索/排序工具栏 -->
      <div class="bank-toolbar">
        <el-input
          v-model="keyword"
          :placeholder="t('toolbar.searchPh')"
          clearable
          style="width: 240px"
        >
          <template #prefix><TikuIcon name="search" :size="13" /></template>
        </el-input>
        <el-select v-model="sort" style="width: 132px" :title="t('toolbar.sortTitle')">
          <el-option :label="t('toolbar.sortCreated')" value="created" />
          <el-option :label="t('toolbar.sortUpdated')" value="updated" />
          <el-option :label="t('toolbar.sortName')" value="name" />
        </el-select>
        <span v-if="searchActive" class="bank-match text-muted">{{ t('toolbar.match', { n: total }) }}</span>
        <span class="bar-grow"></span>
        <RouterLink to="/stats" class="stats-link">
          <TikuIcon name="chart" :size="13" />
          {{ t('toolbar.stats') }}
        </RouterLink>
      </div>

      <!-- 概览（有题库才展示；让少库/新用户页面也有内容与进展感；搜索/排序时收起） -->
      <div v-if="overview && !searchActive" class="home-strip">
        <div class="home-stat">
          <span class="home-num mono">{{ overview.bankCount }}</span>
          <span class="home-label">{{ t('strip.banks') }}</span>
        </div>
        <div class="home-stat">
          <span class="home-num mono">{{ overview.questionCount }}</span>
          <span class="home-label">{{ t('strip.questions') }}</span>
        </div>
        <div class="home-stat" :class="{ 'home-hot': overview.dueTotal > 0 }">
          <span class="home-num mono">{{ overview.dueTotal }}</span>
          <span class="home-label">{{ t('strip.due') }}</span>
        </div>
        <button
          class="home-stat home-click"
          :disabled="!overview.lastSession"
          :title="t('strip.lastTip')"
          @click="goLastSession"
        >
          <template v-if="overview.lastSession">
            <span class="home-num mono">{{ overview.lastSession.correctCount }}/{{ overview.lastSession.answeredCount }}</span>
            <span class="home-label">
              {{ overview.lastSession.modeLabel }} · {{ overview.lastSession.bankName }} · {{ fmtAgo(overview.lastSession.finishedAt) }}
            </span>
          </template>
          <template v-else>
            <span class="home-num mono">—</span>
            <span class="home-label">{{ t('strip.noSession') }}</span>
          </template>
        </button>
      </div>

      <!-- 首次引导：有题库但从没交卷过 -->
      <div v-if="overview && overview.bankCount > 0 && !overview.lastSession && !searchActive" class="home-guide">
        <TikuIcon name="play" :size="14" />
        <span>{{ t('guideNoRecord') }}</span>
      </div>

      <!-- 搜索无结果 -->
      <div v-if="banks.length === 0" class="bank-filter-empty">
        <TikuIcon name="search" :size="30" />
        <p class="text-secondary">{{ t('filterEmpty') }}</p>
      </div>
      <div v-else class="grid">
        <div
          v-for="bank in banks"
          :key="bank.id"
          class="bank-card"
          @click="goDetail(bank.id)"
        >
          <div class="bank-card-top">
            <h3 class="bank-name">{{ bank.name }}</h3>
            <TikuIcon name="chevron-right" :size="15" class="bank-arrow" />
          </div>
          <div class="bank-tags">
            <span v-if="bank.version" class="version-tag">v{{ bank.version }}</span>
            <span v-if="bank.authorName" class="author-tag">{{ bank.authorName }}</span>
          </div>
          <p class="bank-desc">{{ bankDescText(bank) }}</p>
          <div class="bank-meta text-muted">
            <TikuIcon name="clock" :size="13" />
            <span>{{ t('createdOn', { d: formatDate(bank.createdAt) }) }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- 分页 -->
    <div v-if="total > 0" class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="onPageChange"
      />
    </div>

    <!-- 创建题库弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="t('dialogs.createTitle')"
      width="min(92vw, 480px)"
      :close-on-click-modal="false"
      align-center
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('dialogs.name')" required>
          <el-input
            v-model="form.name"
            :placeholder="t('dialogs.namePh')"
            maxlength="100"
            show-word-limit
            @keyup.enter="submitCreate"
          />
        </el-form-item>
        <el-form-item :label="t('dialogs.desc')">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            :placeholder="t('dialogs.descPh')"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <button class="btn btn-ghost" @click="dialogVisible = false">{{ t('dialogs.cancel') }}</button>
        <button class="btn btn-primary" :disabled="submitting" @click="submitCreate">
          {{ submitting ? t('dialogs.submit') + '…' : t('dialogs.submit') }}
        </button>
      </template>
    </el-dialog>

    <!-- 合并题库弹窗 -->
    <el-dialog
      v-model="mergeVisible"
      :title="t('dialogs.mergeTitle')"
      width="min(92vw, 560px)"
      :close-on-click-modal="false"
      align-center
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('dialogs.mergePick')" required>
          <div class="merge-bank-list">
            <label
              v-for="b in allBanks"
              :key="b.id"
              class="merge-bank-item"
              :class="{ on: mergeIds.includes(b.id) }"
            >
              <input v-model="mergeIds" type="checkbox" :value="b.id" />
              <span class="merge-bank-name">{{ b.name }}</span>
              <span v-if="b.version" class="version-tag">v{{ b.version }}</span>
              <span class="merge-bank-desc text-muted">{{ bankDescText(b) }}</span>
            </label>
            <p v-if="allBanks.length === 0" class="text-muted merge-empty">{{ t('dialogs.mergeEmpty') }}</p>
          </div>
        </el-form-item>
        <el-form-item :label="t('dialogs.mergeName')" required>
          <el-input
            v-model="mergeForm.name"
            :placeholder="t('dialogs.mergeNamePh')"
            maxlength="100"
            @keyup.enter="submitMerge"
          />
        </el-form-item>
        <el-form-item :label="t('dialogs.desc')">
          <el-input
            v-model="mergeForm.description"
            type="textarea"
            :rows="2"
            :placeholder="t('dialogs.mergeDescPh')"
            maxlength="500"
          />
        </el-form-item>
        <p class="text-muted merge-note">
          {{ t('mergeNote1') }}
          {{ t('mergeNote2') }}
        </p>
      </el-form>
      <template #footer>
        <button class="btn btn-ghost" @click="mergeVisible = false">{{ t('dialogs.cancel') }}</button>
        <button class="btn btn-primary" :disabled="merging" @click="submitMerge">
          {{ merging ? t('dialogs.mergeSubmit') + '…' : t('dialogs.mergeSubmit') }}
        </button>
      </template>
    </el-dialog>

    <!-- 首启引导气泡（仅空状态首次出现一次；指向「导入」主按钮） -->
    <Teleport to="body">
      <div
        v-if="showGuideBubble"
        class="guide-bubble"
        :style="{ top: bubbleTop + 'px', right: bubbleRight + 'px' }"
        role="tooltip"
        @click="bubbleGoImport"
      >
        <span class="guide-arrow"></span>
        <p class="guide-text">
          {{ t('bubble.main') }}
          <small>{{ t('bubble.sub') }}</small>
        </p>
        <button class="guide-close" :title="t('bubble.close')" @click.stop="dismissGuideBubble">×</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onUnmounted, reactive, ref, watch, watchEffect } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createBank, getBanks, getHomeOverview, importBank, importTikuBank, mergeBanks } from '../api/banks'
import { formatDate } from '../utils/format'
import { pickFile, readArrayBuffer, readTextFile } from '../utils/files'
import TikuIcon from '../components/TikuIcon.vue'
import AiImportDialog from '../components/AiImportDialog.vue'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      pageTitle: '题库',
      pageDesc: '把学习资料变成题库（AI 整理），刷题与复习都在这里',
      act: { import: '导入', merge: '合并题库', create: '创建题库', createShort: '创建' },
      choice: {
        title: '导入',
        lead: '按你的情况选一种：',
        aiTitle: '我手上有试卷 / 学习资料',
        aiTag: '大多数人的选择',
        aiDesc: '上传 PDF、Word、照片或网页链接，AI 自动整理成题目，逐题确认后即可开刷',
        fileTitle: '别人发来了题库文件',
        fileDesc: '选择 .tiku / .json 文件（别人分享的现成题库），导入即可用'
      },
      empty: {
        title: '还没有题库',
        lead: '拾题把「学习资料」变成「能刷的题库」。按你的情况选一种开始：',
        aiTitle: '我手上有试卷 / 学习资料',
        aiTag: '大多数人从这里开始',
        aiDesc: '上传 PDF、Word、照片或网页链接，AI 自动整理成题目，确认后即可开刷',
        fileTitle: '别人发来了题库文件',
        fileDesc: '.tiku / .json 文件，导入即可用',
        createTitle: '想自己出题',
        createDesc: '先建一个空题库，逐题录入',
        foot: '想直接刷现成的？',
        discoverLink: '去「发现题库」逛逛 →'
      },
      toolbar: {
        searchPh: '搜索题库名称 / 描述',
        sortTitle: '排序方式',
        sortCreated: '最近创建',
        sortUpdated: '最近更新',
        sortName: '按名称',
        match: '匹配 {n} 个题库',
        stats: '学习统计'
      },
      strip: {
        banks: '题库',
        questions: '题目',
        due: '今日待复习',
        lastTip: '查看最近一场练习的回顾',
        noSession: '还没有完成过练习'
      },
      guideNoRecord: '还没有做题记录：点下面任一题库进入「开始做题」，刷完第一场后这里会显示你的练习概况与复习提醒',
      filterEmpty: '没有匹配的题库，换个关键词试试',
      createdOn: '创建于 {d}',
      noDesc: '暂无描述', genByAi: '由 AI 导入生成',
      newTag: 'New',
      dialogs: {
        createTitle: '创建题库',
        createDesc: '从零开始建一套自己的题库',
        name: '题库名称',
        namePh: '例如：C1 驾考科目一',
        desc: '描述（可选）',
        descPh: '这套题的主题、来源或使用说明',
        cancel: '取消',
        submit: '创建',
        mergeTitle: '合并题库',
        mergePick: '选择要合并的题库（至少 2 个；源题库会保留，合并产生新题库）',
        mergeEmpty: '暂无可合并的题库',
        mergeName: '新题库名称',
        mergeNamePh: '例如：综合总题库',
        mergeDescPh: '这套合并题库的主题或说明',
        mergeSubmit: '合并为新题库'
      },
      mergeNote1: '合并 = 把所选题库的题目与图片复制进新题库；各自的刷题记录、错题、复习进度留在原题库，不受影响。',
      mergeNote2: '新题库从零开始记录，导出题库文件时自动记录来源（sources）。',
      bubble: { main: '把你的试卷 / 资料变成题库，点「导入」开始', sub: 'PDF、Word、照片都可以，AI 自动整理成题目', close: '不再提示' },
      msgNeedBankName: '请输入题库名称',
      msgBankCreated: '题库创建成功',
      msgNeedTwoBanks: '请至少选择两个题库',
      msgNeedNewBankName: '请输入新题库名称',
      msgMergeDone: '合并完成：{n} 道题已复制进「{name}」（源题库保留，题号已从 1 重新编排）',
      msgFileEmpty: '文件内容为空',
      msgImportCreated: '导入成功',
      msgAlreadyImported: '该题库文件已导入过',
      msgVersionAdded: '已并存导入新版本',
      msgBranched: '内容已被修改，已作为新题库文件导入（分支）',
      msgImportDone: '导入完成',
      msgImportResult: '{tip}：{detail}',
      msgImportedJump: '导入成功，是否跳转到新题库「{id}」？',
      msgJumpOriginal: '是否跳转到原题库？',
      msgAlreadyImportedTitle: '已导入过'
    },
    'en-US': {
      pageTitle: 'Question Banks',
      pageDesc: 'Turn study materials into banks (AI-assisted), practice & review here',
      act: { import: 'Import', merge: 'Merge Banks', create: 'Create Bank', createShort: 'Create' },
      choice: {
        title: 'Import',
        lead: 'Choose what fits your situation:',
        aiTitle: 'I have papers / study materials',
        aiTag: "Most people start here",
        aiDesc: 'Upload PDF, Word, photos or links; AI turns them into questions — review each one, then practice',
        fileTitle: 'Someone sent me a bank file',
        fileDesc: 'Pick a .tiku / .json file (a ready-made bank), import and start'
      },
      empty: {
        title: 'No question banks yet',
        lead: 'Turn your study materials into banks you can practice. Pick a starting point:',
        aiTitle: 'I have papers / study materials',
        aiTag: 'Most people start here',
        aiDesc: 'Upload PDF, Word, photos or links; AI turns them into questions — confirm and practice',
        fileTitle: 'Someone sent me a bank file',
        fileDesc: '.tiku / .json file, import and go',
        createTitle: 'I want to build my own',
        createDesc: 'Create an empty bank, add questions one by one',
        foot: 'Want ready-made banks?',
        discoverLink: 'Browse the Discover plaza →'
      },
      toolbar: {
        searchPh: 'Search bank name / description',
        sortTitle: 'Sort',
        sortCreated: 'Recently created',
        sortUpdated: 'Recently updated',
        sortName: 'By name',
        match: '{n} banks matched',
        stats: 'Stats'
      },
      strip: {
        banks: 'Banks',
        questions: 'Questions',
        due: 'Due today',
        lastTip: 'Review your last practice session',
        noSession: 'No practice finished yet'
      },
      guideNoRecord: 'No practice records yet: open any bank below and start a session — your overview will appear here after your first round.',
      filterEmpty: 'No banks match your search. Try other keywords.',
      createdOn: 'Created {d}',
      noDesc: 'No description', genByAi: 'Generated by AI import',
      newTag: 'New',
      dialogs: {
        createTitle: 'Create Question Bank',
        createDesc: 'Build your own bank from scratch',
        name: 'Bank name',
        namePh: 'e.g. Driving Test Theory C1',
        desc: 'Description (optional)',
        descPh: 'Topic, source or usage notes',
        cancel: 'Cancel',
        submit: 'Create',
        mergeTitle: 'Merge Question Banks',
        mergePick: 'Choose banks to merge (at least 2; source banks are kept, a new bank is created)',
        mergeEmpty: 'No banks available to merge',
        mergeName: 'New bank name',
        mergeNamePh: 'e.g. Combined Master Bank',
        mergeDescPh: 'Topic or notes for the merged bank',
        mergeSubmit: 'Merge into New Bank'
      },
      mergeNote1: 'Merging copies questions and images of the selected banks into a new bank; each source bank keeps its own practice records, mistakes and review progress.',
      mergeNote2: 'The new bank starts fresh; exported bank files record their sources automatically.',
      bubble: { main: 'Turn your papers / materials into a bank — click Import to start', sub: 'PDF, Word and photos all work; AI builds the questions', close: 'Don\'t show again' },
      msgNeedBankName: 'Please enter a bank name',
      msgBankCreated: 'Bank created successfully',
      msgNeedTwoBanks: 'Please select at least two banks',
      msgNeedNewBankName: 'Please enter a name for the new bank',
      msgMergeDone: 'Merge complete: {n} questions copied into “{name}” (source banks are kept; numbering restarts from 1)',
      msgFileEmpty: 'The file is empty',
      msgImportCreated: 'Import succeeded',
      msgAlreadyImported: 'This bank file has already been imported',
      msgVersionAdded: 'Imported as a new version alongside the existing one',
      msgBranched: 'Content was modified, so it was imported as a new bank file (branch)',
      msgImportDone: 'Import complete',
      msgImportResult: '{tip}: {detail}',
      msgImportedJump: 'Import succeeded — go to the new bank “{id}”?',
      msgJumpOriginal: 'Go to the original bank?',
      msgAlreadyImportedTitle: 'Already imported'
    }
  }
})

const router = useRouter()
/** 系统自动生成描述（后端存库固定中文），按界面语言展示；用户自填描述原样显示 */
const AI_GEN_DESC = '由 AI 导入生成'
function bankDescText(b) {
  const d = b?.description
  if (!d) return t('noDesc')
  return d === AI_GEN_DESC ? t('genByAi') : d
}

const aiDialog = ref(null)

function onAiDone(jobId) {
  // 解析完成 → 进入预览页（新建题库，无 bankId）
  router.push(`/ai-import/${jobId}`)
}

const banks = ref([])
const loading = ref(true)
const importing = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)

/* ---------- 题库搜索 / 排序（A3：名称/描述关键词 + 最近创建/更新/名称） ---------- */
const keyword = ref('')
const sort = ref('created')
let kwTimer = null
const searchActive = computed(() => !!keyword.value.trim() || sort.value !== 'created')
watch(keyword, () => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(applyBankFilter, 350)
})
watch(sort, applyBankFilter)
function applyBankFilter() {
  page.value = 1
  loadBanks()
}
onUnmounted(() => clearTimeout(kwTimer))

/* ---------- 主页概览卡带（题库/题目/待复习/最近练习） ---------- */
const overview = ref(null)
async function loadOverview() {
  try {
    overview.value = await getHomeOverview()
  } catch (e) {
    overview.value = null
  }
}
function fmtAgo(iso) {
  if (!iso) return ''
  const d = new Date(String(iso).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 3600 * 1000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < 24 * 3600 * 1000) return `${Math.floor(diff / 3600000)} 小时前`
  if (diff < 7 * 24 * 3600 * 1000) return `${Math.floor(diff / (24 * 3600000))} 天前`
  return formatDate(iso)
}
function goLastSession() {
  const s = overview.value?.lastSession
  if (s?.bankId && s?.sessionId) {
    router.push({ path: `/banks/${s.bankId}/sessions`, query: { view: s.sessionId } })
  }
}

async function loadBanks() {
  loading.value = true
  try {
    const data = await getBanks({
      page: page.value,
      size: pageSize,
      keyword: keyword.value.trim() || undefined,
      sort: sort.value
    })
    banks.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (e) {
    banks.value = []
  } finally {
    loading.value = false
  }
}

function onPageChange(p) {
  page.value = p
  loadBanks()
}

function goDetail(id) {
  router.push(`/banks/${id}`)
}

/* ---------- 导入选择层（顶栏"导入"单一入口 → 按场景二选一） ---------- */
const importChoiceVisible = ref(false)

function openImportChoice() {
  dismissGuideBubble(true)
  importChoiceVisible.value = true
}

function pickAiImport() {
  importChoiceVisible.value = false
  aiDialog.value?.open()
}

function pickFileImport() {
  importChoiceVisible.value = false
  dismissGuideBubble(true)
  doImport()
}

/** 空状态主卡直接进入 AI 整理（场景已说明，无需再过选择层） */
function openAiDirect() {
  dismissGuideBubble(false)
  aiDialog.value?.open()
}

/* ---------- 首启引导气泡（空状态首次出现，指向「导入」主按钮；一次指路） ---------- */
const GUIDE_KEY = 'tiku:guide-bubble-dismissed'
const importMainBtn = ref(null)
const showGuideBubble = ref(false)
const bubbleTop = ref(0)
const bubbleRight = ref(16)
let guideShownOnce = false
let guideTimer = null

function guideDismissed() {
  try {
    return localStorage.getItem(GUIDE_KEY) === '1'
  } catch {
    return false
  }
}

function dismissGuideBubble(permanent = false) {
  showGuideBubble.value = false
  if (guideTimer) {
    clearTimeout(guideTimer)
    guideTimer = null
  }
  if (permanent) {
    try {
      localStorage.setItem(GUIDE_KEY, '1')
    } catch {
      /* 忽略 */
    }
  }
}

function bubbleGoImport() {
  dismissGuideBubble(true)
  openImportChoice()
}

// 空状态渲染完成后延迟出现；有题库 / 已关闭过则不再出现
watchEffect(() => {
  if (loading.value || banks.value.length > 0 || searchActive.value) {
    return
  }
  if (guideShownOnce || guideDismissed()) {
    return
  }
  guideShownOnce = true
  guideTimer = setTimeout(() => {
    if (!importMainBtn.value) return
    const r = importMainBtn.value.getBoundingClientRect()
    bubbleTop.value = Math.round(r.bottom + 14)
    bubbleRight.value = Math.max(16, Math.round(window.innerWidth - r.right))
    showGuideBubble.value = true
  }, 1200)
})
onUnmounted(() => {
  if (guideTimer) clearTimeout(guideTimer)
})

/* ---------- 创建题库 ---------- */
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ name: '', description: '' })

function openCreate() {
  dismissGuideBubble(false)
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

async function submitCreate() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning(t('msgNeedBankName'))
    return
  }
  submitting.value = true
  try {
    const bankId = await createBank({ name, description: form.description.trim() || null })
    ElMessage.success(t('msgBankCreated'))
    dialogVisible.value = false
    // 直接跳转详情页并自动打开“添加题目”面板（连续操作不打断）
    router.push({ path: `/banks/${bankId}`, query: { new: '1' } })
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

/* ---------- 合并题库 ---------- */
const mergeVisible = ref(false)
const merging = ref(false)
const allBanks = ref([]) // 合并选择器用：一次拉全量（个人量级）
const mergeIds = ref([])
const mergeForm = reactive({ name: '', description: '' })

async function openMerge() {
  dismissGuideBubble(false)
  mergeForm.name = ''
  mergeForm.description = ''
  mergeIds.value = []
  mergeVisible.value = true
  try {
    // 分页拉全量（上限 500，个人量级足够）
    const data = await getBanks({ page: 1, size: 500 })
    allBanks.value = data.records || []
  } catch (e) {
    allBanks.value = []
  }
}

async function submitMerge() {
  const name = mergeForm.name.trim()
  if (mergeIds.value.length < 2) {
    ElMessage.warning(t('msgNeedTwoBanks'))
    return
  }
  if (!name) {
    ElMessage.warning(t('msgNeedNewBankName'))
    return
  }
  merging.value = true
  try {
    const res = await mergeBanks({
      name,
      description: mergeForm.description.trim() || null,
      sourceBankIds: mergeIds.value
    })
    ElMessage.success(t('msgMergeDone', { n: res.questionsCopied, name: res.name }))
    mergeVisible.value = false
    router.push(`/banks/${res.bankId}`)
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    merging.value = false
  }
}

/* ---------- 导入题库文件 ---------- */
const IMPORT_TIPS = {
  CREATED: 'msgImportCreated',
  ALREADY_IMPORTED: 'msgAlreadyImported',
  VERSION_ADDED: 'msgVersionAdded',
  BRANCHED: 'msgBranched'
}

async function doImport() {
  dismissGuideBubble(true) // 用户已找到文件导入路径，不再提示
  if (importing.value) return
  let file
  try {
    file = await pickFile('.tiku,.json,application/zip,application/json')
  } catch (e) {
    return // 用户取消选择
  }
  importing.value = true
  try {
    // .tiku 容器（v2）按 zip 字节导入；.json 纯文本（v1）按原文导入
    const isTiku = /\.tiku$/i.test(file.name) || file.type === 'application/zip'
    if (isTiku) {
      const bytes = await readArrayBuffer(file)
      if (!bytes || bytes.byteLength === 0) {
        ElMessage.warning(t('msgFileEmpty'))
        return
      }
      const res = await importTikuBank(bytes)
      await handleImportResult(res)
    } else {
      const text = await readTextFile(file)
      if (!text.trim()) {
        ElMessage.warning(t('msgFileEmpty'))
        return
      }
      const res = await importBank(text)
      await handleImportResult(res)
    }
  } catch (e) {
    /* 400（文件格式错误等）由拦截器提示 */
  } finally {
    importing.value = false
  }
}

async function handleImportResult(res) {
  const tip = t(IMPORT_TIPS[res?.result] || 'msgImportDone')
  if (res?.result === 'CREATED') {
    ElMessage.success(t('msgImportResult', { tip, detail: res.message || '' }))
    await loadBanks()
    try {
      await ElMessageBox.confirm(t('msgImportedJump', { id: res.bankId }), t('msgImportDone'), {
        confirmButtonText: '去查看',
        cancelButtonText: '留在本页'
      })
      router.push(`/banks/${res.bankId}`)
    } catch (e) {
      /* 用户选择留在本页 */
    }
  } else if (res?.result === 'ALREADY_IMPORTED') {
    ElMessage.warning(t('msgImportResult', { tip, detail: res.message || '' }))
    await loadBanks()
    try {
      await ElMessageBox.confirm(t('msgJumpOriginal'), t('msgAlreadyImportedTitle'), {
        confirmButtonText: '去查看',
        cancelButtonText: '取消'
      })
      router.push(`/banks/${res.bankId}`)
    } catch (e) {
      /* 留在本页 */
    }
  } else {
    // VERSION_ADDED / BRANCHED：提示并存/分支，刷新列表即可看到新题库
    ElMessage.success(t('msgImportResult', { tip, detail: res.message || '' }))
    await loadBanks()
  }
}

loadBanks()
loadOverview()
</script>

<style scoped>
/* 学习概览卡带 */
.home-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 22px;
}
.home-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  text-align: left;
  font-family: var(--font-sans);
}
.home-stat.home-click {
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease);
}
.home-stat.home-click:hover:not(:disabled) {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.home-stat.home-click:disabled {
  cursor: default;
}
.home-num {
  font-size: 22px;
  font-weight: 700;
  color: var(--accent-text);
}
.home-stat.home-hot .home-num {
  color: var(--danger);
}
.home-label {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}
/* 首次引导（有题库但从没交卷过） */
.home-guide {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 16px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 10px;
  color: var(--accent-text);
  font-size: 13px;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 28px;
  /* 窄视口：右侧按钮组换到下一行，避免标题区被压成逐字竖排 */
  flex-wrap: wrap;
}
.page-title {
  font-size: 26px;
}
.page-desc {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
}
.header-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  flex-wrap: wrap;
  max-width: 100%;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

/* 搜索/排序工具栏 */
.bank-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}
.bank-match {
  font-size: 12px;
}
.stats-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  color: var(--accent-text);
  text-decoration: none;
  padding: 5px 10px;
  border-radius: 6px;
  transition: background var(--ease);
}
.stats-link:hover {
  background: var(--accent-soft);
}
.bank-filter-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 70px 0;
  color: var(--text-muted);
}

.bank-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 18px 20px;
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease), transform var(--ease);
}
.bank-card:hover {
  background: var(--bg-hover);
  border-color: var(--border-strong);
  transform: translateY(-1px);
}
.bank-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.bank-name {
  font-size: 16px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bank-arrow {
  color: var(--text-muted);
  flex-shrink: 0;
  transition: color var(--ease), transform var(--ease);
}
.bank-card:hover .bank-arrow {
  color: var(--accent-text);
  transform: translateX(2px);
}
.bank-tags {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.version-tag {
  font-size: 11px;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 1px 8px;
}
.author-tag {
  font-size: 11px;
  color: var(--text-secondary);
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 1px 8px;
}
.bank-desc {
  margin: 10px 0 16px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 40px;
}
.bank-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 72px 0;
  color: var(--text-muted);
  text-align: center;
}
.empty h3 {
  margin-top: 8px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}
.empty-lead {
  font-size: 13px;
}

/* ---------- 空状态 onboarding 场景卡 ---------- */
.onboard-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  max-width: 560px;
  margin-top: 14px;
  text-align: left;
}
.onboard-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 14px;
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-primary);
  font-family: var(--font-sans);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--ease), box-shadow var(--ease), transform var(--ease);
}
.onboard-card:hover {
  border-color: var(--accent);
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
  transform: translateY(-1px);
}
/* 主入口：accent 描边 + 浅色底，明确"这是正门" */
.onboard-primary {
  border-color: var(--accent);
  background: var(--accent-soft);
  border-width: 1.5px;
}
.onboard-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  flex-shrink: 0;
  border-radius: 12px;
  background: var(--accent);
  color: #fff;
}
.onboard-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}
.onboard-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.onboard-tag {
  font-style: normal;
  font-size: 11px;
  font-weight: 500;
  color: var(--accent-text);
  background: var(--accent);
  padding: 2px 8px;
  border-radius: 999px;
  color: #fff;
}
.onboard-desc {
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.6;
}
.onboard-go {
  color: var(--accent-text);
  flex-shrink: 0;
}
.onboard-subrow {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.onboard-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-primary);
  font-family: var(--font-sans);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--ease), background var(--ease);
}
.onboard-sub:hover {
  border-color: var(--accent);
  background: var(--bg-hover);
}
.onboard-sub-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.onboard-sub-title {
  font-size: 13px;
  font-weight: 600;
}
.onboard-sub-desc {
  font-size: 11.5px;
  color: var(--text-muted);
}
.onboard-foot {
  font-size: 12.5px;
  text-align: center;
}
.onboard-link {
  color: var(--accent-text);
  font-weight: 500;
}
.onboard-link:hover {
  text-decoration: underline;
}
@media (max-width: 640px) {
  .onboard-subrow {
    grid-template-columns: 1fr;
  }
}

/* ---------- 导入选择层 ---------- */
.choice-lead {
  margin: 0 0 12px;
  font-size: 13px;
}
.choice-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.choice-grid .onboard-card {
  width: 100%;
}
.onboard-icon-plain {
  background: var(--text-secondary);
}

/* ---------- 首启引导气泡 ---------- */
.guide-bubble {
  position: fixed;
  z-index: 1200;
  max-width: 300px;
  padding: 14px 34px 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--accent);
  background: var(--bg-card);
  box-shadow: 0 10px 32px rgba(0, 0, 0, 0.18);
  cursor: pointer;
  animation: bubble-in 260ms ease;
  transition: box-shadow var(--ease);
}
.guide-bubble:hover {
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.24);
}
.guide-arrow {
  position: absolute;
  top: -7px;
  right: 22px;
  width: 12px;
  height: 12px;
  background: var(--bg-card);
  border-left: 1px solid var(--accent);
  border-top: 1px solid var(--accent);
  transform: rotate(45deg);
}
.guide-text {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--text-primary);
  font-weight: 500;
}
.guide-text small {
  display: block;
  margin-top: 2px;
  font-weight: 400;
  font-size: 12px;
  color: var(--text-muted);
}
.guide-close {
  position: absolute;
  top: 6px;
  right: 8px;
  width: 22px;
  height: 22px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  border-radius: 6px;
}
.guide-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
@keyframes bubble-in {
  from {
    opacity: 0;
    transform: translateY(-6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

/* 骨架占位 */
.sk-line {
  height: 12px;
  border-radius: 6px;
  background: var(--bg-hover);
  margin-bottom: 12px;
}

/* 合并题库选择列表 */
.merge-bank-list {
  max-height: 260px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  padding: 4px;
}
.merge-bank-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background var(--ease);
}
.merge-bank-item:hover {
  background: var(--bg-hover);
}
.merge-bank-item.on {
  background: var(--accent-soft);
}
.merge-bank-item input {
  flex-shrink: 0;
}
.merge-bank-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.merge-bank-item .version-tag {
  flex-shrink: 0;
}
.merge-bank-desc {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}
.merge-empty {
  padding: 16px;
  text-align: center;
}
.merge-note {
  font-size: 12px;
  line-height: 1.8;
}
</style>
