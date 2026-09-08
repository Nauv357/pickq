<template>
  <div class="page">
    <!-- 题库不存在 -->
    <div v-if="bankError" class="error-state">
      <TikuIcon name="file" :size="40" />
      <h3>{{ bankError }}</h3>
      <p class="text-secondary">题库可能已被删除，或地址有误</p>
      <button class="btn btn-secondary" @click="$router.push('/')">返回题库列表</button>
    </div>

    <template v-else>
      <!-- 头部 -->
      <header class="page-header">
        <div class="header-main">
          <div class="title-line">
            <h1 class="page-title">{{ bank?.name || '加载中…' }}</h1>
            <span v-if="bank?.version" class="version-tag">v{{ bank.version }}</span>
          </div>
          <p v-if="bank?.description" class="page-desc">{{ bank.description }}</p>
          <div class="page-meta text-muted">
            <span v-if="bank?.authorName">作者：{{ bank.authorName }}</span>
            <span v-if="bank?.authorName" class="dot"></span>
            <span v-if="bank?.source" class="source-text" :title="bank.source">来源：{{ bank.source }}</span>
            <span v-if="bank?.source" class="dot"></span>
            <span>创建于 {{ formatDate(bank?.createdAt) }}</span>
            <span class="dot"></span>
            <span>共 {{ qTotal }} 题</span>
          </div>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary" :disabled="!bank" @click="$router.push(`/banks/${id}/sessions`)">
            <TikuIcon name="clock" :size="14" />
            练习历史
          </button>
          <button class="btn btn-secondary" :disabled="!bank" @click="openEditBank">
            <TikuIcon name="edit" :size="14" />
            编辑
          </button>
          <button class="btn btn-secondary" :disabled="!bank" @click="openExport">
            <TikuIcon name="download" :size="14" />
            导出题库文件
          </button>
          <button class="btn btn-secondary" :disabled="!bank" @click="$router.push(`/banks/${id}/print`)">
            <TikuIcon name="file" :size="14" />
            打印试卷
          </button>
          <button class="btn btn-danger" :disabled="!bank" @click="confirmDeleteBank">
            <TikuIcon name="trash" :size="14" />
            删除
          </button>
          <button class="btn btn-primary" :disabled="!bank" @click="openSession">
            <TikuIcon name="play" :size="14" />
            开始做题
          </button>
        </div>
      </header>

      <!-- 学习进度卡片 -->
      <section v-if="progress" class="progress-card">
        <div class="progress-stats">
          <div class="stat">
            <span class="stat-num mono">{{ progress.answeredQuestions }}</span>
            <span class="stat-label">已做 / 共 {{ progress.totalQuestions }} 题</span>
          </div>
          <div class="stat">
            <span class="stat-num mono">{{ Math.round(progress.accuracy * 100) }}%</span>
            <span class="stat-label">正确率（作答 {{ progress.recordsCount }} 次）</span>
          </div>
          <div class="stat">
            <span class="stat-num mono">{{ progress.progressPercent }}%</span>
            <span class="stat-label">完成度</span>
          </div>
        </div>
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: progress.progressPercent + '%' }"></div>
        </div>
        <p v-if="progress.recordsCount === 0" class="progress-hint text-muted">
          还没有做题记录，点击「开始做题」刷第一轮
        </p>
      </section>

      <!-- 复习 / 错题工具条 -->
      <section class="toolbar">
        <div class="tool-item">
          <span class="tool-label">复习计划</span>
          <button
            class="toggle"
            :class="{ on: reviewEnabled }"
            :disabled="reviewToggling"
            @click="toggleReview"
          >
            <span class="toggle-knob"></span>
          </button>
          <button
            v-if="reviewEnabled"
            class="btn btn-primary btn-sm due-btn"
            :disabled="dueLoading || dueTotal === 0"
            @click="openDue"
          >
            <TikuIcon name="bell" :size="13" />
            今日待复习 {{ dueTotal }} 题
          </button>
          <span v-else class="text-muted tool-tip">
            关闭时进度照记、错题照收，只是不提醒到期复习；重新开启后到期题会回到队列
          </span>
        </div>
        <div class="tool-item">
          <button class="btn btn-secondary btn-sm" @click="openWrong">
            <TikuIcon name="list" :size="13" />
            错题{{ wrongTotal > 0 ? `（${wrongTotal}）` : '' }}
          </button>
        </div>
        <div class="tool-item">
          <button
            class="btn btn-secondary btn-sm"
            :title="favoriteCount > 0 ? '一键开刷全部收藏题（收藏模式）' : '还没有收藏题'"
            :disabled="favStarting"
            @click="startFavoriteSession"
          >
            <TikuIcon name="star" :size="13" :filled="favoriteCount > 0" />
            收藏{{ favoriteCount > 0 ? `（${favoriteCount}）` : '' }}
          </button>
        </div>
      </section>

      <!-- 录题面板（编辑打开时页面右侧悬浮"题号盘"，不占主内容宽度） -->
      <div ref="panelEl">
            <QuestionFormPanel
              v-if="panelOpen"
              :key="panelKey"
              :bank-id="Number(id)"
              :mode="panelMode"
              :initial="editingQuestion"
              :next-number="nextNumber"
              :nav="navState"
              :jump-request="jumpRequest"
              @saved="onPanelSaved"
              @navigate="handleNavigate"
              @jump-to="onPanelJumpTo"
              @closed="panelOpen = false"
            />
          </div>

      <!-- 题目区（全宽，与上方元素等宽对齐） -->
      <section class="questions-section">
        <div class="section-head">
          <div class="section-title">
            <h2>题目</h2>
            <span class="count-badge">{{ qTotal }}</span>
          </div>
          <div class="section-actions">
            <button class="btn btn-secondary btn-sm" @click="openMaterials">
              <TikuIcon name="file" :size="13" />
              材料
            </button>
            <button class="btn btn-secondary btn-sm" @click="aiDialog?.open()">
              <TikuIcon name="sparkle" :size="13" />
              AI 追加
            </button>
            <button class="btn btn-secondary btn-sm" @click="openBatch">
              <TikuIcon name="upload" :size="13" />
              批量导入
            </button>
            <button
              class="btn btn-secondary btn-sm"
              title="把本库无答案的客观题分批送 AI 判定回填（答案后配的批量兑现）"
              @click="openAiFill"
            >
              <TikuIcon name="sparkle" :size="13" />
              AI 补答案
            </button>
            <button
              class="btn btn-secondary btn-sm"
              :class="{ active: selectionMode }"
              :title="selectionMode ? '退出选择模式' : '勾选若干题目，另存为新题库或并入其他题库'"
              @click="toggleSelectionMode"
            >
              <TikuIcon name="check" :size="13" />
              {{ selectionMode ? '取消选题' : '选题另存' }}
            </button>
            <button class="btn btn-primary btn-sm" @click="openCreatePanel">
              <TikuIcon name="plus" :size="13" />
              添加题目
            </button>
          </div>
        </div>

        <!-- 搜索与筛选（第十轮：keyword/题型/范围/分类） -->
        <div class="q-filter-bar">
          <el-input
            v-model="qKeyword"
            placeholder="搜索题干 / 选项关键词"
            clearable
            style="width: 230px"
            @keyup.enter="applyFilter"
            @clear="applyFilter"
          >
            <template #prefix><TikuIcon name="search" :size="13" /></template>
          </el-input>
          <el-select v-model="qType" placeholder="题型" clearable style="width: 110px" @change="applyFilter">
            <el-option v-for="t in typeFilterOptions" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-select v-model="qScope" placeholder="范围" clearable style="width: 110px" @change="applyFilter">
            <el-option label="全部" value="all" />
            <el-option label="收藏" value="favorite" />
            <el-option label="错题" value="wrong" />
            <el-option label="未做" value="undone" />
          </el-select>
          <el-select v-model="qCategory" placeholder="分类" clearable filterable style="width: 140px" @change="applyFilter">
            <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
          </el-select>
          <span v-if="filterActive" class="q-filter-hint text-muted">筛选结果 {{ qTotal }} 题</span>
        </div>

        <!-- 加载中 -->
        <div v-if="qLoading" class="q-list">
          <div v-for="n in 4" :key="n" class="q-row tiku-skeleton">
            <div class="sk-line" style="width: 6%"></div>
            <div class="sk-line" style="width: 30%"></div>
            <div class="sk-line" style="width: 45%"></div>
          </div>
        </div>

        <!-- 空 -->
        <div v-else-if="questions.length === 0" class="q-empty">
          <TikuIcon name="file" :size="34" />
          <p class="text-secondary">{{ filterActive ? '没有匹配的题目，试试调整筛选条件' : '题库还没有题目' }}</p>
          <button v-if="!filterActive" class="btn btn-secondary btn-sm" @click="openCreatePanel">录入第一题</button>
        </div>

        <!-- 题目行 -->
        <div v-else class="q-list">
          <template v-for="(q, i) in questions" :key="q.questionId">
            <div
              class="q-row"
              :class="{ 'q-row-open': !!aiOpen[q.questionId], 'q-row-sel': selectionMode && selectedQids.has(q.questionId) }"
              :data-qid="q.questionId"
              :title="selectionMode
                ? (selectedQids.has(q.questionId) ? '点击取消选择' : '点击选择该题')
                : '点击编辑该题（右侧按钮可做题 / AI 解析 / 删除）'"
              @click="selectionMode ? toggleSelect(q.questionId) : openEditPanel(q.questionId, i)"
            >
              <!-- 选择模式：行首勾选框 -->
              <button
                v-if="selectionMode"
                class="sel-box"
                :class="{ on: selectedQids.has(q.questionId) }"
                title="选择 / 取消"
                @click.stop="toggleSelect(q.questionId)"
              >
                <TikuIcon v-if="selectedQids.has(q.questionId)" name="check" :size="12" />
              </button>
              <!-- 题号：questionNumber 为 null（后端回填定位失败）→ 显示"未编号"灰色徽标；
                   重复题号（当前列表 >1 次）→ 红色"重复题号 N"徽标（第十一轮） -->
              <span
                v-if="duplicateNums.has(q.questionNumber)"
                class="q-number mono dup-number"
                title="该题号在当前列表中重复，可能是重复导入的题目，请删除或修改题号"
              >重复题号 {{ q.questionNumber }}</span>
              <span v-else-if="q.questionNumber != null" class="q-number mono">#{{ q.questionNumber }}</span>
              <span v-else class="q-number mono unnumbered" title="后端未能回填题号，导入/录入后自动按顺序补号">未编号</span>
              <span class="q-type" :class="`type-${String(q.questionType).toLowerCase()}`">{{ q.typeLabel }}</span>
              <span
                v-if="q.questionType !== 'SUBJECTIVE' && !answerKeysText(q)"
                class="q-no-answer"
                title="未配置答案：做题时无法判对错，点击编辑补配"
              >无答案</span>
              <span class="q-content" :title="q.content">{{ summarizeContent(q.content) }}</span>
              <span v-if="q.topic" class="q-topic">{{ q.topic }}</span>
              <button
                class="q-fav-btn"
                :class="{ on: q.favorite }"
                :title="q.favorite ? '取消收藏' : '收藏此题（做题页可只练收藏题）'"
                @click.stop="toggleRowFavorite(q)"
              >
                <TikuIcon name="star" :size="14" :filled="q.favorite" />
              </button>
              <span class="q-score text-muted">{{ formatScore(q.score) }} 分</span>
              <div class="q-actions" @click.stop>
                <button class="icon-btn" title="从该题开始顺序做题" @click="startPracticeAt(q.questionId)">
                  <TikuIcon name="play" :size="14" />
                </button>
                <button
                  class="icon-btn"
                  :title="aiOpen[q.questionId] ? '收起解析' : 'AI 解析'"
                  @click="toggleAiPanel(q)"
                >
                  <TikuIcon name="sparkle" :size="14" />
                </button>
                <button class="icon-btn" title="编辑" @click="openEditPanel(q.questionId, i)">
                  <TikuIcon name="edit" :size="14" />
                </button>
                <button class="icon-btn danger" title="删除" @click="confirmDeleteQuestion(q)">
                  <TikuIcon name="trash" :size="14" />
                </button>
              </div>
            </div>
            <!-- 行内 AI 解析面板（展开后自动生成；含正式解析展示） -->
            <div v-show="!!aiOpen[q.questionId]" class="q-ai-panel" @click.stop>
              <div v-if="q.materialContent" class="an-block">
                <span class="an-label">材料</span>
                <div class="an-content" v-html="richHtml(q.materialContent)"></div>
              </div>
              <div v-if="answerKeysText(q) || q.answerText" class="ai-panel-row">
                <span class="an-label">答案</span>
                <span class="an-answer">{{ answerKeysText(q) || q.answerText }}</span>
              </div>
              <div v-if="q.referenceAnswer" class="ai-panel-row">
                <span class="an-label">参考答案</span>
                <div class="an-content" v-html="richHtml(q.referenceAnswer)"></div>
              </div>
              <div v-if="q.analysis" class="ai-panel-row">
                <span class="an-label">解析</span>
                <div class="an-content" v-html="richHtml(q.analysis)"></div>
              </div>
              <QuestionAiAnalysis
                :question-id="q.questionId"
                :bank-id="Number(id)"
                :auto-start="!!aiOpen[q.questionId]"
                @saved="onAnalysisSaved(q, $event)"
              />
            </div>
          </template>
        </div>

        <!-- 分页 -->
        <div v-if="qTotal > 0" class="pager">
          <el-pagination
            background
            layout="prev, pager, next, total"
            :total="qTotal"
            :page-size="qPageSize"
            :current-page="qPage"
            @current-change="onPageChange"
          />
        </div>

        <!-- 选择模式工具条（悬浮底部）：勾选题目后另存为新题库 / 并入现有题库 -->
        <div v-if="selectionMode" class="selection-bar">
          <span class="sel-count">已选 <b class="mono">{{ selectedQids.size }}</b> 题</span>
          <button class="btn btn-ghost btn-sm" :disabled="allPageSelected" @click="selectAllPage">
            {{ allPageSelected ? '本页已全选' : '全选本页' }}
          </button>
          <button class="btn btn-ghost btn-sm" :disabled="selectedQids.size === 0" @click="selectedQids.clear()">清空</button>
          <span class="sel-spacer"></span>
          <button
            class="btn btn-secondary btn-sm"
            :disabled="selectedQids.size === 0 || selectionBusy"
            @click="openNewBankDialog"
          >另存为新题库</button>
          <button
            class="btn btn-secondary btn-sm"
            :disabled="selectedQids.size === 0 || selectionBusy"
            @click="openMergeDialog"
          >并入现有题库</button>
          <button class="btn btn-ghost btn-sm" @click="toggleSelectionMode">退出</button>
        </div>
      </section>

      <!-- 另存为新题库弹窗 -->
      <el-dialog v-model="saveAsVisible" title="另存为新题库" width="min(92vw, 460px)" align-center>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="题库名称" required>
            <el-input v-model="saveAsForm.name" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="描述（可选）">
            <el-input v-model="saveAsForm.description" type="textarea" :rows="2" maxlength="500" show-word-limit />
          </el-form-item>
          <p class="form-tip text-muted">
            将复制当前勾选的 {{ selectedQids.size }} 道题（含图片与关联材料）到新题库；原题库保持不变。新题库从零记录做题进度。
          </p>
        </el-form>
        <template #footer>
          <button class="btn btn-ghost" @click="saveAsVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="selectionBusy" @click="submitSaveAs">
            {{ selectionBusy ? '复制中…' : '创建并复制' }}
          </button>
        </template>
      </el-dialog>

      <!-- 并入现有题库弹窗 -->
      <el-dialog v-model="mergeToVisible" title="并入现有题库" width="min(92vw, 460px)" align-center>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="目标题库" required>
            <el-select v-model="mergeTargetBankId" placeholder="选择题库" filterable style="width: 100%">
              <el-option v-for="b in otherBanks" :key="b.id" :label="b.name" :value="b.id" />
            </el-select>
          </el-form-item>
          <p class="form-tip text-muted">
            将复制当前勾选的 {{ selectedQids.size }} 道题到所选题库（含图片与关联材料）；本题库保持不变。
          </p>
        </el-form>
        <template #footer>
          <button class="btn btn-ghost" @click="mergeToVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="selectionBusy" @click="submitMergeTo">
            {{ selectionBusy ? '复制中…' : '并入所选题库' }}
          </button>
        </template>
      </el-dialog>

      <!-- 右侧悬浮题号盘（编辑模式）：宽视口常显；窄视口（右侧放不下）收成"题号"小按钮，点击展开 -->
      <template v-if="dockOpen">
        <QuestionNavDock
          v-if="!dockNarrow || dockFabOpen"
          class="edit-dock-side"
          :class="{ 'dock-pop': dockNarrow }"
          title="全部题目"
          :items="qNav"
          :active-id="editingQuestionId"
          :hint="filterActive ? '列表有筛选，跳题不改动筛选' : ''"
          :draggable="!dockNarrow"
          :storage-key="dockNarrow ? '' : 'edit-dock'"
          active-fill
          @select="onDockSelect"
        />
        <button
          v-if="dockNarrow"
          class="dock-fab"
          :class="{ open: dockFabOpen }"
          :title="dockFabOpen ? '收起题号盘' : '打开题号盘（按题号跳题）'"
          @click="dockFabOpen = !dockFabOpen"
        >
          <TikuIcon :name="dockFabOpen ? 'x' : 'list'" :size="14" />
          <span>{{ dockFabOpen ? '收起' : '题号' }}</span>
        </button>
      </template>

      <!-- 编辑题库弹窗 -->
      <el-dialog v-model="editBankVisible" title="编辑题库" width="min(92vw, 500px)" align-center>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="题库名称" required>
            <el-input v-model="editBankForm.name" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="描述（可选）">
            <el-input v-model="editBankForm.description" type="textarea" :rows="2" maxlength="500" show-word-limit />
          </el-form-item>
          <el-form-item label="来源声明（可选）">
            <el-input v-model="editBankForm.source" maxlength="255" placeholder="如：整理自公开教材与历年真题" />
          </el-form-item>
          <el-form-item label="作者名（可选）">
            <el-input v-model="editBankForm.authorName" maxlength="100" placeholder="导出题库文件时写入文件的展示名" />
          </el-form-item>
        </el-form>
        <template #footer>
          <button class="btn btn-ghost" @click="editBankVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="editBankSubmitting" @click="submitEditBank">
            {{ editBankSubmitting ? '保存中…' : '保存' }}
          </button>
        </template>
      </el-dialog>

      <!-- 导出弹窗 -->
      <el-dialog v-model="exportVisible" title="导出题库文件" width="min(92vw, 500px)" align-center>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="版本号">
            <el-input v-model="exportForm.version" placeholder="如 1.0.0" maxlength="20" />
            <p v-if="bank?.packageKey" class="form-tip text-muted">
              内容未修改时需保持原版本号（v{{ bank.version }}）；修改过内容可选择「更新版本」或「另存新文件」
            </p>
            <p v-else class="form-tip text-muted">
              这份题库尚未发布过，导出将生成新的发布身份，版本号缺省 1.0.0
            </p>
          </el-form-item>
          <el-form-item label="作者名（可选）">
            <el-input v-model="exportForm.authorName" maxlength="100" placeholder="导出文件中的展示名" />
          </el-form-item>
          <el-form-item v-if="bank?.packageKey" label="身份模式">
            <div class="mode-options">
              <button
                v-for="m in modeOptions"
                :key="m.value"
                class="mode-option"
                :class="{ active: exportForm.mode === m.value }"
                @click="exportForm.mode = m.value"
              >
                <span class="mode-title">{{ m.label }}</span>
                <span class="mode-desc">{{ m.desc }}</span>
              </button>
            </div>
          </el-form-item>
          <el-form-item v-else label="身份模式">
            <p class="form-tip text-muted">这份题库尚未发布过，导出将生成新的发布身份</p>
          </el-form-item>
          <el-form-item label="文件格式">
            <div class="mode-options">
              <button
                v-for="f in formatOptions"
                :key="f.value"
                class="mode-option"
                :class="{ active: exportForm.format === f.value }"
                @click="exportForm.format = f.value"
              >
                <span class="mode-title">{{ f.label }}</span>
                <span class="mode-desc">{{ f.desc }}</span>
              </button>
            </div>
          </el-form-item>
          <el-form-item label="发布到题库广场">
            <p class="form-tip text-muted">
              题库广场已支持直接上传题库文件发布。导出本文件后，
              到题库广场「发布作品」页上传即可（可选择中心托管或自提供下载链接）。
            </p>
          </el-form-item>
        </el-form>
        <template #footer>
          <button class="btn btn-ghost" @click="exportVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="exporting" @click="submitExport">
            {{ exporting ? '导出中…' : '导出并保存文件' }}
          </button>
        </template>
      </el-dialog>

      <!-- 开始做题弹窗（会话模式） -->
      <el-dialog v-model="sessionVisible" title="开始做题" width="min(92vw, 540px)" align-center>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="练习模式">
            <div class="mode-options">
              <button
                v-for="m in sessionModes"
                :key="m.value"
                class="mode-option"
                :class="{ active: sessionForm.mode === m.value }"
                @click="onModeSelect(m.value)"
              >
                <span class="mode-title">{{ m.label }}</span>
                <span class="mode-desc">{{ m.desc }}</span>
              </button>
            </div>
          </el-form-item>
          <template v-if="sessionForm.mode === 'TOPIC'">
            <el-form-item label="主题（可多选）">
              <el-select
                v-model="selectedTopics"
                multiple
                collapse-tags
                collapse-tags-tooltip
                placeholder="不限主题"
                style="width: 100%"
                clearable
              >
                <el-option v-for="t in topicOptions" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-form-item label="分类（可多选）">
              <el-select
                v-model="selectedCategories"
                multiple
                collapse-tags
                collapse-tags-tooltip
                placeholder="不限分类"
                style="width: 100%"
                clearable
              >
                <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
            <p class="form-tip text-muted">同时选择主题与分类时，题目需同时命中；只选一组时按该组筛选</p>
          </template>
          <el-form-item label="题目数量（留空 = 范围内全部）">
            <el-input-number v-model="sessionForm.count" :min="1" :max="500" controls-position="right" style="width: 200px" placeholder="全部" />
          </el-form-item>
        </el-form>
        <template #footer>
          <button class="btn btn-ghost" @click="sessionVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="sessionCreating" @click="submitSession">
            {{ sessionCreating ? '抽题中…' : '开始' }}
          </button>
        </template>
      </el-dialog>

      <!-- 批量导入弹窗 -->
      <el-dialog v-model="batchVisible" title="批量导入题目" width="min(92vw, 560px)" align-center>
        <div class="batch-head">
          <p class="text-secondary batch-desc">
            粘贴 AI 整理或结构化的题目 JSON（数组，或 <code>{ "questions": [...] }</code>），也可以选择 .json 文件
          </p>
          <button class="btn btn-secondary btn-sm" @click="pickBatchFile">
            <TikuIcon name="file" :size="13" />
            选择文件
          </button>
        </div>
        <el-input
          v-model="batchText"
          type="textarea"
          :rows="10"
          placeholder='[{"questionType":"SINGLE","content":"题干","options":[{"key":"A","text":"选项A"},{"key":"B","text":"选项B"}],"answerKeys":["A"],"score":1}, ...]'
        />
        <template #footer>
          <button class="btn btn-ghost" @click="batchVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="batchSubmitting" @click="submitBatch">
            {{ batchSubmitting ? '导入中…' : '导入题目' }}
          </button>
        </template>
      </el-dialog>

      <!-- AI 批量补答案弹窗（A1：无答案客观题 → 思考模式判定回填，分钟级同步执行） -->
      <el-dialog
        v-model="fillVisible"
        title="AI 批量补答案"
        width="min(92vw, 500px)"
        align-center
        :close-on-click-modal="false"
        :close-on-press-escape="!fillBusy"
        :show-close="!fillBusy"
      >
        <div class="fill-desc">
          <p>
            将把本库<strong>没有答案的客观题</strong>分批送 AI（思考模式）逐题判定并回填正确答案；
            含图片的题目会把图片一并提供，AI 将结合图片判断。
          </p>
          <p class="text-muted">适合：刚导入/录入还没配答案、正打算逐题手动补的题目（一次搞定）。</p>
        </div>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="本次处理范围">
            <el-select v-model="fillType" style="width: 100%">
              <el-option label="全部客观题（单选 + 多选 + 判断）" value="" />
              <el-option v-for="t in objectiveTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="fillWithAnalysis">同时补解析（只补尚无解析的题）</el-checkbox>
          </el-form-item>
        </el-form>
        <p v-if="fillBusy" class="fill-busy">
          AI 判定中…（分批串行执行，可能需要几分钟），请勿刷新或关闭本页，完成后会自动弹出结果；
          如意外关闭，后端仍可能继续写入，稍后刷新页面即可看到已回填的答案
        </p>
        <template #footer>
          <button class="btn btn-ghost" :disabled="fillBusy" @click="fillVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="fillBusy" @click="submitAiFill">
            {{ fillBusy ? 'AI 判定中…' : '开始补答案' }}
          </button>
        </template>
      </el-dialog>

      <!-- 错题弹窗 -->
      <el-dialog v-model="wrongVisible" title="错题本" width="min(92vw, 640px)" align-center>
        <div v-if="wrongLoading" class="wrong-loading text-muted">加载中…</div>
        <div v-else-if="wrongList.length === 0" class="wrong-empty text-muted">
          太棒了，最近一次作答没有错题 🎉
        </div>
        <div v-else class="wrong-list">
          <div v-for="(w, wi) in wrongList" :key="w.questionId" class="wrong-row">
            <span v-if="w.questionNumber != null" class="q-number mono">#{{ w.questionNumber }}</span>
            <span v-else class="q-number mono unnumbered" title="后端未能回填题号">未编号</span>
            <span class="q-type" :class="`type-${String(w.questionType).toLowerCase()}`">{{ w.typeLabel }}</span>
            <span class="wrong-content">{{ w.content }}</span>
            <span class="wrong-meta text-muted">
              错 {{ w.wrongCount }} 次 · {{ formatDate(w.lastAnsweredAt) }}
            </span>
            <button class="icon-btn" title="编辑该题" @click="editFromDialog(w)">
              <TikuIcon name="edit" :size="13" />
            </button>
          </div>
        </div>
        <div v-if="wrongTotal > 0" class="wrong-pager">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="wrongTotal"
            :page-size="wrongPageSize"
            :current-page="wrongPage"
            @current-change="onWrongPageChange"
          />
        </div>
        <template #footer>
          <button class="btn btn-ghost" @click="wrongVisible = false">关闭</button>
          <button class="btn btn-primary" :disabled="wrongList.length === 0 || sessionCreating" @click="startWrongSession">
            <TikuIcon name="refresh" :size="14" />
            错题重做（{{ wrongTotal }} 题）
          </button>
        </template>
      </el-dialog>

      <!-- 待复习队列弹窗（含逾期欠账标记 + 重置入口） -->
      <el-dialog v-model="dueVisible" title="待复习队列" width="min(92vw, 640px)" align-center>
        <div v-if="dueListLoading" class="wrong-loading text-muted">加载中…</div>
        <div v-else-if="dueList.length === 0" class="wrong-empty text-muted">
          当前没有到期题目 🎉
        </div>
        <div v-else class="wrong-list">
          <div v-for="(w, wi) in dueList" :key="w.questionId" class="wrong-row">
            <span v-if="w.questionNumber != null" class="q-number mono">#{{ w.questionNumber }}</span>
            <span v-else class="q-number mono unnumbered" title="未编号">未编号</span>
            <span class="q-type" :class="`type-${String(w.questionType).toLowerCase()}`">{{ w.typeLabel }}</span>
            <span class="wrong-content">{{ w.content }}</span>
            <span class="wrong-meta text-muted">
              <span v-if="w.overdueDays > 0" class="due-overdue">已逾期 {{ w.overdueDays }} 天</span>
              <span v-else-if="w.dueAt" class="due-today">今日到期</span>
              · Lv.{{ w.level }} · {{ formatDate(w.dueAt) }}
            </span>
            <button class="icon-btn" title="编辑该题" @click="editFromDialog(w)">
              <TikuIcon name="edit" :size="13" />
            </button>
          </div>
        </div>
        <div v-if="dueTotal > 0" class="wrong-pager">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="dueTotal"
            :page-size="duePageSize"
            :current-page="duePage"
            @current-change="onDuePageChange"
          />
        </div>
        <template #footer>
          <button class="btn btn-ghost" @click="dueVisible = false">关闭</button>
          <button
            class="btn btn-secondary"
            :disabled="reviewResetting || dueList.length === 0"
            @click="resetReviewPlan"
          >
            <TikuIcon name="trash" :size="13" />
            重置复习计划
          </button>
          <button
            class="btn btn-primary"
            :disabled="dueListLoading || dueList.length === 0 || sessionCreating"
            @click="startReviewSession"
          >
            <TikuIcon name="bell" :size="13" />
            开始复习（{{ dueTotal }} 题）
          </button>
        </template>
      </el-dialog>

      <!-- AI 追加弹窗（目标锁定当前题库） -->
      <AiImportDialog ref="aiDialog" :bank-id="id" :bank-name="bank?.name" @done="onAiDone" />

      <!-- 材料管理弹窗 -->
      <el-dialog v-model="materialsVisible" title="共享材料（资料分析大题干）" width="min(92vw, 620px)" align-center>
        <div class="material-list">
          <div v-for="m in materials" :key="m.id" class="material-item">
            <div class="material-content" v-html="richHtml(m.content)"></div>
            <div class="material-actions">
              <button class="btn btn-secondary btn-sm" @click="editMaterial(m)">
                <TikuIcon name="edit" :size="12" />
                编辑
              </button>
              <button class="btn btn-danger btn-sm" @click="removeMaterial(m)">
                <TikuIcon name="trash" :size="12" />
                删除
              </button>
            </div>
          </div>
          <div v-if="materials.length === 0" class="material-empty text-muted">
            还没有材料。材料用于资料分析题（组内题共用的文字/图片大题干），创建后可在录题时关联。
          </div>
        </div>

        <!-- 新建/编辑 -->
        <div class="material-edit">
          <div class="field-head">
            <label class="field-label">{{ editingMaterialId ? '编辑材料' : '新建材料' }}</label>
            <button class="img-btn" :disabled="materialSaving" @click="insertMaterialImage">
              <TikuIcon name="file" :size="12" />
              插图
            </button>
          </div>
          <el-input
            v-model="materialContent"
            type="textarea"
            :rows="3"
            placeholder="材料内容（文字 + [图片:文件名] 标记）"
          />
          <div class="material-edit-actions">
            <button class="btn btn-ghost btn-sm" @click="resetMaterialForm">清空</button>
            <button class="btn btn-primary btn-sm" :disabled="materialSaving" @click="saveMaterial">
              {{ materialSaving ? '保存中…' : editingMaterialId ? '保存修改' : '创建材料' }}
            </button>
          </div>
        </div>
        <template #footer>
          <button class="btn btn-ghost" @click="materialsVisible = false">关闭</button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchCreateQuestions, aiFillAnswers, copyQuestionSelection, deleteBank, exportBank, exportTikuBank, getBank, getBankQuestionNav, getBankQuestions, getBanks, setReviewEnabled, updateBank } from '../api/banks'
import { deleteQuestion, getQuestion, setFavorite } from '../api/questions'
import { getBankProgress, getReviewDue, getReviewSummary, getWrongQuestions, resetReviewStates } from '../api/studyRecords'
import { createSession, getBankCategories } from '../api/sessions'
import { formatDate, formatScore } from '../utils/format'
import { richTextToHtml } from '../utils/richText'
import { createMaterial, deleteMaterial, listMaterials, updateMaterial, uploadImage } from '../api/materials'
import { pickFile, readTextFile, saveBlob, saveJsonFile } from '../utils/files'
import TikuIcon from '../components/TikuIcon.vue'
import QuestionFormPanel from '../components/QuestionFormPanel.vue'
import QuestionNavDock from '../components/QuestionNavDock.vue'
import AiImportDialog from '../components/AiImportDialog.vue'
import QuestionAiAnalysis from '../components/QuestionAiAnalysis.vue'

const route = useRoute()
const router = useRouter()
const id = route.params.id

/* ---------- 题库信息 ---------- */
const bank = ref(null)
const bankError = ref('')

async function loadBank() {
  try {
    bank.value = await getBank(id)
    //复习开关状态以后端为准（旧实现只写 localStorage：清缓存/换设备后状态错乱，
    //且后端从未真正按开关 gate 队列——见后端 StudyRecordService.listReviewDue/PracticeSessionService）
    reviewEnabled.value = !!bank.value?.reviewEnabled
    if (reviewEnabled.value) {
      loadReviewDue()
    }
  } catch (e) {
    bankError.value = e.message || '题库不存在'
  }
}

/* ---------- 题目列表 ---------- */
const questions = ref([])
const qLoading = ref(true)
const qPage = ref(1)
const qPageSize = 50
const qTotal = ref(0)

const nextNumber = computed(() => qTotal.value + 1)
// 当前页首题的全局序号（题号缺失时按列表顺序显示）
const offset = computed(() => (qPage.value - 1) * qPageSize)

/* ---------- 搜索与筛选（第十轮） ---------- */
const qKeyword = ref('')
const qType = ref('')
const qScope = ref('')
const qCategory = ref('')
const typeFilterOptions = [
  { value: 'SINGLE', label: '单选' },
  { value: 'MULTIPLE', label: '多选' },
  { value: 'JUDGE', label: '判断' },
  { value: 'SUBJECTIVE', label: '主观' }
]
// AI 补答案只处理客观题
const objectiveTypeOptions = typeFilterOptions.filter((t) => t.value !== 'SUBJECTIVE')
const filterActive = computed(() => !!(qKeyword.value || qType.value || qScope.value || qCategory.value))

/* 重复题号统计（第十一轮：当前列表内 questionNumber 出现 >1 即标红，纯前端） */
const duplicateNums = computed(() => {
  const count = new Map()
  for (const q of questions.value) {
    if (q.questionNumber != null) {
      count.set(q.questionNumber, (count.get(q.questionNumber) || 0) + 1)
    }
  }
  return new Set([...count.entries()].filter(([, c]) => c > 1).map(([n]) => n))
})

function applyFilter() {
  qPage.value = 1
  loadQuestions()
}

async function loadQuestions() {
  qLoading.value = true
  try {
    const data = await getBankQuestions(id, {
      page: qPage.value,
      size: qPageSize,
      keyword: qKeyword.value.trim() || undefined,
      questionType: qType.value || undefined,
      scope: qScope.value || undefined,
      category: qCategory.value || undefined
    })
    questions.value = data.records || []
    qTotal.value = Number(data.total || 0)
  } catch (e) {
    questions.value = []
  } finally {
    qLoading.value = false
  }
  //同步"全部题目"题号盘数据（编辑模式右侧题号盘用；删除/导入/保存后保持最新；接口轻量）
  loadQuestionNav()
}

/* ---------- 题号盘（编辑模式右侧：全部题目圆形题号，任意跳转） ---------- */
const qNav = ref([])
const dockOpen = computed(() => panelOpen.value && panelMode.value === 'edit')
const jumpRequest = ref({ seq: 0, questionId: null })

/* 题号盘窄视口收起（视口 ≤1680 右侧放不下 188px 悬浮盘（内容限宽 1440 联动）→ 收成"题号"小按钮，点击展开；
   浏览器缩放/窗口宽度变化都会触发 media query change，展开态自动跟随） */
const dockNarrow = ref(false)
const dockFabOpen = ref(false)
const dockMq = window.matchMedia('(max-width: 1680px)')
function syncDockNarrow(e) {
  dockNarrow.value = e.matches
}
dockNarrow.value = dockMq.matches
dockMq.addEventListener('change', syncDockNarrow)
onBeforeUnmount(() => dockMq.removeEventListener('change', syncDockNarrow))

//全量题号（不带筛选：编辑校对时可在全部题中跳转）
async function loadQuestionNav() {
  try {
    qNav.value = (await getBankQuestionNav(id)) || []
  } catch (e) {
    qNav.value = []
  }
}

/* 题号盘点击：通知编辑面板（面板先确认未保存修改，通过后 emit jump-to） */
function onDockSelect(questionId) {
  jumpRequest.value = { seq: jumpRequest.value.seq + 1, questionId }
}

/* 面板确认后的真正跳转：无筛选时把列表翻到目标题所在页，再打开该题编辑 */
async function onPanelJumpTo(questionId) {
  await jumpToQuestion(questionId)
}

async function jumpToQuestion(questionId) {
  if (!filterActive.value) {
    const idx = qNav.value.findIndex((n) => n.questionId === questionId)
    if (idx >= 0) {
      const targetPage = Math.floor(idx / qPageSize) + 1
      if (targetPage !== qPage.value) {
        qPage.value = targetPage
        await loadQuestions()
      }
    }
  }
  //列表当前页找该题行下标（找不到则 -1，面板导航箭头按列表边界禁用，题号盘仍可跳）
  const idx = questions.value.findIndex((q) => q.questionId === questionId)
  await openEditPanel(questionId, idx)
}

/* 列表行题干摘要：图片标记替换为〔图〕占位，避免显示原始 [图片:…] 文本 */
const summarizeContent = (text) => (text || '').replace(/\[图片:[^\]]+\]/g, '〔图〕')

/* 弹窗（错题 / 待复习）行内"编辑"：已有编辑会话走面板确认流；否则直接跳转打开 */
async function editFromDialog(w) {
  wrongVisible.value = false
  dueVisible.value = false
  if (panelOpen.value && panelMode.value === 'edit') {
    onDockSelect(w.questionId)
    return
  }
  await jumpToQuestion(w.questionId)
}

/* ---------- 选题另存 / 并入（多选模式：勾选题目复制到新题库或并入其他题库，源库保留） ---------- */
const selectionMode = ref(false)
const selectedQids = reactive(new Set())
const selectionBusy = ref(false)
const saveAsVisible = ref(false)
const saveAsForm = reactive({ name: '', description: '' })
const mergeToVisible = ref(false)
const mergeTargetBankId = ref(null)
const otherBanks = ref([])
const MAX_SELECT = 500
const allPageSelected = computed(
  () => questions.value.length > 0 && questions.value.every((q) => selectedQids.has(q.questionId))
)

function toggleSelectionMode() {
  selectionMode.value = !selectionMode.value
  if (!selectionMode.value) selectedQids.clear()
}
function toggleSelect(qid) {
  if (selectedQids.has(qid)) selectedQids.delete(qid)
  else selectedQids.add(qid)
}
function selectAllPage() {
  questions.value.forEach((q) => selectedQids.add(q.questionId))
}
function exitSelection() {
  selectionMode.value = false
  selectedQids.clear()
}
function openNewBankDialog() {
  saveAsForm.name = `${bank.value?.name || '题库'} · 精选 ${selectedQids.size} 题`
  saveAsForm.description = ''
  saveAsVisible.value = true
}
async function submitSaveAs() {
  if (selectedQids.size > MAX_SELECT) {
    ElMessage.warning(`一次最多复制 ${MAX_SELECT} 题，请分批操作`)
    return
  }
  if (!saveAsForm.name.trim()) {
    ElMessage.warning('请输入新题库名称')
    return
  }
  selectionBusy.value = true
  try {
    const res = await copyQuestionSelection(id, {
      questionIds: [...selectedQids],
      name: saveAsForm.name.trim(),
      description: saveAsForm.description.trim() || null
    })
    ElMessage.success(`已创建「${res.name}」，复制 ${res.questionsCopied} 题（题号已在目标库从 1 重新编排）`)
    saveAsVisible.value = false
    exitSelection()
    router.push(`/banks/${res.bankId}`)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    selectionBusy.value = false
  }
}
async function openMergeDialog() {
  mergeTargetBankId.value = null
  try {
    const data = await getBanks({ page: 1, size: 200 })
    otherBanks.value = (data.records || []).filter((b) => b.id !== Number(id))
  } catch (e) {
    otherBanks.value = []
  }
  mergeToVisible.value = true
}
async function submitMergeTo() {
  if (selectedQids.size > MAX_SELECT) {
    ElMessage.warning(`一次最多复制 ${MAX_SELECT} 题，请分批操作`)
    return
  }
  if (!mergeTargetBankId.value) {
    ElMessage.warning('请选择目标题库')
    return
  }
  selectionBusy.value = true
  try {
    const res = await copyQuestionSelection(id, {
      questionIds: [...selectedQids],
      targetBankId: mergeTargetBankId.value
    })
    ElMessage.success(`已并入「${res.name}」（复制 ${res.questionsCopied} 题，题号已顺延编排不重复）`)
    mergeToVisible.value = false
    exitSelection()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    selectionBusy.value = false
  }
}

/* ---------- 行内 AI 解析面板 ---------- */
const aiOpen = reactive({})
function toggleAiPanel(q) {
  aiOpen[q.questionId] = !aiOpen[q.questionId]
}

//AI 解析保存成功：同步面板内解析展示 + 刷新列表
function onAnalysisSaved(q, text) {
  if (q && text) {
    q.analysis = text
  }
  loadQuestions()
}

//列表接口 answerKeys 为字符串（"AC"），兼容数组：转 "A、C" 展示
const answerKeysText = (q) => {
  const k = q?.answerKeys
  if (!k) return ''
  if (Array.isArray(k)) return k.join('、')
  return String(k)
    .split('')
    .filter((c) => c.trim())
    .join('、')
}

function onPageChange(p) {
  qPage.value = p
  loadQuestions()
}

/* ---------- 学习进度 ---------- */
const progress = ref(null)

async function loadProgress() {
  try {
    progress.value = await getBankProgress(id)
  } catch (e) {
    progress.value = null
  }
}

/* ---------- 复习计划 ---------- */
//开关状态以后端为准（见 loadBank 同步）；false 只是"加载完成前"的初始占位
const reviewEnabled = ref(false)
const reviewToggling = ref(false)
const dueTotal = ref(0)
const dueLoading = ref(false)

async function loadReviewDue() {
  if (!reviewEnabled.value) return
  dueLoading.value = true
  try {
    const data = await getReviewSummary(id)
    dueTotal.value = Number(data.dueTotal || 0)
  } catch (e) {
    dueTotal.value = 0
  } finally {
    dueLoading.value = false
  }
}

async function toggleReview() {
  reviewToggling.value = true
  const next = !reviewEnabled.value
  try {
    await setReviewEnabled(id, next)
    reviewEnabled.value = next
    if (next) {
      //开启瞬间提示积压规模（含逾期欠账），让"洪水"变成可见的账
      const data = await getReviewSummary(id)
      dueTotal.value = Number(data.dueTotal || 0)
      const overdue = Number(data.overdueTotal || 0)
      if (dueTotal.value > 0) {
        ElMessage.success(
          overdue > 0
            ? `复习计划已开启：待复习 ${dueTotal.value} 题（含逾期 ${overdue} 题）`
            : `复习计划已开启：今日待复习 ${dueTotal.value} 题`
        )
      } else {
        ElMessage.success('复习计划已开启')
      }
    } else {
      dueTotal.value = 0
      ElMessage.info('已暂停到期提醒：进度照记、错题照收，只是不再提醒到期复习')
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    reviewToggling.value = false
  }
}

/* ---------- 待复习队列弹窗（逾期标记 + 重置入口） ---------- */
const dueVisible = ref(false)
const dueList = ref([])
const dueListLoading = ref(false)
const duePage = ref(1)
const duePageSize = 10

function openDue() {
  duePage.value = 1
  dueVisible.value = true
  loadDueList()
}

async function loadDueList() {
  dueListLoading.value = true
  try {
    const data = await getReviewDue(id, { page: duePage.value, size: duePageSize })
    dueList.value = data.records || []
    dueTotal.value = Number(data.total || 0)
  } catch (e) {
    dueList.value = []
  } finally {
    dueListLoading.value = false
  }
}

function onDuePageChange(p) {
  duePage.value = p
  loadDueList()
}

async function startReviewSession() {
  await startSessionWith({ mode: 'REVIEW' }, dueTotal.value)
}

//重置复习计划：清空该题库全部复习状态（含暂停标记），作答记录与错题本不受影响
const reviewResetting = ref(false)

async function resetReviewPlan() {
  if (reviewResetting.value) return
  try {
    await ElMessageBox.confirm(
      '将清空该题库的全部复习进度（含单题暂停标记），之后从 1 天间隔重新开始。作答记录与错题本不受影响。确定重置吗？',
      '重置复习计划',
      {
        type: 'warning',
        confirmButtonText: '重置',
        cancelButtonText: '再想想',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (e) {
    return // 用户取消
  }
  reviewResetting.value = true
  try {
    const n = await resetReviewStates(id)
    ElMessage.success(`已重置复习计划（清除 ${n} 条进度）`)
    dueTotal.value = 0
    dueList.value = []
    dueVisible.value = false
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    reviewResetting.value = false
  }
}

/* ---------- 错题 ---------- */
const wrongVisible = ref(false)
const wrongList = ref([])
const wrongLoading = ref(false)
const wrongPage = ref(1)
const wrongPageSize = 10
const wrongTotal = ref(0)

async function loadWrongCount() {
  try {
    const data = await getWrongQuestions(id, { page: 1, size: 1 })
    wrongTotal.value = Number(data.total || 0)
  } catch (e) {
    wrongTotal.value = 0
  }
}

function openWrong() {
  wrongVisible.value = true
  wrongPage.value = 1
  loadWrong()
}

async function loadWrong() {
  wrongLoading.value = true
  try {
    const data = await getWrongQuestions(id, { page: wrongPage.value, size: wrongPageSize })
    wrongList.value = data.records || []
    wrongTotal.value = Number(data.total || 0)
  } catch (e) {
    wrongList.value = []
  } finally {
    wrongLoading.value = false
  }
}

function onWrongPageChange(p) {
  wrongPage.value = p
  loadWrong()
}

async function startWrongSession() {
  await startSessionWith({ mode: 'WRONG' }, wrongTotal.value)
}

/* ---------- 会话（开始做题 / 复习 / 错题重做） ---------- */
const sessionVisible = ref(false)
const sessionCreating = ref(false)

const sessionModes = [
  { value: 'ALL', label: '全部随机', desc: '未做过的题优先，不足再随机补充' },
  { value: 'SEQUENCE', label: '顺序刷题', desc: '按题号顺序逐题刷' },
  { value: 'TOPIC', label: '按分类', desc: '按主题/分类筛选（可多选）' },
  { value: 'REVIEW', label: '复习队列', desc: '今日到期待复习的题目' },
  { value: 'WRONG', label: '错题', desc: '最近一次答错的题目' },
  { value: 'FAVORITE', label: '收藏', desc: '已收藏的题目' }
]

const sessionForm = reactive({ mode: 'ALL', count: null })

// TOPIC 多选：选项来自后端 categories 聚合接口
const topicOptions = ref([])
const categoryOptions = ref([])
const selectedTopics = ref([])
const selectedCategories = ref([])

function openSession() {
  sessionForm.mode = 'ALL'
  sessionForm.count = null
  selectedTopics.value = []
  selectedCategories.value = []
  sessionVisible.value = true
}

function onModeSelect(mode) {
  sessionForm.mode = mode
  if (mode === 'TOPIC') loadTopicOptions()
}

async function loadTopicOptions() {
  if (topicOptions.value.length || categoryOptions.value.length) return
  try {
    const data = await getBankCategories(id)
    topicOptions.value = data.topics || []
    categoryOptions.value = data.categories || []
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function submitSession() {
  const body = { mode: sessionForm.mode, count: sessionForm.count || null }
  if (sessionForm.mode === 'TOPIC') {
    // 后端字段：topic/category 为多选数组（IN 语义），两组之间 AND
    body.topic = selectedTopics.value
    body.category = selectedCategories.value
  }
  await startSessionWith(body)
}

async function startSessionWith(body, expectedTotal) {
  if (sessionCreating.value) return // 防双击/多入口并发重复建会话
  sessionCreating.value = true
  try {
    const data = await createSession(id, body)
    if (!data.total || data.questions.length === 0) {
      ElMessage.warning('该范围没有题目，换个模式试试')
      return
    }
    ElMessage.success(`已抽取 ${data.total} 题，开始刷题`)
    sessionVisible.value = false
    wrongVisible.value = false
    dueVisible.value = false
    if (expectedTotal != null && expectedTotal > 0) loadReviewDue()
    router.push({ path: `/banks/${id}/practice`, query: { sessionId: data.sessionId } })
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    sessionCreating.value = false
  }
}

/* ---------- 收藏题一键直达（收藏(N)：按收藏模式开刷全部收藏题） ---------- */
const favoriteCount = ref(0)
const favStarting = ref(false)

async function loadFavoriteCount() {
  try {
    //复用题目列表接口 scope=favorite 取总数（只取 1 条用于计数）
    const data = await getBankQuestions(id, { scope: 'favorite', page: 1, size: 1 })
    favoriteCount.value = Number(data.total || 0)
  } catch (e) {
    favoriteCount.value = 0
  }
}

async function startFavoriteSession() {
  if (favStarting.value || sessionCreating.value) return
  if (favoriteCount.value === 0) {
    ElMessage.info('还没有收藏任何题目：点题目行中的星标即可收藏')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将以「收藏」模式抽取全部 ${favoriteCount.value} 道收藏题开始刷题（做题中随时可改收藏，不影响本次会话）。确定开始吗？`,
      '刷收藏题',
      { confirmButtonText: '开始刷题', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  await startSessionWith({ mode: 'FAVORITE' }, null)
}

/* ---------- 材料管理（资料分析共享大题干） ---------- */
const materialsVisible = ref(false)
const materials = ref([])
const materialContent = ref('')
const editingMaterialId = ref(null)
const materialSaving = ref(false)

async function loadMaterials() {
  try {
    materials.value = (await listMaterials(id)) || []
  } catch (e) {
    materials.value = []
  }
}

const richHtml = (text) => richTextToHtml(text, id)

function openMaterials() {
  materialsVisible.value = true
  resetMaterialForm()
  loadMaterials()
}

function resetMaterialForm() {
  materialContent.value = ''
  editingMaterialId.value = null
}

function editMaterial(m) {
  editingMaterialId.value = m.id
  materialContent.value = m.content || ''
}

async function saveMaterial() {
  const content = materialContent.value.trim()
  if (!content) {
    ElMessage.warning('请输入材料内容')
    return
  }
  materialSaving.value = true
  try {
    if (editingMaterialId.value) {
      await updateMaterial(id, editingMaterialId.value, content)
      ElMessage.success('材料已更新')
    } else {
      await createMaterial(id, content)
      ElMessage.success('材料已创建，可在录题时关联')
    }
    resetMaterialForm()
    loadMaterials()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    materialSaving.value = false
  }
}

async function removeMaterial(m) {
  try {
    await ElMessageBox.confirm('删除后，组内题将解除与该材料的关联。确定删除吗？', '删除材料', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch (e) {
    return
  }
  try {
    await deleteMaterial(id, m.id)
    ElMessage.success('材料已删除')
    loadMaterials()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function insertMaterialImage() {
  let file
  try {
    file = await pickFile('image/*')
  } catch (e) {
    return
  }
  materialSaving.value = true
  try {
    const res = await uploadImage(id, file)
    const marker = `[图片:${res.name}]`
    materialContent.value = materialContent.value ? `${materialContent.value}\n${marker}` : marker
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    materialSaving.value = false
  }
}

/* ---------- AI 追加（目标锁定当前题库） ---------- */
const aiDialog = ref(null)

function onAiDone(jobId) {
  router.push({ path: `/ai-import/${jobId}`, query: { bankId: id } })
}

/* ---------- 点击题目行：创建顺序会话并从该题往后做（可交卷/进历史） ---------- */
async function startPracticeAt(questionId) {
  if (sessionCreating.value) return // 防双击重复建会话（孤儿会话污染练习历史）
  sessionCreating.value = true
  try {
    // 后端 SEQUENCE + startQuestionId：从该题按题号顺序抽 20 题
    // 带当前列表筛选（关键词/题型/范围/分类）→ 从"筛选结果"内该题往后刷
    const data = await createSession(id, {
      mode: 'SEQUENCE',
      startQuestionId: questionId,
      count: 20,
      keyword: qKeyword.value.trim() || undefined,
      questionType: qType.value || undefined,
      category: qCategory.value ? [qCategory.value] : undefined,
      scope: qScope.value || undefined
    })
    if (!data.total || data.questions.length === 0) {
      ElMessage.warning('题库还没有题目')
      return
    }
    ElMessage.success(`已抽取 ${data.total} 题，从目标题开始刷题`)
    router.push({ path: `/banks/${id}/practice`, query: { sessionId: data.sessionId } })
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    sessionCreating.value = false
  }
}

/* ---------- 录题面板 ---------- */
const panelOpen = ref(false)
const panelMode = ref('create')
const panelKey = ref(0)
const editingQuestion = ref(null)
const panelEl = ref(null)
// 连续编辑导航状态：当前编辑题在"当前结果列表"中的行下标 / questionId（跨页切换自动翻页）
const editingListIndex = ref(-1)
const editingQuestionId = ref(null)
const navSwitching = ref(false)

/* 打开面板后把页面滚动到面板（列表深处的"编辑"点击后面板在页面顶部，不滚动用户看不到） */
function scrollToPanel() {
  nextTick(() => {
    panelEl.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

function openCreatePanel() {
  panelMode.value = 'create'
  editingQuestion.value = null
  editingQuestionId.value = null
  editingListIndex.value = -1
  panelKey.value++
  panelOpen.value = true
  scrollToPanel()
}

async function openEditPanel(questionId, index = -1) {
  try {
    const detail = await getQuestion(questionId)
    panelMode.value = 'edit'
    editingQuestion.value = detail
    editingQuestionId.value = questionId
    editingListIndex.value = index >= 0 ? index : questions.value.findIndex((q) => q.questionId === questionId)
    panelKey.value++
    panelOpen.value = true
    scrollToPanel()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* 编辑面板导航状态：上一题 / 下一题沿"当前筛选结果"的列表顺序走，跨页自动翻页 */
const navState = computed(() => {
  if (panelMode.value !== 'edit' || !panelOpen.value) return null
  let idx = editingListIndex.value
  if (idx < 0) idx = questions.value.findIndex((q) => q.questionId === editingQuestionId.value)
  const hasPrev = idx > 0 || qPage.value > 1
  const hasNext =
    (idx >= 0 && idx < questions.value.length - 1) || qPage.value * qPageSize < qTotal.value
  return {
    hasPrev,
    hasNext,
    pos: idx >= 0 ? (qPage.value - 1) * qPageSize + idx + 1 : 0,
    total: qTotal.value
  }
})

/* 面板"上一题 / 下一题"：页内直接切换；到页边界自动翻页后从边界行继续 */
async function handleNavigate(dir) {
  if (navSwitching.value) return
  navSwitching.value = true
  try {
    let idx = editingListIndex.value
    // 当前编辑题可能在保存刷新后移动过位置，以列表中的实际位置为准
    const found = questions.value.findIndex((q) => q.questionId === editingQuestionId.value)
    if (found >= 0) idx = found
    const target = idx + dir
    if (target >= 0 && target < questions.value.length) {
      await openEditPanel(questions.value[target].questionId, target)
      return
    }
    if (dir > 0 && qPage.value * qPageSize < qTotal.value) {
      qPage.value += 1
      await loadQuestions()
      const row = questions.value[0]
      if (row) {
        await openEditPanel(row.questionId, 0)
        return
      }
    } else if (dir < 0 && qPage.value > 1) {
      qPage.value -= 1
      await loadQuestions()
      const row = questions.value[questions.value.length - 1]
      if (row) {
        await openEditPanel(row.questionId, questions.value.length - 1)
        return
      }
    }
    ElMessage.info(dir > 0 ? '已经是最后一题了' : '已经是第一题了')
  } finally {
    navSwitching.value = false
  }
}

async function onPanelSaved() {
  // 刷新列表，题号建议值（total + 1）随之更新；进度随之变化
  await loadQuestions()
  loadProgress()
  // 编辑保存后面板停留：题号修改可能让当前题在列表中移动，重定位行下标供导航使用
  if (panelMode.value === 'edit' && editingQuestionId.value) {
    const found = questions.value.findIndex((q) => q.questionId === editingQuestionId.value)
    if (found >= 0) {
      editingListIndex.value = found
    } else {
      editingListIndex.value = Math.min(editingListIndex.value, questions.value.length - 1)
    }
  }
}

/* 创建题库后跳转带 ?new=1，自动打开录题面板 */
if (route.query.new === '1') {
  openCreatePanel()
  router.replace({ path: `/banks/${id}` })
}

/* ---------- 编辑题库 ---------- */
const editBankVisible = ref(false)
const editBankSubmitting = ref(false)
const editBankForm = reactive({ name: '', description: '', source: '', authorName: '' })

function openEditBank() {
  editBankForm.name = bank.value?.name || ''
  editBankForm.description = bank.value?.description || ''
  editBankForm.source = bank.value?.source || ''
  editBankForm.authorName = bank.value?.authorName || ''
  editBankVisible.value = true
}

async function submitEditBank() {
  const name = editBankForm.name.trim()
  if (!name) {
    ElMessage.warning('请输入题库名称')
    return
  }
  editBankSubmitting.value = true
  try {
    // 后端仅更新非 null 字段，这里只传表单里改动的字段
    const patch = {}
    if (name !== bank.value.name) patch.name = name
    if (editBankForm.description.trim() !== (bank.value.description || '')) {
      patch.description = editBankForm.description.trim() || null
    }
    if (editBankForm.source.trim() !== (bank.value.source || '')) {
      patch.source = editBankForm.source.trim() || null
    }
    if (editBankForm.authorName.trim() !== (bank.value.authorName || '')) {
      patch.authorName = editBankForm.authorName.trim() || null
    }
    if (Object.keys(patch).length === 0) {
      ElMessage.info('没有需要保存的修改')
      editBankVisible.value = false
      return
    }
    await updateBank(id, patch)
    ElMessage.success('题库已更新')
    editBankVisible.value = false
    await loadBank()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    editBankSubmitting.value = false
  }
}

/* ---------- 导出题库文件 ---------- */
const exportVisible = ref(false)
const exporting = ref(false)
const exportForm = reactive({ version: '', authorName: '', mode: 'AUTO', format: 'tiku' })

const formatOptions = [
  { value: 'tiku', label: '.tiku 容器（推荐）', desc: '图片与文字分离打包，体积小、加载快' },
  { value: 'json', label: '.json 纯文本', desc: '兼容旧版拾题应用；图片以 base64 内嵌，文件较大' }
]

const modeOptions = [
  { value: 'AUTO', label: '自动判断', desc: '内容未变沿用原发布身份；已修改则另存新文件' },
  { value: 'UPGRADE', label: '更新版本', desc: '沿用发布身份出新版本（作者迭代）' },
  { value: 'BRANCH', label: '另存新文件', desc: '生成新的发布身份（不改变原题库）' }
]

const DEFAULT_AUTHOR_KEY = 'tiku:default-author'

function openExport() {
  exportForm.version = bank.value?.version || '1.0.0'
  //作者名默认：题库记录 → 设置页"默认作者名"（本地记忆，中心账号上线前用）
  let savedAuthor = ''
  try {
    savedAuthor = localStorage.getItem(DEFAULT_AUTHOR_KEY) || ''
  } catch (e) {
    /* 忽略 */
  }
  exportForm.authorName = bank.value?.authorName || savedAuthor
  exportForm.mode = 'AUTO'
  exportForm.format = 'tiku'
  exportVisible.value = true
}

async function submitExport() {
  const version = exportForm.version.trim() || '1.0.0'
  const payload = { version }
  if (exportForm.authorName.trim()) payload.authorName = exportForm.authorName.trim()
  if (bank.value?.packageKey && exportForm.mode !== 'AUTO') payload.mode = exportForm.mode
  exporting.value = true
  try {
    const baseName = `${bank.value?.name || '题库'}-${version}`
    if (exportForm.format === 'tiku') {
      // v2 .tiku 容器（zip：package.json + media/）——带图题库体积小，推荐
      const blob = await exportTikuBank(id, payload)
      if (!(blob instanceof Blob) || blob.size === 0) throw new Error('导出失败')
      const res = await saveBlob(blob, `${baseName}.tiku`)
      if (res && !res.saved) return //桌面版用户取消另存为
      ElMessage.success(res?.path ? `已导出到：${res.path}` : `已导出 ${baseName}.tiku（可到题库广场发布）`)
    } else {
      // v1 纯 JSON（兼容旧版拾题应用；图片以 base64 内嵌，文件较大）
      const file = await exportBank(id, payload)
      const pkg = { ...file }
      delete pkg.checksum
      const res = await saveJsonFile(pkg, `${baseName}.json`)
      if (res && !res.saved) return //桌面版用户取消另存为
      ElMessage.success(res?.path ? `已导出到：${res.path}` : `已导出 ${baseName}.json（可到题库广场发布）`)
    }
    exportVisible.value = false
    // UPGRADE 迭代时后端会把新版本登记回题库，刷新信息
    await loadBank()
    if (reviewEnabled.value) loadReviewDue()
  } catch (e) {
    /* 400（内容未变却改版本号等）由拦截器提示，弹窗保留供调整 */
  } finally {
    exporting.value = false
  }
}

/* ---------- 批量导入题目 ---------- */
const batchVisible = ref(false)
const batchText = ref('')
const batchSubmitting = ref(false)

function openBatch() {
  batchText.value = ''
  batchVisible.value = true
}

async function pickBatchFile() {
  let file
  try {
    file = await pickFile('.json,application/json')
  } catch (e) {
    return
  }
  try {
    batchText.value = await readTextFile(file)
  } catch (e) {
    ElMessage.error('文件读取失败')
  }
}

async function submitBatch() {
  let questions
  try {
    const parsed = JSON.parse(batchText.value)
    questions = Array.isArray(parsed) ? parsed : parsed?.questions
    if (!Array.isArray(questions) || questions.length === 0) {
      throw new Error('empty')
    }
  } catch (e) {
    ElMessage.warning('JSON 格式不正确：应为题目数组，或 { "questions": [...] } 对象')
    return
  }
  batchSubmitting.value = true
  try {
    const inserted = await batchCreateQuestions(id, questions)
    ElMessage.success(`批量导入成功，新增 ${inserted} 道题目`)
    batchVisible.value = false
    loadQuestions()
    loadProgress()
  } catch (e) {
    /* 校验失败信息由拦截器提示 */
  } finally {
    batchSubmitting.value = false
  }
}

/* ---------- AI 批量补答案（A1：无答案客观题 → 思考模式判定回填） ---------- */
const fillVisible = ref(false)
const fillBusy = ref(false)
const fillType = ref('') // '' = 全部客观题
const fillWithAnalysis = ref(false)

function openAiFill() {
  fillType.value = ''
  fillWithAnalysis.value = false
  fillVisible.value = true
}

async function submitAiFill() {
  fillBusy.value = true
  try {
    const r = await aiFillAnswers(id, {
      questionType: fillType.value || null,
      withAnalysis: fillWithAnalysis.value
    })
    const { total = 0, filled = 0, undetermined = 0, failed = 0 } = r || {}
    fillVisible.value = false
    if (total === 0) {
      ElMessage.info('本库没有无答案的客观题，无需补答案')
    } else {
      const parts = [`共处理 ${total} 道无答案客观题：`]
      parts.push(`✅ 已回填 ${filled} 道`)
      if (undetermined > 0) parts.push(`❓ 无法判定 ${undetermined} 道（选项不完整或模型无法确定，可手动补）`)
      if (failed > 0) parts.push(`⚠️ 批次失败 ${failed} 道（可稍后重试）`)
      ElMessageBox.alert(parts.join('<br/>'), 'AI 补答案完成', {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '好的'
      })
    }
    loadQuestions()
    loadQuestionNav()
    loadProgress()
  } catch (e) {
    /* 网络/服务中断：拦截器已提示；后端可能已写入部分批次，刷新列表可见 */
    loadQuestions()
    loadQuestionNav()
  } finally {
    fillBusy.value = false
  }
}

/* ---------- 删除题库 ---------- */
async function confirmDeleteBank() {
  try {
    await ElMessageBox.confirm(
      `确定删除题库「${bank.value.name}」吗？将同时删除其下 ${qTotal.value} 道题目和全部刷题记录，此操作不可恢复。`,
      '删除题库',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (e) {
    return // 用户取消
  }
  try {
    const result = await deleteBank(id)
    const { deletedQuestions = 0, affectedRecords = 0 } = result || {}
    ElMessage.success(`题库已删除（连带删除 ${deletedQuestions} 道题目、${affectedRecords} 条刷题记录）`)
    router.push('/')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* ---------- 行内收藏切换（列表星标点击，A3） ---------- */
async function toggleRowFavorite(q) {
  const next = !q.favorite
  try {
    await setFavorite(q.questionId, next)
    q.favorite = next
    loadFavoriteCount() //重查计数（避免与首次加载竞态）
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* ---------- 删除题目 ---------- */
async function confirmDeleteQuestion(q) {
  try {
    await ElMessageBox.confirm(
      `确定删除第 ${q.questionNumber ?? '—'} 题吗？此操作不可恢复。`,
      '删除题目',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (e) {
    return
  }
  try {
    await deleteQuestion(q.questionId)
    ElMessage.success('题目已删除')
    // 当前页删空则回退一页
    if (questions.value.length === 1 && qPage.value > 1) {
      qPage.value--
    }
    loadQuestions()
    loadProgress()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

loadBank()
loadQuestions()
loadProgress()
loadWrongCount()
loadFavoriteCount()
loadReviewDue()
loadTopicOptions() // 分类筛选选项（与 TOPIC 会话共用，幂等）
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.title-line {
  display: flex;
  align-items: center;
  gap: 10px;
}
.page-title {
  font-size: 26px;
}
.version-tag {
  font-size: 12px;
  color: var(--accent-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 999px;
  padding: 2px 10px;
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
.source-text {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--text-muted);
}
.header-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  flex-wrap: wrap;
  /* 窄视口下随父容器收窄，内部按钮换行而不横向溢出 */
  max-width: 100%;
}

/* 学习进度卡片 */
.progress-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 18px 22px 16px;
  margin-bottom: 16px;
}
.progress-stats {
  display: flex;
  gap: 48px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-num {
  font-size: 21px;
  font-weight: 600;
  color: var(--accent-text);
}
.stat-label {
  font-size: 12px;
  color: var(--text-muted);
}
.progress-track {
  height: 6px;
  border-radius: 999px;
  background: var(--bg-elev);
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
  transition: width var(--ease-slow);
}
.progress-hint {
  margin: 10px 0 0;
  font-size: 12px;
}

/* 工具条 */
.toolbar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 12px 0 20px;
  flex-wrap: wrap;
}
.tool-item {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tool-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.tool-tip {
  font-size: 12px;
}
.due-btn {
  gap: 6px;
}

/* 开关 */
.toggle {
  position: relative;
  width: 40px;
  height: 22px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  background: var(--bg-elev);
  cursor: pointer;
  transition: background var(--ease), border-color var(--ease);
  padding: 0;
}
.toggle .toggle-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--text-muted);
  transition: transform var(--ease), background var(--ease);
}
.toggle.on {
  background: var(--accent);
  border-color: var(--accent);
}
.toggle.on .toggle-knob {
  transform: translateX(18px);
  background: #fff;
}
.toggle:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 题目区 */
.questions-section {
  margin-top: 4px;
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 10px;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.section-title h2 {
  font-size: 17px;
}
.section-actions {
  display: flex;
  gap: 10px;
}
.count-badge {
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 1px 9px;
}

/* 右侧悬浮题号盘（编辑模式）：fixed 于内容右缘与视口右边界之间，主内容始终全宽不受挤压 */
.edit-dock-side {
  position: fixed;
  top: 84px;
  right: 24px;
  width: 188px;
  z-index: 40;
}
/* 窄视口展开态（点击"题号"小按钮后）：面板下移避开按钮，浮于内容之上 */
.edit-dock-side.dock-pop {
  top: 132px;
}
.edit-dock-side.dock-pop :deep(.qnav-grid) {
  max-height: calc(100vh - 330px);
}



.q-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.q-filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.q-filter-hint {
  font-size: 12px;
}
.q-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  cursor: pointer;
  transition: border-color var(--ease), background var(--ease);
}
.q-row:hover {
  border-color: var(--border-strong);
  background: var(--bg-hover);
}
.q-row:hover .q-content {
  color: var(--accent-text);
}
.q-number {
  flex-shrink: 0;
  min-width: 44px;
  color: var(--text-muted);
  font-size: 13px;
}
.q-number.unnumbered {
  color: var(--text-muted);
  background: var(--bg-elev);
  border: 1px dashed var(--border-strong);
  border-radius: 6px;
  padding: 1px 7px;
  font-size: 12px;
  min-width: auto;
}
.q-number.dup-number {
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger);
  border-radius: 6px;
  padding: 1px 7px;
  font-size: 12px;
  min-width: auto;
}
.q-type {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent-text);
  border: 1px solid var(--accent);
}
.q-type.type-multiple {
  background: var(--success-soft);
  color: var(--success);
  border-color: var(--success);
}
.q-type.type-judge {
  background: var(--warning-soft);
  color: var(--warning);
  border-color: var(--warning);
}
.q-type.type-subjective {
  background: var(--type-subj-bg);
  color: var(--type-subj-fg);
  border-color: var(--type-subj-fg);
}
/* 客观题未配置答案标记 */
.q-no-answer {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger);
  border-radius: 6px;
  padding: 1px 7px;
}
.q-content {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.q-topic {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-secondary);
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 1px 8px;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.q-fav-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-muted);
  transition: color 0.15s, background 0.15s;
}
.q-fav-btn:hover {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 12%, transparent);
}
.q-fav-btn.on {
  color: var(--warning);
}
.q-score {
  flex-shrink: 0;
  font-size: 12px;
}
.q-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
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
.icon-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.icon-btn.danger:hover {
  background: var(--danger-soft);
  color: var(--danger);
}

.q-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 60px 0;
  color: var(--text-muted);
}
.q-empty p {
  margin: 0;
  font-size: 14px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 26px;
}

/* 选题另存：行勾选框 + 选中行高亮 + 底部操作条 */
.sel-box {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid var(--border-strong);
  border-radius: 6px;
  background: var(--bg-card);
  color: #fff;
  cursor: pointer;
  padding: 0;
  transition: all var(--ease);
}
.sel-box:hover {
  border-color: var(--accent);
}
.sel-box.on {
  background: var(--accent);
  border-color: var(--accent);
}
.q-row-sel {
  border-color: var(--accent) !important;
  background: var(--accent-soft) !important;
}
.selection-bar {
  position: sticky;
  bottom: 14px;
  z-index: 25;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 18px;
  padding: 10px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  box-shadow: 0 8px 26px rgba(0, 0, 0, 0.14);
  flex-wrap: wrap;
}
.sel-count {
  font-size: 13px;
  color: var(--text-primary);
}
.sel-count b {
  color: var(--accent-text);
}
.sel-spacer {
  flex: 1;
}
.section-actions .btn.active {
  border-color: var(--accent);
  color: var(--accent-text);
  background: var(--accent-soft);
}

/* 行内 AI 解析面板 */
.q-row-open {
  box-shadow: inset 0 0 0 1px var(--accent);
}
.q-ai-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 14px;
  margin: 0 0 10px;
  background: var(--bg-card);
  border-radius: 8px;
  border: 1px solid var(--border);
}
.ai-panel-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ai-panel-row .an-label {
  display: inline-block;
  width: auto;
  margin-right: 8px;
}

/* 解析弹窗 */
.analysis-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 60vh;
  overflow-y: auto;
}
.an-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.an-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
}
.an-content {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);
}
.an-answer {
  font-size: 14px;
  font-weight: 700;
  color: var(--success);
}
.an-empty {
  font-size: 13px;
  margin: 0;
}

/* 弹窗内通用 */
.form-tip {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
.mode-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.mode-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 10px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text-primary);
  font-family: var(--font-sans);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--ease), background var(--ease);
}
.mode-option:hover {
  border-color: var(--border-strong);
}
.mode-option.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.mode-title {
  font-size: 14px;
  font-weight: 500;
}
.mode-desc {
  font-size: 12px;
  color: var(--text-secondary);
}

/* 批量导入 */
.batch-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.batch-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}
.batch-desc code {
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
  font-size: 12px;
}

/* AI 批量补答案弹窗 */
.fill-desc {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
  font-size: 13px;
  line-height: 1.7;
}
.fill-desc p {
  margin: 0;
}
.fill-busy {
  margin: 4px 0 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  color: var(--accent);
  font-size: 13px;
  line-height: 1.6;
}

/* 错题弹窗 */
.wrong-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 420px;
  overflow-y: auto;
}
.wrong-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.wrong-content {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wrong-meta {
  flex-shrink: 0;
  font-size: 12px;
}
.wrong-pager {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}
.wrong-loading,
.wrong-empty {
  padding: 30px 0;
  text-align: center;
  font-size: 13px;
}
/* 待复习队列：逾期欠账弱标记（区分历史欠账与今日到期） */
.due-overdue {
  color: var(--danger);
  font-weight: 600;
}
.due-today {
  color: var(--accent-text);
}

/* 错误态 */
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 100px 0;
  color: var(--text-muted);
  text-align: center;
}
.error-state h3 {
  color: var(--text-primary);
  font-size: 17px;
}
.error-state .btn {
  margin-top: 12px;
}

.sk-line {
  height: 12px;
  border-radius: 6px;
  background: var(--bg-hover);
  flex: 1;
}

/* 材料管理弹窗 */
.material-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 300px;
  overflow-y: auto;
  margin-bottom: 16px;
}
.material-item {
  padding: 12px 14px;
  background: var(--bg-elev);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.material-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.material-content :deep(.rich-img) {
  max-width: 100%;
  max-height: 220px;
  border-radius: 8px;
  margin: 6px 0;
  display: block;
}
.material-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.material-empty {
  font-size: 13px;
  text-align: center;
  padding: 24px 0;
}
.material-edit {
  padding: 14px;
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-control);
}
.field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.material-edit .field-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.img-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elev);
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
  margin-bottom: 8px;
  transition: all var(--ease);
}
.img-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent-text);
}
.img-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.material-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}
</style>
