<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="brand">
        <span class="brand-seal" role="img" aria-label="拾题">
          <span class="seal-char">拾</span>
          <span class="seal-char">题</span>
        </span>
        <span class="brand-sub">{{ t('brandSub') }}</span>
      </div>

      <nav class="nav">
        <RouterLink
          to="/"
          class="nav-item"
          :class="{ active: isBankActive }"
          :title="sidebarCollapsed ? t('nav.banks') : undefined"
        >
          <TikuIcon name="book" :size="16" />
          <span>{{ t('nav.banks') }}</span>
        </RouterLink>
        <RouterLink
          to="/stats"
          class="nav-item"
          :class="{ active: route.name === 'stats' }"
          :title="sidebarCollapsed ? t('nav.stats') : undefined"
        >
          <TikuIcon name="chart" :size="16" />
          <span>{{ t('nav.stats') }}</span>
        </RouterLink>
        <RouterLink
          to="/discover"
          class="nav-item"
          :class="{ active: route.name === 'discover' }"
          :title="sidebarCollapsed ? t('nav.discover') : undefined"
        >
          <TikuIcon name="search" :size="16" />
          <span>{{ t('nav.discover') }}</span>
        </RouterLink>
        <RouterLink
          to="/my-works"
          class="nav-item"
          :class="{ active: route.name === 'my-works' }"
          :title="sidebarCollapsed ? t('nav.myWorks') : undefined"
        >
          <TikuIcon name="upload" :size="16" />
          <span>{{ t('nav.myWorks') }}</span>
        </RouterLink>
        <RouterLink
          to="/settings"
          class="nav-item"
          :class="{ active: route.name === 'settings' }"
          :title="sidebarCollapsed ? t('nav.settings') : undefined"
        >
          <TikuIcon name="settings" :size="16" />
          <span>{{ t('nav.settings') }}</span>
        </RouterLink>
        <RouterLink
          to="/ai-import/jobs"
          class="nav-item"
          :class="{ active: route.name === 'ai-import-jobs' }"
          :title="sidebarCollapsed ? t('nav.aiJobs') : undefined"
        >
          <TikuIcon name="list" :size="16" />
          <span>{{ t('nav.aiJobs') }}</span>
        </RouterLink>
      </nav>

      <!-- AI 任务全局监控：进行中徽标（点击进入预览） -->
      <button v-if="activeAiJobs.length && !sidebarCollapsed" class="ai-badge" :title="t('viewAiTip')" @click="goActiveJob">
        <span class="ai-spin"><TikuIcon name="refresh" :size="12" /></span>
        {{ t('aiWorkingN', { n: activeAiJobs.length }) }}
      </button>

      <!-- 最近 AI 导入（未确认任务常驻入口：通知被错过 / 预览页返回后仍可回到任务） -->
      <div v-if="recentJobs.length && !sidebarCollapsed" class="ai-recent">
        <div class="ai-recent-head">
          <span class="ai-recent-title">{{ t('recentAi') }}</span>
          <button class="ai-recent-all" :title="t('viewAllAi')" @click="router.push('/ai-import/jobs')">{{ t('allTasks') }}</button>
        </div>
        <div v-for="job in recentJobs" :key="job.id" class="ai-recent-item">
          <button
            class="ai-badge"
            :class="{ failed: job.status === 'FAILED' }"
            :title="t('backToTask') + ' #' + job.id"
            @click="router.push(`/ai-import/${job.id}`)"
          >
            <TikuIcon :name="job.status === 'SUCCESS' ? 'file' : job.status === 'FAILED' ? 'x' : 'refresh'" :size="12" />
            <span class="last-job-text">
              <template v-if="job.status === 'SUCCESS'">{{ t('aiPending') }}：{{ job.fileName }}（{{ job.questions?.length || 0 }} {{ t('qUnit') }}）</template>
              <template v-else-if="job.status === 'FAILED'">{{ t('aiFailed') }}：{{ job.fileName }}</template>
              <template v-else>{{ t('aiWorking') }}：{{ job.fileName }}</template>
            </span>
          </button>
          <button class="ai-delete" :title="t('deleteTask')" @click="removeRecentJob(job)">
            <TikuIcon name="x" :size="11" />
          </button>
        </div>
      </div>

      <!-- 折叠时：AI 进行中指示（图标级） -->
      <button v-if="activeAiJobs.length && sidebarCollapsed" class="ai-badge ai-badge-mini" :title="t('aiWorkingView')" @click="goActiveJob">
        <span class="ai-spin"><TikuIcon name="refresh" :size="14" /></span>
      </button>

      <div class="sidebar-foot">
        <span v-if="!sidebarCollapsed" class="text-muted">{{ t('offlineNote') }}</span>
        <button
          v-if="!sidebarCollapsed"
          class="theme-toggle"
          :title="t('themeToggleTitle', { v: isDark ? t('theme.dark') : t('theme.light') })"
          @click="toggleTheme"
        >
          <span class="theme-dot" :class="{ on: isDark }"></span>
          {{ t('theme.label') }}:{{ isDark ? t('theme.dark') : t('theme.light') }}
        </button>
        <button
          v-if="sidebarCollapsed"
          class="sidebar-toggle"
          :title="t('expandSidebar')"
          @click="sidebarCollapsed = false"
        >
          <TikuIcon name="chevron-right" :size="15" />
        </button>
      </div>
    </aside>

    <main class="main" :class="{ collapsed: sidebarCollapsed, compact: isPractice }">
      <!-- key=fullPath：同一路由组件在不同 id/query 间导航时强制重建（如 另存新题库后跳到 /banks/{newId} 要重新加载） -->
      <!-- key=path：跨题库跳转（path 不同）强制重建避免残留旧库内容；
           query 变化（如做题页 at= 位置同步）不重建，避免每次切题整页重挂载丢键盘输入 -->
      <RouterView :key="route.path" />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getAiImportJob, getRecentAiJobs, listActiveAiJobs, deleteAiJob } from '../api/aiImport'
import { ElMessage, ElMessageBox } from 'element-plus'
import TikuIcon from '../components/TikuIcon.vue'
import { currentTheme, setTheme } from '../utils/theme'

/* 导航/页脚静态文案（中英；组件内局部字典，随语言切换即时生效） */
const { t } = useI18n({
  messages: {
    'zh-CN': {
      brandSub: '自建题库',
      viewAiTip: '点击查看 AI 导入任务', aiWorkingN: 'AI 整理中（{n}）', recentAi: '最近 AI 导入', viewAllAi: '查看全部 AI 任务', allTasks: '全部任务', backToTask: '回到任务', aiPending: 'AI 导入待确认', aiFailed: 'AI 导入失败', aiWorking: 'AI 整理中', deleteTask: '删除任务', qUnit: '题', aiWorkingView: 'AI 整理中，点击查看',
      nav: { banks: '题库', stats: '统计', discover: '发现题库', myWorks: '我的作品', settings: '设置', aiJobs: 'AI 任务' },
      offlineNote: '离线优先 · 数据在本机',
      theme: { label: '主题', light: '白天', dark: '黑夜' },
      themeToggleTitle: '点击切换主题(当前: {v})',
      expandSidebar: '展开侧边栏',
      delAskCancel: '将取消任务「{name}」的处理（取消后可再次删除彻底清理），确定吗？',
      delAskDelete: '将删除任务「{name}」及其整理结果，确定吗？',
      delTitle: '删除 AI 导入任务',
      taskCanceled: '已取消任务',
      taskDeleted: '任务已删除'
    },
    'en-US': {
      brandSub: 'Your Question Banks',
      viewAiTip: 'View AI import tasks', aiWorkingN: 'AI working ({n})', recentAi: 'Recent AI imports', viewAllAi: 'View all AI tasks', allTasks: 'All tasks', backToTask: 'Back to task', aiPending: 'AI import pending', aiFailed: 'AI import failed', aiWorking: 'AI working', deleteTask: 'Delete task', qUnit: 'q', aiWorkingView: 'AI working — click to view',
      nav: { banks: 'Banks', stats: 'Stats', discover: 'Discover', myWorks: 'My works', settings: 'Settings', aiJobs: 'AI Jobs' },
      offlineNote: 'Offline-first · data stays on this device',
      theme: { label: 'Theme', light: 'Light', dark: 'Dark' },
      themeToggleTitle: 'Toggle theme (current: {v})',
      expandSidebar: 'Expand sidebar',
      delAskCancel: 'This will cancel processing of task “{name}” (you can delete it completely afterward). Proceed?',
      delAskDelete: 'This will delete task “{name}” and its results. Proceed?',
      delTitle: 'Delete AI import task',
      taskCanceled: 'Task canceled',
      taskDeleted: 'Task deleted'
    }
  }
})

const route = useRoute()
const router = useRouter()

/* 双主题快捷切换(规范 v2.1 §4.10:系统偏好见 utils/theme.js,三态面板在设置页 P1) */
const isDark = ref(currentTheme() === 'dark')
function toggleTheme() {
  isDark.value = setTheme(isDark.value ? 'light' : 'dark') === 'dark'
}
// “题库”导航在列表页与所有 /banks/* 子页均高亮
const isBankActive = computed(() => route.name === 'bank-list' || route.path.startsWith('/banks'))
// 做题页：全屏紧凑模式（去掉页面内边距，让做题内容占比最大化）
const isPractice = computed(() => route.name === 'practice')

/* ---------- 侧边栏自动收缩：题库详情 / 做题 / 练习回顾页折叠为窄图标栏；
   视口过窄（<1100px）时强制折叠，避免小窗口布局错位 ---------- */
const sidebarCollapsed = ref(false)
const routeCollapses = ref(false)
const NARROW_BREAKPOINT = 1100

watch(
  () => route.path,
  (path) => {
    // /banks/:id、/banks/:id/practice、/banks/:id/sessions → 折叠
    routeCollapses.value = /^\/banks\/\d+(\/(practice|sessions))?$/.test(path)
    sidebarCollapsed.value = routeCollapses.value || isNarrowViewport()
  },
  { immediate: true }
)

function isNarrowViewport() {
  return typeof window !== 'undefined' && window.innerWidth < NARROW_BREAKPOINT
}
let resizeTimer = null
function onViewportResize() {
  clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    //窄视口强制折叠（覆盖用户手动展开，避免错位）；宽视口回到路由决定
    if (isNarrowViewport() || routeCollapses.value) {
      sidebarCollapsed.value = true
    }
  }, 120)
}
/* ---------- AI 任务全局监控（任意页面生效） ---------- */
// 5s 轮询：进行中任务徽标 + 完成后系统通知 + 最近未确认任务入口（后端权威数据）
const activeAiJobs = ref([])
const recentJobs = ref([])
const seenJobs = new Map() // jobId -> { notified }
let initialized = false
let permissionRequested = false
let activeTimer = null

async function refreshActive() {
  try {
    // 最近未确认任务（含进行中/完成/失败，未确认前保留入口）
    const recent = await getRecentAiJobs(3)
    recentJobs.value = recent || []
  } catch (e) {
    /* 接口未就绪时静默（不阻塞其他功能） */
  }
  try {
    const jobs = await listActiveAiJobs()
    activeAiJobs.value = jobs || []
    const currentIds = new Set(activeAiJobs.value.map((j) => j.id))

    if (!initialized) {
      // 首轮只记录当前进行中的任务，不通知（避免对旧任务误报）
      for (const j of activeAiJobs.value) seenJobs.set(j.id, { notified: false })
      initialized = true
      if (activeAiJobs.value.length) requestNotifyPermission()
      return
    }

    for (const j of activeAiJobs.value) {
      if (!seenJobs.has(j.id)) {
        seenJobs.set(j.id, { notified: false })
        requestNotifyPermission()
      }
    }
    // 从 active 消失的任务 → 查询最终状态 → 系统通知（notifyJobFinished 内跳过 CANCELED）
    for (const [jobId, rec] of seenJobs) {
      if (!currentIds.has(jobId) && !rec.notified) {
        rec.notified = true
        notifyJobFinished(jobId)
      }
    }
  } catch (e) {
    /* 后端不可用时静默 */
  }
}

async function notifyJobFinished(jobId) {
  let job
  try {
    job = await getAiImportJob(jobId)
  } catch (e) {
    return
  }
  //用户主动取消（含预览页/侧边栏取消）：不弹"失败"通知
  if (job.status === 'CANCELED') return
  const success = job.status === 'SUCCESS'
  showNotification(
    success ? 'AI 整理完成' : 'AI 整理失败',
    success ? `共 ${job.questions?.length || 0} 题，点击查看预览` : (job.error || '处理失败，点击查看详情'),
    jobId
  )
}

function showNotification(title, body, jobId) {
  if (!('Notification' in window)) return
  if (Notification.permission === 'granted') {
    const n = new Notification(title, { body })
    n.onclick = () => {
      window.focus()
      router.push(`/ai-import/${jobId}`)
      n.close()
    }
  } else if (Notification.permission === 'default') {
    Notification.requestPermission().then((p) => {
      if (p === 'granted') showNotification(title, body, jobId)
    })
  }
}

function requestNotifyPermission() {
  if (permissionRequested) return
  permissionRequested = true
  if ('Notification' in window && Notification.permission === 'default') {
    Notification.requestPermission().catch(() => {})
  }
}

function goActiveJob() {
  const job = activeAiJobs.value[0]
  if (job) router.push(`/ai-import/${job.id}`)
}

/* 删除/取消最近任务（进行中 = 标记取消；已完成/失败 = 直接删除） */
async function removeRecentJob(job) {
  const active = job.status === 'PENDING' || job.status === 'PROCESSING'
  try {
    await ElMessageBox.confirm(
      active
        ? t('delAskCancel', { name: job.fileName })
        : t('delAskDelete', { name: job.fileName }),
      t('delTitle'),
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (e) {
    return // 用户取消
  }
  try {
    await deleteAiJob(job.id)
    recentJobs.value = recentJobs.value.filter((j) => j.id !== job.id)
    ElMessage.success(active ? t('taskCanceled') : t('taskDeleted'))
  } catch (e) {
    /* 拦截器已提示 */
  }
}

onMounted(() => {
  refreshActive()
  activeTimer = setInterval(refreshActive, 5000)
  window.addEventListener('resize', onViewportResize)
})
onUnmounted(() => {
  clearInterval(activeTimer)
  clearTimeout(resizeTimer)
  window.removeEventListener('resize', onViewportResize)
})
</script>

<style scoped>
.app-shell {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  width: 220px;
  display: flex;
  flex-direction: column;
  padding: 20px 14px;
  background: var(--bg-elev);
  border-right: 1px solid var(--border);
  z-index: 10;
  transition: width 200ms ease;
  overflow: hidden;
}
.sidebar.collapsed {
  width: 64px;
  padding: 20px 8px;
  align-items: center;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 10px 22px;
  white-space: nowrap;
  transition: padding var(--ease);
}
.sidebar.collapsed .brand {
  padding: 4px 0 22px;
}
/* 品牌印（与官网 nav 同构）：朱泥白文方印，竖排"拾题"。
   印泥红为恒定本色（不随主题漂移——印章盖出来就是同一方印） */
.brand-seal {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  background: #c3272b;
  color: #fdfcf7;
  border: 1px solid #9e1f22;
}
.brand-seal .seal-char {
  width: 26px;
  height: 13px;
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: 12px;
  line-height: 13px;
  text-align: center;
  letter-spacing: 0;
  user-select: none;
}
.brand-sub {
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 0.2em;
  transition: opacity var(--ease);
}
.sidebar.collapsed .brand-sub {
  opacity: 0;
  width: 0;
  overflow: hidden;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 9px;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  transition: background var(--ease), color var(--ease);
  white-space: nowrap;
}
.sidebar.collapsed .nav-item {
  justify-content: center;
  padding: 9px 0;
}
.sidebar.collapsed .nav-item span {
  display: none;
}
.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent-text);
}

.sidebar-foot {
  margin-top: auto;
  padding: 10px 12px 0;
  font-size: 12px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

/* 双主题快捷切换 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--ease);
}
.theme-toggle:hover {
  border-color: var(--accent);
  color: var(--text-primary);
}
.theme-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-muted);
  transition: background var(--ease), box-shadow var(--ease);
}
.theme-dot.on {
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
}

/* AI 任务进行中徽标 */
.ai-badge {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 6px 0;
  padding: 8px 12px;
  border: 1px solid var(--accent);
  border-radius: 9px;
  background: var(--accent-soft);
  color: var(--accent-text);
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--ease);
  text-align: left;
  width: calc(100% - 12px);
}
.ai-badge:hover {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
}
.ai-badge.last-job.failed {
  border-color: var(--danger);
  background: var(--danger-soft);
  color: var(--danger);
}
.ai-badge.last-job.failed:hover {
  border-color: var(--danger);
}
.last-job-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-recent {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
}
.ai-recent-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px 0 12px;
}
.ai-recent-title {
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 0.02em;
}
.ai-recent-all {
  border: none;
  background: transparent;
  color: var(--accent, #409eff);
  font-size: 11px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 5px;
}
.ai-recent-all:hover {
  background: var(--bg-hover);
}
.ai-recent-item {
  position: relative;
  margin: 0 6px;
}
.ai-recent-item .ai-badge {
  margin: 0;
  width: 100%;
  padding-right: 30px;
}
.ai-delete {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--ease);
}
.ai-delete:hover {
  background: var(--danger-soft);
  color: var(--danger);
}
.ai-spin {
  display: inline-flex;
  animation: ai-spin 1.2s linear infinite;
}
@keyframes ai-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 折叠态：AI 进行中图标徽标 */
.ai-badge-mini {
  margin: 14px 0 0;
  width: 40px;
  justify-content: center;
  padding: 8px 0;
  width: calc(100% - 0px);
}

/* 折叠态展开按钮 */
.sidebar-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--bg-card);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--ease);
}
.sidebar-toggle:hover {
  border-color: var(--accent);
  color: var(--accent-text);
}

.main {
  flex: 1;
  margin-left: 220px;
  min-width: 0;
  /* 桌面 100% 全屏（1920）下内容饱满：展开态与折叠态（.collapsed）统一 1440 限宽，
     减少右侧大段留白（1080 → 1440） */
  max-width: 1440px;
  padding: 40px 48px 64px;
  transition: margin-left 200ms ease;
}
.main.collapsed {
  margin-left: 64px;
  /* 侧栏折叠腾出的空间给内容：详情/历史等折叠页同样 1440；悬浮题号盘/答题卡显示断点
     已随之联动（>1680 常显，窄于则收成胶囊按钮） */
  max-width: 1440px;
}
/* 做题页：全屏紧凑（无内边距、不限宽，页面内部自行分栏） */
.main.compact {
  padding: 0;
  max-width: none;
}
</style>
