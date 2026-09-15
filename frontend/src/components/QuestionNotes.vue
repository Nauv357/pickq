<template>
  <div class="qn">
    <!-- 折叠态：只显示"我记了 N 条"；点开才展开（做题页不抢注意力） -->
    <button v-if="!open" class="qn-toggle" :class="{ empty: !notes.length }" @click="toggle">
      <TikuIcon name="edit" :size="12" />
      {{ notes.length ? t('hasN', { n: notes.length }) : t('add') }}
    </button>

    <div v-else class="qn-body">
      <div v-for="n in notes" :key="n.id" class="qn-item">
        <div class="qn-head">
          <span class="qn-tag" :class="n.source">{{ n.source === 'ai' ? t('fromAi') : t('mine') }}</span>
          <span class="text-muted qn-time">{{ formatTime(n.updatedAt) }}</span>
          <button class="qn-act" :title="t('edit')" @click="startEdit(n)">{{ t('edit') }}</button>
          <button class="qn-act danger" :title="t('delete')" @click="remove(n)">{{ t('delete') }}</button>
        </div>
        <div v-if="editingId !== n.id" class="qn-text">{{ n.content }}</div>
        <div v-else class="qn-edit">
          <textarea v-model="draft" class="qn-input" rows="3" @keydown.enter.exact.prevent="saveEdit(n)"></textarea>
          <div class="qn-edit-btns">
            <button class="btn btn-secondary btn-sm" @click="editingId = null">{{ t('cancel') }}</button>
            <button class="btn btn-primary btn-sm" :disabled="saving || !draft.trim()" @click="saveEdit(n)">{{ t('save') }}</button>
          </div>
        </div>
      </div>

      <div class="qn-new">
        <textarea
          v-model="draft"
          class="qn-input"
          rows="2"
          :placeholder="t('placeholder')"
          @keydown.enter.exact.prevent="create"
        ></textarea>
        <div class="qn-new-btns">
          <span class="text-muted qn-hint">{{ t('hint') }}</span>
          <button class="btn btn-ghost btn-sm" @click="open = false">{{ t('collapse') }}</button>
          <button class="btn btn-primary btn-sm" :disabled="saving || !draft.trim()" @click="create">
            {{ saving ? t('saving') : t('save') }}
          </button>
        </div>
      </div>
      <p v-if="error" class="qn-error">{{ error }}</p>
    </div>
  </div>
</template>

<script setup>
/**
 * 每题的笔记（我的想法）。
 *
 * 为什么单独一块而不是并进「讲解」：讲解是 AI 说的，笔记是**我说的**；
 * 解析是题库的（会随包导出、给别人看），笔记只在本机。这个边界写进 docs/features.md。
 *
 * 折叠态只占一个小按钮，展开才显示内容与输入框——做题页里不抢注意力。
 */
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import TikuIcon from './TikuIcon.vue'
import { useConfirm } from '../composables/useConfirm'
import { createNote, deleteNote, listNotes, listQuestionNotes, updateNote } from '../api/notes'

const props = defineProps({
  bankId: { type: [Number, String], required: true },
  questionId: { type: [Number, String], default: null },
  /** 题库级笔记（没有具体题时的随手记）：不传 questionId 时用 */
  bankLevel: { type: Boolean, default: false },
  /** 打开时就展开（例如"我的笔记"列表里） */
  startOpen: { type: Boolean, default: false }
})

const emit = defineEmits(['changed'])

const { t } = useI18n({
  messages: {
    'zh-CN': {
      add: '记一笔',
      hasN: '我的笔记（{n}）',
      mine: '我写的',
      fromAi: 'AI 讲解存进来的',
      edit: '改',
      delete: '删',
      cancel: '取消',
      save: '保存',
      saving: '保存中…',
      placeholder: '写下你的想法、记忆钩子、老师提醒…（Enter 保存，Shift+Enter 换行）',
      hint: '只存本机，不会随题库文件分享给别人',
      collapse: '收起',
      saved: '已记下',
      removed: '已删除',
      delAsk: '删除这条笔记？',
      delTitle: '删除笔记',
      fail: '操作失败'
    },
    'en-US': {
      add: 'Add a note',
      hasN: 'My notes ({n})',
      mine: 'Mine',
      fromAi: 'Saved from AI',
      edit: 'Edit',
      delete: 'Delete',
      cancel: 'Cancel',
      save: 'Save',
      saving: 'Saving…',
      placeholder: 'Your takeaway, memory hook, or the tutor’s tip… (Enter to save, Shift+Enter for newline)',
      hint: 'Stored on this machine only — never shared inside bank files',
      collapse: 'Collapse',
      saved: 'Note saved',
      removed: 'Note deleted',
      delAsk: 'Delete this note?',
      delTitle: 'Delete note',
      fail: 'Action failed'
    }
  }
})

const { confirmDanger } = useConfirm(t)

const notes = ref([])
const open = ref(props.startOpen)
const saving = ref(false)
const error = ref('')
const draft = ref('')
const editingId = ref(null)

const formatTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function load() {
  try {
    if (props.questionId) {
      notes.value = (await listQuestionNotes(Number(props.questionId))) || []
    } else {
      const data = await listNotes(props.bankId, { page: 1, size: 50 })
      notes.value = data?.records || []
    }
  } catch (e) {
    notes.value = []
  }
}

onMounted(() => {
  if (open.value) load()
})

function toggle() {
  open.value = !open.value
  if (open.value && !notes.value.length) load()
}

watch(
  () => props.questionId,
  () => {
    notes.value = []
    editingId.value = null
    draft.value = ''
    if (open.value) load()
  }
)

async function create() {
  const content = draft.value.trim()
  if (!content) return
  saving.value = true
  error.value = ''
  try {
    await createNote(props.bankId, {
      questionId: props.questionId ? Number(props.questionId) : null,
      content,
      source: 'user'
    })
    draft.value = ''
    ElMessage.success(t('saved'))
    await load()
    emit('changed')
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
    emit('changed')
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
    emit('changed')
  } catch (e) {
    error.value = e?.response?.data?.message || t('fail')
  }
}

defineExpose({ reload: load, openUp: () => { open.value = true; load() } })
</script>

<style scoped>
.qn {
  margin-top: 8px;
}
.qn-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px dashed var(--border-strong);
  background: transparent;
  border-radius: 12px;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.qn-toggle.empty {
  color: var(--text-muted);
}
.qn-body {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--bg-card);
}
.qn-item {
  padding: 6px 0;
  border-bottom: 1px dashed var(--border);
}
.qn-item:last-of-type {
  border-bottom: none;
}
.qn-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 3px;
}
.qn-tag {
  font-size: 11px;
  border: 1px solid currentColor;
  border-radius: 10px;
  padding: 0 6px;
  color: var(--text-muted);
}
.qn-tag.ai {
  color: var(--accent-text);
}
.qn-time {
  font-size: 11px;
}
.qn-act {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  padding: 0 4px;
}
.qn-act + .qn-act {
  margin-left: 0;
}
.qn-act.danger {
  color: var(--danger);
}
.qn-text {
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}
.qn-edit-btns,
.qn-new-btns {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
}
.qn-hint {
  flex: 1;
  font-size: 11.5px;
}
.qn-input {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 8px;
  font-size: 13px;
  font-family: inherit;
  line-height: 1.7;
  background: var(--bg-elev);
  color: var(--text-primary);
  resize: vertical;
}
.qn-new {
  margin-top: 8px;
}
.qn-error {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--danger);
}
</style>
