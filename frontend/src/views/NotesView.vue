<template>
  <div class="page notes-page">
    <PageHeader :title="t('title')" :desc="t('desc')">
      <template #meta>
        <span>{{ t('totalN', { n: total }) }}</span>
        <span v-if="bankFilter" class="note-scope">· {{ t('inBank', { name: bankFilter.name }) }}</span>
      </template>
      <template #actions>
        <!-- 阅读字号：与设置里同一处实现 -->
        <div class="size-box" :title="t('fontTip')">
          <span class="text-muted size-label">{{ t('font') }}</span>
          <button class="size-btn" :disabled="!canShrinkReading" @click="stepReadingScale(-1)">A−</button>
          <span class="size-cur">{{ currentScale.label }}</span>
          <button class="size-btn" :disabled="!canGrowReading" @click="stepReadingScale(1)">A+</button>
        </div>
        <button class="btn btn-primary" @click="startCreate">
          <TikuIcon name="plus" :size="15" />
          {{ t('write') }}
        </button>
      </template>
    </PageHeader>

    <!-- 找与筛 -->
    <div class="note-bar">
      <div class="note-search">
        <TikuIcon name="search" :size="14" />
        <input v-model="keyword" class="note-search-input" :placeholder="t('searchPh')" @keyup.enter="applyFilter" />
        <button v-if="keyword" class="note-search-clear" :title="t('clearSearch')" @click="clearSearch">✕</button>
      </div>
      <div class="note-tabs">
        <button class="note-tab" :class="{ active: !sourceFilter }" @click="setSource('')">{{ t('srcAll') }}</button>
        <button class="note-tab" :class="{ active: sourceFilter === 'user' }" @click="setSource('user')">{{ t('mine') }}</button>
        <button class="note-tab" :class="{ active: sourceFilter === 'ai' }" @click="setSource('ai')">{{ t('fromAi') }}</button>
        <button class="note-tab" :class="{ active: unlinkedOnly }" @click="toggleUnlinked">{{ t('unlinked') }}</button>
        <button v-if="bankFilter" class="note-tab active" :title="t('seeAll')" @click="clearBankFilter">
          {{ bankFilter.name }} ✕
        </button>
      </div>
    </div>

    <!-- 左：列表；右：这一条（读 / 改 / 新建）。窄屏一次只显示一侧。 -->
    <div class="notes-split" :class="{ narrow: isNarrow, 'show-detail': showDetailPane }">
      <aside class="list-pane">
        <div v-if="loading" class="note-loading">
          <div class="card tiku-skeleton">
            <div class="sk-line" style="width: 40%"></div>
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
          :title="keyword || sourceFilter || unlinkedOnly ? t('noHitTitle') : t('emptyTitle')"
          :desc="keyword ? t('noHitDesc', { kw: keyword }) : t('emptyDesc')"
        >
          <button v-if="keyword" class="btn btn-secondary" @click="clearSearch">{{ t('clearSearch') }}</button>
        </EmptyState>

        <template v-else>
          <div class="note-groups">
            <section v-for="g in groups" :key="g.key" class="note-group">
              <h2 class="note-group-head">
                <span class="note-group-name">{{ t(g.labelKey) }}</span>
                <span class="text-muted note-group-count">{{ t('groupN', { n: g.notes.length }) }}</span>
              </h2>
              <!-- 整行可点 = 打开这一条（不是只有右侧小按钮能点） -->
              <button
                v-for="n in g.notes"
                :key="n.id"
                class="note-row"
                :class="{ on: n.id === selectedId }"
                @click="select(n)"
              >
                <span class="note-row-rail" :style="n.color ? { '--rail': `var(--hl-${n.color})` } : null"></span>
                <span class="note-row-main">
                  <span class="note-row-text">{{ summary(n) }}</span>
                  <span class="note-row-meta">
                    <span class="note-row-time">{{ noteTimeLabel(n.updatedAt) }}</span>
                    <span v-if="n.source === 'ai'" class="note-row-src">· {{ t('fromAiShort') }}</span>
                    <span v-if="!n.links?.length" class="note-row-src">· {{ t('unlinked') }}</span>
                    <span v-else class="note-row-where">· {{ shortLabel(n.links[0]) }}<template v-if="n.links.length > 1"> +{{ n.links.length - 1 }}</template></span>
                  </span>
                </span>
              </button>
            </section>
          </div>
          <Pager :page="page" :size="size" :total="total" @update:page="onPage" />
        </template>
      </aside>

      <section class="detail-pane">
        <!-- 窄屏：回到列表 -->
        <button v-if="isNarrow && showDetailPane" class="detail-back" @click="backToList">
          <TikuIcon name="arrow-left" :size="14" />
          {{ t('backToList') }}
        </button>

        <!-- 写一条（只在这里新建；不再有第二个常驻编辑区） -->
        <template v-if="mode === 'create'">
          <h2 class="detail-title">{{ t('write') }}</h2>
          <NoteComposer
            ref="createBox"
            v-model="newDraft"
            :bank-id="quickBankId || bankFilter?.id || null"
            :placeholder="t('quickPh')"
            :saving="saving"
            @save="doCreate"
          >
            <template #footer>
              <div class="detail-foot">
                <label class="note-hang">
                  <span class="text-muted">{{ t('hangOn') }}</span>
                  <el-select v-model="quickBankId" size="small" clearable filterable :placeholder="t('nothing')" class="note-bank-select">
                    <el-option v-for="b in banks" :key="b.id" :label="b.name" :value="b.id" />
                  </el-select>
                </label>
                <span class="text-muted note-hint">{{ t('localOnly') }}</span>
                <button class="btn btn-secondary btn-sm" @click="cancelCreate">{{ t('cancel') }}</button>
                <button class="btn btn-primary btn-sm" :disabled="saving || !newDraft.trim()" @click="doCreate">
                  {{ saving ? t('saving') : t('save') }}
                </button>
              </div>
            </template>
          </NoteComposer>
          <p v-if="error" class="note-error">{{ error }}</p>
        </template>

        <!-- 改这一条（同一个位置、唯一的编辑位） -->
        <template v-else-if="mode === 'edit' && selectedNote">
          <div class="detail-head">
            <span class="detail-when">{{ formatTime(selectedNote.updatedAt) }}</span>
          </div>
          <NoteComposer
            ref="editBox"
            v-model="editDraft"
            cancelable
            :bank-id="noteBankId(selectedNote)"
            :placeholder="t('quickPh')"
            :saving="saving"
            @save="saveEdit"
            @cancel="cancelEdit"
          >
            <template #footer>
              <div class="detail-foot">
                <span class="text-muted note-hint">{{ t('editHint') }}</span>
                <button class="btn btn-ghost btn-sm" @click="cancelEdit">{{ t('cancel') }}</button>
                <button class="btn btn-primary btn-sm" :disabled="saving || !editDraft.trim()" @click="saveEdit">
                  {{ saving ? t('saving') : t('save') }}
                </button>
              </div>
            </template>
          </NoteComposer>
          <p v-if="error" class="note-error">{{ error }}</p>
        </template>

        <!-- 读这一条 -->
        <template v-else-if="selectedNote">
          <div class="detail-head">
            <span v-if="selectedNote.source === 'ai'" class="detail-src">{{ t('fromAiLong') }}</span>
            <span class="detail-when" :title="formatTime(selectedNote.updatedAt)">{{ noteTimeLabel(selectedNote.updatedAt) }}</span>
            <span class="detail-grow"></span>
            <button class="note-act" @click="startEdit">{{ t('edit') }}</button>
            <button class="note-act" :class="{ on: colorOpen }" @click="colorOpen = !colorOpen">{{ t('color') }}</button>
            <button class="note-act" @click="openLinkDialog(selectedNote)">{{ t('link') }}</button>
            <button class="note-act danger" @click="remove(selectedNote)">{{ t('delete') }}</button>
          </div>

          <!-- 标色：整条笔记的类别色（黄=要背 / 绿=懂了 / 蓝=待查 / 粉=易错，怎么用你定） -->
          <div v-if="colorOpen" class="detail-colors">
            <button
              v-for="c in MARK_COLORS"
              :key="c.key"
              class="note-color-dot"
              :class="[`dot-${c.key}`, { on: selectedNote.color === c.key }]"
              :title="isEn ? c.labelEn : c.label"
              @click="pickColor(selectedNote, c.key)"
            ></button>
            <button class="note-color-none" :class="{ on: !selectedNote.color }" @click="pickColor(selectedNote, null)">
              {{ t('noColor') }}
            </button>
          </div>

          <div class="detail-text" v-html="richHtml(selectedNote.content, noteBankId(selectedNote))"></div>

          <div v-if="selectedNote.links?.length" class="note-links">
            <span v-for="l in selectedNote.links" :key="l.type + l.targetId" class="note-link">
              <button class="note-link-main" :class="{ on: isOpen(l) }" @click="onLinkClick(l)">
                <TikuIcon :name="l.type === 'question' ? 'file' : 'book'" :size="12" />
                {{ shortLabel(l) }}
                <TikuIcon v-if="l.type === 'question'" :name="isOpen(l) ? 'chevron-up' : 'chevron-down'" :size="11" />
              </button>
              <button class="note-link-x" :title="t('unlink')" @click="unlink(l)">✕</button>
            </span>
          </div>
          <p v-else class="text-muted detail-nolink">{{ t('noLink') }}</p>

          <!-- 题目的就地预览：题干 + 选项（正确答案高亮）+ 答案/解析 -->
          <NoteQuestionPreview
            v-for="l in openQuestionLinks()"
            :key="`pv-${l.targetId}`"
            :link="l"
            :note-id="selectedNote.id"
            @go="openLink(l)"
            @close="togglePreview(l)"
          />
        </template>

        <!-- 什么都没选 -->
        <div v-else class="detail-empty">
          <TikuIcon name="edit" :size="22" />
          <p class="detail-empty-title">{{ t('pickHint') }}</p>
          <p class="text-muted detail-empty-desc">{{ t('pickHintDesc') }}</p>
          <button class="btn btn-secondary btn-sm" @click="startCreate">{{ t('write') }}</button>
        </div>
      </section>
    </div>

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
 * 我的笔记（顶层页）——**列表 + 右侧详情**（2026-09-16 第三次反馈后重做）。
 *
 * 用户的原话："我第一时间想到的应该是点击某一个笔记打开来看看。结果实际上我们只能点击笔记右侧的修改按钮，
 * 而且点击后还直接在笔记页面顶部出现修改区域……点击保存后，都会出现一个新的笔记。"
 *
 * 于是这一版的规矩（同时写进 docs/design-ui.md 的 R18–R20）：
 * - **点这一条 = 打开它**：整行是按钮，右侧详情里读全文、看关联、就地展开题目；
 * - **同一时刻只有一个编辑位**：用一个状态机 `mode = idle | create | edit`，
 *   新建与编辑各自持有自己的草稿（曾经两者共用一个 draft，导致"编辑时顶部也出现同样内容"、
 *   点错按钮就把编辑变成了新增）；
 * - **改就是改**：打开某条 → 改 → 保存 = `PUT /notes/{id}`，绝不新增；新建只从「写一条」入口发生。
 *
 * 窄屏（≤1100px）退化成"一次只显示一侧"：点一条 → 详情占满，左上角「返回列表」。
 *
 * 边界（docs/features.md §4.9）：解析是题库的（随包导出），笔记是我的（只存本机）。
 * 笔记不隶属于任何题库/题目：挂在哪里由关联决定（0..N 个题库/题目，未归类也合法）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { addNoteLink, createNote, deleteNote, listNotes, removeNoteLink, setNoteColor, updateNote } from '../api/notes'
import { getBank, getBankQuestions, getBanks } from '../api/banks'
import { formatDate, noteTimeLabel, timeGroupKey, TIME_GROUPS } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import { MARK_COLORS, stripMarks } from '../utils/noteMarkup'
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
      desc: '做题时随手记的想法、AI 讲解里值得留下的部分。点左边任意一条就能打开来看；只存在本机，不随题库文件分享给别人',
      totalN: '共 {n} 条',
      inBank: '只看《{name}》',
      seeAll: '看全部笔记',
      font: '字号',
      fontTip: '正文大小（题目、解析、笔记一起变）',
      write: '写一条',
      quickPh: '写一条（Enter 保存，Shift+Enter 换行；选中文字可以用颜色标记）',
      editHint: '改完点保存即可（不会新建一条）',
      hangOn: '挂到',
      nothing: '不挂（未归类）',
      localOnly: '只存本机',
      srcAll: '全部',
      mine: '我写的',
      fromAi: '从讲解存的',
      fromAiShort: '来自讲解',
      fromAiLong: '来自 AI 讲解',
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
      expand: '展开全文',
      collapse: '收起',
      unlink: '取消这条关联（笔记留着）',
      goBank: '去题库',
      qLabel: '第 {n} 题',
      backToList: '返回列表',
      pickHint: '从左边点一条笔记来看',
      pickHintDesc: '也可以点右上角「写一条」新建；笔记里的题目能就地展开，不用跳走。',
      noLink: '这条笔记还没关联任何题库或题目——点「关联…」可以挂上去。',
      linkTitle: '关联到',
      fieldBank: '题库',
      fieldQuestion: '题目（可只挂题库）',
      pickBank: '选题库',
      pickQuestion: '选题目',
      linkTip: '一条笔记可以挂多个地方：确定后再点「关联…」继续加。',
      linkOk: '关联',
      emptyTitle: '还没有笔记',
      emptyDesc: '点右上角「写一条」，或做题、回顾时在每题下面「记一笔」。',
      noHitTitle: '没找到相关笔记',
      noHitDesc: '当前筛选下没有匹配的笔记（{kw}）。',
      saved: '已记下',
      removed: '已删除',
      linked: '已关联',
      unlinkedOk: '已取消关联',
      colorDone: '已标色',
      delAsk: '删除这条笔记？',
      delTitle: '删除笔记',
      discardAsk: '这条还在修改中，切换会丢掉未保存的内容。要继续吗？',
      discardTitle: '放弃未保存的修改',
      loadFail: '笔记加载失败，请检查后端是否还在运行',
      retry: '重试',
      fail: '操作失败'
    },
    'en-US': {
      title: 'My notes',
      desc: 'Thoughts you jot down while practising, plus the useful bits of AI explanations. Click any note on the left to open it; stored on this machine only, never shared inside bank files',
      totalN: '{n} notes',
      inBank: 'In “{name}” only',
      seeAll: 'See all notes',
      font: 'Size',
      fontTip: 'Content size (questions, analysis and notes together)',
      write: 'New note',
      quickPh: 'Write a note (Enter to save, Shift+Enter for newline; select text to mark it)',
      editHint: 'Save updates this note (it will not create a new one)',
      hangOn: 'Attach to',
      nothing: 'Nothing (unfiled)',
      localOnly: 'Local only',
      srcAll: 'All',
      mine: 'Mine',
      fromAi: 'From explanations',
      fromAiShort: 'from explanation',
      fromAiLong: 'From an AI explanation',
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
      expand: 'Show all',
      collapse: 'Collapse',
      unlink: 'Remove this attachment (the note stays)',
      goBank: 'Open in bank',
      qLabel: 'Q{n}',
      backToList: 'Back to list',
      pickHint: 'Pick a note on the left',
      pickHintDesc: 'Or use “New note” at the top right. Questions inside a note open in place — no jumping around.',
      noLink: 'This note is not attached to any bank or question yet — use “Attach…” to add one.',
      linkTitle: 'Attach to',
      fieldBank: 'Bank',
      fieldQuestion: 'Question (bank alone is fine)',
      pickBank: 'Pick a bank',
      pickQuestion: 'Pick a question',
      linkTip: 'One note can be attached in several places: confirm, then use “Attach…” again.',
      linkOk: 'Attach',
      emptyTitle: 'No notes yet',
      emptyDesc: 'Use “New note” at the top right, or “Add a note” under any question while practising.',
      noHitTitle: 'No matching notes',
      noHitDesc: 'Nothing matches the current filter ({kw}).',
      saved: 'Note saved',
      removed: 'Note deleted',
      linked: 'Attached',
      unlinkedOk: 'Attachment removed',
      colorDone: 'Color updated',
      delAsk: 'Delete this note?',
      delTitle: 'Delete note',
      discardAsk: 'This note has unsaved changes — switching will discard them. Continue?',
      discardTitle: 'Discard unsaved changes',
      loadFail: 'Could not load notes — check that the backend is still running',
      retry: 'Retry',
      fail: 'Action failed'
    }
  }
})

const route = useRoute()
const router = useRouter()
const { confirmDanger } = useConfirm(t)

/* ---------- 列表状态 ---------- */
const notes = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(true)
const loadError = ref('')
const error = ref('')
const saving = ref(false)

const keyword = ref('')
const sourceFilter = ref('')
const unlinkedOnly = ref(false)
const banks = ref([])
const bankFilter = ref(null)

/* ---------- 右栏状态 ---------- */
/** 唯一编辑位：idle 读 / create 新建 / edit 改这一条（三者互斥） */
const mode = ref('idle')
const selectedId = ref(null)
/** 选中的那条（存快照：翻页/筛选把列表换掉时，右栏不该突然空掉） */
const selectedNote = ref(null)
const newDraft = ref('')
const editDraft = ref('')
const createBox = ref(null)
const editBox = ref(null)
const colorOpen = ref(false)

const quickBankId = ref(null)
const linkVisible = ref(false)
const linkNote = ref(null)
const linkBankId = ref(null)
const linkQuestionId = ref(null)
const linkQuestions = ref([])
const linkQuestionsLoading = ref(false)

const isEn = computed(() => String(locale.value).startsWith('en'))
const currentScale = computed(() => READING_SCALES.find((s) => s.key === readingScale.value) || READING_SCALES[1])
const linkTargetReady = computed(() => Number(linkBankId.value) > 0 || Number(linkQuestionId.value) > 0)

/** 有未保存的改动（切换选中/退出编辑前要拦一下） */
const dirty = computed(
  () => mode.value === 'edit' && !!selectedNote.value && editDraft.value.trim() !== selectedNote.value.content
)

/* ---------- 窄屏：一次只显示一侧 ---------- */
const narrowMq = window.matchMedia('(max-width: 1100px)')
const isNarrow = ref(narrowMq.matches)
const pane = ref('list')
function syncNarrow(e) {
  isNarrow.value = e.matches
  if (!e.matches) pane.value = 'list'
}
narrowMq.addEventListener('change', syncNarrow)
onBeforeUnmount(() => narrowMq.removeEventListener('change', syncNarrow))
/** 右栏是否可见（宽屏恒可见；窄屏看当前在哪一侧 / 是否在新建态） */
const showDetailPane = computed(() => !isNarrow.value || pane.value === 'detail' || mode.value === 'create')

/* ---------- 时间分组（口径见 utils/format.js） ---------- */
const groups = computed(() => {
  const buckets = new Map(TIME_GROUPS.map((k) => [k, []]))
  for (const n of notes.value) {
    buckets.get(timeGroupKey(n.updatedAt)).push(n)
  }
  return TIME_GROUPS.map((key) => ({ key, labelKey: `g.${key}`, notes: buckets.get(key) })).filter((g) => g.notes.length)
})

const formatTime = (iso) => (iso ? formatDate(iso) : '')

/** 列表里的一行：去掉标注符号（高亮 / 加粗 / 下划线）压成一行纯文本，避免列表渲染成富文本块 */
function summary(n) {
  return stripMarks(n.content || '').replace(/\s+/g, ' ').trim()
}

function noteBankId(n) {
  const links = n?.links || []
  const q = links.find((l) => l.type === 'question' && l.bankId)
  if (q) return q.bankId
  const b = links.find((l) => l.type === 'bank')
  if (b) return b.targetId
  return bankFilter.value?.id || quickBankId.value || null
}

const richHtml = (s, bankId) => richTextToHtml(s || '', bankId)
const shortLabel = (l) => (l.type === 'question' ? t('qLabel', { n: l.questionNumber ?? '—' }) : l.label)

/* ---------- 关联题目的就地预览（只在右栏、只对当前这条） ---------- */
const openPreviews = reactive({})
const previewKey = (l) => `${selectedId.value}:${l.targetId}`
const isOpen = (l) => !!openPreviews[previewKey(l)]
const openQuestionLinks = () =>
  ((selectedNote.value?.links) || []).filter((l) => l.type === 'question' && isOpen(l))

function onLinkClick(l) {
  if (l.type === 'question') togglePreview(l)
  else openLink(l)
}

function togglePreview(l) {
  const key = previewKey(l)
  openPreviews[key] = !openPreviews[key]
  if (!openPreviews[key]) delete openPreviews[key]
}

/* ---------- 加载 ---------- */
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
    if (sourceFilter.value) params.source = sourceFilter.value
    const data = await listNotes(params)
    notes.value = data?.records || []
    total.value = Number(data?.total || 0)
    // 右栏那条如果还在当前页里，就用最新数据刷新它（改了正文/标了色要立刻反映）
    if (selectedId.value) {
      const fresh = notes.value.find((n) => n.id === selectedId.value)
      if (fresh) selectedNote.value = fresh
    }
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
  // ?note=<id> 直达某一条（例如从别处跳回来）
  const wanted = Number(route.query.note || 0)
  if (wanted) {
    const found = notes.value.find((n) => n.id === wanted)
    if (found) select(found)
  }
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

/* ---------- 选中 / 读取 ---------- */
async function select(n) {
  if (n.id === selectedId.value && mode.value === 'idle') {
    if (isNarrow.value) pane.value = 'detail'
    return
  }
  if (dirty.value) {
    const ok = await confirmDanger(t('discardAsk'), t('discardTitle')).catch(() => false)
    if (!ok) return
  }
  selectedId.value = n.id
  selectedNote.value = n
  mode.value = 'idle'
  colorOpen.value = false
  for (const key of Object.keys(openPreviews)) delete openPreviews[key]
  if (isNarrow.value) pane.value = 'detail'
  syncUrl()
}

/** 正在看哪一条写进地址栏（`?note=<id>`）：刷新、从题库返回、前进后退都还在这一条上 */
function syncUrl(noteId = selectedId.value) {
  const query = { ...route.query }
  if (noteId) query.note = String(noteId)
  else delete query.note
  router.replace({ path: '/notes', query })
}

function backToList() {
  pane.value = 'list'
}

/* ---------- 新建（唯一的新增入口） ---------- */
async function startCreate() {
  if (dirty.value) {
    const ok = await confirmDanger(t('discardAsk'), t('discardTitle')).catch(() => false)
    if (!ok) return
  }
  mode.value = 'create'
  newDraft.value = ''
  error.value = ''
  if (isNarrow.value) pane.value = 'detail'
  await nextTick()
  createBox.value?.focus()
}

function cancelCreate() {
  mode.value = 'idle'
  newDraft.value = ''
  if (isNarrow.value) pane.value = 'list'
}

async function doCreate() {
  const content = newDraft.value.trim()
  if (!content || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const created = await createNote({ bankId: quickBankId.value || null, content, source: 'user' })
    newDraft.value = ''
    mode.value = 'idle'
    ElMessage.success(t('saved'))
    page.value = 1
    await load()
    // 新建完直接把这条打开（用户刚写的，接着看/接着改都顺手）
    if (created?.id) {
      selectedId.value = created.id
      selectedNote.value = created
    }
    if (isNarrow.value) pane.value = 'detail'
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  } finally {
    saving.value = false
  }
}

/* ---------- 改这一条（PUT，绝不新增） ---------- */
async function startEdit() {
  if (!selectedNote.value) return
  mode.value = 'edit'
  editDraft.value = selectedNote.value.content
  error.value = ''
  await nextTick()
  editBox.value?.focus()
}

function cancelEdit() {
  mode.value = 'idle'
  editDraft.value = ''
}

async function saveEdit() {
  const note = selectedNote.value
  const content = editDraft.value.trim()
  if (!note || !content || saving.value) return
  saving.value = true
  try {
    const updated = await updateNote(note.id, content)
    selectedNote.value = { ...note, ...updated }
    mode.value = 'idle'
    editDraft.value = ''
    ElMessage.success(t('saved'))
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  } finally {
    saving.value = false
  }
}

/* ---------- 标色 / 删除 / 关联 ---------- */
async function pickColor(n, color) {
  try {
    const updated = await setNoteColor(n.id, color)
    n.color = color || null
    if (selectedNote.value?.id === n.id) selectedNote.value = { ...selectedNote.value, color: color || null, ...updated }
    colorOpen.value = false
    ElMessage.success(t('colorDone'))
    await load()
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
    if (selectedId.value === n.id) {
      selectedId.value = null
      selectedNote.value = null
      mode.value = 'idle'
      syncUrl(null)
      if (isNarrow.value) pane.value = 'list'
    }
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  }
}

async function unlink(l) {
  const note = selectedNote.value
  if (!note) return
  try {
    const updated = await removeNoteLink(note.id, l.type, l.targetId)
    selectedNote.value = { ...note, ...updated }
    delete openPreviews[previewKey(l)]
    ElMessage.success(t('unlinkedOk'))
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  }
}

function openLink(l) {
  if (l.type === 'question') {
    router.push({ path: `/banks/${l.bankId}`, query: { q: l.targetId } })
  } else {
    router.push(`/banks/${l.targetId}`)
  }
}

function openLinkDialog(n) {
  linkNote.value = n
  colorOpen.value = false
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
    if (selectedId.value === note.id) {
      selectedNote.value = notes.value.find((n) => n.id === note.id) || selectedNote.value
    }
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  } finally {
    saving.value = false
  }
}

/* ---------- 筛选 ---------- */
function applyFilter() {
  page.value = 1
  load()
}

function setSource(src) {
  sourceFilter.value = sourceFilter.value === src ? '' : src
  applyFilter()
}

function toggleUnlinked() {
  unlinkedOnly.value = !unlinkedOnly.value
  applyFilter()
}

function clearSearch() {
  keyword.value = ''
  applyFilter()
}

function clearBankFilter() {
  router.push({ path: '/notes' })
}
</script>

<style scoped>
/* 页面略放宽（列表 + 详情两栏），但正文本行仍是 68ch 上限 */
.notes-page {
  max-width: 1180px;
  margin: 0 auto;
}
.note-scope {
  margin-left: 6px;
}

/* 字号调节（头部，与设置里同一处实现） */
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
  flex-wrap: wrap;
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

/* 两栏 */
.notes-split {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}
.list-pane {
  width: 340px;
  flex-shrink: 0;
  max-height: calc(100vh - 260px);
  overflow-y: auto;
  padding-right: 2px;
}
.detail-pane {
  flex: 1;
  min-width: 0;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 16px 20px 20px;
  max-height: calc(100vh - 200px);
  overflow-y: auto;
}
/* 窄屏：一次只显示一侧 */
.notes-split.narrow .detail-pane {
  display: none;
}
.notes-split.narrow.show-detail .list-pane {
  display: none;
}
.notes-split.narrow .detail-pane {
  max-height: none;
}
.notes-split.narrow.show-detail .detail-pane {
  display: block;
  width: 100%;
}

/* 列表：整行可点 */
.note-groups {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.note-group-head {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 4px 0 6px;
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
.note-row {
  position: relative;
  display: flex;
  gap: 10px;
  width: 100%;
  text-align: left;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 9px 12px 9px 14px;
  margin-bottom: 6px;
  cursor: pointer;
  font-family: inherit;
  transition: border-color var(--ease), background var(--ease);
}
.note-row:hover {
  background: var(--bg-hover);
  border-color: var(--border-strong);
}
.note-row.on {
  border-color: var(--accent);
  background: var(--bg-card);
  box-shadow: inset 0 0 0 1px var(--accent);
}
.note-row-rail {
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--rail, transparent);
}
.note-row-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.note-row-text {
  font-size: calc(var(--content-font) - 1.5px);
  line-height: 1.55;
  color: var(--text-primary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.note-row-meta {
  display: flex;
  gap: 4px;
  font-size: 11.5px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
}
.note-row-where,
.note-row-src {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 详情 */
.detail-back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  color: var(--accent-text);
  font-size: 12.5px;
  cursor: pointer;
  padding: 0 0 10px;
}
.detail-title {
  font-size: 15px;
  margin-bottom: 10px;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.detail-grow {
  flex: 1;
}
.detail-src {
  font-size: 11.5px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 0 6px;
}
.detail-when {
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
.detail-colors {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
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
/* 正文：跟着"阅读字号"档位，行宽 68ch 上限 */
.detail-text {
  font-size: var(--content-font);
  line-height: var(--content-lh);
  white-space: pre-wrap;
  word-break: break-word;
  max-width: 68ch;
}
.detail-text :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 6px 0;
  display: block;
}
.detail-nolink {
  margin: 14px 0 0;
  font-size: 12.5px;
}
.detail-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.note-hang {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.note-bank-select {
  width: 170px;
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
/* 未选中任何一条 */
.detail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 60px 0;
  color: var(--text-muted);
  text-align: center;
}
.detail-empty-title {
  margin: 6px 0 0;
  font-size: 14px;
  color: var(--text-secondary);
}
.detail-empty-desc {
  margin: 0 0 10px;
  font-size: 12.5px;
  max-width: 320px;
}

/* 关联标签 */
.note-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 14px;
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
