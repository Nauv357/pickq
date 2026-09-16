<template>
  <div class="qp">
    <div class="qp-head">
      <span class="qp-title">{{ t('qLabel', { n: link.questionNumber ?? '—' }) }}</span>
      <span v-if="question?.typeLabel" class="qp-type">{{ question.typeLabel }}</span>
      <span class="qp-grow"></span>
      <button class="qp-act" @click="$emit('go')">{{ t('goBank') }}</button>
      <button class="qp-act" @click="$emit('close')">{{ t('collapse') }}</button>
    </div>

    <div v-if="loading" class="text-muted qp-loading">{{ t('loading') }}</div>
    <div v-else-if="!question" class="text-muted qp-loading">{{ t('failed') }}</div>
    <template v-else>
      <div class="qp-stem" v-html="richTextToHtml(question.content, link.bankId)"></div>
      <div v-if="question.materialContent" class="qp-material">
        <b>{{ t('material') }}：</b>
        <span v-html="richTextToHtml(question.materialContent, link.bankId)"></span>
      </div>
      <div v-if="question.options?.length" class="qp-opts">
        <div
          v-for="opt in question.options"
          :key="opt.key"
          class="qp-opt"
          :class="{ correct: question.answerKeys?.includes(opt.key) }"
        >
          <span class="qp-key">{{ opt.key }}</span>
          <span v-html="richTextToHtml(opt.text, link.bankId)"></span>
        </div>
      </div>
      <div v-if="question.answerKeys?.length" class="qp-row">
        <b>{{ t('answer') }}：</b>{{ question.answerKeys.join('、') }}
      </div>
      <div v-if="question.answerText" class="qp-row"><b>{{ t('answer') }}：</b>{{ question.answerText }}</div>
      <div v-if="question.referenceAnswer" class="qp-row">
        <b>{{ t('reference') }}：</b><span v-html="richTextToHtml(question.referenceAnswer, link.bankId)"></span>
      </div>
      <div v-if="question.analysis" class="qp-row">
        <b>{{ t('analysis') }}：</b><span v-html="richTextToHtml(question.analysis, link.bankId)"></span>
      </div>
      <p v-if="!hasAnyAnswer" class="text-muted qp-loading">{{ t('noAnswer') }}</p>
    </template>
  </div>
</template>

<script setup>
/**
 * 笔记里关联题目的**就地预览**（2026-09-16 用户反馈："关联的题目只有个跳转链接，
 * 点击还要跳转到题库页面；正常应该能在笔记处直接或折叠式查看"）。
 *
 * 只在展开时挂载 → 打开哪道题就只取哪道题（`GET /api/questions/{id}`），
 * 不展开的笔记一个请求都不发。看完要么就地收起，要么「去题库」进完整页面
 * （讲解、笔记、收藏那些仍在题库页，笔记页不重复造一套）。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getQuestion } from '../api/questions'
import { richTextToHtml } from '../utils/richText'

const props = defineProps({
  /** 笔记的一条关联（type=question）：{targetId, bankId, questionNumber, label} */
  link: { type: Object, required: true },
  /** 笔记 id（仅用于 key / 调试） */
  noteId: { type: [Number, String], default: null }
})

defineEmits(['go', 'close'])

const { t } = useI18n({
  messages: {
    'zh-CN': {
      qLabel: '第 {n} 题',
      goBank: '去题库',
      collapse: '收起',
      loading: '正在取题…',
      failed: '这道题取不到了（可能已被删除）',
      material: '材料',
      answer: '正确答案',
      reference: '参考答案',
      analysis: '解析',
      noAnswer: '这道题还没有配置答案与解析'
    },
    'en-US': {
      qLabel: 'Q{n}',
      goBank: 'Open in bank',
      collapse: 'Collapse',
      loading: 'Loading question…',
      failed: 'This question is gone (it may have been deleted)',
      material: 'Material',
      answer: 'Answer',
      reference: 'Reference answer',
      analysis: 'Analysis',
      noAnswer: 'This question has no answer or analysis yet'
    }
  }
})

const question = ref(null)
const loading = ref(true)

const hasAnyAnswer = computed(() => {
  const q = question.value
  return !!(q && (q.answerKeys?.length || q.answerText || q.referenceAnswer || q.analysis))
})

onMounted(async () => {
  try {
    question.value = await getQuestion(props.link.targetId)
  } catch (e) {
    question.value = null
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.qp {
  margin-top: 10px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: 8px;
  background: var(--bg-elev);
  padding: 10px 12px;
}
.qp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.qp-grow {
  flex: 1;
}
.qp-title {
  font-size: 12.5px;
  color: var(--accent-text);
  font-weight: 600;
}
.qp-type {
  font-size: 11px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 0 6px;
}
.qp-act {
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 12.5px;
  cursor: pointer;
  padding: 0 4px;
}
.qp-act:hover {
  color: var(--accent-text);
}
.qp-loading {
  font-size: 12.5px;
  margin: 4px 0 0;
}
.qp-stem {
  font-size: var(--content-font);
  line-height: var(--content-lh);
  white-space: pre-wrap;
  word-break: break-word;
  max-width: 68ch;
}
.qp-stem :deep(.rich-img),
.qp-material :deep(.rich-img),
.qp-row :deep(.rich-img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 6px 0;
  display: block;
}
.qp-material {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-secondary);
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
}
.qp-opts {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}
.qp-opt {
  display: flex;
  gap: 8px;
  font-size: 13.5px;
  line-height: 1.7;
  padding: 3px 8px;
  border-radius: 6px;
}
.qp-opt.correct {
  background: var(--success-soft);
  color: var(--success);
}
.qp-key {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text-secondary);
}
.qp-opt.correct .qp-key {
  color: var(--success);
}
.qp-row {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-primary);
}
.qp-row b {
  color: var(--text-secondary);
  font-weight: 600;
}
</style>
