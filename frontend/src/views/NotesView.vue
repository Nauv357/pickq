<template>
  <div class="page">
    <PageHeader :title="t('title')" :desc="t('desc')">
      <template #meta>
        <span>{{ t('totalN', { n: total }) }}</span>
        <span v-if="bankFilter" class="note-scope">· {{ t('inBank', { name: bankFilter.name }) }}</span>
      </template>
      <template #actions>
        <button v-if="bankFilter" class="btn btn-secondary btn-sm" @click="clearBankFilter">{{ t('seeAll') }}</button>
      </template>
    </PageHeader>

    <!-- 随手记：想到什么先写下来，挂不挂某个题库都行 -->
    <section class="note-new">
      <textarea
        ref="newBox"
        v-model="draft"
        class="note-input"
        rows="1"
        :placeholder="t('quickPh')"
        @input="autoGrow"
        @keydown.enter.exact.prevent="createQuick"
      ></textarea>
      <div v-if="needsPreview(draft)" class="note-preview">
        <span class="text-muted note-preview-label">{{ t('preview') }}</span>
        <div class="note-text" v-html="richHtml(draft)"></div>
      </div>
      <div class="note-new-foot">        <label class="note-hang">
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
      <p v-if="error" class="note-error">{{ error }}</p>
    </section>

    <div class="note-tabs">
      <button class="note-tab" :class="{ active: !unlinkedOnly }" @click="setFilter('all')">{{ t('all') }}</button>
      <button class="note-tab" :class="{ active: unlinkedOnly }" @click="setFilter('unlinked')">{{ t('unlinked') }}</button>
      <span v-if="bankFilter" class="note-tab static">{{ bankFilter.name }}</span>
    </div>

    <div v-if="loading" class="note-loading">
      <div class="card tiku-skeleton">
        <div class="sk-line" style="width: 30%"></div>
        <div class="sk-line" style="width: 80%"></div>
      </div>
    </div>

    <!-- 失败要说清原因并给出路（以前这里静默落成"空"） -->
    <div v-else-if="loadError" class="note-failed">
      <TikuIcon name="info" :size="16" />
      <span>{{ loadError }}</span>
      <button class="btn btn-secondary btn-sm" @click="load">{{ t('retry') }}</button>
    </div>

    <EmptyState v-else-if="!notes.length" icon="edit" :title="t('emptyTitle')" :desc="t('emptyDesc')" />

    <div v-else class="note-list">
      <div v-for="n in notes" :key="n.id" class="note-item">
        <div class="note-head">
          <span v-if="!n.links?.length" class="note-where text-muted">{{ t('unlinked') }}</span>
          <span v-for="l in n.links" :key="l.type + l.targetId" class="note-link-chip">
            <button class="note-link-jump" :title="t('jump')" @click="openLink(l)">{{ l.label }}</button>
            <button class="note-link-x" :title="t('unlink')" @click="unlink(n, l)">
              <TikuIcon name="x" :size="11" />
            </button>
          </span>
          <span class="note-tag" :class="n.source">{{ n.source === 'ai' ? t('fromAi') : t('mine') }}</span>
          <span class="text-muted note-time">{{ formatTime(n.updatedAt) }}</span>
          <button class="note-act" @click="startEdit(n)">{{ t('edit') }}</button>
          <button class="note-act" @click="openLinkDialog(n)">{{ t('link') }}</button>
          <button class="note-act danger" @click="remove(n)">{{ t('delete') }}</button>
        </div>
        <div v-if="editingId !== n.id" class="note-text" v-html="richHtml(n.content, n)"></div>
        <div v-else class="note-edit">
          <textarea
            v-model="draft"
            class="note-input"
            rows="1"
            @input="autoGrow"
            @keydown.enter.exact.prevent="saveEdit(n)"
          ></textarea>
          <div v-if="needsPreview(draft)" class="note-preview">
            <span class="text-muted note-preview-label">{{ t('preview') }}</span>
            <div class="note-text" v-html="richHtml(draft, n)"></div>
          </div>
          <div class="note-edit-btns">
            <button class="btn btn-ghost btn-sm" @click="editingId = null">{{ t('cancel') }}</button>
            <button class="btn btn-primary btn-sm" :disabled="saving || !draft.trim()" @click="saveEdit(n)">{{ t('save') }}</button>
          </div>
        </div>
      </div>
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
 * 我的笔记（全局）。
 *
 * 边界（写进 docs/features.md §4.9）：
 * - **解析**是题库的：写进 `question.analysis`，会随题库文件导出、会给别人看；
 * - **笔记**是我的：只存本机（`note` 表，不进内容包），是记忆钩子与体会。
 *
 * 笔记**不隶属于任何题库/题目**：挂在哪里由关联决定，一条笔记可以同时挂在多个题库、
 * 多道题上，也可以一个都不挂（未归类）。题库/题目被删只删关联，笔记内容留着。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { addNoteLink, createNote, deleteNote, listNotes, removeNoteLink, updateNote } from '../api/notes'
import { getBank, getBankQuestions, getBanks } from '../api/banks'
import { formatDate } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import Pager from '../components/Pager.vue'
import TikuIcon from '../components/TikuIcon.vue'
import { useConfirm } from '../composables/useConfirm'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      title: '我的笔记',
      desc: '做题时随手记的想法、AI 讲解里值得留下的部分。可以挂在题库或某道题上，也可以谁都不挂；只存在本机，不随题库文件分享给别人',
      totalN: '共 {n} 条',
      inBank: '只看《{name}》',
      seeAll: '看全部笔记',
      quickPh: '写一条（Enter 保存，Shift+Enter 换行）',
      hangOn: '挂到',
      nothing: '不挂（未归类）',
      localOnly: '只存本机',
      preview: '效果预览',
      all: '全部',
      unlinked: '未归类',
      save: '保存',
      saving: '保存中…',
      cancel: '取消',
      edit: '改',
      delete: '删',
      link: '关联…',
      mine: '我写的',
      fromAi: 'AI 讲解',
      jump: '去看这个地方',
      unlink: '取消这条关联（笔记留着）',
      qPrefix: '第 {n} 题',
      linkTitle: '关联到',
      fieldBank: '题库',
      fieldQuestion: '题目（可只挂题库）',
      pickBank: '选题库',
      pickQuestion: '选题目',
      linkTip: '一条笔记可以挂多个地方：确定后再点「关联…」继续加。',
      linkOk: '关联',
      emptyTitle: '还没有笔记',
      emptyDesc: '上面写一条试试；做题与回顾时每题下面也有「记一笔」。',
      saved: '已记下',
      removed: '已删除',
      linked: '已关联',
      unlinkedOk: '已取消关联',
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
      quickPh: 'Write a note (Enter to save, Shift+Enter for newline)',
      hangOn: 'Attach to',
      nothing: 'Nothing (unfiled)',
      localOnly: 'Local only',
      preview: 'Preview',
      all: 'All',
      unlinked: 'Unfiled',
      save: 'Save',
      saving: 'Saving…',
      cancel: 'Cancel',
      edit: 'Edit',
      delete: 'Delete',
      link: 'Attach…',
      mine: 'Mine',
      fromAi: 'AI explanation',
      jump: 'Go there',
      unlink: 'Remove this attachment (the note stays)',
      qPrefix: 'Q{n}',
      linkTitle: 'Attach to',
      fieldBank: 'Bank',
      fieldQuestion: 'Question (bank alone is fine)',
      pickBank: 'Pick a bank',
      pickQuestion: 'Pick a question',
      linkTip: 'One note can be attached in several places: confirm, then use “Attach…” again.',
      linkOk: 'Attach',
      emptyTitle: 'No notes yet',
      emptyDesc: 'Write one above; practice and review also offer “Add a note” under each question.',
      saved: 'Note saved',
      removed: 'Note deleted',
      linked: 'Attached',
      unlinkedOk: 'Attachment removed',
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

const banks = ref([])
const quickBankId = ref(null)
const bankFilter = ref(null)
const unlinkedOnly = ref(false)

const linkVisible = ref(false)
const linkNote = ref(null)
const linkBankId = ref(null)
const linkQuestionId = ref(null)
const linkQuestions = ref([])
const linkQuestionsLoading = ref(false)

const linkTargetReady = computed(() => Number(linkBankId.value) > 0 || Number(linkQuestionId.value) > 0)

const formatTime = (iso) => (iso ? formatDate(iso) : '')

/**
 * 笔记里的图片是题库资源（[图片:name] → /api/banks/{id}/images/…），渲染时需要一个题库 id：
 * 优先用这条笔记自己挂的题目/题库，都没有（未归类）就退回当前筛选的题库。
 */
function noteBankId(n) {
  const links = n?.links || []
  const q = links.find((l) => l.type === 'question' && l.bankId)
  if (q) return q.bankId
  const b = links.find((l) => l.type === 'bank')
  if (b) return b.targetId
  return bankFilter.value?.id || quickBankId.value || null
}

/** 渲染与编辑用同一套规则（公式/图片/表格），所见即所得 */
const richHtml = (s, n) => richTextToHtml(s || '', noteBankId(n))

const needsPreview = (text) => /\$[^$]+\$|\[图片:|<table[\s>]/i.test(String(text || ''))

function autoGrow(e) {
  const el = e?.target
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 400)}px`
}

function growAll() {
  nextTick(() => {
    document.querySelectorAll('.note-input').forEach((el) => {
      el.style.height = 'auto'
      el.style.height = `${Math.min(el.scrollHeight, 400)}px`
    })
  })
}

async function loadBanks() {
  try {
    const data = await getBanks({ page: 1, size: 200 })
    banks.value = data?.records || []
  } catch (e) {
    banks.value = []
  }
}

/** 只走题库过滤（questionId 过滤由做题页的就地组件用） */
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const params = { page: page.value, size: size.value }
    if (unlinkedOnly.value) {
      params.unlinked = true
    } else if (bankFilter.value) {
      params.bankId = bankFilter.value.id
    }
    const data = await listNotes(params)
    notes.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch (e) {
    notes.value = []
    total.value = 0
    loadError.value = t('loadFail')
  } finally {
    loading.value = false
    growAll()
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
      /* 题库可能已删：名字保持 #id，列表照常显示未归类笔记 */
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

async function createQuick() {
  const content = draft.value.trim()
  if (!content || saving.value) return
  saving.value = true
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
  growAll()
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
    ElMessage.success(t('unlinkedOk'))
    await load()
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  }
}

/** 跳到笔记挂的地方：题库 → 题库详情；题目 → 题库详情里定位到这道题 */
function openLink(link) {
  if (link.type === 'question') {
    router.push({ path: `/banks/${link.bankId}`, query: { q: link.targetId } })
  } else {
    router.push(`/banks/${link.targetId}`)
  }
}

function openLinkDialog(n) {
  linkNote.value = n
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
      label: `${t('qPrefix', { n: q.questionNumber ?? '—' })} ${String(q.content || '').replace(/\s+/g, ' ').slice(0, 30)}`
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
    if (Number(linkQuestionId.value) > 0) {
      await addNoteLink(note.id, 'question', Number(linkQuestionId.value))
    } else {
      await addNoteLink(note.id, 'bank', Number(linkBankId.value))
    }
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
.note-scope {
  margin-left: 6px;
}
.note-new {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 12px 14px;
  margin-bottom: 12px;
}
.note-input {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13.5px;
  font-family: inherit;
  line-height: 1.75;
  background: var(--bg-elev);
  color: var(--text-primary);
  /* 高度由内容撑开（autoGrow），超过 400px 才内部滚动 */
  resize: none;
  overflow-y: auto;
}
.note-preview {
  margin-top: 8px;
  border-left: 2px solid var(--accent);
  padding: 4px 0 4px 9px;
  background: var(--bg-elev);
  border-radius: 0 8px 8px 0;
}
.note-preview-label {
  display: block;
  font-size: 11px;
  margin-bottom: 2px;
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
.note-tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.note-tab {
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12.5px;
  cursor: pointer;
}
.note-tab.active {
  border-color: var(--accent);
  color: var(--accent-text);
}
.note-tab.static {
  cursor: default;
  color: var(--text-muted);
}
.note-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.note-item {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 12px 14px;
}
.note-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.note-where {
  font-size: 12.5px;
}
/* 关联：一条笔记可以挂多处，每处一个可点可解除的小标签 */
.note-link-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  padding: 0 4px 0 8px;
  font-size: 12px;
  background: var(--bg-elev);
}
.note-link-jump {
  border: none;
  background: none;
  padding: 0;
  color: var(--accent-text);
  font-size: 12px;
  cursor: pointer;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.note-link-x {
  border: none;
  background: none;
  padding: 0 2px;
  color: var(--text-muted);
  cursor: pointer;
  display: inline-flex;
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
}
.note-act {
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 12.5px;
  cursor: pointer;
  padding: 0 4px;
}
.note-act:first-of-type {
  margin-left: auto;
}
.note-act.danger {
  color: var(--danger);
}
.note-text {
  font-size: 13.5px;
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-word;
}
.note-edit-btns {
  display: flex;
  gap: 8px;
  margin-top: 8px;
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
