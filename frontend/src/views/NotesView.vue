<template>
  <div class="page">
    <PageHeader :title="t('title')" :desc="t('desc')" :back-to="`/banks/${id}`" :back-text="t('backToBank')">
      <template #meta>
        <span>{{ t('totalN', { n: total }) }}</span>
      </template>
    </PageHeader>

    <!-- 随手记：没有具体某道题时的想法（挂在题库上） -->
    <section class="note-new">
      <textarea
        v-model="draft"
        class="note-input"
        rows="2"
        :placeholder="t('quickPh')"
        @keydown.enter.exact.prevent="createQuick"
      ></textarea>
      <div class="note-new-foot">
        <span class="text-muted note-hint">{{ t('localOnly') }}</span>
        <button class="btn btn-primary btn-sm" :disabled="saving || !draft.trim()" @click="createQuick">
          {{ saving ? t('saving') : t('save') }}
        </button>
      </div>
    </section>

    <div v-if="loading" class="note-loading">
      <div class="card tiku-skeleton">
        <div class="sk-line" style="width: 30%"></div>
        <div class="sk-line" style="width: 80%"></div>
      </div>
    </div>

    <EmptyState
      v-else-if="!notes.length"
      icon="edit"
      :title="t('emptyTitle')"
      :desc="t('emptyDesc')"
    />

    <div v-else class="note-list">
      <div v-for="n in notes" :key="n.id" class="note-item">
        <div class="note-head">
          <span class="note-where">
            <template v-if="n.questionId">
              <button class="note-q" @click="openQuestion(n.questionId)">{{ t('questionN', { n: n.questionNumber ?? '—' }) }}</button>
            </template>
            <template v-else>{{ t('bankLevel') }}</template>
          </span>
          <span class="note-tag" :class="n.source">{{ n.source === 'ai' ? t('fromAi') : t('mine') }}</span>
          <span class="text-muted note-time">{{ formatTime(n.updatedAt) }}</span>
          <button class="note-act" @click="startEdit(n)">{{ t('edit') }}</button>
          <button class="note-act danger" @click="remove(n)">{{ t('delete') }}</button>
        </div>
        <div v-if="editingId !== n.id" class="note-text">{{ n.content }}</div>
        <div v-else class="note-edit">
          <textarea v-model="draft" class="note-input" rows="3"></textarea>
          <div class="note-edit-btns">
            <button class="btn btn-ghost btn-sm" @click="editingId = null">{{ t('cancel') }}</button>
            <button class="btn btn-primary btn-sm" :disabled="saving || !draft.trim()" @click="saveEdit(n)">{{ t('save') }}</button>
          </div>
        </div>
      </div>
    </div>

    <Pager :page="page" :size="size" :total="total" @update:page="onPage" />
  </div>
</template>

<script setup>
/**
 * 我的笔记（题库内所有笔记）。
 *
 * 边界（写进 docs/features.md §4.9）：
 * - **解析**是题库的：写进 `question.analysis`，会随题库文件导出、会给别人看；
 * - **笔记**是我的：只存本机（`note` 表，不进内容包），是记忆钩子与体会；
 * - 挂题（做题/回顾时记）与挂题库（这里顶部的随手记）都支持。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { createNote, deleteNote, listNotes, updateNote } from '../api/notes'
import { formatDate } from '../utils/format'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import Pager from '../components/Pager.vue'
import { useConfirm } from '../composables/useConfirm'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      title: '我的笔记',
      desc: '做题时随手记的想法、AI 讲解里值得留下的部分——只存在本机，不会随题库文件分享给别人',
      backToBank: '返回题库',
      totalN: '共 {n} 条',
      quickPh: '随手记一条（没有具体某道题时的想法；Enter 保存）',
      localOnly: '只存本机 · 不随题库导出',
      save: '保存',
      saving: '保存中…',
      cancel: '取消',
      edit: '改',
      delete: '删',
      mine: '我写的',
      fromAi: 'AI 讲解',
      bankLevel: '随手记',
      questionN: '第 {n} 题',
      emptyTitle: '还没有笔记',
      emptyDesc: '做题或回顾时，每题下面都有「记一笔」；AI 讲解里也能一键「存进笔记」。',
      saved: '已记下',
      removed: '已删除',
      delAsk: '删除这条笔记？',
      delTitle: '删除笔记',
      fail: '操作失败'
    },
    'en-US': {
      title: 'My notes',
      desc: 'Thoughts you jot down while practising, plus the useful bits of AI explanations — stored on this machine only, never shared inside bank files',
      backToBank: 'Back to bank',
      totalN: '{n} notes',
      quickPh: 'Quick note (for thoughts that belong to no single question; Enter to save)',
      localOnly: 'Local only · never exported with the bank',
      save: 'Save',
      saving: 'Saving…',
      cancel: 'Cancel',
      edit: 'Edit',
      delete: 'Delete',
      mine: 'Mine',
      fromAi: 'AI explanation',
      bankLevel: 'Quick note',
      questionN: 'Question {n}',
      emptyTitle: 'No notes yet',
      emptyDesc: 'Each question has an “Add a note” box in practice and review; AI explanations can be saved to notes in one click.',
      saved: 'Note saved',
      removed: 'Note deleted',
      delAsk: 'Delete this note?',
      delTitle: 'Delete note',
      fail: 'Action failed'
    }
  }
})

const route = useRoute()
const router = useRouter()
const id = route.params.id
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

const formatTime = (iso) => (iso ? formatDate(iso) : '')

async function load() {
  loading.value = true
  try {
    const data = await listNotes(id, { page: page.value, size: size.value })
    notes.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch (e) {
    notes.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onPage(p) {
  page.value = p
  load()
}

async function createQuick() {
  const content = draft.value.trim()
  if (!content || saving.value) return
  saving.value = true
  try {
    await createNote(id, { questionId: null, content, source: 'user' })
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

/** 点题号 → 回题库列表并把这道题的讲解/笔记面板定位过去（用 q 参数打开题目编辑器过重，先回列表） */
function openQuestion(questionId) {
  router.push({ path: `/banks/${id}`, query: { q: questionId } })
}
</script>

<style scoped>
.note-new {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 12px 14px;
  margin-bottom: 14px;
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
  resize: vertical;
}
.note-new-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.note-hint {
  flex: 1;
  font-size: 12px;
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
  gap: 10px;
  margin-bottom: 6px;
}
.note-where {
  font-size: 12.5px;
  color: var(--text-secondary);
}
.note-q {
  border: none;
  background: none;
  padding: 0;
  color: var(--accent-text);
  font-size: 12.5px;
  cursor: pointer;
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
  margin-left: auto;
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 12.5px;
  cursor: pointer;
}
.note-act + .note-act {
  margin-left: 0;
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
</style>
