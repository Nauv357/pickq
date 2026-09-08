<template>
  <div v-if="refs.length" class="field-img-previews">
    <div v-for="n in refs" :key="n" class="field-img-item">
      <el-image
        :src="getJobImageUrl(jobId, n)"
        fit="cover"
        :preview-src-list="[getJobImageUrl(jobId, n)]"
        preview-teleported
      >
        <template #error>
          <div class="img-err"><TikuIcon name="x" :size="12" /></div>
        </template>
      </el-image>
      <span class="img-num mono">{{ n }}</span>
      <button class="img-remove" title="删除此图片引用（移除文本中的 [图片N]）" @click="removeRef(n)">
        <TikuIcon name="x" :size="10" />
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getJobImageUrl } from '../api/aiImport'
import TikuIcon from './TikuIcon.vue'

/**
 * 字段内图片预览：把 obj[field] 文本中的 [图片N] 标记渲染为任务临时图片（预览阶段无正式文件名），
 * 悬停红叉 = 从该字段文本中删除对应编号的引用（确认导入时后端按剩余标记落盘图片）。
 */
const props = defineProps({
  obj: { type: Object, required: true },
  field: { type: String, required: true },
  jobId: { type: Number, required: true }
})

// 文本中 [图片N] 的去重编号（按出现顺序）
const refs = computed(() => {
  const text = props.obj?.[props.field]
  if (!text) return []
  const seen = []
  for (const m of String(text).matchAll(/\[图片(\d+)\]/g)) {
    const n = Number(m[1])
    if (!seen.includes(n)) seen.push(n)
  }
  return seen
})

function removeRef(num) {
  props.obj[props.field] = (props.obj[props.field] || '').replace(new RegExp(`\\[图片${num}\\]`, 'g'), '')
}
</script>

<style scoped>
.field-img-previews {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.field-img-item {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border);
  background: var(--bg-elev);
  flex-shrink: 0;
}
.field-img-item :deep(.el-image) {
  width: 100%;
  height: 100%;
  display: block;
}
.field-img-item :deep(.el-image__inner) {
  width: 100%;
  height: 100%;
}
.img-num {
  position: absolute;
  left: 3px;
  bottom: 3px;
  font-size: 10px;
  line-height: 15px;
  padding: 0 4px;
  border-radius: 4px;
  background: var(--bg-base);
  border: 1px solid var(--border);
  color: var(--text-primary);
  pointer-events: none;
}
.img-remove {
  position: absolute;
  right: 2px;
  top: 2px;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  border: none;
  background: var(--danger-soft);
  color: var(--danger);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
}
.field-img-item:hover .img-remove {
  opacity: 1;
}
.img-remove:hover {
  background: var(--danger);
  color: #fff;
}
.img-err {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  background: var(--bg-elev);
}
</style>
