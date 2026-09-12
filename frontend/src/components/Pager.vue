<template>
  <div v-if="total > size" class="pager">
    <el-pagination
      :current-page="page"
      :page-size="size"
      :total="total"
      :layout="layout"
      :page-sizes="pageSizes"
      :small="small"
      background
      @current-change="onPage"
      @size-change="onSize"
    />
  </div>
</template>

<script setup>
/**
 * 统一分页（docs/design-ui.md 规则 R5）：位置固定在内容区下方居中，样式统一。
 * 现状 5 个页面各写一份 .pager + el-pagination 配置，样式与显示条件略有出入。
 */
const props = defineProps({
  page: { type: Number, required: true },
  size: { type: Number, required: true },
  total: { type: Number, required: true },
  /** 可切换每页条数时传（如 [20, 50, 100]）；不传则不显示"每页 N 条" */
  pageSizes: { type: Array, default: null },
  small: { type: Boolean, default: false }
})

const emit = defineEmits(['update:page', 'update:size', 'change'])

const layout = props.pageSizes ? 'total, sizes, prev, pager, next' : 'total, prev, pager, next'

function onPage(p) {
  emit('update:page', p)
  emit('change', { page: p, size: props.size })
}

function onSize(s) {
  emit('update:size', s)
  emit('update:page', 1)
  emit('change', { page: 1, size: s })
}
</script>

<style scoped>
.pager {
  display: flex;
  justify-content: center;
  margin-top: 26px;
}
</style>
