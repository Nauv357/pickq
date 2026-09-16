<template>
  <div class="page">
    <!-- ============ 回顾视图（与做完题的成绩页共用 SessionReview：逐题部分一字不差） ============ -->
    <template v-if="viewSession">
      <PageHeader :title="t('title')" :back-to="`/banks/${id}`" :back-text="t('backToBank')">
        <template #title-extra>
          <span class="mode-tag">{{ MODE_LABELS[viewSession.mode] || viewSession.mode }}</span>
          <span class="status-tag" :class="viewSession.status === 'COMPLETED' ? 'status-done' : 'status-doing'">
            {{ viewSession.status === 'COMPLETED' ? t('completed') : t('running') }}
          </span>
        </template>
        <template #meta>
          <span>{{ t('timeUsed') }} {{ formatDuration(viewSession.totalSeconds) }}</span>
          <span class="dot"></span>
          <span>{{ formatDate(viewSession.createdAt) }}</span>
        </template>
        <template #actions>
          <button v-if="viewSession.status !== 'COMPLETED'" class="btn btn-primary" @click="continuePractice">
            <TikuIcon name="play" :size="14" />
            {{ t('continuePractice') }}
          </button>
          <button v-else class="btn btn-secondary btn-sm" @click="backToList">{{ t('backToList') }}</button>
        </template>
      </PageHeader>

      <SessionReview :session="viewSession" :bank-id="id" @changed="reloadReview" />
    </template>

    <!-- ============ 历史列表 ============ -->
    <template v-else>
      <PageHeader
        :title="t('historyTitle')"
        :desc="t('sessionsTotal', { n: total })"
        :back-to="`/banks/${id}`"
        :back-text="t('backToBank')"
      />

      <div v-if="loading" class="sess-list">
        <div v-for="n in 4" :key="n" class="sess-row tiku-skeleton">
          <div class="sk-line" style="width: 25%"></div>
          <div class="sk-line" style="width: 40%"></div>
        </div>
      </div>

      <EmptyState v-else-if="sessions.length === 0" icon="clock" :title="t('emptyTitle')" :desc="t('emptyTip')">
        <button class="btn btn-primary" @click="$router.push(`/banks/${id}`)">{{ t('goPractice') }}</button>
      </EmptyState>

      <div v-else class="sess-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="sess-row"
          @click="openReview(s.id)"
        >
          <span class="mode-tag">{{ MODE_LABELS[s.mode] || s.mode }}</span>
          <span class="status-tag" :class="s.status === 'COMPLETED' ? 'status-done' : 'status-doing'">
            {{ s.status === 'COMPLETED' ? t('completed') : t('running') }}
          </span>
          <span class="sess-score mono">{{ formatScore(s.totalScore) }} / {{ formatScore(s.maxScore) }} {{ t('unitPoint') }}</span>
          <span class="sess-answer text-muted mono">{{ t('correctOf', { a: s.correctCount, b: s.answeredCount }) }}</span>
          <span class="sess-time text-muted mono">{{ formatDuration(s.totalSeconds) }}</span>
          <span class="sess-date text-muted">{{ formatDate(s.createdAt) }}</span>
          <TikuIcon name="chevron-right" :size="14" class="sess-arrow" />
        </div>
      </div>

      <Pager :page="page" :size="pageSize" :total="total" @update:page="onPageChange" />
    </template>
  </div>
</template>

<script setup>
/**
 * 练习历史：列表 + 回顾。
 *
 * 回顾部分**不再自己实现**：逐题回顾由 `SessionReview` 渲染（与刚做完题的成绩页同一个组件、
 * 同一份数据 `GET /sessions/{id}`），所以"刚做完能看到的题干/选项/解析，历史里也一模一样"。
 * 2026-09-16 用户反馈：两套实现导致同一场练习在两个入口看到的东西不一样。
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      backToBank: '返回题库', title: '练习回顾', completed: '已完成', running: '进行中',
      correctOf: '答对 {a} / {b}', timeUsed: '用时', continuePractice: '继续做题', backToList: '返回列表',
      unitPoint: '分',
      historyTitle: '练习历史', sessionsTotal: '共 {n} 次练习',
      emptyTitle: '还没有练习记录', emptyTip: '完成一轮练习后，这里会保留每次的得分与逐题回顾', goPractice: '去练习'
    },
    'en-US': {
      backToBank: 'Back to bank', title: 'Session Review', completed: 'Completed', running: 'In progress',
      correctOf: '{a} / {b} correct', timeUsed: 'Time', continuePractice: 'Continue practicing', backToList: 'Back to list',
      unitPoint: 'pts',
      historyTitle: 'Practice history', sessionsTotal: '{n} sessions',
      emptyTitle: 'No practice sessions yet', emptyTip: 'After you finish a round, each session keeps its score and per-question review', goPractice: 'Start practicing'
    }
  }
})

import { useRoute, useRouter } from 'vue-router'
import { getSessionDetail, listSessions } from '../api/sessions'
import { formatDate, formatScore } from '../utils/format'
import TikuIcon from '../components/TikuIcon.vue'
import SessionReview from '../components/SessionReview.vue'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import Pager from '../components/Pager.vue'

const route = useRoute()
const router = useRouter()
const id = route.params.id

const MODE_LABELS = {
  PLAN: '智能配题',
  ALL: '全部随机',
  SEQUENCE: '顺序刷题',
  TOPIC: '按试卷 / 章节',
  REVIEW: '复习队列',
  WRONG: '错题重做',
  FAVORITE: '收藏'
}

/* ---------- 历史列表 ---------- */
const sessions = ref([])
const loading = ref(true)
const page = ref(1)
const pageSize = 10
const total = ref(0)

async function loadSessions() {
  loading.value = true
  try {
    const data = await listSessions(id, { page: page.value, size: pageSize })
    sessions.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (e) {
    sessions.value = []
  } finally {
    loading.value = false
  }
}

function onPageChange(p) {
  page.value = p
  loadSessions()
}

/* ---------- 回顾 ---------- */
const viewSession = ref(null)

async function openReview(sessionId) {
  try {
    viewSession.value = await getSessionDetail(sessionId)
    // 同步 URL，方便刷新/分享
    router.replace({ path: `/banks/${id}/sessions`, query: { view: sessionId } })
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 自评赋分后会话分数会变：重新拉一次详情（同一个 data source，不另算一遍） */
async function reloadReview() {
  if (viewSession.value) await openReview(viewSession.value.id)
}

function backToList() {
  viewSession.value = null
  router.replace({ path: `/banks/${id}/sessions` })
}

function continuePractice() {
  if (!viewSession.value) return
  router.push({ path: `/banks/${id}/practice`, query: { sessionId: viewSession.value.id } })
}

function formatDuration(seconds) {
  if (seconds == null) return '—'
  const s = Math.max(0, seconds)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  return rest ? `${m} 分 ${rest} 秒` : `${m} 分钟`
}

onMounted(() => {
  // 总是预加载练习历史列表（防止从回顾返回列表时空白加载）
  loadSessions()
  // 支持 ?view=sessionId 直达回顾
  const viewId = route.query.view
  if (viewId) {
    openReview(Number(viewId))
  }
})
</script>

<style scoped>
/* 页头 / 空态 / 分页条 / 逐题回顾都用公共组件（PageHeader / EmptyState / Pager / SessionReview），
   这里只保留历史列表与页头小标签的样式 */
.dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--text-muted);
}

.mode-tag {
  font-size: 12px;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 2px 10px;
  flex-shrink: 0;
}
.status-tag {
  font-size: 12px;
  border-radius: 999px;
  padding: 2px 10px;
  flex-shrink: 0;
}
.status-done {
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success);
}
.status-doing {
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning);
}

/* 列表 */
.sess-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sess-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease);
}
.sess-row:hover {
  background: var(--bg-hover);
  border-color: var(--border-strong);
}
.sess-score {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.sess-answer,
.sess-time,
.sess-date {
  font-size: 12px;
  flex-shrink: 0;
}
.sess-date {
  margin-left: auto;
}
.sess-arrow {
  color: var(--text-muted);
  flex-shrink: 0;
}
.sess-row:hover .sess-arrow {
  color: var(--accent-text);
  transform: translateX(2px);
  transition: all var(--ease);
}

/* 其他 */
.sk-line {
  height: 12px;
  border-radius: 6px;
  background: var(--bg-hover);
  flex: 1;
}
</style>
