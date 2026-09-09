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
            <button class="lb-btn" @click="toggleMode">{{ mode === 'fit' ? '原始大小' : '适应屏幕' }}</button>
            <button class="lb-btn" @click="zoom(-0.25)">−</button>
            <span class="lb-scale mono">{{ Math.round(scale * 100) }}%</span>
            <button class="lb-btn" @click="zoom(0.25)">+</button>
            <button class="lb-btn lb-close" @click="closeLightbox">关闭</button>
          </div>
        </div>
        <div
          class="lightbox-viewport"
          :class="{ original: mode === 'original' }"
          @click.self="closeLightbox"
          @wheel.prevent="onWheel"
        >
          <img :src="lightboxUrl" :style="imgStyle" alt="放大查看" />
        </div>
        <p class="lightbox-hint">原始大小模式下可滚动 / 滚轮缩放查看细节 · 点击空白处关闭</p>
      </div>
    </Teleport>
  </el-config-provider>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import TikuIcon from './components/TikuIcon.vue'
import { isDesktop, checkForUpdate, getAppVersion } from './utils/updater'
import { openExternal } from './utils/external'
import { elLocale } from './i18n/lang'

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
   - 「忽略此版本」：记住版本号，只有出现更新的版本才再次自动提醒
     （手动渠道不受影响：设置 → 检查更新随时可用）
   - 「稍后」（× / ESC）：本次不提醒，下次启动再弹 */
const SKIP_UPDATE_KEY = 'tiku:skip-update-version'
let autoChecked = false

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
        <p style="margin:0 0 4px;color:var(--text-secondary,#909399)">当前 v${esc(currentVer)} → 新版本 <b style="color:var(--accent,#409eff)">v${esc(r.info.version)}</b></p>
        <div style="margin:2px 0 0;max-height:150px;overflow-y:auto;color:var(--text-primary,#303133);white-space:pre-wrap;padding-right:4px">${esc(r.info.notes || '包含修复与改进。')}</div>
      </div>`
    try {
      await ElMessageBox({
        title: `发现新版本 v${r.info.version}`,
        message: html,
        dangerouslyUseHTMLString: true,
        confirmButtonText: '立即更新',
        showCancelButton: true,
        cancelButtonText: '忽略此版本',
        distinguishCancelAndClose: true,
        closeOnClickModal: false,
        customClass: 'update-dialog',
        type: 'info'
      })
      // 立即更新 → 去设置页（页面已自动检查并显示可更新状态）
      if (router.currentRoute.value.name !== 'settings') {
        router.push({ name: 'settings' })
      }
    } catch (action) {
      if (action === 'cancel') {
        // 忽略此版本
        setSkippedVersion(r.info.version)
        ElMessage.success(`已忽略 v${r.info.version}，有新版本时会再提醒；也可随时在「设置」里手动更新`)
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
