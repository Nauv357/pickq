<template>
  <div class="page notes-page">
    <PageHeader :title="t('title')" :desc="t('desc')">
      <template #meta>
        <span>{{ t('totalN', { n: total }) }}</span>
        <span v-if="bankFilter" class="note-scope">· {{ t('inBank', { name: bankFilter.name }) }}</span>
      </template>
      <template #actions>
        <!-- 阅读字号：正文太小是真实反馈，这里给最快的调节入口（与设置里那一处同一个真相） -->
        <div class="size-box" :title="t('fontTip')">
          <span class="text-muted size-label">{{ t('font') }}</span>
          <button class="size-btn" :disabled="!canShrinkReading" @click="stepReadingScale(-1)">A−</button>
          <span class="size-cur">{{ currentScale.label }}</span>
          <button class="size-btn" :disabled="!canGrowReading" @click="stepReadingScale(1)">A+</button>
        </div>
      </template>
    </PageHeader>

    <!-- 写一条：想到什么先写下来；挂不挂某个题库都行 -->
    <section class="note-new">
      <NoteComposer
        v-model="draft"
        :bank-id="quickBankId || bankFilter?.id || null"
        :placeholder="t('quickPh')"
        :saving="saving"
        @save="createQuick"
      >
        <template #footer>
          <div class="note-new-foot">
            <label class="note-hang">
              <span class="text-muted">{{ t('hangOn') }}</span>
              <el-select v-model="quickBankId" size="small" clearable filterable :placeholder="t('nothing')" class="note-bank-select">
                <el-option v-for="b in banks" :key="b.id" :label="b.name" :value="b.id" />
              </el-select>
            </label>
            <span class="text-muted note-hint">{{ t('localOnly') }}</span>
            <button class="btn btn-primary btn-sm" :disabled="saving || !draft.trim()" @click="createQuick">
              {{ saving ? t('saving') : t('save') }}
            </button>
          </div>
        </template>
      </NoteComposer>
      <p v-if="error" class="note-error">{{ error }}</p>
    </section>

    <!-- 找与筛：搜索 + 三段筛选（都是服务端口径，不是本地过滤） -->
    <div class="note-bar">
      <div class="note-search">
        <TikuIcon name="search" :size="14" />
        <input v-model="keyword" class="note-search-input" :placeholder="t('searchPh')" @keyup.enter="applySearch" />
        <button v-if="keyword" class="note-search-clear" :title="t('clearSearch')" @click="clearSearch">✕</button>
      </div>
      <div class="note-tabs">
        <button class="note-tab" :class="{ active: !unlinkedOnly }" @click="setFilter('all')">{{ t('all') }}</button>
        <button class="note-tab" :class="{ active: unlinkedOnly }" @click="setFilter('unlinked')">{{ t('unlinked') }}</button>
        <button v-if="bankFilter" class="note-tab" :class="{ active: true }" :title="t('seeAll')" @click="clearBankFilter">
          {{ bankFilter.name }} ✕
        </button>
      </div>
    </div>

    <div v-if="loading" class="note-loading">
      <div class="card tiku-skeleton">
        <div class="sk-line" style="width: 30%"></div>
        <div class="sk-line" style="width: 80%"></div>
      </div>
    </div>

    <!-- 失败要说清原因并给出路（不能静默落成"空"） -->
    <div v-else-if="loadError" class="note-failed">
      <TikuIcon name="info" :size="16" />
      <span>{{ loadError }}</span>
      <button class="btn btn-secondary btn-sm" @click="load">{{ t('retry') }}</button>
    </div>

    <EmptyState
      v-else-if="!notes.length"
      icon="edit"
      :title="keyword ? t('noHitTitle') : t('emptyTitle')"
      :desc="keyword ? t('noHitDesc', { kw: keyword }) : t('emptyDesc')"
    >
      <button v-if="keyword" class="btn btn-secondary" @click="clearSearch">{{ t('clearSearch') }}</button>
    </EmptyState>

    <!-- 按时间分组：今天 / 昨天 / 最近 7 天 / 最近 30 天 / 更早（小标题吸顶，长列表不迷路） -->
    <div v-else class="note-groups">
      <section v-for="g in groups" :key="g.key" class="note-group">
        <h2 class="note-group-head">
          <span class="note-group-name">{{ t(g.labelKey) }}</span>
          <span class="text-muted note-group-count">{{ t('groupN', { n: g.notes.length }) }}</span>
        </h2>

        <article
          v-for="n in g.notes"
          :key="n.id"
          class="note-card"
          :class="{ marked: !!n.color }"
          :style="n.color ? { '--rail': `var(--hl-${n.color})` } : null"
        >
          <header class="note-head">
            <span class="note-tag" :class="n.source">{{ n.source === 'ai' ? t('fromAi') : t('mine') }}</span>
            <span class="note-time" :title="formatTime(n.updatedAt)">{{ noteTimeLabel(n.updatedAt) }}</span>
            <span class="note-grow"></span>
            <button class="note-act" @click="startEdit(n)">{{ t('edit') }}</button>
            <button class="note-act" :class="{ on: colorOpen === n.id }" @click="toggleColorPicker(n)">{{ t('color') }}</button>
            <button class="note-act" @click="openLinkDialog(n)">{{ t('link') }}</button>
            <button class="note-act danger" @click="remove(n)">{{ t('delete') }}</button>
          </header>

          <!-- 标色：给整条笔记一个类别色（黄=要背 / 绿=懂了 / 蓝=待查 / 粉=易错，怎么用你定） -->
          <div v-if="colorOpen === n.id" class="note-colors">
            <button
              v-for="c in MARK_COLORS"
              :key="c.key"
              class="note-color-dot"
              :class="[`dot-${c.key}`, { on: n.color === c.key }]"
              :title="isEn ? c.labelEn : c.label"
              @click="pickColor(n, c.key)"
            ></button>
            <button class="note-color-none" :class="{ on: !n.color }" @click="pickColor(n, null)">{{ t('noColor') }}</button>
          </div>

          <div v-if="editingId !== n.id">
            <div class="note-text" :class="{ clamped: isLong(n) && !expanded[n.id] }" v-html="richHtml(n.content, n)"></div>
            <button v-if="isLong(n)" class="note-more" @click="toggleExpand(n)">
              {{ expanded[n.id] ? t('collapse') : t('expand') }}
            </button>
          </div>
          <div v-else class="note-edit">
            <NoteComposer
              v-model="draft"
              compact
              cancelable
              :bank-id="noteBankId(n)"
              :saving="saving"
              :placeholder="t('quickPh')"
              @save="saveEdit(n)"
              @cancel="editingId = null"
            >
              <template #footer>
                <div class="note-edit-btns">
                  <button class="btn btn-ghost btn-sm" @click="editingId = null">{{ t('cancel') }}</button>
                  <button class="btn btn-primary btn-sm" :disabled="saving || !draft.trim()" @click="saveEdit(n)">
                    {{ saving ? t('saving') : t('save') }}
                  </button>
                </div>
              </template>
            </NoteComposer>
          </div>

          <!-- 关联：题目可以就地展开看（不用跳走），题库还是跳过去 -->
          <div v-if="n.links?.length" class="note-links">
            <span v-for="l in n.links" :key="l.type + l.targetId" class="note-link">
              <button class="note-link-main" :class="{ on: isOpen(n, l) }" @click="onLinkClick(n, l)">
                <TikuIcon :name="l.type === 'question' ? 'file' : 'book'" :size="12" />
                {{ shortLabel(l) }}
                <TikuIcon v-if="l.type === 'question'" :name="isOpen(n, l) ? 'chevron-up' : 'chevron-down'" :size="11" />
              </button>
              <button class="note-link-x" :title="t('unlink')" @click="unlink(n, l)">✕</button>
            </span>
          </div>

          <!-- 题目的就地预览：题干 + 选项（标出正确答案）+ 答案/解析，「去题库」再进完整页面 -->
          <NoteQuestionPreview
            v-for="l in openQuestionLinks(n)"
            :key="`pv-${l.targetId}`"
            :link="l"
            :note-id="n.id"
            @go="openLink(l)"
            @close="togglePreview(n, l)"
          />
        </article>
      </section>
    </div>

    <Pager :page="page" :size="size" :total="total" @update:page="onPage" />

    <!-- 关联到题库 / 某道题（一条笔记可以挂多处，这里一次加一条） -->
    <el-dialog v-model="linkVisible" :title="t('linkTitle')" width="min(92vw, 460px)" align-center>
      <div class="note-link-form">
        <label class="note-field">
          <span>{{ t('fieldBank') }}</span>
          <el-select v-model="linkBankId" filterable size="small" :placeholder="t('pickBank')" @change="onLinkBankChange">
            <el-option v-for="b in banks" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </label>
        <label class="note-field">
          <span>{{ t('fieldQuestion') }}</span>
          <el-select
            v-model="linkQuestionId"
            filterable
            clearable
            size="small"
            :disabled="!linkBankId"
            :loading="linkQuestionsLoading"
            :placeholder="t('pickQuestion')"
          >
            <el-option v-for="q in linkQuestions" :key="q.questionId" :label="q.label" :value="q.questionId" />
          </el-select>
        </label>
        <p class="text-muted note-link-tip">{{ t('linkTip') }}</p>
      </div>
      <template #footer>
        <button class="btn btn-secondary btn-sm" @click="linkVisible = false">{{ t('cancel') }}</button>
        <button class="btn btn-primary btn-sm" :disabled="saving || !linkTargetReady" @click="confirmLink">{{ t('linkOk') }}</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 我的笔记（顶层页）。
 *
 * 设计（2026-09-16 用户反馈："就是单纯的摆放着笔记，视觉效果上就是一堆文字，看着杂乱无章"）：
 * - **按时间分组**（今天 / 昨天 / 最近 7 天 / 最近 30 天 / 更早），小标题吸顶，长列表不迷路；
 * - **卡片有层级**：来源与时间在标题行、正文有阅读宽度上限（不再一行拉满 1100px）、
 *   长笔记默认折叠到 6 行、可标一个类别色（左侧色条）；
 * - **关联的题目就地展开**（题干/选项/答案/解析），不用跳走再回来；题库仍是跳转；
 * - 正文支持**轻量标注**（高亮/加粗/下划线，见 NoteComposer 与 utils/noteMarkup.js）；
 * - 顶部可调**阅读字号**（与设置里同一处实现）。
 *
 * 边界（docs/features.md §4.9）：解析是题库的（随包导出），笔记是我的（只存本机）。
 * 笔记不隶属于任何题库/题目：挂在哪里由关联决定，0..N 个题库/题目，未归类也合法。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { addNoteLink, createNote, deleteNote, listNotes, removeNoteLink, setNoteColor, updateNote } from '../api/notes'
import { getBank, getBankQuestions, getBanks } from '../api/banks'
import { formatDate, noteTimeLabel, timeGroupKey, TIME_GROUPS } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import { MARK_COLORS } from '../utils/noteMarkup'
import { READING_SCALES, canGrowReading, canShrinkReading, readingScale, stepReadingScale } from '../utils/reading'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import Pager from '../components/Pager.vue'
import TikuIcon from '../components/TikuIcon.vue'
import NoteComposer from '../components/NoteComposer.vue'
import NoteQuestionPreview from '../components/NoteQuestionPreview.vue'
import { useConfirm } from '../composables/useConfirm'

const { t, locale } = useI18n({
  messages: {
    'zh-CN': {
      title: '我的笔记',
      desc: '做题时随手记的想法、AI 讲解里值得留下的部分。可以挂在题库或某道题上，也可以谁都不挂；只存在本机，不随题库文件分享给别人',
      totalN: '共 {n} 条',
      inBank: '只看《{name}》',
      seeAll: '看全部笔记',
      font: '字号',
      fontTip: '正文大小（题目、解析、笔记一起变）',
      quickPh: '写一条（Enter 保存，Shift+Enter 换行；选中文字可以用颜色标记）',
      hangOn: '挂到',
      nothing: '不挂（未归类）',
      localOnly: '只存本机',
      all: '全部',
      unlinked: '未归类',
      searchPh: '搜笔记内容…',
      clearSearch: '清空搜索',
      groupN: '{n} 条',
      g: { today: '今天', yesterday: '昨天', week: '最近 7 天', month: '最近 30 天', older: '更早' },
      save: '保存',
      saving: '保存中…',
      cancel: '取消',
      edit: '改',
      delete: '删',
      color: '标色',
      noColor: '不标色',
      link: '关联…',
      mine: '我写的',
      fromAi: 'AI 讲解',
      expand: '展开全文',
      collapse: '收起',
      unlink: '取消这条关联（笔记留着）',
      qLabel: '第 {n} 题',
      goBank: '去题库',
      loading: '正在取题…',
      previewFail: '这道题取不到了（可能已被删除）',
      material: '材料',
      answer: '正确答案',
      reference: '参考答案',
      analysis: '解析',
      linkTitle: '关联到',
      fieldBank: '题库',
      fieldQuestion: '题目（可只挂题库）',
      pickBank: '选题库',
      pickQuestion: '选题目',
      linkTip: '一条笔记可以挂多个地方：确定后再点「关联…」继续加。',
      linkOk: '关联',
      emptyTitle: '还没有笔记',
      emptyDesc: '上面写一条试试；做题与回顾时每题下面也有「记一笔」。',
      noHitTitle: '没找到相关笔记',
      noHitDesc: '没有包含「{kw}」的笔记，换个词试试。',
      saved: '已记下',
      removed: '已删除',
      linked: '已关联',
      unlinkedOk: '已取消关联',
      colorDone: '已标色',
      delAsk: '删除这条笔记？',
      delTitle: '删除笔记',
      loadFail: '笔记加载失败，请检查后端是否还在运行',
      retry: '重试',
      fail: '操作失败'
    },
    'en-US': {
      title: 'My notes',
      desc: 'Thoughts you jot down while practising, plus the useful bits of AI explanations. Attach them to banks or single questions, or to nothing at all — stored on this machine only, never shared inside bank files',
      totalN: '{n} notes',
      inBank: 'In “{name}” only',
      seeAll: 'See all notes',
      font: 'Size',
      fontTip: 'Content size (questions, analysis and notes together)',
      quickPh: 'Write a note (Enter to save, Shift+Enter for newline; select text to mark it)',
      hangOn: 'Attach to',
      nothing: 'Nothing (unfiled)',
      localOnly: 'Local only',
      all: 'All',
      unlinked: 'Unfiled',
      searchPh: 'Search notes…',
      clearSearch: 'Clear search',
      groupN: '{n}',
      g: { today: 'Today', yesterday: 'Yesterday', week: 'Last 7 days', month: 'Last 30 days', older: 'Earlier' },
      save: 'Save',
      saving: 'Saving…',
      cancel: 'Cancel',
      edit: 'Edit',
      delete: 'Delete',
      color: 'Color',
      noColor: 'None',
      link: 'Attach…',
      mine: 'Mine',
      fromAi: 'AI explanation',
      expand: 'Show all',
      collapse: 'Collapse',
      unlink: 'Remove this attachment (the note stays)',
      qLabel: 'Q{n}',
      goBank: 'Open in bank',
      loading: 'Loading question…',
      previewFail: 'This question is gone (it may have been deleted)',
      material: 'Material',
      answer: 'Answer',
      reference: 'Reference answer',
      analysis: 'Analysis',
      linkTitle: 'Attach to',
      fieldBank: 'Bank',
      fieldQuestion: 'Question (bank alone is fine)',
      pickBank: 'Pick a bank',
      pickQuestion: 'Pick a question',
      linkTip: 'One note can be attached in several places: confirm, then use “Attach…” again.',
      linkOk: 'Attach',
      emptyTitle: 'No notes yet',
      emptyDesc: 'Write one above; practice and review also offer “Add a note” under each question.',
      noHitTitle: 'No matching notes',
      noHitDesc: 'No note contains “{kw}”. Try another word.',
      saved: 'Note saved',
      removed: 'Note deleted',
      linked: 'Attached',
      unlinkedOk: 'Attachment removed',
      colorDone: 'Color updated',
      delAsk: 'Delete this note?',
      delTitle: 'Delete note',
      loadFail: 'Could not load notes — check that the backend is still running',
      retry: 'Retry',
      fail: 'Action failed'
    }
  }
})

const route = useRoute()
const router = useRouter()
const { confirmDanger } = useConfirm(t)

const notes = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(true)
const saving = ref(false)
const draft = ref('')
const editingId = ref(null)
const error = ref('')
/** 加载失败：页面要显示原因 + 重试（不能与"还没有笔记"混为一谈） */
const loadError = ref('')

const keyword = ref('')
const banks = ref([])
const quickBankId = ref(null)
const bankFilter = ref(null)
const unlinkedOnly = ref(false)

const expanded = reactive({})
const colorOpen = ref(null)
/** 就地展开的题目预览：key = `${noteId}:${questionId}` → true（展开的才渲染组件、才发请求） */
const openPreviews = reactive({})

const linkVisible = ref(false)
const linkNote = ref(null)
const linkBankId = ref(null)
const linkQuestionId = ref(null)
const linkQuestions = ref([])
const linkQuestionsLoading = ref(false)

const linkTargetReady = computed(() => Number(linkBankId.value) > 0 || Number(linkQuestionId.value) > 0)
const isEn = computed(() => String(locale.value).startsWith('en'))
const currentScale = computed(() => READING_SCALES.find((s) => s.key === readingScale.value) || READING_SCALES[1])

/** 时间分组（同一处口径见 utils/format.js；空组不渲染） */
const groups = computed(() => {
  const buckets = new Map(TIME_GROUPS.map((k) => [k, []]))
  for (const n of notes.value) {
    buckets.get(timeGroupKey(n.updatedAt)).push(n)
  }
  return TIME_GROUPS.map((key) => ({ key, labelKey: `g.${key}`, notes: buckets.get(key) })).filter((g) => g.notes.length)
})

const formatTime = (iso) => (iso ? formatDate(iso) : '')

function noteBankId(n) {
  const links = n?.links || []
  const q = links.find((l) => l.type === 'question' && l.bankId)
  if (q) return q.bankId
  const b = links.find((l) => l.type === 'bank')
  if (b) return b.targetId
  return bankFilter.value?.id || quickBankId.value || null
}

/** 渲染与编辑用同一套规则（公式/图片/标注），所见即所得 */
const richHtml = (s, bankId) => richTextToHtml(s || '', bankId)

/** 长笔记（含多行或超过 120 字）默认折叠：列表要能一眼扫过 */
const isLong = (n) => {
  const text = String(n.content || '')
  return text.length > 120 || text.split('\n').length > 4
}
const toggleExpand = (n) => {
  expanded[n.id] = !expanded[n.id]
}

const questionLinks = (n) => (n.links || []).filter((l) => l.type === 'question')
const previewKey = (n, l) => `${n.id}:${l.targetId}`
const isOpen = (n, l) => !!openPreviews[previewKey(n, l)]
const openQuestionLinks = (n) => questionLinks(n).filter((l) => isOpen(n, l))

/** 关联标签：题目就地展开预览，题库直接跳过去 */
function onLinkClick(n, l) {
  if (l.type === 'question') togglePreview(n, l)
  else openLink(l)
}

function togglePreview(n, l) {
  const key = previewKey(n, l)
  openPreviews[key] = !openPreviews[key]
  if (!openPreviews[key]) delete openPreviews[key]
}

/** 标签上只放"第 N 题"或题库名（完整名字在 title 里） */
function shortLabel(l) {
  if (l.type === 'question') return t('qLabel', { n: l.questionNumber ?? '—' })
  return l.label
}

async function loadBanks() {
  try {
    const data = await getBanks({ page: 1, size: 200 })
    banks.value = data?.records || []
  } catch (e) {
    banks.value = []
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const params = { page: page.value, size: size.value }
    if (unlinkedOnly.value) params.unlinked = true
    else if (bankFilter.value) params.bankId = bankFilter.value.id
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const data = await listNotes(params)
    notes.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch (e) {
    notes.value = []
    total.value = 0
    loadError.value = t('loadFail')
  } finally {
    loading.value = false
  }
}

/** 从题库详情进来（/notes?bankId=3）：默认"挂到"这个题库，并且只看这个题库的笔记 */
async function applyRoute() {
  const raw = route.query.bankId
  if (!raw) {
    bankFilter.value = null
    quickBankId.value = null
    return
  }
  const id = Number(raw)
  const known = banks.value.find((b) => Number(b.id) === id)
  bankFilter.value = known || { id, name: `#${id}` }
  quickBankId.value = id
  if (!known) {
    try {
      const bank = await getBank(id)
      if (bank) bankFilter.value = { id, name: bank.name }
    } catch (e) {
      /* 题库可能已删：名字保持 #id，列表照常显示 */
    }
  }
}

onMounted(async () => {
  await loadBanks()
  await applyRoute()
  await load()
})

watch(() => route.query.bankId, async () => {
  page.value = 1
  await applyRoute()
  await load()
})

function onPage(p) {
  page.value = p
  load()
}

function clearBankFilter() {
  router.push({ path: '/notes' })
}

function setFilter(kind) {
  unlinkedOnly.value = kind === 'unlinked'
  page.value = 1
  load()
}

function applySearch() {
  page.value = 1
  load()
}

function clearSearch() {
  keyword.value = ''
  page.value = 1
  load()
}

async function createQuick() {
  const content = draft.value.trim()
  if (!content || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await createNote({ bankId: quickBankId.value || null, content, source: 'user' })
    draft.value = ''
    ElMessage.success(t('saved'))
    page.value = 1
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  } finally {
    saving.value = false
  }
}

function startEdit(n) {
  editingId.value = n.id
  draft.value = n.content
  colorOpen.value = null
}

async function saveEdit(n) {
  const content = draft.value.trim()
  if (!content || saving.value) return
  saving.value = true
  try {
    await updateNote(n.id, content)
    editingId.value = null
    draft.value = ''
    ElMessage.success(t('saved'))
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  } finally {
    saving.value = false
  }
}

function toggleColorPicker(n) {
  colorOpen.value = colorOpen.value === n.id ? null : n.id
}

/** 整条笔记的类别色（与正文里的行内高亮是两件事） */
async function pickColor(n, color) {
  try {
    await setNoteColor(n.id, color)
    n.color = color || null
    colorOpen.value = null
    ElMessage.success(t('colorDone'))
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  }
}

async function remove(n) {
  const ok = await confirmDanger(t('delAsk'), t('delTitle')).catch(() => false)
  if (!ok) return
  try {
    await deleteNote(n.id)
    ElMessage.success(t('removed'))
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  }
}

async function unlink(n, link) {
  try {
    await removeNoteLink(n.id, link.type, link.targetId)
    delete previews[previewKey(n, link)]
    ElMessage.success(t('unlinkedOk'))
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  }
}

/** 跳到笔记挂的题库：题目 → 题库详情里定位到这道题；题库 → 题库详情 */
function openLink(link) {
  if (link.type === 'question') {
    router.push({ path: `/banks/${link.bankId}`, query: { q: link.targetId } })
  } else {
    router.push(`/banks/${link.targetId}`)
  }
}

function openLinkDialog(n) {
  linkNote.value = n
  colorOpen.value = null
  linkBankId.value = bankFilter.value?.id || quickBankId.value || null
  linkQuestionId.value = null
  linkQuestions.value = []
  linkVisible.value = true
  if (linkBankId.value) onLinkBankChange(linkBankId.value)
}

async function onLinkBankChange(bankId) {
  linkQuestionId.value = null
  linkQuestions.value = []
  if (!bankId) return
  linkQuestionsLoading.value = true
  try {
    const data = await getBankQuestions(bankId, { page: 1, size: 200 })
    linkQuestions.value = (data?.records || []).map((q) => ({
      questionId: q.questionId,
      label: `${t('qLabel', { n: q.questionNumber ?? '—' })} ${String(q.content || '').replace(/\s+/g, ' ').slice(0, 30)}`
    }))
  } catch (e) {
    linkQuestions.value = []
  } finally {
    linkQuestionsLoading.value = false
  }
}

async function confirmLink() {
  const note = linkNote.value
  if (!note || !linkTargetReady.value || saving.value) return
  saving.value = true
  try {
    if (Number(linkQuestionId.value) > 0) await addNoteLink(note.id, 'question', Number(linkQuestionId.value))
    else await addNoteLink(note.id, 'bank', Number(linkBankId.value))
    ElMessage.success(t('linked'))
    linkVisible.value = false
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* 阅读宽度上限：一页笔记不再一行拉满（长行是"看着杂乱"的主因之一） */
.notes-page {
  max-width: 880px;
  margin: 0 auto;
}
.note-scope {
  margin-left: 6px;
}
/* 字号调节（头部右侧，与设置里同一处实现） */
.size-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 8px;
  background: var(--bg-card);
}
.size-label {
  font-size: 12px;
}
.size-btn {
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: 12.5px;
  cursor: pointer;
  padding: 0 2px;
}
.size-btn:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}
.size-cur {
  font-size: 12px;
  color: var(--accent-text);
  min-width: 28px;
  text-align: center;
}

.note-new {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 12px 14px;
  margin-bottom: 12px;
}
.note-new-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.note-hang {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.note-bank-select {
  width: 180px;
}
.note-hint {
  flex: 1;
  font-size: 12px;
}
.note-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger);
}

/* 搜索 + 筛选 */
.note-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.note-search {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 200px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 5px 10px;
  background: var(--bg-card);
  color: var(--text-muted);
}
.note-search-input {
  flex: 1;
  border: none;
  background: none;
  outline: none;
  font-size: 13px;
  color: var(--text-primary);
  font-family: inherit;
}
.note-search-clear {
  border: none;
  background: none;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 12px;
}
.note-tabs {
  display: flex;
  align-items: center;
  gap: 8px;
}
.note-tab {
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12.5px;
  cursor: pointer;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.note-tab.active {
  border-color: var(--accent);
  color: var(--accent-text);
}

/* 时间分组 */
.note-groups {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.note-group-head {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 6px 0 8px;
  padding: 4px 2px;
  background: var(--bg-base);
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.02em;
}
.note-group-head::before {
  content: '';
  width: 3px;
  height: 12px;
  border-radius: 2px;
  background: var(--border-strong);
}
.note-group-count {
  font-size: 11.5px;
  font-weight: 400;
}
.note-group:not(:first-child) .note-group-head {
  margin-top: 14px;
}

/* 笔记卡：左侧色条 = 类别色；标题行给出处与时间，正文限制宽度 */
.note-card {
  position: relative;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 12px 14px 12px 16px;
  margin-bottom: 8px;
}
.note-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--rail, transparent);
}
.note-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.note-grow {
  flex: 1;
}
.note-tag {
  font-size: 11px;
  border: 1px solid currentColor;
  border-radius: 10px;
  padding: 0 6px;
  color: var(--text-muted);
}
.note-tag.ai {
  color: var(--accent-text);
}
.note-time {
  font-size: 12px;
  color: var(--text-muted);
}
.note-act {
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 12.5px;
  cursor: pointer;
  padding: 0 4px;
}
.note-act:hover,
.note-act.on {
  color: var(--accent-text);
}
.note-act.danger {
  color: var(--danger);
}

/* 标色 */
.note-colors {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.note-color-dot {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid var(--border-strong);
  cursor: pointer;
  padding: 0;
}
.note-color-dot.on {
  box-shadow: 0 0 0 2px var(--bg-card), 0 0 0 3px var(--accent);
}
.dot-y { background: var(--hl-y); }
.dot-g { background: var(--hl-g); }
.dot-b { background: var(--hl-b); }
.dot-p { background: var(--hl-p); }
.note-color-none {
  border: 1px solid var(--border);
  background: var(--bg-elev);
  color: var(--text-muted);
  border-radius: 999px;
  padding: 1px 9px;
  font-size: 11.5px;
  cursor: pointer;
}
.note-color-none.on {
  border-color: var(--accent);
  color: var(--accent-text);
}

/* 正文：字号跟着"阅读字号"档位走，行高宽松，长笔记折叠 */
.note-text {
  font-size: var(--content-font);
  line-height: var(--content-lh);
  white-space: pre-wrap;
  word-break: break-word;
  max-width: 68ch;
}
.note-text.clamped {
  display: -webkit-box;
  -webkit-line-clamp: 6;
  line-clamp: 6;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.note-more {
  border: none;
  background: none;
  color: var(--accent-text);
  font-size: 12px;
  cursor: pointer;
  padding: 4px 0 0;
}
.note-edit-btns {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

/* 关联标签 */
.note-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.note-link {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elev);
  overflow: hidden;
}
.note-link-main {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: 12px;
  padding: 2px 4px 2px 9px;
  cursor: pointer;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.note-link-main:hover,
.note-link-main.on {
  color: var(--accent-text);
}
.note-link-x {
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 11px;
  padding: 2px 8px 2px 4px;
  cursor: pointer;
}

/* 题目就地预览 */
.qp {
  margin-top: 10px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: 8px;
  background: var(--bg-elev);
  padding: 10px 12px;
}
.qp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.qp-title {
  font-size: 12.5px;
  color: var(--accent-text);
  font-weight: 600;
}
.qp-type {
  font-size: 11px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 0 6px;
}
.qp-loading {
  font-size: 12.5px;
}
.qp-stem {
  font-size: var(--content-font);
  line-height: var(--content-lh);
  white-space: pre-wrap;
  word-break: break-word;
  max-width: 68ch;
}
.qp-material {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-secondary);
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
}
.qp-opts {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}
.qp-opt {
  display: flex;
  gap: 8px;
  font-size: 13.5px;
  line-height: 1.7;
  padding: 3px 8px;
  border-radius: 6px;
}
.qp-opt.correct {
  background: var(--success-soft);
  color: var(--success);
}
.qp-key {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text-secondary);
}
.qp-opt.correct .qp-key {
  color: var(--success);
}
.qp-row {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-primary);
}
.qp-row b {
  color: var(--text-secondary);
  font-weight: 600;
}

.note-loading {
  padding: 6px 0;
}
.note-failed {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--bg-card);
  color: var(--text-secondary);
  font-size: 13px;
}
.note-link-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.note-field {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.note-field > span {
  width: 110px;
  color: var(--text-secondary);
}
.note-field :deep(.el-select) {
  flex: 1;
}
.note-link-tip {
  margin: 0;
  font-size: 12px;
}
</style>
