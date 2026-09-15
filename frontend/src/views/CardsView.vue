<template>
  <div class="cards-page">
    <header class="cd-head">
      <button class="btn btn-ghost btn-sm" @click="$router.push(`/banks/${id}`)">
        <TikuIcon name="chevron-left" :size="14" />
        {{ t('backToBank') }}
      </button>
      <h1 class="cd-title">{{ t('title') }}</h1>
      <span class="text-muted cd-sub">{{ bank?.name }}</span>
      <span class="grow"></span>
      <span v-if="stats" class="text-muted cd-stats">
        {{ t('stats', { total: stats.total, due: stats.due, unconfirmed: stats.unconfirmed }) }}
      </span>
      <button class="btn btn-secondary btn-sm" :disabled="generating" @click="generate">
        <TikuIcon name="sparkle" :size="14" />
        {{ generating ? t('generating') : t('generate') }}
      </button>
    </header>

    <p class="cd-note text-muted">{{ t('note') }}</p>

    <!-- 复习模式 -->
    <section v-if="reviewMode" class="card cd-review">
      <div class="cd-review-head">
        <span class="text-muted">{{ t('reviewProgress', { done: reviewedCount, total: queue.length }) }}</span>
        <span class="grow"></span>
        <button class="btn btn-ghost btn-sm" @click="exitReview">{{ t('exitReview') }}</button>
      </div>
      <template v-if="current">
        <div class="cd-front">{{ current.front }}</div>
        <div v-if="revealed" class="cd-back">
          <span class="text-muted">{{ t('answer') }}</span>{{ current.back }}
        </div>
        <div class="cd-review-actions">
          <button v-if="!revealed" class="btn btn-primary" @click="revealed = true">{{ t('showAnswer') }}</button>
          <template v-else>
            <button class="btn btn-secondary" @click="answer(false)">{{ t('forgot') }}</button>
            <button class="btn btn-primary" @click="answer(true)">{{ t('remembered') }}</button>
          </template>
          <span class="text-muted cd-origin">{{ t('origin', { n: current.questionNumber ?? '?' }) }}</span>
        </div>
      </template>
      <p v-else class="text-muted cd-done">{{ t('reviewDone') }}</p>
    </section>

    <!-- 卡片管理 -->
    <section v-else class="card cd-list-card">
      <div class="cd-list-head">
        <button
          v-for="f in filters"
          :key="f.value"
          class="cd-chip"
          :class="{ on: filter === f.value }"
          @click="setFilter(f.value)"
        >
          {{ f.label }}
        </button>
        <span class="grow"></span>
        <button class="btn btn-primary btn-sm cd-start" :disabled="dueCards.length === 0" @click="startReview">
          {{ dueCards.length ? t('startReview', { n: dueCards.length }) : t('noDue') }}
        </button>
      </div>

      <p v-if="cards.length === 0" class="text-muted cd-empty">{{ t('empty') }}</p>
      <div v-else class="cd-list">
        <div v-for="c in cards" :key="c.id" class="cd-item" :class="{ unconfirmed: !c.confirmed }">
          <div class="cd-item-main">
            <div class="cd-item-front">{{ c.front }}</div>
            <div class="cd-item-back text-muted">{{ c.back }}</div>
            <div class="cd-item-meta text-muted">
              {{ t('origin', { n: c.questionNumber ?? '?' }) }}
              <template v-if="c.nodeName"> · {{ c.nodeName }}</template>
              <template v-if="!c.confirmed"> · {{ t('unconfirmedTip') }}</template>
              <template v-else> · {{ t('schedTip', { days: c.intervalDays, lapses: c.lapses }) }}</template>
            </div>
          </div>
          <div class="cd-item-actions">
            <button v-if="!c.confirmed" class="btn btn-primary btn-sm" @click="confirmOne(c)">{{ t('confirm') }}</button>
            <button class="btn btn-ghost btn-sm" @click="edit(c)">{{ t('edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="remove(c)">{{ t('delete') }}</button>
          </div>
        </div>
      </div>
    </section>

    <!-- 编辑卡片 -->
    <el-dialog v-model="editOpen" :title="t('editTitle')" width="min(92vw, 520px)" align-center>
      <div class="cd-form">
        <div class="field">
          <label class="field-label">{{ t('front') }}</label>
          <el-input v-model="editForm.front" type="textarea" :rows="2" maxlength="500" />
        </div>
        <div class="field">
          <label class="field-label">{{ t('backText') }}</label>
          <el-input v-model="editForm.back" type="textarea" :rows="2" maxlength="500" />
        </div>
        <p class="text-muted cd-tip">{{ t('editTip') }}</p>
      </div>
      <template #footer>
        <button class="btn btn-secondary" @click="editOpen = false">{{ t('cancel') }}</button>
        <button class="btn btn-primary" @click="saveEdit">{{ t('save') }}</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import TikuIcon from '../components/TikuIcon.vue'
import { getBank } from '../api/banks'
import { getSkillTemplates } from '../api/skills'
import {
  confirmCards, deleteCards, generateCards, getCardStats, getCards, getDueCards, reviewCard, updateCard
} from '../api/cards'

/**
 * 闪卡（阶段 3）：题目测"再认"，卡片测"回忆"。
 *
 * 交互取舍：
 * - **复习是"先想再翻"**：先只显示挖空提示，点「显示答案」才给答案，然后自评记得/忘了；
 * - **AI 生成的卡默认未确认**：未确认不排期，先让人过一眼（否则没校对的卡会占满复习队列）；
 * - 每张卡都能看到出处题号，方便回原题核对。
 */
const route = useRoute()
const id = route.params.id

const { t } = useI18n({
  messages: {
    'zh-CN': {
      title: '闪卡',
      backToBank: '返回题库',
      note: '卡片练的是「回忆」（题目练的是「再认」）。复习节奏与题目同一套：记得就拉长间隔，忘了立刻重来；答错卡片会让已过关的知识点回到待练状态。',
      stats: '共 {total} 张 · 到期 {due} · 待确认 {unconfirmed}',
      generate: '从解析生成',
      generating: '生成中…',
      genDone: '新生成 {n} 张卡（未确认，先看一遍再确认）',
      genTruncated: '已达到本次调用上限，可再次点击继续',
      startReview: '开始复习（{n} 张）',
      noDue: '今天没有到期的卡',
      reviewProgress: '第 {done} / {total} 张',
      exitReview: '退出复习',
      showAnswer: '显示答案',
      remembered: '记得',
      forgot: '忘了',
      answer: '答案：',
      origin: '出自第 {n} 题',
      reviewDone: '这一轮复习完了。记得的卡间隔会拉长，忘了的卡稍后还会出现。',
      empty: '还没有卡片。点右上角「从解析生成」，AI 会从有解析的题里提炼挖空卡。',
      filterAll: '全部 {n}',
      filterUnconfirmed: '待确认 {n}',
      filterDue: '到期 {n}',
      filterConfirmed: '已确认 {n}',
      unconfirmedTip: '待确认（不参与复习）',
      schedTip: '间隔 {days} 天 · 忘过 {lapses} 次',
      confirm: '确认',
      edit: '编辑',
      delete: '删除',
      editTitle: '编辑卡片',
      front: '提示（含 ____）',
      backText: '答案要点',
      editTip: '手动改过的卡会视为人工确认，直接进入复习队列。',
      save: '保存',
      cancel: '取消',
      saved: '已保存',
      confirmed: '已确认并进入复习队列',
      removed: '已删除',
      errNoAi: '尚未配置 AI 模型：请在「设置 → AI」里填好 Key 再回来。'
    },
    'en-US': {
      title: 'Flashcards',
      backToBank: 'Back',
      note: 'Cards train recall (questions train recognition). Scheduling matches questions: remembered stretches the interval, forgotten resets it; a failed card sends a cleared node back to practising.',
      stats: '{total} cards · {due} due · {unconfirmed} to confirm',
      generate: 'Generate from analysis',
      generating: 'Generating…',
      genDone: '{n} new cards (unconfirmed — review them first)',
      genTruncated: 'Reached this round’s limit — click again to continue',
      startReview: 'Review ({n})',
      noDue: 'Nothing due today',
      reviewProgress: 'Card {done} / {total}',
      exitReview: 'Exit',
      showAnswer: 'Show answer',
      remembered: 'Remembered',
      forgot: 'Forgot',
      answer: 'Answer: ',
      origin: 'from question {n}',
      reviewDone: 'Round finished. Remembered cards get longer intervals; forgotten ones come back soon.',
      empty: 'No cards yet. Use “Generate from analysis” to extract cloze cards from questions that have analysis.',
      filterAll: 'All {n}',
      filterUnconfirmed: 'To confirm {n}',
      filterDue: 'Due {n}',
      filterConfirmed: 'Confirmed {n}',
      unconfirmedTip: 'unconfirmed (not scheduled)',
      schedTip: 'interval {days}d · lapses {lapses}',
      confirm: 'Confirm',
      edit: 'Edit',
      delete: 'Delete',
      editTitle: 'Edit card',
      front: 'Prompt (with ____)',
      backText: 'Answer',
      editTip: 'Edited cards count as manually confirmed and enter the review queue.',
      save: 'Save',
      cancel: 'Cancel',
      saved: 'Saved',
      confirmed: 'Confirmed and scheduled',
      removed: 'Deleted',
      errNoAi: 'AI is not configured yet: set your key in Settings → AI.'
    }
  }
})

const bank = ref(null)
const stats = ref(null)
const cards = ref([])
const dueCards = ref([])
const filter = ref('all')
const generating = ref(false)
const templateId = ref('')

const reviewMode = ref(false)
const queue = ref([])
const queueIndex = ref(0)
const revealed = ref(false)
const reviewedCount = ref(0)
const current = computed(() => queue.value[queueIndex.value] || null)

const editOpen = ref(false)
const editForm = reactive({ id: null, front: '', back: '' })

const filters = computed(() => [
  { value: 'all', label: t('filterAll', { n: stats.value?.total ?? 0 }) },
  { value: 'unconfirmed', label: t('filterUnconfirmed', { n: stats.value?.unconfirmed ?? 0 }) },
  { value: 'due', label: t('filterDue', { n: stats.value?.due ?? 0 }) },
  { value: 'confirmed', label: t('filterConfirmed', { n: (stats.value?.total ?? 0) - (stats.value?.unconfirmed ?? 0) }) }
])

async function refresh() {
  try {
    const [s, list, due] = await Promise.all([
      getCardStats(Number(id)),
      getCards(Number(id), { status: filter.value === 'all' ? undefined : filter.value, templateId: templateId.value || undefined }),
      getDueCards(Number(id), { templateId: templateId.value || undefined })
    ])
    stats.value = s
    cards.value = list || []
    dueCards.value = due || []
  } catch (e) {
    /* 拦截器已提示 */
  }
}

function setFilter(value) {
  filter.value = value
  refresh()
}

async function generate() {
  generating.value = true
  try {
    const res = await generateCards(Number(id), { templateId: templateId.value || undefined, maxAiCalls: 5 })
    ElMessage[res?.truncated ? 'warning' : 'success'](
      res?.truncated ? t('genTruncated') : t('genDone', { n: res?.generated ?? 0 })
    )
    await refresh()
  } catch (e) {
    const msg = String(e?.message || '')
    ElMessage.error(/尚未配置 AI/.test(msg) ? t('errNoAi') : msg || t('genTruncated'))
  } finally {
    generating.value = false
  }
}

function startReview() {
  queue.value = [...dueCards.value]
  queueIndex.value = 0
  reviewedCount.value = 0
  revealed.value = false
  reviewMode.value = true
}

function exitReview() {
  reviewMode.value = false
  refresh()
}

async function answer(remembered) {
  const card = current.value
  if (!card) return
  try {
    await reviewCard(Number(id), card.id, remembered)
  } catch (e) {
    /* 拦截器已提示 */
  }
  reviewedCount.value++
  queueIndex.value++
  revealed.value = false
  if (queueIndex.value >= queue.value.length) {
    await refresh()
  }
}

async function confirmOne(card) {
  try {
    await confirmCards(Number(id), [card.id], true)
    ElMessage.success(t('confirmed'))
    await refresh()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

function edit(card) {
  editForm.id = card.id
  editForm.front = card.front
  editForm.back = card.back
  editOpen.value = true
}

async function saveEdit() {
  try {
    await updateCard(Number(id), editForm.id, { front: editForm.front, back: editForm.back })
    editOpen.value = false
    ElMessage.success(t('saved'))
    await refresh()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function remove(card) {
  try {
    await ElMessageBox.confirm(card.front, t('delete'), { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await deleteCards(Number(id), [card.id])
    ElMessage.success(t('removed'))
    await refresh()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

onMounted(async () => {
  try {
    const [bankData, tpls] = await Promise.all([getBank(id), getSkillTemplates()])
    bank.value = bankData
    const saved = localStorage.getItem('tiku.skillTemplateId')
    templateId.value = (tpls || []).find((x) => x.templateId === saved)?.templateId || tpls?.[0]?.templateId || ''
  } catch (e) {
    /* 拦截器已提示 */
  }
  await refresh()
})
</script>

<style scoped>
.cards-page {
  padding: 4px 2px 40px;
}
.cd-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.cd-title {
  font-size: 20px;
  margin: 0;
}
.cd-sub,
.cd-stats {
  font-size: 13px;
}
.grow {
  flex: 1;
}
.cd-note {
  font-size: 12px;
  margin: 6px 0 14px;
  line-height: 1.8;
}
.card {
  margin-bottom: 14px;
}
.cd-review-head,
.cd-list-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.cd-front {
  font-size: 17px;
  line-height: 1.9;
  padding: 18px 14px;
  border-radius: 10px;
  background: var(--bg-elev);
  min-height: 72px;
}
.cd-back {
  margin-top: 12px;
  font-size: 15px;
  line-height: 1.9;
  color: var(--success);
}
.cd-review-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}
.cd-origin {
  margin-left: auto;
  font-size: 12px;
}
.cd-done {
  font-size: 13px;
}
.cd-chip {
  border: 1px solid var(--border);
  background: var(--bg-card);
  border-radius: 14px;
  padding: 3px 12px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
}
.cd-chip.on {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.cd-empty {
  font-size: 13px;
  line-height: 1.8;
}
.cd-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 60vh;
  overflow: auto;
}
.cd-item {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
}
.cd-item.unconfirmed {
  border-style: dashed;
}
.cd-item-main {
  flex: 1;
  min-width: 0;
}
.cd-item-front {
  font-size: 14px;
}
.cd-item-back {
  font-size: 13px;
  margin-top: 2px;
}
.cd-item-meta {
  font-size: 12px;
  margin-top: 4px;
}
.cd-item-actions {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}
.cd-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cd-tip {
  font-size: 12px;
}
</style>
