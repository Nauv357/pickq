<template>
  <div class="page ai-jobs-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">AI 任务</h1>
        <p class="page-sub">
          所有尚未确认导入的任务都会保留在这里（进行中 / 待确认 / 失败 / 已取消），
          随时回来继续预览修改，不会被新任务挤掉。
        </p>
      </div>
      <div class="page-actions">
        <span v-if="runningCount()" class="ai-running-tip">
          <span class="ai-spin"><TikuIcon name="refresh" :size="13" /></span>
          {{ runningCount() }} 个任务整理中
        </span>
      </div>
    </div>

    <div class="ai-jobs-card">
      <div v-if="loading" class="empty-tip text-muted">加载中…</div>

      <template v-else-if="jobs.length">
        <div v-for="job in jobs" :key="job.id" class="job-row" @click="openJob(job)">
          <div class="job-main">
            <span class="job-status" :class="statusClass(job)">
              <TikuIcon :name="statusIcon(job)" :size="14" />
            </span>
            <div class="job-info">
              <div class="job-name">
                {{ job.fileName }}
                <span v-if="job.fileType" class="job-type">{{ job.fileType }}</span>
              </div>
              <div class="job-meta">
                <span class="job-state" :class="statusClass(job)">{{ statusText(job) }}</span>
                <template v-if="job.status === 'PROCESSING'">
                  <span class="job-progress">{{ job.progress ?? 0 }}%</span>
                </template>
                <template v-else-if="job.status === 'SUCCESS'">
                  <span class="job-questions">{{ job.questions?.length ?? 0 }} 题</span>
                  <span v-if="job.processPath" class="job-flags job-path" :title="'处理方式：' + job.processPath">{{ job.processPath }}</span>
                  <span class="job-flags" v-if="job.thinking">思考</span>
                  <span class="job-flags" v-if="job.aiSupplement">补答</span>
                  <span v-if="fmtDur(job)" class="job-dur" title="单次处理时长">{{ fmtDur(job) }}</span>
                </template>
                <span class="job-time">{{ fmtTime(job.createdAt) }}</span>
              </div>
              <div v-if="job.status === 'FAILED' && job.error" class="job-error" :title="job.error">
                {{ job.error }}
              </div>
              <div v-else-if="job.status === 'PROCESSING'" class="job-bar">
                <div class="job-bar-inner" :style="{ width: (job.progress ?? 0) + '%' }"></div>
              </div>
            </div>
          </div>
          <div class="job-actions">
            <button
              v-if="job.status === 'SUCCESS' || job.status === 'PROCESSING' || job.status === 'PENDING'"
              class="btn btn-primary btn-sm"
              title="打开预览 / 继续整理"
              @click.stop="openJob(job)"
            >
              {{ job.status === 'SUCCESS' ? '继续预览' : '查看进度' }}
            </button>
            <button class="btn btn-ghost btn-sm danger" title="删除任务" @click.stop="removeJob(job)">
              删除
            </button>
          </div>
        </div>
      </template>

      <div v-else class="empty-state">
        <TikuIcon name="file" :size="40" />
        <p>暂无待确认的 AI 导入任务</p>
        <p class="text-muted">在任一题库页面点击「AI 追加」开始导入，任务会出现在这里</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteAiJob, getRecentAiJobs } from '../api/aiImport'
import TikuIcon from '../components/TikuIcon.vue'

const router = useRouter()
const jobs = ref([])
const loading = ref(true)
let timer = null

async function load() {
  try {
    const list = await getRecentAiJobs(50)
    jobs.value = list || []
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

const runningCount = () => jobs.value.filter((j) => j.status === 'PENDING' || j.status === 'PROCESSING').length

function statusIcon(job) {
  if (job.status === 'SUCCESS') return 'file'
  if (job.status === 'FAILED') return 'x'
  if (job.status === 'CANCELED') return 'x'
  if (job.status === 'PROCESSING') return 'refresh'
  return 'clock'
}
function statusText(job) {
  if (job.status === 'PENDING') return '排队中'
  if (job.status === 'PROCESSING') return '整理中'
  if (job.status === 'SUCCESS') return '待确认导入'
  if (job.status === 'FAILED') return '失败'
  if (job.status === 'CANCELED') return '已取消'
  return job.status || ''
}
function statusClass(job) {
  if (job.status === 'SUCCESS') return 'st-success'
  if (job.status === 'FAILED' || job.status === 'CANCELED') return 'st-failed'
  if (job.status === 'PROCESSING' || job.status === 'PENDING') return 'st-running'
  return ''
}
function fmtTime(iso) {
  if (!iso) return ''
  const d = new Date(String(iso).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16)
  const now = Date.now()
  const diff = now - d.getTime()
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 3600 * 1000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < 24 * 3600 * 1000) return `${Math.floor(diff / 3600000)} 小时前`
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/* 单次处理时长（finishedAt - createdAt → "7 分 12 秒" / "45 秒"） */
function fmtDur(job) {
  if (!job?.createdAt || !job?.finishedAt) return ''
  const s = new Date(String(job.createdAt).replace(' ', 'T')).getTime()
  const e = new Date(String(job.finishedAt).replace(' ', 'T')).getTime()
  if (Number.isNaN(s) || Number.isNaN(e) || e < s) return ''
  const sec = Math.round((e - s) / 1000)
  if (sec < 60) return `${sec} 秒`
  const m = Math.floor(sec / 60)
  const r = sec % 60
  return r ? `${m} 分 ${r} 秒` : `${m} 分钟`
}

function openJob(job) {
  if (job.status === 'FAILED' || job.status === 'CANCELED') return
  router.push(`/ai-import/${job.id}`)
}

async function removeJob(job) {
  const active = job.status === 'PENDING' || job.status === 'PROCESSING'
  try {
    await ElMessageBox.confirm(
      active
        ? `将取消任务「${job.fileName}」的处理（取消后可再次删除彻底清理），确定吗？`
        : `将删除任务「${job.fileName}」及其整理结果，确定吗？`,
      '删除 AI 导入任务',
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
    jobs.value = jobs.value.filter((j) => j.id !== job.id)
    ElMessage.success(active ? '已取消任务' : '任务已删除')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 5000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.page {
  padding: 22px 26px;
  max-width: 860px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-title {
  font-size: 20px;
  font-weight: 700;
  margin: 0 0 6px;
}
.page-sub {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
  max-width: 560px;
  line-height: 1.6;
}
.ai-running-tip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--accent, #409eff);
  padding: 6px 12px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent, #409eff) 10%, transparent);
  white-space: nowrap;
}
.ai-spin {
  display: inline-flex;
  animation: ai-rotate 1.2s linear infinite;
}
@keyframes ai-rotate {
  to {
    transform: rotate(360deg);
  }
}
.ai-jobs-card {
  background: var(--bg-card, #fff);
  border: 1px solid var(--border-color, #eee);
  border-radius: 12px;
  overflow: hidden;
}
.job-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  cursor: pointer;
  transition: background 0.15s;
}
.job-row:hover {
  background: var(--bg-hover, #f5f6f7);
}
.job-row + .job-row {
  border-top: 1px solid var(--border-color, #f0f0f0);
}
.job-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
  flex: 1;
}
.job-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  flex-shrink: 0;
  margin-top: 2px;
}
.job-status.st-running {
  color: var(--accent, #409eff);
  background: color-mix(in srgb, var(--accent, #409eff) 12%, transparent);
}
.job-status.st-running svg {
  animation: ai-rotate 1.2s linear infinite;
}
.job-status.st-success {
  color: #52a552;
  background: color-mix(in srgb, #52a552 12%, transparent);
}
.job-status.st-failed {
  color: #d64545;
  background: color-mix(in srgb, #d64545 10%, transparent);
}
.job-info {
  min-width: 0;
  flex: 1;
}
.job-name {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.job-type {
  font-size: 11px;
  color: var(--text-muted);
  border: 1px solid var(--border-color, #ddd);
  border-radius: 4px;
  padding: 0 5px;
  margin-left: 6px;
  vertical-align: 1px;
  font-weight: 400;
}
.job-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
  flex-wrap: wrap;
}
.job-state.st-success {
  color: #52a552;
}
.job-state.st-running {
  color: var(--accent, #409eff);
}
.job-state.st-failed {
  color: #d64545;
}
.job-flags {
  font-size: 11px;
  border: 1px solid var(--border-color, #ddd);
  border-radius: 4px;
  padding: 0 5px;
}
/* 实际处理路径标签（直传视觉等）：弱化描边，避免与思考/补答抢视觉 */
.job-path {
  border-color: var(--border);
  color: var(--text-secondary);
  background: var(--bg-elev, #f5f5f5);
}
.job-dur {
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}
.job-error {
  margin-top: 5px;
  font-size: 12px;
  color: #d64545;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}
.job-bar {
  margin-top: 7px;
  height: 4px;
  border-radius: 2px;
  background: var(--bg-elev, #ececec);
  overflow: hidden;
  max-width: 360px;
}
.job-bar-inner {
  height: 100%;
  border-radius: 2px;
  background: var(--accent, #409eff);
  transition: width 0.6s;
}
.job-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
}
.empty-state p {
  margin: 8px 0 0;
  font-size: 14px;
}
.empty-tip {
  padding: 40px;
  text-align: center;
}
</style>
