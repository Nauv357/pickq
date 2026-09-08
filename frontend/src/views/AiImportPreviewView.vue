<template>
  <div class="page">
    <!-- 加载/处理中 -->
    <div v-if="loading" class="empty">
      <div class="stage-spin"><TikuIcon name="refresh" :size="26" /></div>
      <h3>{{ stageText || '任务处理中…' }}</h3>
      <p class="text-secondary">AI 正在整理题目，请稍候</p>
    </div>

    <!-- 失败 / 已取消 -->
    <div v-else-if="errorMsg" class="error-state">
      <TikuIcon :name="canceled ? 'x' : 'x'" :size="40" />
      <h3>{{ canceled ? '任务已取消' : '导入失败' }}</h3>
      <p class="text-secondary">{{ errorMsg }}</p>
      <div class="error-actions">
        <button v-if="canceled" class="btn btn-danger" :disabled="deleting" @click="deleteAgain">
          <TikuIcon name="trash" :size="14" />
          {{ deleting ? '删除中…' : '彻底删除此任务' }}
        </button>
        <button class="btn btn-secondary" @click="$router.push(backTo)">返回</button>
      </div>
    </div>

    <!-- 空结果 -->
    <div v-else-if="questions.length === 0" class="error-state">
      <TikuIcon name="file" :size="40" />
      <h3>未能从文档提取题目</h3>
      <p class="text-secondary">请调整文档内容或更换模型后重试</p>
      <button class="btn btn-secondary" @click="$router.push('/')">返回题库列表</button>
    </div>

    <template v-else>
      <header class="page-header">
        <div>
          <button class="back-link" @click="$router.push(backTo)">
            <TikuIcon name="arrow-left" :size="14" />
            返回
          </button>
          <div class="title-line">
            <h1 class="page-title">AI 导入预览</h1>
            <span class="file-tag">{{ fileName }}</span>
          </div>
          <p class="page-desc text-muted">
            AI 共生成 {{ questions.length }} 题，其中 <b class="ai-supp-count">{{ aiSupplementCount }}</b> 题为 AI 补充答案（需重点核对）
            <template v-if="noAnswerCount > 0">，<b class="no-answer-count">{{ noAnswerCount }}</b> 题暂无答案待补填</template>，可编辑后确认导入
          </p>
          <p v-if="jobMeta" class="page-desc text-muted proc-line">
            本次处理：{{ jobMeta.processPath || ENGINE_LABEL[jobMeta.engine] || jobMeta.engine }} · 思考{{ jobMeta.thinking ? '开' : '关' }} · AI 补充{{ jobMeta.aiSupplement ? '开' : '关' }}
          </p>
        </div>
        <div class="header-actions">
          <button class="btn btn-danger" :disabled="canceling" @click="cancel">
            <TikuIcon name="trash" :size="14" />
            {{ canceling ? '取消中…' : '取消导入' }}
          </button>
          <button class="btn btn-primary" :disabled="confirming" @click="confirm">
            <TikuIcon name="check" :size="15" />
            {{ confirming ? '导入中…' : '确认导入' }}
          </button>
        </div>
      </header>

      <!-- 题数差异检测提示（复杂排版/模型劣化时提醒；不阻塞确认导入） -->
      <div v-if="warningHint" class="warning-banner">
        <TikuIcon name="info" :size="14" />
        <span>{{ warningHint }}</span>
      </div>

      <!-- 题干完整性提示（材料题 = 材料 + 问题全文，勿删材料） -->
      <div class="material-tip">
        <TikuIcon name="info" :size="14" />
        <span>题干已包含材料全文（后端已补齐），编辑时请勿删除材料部分</span>
      </div>

      <!-- 已确认导入提示（重复访问历史任务时） -->
      <div v-if="jobConfirmed" class="confirmed-banner">
        <TikuIcon name="check" :size="15" />
        <span>该任务已确认导入（重复确认不会重复导入）</span>
      </div>

      <!-- 素材区（右侧抽屉：图片 + 材料两个 Tab；拖拽/复制补图、拖材料关联题目） -->
      <aside class="image-panel" :class="{ collapsed: !panelOpen }">
        <div class="image-panel-head" @click="panelOpen = !panelOpen">
          <TikuIcon name="file" :size="14" />
          <span>素材区</span>
          <TikuIcon :name="panelOpen ? 'chevron-down' : 'chevron-up'" :size="13" class="panel-toggle" />
        </div>
        <div v-if="panelOpen" class="image-panel-body">
          <div class="panel-tabs">
            <button :class="{ active: panelTab === 'images' }" @click="panelTab = 'images'">
              图片{{ jobImages.length ? `（${jobImages.length}）` : '' }}
            </button>
            <button :class="{ active: panelTab === 'materials' }" @click="panelTab = 'materials'">
              材料{{ materials.length ? `（${materials.length}）` : '' }}
            </button>
            <button :class="{ active: panelTab === 'numbers' }" @click="panelTab = 'numbers'">
              题号{{ questions.length ? `（${questions.length}）` : '' }}
            </button>
          </div>

          <!-- 图片 Tab -->
          <template v-if="panelTab === 'images'">
            <p class="image-panel-tip text-muted">
              拖拽或点击图片，把 <code>[图片N]</code> 插入到题目/材料输入框（含图题自动配图失败时手动补图）
            </p>
            <div v-if="jobImages.length === 0" class="image-empty text-muted">本任务未提取到图片</div>
            <div v-else class="image-grid">
              <div
                v-for="img in jobImages"
                :key="img.num"
                class="image-thumb"
                :class="{ used: usedImageNums.has(img.num) }"
                :title="`[图片${img.num}]${usedImageNums.has(img.num) ? '（已引用）' : ''}${img.fileName ? '：' + img.fileName : ''}${img.ext ? '.' + img.ext : ''}`"
                draggable="true"
                @dragstart="onDragStart($event, img.num)"
              >
                <el-image
                  :src="getJobImageUrl(jobId, img.num)"
                  :preview-src-list="[getJobImageUrl(jobId, img.num)]"
                  preview-teleported
                  fit="cover"
                />
                <span class="num-badge mono">{{ img.num }}</span>
                <span v-if="usedImageNums.has(img.num)" class="used-dot" title="已在题目/材料中引用"></span>
                <button class="copy-btn" title="复制 [图片N] 到剪贴板" @click.stop="copyImageRef(img.num)">
                  <TikuIcon name="copy" :size="11" />
                </button>
              </div>
            </div>
          </template>

          <!-- 材料 Tab -->
          <template v-else-if="panelTab === 'materials'">
            <p class="image-panel-tip text-muted">
              拖材料卡片到题目上，或点「关联」批量设置；展开可编辑材料内容（含表格/图片标记）
            </p>
            <div v-if="materials.length === 0" class="image-empty text-muted">本任务未检测到共享材料</div>
            <div v-else class="material-snippet-list">
              <div
                v-for="m in materials"
                :key="m.materialKey"
                class="material-snippet"
                :class="{ linked: linkedCount(m.materialKey) > 0 }"
                draggable="true"
                @dragstart="onMaterialDragStart($event, m.materialKey)"
              >
                <div class="material-snippet-head">
                  <span class="material-key-tag">{{ m.materialKey }}</span>
                  <span class="text-muted snippet-count">{{ linkedCount(m.materialKey) }} 题关联</span>
                  <button class="snippet-link-btn" title="关联到题目（勾选批量设置）" @click.stop="openMaterialLink(m.materialKey)">
                    <TikuIcon name="link" :size="11" />
                    关联
                  </button>
                  <button class="snippet-toggle" :title="materialExpanded.has(m.materialKey) ? '收起' : '展开编辑'" @click.stop="toggleMaterialExpand(m.materialKey)">
                    <TikuIcon :name="materialExpanded.has(m.materialKey) ? 'chevron-up' : 'chevron-down'" :size="12" />
                  </button>
                </div>
                <div v-if="materialExpanded.has(m.materialKey)" class="material-snippet-edit">
                  <textarea v-model="m.content" :rows="4" placeholder="材料内容（文字 + [图片N] / 表格 HTML）" @dragover.prevent @drop="onDropImage($event, m, 'content')" />
                  <FieldImages :obj="m" field="content" :job-id="jobId" />
                </div>
                <div v-else class="material-snippet-preview" title="点击展开编辑" @click="toggleMaterialExpand(m.materialKey)">
                  <span v-html="qPreviewHtml(m.content)"></span>
                </div>
              </div>
            </div>
          </template>

          <!-- 题号 Tab：圆形题号盘，点击跳转到对应题目卡片（含输入直达） -->
          <template v-else>
            <p class="image-panel-tip text-muted">
              点题号跳转到对应题目；<span class="no-answer-hint">红色圆钮 = 客观题未配答案</span>，可配合「图片待补充 / AI 补充」标记逐题校对
            </p>
            <QuestionNavDock
              class="dock-in-drawer"
              title="全部题目"
              :items="dockJumpItems"
              storage-key="preview-dock"
              active-fill
              @select="focusCard"
            />
          </template>
        </div>
      </aside>

      <!-- 共享材料（可编辑；确认导入时由后端一并入库） -->
      <div v-if="materials.length" class="preview-materials">
        <div class="section-title">
          <h2>共享材料（{{ materials.length }}）</h2>
          <span class="text-muted material-note">材料用于资料分析题，编辑时勿删材料文本</span>
        </div>
        <div v-for="(m, mi) in materials" :key="m.materialKey" class="material-edit-card">
          <div class="material-edit-head">
            <span class="material-key-tag">{{ m.materialKey }}</span>
            <button class="icon-btn danger" title="删除材料（关联题将解除引用）" @click="removeMaterial(mi)">
              <TikuIcon name="trash" :size="13" />
            </button>
          </div>
          <el-input
            v-model="m.content"
            type="textarea"
            :rows="3"
            placeholder="材料内容（文字 + [图片:文件名] 标记）"
            @dragover.prevent
            @drop="onDropImage($event, m, 'content')"
          />
          <FieldImages :obj="m" field="content" :job-id="jobId" />
        </div>
      </div>

      <div class="preview-list">
        <div v-for="(q, qi) in questions" :key="qi" :data-qidx="qi">
          <div
            class="preview-card"
            :class="{ 'drag-over': dragOverIndex === qi, 'card-flash': flashIdx === qi }"
            @dragover.prevent="dragOverIndex = qi"
            @dragleave="dragOverIndex = null"
            @drop="onCardDrop($event, qi)"
          >
            <div class="card-head">
              <span
                class="drag-handle"
                draggable="true"
                title="拖动排序"
                @dragstart="onQuestionDragStart($event, qi)"
              >
                <TikuIcon name="grip" :size="13" />
              </span>
              <!-- 题号：questionNumber 为 null（后端题号回填定位失败）→ 显示"未编号"灰色徽标（第十一轮）；
                   重复题号（列表内 >1 次）→ 红色"重复题号 N"徽标（第十一轮） -->
              <span
                v-if="duplicateNums.has(q.questionNumber)"
                class="q-index mono dup-number"
                title="该题号在列表中重复，可能是重复导入的题目，请删除或修改题号"
              >重复题号 {{ q.questionNumber }}</span>
              <span v-else-if="q.questionNumber != null" class="q-index mono">#{{ q.questionNumber }}</span>
              <span v-else class="q-index mono unnumbered" title="后端未能回填题号（公式/跨行/图片转写差异导致），可拖拽归位或提交时自动补号">未编号</span>
            <!-- 答案来源标记：ORIGINAL=原文答案 / AI_SUPPLEMENT=AI 补充（需重点核对） -->
            <span v-if="q.answerSource === 'ORIGINAL'" class="src-tag src-original" title="答案来自源文档原文">
              <TikuIcon name="check" :size="11" />
              原文答案
            </span>
            <span v-else-if="q.answerSource === 'AI_SUPPLEMENT'" class="src-tag src-ai" title="源文档无答案，由 AI 补充，请重点核对">
              <TikuIcon name="sparkle" :size="11" />
              AI 补充
            </span>
            <!-- 无答案：标红提醒补填（aiSupplement=false 或原文缺失时） -->
            <span v-else-if="!q.answerKeys.length" class="src-tag src-no-answer" title="本题暂无答案，请补填后导入">
              <TikuIcon name="x" :size="11" />
              无答案
            </span>
            <!-- 关联材料标识（点击移除） -->
            <span
              v-if="q.materialKey"
              class="src-tag src-material clickable"
              title="已关联共享材料，点击移除"
              @click="q.materialKey = null"
            >
              <TikuIcon name="file" :size="11" />
              {{ q.materialKey }}
              <TikuIcon name="x" :size="10" class="tag-x" />
            </span>
            <!-- 图片待补充：文档含图且其他题已配图，而本题题干/选项无任何图片引用（图形题漏配图提示） -->
            <span
              v-if="needsImageFlag(q)"
              class="src-tag src-no-image"
              title="文档包含图片且其他题已引用图片，本题未引用任何图片。若本题应有图（图形推理等），请从右侧素材区拖入或复制 [图片N] 到题干/选项"
            >
              <TikuIcon name="x" :size="11" />
              图片待补充
            </span>
            <div class="type-tabs">
              <button
                v-for="t in typeOptions"
                :key="t.value"
                class="type-tab"
                :class="{ active: q.type === t.value }"
                @click="switchType(q, t.value)"
              >
                {{ t.label }}
              </button>
            </div>
            <span class="card-score">
              <el-input-number v-model="q.score" :min="1" :max="100" size="small" controls-position="right" style="width: 110px" />
            </span>
            <button
              class="icon-btn"
              :class="{ active: previewOpen.has(qi) }"
              :title="previewOpen.has(qi) ? '收起渲染预览' : '查看公式/图片渲染效果（确认公式转写是否正确）'"
              @click="togglePreview(qi)"
            >
              <TikuIcon name="eye" :size="14" />
            </button>
            <button class="icon-btn danger" title="删除此题" @click="removeQuestion(qi)">
              <TikuIcon name="trash" :size="14" />
            </button>
          </div>
          <!-- 渲染预览（只读）：公式 KaTeX + [图片N] 任务图 + 表格，供确认公式转写/图片归属是否正确 -->
          <div v-if="previewOpen.has(qi)" class="preview-block">
            <div class="preview-title">渲染预览</div>
            <div class="preview-row" v-html="qPreviewHtml(q.content)"></div>
            <template v-if="q.type !== 'SUBJECTIVE' && q.type !== 'JUDGE'">
              <div v-for="opt in q.options" :key="opt.key" class="preview-row preview-option">
                <span class="opt-key">{{ opt.key }}</span>
                <span v-html="qPreviewHtml(opt.text) || `（选项 ${opt.key} 为空）`"></span>
              </div>
            </template>
            <div v-if="q.type === 'JUDGE'" class="preview-row">A. 正确&nbsp;&nbsp;B. 错误</div>
            <div v-if="q.type === 'SUBJECTIVE' && q.referenceAnswer" class="preview-row">
              <span class="preview-label">参考答案：</span><span v-html="qPreviewHtml(q.referenceAnswer)"></span>
            </div>
          </div>

          <div class="card-body">
            <!-- 材料关联（下拉选择或从素材区拖入） -->
            <div v-if="materials.length" class="field">
              <label class="field-label">共享材料（可选）</label>
              <el-select v-model="q.materialKey" clearable placeholder="关联共享材料（从右侧素材区拖入或选择）" style="width: 100%">
                <el-option v-for="m in materials" :key="m.materialKey" :label="m.materialKey" :value="m.materialKey" />
              </el-select>
            </div>

            <div class="field">
              <label class="field-label">题干 <span class="required">*</span></label>
              <el-input
                v-model="q.content"
                type="textarea"
                :rows="2"
                placeholder="题目内容"
                @dragover.prevent
                @drop="onDropImage($event, q, 'content')"
              />
              <FieldImages :obj="q" field="content" :job-id="jobId" />
            </div>

            <!-- 选项（判断/主观题隐藏） -->
            <div v-if="q.type !== 'JUDGE' && q.type !== 'SUBJECTIVE'" class="field">
              <label class="field-label">选项 <span class="required">*</span></label>
              <div class="option-rows">
                <div v-for="(opt, oi) in q.options" :key="oi" class="option-row">
                  <span class="opt-key">{{ keyOf(oi) }}</span>
                  <div class="option-main">
                    <el-input
                      v-model="opt.text"
                      :placeholder="`选项 ${keyOf(oi)}`"
                      @dragover.prevent
                      @drop="onDropImage($event, opt, 'text')"
                    />
                    <FieldImages :obj="opt" field="text" :job-id="jobId" />
                  </div>
                  <button
                    class="icon-btn"
                    :disabled="q.options.length <= 2"
                    @click="q.options.splice(oi, 1); rekey(q)"
                  >
                    <TikuIcon name="x" :size="13" />
                  </button>
                </div>
                <button v-if="q.options.length < 10" class="btn btn-ghost btn-sm" @click="addOption(q)">
                  <TikuIcon name="plus" :size="13" />
                  添加选项
                </button>
              </div>
            </div>

            <!-- 主观题参考答案 -->
            <div v-if="q.type === 'SUBJECTIVE'" class="field">
              <label class="field-label">参考答案（可选）</label>
              <el-input
                v-model="q.referenceAnswer"
                type="textarea"
                :rows="2"
                placeholder="主观题参考答案"
                @dragover.prevent
                @drop="onDropImage($event, q, 'referenceAnswer')"
              />
              <FieldImages :obj="q" field="referenceAnswer" :job-id="jobId" />
            </div>

            <!-- 答案（主观题无） -->
            <div v-if="q.type !== 'SUBJECTIVE'" class="field">
              <label class="field-label">正确答案 <span class="required">*</span></label>
              <div v-if="q.type === 'SINGLE'" class="answer-rows">
                <button
                  v-for="opt in q.options"
                  :key="opt.key"
                  class="answer-row"
                  :class="{ selected: q.answerKeys[0] === opt.key }"
                  @click="q.answerKeys = [opt.key]"
                >
                  <span class="answer-dot"></span>
                  <span class="opt-key">{{ opt.key }}</span>
                  <span class="answer-text">{{ opt.text || `选项 ${opt.key}` }}</span>
                </button>
              </div>
              <div v-else-if="q.type === 'MULTIPLE'" class="answer-rows">
                <button
                  v-for="opt in q.options"
                  :key="opt.key"
                  class="answer-row"
                  :class="{ selected: q.answerKeys.includes(opt.key) }"
                  @click="toggleMulti(q, opt.key)"
                >
                  <span class="answer-check"></span>
                  <span class="opt-key">{{ opt.key }}</span>
                  <span class="answer-text">{{ opt.text || `选项 ${opt.key}` }}</span>
                </button>
              </div>
              <div v-else class="judge-answer">
                <button class="judge-btn" :class="{ selected: q.answerKeys[0] === 'A' }" @click="q.answerKeys = ['A']">
                  <TikuIcon name="check" :size="16" /> 正确
                </button>
                <button class="judge-btn" :class="{ selected: q.answerKeys[0] === 'B' }" @click="q.answerKeys = ['B']">
                  <TikuIcon name="x" :size="16" /> 错误
                </button>
              </div>
            </div>

            <div class="field-grid">
              <div class="field">
                <label class="field-label">主题</label>
                <el-input v-model="q.topic" placeholder="可选" maxlength="100" />
              </div>
              <div class="field">
                <label class="field-label">分类</label>
                <el-input v-model="q.category" placeholder="可选" maxlength="100" />
              </div>
            </div>

            <div class="field">
              <label class="field-label">答案文字（可选）</label>
              <el-input
                v-model="q.answerText"
                maxlength="500"
                placeholder="如：选 B，因为……"
                @dragover.prevent
                @drop="onDropImage($event, q, 'answerText')"
              />
              <FieldImages :obj="q" field="answerText" :job-id="jobId" />
            </div>
            <div class="field">
              <label class="field-label">解析（可选）</label>
              <el-input
                v-model="q.analysis"
                type="textarea"
                :rows="2"
                placeholder="答案解析"
                @dragover.prevent
                @drop="onDropImage($event, q, 'analysis')"
              />
              <FieldImages :obj="q" field="analysis" :job-id="jobId" />
            </div>
          </div>
          </div>
          <div class="insert-row">
            <button class="btn btn-ghost btn-sm" @click="insertQuestion(qi + 1)">
              <TikuIcon name="plus" :size="12" />
              在此插入新题
            </button>
          </div>
        </div>
      </div>

      <!-- 加题 -->
      <div class="add-row">
        <button class="btn btn-secondary" @click="addQuestion">
          <TikuIcon name="plus" :size="14" />
          添加题目
        </button>
      </div>

      <div class="confirm-bar">
        <span class="text-muted">共 {{ questions.length }} 题</span>
        <button class="btn btn-primary" :disabled="confirming" @click="confirm">
          <TikuIcon name="check" :size="15" />
          {{ confirming ? '导入中…' : '确认导入' }}
        </button>
      </div>

      <!-- 材料关联弹窗（材料素材区"关联"按钮 → 勾选题目批量设置 materialKey） -->
      <el-dialog v-model="materialLinkVisible" :title="`关联材料「${materialLinkKey}」到题目`" width="min(92vw, 480px)" align-center>
        <div v-if="questions.length === 0" class="text-muted">暂无可关联的题目</div>
        <div v-else class="material-link-list">
          <label v-for="(q, qi) in questions" :key="qi" class="material-link-item">
            <el-checkbox v-model="materialLinkSelection" :value="qi" />
            <span class="mono link-index">#{{ qi + 1 }}</span>
            <span class="link-content">{{ (q.content || '').replace(/\[图片\d+\]/g, '') || '（空题干）' }}</span>
          </label>
        </div>
        <template #footer>
          <button class="btn btn-secondary" @click="materialLinkVisible = false">取消</button>
          <button class="btn btn-primary" @click="applyMaterialLink">确定关联</button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmAiImport, deleteAiJob, getAiImportJob, getJobImageUrl, listJobImages, listMaterialSnippets } from '../api/aiImport'
import { sanitizeTableHtml, latexOnlyHtml } from '../utils/richText'
import TikuIcon from '../components/TikuIcon.vue'
import FieldImages from '../components/FieldImages.vue'
import QuestionNavDock from '../components/QuestionNavDock.vue'

const route = useRoute()
const router = useRouter()
const jobId = computed(() => Number(route.params.jobId))
const targetBankId = route.query.bankId ? Number(route.query.bankId) : null
// 每题"渲染预览"开关（题干/选项以渲染后的效果展示——公式 KaTeX、[图片N] → 任务图、表格）
const previewOpen = ref(new Set())
function togglePreview(qi) {
  const s = new Set(previewOpen.value)
  if (s.has(qi)) s.delete(qi)
  else s.add(qi)
  previewOpen.value = s
}

const typeOptions = [
  { value: 'SINGLE', label: '单选' },
  { value: 'MULTIPLE', label: '多选' },
  { value: 'JUDGE', label: '判断' },
  { value: 'SUBJECTIVE', label: '主观' }
]
const JUDGE_OPTIONS = [
  { key: 'A', text: '正确' },
  { key: 'B', text: '错误' }
]

const loading = ref(true)
const stageText = ref('')
const errorMsg = ref('')
const canceled = ref(false)
const jobConfirmed = ref(false)
const fileName = ref('')
const warningHint = ref('')
// 本次任务的处理配置（预览页展示实际走的路径意图，透明化 AUTO 判定）
const jobMeta = ref(null)
const ENGINE_LABEL = { AUTO: '智能推荐（自动路由）', LOCAL: '本地（直传视觉/文本）', MINERU: 'MinerU 增强' }
const questions = ref([])
// 共享材料（资料分析大题干，可编辑；确认导入时由后端一并入库）
const materials = ref([])
const confirming = ref(false)
const confirmed = ref(false)
const deleting = ref(false)
let timer = null

/* ---------- 素材区（图片 + 材料 Tab；手动补图 / 材料关联兜底） ---------- */
const jobImages = ref([]) // [{num, fileName, ext}]
const panelOpen = ref(true)
const panelTab = ref('images') // 'images' | 'materials'
const materialExpanded = ref(new Set()) // 已展开编辑的材料 key
const dragOverIndex = ref(null) // 排序拖拽悬停的卡片索引（高亮）

/* 材料关联弹窗（点击材料卡片"关联"按钮） */
const materialLinkVisible = ref(false)
const materialLinkKey = ref('')
const materialLinkSelection = ref([]) // 选中题目索引

async function loadImages() {
  try {
    jobImages.value = (await listJobImages(jobId.value)) || []
  } catch (e) {
    jobImages.value = []
  }
}

/* 材料素材：加载并合并进 materials（去重；已有编辑内容优先保留） */
async function loadMaterialSnippets() {
  try {
    const snips = (await listMaterialSnippets(jobId.value)) || []
    for (const s of snips) {
      if (s && s.materialKey && !materials.value.some((m) => m.materialKey === s.materialKey)) {
        materials.value.push({ materialKey: s.materialKey, content: s.content || '' })
      }
    }
  } catch (e) {
    /* 接口不可用（旧后端）时忽略 */
  }
}

function toggleMaterialExpand(key) {
  const next = new Set(materialExpanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  materialExpanded.value = next
}

/* 富文本渲染预览（题干/选项/材料共用）：表格 HTML → 白名单真实表格，[图片N] → 任务临时图，
 * $...$ LaTeX → KaTeX，其余文本转义（防 XSS）。先公式渲染（输出已转义 + KaTeX HTML），再图，再换行。 */
function qPreviewHtml(content) {
  if (!content) return ''
  const renderImgs = (html) =>
    String(html).replace(/\[图片(\d+)\]/g, (m, n) => {
      const url = getJobImageUrl(jobId.value, Number(n))
      return `<img class="snip-img" src="${url}" alt="${m}" loading="lazy">`
    })
  const segments = String(content).split(/(<table[\s>][\s\S]*?<\/table>)/gi)
  let html = ''
  for (const seg of segments) {
    if (!seg) continue
    if (/^<table[\s>]/i.test(seg)) {
      html += `<div class="rich-table-wrap">${renderImgs(sanitizeTableHtml(seg))}</div>`
    } else {
      html += renderImgs(latexOnlyHtml(seg))
    }
  }
  return html
}

/* 某材料被多少题关联 */
function linkedCount(materialKey) {
  return questions.value.filter((q) => q.materialKey === materialKey).length
}

/* 材料拖拽开始：携带 materialKey（与图片拖拽的 text/plain 区分） */
function onMaterialDragStart(e, materialKey) {
  e.dataTransfer.setData('application/x-tiku-material', materialKey)
  e.dataTransfer.effectAllowed = 'copy'
}

/* 题目卡片 drop：材料 → 设 materialKey；题目排序 → 移动卡片 */
function onCardDrop(e, toIndex) {
  dragOverIndex.value = null
  const matKey = e.dataTransfer?.getData('application/x-tiku-material') || ''
  if (matKey) {
    const q = questions.value[toIndex]
    if (q) q.materialKey = matKey
    return
  }
  const from = e.dataTransfer?.getData('application/x-tiku-question') || ''
  if (from !== '') {
    moveQuestion(Number(from), toIndex)
  }
}

/* 排序拖拽 */
function onQuestionDragStart(e, fromIndex) {
  e.dataTransfer.setData('application/x-tiku-question', String(fromIndex))
  e.dataTransfer.effectAllowed = 'move'
}
function moveQuestion(from, to) {
  if (from === to) return
  const arr = questions.value
  const [item] = arr.splice(from, 1)
  arr.splice(to, 0, item)
}

/* 插入新题（中间插入；提交时后端按新顺序补题号） */
function insertQuestion(index) {
  questions.value.splice(index, 0, emptyQuestion())
}

/* 材料关联弹窗 */
function openMaterialLink(materialKey) {
  materialLinkKey.value = materialKey
  materialLinkSelection.value = questions.value
    .map((q, i) => (q.materialKey === materialKey ? i : -1))
    .filter((i) => i >= 0)
  materialLinkVisible.value = true
}
function applyMaterialLink() {
  for (let i = 0; i < questions.value.length; i++) {
    questions.value[i].materialKey = materialLinkSelection.value.includes(i) ? materialLinkKey.value : null
  }
  materialLinkVisible.value = false
  ElMessage.success(`已更新材料「${materialLinkKey.value}」的关联`)
}

// 已引用编号（扫描题目/材料文本中的 [图片N]，素材区高亮"已用"）
const usedImageNums = computed(() => {
  const used = new Set()
  const scan = (text) => {
    if (!text) return
    for (const m of String(text).matchAll(/\[图片(\d+)\]/g)) used.add(Number(m[1]))
  }
  for (const q of questions.value) {
    scan(q.content)
    scan(q.referenceAnswer)
    scan(q.answerText)
    scan(q.analysis)
    for (const o of q.options || []) scan(o.text)
  }
  for (const m of materials.value) scan(m.content)
  return used
})

/* ---------- 图片待补充标记（交接包页面 1 兜底①：文档含图但本题未配图） ---------- */
const IMAGE_REF_RE = /\[图片\d+\]/
// 任务是否含图（素材区有图）
const jobHasImages = computed(() => jobImages.value.length > 0)
// 是否有任意题目引用了图片（说明自动配图机制在工作；纯文字文档不触发提示）
const anyQuestionHasImage = computed(() =>
  questions.value.some((q) =>
    [q.content, q.referenceAnswer, q.answerText, q.analysis, ...(q.options || []).map((o) => o.text)].some(
      (t) => t && IMAGE_REF_RE.test(t)
    )
  )
)
// 本题题干/选项是否完全无图片引用
function questionHasNoImage(q) {
  return ![q.content, ...(q.options || []).map((o) => o.text)].some((t) => t && IMAGE_REF_RE.test(t))
}
function needsImageFlag(q) {
  return jobHasImages.value && anyQuestionHasImage.value && questionHasNoImage(q)
}

// 拖拽开始：把 [图片N] 写入 dataTransfer（覆盖图片默认拖拽行为）
function onDragStart(e, num) {
  const dt = e.dataTransfer
  dt.setData('text/plain', `[图片${num}]`)
  dt.effectAllowed = 'copy'
}

// 拖放落点：在光标位置插入 [图片N]（el-input 内部 input/textarea 透传 drop；未聚焦则追加末尾）
function onDropImage(e, obj, field) {
  const marker = e.dataTransfer?.getData('text/plain') || ''
  if (!marker.startsWith('[图片')) return
  e.preventDefault()
  const el = e.target // 内部原生 input/textarea
  const value = obj[field] || ''
  const pos = el && typeof el.selectionStart === 'number' ? el.selectionStart : value.length
  obj[field] = value.slice(0, pos) + marker + value.slice(pos)
}

// 点击复制 [图片N] 到剪贴板（未聚焦输入框时用）
async function copyImageRef(num) {
  const marker = `[图片${num}]`
  try {
    await navigator.clipboard.writeText(marker)
    ElMessage.success(`已复制 ${marker}，粘贴到题目任意输入框`)
  } catch (e) {
    ElMessage.warning('复制失败，请手动输入 ' + marker)
  }
}

const backTo = computed(() => (targetBankId ? `/banks/${targetBankId}` : '/'))

// AI 补充答案的题数（需重点核对）
const aiSupplementCount = computed(() =>
  questions.value.filter((q) => q.answerSource === 'AI_SUPPLEMENT').length
)

// 暂无答案的题数（需补填；主观题不算"无答案"）
const noAnswerCount = computed(() =>
  questions.value.filter((q) => q.type !== 'SUBJECTIVE' && (!q.answerKeys || q.answerKeys.length === 0)).length
)

/* 重复题号统计（第十一轮：列表内 questionNumber 出现 >1 即标红，纯前端；异常题号如 189 会因此暴露） */
const duplicateNums = computed(() => {
  const count = new Map()
  for (const q of questions.value) {
    if (q.questionNumber != null) {
      count.set(q.questionNumber, (count.get(q.questionNumber) || 0) + 1)
    }
  }
  return new Set([...count.entries()].filter(([, c]) => c > 1).map(([n]) => n))
})

const keyOf = (i) => String.fromCharCode(65 + i)

function emptyQuestion() {
  return {
    type: 'SINGLE',
    content: '',
    options: [{ key: 'A', text: '' }, { key: 'B', text: '' }],
    answerKeys: [],
    score: 1,
    topic: '',
    category: '',
    answerText: '',
    analysis: '',
    referenceAnswer: '',
    materialKey: null, // 关联共享材料（可拖入/下拉/点击设置）
    questionNumber: null, // 源题号；提交前按预览顺序重排 1..N
    answerSource: null // 手动新增题无来源标记
  }
}

async function loadJob() {
  try {
    const job = await getAiImportJob(jobId.value)
    fileName.value = job.fileName || ''
    jobConfirmed.value = job.confirmed === true
    warningHint.value = job.warningHint || ''
    jobMeta.value = job.engine || job.processPath
      ? { engine: job.engine, processPath: job.processPath, thinking: job.thinking, aiSupplement: job.aiSupplement }
      : null
    if (job.status === 'CANCELED') {
      loading.value = false
      canceled.value = true
      errorMsg.value = '该任务已被取消，可彻底删除或返回'
      return
    }
    if (job.status === 'FAILED') {
      loading.value = false
      errorMsg.value = job.error || '处理失败'
      return
    }
    if (job.status === 'SUCCESS') {
      loading.value = false
      questions.value = (job.questions || []).map(normalize)
      // 共享材料（资料分析大题干，可编辑）
      materials.value = (job.materials || []).map((m) => ({ materialKey: m.materialKey, content: m.content || '' }))
      loadImages()
      loadMaterialSnippets()
      return
    }
    // 未完成：轮询
    const STAGE_TEXT = { PARSING: '解析文档中…', AI_GENERATING: 'AI 整理题目中…', VALIDATING: '校验题目中…' }
    stageText.value = STAGE_TEXT[job.stage] || '任务处理中…'
    clearInterval(timer)
    timer = setInterval(async () => {
      try {
        const j = await getAiImportJob(jobId.value)
        if (j.status === 'CANCELED') {
          clearInterval(timer)
          loading.value = false
          canceled.value = true
          errorMsg.value = '该任务已被取消，可彻底删除或返回'
        } else if (j.status === 'FAILED') {
          clearInterval(timer)
          loading.value = false
          errorMsg.value = j.error || '处理失败'
        } else if (j.status === 'SUCCESS') {
          clearInterval(timer)
          loading.value = false
          questions.value = (j.questions || []).map(normalize)
          materials.value = (j.materials || []).map((m) => ({ materialKey: m.materialKey, content: m.content || '' }))
          warningHint.value = j.warningHint || ''
          loadImages()
          loadMaterialSnippets()
        } else {
          stageText.value = STAGE_TEXT[j.stage] || '任务处理中…'
        }
      } catch (e) {
        //任务被物理删除（404）/后端异常：停止轮询并明确提示，避免无限 loading
        clearInterval(timer)
        loading.value = false
        errorMsg.value = e?.response?.status === 404 ? '任务不存在或已被清理' : '加载任务失败，请刷新重试'
      }
    }, 1500)
  } catch (e) {
    loading.value = false
    errorMsg.value = '任务不存在或已被清理'
  }
}

/* 归一化 AI 输出字段（type 枚举、选项 key 重排、score 默认 1；主观题默认 5） */
function normalize(q) {
  const type = ['SINGLE', 'MULTIPLE', 'JUDGE', 'SUBJECTIVE'].includes(q.type) ? q.type : 'SINGLE'
  let options = Array.isArray(q.options) ? q.options.map((o) => ({ key: o.key, text: o.text || '' })) : []
  if (type === 'JUDGE') {
    options = JUDGE_OPTIONS.map((o) => ({ ...o }))
  } else if (type === 'SUBJECTIVE') {
    options = [] // 主观题无选项
  } else {
    options = options.slice(0, 10)
    while (options.length < 2) options.push({ key: keyOf(options.length), text: '' })
    options.forEach((o, i) => { o.key = keyOf(i) })
  }
  const answerKeys = (Array.isArray(q.answerKeys) ? q.answerKeys : []).filter((k) => options.some((o) => o.key === k))
  // 答案来源标记：ORIGINAL=原文答案 / AI_SUPPLEMENT=AI 补充（旧任务数据可能为 null）
  // 防御性修正：标了"AI 补充"但答案为空 = 实际未补充（后端修复前的数据），按"无答案"展示，避免与统计文案矛盾
  let answerSource = q.answerSource === 'ORIGINAL' || q.answerSource === 'AI_SUPPLEMENT' ? q.answerSource : null
  if (answerSource === 'AI_SUPPLEMENT' && answerKeys.length === 0) answerSource = null
  return {
    type,
    content: q.content || '',
    options,
    answerKeys,
    score: Number(q.score) >= 1 ? Number(q.score) : type === 'SUBJECTIVE' ? 5 : 1,
    topic: q.topic || '',
    category: q.category || '',
    answerText: q.answerText || '',
    analysis: q.analysis || '',
    referenceAnswer: q.referenceAnswer || '',
    materialKey: q.materialKey || null, // 关联共享材料（预览编辑时勿删材料文本）
    questionNumber: q.questionNumber ?? null, // 源文档题号（后端回填；提交前按新顺序重排）
    answerSource
  }
}

function switchType(q, type) {
  if (q.type === type) return
  const wasJudge = q.type === 'JUDGE'
  q.type = type
  q.answerKeys = []
  if (type === 'JUDGE') {
    q.options = JUDGE_OPTIONS.map((o) => ({ ...o }))
  } else if (type === 'SUBJECTIVE') {
    q.options = []
    if (q.score === 1) q.score = 5
  } else {
    //判断题切回选择题：清掉"正确/错误"默认选项，恢复空白选项（否则切回后选项变成 A.正确 B.错误）
    if (wasJudge && q.options.length === 2
        && q.options[0].text === '正确' && q.options[1].text === '错误') {
      q.options = []
    }
    while (q.options.length < 2) q.options.push({ key: keyOf(q.options.length), text: '' })
    rekey(q)
    if (q.score === 5 && !q.referenceAnswer) q.score = 1
  }
}

function rekey(q) {
  q.options.forEach((o, i) => { o.key = keyOf(i) })
  const valid = new Set(q.options.map((o) => o.key))
  q.answerKeys = q.answerKeys.filter((k) => valid.has(k))
}

function addOption(q) {
  if (q.options.length >= 10) return
  q.options.push({ key: keyOf(q.options.length), text: '' })
}

function toggleMulti(q, key) {
  q.answerKeys = q.answerKeys.includes(key)
    ? q.answerKeys.filter((k) => k !== key)
    : [...q.answerKeys, key]
}

function removeQuestion(index) {
  if (questions.value.length <= 1) {
    ElMessage.warning('至少保留一题')
    return
  }
  questions.value.splice(index, 1)
}

/* 删除材料：关联题目解除引用（确认导入时后端按剩余材料映射） */
function removeMaterial(index) {
  const m = materials.value[index]
  if (!m) return
  materials.value.splice(index, 1)
  for (const q of questions.value) {
    if (q.materialKey === m.materialKey) q.materialKey = null
  }
  ElMessage.success(`已删除材料「${m.materialKey}」，关联题目已解除引用`)
}

function addQuestion() {
  questions.value.push(emptyQuestion())
}

async function confirm() {
  if (confirmed.value) {
    ElMessage.info('该任务已导入过')
    return
  }
  // 校验（失败即滚动高亮到该题卡片；提示同时带题号，避免与列表位置混淆）
  const qLabel = (q, i) => (q.questionNumber != null ? `列表第 ${i + 1} 题（#${q.questionNumber}）` : `列表第 ${i + 1} 题（未编号）`)
  for (let i = 0; i < questions.value.length; i++) {
    const q = questions.value[i]
    if (!q.content.trim()) {
      ElMessage.warning(`${qLabel(q, i)}题干为空`)
      focusCard(i)
      return
    }
    if (q.type !== 'JUDGE' && q.type !== 'SUBJECTIVE') {
      if (q.options.length < 2 || q.options.some((o) => !o.text.trim())) {
        ElMessage.warning(`${qLabel(q, i)}选项不完整（至少 2 个且文本非空）`)
        focusCard(i)
        return
      }
    }
    //答案不强制：无答案题可先导入（做题时无法判对错、不污染统计），之后在题库编辑页补配（红色圆钮会提示）
  }
  confirming.value = true
  try {
    // 题号规则：拖拽排序/插入新题后，按预览顺序重排 questionNumber = 1..N（后端按此落库）
    questions.value.forEach((q, i) => {
      q.questionNumber = i + 1
    })
    // 提交编辑后的完整题目 + 材料（后端以提交内容为准；引用材料的提交必须同时提交 materials）
    const payload = {
      questions: questions.value.map((q) => ({
        type: q.type,
        content: q.content,
        options: q.type === 'SUBJECTIVE' ? [] : q.options,
        answerKeys: q.type === 'SUBJECTIVE' ? [] : q.answerKeys,
        answerText: q.answerText || null,
        analysis: q.analysis || null,
        referenceAnswer: q.referenceAnswer || null,
        topic: q.topic || null,
        category: q.category || null,
        score: q.score,
        materialKey: q.materialKey || null,
        questionNumber: q.questionNumber,
        answerSource: q.answerSource || null
      })),
      materials: materials.value.map((m) => ({ materialKey: m.materialKey, content: m.content }))
    }
    const res = await confirmAiImport(jobId.value, targetBankId, payload)
    confirmed.value = true
    // 确认后任务不再出现在侧边栏"最近 AI 导入"（后端标记 confirmed）
    ElMessage.success(`已导入 ${res.importedCount} 道题目`)
    router.replace(`/banks/${res.bankId}`)
  } catch (e) {
    /* 拦截器已提示；后端兜底校验报"第 N 题…"时同样聚焦到该卡片（提交顺序 = 列表顺序） */
    const mm = String(e?.message || '').match(/第\s*(\d+)\s*题/)
    if (mm) {
      const i = Number(mm[1]) - 1
      if (i >= 0 && i < questions.value.length) focusCard(i)
    }
  } finally {
    confirming.value = false
  }
}

/* 校验失败聚焦：滚动到目标题卡片并短暂高亮（用户可立即定位修改） */
const flashIdx = ref(null)
let flashTimer = null
function focusCard(i) {
  if (i == null || i < 0) return
  flashIdx.value = i
  clearTimeout(flashTimer)
  nextTick(() => {
    document
      .querySelector(`[data-qidx="${i}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
  flashTimer = setTimeout(() => {
    if (flashIdx.value === i) flashIdx.value = null
  }, 3000)
}

/* 题号盘数据：预览列表顺序 = 卡片顺序（可拖拽排序/删除，计算属性实时跟随）；questionId 用数组下标充当。
   客观题未配答案 → 红色圆钮（一屏定位待补答案的题；主观题答案走自评/参考答案，不标） */
const dockJumpItems = computed(() =>
  questions.value.map((q, i) => ({
    questionId: i,
    questionNumber: q.questionNumber ?? i + 1,
    status: q.type !== 'SUBJECTIVE' && (!q.answerKeys || !q.answerKeys.length) ? 'no' : undefined
  }))
)

/* ---------- 取消导入（放弃本任务） ---------- */
const canceling = ref(false)

async function cancel() {
  if (canceling.value) return
  try {
    await ElMessageBox.confirm(
      '取消后将删除该任务的整理结果，且无法恢复。确定取消导入吗？',
      '取消导入',
      {
        type: 'warning',
        confirmButtonText: '取消导入',
        cancelButtonText: '再想想',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (e) {
    return // 用户放弃取消
  }
  canceling.value = true
  try {
    await deleteAiJob(jobId.value)
    ElMessage.success('已取消导入')
    router.replace(backTo.value)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    canceling.value = false
  }
}

/* 已取消任务：彻底删除（记录仍在，供二次删除） */
async function deleteAgain() {
  if (deleting.value) return
  deleting.value = true
  try {
    await deleteAiJob(jobId.value)
    ElMessage.success('任务已删除')
    router.replace(backTo.value)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    deleting.value = false
  }
}

onMounted(loadJob)

//离开预览页：停止轮询（防止反复进出同一任务叠加多个轮询流；jobId 切换时 watch 内也会清理）
onBeforeUnmount(() => clearInterval(timer))

// 多任务切换（/ai-import/1 → /ai-import/2）：组件复用，重置状态并重新加载
watch(
  () => route.params.jobId,
  () => {
    clearInterval(timer)
    loading.value = true
    errorMsg.value = ''
    canceled.value = false
    jobConfirmed.value = false
    fileName.value = ''
    warningHint.value = ''
    questions.value = []
    materials.value = []
    jobImages.value = []
    loadJob()
  }
)
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  padding: 0;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 13px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: color var(--ease);
}
.back-link:hover {
  color: var(--accent-text);
}
.title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 26px;
}
.file-tag {
  font-size: 12px;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 2px 10px;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.page-desc {
  margin: 6px 0 0;
  font-size: 13px;
}
.ai-supp-count {
  color: var(--warning);
  font-weight: 600;
}
.no-answer-count {
  color: var(--danger);
  font-weight: 600;
}

/* 答案来源标记 */
.src-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 999px;
  font-weight: 500;
}
.src-original {
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success);
}
.src-ai {
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning);
}
.src-no-answer {
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger);
}
.src-material {
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
}
.src-no-image {
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px dashed var(--warning);
}

/* 预览材料编辑区 */
.preview-materials {
  margin-bottom: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.preview-materials .section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.material-note {
  font-size: 12px;
}
.material-edit-card {
  padding: 12px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border-strong);
  border-left: 3px solid var(--accent);
  border-radius: 10px;
}
.material-edit-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.material-key-tag {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 1px 9px;
}

.preview-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.preview-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow: hidden;
}
/* 校验失败聚焦：目标卡片短暂高亮闪烁（配合自动滚动定位） */
.card-flash {
  box-shadow: inset 0 0 0 2px var(--accent);
}
.card-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.q-index {
  color: var(--text-muted);
  font-size: 13px;
}
.q-index.unnumbered {
  color: var(--text-muted);
  background: var(--bg-elev);
  border: 1px dashed var(--border-strong);
  border-radius: 6px;
  padding: 1px 7px;
  font-size: 12px;
}
.q-index.dup-number {
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger);
  border-radius: 6px;
  padding: 1px 7px;
  font-size: 12px;
}
.card-score {
  margin-left: auto;
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  border-radius: 7px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--ease);
}
.icon-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.icon-btn.danger:hover {
  background: var(--danger-soft);
  color: var(--danger);
}
.icon-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.card-body {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.type-tabs {
  display: inline-flex;
  gap: 4px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 3px;
}
.type-tab {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 13px;
  padding: 4px 14px;
  border-radius: 6px;
  cursor: pointer;
  transition: all var(--ease);
}
.type-tab:hover {
  color: var(--text-primary);
}
.type-tab.active {
  background: var(--accent);
  color: #fff;
  font-weight: 500;
}

.field-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.required {
  color: var(--danger);
}

.option-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.option-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.option-main {
  flex: 1;
  min-width: 0;
}
.option-row .el-input {
  flex: 1;
}
.opt-key {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.answer-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.answer-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 13px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  transition: border-color var(--ease), background var(--ease);
}
.answer-row:hover {
  border-color: var(--border-strong);
}
.answer-row.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.answer-dot {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 2px solid var(--text-muted);
  flex-shrink: 0;
}
.answer-row.selected .answer-dot {
  border-color: var(--accent);
  background: var(--accent);
  box-shadow: inset 0 0 0 3px var(--bg-elev);
}
.answer-check {
  width: 13px;
  height: 13px;
  border-radius: 4px;
  border: 2px solid var(--text-muted);
  flex-shrink: 0;
}
.answer-row.selected .answer-check {
  border-color: var(--accent);
  background: var(--accent);
}
.answer-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.judge-answer {
  display: flex;
  gap: 12px;
}
.judge-btn {
  flex: 1;
  max-width: 160px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 0;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--ease);
}
.judge-btn:hover {
  border-color: var(--border-strong);
}
.judge-btn.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-text);
}

.field-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.add-row {
  display: flex;
  justify-content: center;
  padding: 18px 0 8px;
}

.confirm-bar {
  position: sticky;
  bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-overlay);
  margin-top: 8px;
}
.confirm-bar .text-muted {
  font-size: 13px;
}

.stage-spin {
  display: inline-flex;
  color: var(--accent-text);
  animation: spin 1.2s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.empty,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 90px 0;
  color: var(--text-muted);
  text-align: center;
}
.empty h3,
.error-state h3 {
  color: var(--text-primary);
  font-size: 16px;
}
.error-actions {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}
.error-state .btn {
  margin-top: 0;
}
.confirmed-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 16px;
  border-radius: 12px;
  background: var(--success-soft);
  border: 1px solid var(--success);
  color: var(--success);
  font-size: 13px;
  margin-bottom: 16px;
}
.material-tip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 16px;
  border-radius: 12px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  color: var(--accent-text);
  font-size: 13px;
  margin-bottom: 16px;
}
.warning-banner {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 11px 16px;
  border-radius: 12px;
  background: var(--warning-soft);
  border: 1px solid var(--warning);
  color: var(--warning);
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 16px;
}
.warning-banner :deep(svg) {
  flex-shrink: 0;
  margin-top: 2px;
}

/* ---------- 图片素材区（右侧抽屉） ---------- */
.image-panel {
  position: fixed;
  right: 18px;
  top: 84px;
  width: 264px;
  max-height: 72vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: var(--shadow-overlay);
  z-index: 60;
  overflow: hidden;
}
/* 题号盘嵌入抽屉：去卡片阴影，网格高度适配抽屉滚动区（避免双层滚动） */
.dock-in-drawer :deep(.qnav-dock) {
  box-shadow: none;
  border: none;
  background: transparent;
  padding: 4px 2px;
}
.dock-in-drawer :deep(.qnav-grid) {
  max-height: 38vh;
}
.image-panel.collapsed {
  max-height: none;
}
.image-panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid var(--border);
}
.image-panel.collapsed .image-panel-head {
  border-bottom: none;
}
.panel-toggle {
  margin-left: auto;
  color: var(--text-muted);
}
.image-panel-body {
  overflow-y: auto;
  max-height: 60vh;
  padding: 10px 12px 14px;
}
.image-panel-tip {
  font-size: 12px;
  line-height: 1.6;
  margin: 0 0 10px;
}
.no-answer-hint {
  color: var(--danger);
  font-weight: 600;
}
.image-panel-tip code {
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
  font-size: 11px;
}
.image-empty {
  font-size: 13px;
  text-align: center;
  padding: 18px 0;
}
.image-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.image-thumb {
  position: relative;
  aspect-ratio: 1;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border);
  background: var(--bg-elev);
  cursor: grab;
}
.image-thumb:active {
  cursor: grabbing;
}
.image-thumb.used {
  border-color: var(--success);
}
.image-thumb :deep(.el-image) {
  width: 100%;
  height: 100%;
  display: block;
}
.image-thumb :deep(.el-image__inner) {
  width: 100%;
  height: 100%;
}
.num-badge {
  position: absolute;
  left: 4px;
  bottom: 4px;
  background: var(--bg-base);
  border: 1px solid var(--border);
  color: var(--text-primary);
  font-size: 11px;
  padding: 0 5px;
  border-radius: 5px;
  line-height: 16px;
  pointer-events: none;
}
.used-dot {
  position: absolute;
  right: 4px;
  top: 4px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 2px var(--bg-base);
  pointer-events: none;
}
.copy-btn {
  position: absolute;
  right: 3px;
  bottom: 3px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  border: 1px solid var(--border-strong);
  background: var(--bg-base);
  color: var(--text-primary);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.image-thumb:hover .copy-btn {
  opacity: 1;
}
.copy-btn:hover {
  border-color: var(--accent);
  color: var(--accent-text);
}

/* 素材区 Tab */
.panel-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 10px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 3px;
}
.panel-tabs button {
  flex: 1;
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 13px;
  padding: 5px 0;
  border-radius: 6px;
  cursor: pointer;
}
.panel-tabs button.active {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
}

/* 材料素材卡片 */
.material-snippet-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.material-snippet {
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--bg-elev);
  overflow: hidden;
  cursor: grab;
}
.material-snippet:active {
  cursor: grabbing;
}
.material-snippet.linked {
  border-color: var(--success);
}
.material-snippet-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  border-bottom: 1px solid var(--border);
}
.snippet-count {
  font-size: 11px;
}
.snippet-link-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  border: 1px solid var(--border);
  background: none;
  color: var(--accent-text);
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 6px;
  cursor: pointer;
}
.snippet-link-btn:hover {
  border-color: var(--accent);
}
.snippet-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 1px solid var(--border);
  background: none;
  color: var(--text-muted);
  border-radius: 6px;
  cursor: pointer;
}
.snippet-toggle:hover {
  color: var(--text-primary);
}
.material-snippet-preview {
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
  max-height: 96px;
  overflow: hidden;
  cursor: pointer;
}
.material-snippet-preview :deep(.snip-img) {
  max-width: 64px;
  max-height: 48px;
  border-radius: 5px;
  border: 1px solid var(--border);
  margin: 0 3px 2px 0;
  vertical-align: middle;
}
.material-snippet-edit {
  padding: 8px 10px;
}
.material-snippet-edit textarea {
  width: 100%;
  min-height: 64px;
  resize: vertical;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
  padding: 8px 10px;
  outline: none;
}
.material-snippet-edit textarea:focus {
  border-color: var(--accent);
}

/* 题目卡片：拖柄 / 排序高亮 / 插入行 */
.drag-handle {
  display: inline-flex;
  align-items: center;
  color: var(--text-muted);
  cursor: grab;
  opacity: 0.55;
  padding: 2px 1px;
}
.drag-handle:hover {
  opacity: 1;
  color: var(--text-primary);
}
.preview-card.drag-over {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent) inset;
}
.insert-row {
  display: flex;
  justify-content: center;
  padding: 2px 0 8px;
}
.insert-row .btn {
  opacity: 0.65;
}
.insert-row:hover .btn {
  opacity: 1;
}

/* 关联材料徽标可点击 */
.src-material.clickable {
  cursor: pointer;
}
.src-material.clickable:hover {
  border-color: var(--accent);
}
.tag-x {
  opacity: 0.7;
}

/* 材料关联弹窗 */
.material-link-list {
  max-height: 46vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.material-link-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 7px;
  cursor: pointer;
  font-size: 13px;
}
.material-link-item:hover {
  background: var(--bg-elev);
}
.link-index {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 12px;
  padding-top: 1px;
}
.link-content {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
}
/* 渲染预览（公式 KaTeX + 图 + 表格） */
.preview-block {
  margin: 4px 14px 8px;
  padding: 10px 12px;
  background: var(--bg-elev);
  border: 1px dashed var(--border-color, #d9d9d9);
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.7;
}
.preview-title {
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 1px;
  margin-bottom: 6px;
  text-transform: uppercase;
}
.preview-row {
  word-break: break-word;
}
.preview-row + .preview-row {
  margin-top: 4px;
}
.preview-option {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.preview-label {
  color: var(--text-muted);
  font-size: 12px;
}
.preview-block img.snip-img,
.preview-block img.rich-img {
  max-width: 260px;
  max-height: 220px;
  object-fit: contain;
  border: 1px solid var(--border-color, #eee);
  border-radius: 6px;
  margin: 4px 0;
  background: #fff;
}
.icon-btn.active {
  color: var(--accent, #409eff);
  background: color-mix(in srgb, var(--accent, #409eff) 12%, transparent);
}
</style>
