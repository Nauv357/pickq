<template>
  <div class="ai-analysis">
    <div v-if="!aiText && !generating" class="ai-analysis-trigger">
      <button class="btn btn-secondary btn-sm" :disabled="busy" @click="generate">
        <TikuIcon name="sparkle" :size="13" />
        AI 解析
      </button>
      <span v-if="!everGenerated" class="text-muted ai-hint">
        不会做/想深入理解？让 AI 针对这道题生成解析
      </span>
    </div>

    <div v-if="generating" class="ai-analysis-loading">
      <span class="ai-spinner" />
      <span class="text-muted">AI 正在思考这道题…（约 10~40 秒）</span>
    </div>

    <div v-if="aiText" class="ai-analysis-result">
      <div class="ai-result-head">
        <span class="ai-badge">AI 解析</span>
        <span class="ai-actions">
          <button class="btn btn-secondary btn-sm" :disabled="busy" @click="generate">
            重新生成
          </button>
          <button class="btn btn-primary btn-sm" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存为正式解析' }}
          </button>
        </span>
      </div>
      <div class="ai-result-body" v-html="richHtml(aiText)"></div>
      <p v-if="saved" class="ai-saved-tip text-success">已保存为本题正式解析</p>
      <p v-if="error && aiText" class="ai-error">{{ error }}</p>
    </div>
    <p v-if="error && !aiText" class="ai-error">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { aiAnalysisQuestion, saveQuestionAnalysis } from '../api/questions'
import { richTextToHtml } from '../utils/richText'
import TikuIcon from './TikuIcon.vue'

const props = defineProps({
  questionId: { type: Number, required: true },
  bankId: { type: Number, required: true },
  // 打开面板时自动开始生成（列表行内展开场景）
  autoStart: { type: Boolean, default: false },
})

const emit = defineEmits(['saved'])

const generating = ref(false)
const saving = ref(false)
const aiText = ref('')
const everGenerated = ref(false)
const saved = ref(false)
const error = ref('')
const busy = computed(() => generating.value || saving.value)

const richHtml = (text) => richTextToHtml(text, props.bankId)

// autoStart 打开且尚未生成 → 自动触发
watch(
  () => props.autoStart,
  (on) => {
    if (on && !everGenerated.value && !busy.value) {
      generate()
    }
  },
  { immediate: true }
)

async function generate() {
  if (busy.value || !props.questionId) return
  generating.value = true
  error.value = ''
  aiText.value = ''
  try {
    //拦截器已解包 body.data → resp 即解析文本字符串
    const resp = await aiAnalysisQuestion(props.questionId)
    aiText.value = resp ?? ''
    everGenerated.value = true
    if (!aiText.value) {
      error.value = 'AI 未返回解析内容，请稍后重试'
    }
  } catch (e) {
    error.value = e?.message || 'AI 解析失败，请稍后重试'
  } finally {
    generating.value = false
  }
}

async function save() {
  if (saving.value || !aiText.value) return
  saving.value = true
  error.value = ''
  try {
    await saveQuestionAnalysis(props.questionId, aiText.value)
    saved.value = true
    ElMessage.success('解析已保存')
    emit('saved', aiText.value)
  } catch (e) {
    error.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

defineExpose({ generate })
</script>

<style scoped>
.ai-analysis {
  margin-top: 8px;
  border-top: 1px dashed var(--border, #d8d5cd);
  padding-top: 8px;
}
.ai-analysis-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ai-hint {
  font-size: 12px;
}
.ai-analysis-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.ai-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border, #d8d5cd);
  border-top-color: var(--accent, #c3272b);
  border-radius: 50%;
  animation: ai-rot 0.8s linear infinite;
  display: inline-block;
}
@keyframes ai-rot {
  to {
    transform: rotate(360deg);
  }
}
.ai-analysis-result {
  margin-top: 4px;
}
.ai-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.ai-badge {
  font-size: 12px;
  color: var(--accent, #c3272b);
  border: 1px solid currentColor;
  border-radius: 10px;
  padding: 1px 8px;
}
.ai-actions {
  display: flex;
  gap: 6px;
}
.ai-result-body {
  background: var(--bg-card, #fdfcf7);
  border-radius: 6px;
  padding: 8px 10px;
  line-height: 1.7;
  font-size: 13px;
}
.ai-saved-tip {
  margin-top: 6px;
  font-size: 12px;
}
.ai-error {
  color: #dc2626;
  font-size: 12px;
  margin-top: 6px;
}
</style>
