<template>
  <div class="page">
    <!-- 加载/处理中 -->
    <div v-if="loading" class="empty">
      <div class="stage-spin"><TikuIcon name="refresh" :size="26" /></div>
      <h3>{{ stageText || t('processing') }}</h3>
      <p class="text-secondary">{{ t('workingTip') }}</p>
    </div>

    <!-- 失败 / 已取消 -->
    <div v-else-if="errorMsg" class="error-state">
      <TikuIcon :name="canceled ? 'x' : 'x'" :size="40" />
      <h3>{{ canceled ? t('canceled') : t('failed') }}</h3>
      <p class="text-secondary">{{ errorMsg }}</p>
      <div class="error-actions">
        <button v-if="canceled" class="btn btn-danger" :disabled="deleting" @click="deleteAgain">
          <TikuIcon name="trash" :size="14" />
          {{ deleting ? t('deleting') : t('deleteTask') }}
        </button>
        <button class="btn btn-secondary" @click="$router.push(backTo)">{{ t('back') }}</button>
      </div>
    </div>

    <!-- 空结果 -->
    <div v-else-if="questions.length === 0" class="error-state">
      <TikuIcon name="file" :size="40" />
      <h3>{{ t('emptyTitle') }}</h3>
      <p class="text-secondary">{{ t('emptyTip') }}</p>
      <button class="btn btn-secondary" @click="$router.push('/')">{{ t('backToBanks') }}</button>
    </div>

    <template v-else>
      <header class="page-header">
        <div>
          <button class="back-link" @click="$router.push(backTo)">
            <TikuIcon name="arrow-left" :size="14" />
            {{ t('back') }}
          </button>
          <div class="title-line">
            <h1 class="page-title">{{ t('title') }}</h1>
            <span class="file-tag">{{ fileName }}</span>
          </div>
          <p class="page-desc text-muted">
            {{ t('genSummary', { total: questions.length, ai: aiSupplementCount }) }}
            <template v-if="noAnswerCount > 0">{{ t('noAnswerNote', { n: noAnswerCount }) }}</template>{{ t('editableNote') }}
          </p>
          <p v-if="jobMeta" class="page-desc text-muted proc-line">
            本次处理：{{ jobMeta.processPath || ENGINE_LABEL[jobMeta.engine] || jobMeta.engine }} · 思考{{ jobMeta.thinking ? '开' : '关' }} · AI 补充{{ jobMeta.aiSupplement ? '开' : '关' }}
          </p>
        </div>
        <div class="header-actions">
          <!-- 全局编辑态开关（合并为一个切换按钮）：全部已展开 → 「全部收起」；有任意一题未展开 → 「全部展开编辑」
               题目列表为空时不显示（空列表时整页走上面的空结果分支，这里只是防御性判断） -->
          <button
            v-if="questions.length > 0"
            class="btn btn-secondary btn-sm"
            :title="allEditing ? t('collapseAllTip') : t('expandAllEditTip')"
            @click="toggleAllEdit"
          >
            <TikuIcon :name="allEditing ? 'chevron-up' : 'list'" :size="14" />
            {{ allEditing ? t('collapseAll') : t('expandAllEdit') }}
          </button>
          <button class="btn btn-danger" :disabled="canceling" @click="cancel">
            <TikuIcon name="trash" :size="14" />
            {{ canceling ? t('cancelingImport') : t('cancelImport') }}
          </button>
          <button class="btn btn-primary" :disabled="confirming" @click="confirm">
            <TikuIcon name="check" :size="15" />
            {{ confirming ? t('importing') : t('confirmImport') }}
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
            :class="{
              'drag-over': dragOverIndex === qi,
              'card-flash': flashIdx === qi,
              'card-invalid': stemInvalid(q) || optionsInvalid(q)
            }"
            @dragover.prevent="dragOverIndex = qi"
            @dragleave="dragOverIndex = null"
            @drop="onCardDrop($event, qi)"
          >
            <div class="card-head">
              <span
                class="drag-handle"
                draggable="true"
                :title="t('dragSort')"
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
              >{{ t('dupNumber', { n: q.questionNumber }) }}</span>
              <span v-else-if="q.questionNumber != null" class="q-index mono">#{{ q.questionNumber }}</span>
              <span v-else class="q-index mono unnumbered" title="后端未能回填题号（公式/跨行/图片转写差异导致），可拖拽归位或提交时自动补号">{{ t('unnumbered') }}</span>
              <!-- 答案来源标记：ORIGINAL=原文答案 / AI_SUPPLEMENT=AI 补充（需重点核对） -->
              <span v-if="q.answerSource === 'ORIGINAL'" class="src-tag src-original" title="答案来自源文档原文">
                <TikuIcon name="check" :size="11" />
                {{ t('srcOriginal') }}
              </span>
              <span v-else-if="q.answerSource === 'AI_SUPPLEMENT'" class="src-tag src-ai" title="源文档无答案，由 AI 补充，请重点核对">
                <TikuIcon name="sparkle" :size="11" />
                {{ t('srcAi') }}
              </span>
              <!-- 无答案：标红提醒补填（aiSupplement=false 或原文缺失时） -->
              <span v-else-if="!q.answerKeys.length" class="src-tag src-no-answer" title="本题暂无答案，请补填后导入">
                <TikuIcon name="x" :size="11" />
                {{ t('noAnswerTag') }}
              </span>
              <!-- 关联材料：渲染态只读展示（材料：M1）；编辑态可点击解除关联 -->
              <span v-if="q.materialKey && !isEditing(q)" class="src-tag src-material">
                <TikuIcon name="file" :size="11" />
                {{ t('materialTag', { k: q.materialKey }) }}
              </span>
              <span
                v-else-if="q.materialKey"
                class="src-tag src-material clickable"
                :title="t('materialUnlink')"
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
                {{ t('needImageTag') }}
              </span>
              <!-- 校验提示（与导入前校验同规则）：长列表里可直接看到哪几题待修；不做筛选 -->
              <span v-if="stemInvalid(q)" class="src-tag src-invalid" :title="t('warnStemEmpty', { pos: qPosText(q, qi) })">
                <TikuIcon name="x" :size="11" />
                {{ t('emptyContent') }}
              </span>
              <span v-if="optionsInvalid(q)" class="src-tag src-invalid" :title="t('warnOptIncomplete', { pos: qPosText(q, qi) })">
                <TikuIcon name="x" :size="11" />
                {{ t('incompleteOptions') }}
              </span>
              <!-- 题型：编辑态可点击切换；渲染态只读标签 -->
              <div v-if="isEditing(q)" class="type-tabs">
                <button
                  v-for="tp in typeOptions"
                  :key="tp.value"
                  class="type-tab"
                  :class="{ active: q.type === tp.value }"
                  @click="switchType(q, tp.value)"
                >
                  {{ tp.label }}
                </button>
              </div>
              <span v-else class="type-label">{{ typeLabel(q.type) }}</span>
              <!-- 分值：编辑态可改；渲染态只读展示 -->
              <span v-if="isEditing(q)" class="card-score">
                <el-input-number v-model="q.score" :min="1" :max="100" size="small" controls-position="right" style="width: 110px" />
              </span>
              <span v-else class="card-score-text mono">{{ t('scoreTag', { n: q.score }) }}</span>
              <!-- 单题编辑态开关：渲染态点「编辑」展开表单，编辑态点「完成」收起（自动回到渲染态） -->
              <button v-if="!isEditing(q)" class="icon-btn" :title="t('editThis')" @click="openEdit(q)">
                <TikuIcon name="edit" :size="14" />
              </button>
              <button v-else class="btn btn-secondary btn-sm" :title="t('doneEdit')" @click="closeEdit(q)">
                <TikuIcon name="check" :size="14" />
                {{ t('doneEdit') }}
              </button>
              <button class="icon-btn danger" :title="t('deleteThis')" @click="removeQuestion(qi)">
                <TikuIcon name="trash" :size="14" />
              </button>
            </div>
          <!-- 渲染态（默认主视图，只读所见即所得）：题干/选项/答案/解析 全部走 KaTeX 公式 + [图片N] 任务图 + 表格渲染 -->
          <div v-if="!isEditing(q)" class="render-block">
            <!-- 题干 -->
            <div v-if="!stemInvalid(q)" class="render-stem" v-html="qPreviewHtml(q.content)"></div>
            <div v-else class="render-stem is-empty">{{ t('emptyContent') }}</div>

            <!-- 选项：判断题固定 A.正确 / B.错误；主观题无选项 -->
            <div v-if="q.type === 'JUDGE'" class="render-judge">
              <span class="render-judge-item"><span class="opt-key">A</span>{{ t('judgeTrue') }}</span>
              <span class="render-judge-item"><span class="opt-key">B</span>{{ t('judgeFalse') }}</span>
            </div>
            <div v-else-if="q.type !== 'SUBJECTIVE'" class="render-options">
              <div v-for="opt in q.options" :key="opt.key" class="render-option">
                <span class="opt-key">{{ opt.key }}</span>
                <span class="render-option-text" v-html="qPreviewHtml(opt.text) || t('optionEmpty', { k: opt.key })"></span>
              </div>
            </div>

            <!-- 答案：客观题 = 正确答案（选项字母 + 答案文字）；主观题 = 参考答案 -->
            <div class="answer-block" :class="{ 'is-ai': q.answerSource === 'AI_SUPPLEMENT' }">
              <div class="answer-head">
                <span class="answer-label">{{ q.type === 'SUBJECTIVE' ? t('refAnswer') : t('answerLabel') }}</span>
                <!-- AI 补答案标记（醒目 + 底色高亮）：源文档无答案、由 AI 生成，导入前请核对 -->
                <span v-if="q.answerSource === 'AI_SUPPLEMENT'" class="ai-supp-tag">
                  <TikuIcon name="sparkle" :size="11" />
                  {{ t('aiSupplementTag') }}
                </span>
              </div>
              <template v-if="q.type === 'SUBJECTIVE'">
                <div v-if="q.referenceAnswer" class="answer-body" v-html="qPreviewHtml(q.referenceAnswer)"></div>
                <div v-else class="answer-empty">{{ t('notProvided') }}</div>
              </template>
              <template v-else>
                <div class="answer-keys">
                  <span v-if="q.type === 'JUDGE' && q.answerKeys.length" class="answer-key mono">
                    {{ q.answerKeys[0] === 'A' ? t('judgeTrue') : t('judgeFalse') }}
                  </span>
                  <span v-else-if="q.answerKeys.length" class="answer-key mono">{{ q.answerKeys.join(t('answerSep')) }}</span>
                  <span v-else class="answer-empty">{{ t('noAnswerTag') }}</span>
                </div>
                <div v-if="q.answerText" class="answer-body" v-html="qPreviewHtml(q.answerText)"></div>
              </template>
            </div>

            <!-- 解析：默认展示；过长（> ~6 行 / ~400 字）折叠，可展开全部 -->
            <div v-if="q.analysis" class="analysis-block">
              <div class="analysis-head">
                <span class="answer-label">{{ t('analysisLabel') }}</span>
                <button v-if="isLongText(q.analysis)" class="link-btn" @click="toggleAnalysis(q)">
                  {{ isAnalysisOpen(q) ? t('collapse') : t('expandFull') }}
                  <TikuIcon :name="isAnalysisOpen(q) ? 'chevron-up' : 'chevron-down'" :size="12" />
                </button>
              </div>
              <div
                class="analysis-body"
                :class="{ 'is-collapsed': isLongText(q.analysis) && !isAnalysisOpen(q) }"
                v-html="qPreviewHtml(q.analysis)"
              ></div>
            </div>
          </div>

          <!-- 编辑态表单（点「编辑」展开，点「完成」收起回到渲染态；表单能力与原来完全一致） -->
          <div v-else class="card-body">
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
              {{ t('insertHere') }}
            </button>
          </div>
        </div>
      </div>

      <!-- 加题 -->
      <div class="add-row">
        <button class="btn btn-secondary" @click="addQuestion">
          <TikuIcon name="plus" :size="14" />
          {{ t('addQuestion') }}
        </button>
      </div>

      <div class="confirm-bar">
        <span class="text-muted">{{ t('totalN', { n: questions.length }) }}</span>
        <button class="btn btn-primary" :disabled="confirming" @click="confirm">
          <TikuIcon name="check" :size="15" />
          {{ confirming ? t('importing') : t('confirmImport') }}
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, toRaw, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      title: 'AI 导入预览', processing: '任务处理中…', workingTip: 'AI 正在整理题目，请稍候',
      canceled: '任务已取消', failed: '导入失败', deleting: '删除中…', deleteTask: '彻底删除此任务',
      back: '返回', emptyTitle: '未能从文档提取题目', emptyTip: '请调整文档内容或更换模型后重试', backToBanks: '返回题库列表',
      genSummary: 'AI 共生成 {total} 题，其中 {ai} 题为 AI 补充答案（需重点核对）',
      noAnswerNote: '，{n} 题暂无答案待补填', editableNote: '，可编辑后确认导入',
      matLinked: '已更新材料「{key}」的关联', imgCopied: '已复制 {marker}，粘贴到题目任意输入框', imgCopyFail: '复制失败，请手动输入 {marker}', keepOneQ: '至少保留一题',
      matDeleted: '已删除材料「{key}」，关联题目已解除引用', alreadyImported: '该任务已导入过',
      listPosNum: '列表第 {i} 题（#{n}）', listPosNoNum: '列表第 {i} 题（未编号）',
      warnStemEmpty: '{pos}题干为空', warnOptIncomplete: '{pos}选项不完整（至少 2 个且文本非空）',
      importedN: '已导入 {n} 道题目', cancelAsk: '取消后将删除该任务的整理结果，且无法恢复。确定取消导入吗？',
      cancelTitle: '取消导入', canceledDone: '已取消导入', taskDeleted: '任务已删除',
      // ---- 渲染态 / 编辑态（默认渲染，按需展开编辑） ----
      editThis: '编辑此题', doneEdit: '完成', expandAllEdit: '全部展开编辑', collapseAll: '全部收起',
      expandAllEditTip: '把所有题目切换为编辑态（表单）', collapseAllTip: '把所有题目收起为渲染态（只读预览）',
      dragSort: '拖动排序', unnumbered: '未编号', dupNumber: '重复题号 {n}', deleteThis: '删除此题',
      insertHere: '在此插入新题', addQuestion: '添加题目', totalN: '共 {n} 题',
      emptyContent: '题干为空', incompleteOptions: '选项不完整',
      // ---- 渲染态字段标签 ----
      answerLabel: '正确答案', refAnswer: '参考答案', analysisLabel: '解析', notProvided: '未提供',
      materialTag: '材料：{k}', materialUnlink: '已关联共享材料，点击解除关联',
      aiSupplementTag: 'AI 补答案 · 请核对', expandFull: '展开全部', collapse: '收起',
      scoreTag: '{n} 分', optionEmpty: '（选项 {k} 为空）', answerSep: '、',
      judgeTrue: '正确', judgeFalse: '错误',
      typeSingle: '单选', typeMultiple: '多选', typeJudge: '判断', typeSubjective: '主观',
      srcOriginal: '原文答案', srcAi: 'AI 补充', noAnswerTag: '无答案', needImageTag: '图片待补充',
      cancelImport: '取消导入', cancelingImport: '取消中…', confirmImport: '确认导入', importing: '导入中…'
    },
    'en-US': {
      title: 'AI Import Preview', processing: 'Processing the task…', workingTip: 'AI is organizing the questions, please wait',
      canceled: 'Task canceled', failed: 'Import failed', deleting: 'Deleting…', deleteTask: 'Delete this task permanently',
      back: 'Back', emptyTitle: 'No questions could be extracted', emptyTip: 'Adjust the document or switch models and retry', backToBanks: 'Back to banks',
      genSummary: '{total} questions generated, {ai} with AI-filled answers (please verify carefully)',
      noAnswerNote: ', {n} without answers yet', editableNote: ' — edit, then confirm the import',
      matLinked: 'Updated the material “{key}” links', imgCopied: 'Copied {marker} — paste it into any question input', imgCopyFail: 'Copy failed — type {marker} manually', keepOneQ: 'Keep at least one question',
      matDeleted: 'Deleted material “{key}”; linked questions were unlinked', alreadyImported: 'This task has already been imported',
      listPosNum: '#{n} (item {i} in the list)', listPosNoNum: 'unnumbered item {i} in the list',
      warnStemEmpty: 'Question {pos} has an empty stem', warnOptIncomplete: 'Question {pos} has incomplete options (need at least 2, all with text)',
      importedN: 'Imported {n} questions', cancelAsk: 'Canceling deletes this task’s results and cannot be undone. Cancel the import?',
      cancelTitle: 'Cancel import', canceledDone: 'Import canceled', taskDeleted: 'Task deleted',
      // ---- rendered / edit mode (rendered by default, edit on demand) ----
      editThis: 'Edit this question', doneEdit: 'Done', expandAllEdit: 'Edit all', collapseAll: 'Collapse all',
      expandAllEditTip: 'Switch every question to edit mode (form)', collapseAllTip: 'Collapse every question back to the rendered view',
      dragSort: 'Drag to reorder', unnumbered: 'Unnumbered', dupNumber: 'Duplicate #{n}', deleteThis: 'Delete this question',
      insertHere: 'Insert a question here', addQuestion: 'Add question', totalN: '{n} questions in total',
      emptyContent: 'Empty stem', incompleteOptions: 'Incomplete options',
      // ---- rendered fields ----
      answerLabel: 'Correct answer', refAnswer: 'Reference answer', analysisLabel: 'Analysis', notProvided: 'Not provided',
      materialTag: 'Material: {k}', materialUnlink: 'Linked shared material — click to unlink',
      aiSupplementTag: 'AI-filled answer · please verify', expandFull: 'Show all', collapse: 'Collapse',
      scoreTag: '{n} pts', optionEmpty: '(option {k} is empty)', answerSep: ', ',
      judgeTrue: 'True', judgeFalse: 'False',
      typeSingle: 'Single', typeMultiple: 'Multiple', typeJudge: 'True/False', typeSubjective: 'Subjective',
      srcOriginal: 'From source', srcAi: 'AI-filled', noAnswerTag: 'No answer', needImageTag: 'Image missing',
      cancelImport: 'Cancel import', cancelingImport: 'Canceling…', confirmImport: 'Confirm import', importing: 'Importing…'
    }
  }
})
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
/* ---------- 渲染态 / 编辑态 ----------
 * 默认全部为渲染态（所见即所得：公式 KaTeX、[图片N] 任务图、表格），需要时按题展开编辑表单。
 * editOpen 存"题目对象本身"而非数组下标：拖拽排序/删除题目后不会串题；空集合 = 全部渲染态。
 * 用 shallowRef（不是 ref）：ref 会把 Set 也包成响应式，而响应式 Set 迭代时会把元素重新包成代理，
 * 导致 add(toRaw(q)) 与 delete(toRaw(q)) 不对称（删不掉、集合只增不减）。shallowRef 保持原生 Set，
 * 只要每次整体替换 Set 就能触发重渲染，同时 toRaw 归一化保证代理/原始对象双向都能命中。
 */
const editOpen = shallowRef(new Set())
// 已展开全部的长解析（默认折叠，按需展开）
const analysisExpanded = shallowRef(new Set())
function isEditing(q) {
  return editOpen.value.has(toRaw(q))
}
function openEdit(q) {
  const s = new Set(editOpen.value)
  s.add(toRaw(q))
  editOpen.value = s
}
function closeEdit(q) {
  const s = new Set(editOpen.value)
  s.delete(toRaw(q))
  editOpen.value = s
}
/* 全部展开编辑 / 全部收起（页头合并后的单按钮切换开关） */
function expandAllEdit() {
  editOpen.value = new Set(questions.value.map((q) => toRaw(q)))
}
function collapseAllEdit() {
  editOpen.value = new Set()
  analysisExpanded.value = new Set() // 一并收起已展开的长解析，彻底回到渲染态
}
/* 是否"所有题目都处于编辑态"：用于切换按钮的图标 / 文案 / 点击方向
   （有任意一题未展开 → 显示「全部展开编辑」） */
const allEditing = computed(
  () => questions.value.length > 0 && questions.value.every((q) => isEditing(q))
)
/* 页头切换按钮：非"全部展开"状态 → 全部展开；已"全部展开" → 全部收起 */
function toggleAllEdit() {
  if (allEditing.value) collapseAllEdit()
  else expandAllEdit()
}

/* 长文本折叠阈值：超过 ~6 行或 ~400 字才折叠（短解析默认完整展示） */
const ANALYSIS_COLLAPSE_CHARS = 400
const ANALYSIS_COLLAPSE_LINES = 6
function isLongText(text) {
  const s = String(text || '')
  return s.length > ANALYSIS_COLLAPSE_CHARS || (s.match(/\n/g) || []).length >= ANALYSIS_COLLAPSE_LINES
}
function isAnalysisOpen(q) {
  return analysisExpanded.value.has(toRaw(q))
}
function toggleAnalysis(q) {
  const s = new Set(analysisExpanded.value)
  if (s.has(toRaw(q))) s.delete(toRaw(q))
  else s.add(toRaw(q))
  analysisExpanded.value = s
}

/* 校验态（与 confirm() 导入前校验同规则）：渲染态卡片上标出问题题，长列表里可直接定位；不做筛选 */
function stemInvalid(q) {
  return !q.content || !q.content.trim()
}
function optionsInvalid(q) {
  if (q.type === 'JUDGE' || q.type === 'SUBJECTIVE') return false
  return q.options.length < 2 || q.options.some((o) => !o.text.trim())
}

/* 题目位置文案（校验提示用：题号 + 列表位置，避免与列表位置混淆） */
function qPosText(q, i) {
  return q.questionNumber != null
    ? t('listPosNum', { i: i + 1, n: q.questionNumber })
    : t('listPosNoNum', { i: i + 1 })
}

const typeOptions = computed(() => [
  { value: 'SINGLE', label: t('typeSingle') },
  { value: 'MULTIPLE', label: t('typeMultiple') },
  { value: 'JUDGE', label: t('typeJudge') },
  { value: 'SUBJECTIVE', label: t('typeSubjective') }
])
/* 题型只读标签（渲染态展示；编辑态由 type-tabs 切换） */
function typeLabel(type) {
  const found = typeOptions.value.find((o) => o.value === type)
  return found ? found.label : type
}
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

/* 富文本渲染（题干/选项/答案/解析/材料共用）：表格 HTML → 白名单真实表格，[图片N] → 任务临时图，
 * $...$ LaTeX → KaTeX，其余文本转义（防 XSS）。先公式渲染（输出已转义 + KaTeX HTML），再图，再换行。
 *
 * 性能：默认全渲染 = 几十上百题同时跑 KaTeX/图片/表格，故对渲染结果做缓存。
 * key = jobId + 内容字符串：同内容（含材料区复用同一段文本）直接命中；
 * 编辑后内容字符串变化 → key 变化 → 自然失效重算；切换任务（jobId 变）也不会串用旧结果。 */
const previewHtmlCache = new Map()
const PREVIEW_CACHE_MAX = 2000 // 上限兜底（极端批量编辑时整体清空，避免无界增长）
function renderJobImages(html) {
  return String(html).replace(/\[图片(\d+)\]/g, (m, n) => {
    const url = getJobImageUrl(jobId.value, Number(n))
    return `<img class="snip-img" src="${url}" alt="${m}" loading="lazy">`
  })
}
function qPreviewHtml(content) {
  if (!content) return ''
  const key = `${jobId.value}\u0000${content}`
  const cached = previewHtmlCache.get(key)
  if (cached !== undefined) return cached
  const segments = String(content).split(/(<table[\s>][\s\S]*?<\/table>)/gi)
  let html = ''
  for (const seg of segments) {
    if (!seg) continue
    if (/^<table[\s>]/i.test(seg)) {
      html += `<div class="rich-table-wrap">${renderJobImages(sanitizeTableHtml(seg))}</div>`
    } else {
      html += renderJobImages(latexOnlyHtml(seg))
    }
  }
  if (previewHtmlCache.size >= PREVIEW_CACHE_MAX) previewHtmlCache.clear()
  previewHtmlCache.set(key, html)
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

/* 插入新题（中间插入；提交时后端按新顺序补题号）。新题为空白，直接展开编辑态（渲染态下没有内容可看） */
function insertQuestion(index) {
  const q = emptyQuestion()
  questions.value.splice(index, 0, q)
  openEdit(q)
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
  ElMessage.success(t('matLinked', { key: materialLinkKey.value }))
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
    ElMessage.success(t('imgCopied', { marker }))
  } catch (e) {
    ElMessage.warning(t('imgCopyFail', { marker }))
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
    ElMessage.warning(t('keepOneQ'))
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
  ElMessage.success(t('matDeleted', { key: m.materialKey }))
}

function addQuestion() {
  const q = emptyQuestion()
  questions.value.push(q)
  openEdit(q) // 新题为空白：直接展开编辑态，省一次点击
}

async function confirm() {
  if (confirmed.value) {
    ElMessage.info(t('alreadyImported'))
    return
  }
  // 校验（失败即滚动高亮到该题卡片；提示同时带题号，避免与列表位置混淆）
  // 规则与渲染态卡片上的「题干为空 / 选项不完整」标签共用 stemInvalid / optionsInvalid，避免两处漂移
  for (let i = 0; i < questions.value.length; i++) {
    const q = questions.value[i]
    if (stemInvalid(q)) {
      ElMessage.warning(t('warnStemEmpty', { pos: qPosText(q, i) }))
      focusCard(i)
      return
    }
    if (optionsInvalid(q)) {
      ElMessage.warning(t('warnOptIncomplete', { pos: qPosText(q, i) }))
      focusCard(i)
      return
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
    ElMessage.success(t('importedN', { n: res.importedCount }))
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
      t('cancelAsk'),
      t('cancelTitle'),
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
    ElMessage.success(t('canceledDone'))
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
    ElMessage.success(t('taskDeleted'))
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
    // 新任务回到全渲染态；渲染缓存按 jobId 分键，这里一并清掉避免跨任务驻留
    editOpen.value = new Set()
    analysisExpanded.value = new Set()
    previewHtmlCache.clear()
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
/* 页头操作区（全局编辑态开关 + 取消/确认导入） */
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
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
/* 校验态标签（题干为空 / 选项不完整）：与导入前校验同规则，便于长列表定位 */
.src-invalid {
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px dashed var(--danger);
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
/* 存在校验问题（题干为空 / 选项不完整）的卡片：描边提醒，便于一眼扫到 */
.preview-card.card-invalid {
  border-color: var(--danger);
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
/* 渲染态分值/题型（只读） */
.card-score-text {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-secondary);
}
.type-label {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 10px;
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
/* ---------- 渲染态（默认主视图，所见即所得：KaTeX 公式 + 图片 + 表格） ---------- */
.render-block {
  padding: 14px 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 13.5px;
  line-height: 1.7;
}
.render-stem {
  color: var(--text-primary);
  word-break: break-word;
}
.render-stem.is-empty {
  color: var(--text-muted);
  font-style: italic;
}
.render-judge {
  display: flex;
  gap: 14px;
}
.render-judge-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
}
.render-options {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.render-option {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}
.render-option-text {
  min-width: 0;
  color: var(--text-secondary);
  word-break: break-word;
}
/* 答案区（客观题=正确答案 / 主观题=参考答案）；AI 补答案时整块高亮提醒核对 */
.answer-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 9px;
}
.answer-block.is-ai {
  background: var(--warning-soft);
  border-color: var(--warning);
}
.answer-head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.answer-label {
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 0.5px;
}
.ai-supp-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px dashed var(--warning);
  border-radius: 999px;
  padding: 1px 9px;
}
.answer-key {
  display: inline-flex;
  align-items: center;
  padding: 1px 10px;
  border-radius: 7px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  color: var(--accent-text);
  font-size: 13px;
  font-weight: 600;
}
.answer-body {
  color: var(--text-primary);
  word-break: break-word;
}
.answer-empty {
  font-size: 13px;
  color: var(--text-muted);
}
/* 解析：默认展示；过长折叠 + 「展开全部 / 收起」 */
.analysis-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.analysis-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.analysis-body {
  color: var(--text-secondary);
  word-break: break-word;
}
.analysis-body.is-collapsed {
  max-height: calc(1.7em * 6); /* ≈ 6 行（与 isLongText 阈值呼应） */
  overflow: hidden;
}
.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: none;
  padding: 0;
  color: var(--accent-text);
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
}
.link-btn:hover {
  text-decoration: underline;
}
.render-block img.snip-img,
.render-block img.rich-img {
  max-width: min(100%, 420px);
  max-height: 300px;
  object-fit: contain;
  border: 1px solid var(--border);
  border-radius: 6px;
  margin: 4px 0;
  background: #fff;
}
</style>
