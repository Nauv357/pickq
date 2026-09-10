<template>
  <!-- el-config-provider：Element Plus 组件文案随语言切换（弹窗/分页等） -->
  <el-config-provider :locale="elLocale">
    <router-view />

    <!-- 全局图片灯箱：点击富文本中的图片查看大图（托底行为；默认展示已尽量完整） -->
    <Teleport to="body">
      <div v-if="lightboxUrl" class="lightbox-mask" @click.self="closeLightbox">
        <div class="lightbox-toolbar">
          <span class="lightbox-name">{{ lightboxName }}</span>
          <div class="lightbox-actions">
            <button class="lb-btn" @click="toggleMode">{{ mode === 'fit' ? t('lbOriginal') : t('lbFit') }}</button>
            <button class="lb-btn" @click="zoom(-0.25)">−</button>
            <span class="lb-scale mono">{{ Math.round(scale * 100) }}%</span>
            <button class="lb-btn" @click="zoom(0.25)">+</button>
            <button class="lb-btn lb-close" @click="closeLightbox">{{ t('lbClose') }}</button>
          </div>
        </div>
        <div
          class="lightbox-viewport"
          :class="{ original: mode === 'original' }"
          @click.self="closeLightbox"
          @wheel.prevent="onWheel"
        >
          <img :src="lightboxUrl" :style="imgStyle" :alt="t('lbAlt')" />
        </div>
        <p class="lightbox-hint">{{ t('lbHint') }}</p>
      </div>
    </Teleport>

    <!-- 自动更新下载：点「立即更新」后直接下载安装（进度 + 停止下载） -->
    <Teleport to="body">
      <div v-if="updOpen" class="upd-mask" @click.self="cancelFlow">
        <div class="upd-card">
          <p class="upd-title">
            {{ updState === 'install' ? t('updInstalling') : t('updDownloading', { v: updVer }) }}
          </p>
          <div v-if="updState === 'download'" class="upd-progress">
            <div class="upd-bar"><div class="upd-fill" :style="{ width: updPct + '%' }"></div></div>
            <p class="upd-pct">{{ updPct }}%</p>
          </div>
          <p v-if="updState === 'install'" class="upd-tip">{{ t('updInstallTip') }}</p>
          <p v-else-if="updState === 'error'" class="upd-err">{{ updErr }}</p>
          <div class="upd-actions">
            <button v-if="updState === 'download'" class="upd-btn" @click="cancelFlow" :disabled="updCanceling">
              {{ updCanceling ? '…' : t('updStop') }}
            </button>
            <template v-else-if="updState === 'error'">
              <button class="upd-btn upd-btn-primary" @click="startDirectUpdate(updInfo)">{{ t('updRetry') }}</button>
              <button class="upd-btn" @click="closeUpdDialog">{{ t('updClose') }}</button>
            </template>
          </div>
        </div>
      </div>
    </Teleport>
  </el-config-provider>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { elLocale } from './i18n/lang'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      lbFit: '适应屏幕', lbOriginal: '原始大小', lbClose: '关闭', lbAlt: '放大查看', lbHint: '原始大小模式下可滚动 / 滚轮缩放查看细节 · 点击空白处关闭',
      updFound: '发现新版本 v{v}', updCurTo: '当前 v{cur} → 新版本 v{ver}', updNotesDef: '包含修复与改进。',
      updNow: '立即更新', updIgnore: '忽略此版本',
      updIgnored: '已忽略 v{v}，出现更新版本时会再提醒；也可随时在「设置」手动更新',
      updDownloading: '正在下载 v{v}…', updInstalling: '下载完成，正在安装…', updInstallTip: '安装完成后应用将自动重启，请稍候',
      updStop: '停止下载', updRetry: '重试', updClose: '关闭',
      updCanceled: '已取消下载；已下载部分已保留，下次会从断点继续',
      updCanceledButReady: '下载已完成，可在「设置」中手动更新'
    },
    'en-US': {
      lbFit: 'Fit to screen', lbOriginal: 'Original size', lbClose: 'Close', lbAlt: 'Zoom in', lbHint: 'In original size you can scroll / zoom with the wheel · click outside to close',
      updFound: 'Update available: v{v}', updCurTo: 'Current v{cur} → New version v{ver}', updNotesDef: 'Fixes and improvements.',
      updNow: 'Update now', updIgnore: 'Ignore this version',
      updIgnored: 'Ignored v{v}; you will be reminded when a newer version appears. You can also update manually in Settings anytime',
      updDownloading: 'Downloading v{v}…', updInstalling: 'Downloaded — installing…', updInstallTip: 'The app will restart automatically once the update is installed',
      updStop: 'Stop download', updRetry: 'Retry', updClose: 'Close',
      updCanceled: 'Download stopped; the partial file is kept and the next download resumes from where it left off',
      updCanceledButReady: 'Download finished — you can update manually in Settings'
    }
  }
})

import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import TikuIcon from './components/TikuIcon.vue'
import { isDesktop, checkForUpdate, getAppVersion, downloadUpdate, installUpdate, cancelUpdate } from './utils/updater'
import { openExternal } from './utils/external'

const router = useRouter()

const lightboxUrl = ref('')
const lightboxName = ref('')
const mode = ref('fit') // fit=适应屏幕 / original=原始大小(1:1)
const scale = ref(1)

const imgStyle = computed(() => {
  if (mode.value === 'fit') {
    return { maxWidth: '94vw', maxHeight: '84vh', width: 'auto', height: 'auto' }
  }
  return { transform: `scale(${scale.value})`, transformOrigin: 'top left' }
})

function openLightbox(url) {
  lightboxUrl.value = url
  lightboxName.value = url.split('/').pop() || ''
  mode.value = 'fit'
  scale.value = 1
}

function closeLightbox() {
  lightboxUrl.value = ''
}

function toggleMode() {
  mode.value = mode.value === 'fit' ? 'original' : 'fit'
  scale.value = 1
}

function zoom(delta) {
  scale.value = Math.min(3, Math.max(0.5, Math.round((scale.value + delta) * 100) / 100))
}

function onWheel(e) {
  if (mode.value !== 'original') return
  zoom(e.deltaY < 0 ? 0.1 : -0.1)
}

function onKeydown(e) {
  if (e.key === 'Escape') closeLightbox()
}

// 事件委托：① 外链 <a>（http/https 或 target=_blank）→ 系统浏览器打开（WebView2 无新窗口）；
// ② 点击任意富文本图片（rich-img）→ 放大查看（托底：默认展示已完整显示）
function onDocClick(e) {
  const a = e.target.closest?.('a')
  if (a) {
    const href = a.getAttribute('href') || ''
    if (a.target === '_blank' || /^https?:\/\//i.test(href)) {
      e.preventDefault()
      openExternal(href.startsWith('http') ? href : new URL(href, location.href).href)
      return
    }
  }
  const img = e.target.closest?.('img.rich-img')
  if (img && img.src) openLightbox(img.src)
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
  autoCheckUpdate()
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
})

/* ---------- 启动自动更新检查（仅桌面版；失败静默，不打扰） ----------
   行为：
   - 启动 5 秒后检查一次；有新版 → 居中弹窗（立即更新 / 忽略此版本 / 稍后）
   - 「立即更新」：直接下载安装（进度对话框 + 停止下载按钮；断点续传）
   - 「忽略此版本」：记住版本号，只有出现更新的版本才再次自动提醒
     （手动渠道不受影响：设置 → 检查更新随时可用）
   - 「稍后」（× / ESC）：本次不提醒，下次启动再弹 */
const SKIP_UPDATE_KEY = 'tiku:skip-update-version'
let autoChecked = false

/* 直接更新对话框状态 */
const updOpen = ref(false)
const updPct = ref(0)
const updVer = ref('')
const updState = ref('download') // download | install | error
const updErr = ref('')
const updCanceling = ref(false)
let updInfo = null
// 用户是否已主动取消并关闭对话框（取消后即使下载恰好完成也不自动安装）
let updAborted = false

function getSkippedVersion() {
  try {
    return localStorage.getItem(SKIP_UPDATE_KEY) || ''
  } catch {
    return ''
  }
}
function setSkippedVersion(v) {
  try {
    localStorage.setItem(SKIP_UPDATE_KEY, v)
  } catch {
    /* 忽略 */
  }
}

/** 立即更新：下载（进度 + 可停止）→ 安装重启。失败可重试 */
async function startDirectUpdate(info) {
  if (!info) return
  updInfo = info
  updVer.value = info.version
  updPct.value = 0
  updState.value = 'download'
  updErr.value = ''
  updOpen.value = true
  updAborted = false
  const res = await downloadUpdate(info, (p) => {
    updPct.value = p
  })
  // 用户已取消并关闭对话框：不再改动界面（若下载恰好已完成也不自动安装）
  if (updAborted) {
    if (res.ok) ElMessage.info(t('updCanceledButReady'))
    return
  }
  if (!res.ok) {
    if (String(res.error || '').includes('取消')) {
      // 用户停止下载：保留断点，关闭对话框
      updOpen.value = false
      ElMessage.info(t('updCanceled'))
    } else {
      updState.value = 'error'
      updErr.value = String(res.error || '')
    }
    return
  }
  // 下载完成 → 安装（进程随即退出，由更新脚本接管重启）
  updState.value = 'install'
  updOpen.value = true
  try {
    await installUpdate(info.version)
  } catch (e) {
    updState.value = 'error'
    updErr.value = String(e?.message || e || '')
  }
}

async function cancelFlow() {
  if (updCanceling.value) return
  updCanceling.value = true
  updAborted = true
  // 立即关闭对话框：不依赖后端返回（后端取消若延迟/失败，界面也不会卡死）
  updOpen.value = false
  ElMessage.info(t('updCanceled'))
  try {
    await cancelUpdate()
  } catch {
    /* 忽略：后端取消失败也不影响界面退出（已下载部分会保留） */
  }
  setTimeout(() => {
    updCanceling.value = false
  }, 1500)
}

function closeUpdDialog() {
  updOpen.value = false
}

async function autoCheckUpdate() {
  if (autoChecked || !isDesktop()) return
  autoChecked = true
  await new Promise((r) => setTimeout(r, 5000))
  try {
    const r = await checkForUpdate()
    if (!r.supported || r.error || !r.info) return
    // 用户曾忽略此版本：不再自动提醒
    if (getSkippedVersion() === r.info.version) return

    const currentVer = (await getAppVersion()) || ''
    const esc = (s) =>
      String(s || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
    // 弹窗内容限高滚动，避免更新说明过长把弹窗撑得很高
    const html = `<div style="font-size:13px;line-height:1.8">
        <p style="margin:0 0 4px;color:var(--text-secondary,#909399)">${esc(
          t('updCurTo', { cur: currentVer, ver: r.info.version })
        )}</p>
        <div style="margin:2px 0 0;max-height:150px;overflow-y:auto;color:var(--text-primary,#303133);white-space:pre-wrap;padding-right:4px">${esc(
          r.info.notes || t('updNotesDef')
        )}</div>
      </div>`
    try {
      await ElMessageBox({
        title: t('updFound', { v: r.info.version }),
        message: html,
        dangerouslyUseHTMLString: true,
        confirmButtonText: t('updNow'),
        showCancelButton: true,
        cancelButtonText: t('updIgnore'),
        distinguishCancelAndClose: true,
        closeOnClickModal: false,
        customClass: 'update-dialog',
        type: 'info'
      })
      // 立即更新 → 直接下载并安装
      await startDirectUpdate(r.info)
    } catch (action) {
      if (action === 'cancel') {
        // 忽略此版本
        setSkippedVersion(r.info.version)
        ElMessage.success(t('updIgnored', { v: r.info.version }))
      }
      // 'close'（×/ESC）= 稍后再说：不记录，下次启动再提醒
    }
  } catch {
    /* 静默 */
  }
}
</script>

<style scoped>
.lightbox-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(5, 6, 8, 0.92);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.lightbox-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  max-width: 1200px;
  padding: 0 4px 12px;
}
.lightbox-name {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lightbox-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.lb-btn {
  padding: 5px 12px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.07);
  color: #fff;
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
  transition: background var(--ease);
}
.lb-btn:hover {
  background: rgba(255, 255, 255, 0.16);
}
.lb-close {
  border-color: rgba(248, 113, 113, 0.4);
  color: #fca5a5;
}
.lb-close:hover {
  background: rgba(248, 113, 113, 0.15);
}
.lb-scale {
  min-width: 44px;
  text-align: center;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
}
.lightbox-viewport {
  display: flex;
  align-items: center;
  justify-content: center;
  max-width: 100%;
  max-height: 100%;
  flex: 1;
  width: 100%;
}
.lightbox-viewport.original {
  display: block;
  overflow: auto;
  cursor: grab;
}
.lightbox-viewport.original:active {
  cursor: grabbing;
}
.lightbox-viewport img {
  display: block;
  border-radius: 4px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.6);
}
.lightbox-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

/* ---------- 自动更新下载对话框 ---------- */
.upd-mask {
  position: fixed;
  inset: 0;
  z-index: 2100;
  background: rgba(5, 6, 8, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.upd-card {
  width: 360px;
  max-width: 92vw;
  background: var(--bg-card, #fff);
  border-radius: 14px;
  padding: 26px 28px 22px;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.24);
}
.upd-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #303133);
  margin: 0 0 18px;
  line-height: 1.6;
}
.upd-progress {
  display: flex;
  align-items: center;
  gap: 12px;
}
.upd-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--fill, #ececf0);
  overflow: hidden;
}
.upd-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--accent, #409eff);
  transition: width 0.2s ease;
}
.upd-pct {
  min-width: 42px;
  text-align: right;
  font-size: 13px;
  color: var(--text-secondary, #909399);
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.upd-tip {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary, #909399);
  line-height: 1.8;
}
.upd-err {
  margin: 0;
  font-size: 13px;
  color: #f56c6c;
  line-height: 1.8;
  word-break: break-all;
}
.upd-actions {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.upd-btn {
  padding: 7px 18px;
  border-radius: 8px;
  border: 1px solid var(--border-color, #dcdfe6);
  background: #fff;
  color: var(--text-primary, #303133);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.upd-btn:hover:not(:disabled) {
  border-color: var(--accent, #409eff);
  color: var(--accent, #409eff);
}
.upd-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.upd-btn-primary {
  border-color: var(--accent, #409eff);
  background: var(--accent, #409eff);
  color: #fff;
}
.upd-btn-primary:hover:not(:disabled) {
  background: var(--accent-dark, #337ecc);
  border-color: var(--accent-dark, #337ecc);
  color: #fff;
}
</style>

<!-- 全局（非 scoped）：更新提示弹窗尺寸——MessageBox 渲染在 body 下，需全局选择器 -->
<style>
.el-message-box.update-dialog {
  width: 440px !important;
  max-width: 92vw !important;
  padding-top: 18px;
}
.el-message-box.update-dialog .el-message-box__title {
  font-size: 15px;
  line-height: 1.6;
}
.el-message-box.update-dialog .el-message-box__message {
  padding-top: 6px;
}
</style>
