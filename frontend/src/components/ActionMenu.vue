<template>
  <Teleport to="body">
    <div
      v-if="open"
      ref="menuEl"
      class="action-menu"
      :style="style"
      role="menu"
      @contextmenu.prevent
    >
      <template v-for="(it, i) in items" :key="it.key || `divider-${i}`">
        <div v-if="it.divider" class="action-menu-divider"></div>
        <button
          v-else
          class="action-menu-item"
          :class="{ 'is-danger': it.danger, 'is-active': i === activeIndex }"
          :disabled="it.disabled"
          role="menuitem"
          @mouseenter="activeIndex = i"
          @click="pick(it)"
        >
          <TikuIcon v-if="it.icon" :name="it.icon" :size="14" class="action-menu-icon" />
          <span class="action-menu-label">{{ it.label }}</span>
          <span v-if="it.hint" class="action-menu-hint">{{ it.hint }}</span>
        </button>
      </template>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * 统一下拉/右键菜单（docs/design-ui.md 规则 R2、R7）。
 *
 * 用法一（右键）：在行/卡片上写 @contextmenu.prevent="menu.openFromEvent($event)"
 * 用法二（「…」按钮）：按钮 click 时调 menu.openFromEl($event.currentTarget)
 *
 * 之后通过 @select="onMenuSelect" 处理 key。菜单项：
 *   [{ key, label, icon?, hint?, danger?, disabled? }, { divider: true }]
 *
 * 关闭规则：点菜单外 / Esc / 滚动 / 选择完自动关闭；键盘 ↑↓ + Enter 可用。
 */
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import TikuIcon from './TikuIcon.vue'

const props = defineProps({
  items: { type: Array, default: () => [] }
})
const emit = defineEmits(['select'])

const open = ref(false)
const menuEl = ref(null)
const x = ref(0)
const y = ref(0)
const activeIndex = ref(-1)

// z-index 高于编辑大弹窗（1900）与悬浮题号盘（1990），低于 Element Plus 的 2000+ 会让确认框盖住菜单
const style = computed(() => ({ left: x.value + 'px', top: y.value + 'px' }))

function firstEnabled() {
  return props.items.findIndex((it) => !it.divider && !it.disabled)
}

function clampToViewport() {
  const el = menuEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const pad = 8
  if (r.right > window.innerWidth - pad) x.value = Math.max(pad, window.innerWidth - r.width - pad)
  if (r.bottom > window.innerHeight - pad) y.value = Math.max(pad, window.innerHeight - r.height - pad)
}

async function show(px, py) {
  x.value = px
  y.value = py
  open.value = true
  activeIndex.value = -1
  // 先打开再等一帧：调用方通常是在同一 tick 里设置"当前目标行"再调 open()，
  // 而 :items 是 computed（依赖目标行）——此刻 props.items 还是上一版（可能为空），
  // 若在这里用旧值做"空菜单就不打开"的短路判断，菜单会静默不出现。
  await nextTick()
  if (!props.items.length) {
    close()
    return
  }
  activeIndex.value = firstEnabled()
  clampToViewport()
  document.addEventListener('mousedown', onDocMouseDown, true)
  document.addEventListener('contextmenu', onDocContextMenu, true)
  window.addEventListener('scroll', close, true)
  window.addEventListener('resize', close, true)
  document.addEventListener('keydown', onKeydown, true)
}

/** 在鼠标位置打开（右键） */
function openFromEvent(e) {
  show(e.clientX, e.clientY)
}

/** 在某个元素下方左对齐打开（「…」按钮） */
function openFromEl(el) {
  if (!el) return
  const r = el.getBoundingClientRect()
  show(r.left, r.bottom + 4)
}

function close() {
  if (!open.value) return
  open.value = false
  activeIndex.value = -1
  document.removeEventListener('mousedown', onDocMouseDown, true)
  document.removeEventListener('contextmenu', onDocContextMenu, true)
  window.removeEventListener('scroll', close, true)
  window.removeEventListener('resize', close, true)
  document.removeEventListener('keydown', onKeydown, true)
}

// 菜单内的点击不算"点外面"；点外面直接关（不 preventDefault，避免吃掉这次右键）
function onDocMouseDown(e) {
  if (menuEl.value && menuEl.value.contains(e.target)) return
  close()
}

// 用捕获阶段：本次右键（还停在 target 阶段）不会被自己关掉，
// 之后在别处右键则先关旧菜单，再由目标元素打开新菜单。
function onDocContextMenu(e) {
  if (menuEl.value && menuEl.value.contains(e.target)) return
  close()
}

function onKeydown(e) {
  if (!open.value) return
  const selectable = props.items.map((it, i) => (it.divider || it.disabled ? -1 : i)).filter((i) => i >= 0)
  if (!selectable.length) return
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    return
  }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    const cur = selectable.indexOf(activeIndex.value)
    const next = e.key === 'ArrowDown'
      ? (cur + 1) % selectable.length
      : (cur - 1 + selectable.length) % selectable.length
    activeIndex.value = selectable[next]
    return
  }
  if (e.key === 'Enter' && activeIndex.value >= 0) {
    e.preventDefault()
    pick(props.items[activeIndex.value])
  }
}

function pick(it) {
  if (!it || it.disabled) return
  close()
  emit('select', it.key, it)
}

onBeforeUnmount(close)

defineExpose({ openFromEvent, openFromEl, close })
</script>

<style scoped>
.action-menu {
  position: fixed;
  z-index: 2200;
  min-width: 176px;
  max-width: 300px;
  padding: 6px;
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  box-shadow: var(--shadow-overlay);
  outline: none;
}
.action-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  background: none;
  border: none;
  border-radius: 7px;
  color: var(--text-primary);
  font-family: var(--font-sans);
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: background var(--ease);
}
.action-menu-item:hover:not(:disabled),
.action-menu-item.is-active:not(:disabled) {
  background: var(--bg-hover);
}
.action-menu-item:disabled {
  color: var(--text-muted);
  cursor: not-allowed;
}
.action-menu-item.is-danger {
  color: var(--danger);
}
.action-menu-item.is-danger:hover:not(:disabled) {
  background: var(--danger-soft);
}
.action-menu-icon {
  flex-shrink: 0;
  color: var(--text-muted);
}
.action-menu-item.is-danger .action-menu-icon {
  color: inherit;
}
.action-menu-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.action-menu-hint {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 12px;
}
.action-menu-divider {
  height: 1px;
  margin: 5px 8px;
  background: var(--border);
}
</style>
