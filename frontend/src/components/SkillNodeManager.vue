<template>
  <el-dialog
    v-model="visible"
    :title="t('title', { name: templateName || templateId })"
    width="min(94vw, 720px)"
    align-center
    :close-on-click-modal="false"
    append-to-body
    @open="load"
  >
    <p class="skm-hint text-muted">{{ t('hint') }}</p>

    <div v-loading="loading" class="skm-body">
      <!-- 自定义知识点：可以改名 / 换阶段 / 删除（改名不动 id，已打的标签不会失效） -->
      <section class="skm-sec">
        <div class="skm-sec-head">
          <h4>{{ t('customTitle') }}</h4>
          <span class="text-muted skm-sec-note">{{ t('customNote') }}</span>
        </div>
        <div v-if="!custom.length" class="skm-empty text-muted">{{ t('customEmpty') }}</div>
        <div v-for="n in custom" :key="n.nodeId" class="skm-row">
          <input
            v-model="n.draftName"
            class="skm-input"
            :placeholder="t('namePh')"
            @keydown.enter="rename(n)"
            @blur="rename(n)"
          />
          <el-select v-model="n.draftStage" size="small" style="width: 170px" @change="rename(n)">
            <el-option v-for="s in stageOptions" :key="s.stageId" :label="s.stageName" :value="s.stageId" />
          </el-select>
          <span class="skm-count text-muted">{{ t('usedN', { n: countOf(n.nodeId) }) }}</span>
          <button class="btn btn-ghost btn-sm" @click="remove(n)">{{ t('remove') }}</button>
        </div>

        <!-- 新增：名称 + 所属阶段 -->
        <div class="skm-row skm-add">
          <input v-model="newName" class="skm-input" :placeholder="t('addPh')" @keydown.enter="add" />
          <el-select v-model="newStage" size="small" style="width: 170px">
            <el-option v-for="s in stageOptions" :key="s.stageId" :label="s.stageName" :value="s.stageId" />
          </el-select>
          <button class="btn btn-secondary btn-sm" :disabled="!newName.trim()" @click="add">{{ t('add') }}</button>
        </div>
      </section>

      <!-- 官方知识点：可停用/恢复（不改官方模板本身，远端更新不会冲突） -->
      <section class="skm-sec">
        <div class="skm-sec-head">
          <h4>{{ t('officialTitle') }}</h4>
          <span class="text-muted skm-sec-note">{{ t('officialNote') }}</span>
        </div>
        <div v-if="disabled.length" class="skm-disabled">
          <span class="text-muted">{{ t('disabledTitle') }}</span>
          <span v-for="d in disabled" :key="d.nodeId" class="skm-chip">
            {{ d.name }}
            <button class="skm-chip-btn" :title="t('enable')" @click="setDisabled(d.nodeId, false)">
              <TikuIcon name="refresh" :size="12" />
            </button>
          </span>
        </div>
        <div v-for="g in officialGroups" :key="g.stageId" class="skm-group">
          <div class="skm-group-head">{{ g.stageName }}</div>
          <div v-for="n in g.nodes" :key="n.nodeId" class="skm-row skm-official">
            <span class="skm-name">{{ n.name }}</span>
            <span class="skm-count text-muted">{{ t('usedN', { n: countOf(n.nodeId) }) }}</span>
            <button class="btn btn-ghost btn-sm" @click="setDisabled(n.nodeId, true)">{{ t('disable') }}</button>
          </div>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="skm-foot">
        <button class="btn btn-ghost btn-sm" :disabled="!customized" @click="reset">{{ t('reset') }}</button>
        <span class="skm-grow"></span>
        <button class="btn btn-primary btn-sm" @click="visible = false">{{ t('done') }}</button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 知识点词表管理（用户反馈："既不能让用户自己编写，又不太准确，应该开放编辑权"）。
 *
 * 三件事，都只影响本机：
 * ① 自定义知识点：新增 / **改名** / 换阶段 / 删除——改名不动 nodeId，所以已打的标签不会失效；
 * ② 官方知识点：**停用 / 恢复**（不改官方模板本身，所以远端模板更新不会与本地冲突）；
 *    （停用后它上面的标签会变成失效标签，在知识点页一键清理即可）
 * ③ 恢复官方模板：一键清掉这张图的全部本机改动。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import TikuIcon from './TikuIcon.vue'
import { useConfirm } from '../composables/useConfirm'
import {
  createSkillNode,
  deleteSkillNode,
  getSkillCoverage,
  getSkillCustomizations,
  resetSkillCustomizations,
  setSkillNodeDisabled,
  updateSkillNode
} from '../api/skills'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  templateId: { type: String, required: true },
  templateName: { type: String, default: '' },
  /** 用来显示"这个知识点在本题库有几道题"（影响面提示） */
  bankId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const { t } = useI18n({
  messages: {
    'zh-CN': {
      title: '管理知识点 · {name}',
      hint: '这里的改动只影响本机（不会改官方模板，也不会进题库文件）。改名不会让已经打好的标签失效；停用/删除后，那些题上的标签会变成"失效标签"，在知识点页可以一键清理。',
      customTitle: '自定义知识点',
      customNote: '可以改名、换阶段、删除',
      customStage: '自定义知识点',
      customEmpty: '还没有自定义知识点：在下面加一个，或在题目标签下拉里直接输入新名称。',
      namePh: '知识点名称',
      addPh: '新知识点名称（如：速算技巧）',
      add: '添加',
      remove: '删除',
      officialTitle: '官方知识点',
      officialNote: '不用了就停用，随时可以恢复',
      disabledTitle: '已停用：',
      disable: '停用',
      enable: '恢复',
      usedN: '本库 {n} 题',
      reset: '恢复官方模板',
      resetAsk: '恢复官方模板会删掉这张图上的全部自定义知识点与停用设置（题目上的标签会变成失效标签，可一键清理）。确定恢复吗？',
      resetTitle: '恢复官方模板',
      resetDone: '已恢复官方模板',
      done: '完成',
      added: '已添加知识点',
      renamed: '已改名（标签不受影响）',
      removed: '已删除知识点',
      removeAsk: '删除自定义知识点「{name}」？',
      removeTitle: '删除知识点',
      disabled: '已停用（它的标签会变成失效标签）',
      enabled: '已恢复',
      fail: '操作失败'
    },
    'en-US': {
      title: 'Manage topics · {name}',
      hint: 'Changes here only affect this machine (the official template and your bank files are untouched). Renaming never invalidates existing tags; disabling or deleting turns their tags into “invalid tags” you can clean up on the topics page.',
      customTitle: 'Custom topics',
      customNote: 'rename · move to another stage · delete',
      customStage: 'Custom topics',
      customEmpty: 'No custom topics yet — add one below, or just type a new name in a question’s tag dropdown.',
      namePh: 'Topic name',
      addPh: 'New topic name',
      add: 'Add',
      remove: 'Delete',
      officialTitle: 'Official topics',
      officialNote: 'disable what you do not use — you can restore anytime',
      disabledTitle: 'Disabled:',
      disable: 'Disable',
      enable: 'Restore',
      usedN: '{n} questions here',
      reset: 'Restore official template',
      resetAsk: 'This removes every custom topic and disable setting for this template (their tags become invalid tags you can clean up). Continue?',
      resetTitle: 'Restore official template',
      resetDone: 'Official template restored',
      done: 'Done',
      added: 'Topic added',
      renamed: 'Renamed (tags unaffected)',
      removed: 'Topic deleted',
      removeAsk: 'Delete the custom topic “{name}”?',
      removeTitle: 'Delete topic',
      disabled: 'Disabled (its tags become invalid tags)',
      enabled: 'Restored',
      fail: 'Action failed'
    }
  }
})

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

/** 「自定义知识点」阶段（与后端 SkillGraphService.CUSTOM_STAGE_ID 一致） */
const CUSTOM_STAGE = 'custom'

const { confirmDanger } = useConfirm(t)

const loading = ref(false)
const custom = ref([])
const disabled = ref([])
const stages = ref([])
const officialNodes = ref([])
const customized = ref(false)
const counts = ref({})
const newName = ref('')
const newStage = ref('custom')

/** 官方节点按阶段分组（停用的不在其中——它们在「已停用」那一行） */
const officialGroups = computed(() => {
  const byStage = new Map()
  for (const n of officialNodes.value) {
    if (!byStage.has(n.stageId)) {
      byStage.set(n.stageId, { stageId: n.stageId, stageName: n.stageName, nodes: [] })
    }
    byStage.get(n.stageId).nodes.push(n)
  }
  return [...byStage.values()]
})

/** 可选阶段：官方阶段 + 「自定义知识点」（后者是隐式可用的，图上还没有自定义节点时也要能选） */
const stageOptions = computed(() => {
  const list = [...stages.value]
  if (!list.some((s) => s.stageId === CUSTOM_STAGE)) {
    list.push({ stageId: CUSTOM_STAGE, stageName: t('customStage') })
  }
  return list
})

const countOf = (nodeId) => counts.value[nodeId] || 0

async function load() {
  if (!props.templateId) return
  loading.value = true
  try {
    const [cust, graph] = await Promise.all([
      getSkillCustomizations(props.templateId),
      props.bankId
        ? getSkillCoverage(props.bankId, props.templateId).catch(() => null)
        : Promise.resolve(null)
    ])
    custom.value = (cust?.customNodes || []).map((n) => ({
      ...n,
      draftName: n.name,
      draftStage: n.stageId || 'custom'
    }))
    disabled.value = cust?.disabledNodes || []
    stages.value = cust?.stages || []
    customized.value = !!cust?.customized
    const map = {}
    for (const n of graph?.nodes || []) {
      map[n.nodeId] = n.questionCount
    }
    counts.value = map

    // 官方节点清单（不带自定义）：用于"可选阶段"之外展示官方词表
    const disabledIds = new Set(disabled.value.map((d) => d.nodeId))
    const all = await loadOfficialNodes()
    officialNodes.value = all.filter((n) => !disabledIds.has(n.nodeId))
  } finally {
    loading.value = false
  }
}

/** 官方节点：从技能图接口取，再剔除自定义节点 */
async function loadOfficialNodes() {
  const { getSkillTemplate } = await import('../api/skills')
  const tpl = await getSkillTemplate(props.templateId)
  return (tpl?.nodes || [])
    .filter((n) => !String(n.nodeId).startsWith('custom.'))
    .map((n) => ({ nodeId: n.nodeId, name: n.name, stageId: n.stageId, stageName: n.stageName }))
}

async function afterChange(msg) {
  ElMessage.success(msg)
  await load()
  emit('changed')
}

async function add() {
  const name = newName.value.trim()
  if (!name) return
  try {
    await createSkillNode(props.templateId, name, newStage.value || undefined)
    newName.value = ''
    await afterChange(t('added'))
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || t('fail'))
  }
}

async function rename(node) {
  const name = String(node.draftName || '').trim()
  if (!name || (name === node.name && node.draftStage === node.stageId)) {
    node.draftName = node.name
    return
  }
  try {
    await updateSkillNode(props.templateId, node.nodeId, { name, stageId: node.draftStage })
    node.draftName = name
    await afterChange(t('renamed'))
  } catch (e) {
    node.draftName = node.name
    ElMessage.error(e?.response?.data?.message || t('fail'))
  }
}

async function remove(node) {
  const ok = await confirmDanger(t('removeAsk', { name: node.name }), t('removeTitle')).catch(() => false)
  if (!ok) return
  try {
    await deleteSkillNode(props.templateId, node.nodeId)
    await afterChange(t('removed'))
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || t('fail'))
  }
}

async function setDisabled(nodeId, value) {
  try {
    await setSkillNodeDisabled(props.templateId, nodeId, value)
    await afterChange(value ? t('disabled') : t('enabled'))
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || t('fail'))
  }
}

async function reset() {
  try {
    await ElMessageBox.confirm(t('resetAsk'), t('resetTitle'), {
      confirmButtonText: t('resetTitle'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await resetSkillCustomizations(props.templateId)
    await afterChange(t('resetDone'))
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || t('fail'))
  }
}

defineExpose({ load })
</script>

<style scoped>
.skm-hint {
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.7;
}
.skm-sec {
  border-top: 1px solid var(--border);
  padding-top: 10px;
  margin-top: 10px;
}
.skm-sec-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
}
.skm-sec-head h4 {
  margin: 0;
  font-size: 14px;
}
.skm-sec-note {
  font-size: 12px;
}
.skm-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
.skm-row.skm-add {
  margin-top: 6px;
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.skm-input {
  flex: 1;
  min-width: 120px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 5px 8px;
  font-size: 13px;
  font-family: inherit;
  background: var(--bg-card);
  color: var(--text-primary);
}
.skm-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skm-count {
  font-size: 12px;
  flex-shrink: 0;
}
.skm-group {
  margin-bottom: 6px;
}
.skm-group-head {
  font-size: 12px;
  color: var(--text-muted);
  margin: 6px 0 2px;
}
.skm-official {
  padding-left: 6px;
}
.skm-disabled {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 12px;
  margin-bottom: 6px;
}
.skm-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px dashed var(--border-strong);
  border-radius: 12px;
  padding: 2px 6px 2px 10px;
  color: var(--text-secondary);
}
.skm-chip-btn {
  border: none;
  background: none;
  color: var(--accent-text);
  cursor: pointer;
  display: inline-flex;
  padding: 2px;
}
.skm-empty {
  font-size: 12.5px;
  padding: 4px 0;
}
.skm-foot {
  display: flex;
  align-items: center;
  gap: 8px;
}
.skm-grow {
  flex: 1;
}
</style>
