<template>
  <div class="empty" :class="{ 'empty-compact': compact }">
    <TikuIcon :name="icon" :size="compact ? 30 : 40" />
    <h3 v-if="title">{{ title }}</h3>
    <p v-if="desc" class="text-secondary">{{ desc }}</p>
    <!-- 规则 R4：空态必须给一条出路，不允许只有"暂无数据" -->
    <div v-if="$slots.default" class="empty-actions">
      <slot></slot>
    </div>
  </div>
</template>

<script setup>
/**
 * 统一空态（docs/design-ui.md 规则 R4）：图标 + 一句"为什么空" + 一条出路。
 * 页面只需要传文案和出路按钮，不要各自写 .empty 结构（现状 11 种写法、5 处没有出路）。
 */
import TikuIcon from './TikuIcon.vue'

defineProps({
  /** TikuIcon 名称：file / chart / clock / search / star … */
  icon: { type: String, default: 'file' },
  title: { type: String, default: '' },
  /** 说清"为什么空"或"怎么才会有数据"，不要写"暂无数据" */
  desc: { type: String, default: '' },
  /** 紧凑版（卡片内 / 弹窗内使用） */
  compact: { type: Boolean, default: false }
})
</script>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 90px 0;
  color: var(--text-muted);
  text-align: center;
}
.empty-compact {
  padding: 34px 0;
}
.empty h3 {
  margin: 6px 0 0;
  color: var(--text-primary);
}
.empty p {
  margin: 0;
  max-width: 46em;
  font-size: 14px;
  line-height: 1.7;
}
.empty-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  flex-wrap: wrap;
  justify-content: center;
}
</style>
