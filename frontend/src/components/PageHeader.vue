<template>
  <header class="page-header">
    <div class="page-header-main">
      <RouterLink v-if="backTo" :to="backTo" class="back-link">
        <TikuIcon name="arrow-left" :size="14" />
        {{ backText || t('back') }}
      </RouterLink>
      <div class="title-line">
        <h1 class="page-title">{{ title }}</h1>
        <slot name="title-extra"></slot>
      </div>
      <p v-if="desc" class="page-desc">{{ desc }}</p>
      <div v-if="$slots.meta" class="page-meta text-muted">
        <slot name="meta"></slot>
      </div>
    </div>
    <div v-if="$slots.actions || note" class="page-header-actions">
      <span v-if="note" class="page-note text-muted">{{ note }}</span>
      <slot name="actions"></slot>
    </div>
  </header>
</template>

<script setup>
/**
 * 页面头（docs/design-ui.md 规则 R1/R2/R6）：
 *   左：可选返回入口 → 标题 → 一句话说明 → 摘要信息
 *   右：说明性文字 + 操作按钮，**主操作永远放最右**，每屏最多一个主按钮
 * 页面不要自己写 header 结构，避免"每页长一样但位置各不相同"。
 */
import { useI18n } from 'vue-i18n'
import TikuIcon from './TikuIcon.vue'

defineProps({
  title: { type: String, required: true },
  /** 一句话说明这一屏是干什么的（不是重复标题） */
  desc: { type: String, default: '' },
  /** 传了就渲染左上角返回入口（同页内多视图切换请传，跨页返回也传） */
  backTo: { type: [String, Object], default: null },
  /** 返回入口文案，默认「返回」；跨页建议写清去哪（如「返回题库」） */
  backText: { type: String, default: '' },
  /** 右侧说明性文字（如「仅统计本机」），不是按钮 */
  note: { type: String, default: '' }
})

// 全局词典：common.back
const { t } = useI18n()
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 24px;
}
.page-header-main {
  min-width: 0;
}
.title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0;
  font-size: 26px;
  line-height: 1.25;
}
.page-desc {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
}
.page-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 13px;
  flex-wrap: wrap;
}
.page-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.page-note {
  font-size: 12px;
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  padding: 0;
  background: none;
  border: none;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;
  transition: color var(--ease);
}
.back-link:hover {
  color: var(--accent-text);
}
</style>
