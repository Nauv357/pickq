<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('pageTitle') }}</h1>
        <p class="page-desc">{{ t('pageDesc') }}</p>
      </div>
      <div class="header-actions">
        <button class="btn btn-secondary" :disabled="loading" @click="load()">
          <TikuIcon name="refresh" :size="15" />
          {{ t('refresh') }}
        </button>
        <button class="btn btn-secondary" @click="openPlaza">
          <TikuIcon name="link" :size="15" />
          {{ t('browseWeb') }}
        </button>
        <button class="btn btn-primary" :disabled="!currentUser || meLoading" @click="openPublish">
          <TikuIcon name="upload" :size="15" />
          {{ t('publishNew') }}
        </button>
      </div>
    </header>

    <!-- 刚发布成功：给一个去官网查看的入口（弹窗已关闭） -->
    <div v-if="lastPublished" class="ok-banner">
      <TikuIcon name="check" :size="15" />
      <span class="ok-text">{{ t('publishedTip', { title: lastPublished.title }) }}</span>
      <button v-if="lastPublished.packageKey" class="btn btn-ghost btn-sm" @click="openPack(lastPublished)">{{ t('openOnWeb') }}</button>
      <button class="btn btn-ghost btn-sm icon-btn" :title="t('close')" @click="lastPublished = null">
        <TikuIcon name="x" :size="14" />
      </button>
    </div>

    <!-- 登录态：恢复中 -->
    <div v-if="meLoading" class="works-state">
      <div class="sk-row tiku-skeleton"></div>
      <div class="sk-row tiku-skeleton"></div>
    </div>

    <!-- 未登录：引导去「发现题库」页登录（登录/注册在广场页统一处理） -->
    <div v-else-if="!currentUser" class="works-state">
      <TikuIcon name="info" :size="40" />
      <h3>{{ t('needLoginTitle') }}</h3>
      <p class="text-secondary">{{ t('needLoginDesc') }}</p>
      <RouterLink class="btn btn-primary" to="/discover">{{ t('goDiscover') }}</RouterLink>
    </div>

    <template v-else>
      <p class="account-line text-muted">
        {{ t('accountPrefix') }}<span class="account-name">{{ accountName }}</span>
      </p>

      <!-- 加载骨架 -->
      <div v-if="loading && !records.length" class="works-list">
        <div v-for="n in 3" :key="n" class="sk-row tiku-skeleton"></div>
      </div>

      <!-- 加载失败（含会话失效：已回到未登录态，交由上方分支引导） -->
      <div v-else-if="listError" class="works-state">
        <TikuIcon name="info" :size="40" />
        <h3>{{ t('loadFail') }}</h3>
        <p class="text-secondary">{{ listError }}</p>
        <button class="btn btn-secondary" @click="load()">{{ t('retry') }}</button>
      </div>

      <!-- 空态 -->
      <div v-else-if="!records.length" class="works-state">
        <TikuIcon name="package" :size="40" />
        <h3>{{ t('emptyTitle') }}</h3>
        <p class="text-secondary">{{ t('emptyDesc') }}</p>
        <button class="btn btn-primary" @click="openPublish">{{ t('publishNew') }}</button>
      </div>

      <!-- 作品列表 -->
      <div v-else class="works-list">
        <p class="list-total text-muted">{{ t('totalN', { n: total }) }}</p>
        <div v-for="w in records" :key="w.packageKey" class="w-item">
          <div class="w-main">
            <p class="w-title">
              <button class="title-link" :title="t('viewPage')" @click="openPack(w)">{{ w.title }}</button>
              <span class="tag" :class="w.status === 'REMOVED' ? 'removed' : 'active'">{{ statusText(w.status) }}</span>
              <span v-if="w.storageKind === 'HOSTED'" class="tag hosted">{{ t('tagHosted') }}</span>
              <span v-else-if="w.storageKind === 'EXTERNAL'" class="tag ext">{{ t('tagExternal') }}</span>
            </p>
            <p class="w-desc">{{ w.description || t('noDesc') }}</p>
            <p class="w-meta">
              {{ t('versionN', { v: w.version }) }}
              <template v-if="Number(w.versionCount || 0) > 1"> · {{ t('versionsN', { n: w.versionCount }) }}</template>
              · {{ t('questionsN', { n: w.questionsCount ?? '—' }) }}
              · {{ t('favoritesN', { n: w.favoritesCount ?? 0 }) }}
              · {{ t('downloadClicksN', { n: w.downloadClicks ?? 0 }) }}
              <template v-if="w.fileSizeBytes"> · {{ formatBytes(w.fileSizeBytes) }}</template>
              · {{ t('createdOn', { d: dateText(w.createdAt) }) }}
            </p>
            <p v-if="w.storageKind === 'EXTERNAL' && w.downloadUrl" class="w-link text-muted">
              {{ t('linkPrefix') }}<span class="mono ellipsis">{{ w.downloadUrl }}</span>
            </p>
          </div>
          <div class="w-actions">
            <button class="btn btn-ghost btn-sm icon-btn" :title="t('viewPage')" @click="openPack(w)">
              <TikuIcon name="link" :size="14" />
            </button>
            <button class="btn btn-secondary btn-sm" :disabled="isBusy(w)" @click="openEdit(w)">{{ t('edit') }}</button>
            <!-- 补传文件仅在「登记为托管但尚无托管文件」时可用：
                 已带文件指纹的版本官网会直接 409（如需替换请下架后重新发布） -->
            <button
              v-if="canUploadFile(w)"
              class="btn btn-secondary btn-sm"
              :disabled="isBusy(w)"
              :title="t('uploadFileTip')"
              @click="openFileDialog(w)"
            >{{ t('uploadFile') }}</button>
            <button
              v-if="w.status !== 'REMOVED'"
              class="btn btn-danger btn-sm"
              :disabled="isBusy(w)"
              @click="doRemove(w)"
            >{{ busyKey === `${w.packageKey}:remove` ? t('removing') : t('remove') }}</button>
            <span v-else class="removed-note text-muted">{{ t('removedNote') }}</span>
          </div>
        </div>
      </div>

      <div v-if="total > size" class="pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="size"
          :current-page="page"
          @current-change="onPage"
        />
      </div>

      <!-- 底部说明：发布前先导出 .tiku 文件 -->
      <p class="foot-tip text-muted">
        {{ t('exportTip') }}
        <RouterLink class="foot-link" to="/banks">{{ t('gotoBanks') }}</RouterLink>
      </p>
    </template>

    <!-- ============ 发布新作品 ============ -->
    <el-dialog
      v-model="publishVisible"
      :title="t('pubTitle')"
      width="min(94vw, 560px)"
      align-center
      :close-on-click-modal="false"
      :close-on-press-escape="!pubBusy"
      :show-close="!pubBusy"
      :before-close="beforePublishClose"
      @closed="afterPublishClosed"
    >
      <p v-if="pubError" class="form-error">{{ pubError }}</p>

      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('pubFileLabel')">
          <div class="file-pick">
            <label class="btn btn-secondary">
              <TikuIcon name="file" :size="14" />
              {{ pubFile ? t('pubChooseAgain') : t('pubChooseFile') }}
              <input
                ref="pubInput"
                class="file-input"
                type="file"
                accept=".tiku,.json"
                :disabled="pubBusy"
                @change="onPickPublishFile"
              />
            </label>
            <span v-if="pubFile" class="file-name ellipsis">{{ t('pubFileChosen', { name: pubFile.name, size: formatBytes(pubFile.size) }) }}</span>
            <span v-else class="text-muted file-hint">{{ t('pubFileHint') }}</span>
          </div>

          <!-- 选中文件后立刻本地体检（后端只解析元数据、不转发）：不合格就不必跨境传 200MB -->
          <p v-if="pubInspecting" class="inspect-line text-muted">
            <span class="inspect-spinner" />
            {{ t('inspecting') }}
          </p>
          <template v-else-if="pubInspectError">
            <p class="inspect-fail">{{ t('inspectFail') }}</p>
            <p class="inspect-fail inspect-detail">{{ pubInspectError }}</p>
            <p class="field-tip text-muted">{{ t('inspectFailTip') }}</p>
          </template>
          <div v-else-if="pubInspect" class="inspect-box">
            <p class="inspect-head">
              <TikuIcon name="check" :size="14" />
              {{ t('inspectResultTitle') }}
            </p>
            <p class="inspect-row">
              <span class="inspect-label">{{ t('inspectTitleLabel') }}</span>
              <span class="inspect-value" :title="pubInspect.title">{{ pubInspect.title }}</span>
            </p>
            <p class="inspect-row">
              <span class="inspect-label">{{ t('inspectQuestionsLabel') }}</span>
              <span class="inspect-value">
                {{ t('questionsCount', { n: pubInspect.questionsCount ?? 0 }) }}
                <template v-if="Number(pubInspect.materialsCount || 0) > 0"> · {{ t('materialsCount', { n: pubInspect.materialsCount }) }}</template>
              </span>
            </p>
            <p class="inspect-row">
              <span class="inspect-label">{{ t('inspectVersionLabel') }}</span>
              <span class="inspect-value mono">{{ pubInspect.version }}</span>
            </p>
            <p class="inspect-row">
              <span class="inspect-label">{{ t('inspectKeyLabel') }}</span>
              <span class="inspect-value mono" :title="pubInspect.packageKey">{{ pubInspect.packageKey }}</span>
            </p>
          </div>
        </el-form-item>

        <el-form-item>
          <template #label>{{ t('pubTitleLabel') }}</template>
          <el-input
            v-model="pubForm.title"
            maxlength="200"
            :disabled="pubBusy"
            :placeholder="t('pubTitlePh')"
            @input="onPubTitleInput"
          />
          <p class="field-tip text-muted">{{ t('pubTitleHint') }}</p>
        </el-form-item>

        <el-form-item>
          <template #label>{{ t('pubDescLabel') }}</template>
          <el-input
            v-model="pubForm.description"
            type="textarea"
            :rows="3"
            resize="none"
            maxlength="2000"
            show-word-limit
            :disabled="pubBusy"
            :placeholder="t('pubDescPh')"
          />
        </el-form-item>

        <el-form-item>
          <template #label>{{ t('pubSourceLabel') }}</template>
          <el-input
            v-model="pubForm.source"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="500"
            show-word-limit
            :disabled="pubBusy"
            :placeholder="t('pubSourcePh')"
          />
        </el-form-item>

        <el-form-item :label="t('pubStorageLabel')">
          <el-radio-group v-model="pubForm.storageKind" :disabled="pubBusy">
            <el-radio-button value="HOSTED">{{ t('pubHosted') }}</el-radio-button>
            <el-radio-button value="EXTERNAL">{{ t('pubExternal') }}</el-radio-button>
          </el-radio-group>
          <p class="field-tip text-muted">{{ pubForm.storageKind === 'HOSTED' ? t('pubHostedDesc') : t('pubExternalDesc') }}</p>
        </el-form-item>

        <el-form-item v-if="pubForm.storageKind === 'EXTERNAL'">
          <template #label>{{ t('pubUrlLabel') }}</template>
          <el-input v-model="pubForm.downloadUrl" :disabled="pubBusy" :placeholder="t('pubUrlPh')" />
          <p class="field-tip text-muted">{{ t('pubUrlHint') }}</p>
        </el-form-item>
      </el-form>

      <!-- 上传进度：大文件可能上百 MB，必须可见 -->
      <div v-if="pubBusy" class="progress-box">
        <el-progress
          :percentage="pubPct"
          :stroke-width="10"
          :indeterminate="pubIndeterminate"
          :status="pubServerBusy ? 'success' : ''"
        />
        <p class="progress-tip text-muted">
          {{ pubServerBusy ? t('pubServerBusy') : t('pubUploading', { p: pubPct }) }}
        </p>
      </div>

      <template #footer>
        <button class="btn btn-ghost" :disabled="pubBusy" @click="publishVisible = false">{{ t('cancel') }}</button>
        <button v-if="pubBusy" class="btn btn-danger" @click="cancelPublish">{{ t('cancelUpload') }}</button>
        <button
          v-else
          class="btn btn-primary"
          :disabled="!pubReady"
          :title="pubReady ? '' : t('pubBlockedTip')"
          @click="doPublish"
        >{{ t('doPublish') }}</button>
      </template>
    </el-dialog>

    <!-- ============ 编辑作品信息 ============ -->
    <el-dialog
      v-model="editVisible"
      :title="t('editTitle')"
      width="min(94vw, 520px)"
      align-center
      :close-on-click-modal="false"
      :show-close="!editBusy"
      :before-close="beforeEditClose"
      @closed="resetEdit"
    >
      <p v-if="editError" class="form-error">{{ editError }}</p>

      <el-form v-if="editTarget" label-position="top" @submit.prevent>
        <el-form-item :label="t('editWorkLabel')">
          <p class="edit-work">{{ editTarget.title }}</p>
          <p class="field-tip text-muted">{{ t('editVersion', { v: editTarget.version }) }} · {{ t('editTitleLocked') }}</p>
        </el-form-item>

        <el-form-item>
          <template #label>{{ t('pubDescLabel') }}</template>
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="4"
            resize="none"
            maxlength="2000"
            show-word-limit
            :disabled="editBusy"
            :placeholder="t('pubDescPh')"
          />
        </el-form-item>

        <el-form-item>
          <template #label>{{ t('pubSourceLabel') }}</template>
          <el-input
            v-model="editForm.source"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="500"
            show-word-limit
            :disabled="editBusy"
            :placeholder="t('pubSourcePh')"
          />
        </el-form-item>

        <el-form-item v-if="editTarget.storageKind !== 'HOSTED'">
          <template #label>{{ t('pubUrlLabel') }}</template>
          <el-input v-model="editForm.downloadUrl" :disabled="editBusy" :placeholder="t('pubUrlPh')" />
          <p class="field-tip text-muted">{{ t('editClearHint') }}</p>
        </el-form-item>
        <p v-else class="field-tip text-muted">{{ t('editHostedNoUrl') }}</p>
        <p v-if="editTarget.storageKind === 'HOSTED' && editTarget.fileSha256" class="field-tip text-muted">
          {{ t('editHostedFileNote') }}
        </p>
      </el-form>

      <template #footer>
        <button class="btn btn-ghost" :disabled="editBusy" @click="editVisible = false">{{ t('cancel') }}</button>
        <button class="btn btn-primary" :disabled="editBusy" @click="doEdit">
          {{ editBusy ? t('saving') : t('save') }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 补传托管文件 ============ -->
    <el-dialog
      v-model="fileVisible"
      :title="t('fileTitle')"
      width="min(94vw, 520px)"
      align-center
      :close-on-click-modal="false"
      :close-on-press-escape="!fileBusy"
      :show-close="!fileBusy"
      :before-close="beforeFileClose"
      @closed="resetFileDialog"
    >
      <p v-if="fileError" class="form-error">{{ fileError }}</p>

      <template v-if="fileTarget">
        <p class="file-target">
          {{ fileTarget.title }}
          <span class="mono text-muted">· {{ fileTarget.packageKey }} / v{{ fileTarget.version }}</span>
        </p>
        <p class="field-tip text-muted">{{ t('fileHint') }}</p>

        <div class="file-pick">
          <label class="btn btn-secondary">
            <TikuIcon name="file" :size="14" />
            {{ filePick ? t('pubChooseAgain') : t('pubChooseFile') }}
            <input
              ref="fileInput"
              class="file-input"
              type="file"
              accept=".tiku,.json"
              :disabled="fileBusy"
              @change="onPickUploadFile"
            />
          </label>
          <span v-if="filePick" class="file-name ellipsis">{{ t('pubFileChosen', { name: filePick.name, size: formatBytes(filePick.size) }) }}</span>
          <span v-else class="text-muted file-hint">{{ t('filePickHint') }}</span>
        </div>

        <div v-if="fileBusy" class="progress-box">
          <el-progress
            :percentage="filePct"
            :stroke-width="10"
            :indeterminate="fileIndeterminate"
            :status="fileServerBusy ? 'success' : ''"
          />
          <p class="progress-tip text-muted">
            {{ fileServerBusy ? t('pubServerBusy') : t('pubUploading', { p: filePct }) }}
          </p>
        </div>
      </template>

      <template #footer>
        <button class="btn btn-ghost" :disabled="fileBusy" @click="fileVisible = false">{{ t('cancel') }}</button>
        <button v-if="fileBusy" class="btn btn-danger" @click="cancelFileUpload">{{ t('cancelUpload') }}</button>
        <button v-else class="btn btn-primary" @click="doUploadFile">{{ t('doUpload') }}</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import TikuIcon from '../components/TikuIcon.vue'
import http from '../api/http'
import { centerPacksUrl, getCenterUrl } from '../utils/center'
import { openExternal } from '../utils/external'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      pageTitle: '我的作品',
      pageDesc: '管理你在拾题题库广场发布的作品：编辑信息、补传托管文件、下架，或发布新作品',
      publishNew: '发布新作品',
      refresh: '刷新',
      browseWeb: '在官网浏览',
      close: '关闭',
      /* ---- 登录态 ---- */
      accountPrefix: '当前广场账号：',
      needLoginTitle: '请先登录题库广场账号',
      needLoginDesc: '发布作品与管理作品都需要登录题库广场账号；请先到「发现题库」页登录，再回到本页。',
      goDiscover: '去发现题库页登录',
      /* ---- 列表 ---- */
      totalN: '共 {n} 个作品',
      emptyTitle: '还没有发布过作品',
      emptyDesc: '先在题库详情页导出 .tiku 文件，再回到这里点「发布新作品」。',
      loadFail: '加载我的作品失败',
      retry: '重试',
      statusActive: '在架',
      statusRemoved: '已下架',
      removedNote: '已下架的作品不再在广场展示',
      tagHosted: '中心托管',
      tagExternal: '作者外链',
      noDesc: '未填写描述',
      versionN: 'v{v}',
      versionsN: '{n} 个版本',
      questionsN: '共 {n} 题',
      favoritesN: '收藏 {n}',
      downloadClicksN: '下载跳转 {n}',
      createdOn: '创建于 {d}',
      linkPrefix: '下载链接：',
      viewPage: '在官网查看作品页',
      openOnWeb: '在官网查看',
      edit: '编辑',
      save: '保存',
      saving: '保存中…',
      uploadFile: '补传文件',
      uploadFileTip: '该版本尚无托管文件，可在此补传',
      remove: '下架',
      removing: '下架中…',
      /* ---- 发布对话框 ---- */
      pubTitle: '发布新作品',
      pubFileLabel: '题库文件',
      pubChooseFile: '选择题库文件',
      pubChooseAgain: '重新选择文件',
      pubFileHint: '支持 .tiku 与 .json 两种题库文件，单个文件不超过 200MB',
      pubFileChosen: '已选择：{name}（{size}）',
      /* 选中文件后本地体检（后端只解析元数据，不合格就不用跨境上传） */
      inspecting: '正在检查题库文件…',
      inspectResultTitle: '已识别',
      inspectFail: '题库文件检查未通过',
      inspectFailTip: '该文件无法发布，请重新选择题库文件（支持 .tiku 与 .json 两种格式）',
      inspectTitleLabel: '标题',
      inspectQuestionsLabel: '题目',
      inspectVersionLabel: '版本',
      inspectKeyLabel: '标识',
      questionsCount: '{n} 题',
      materialsCount: '{n} 份材料',
      pubTitleLabel: '标题（选填）',
      pubTitlePh: '默认使用题库文件内的标题',
      pubTitleHint: '标题来自题库文件，可在此修改；留空则沿用文件内的标题',
      pubBlockedTip: '请选择题库文件并等待检查通过后再发布',
      msgNeedInspect: '题库文件尚未通过本地检查，请重新选择文件',
      msgInspecting: '正在检查题库文件，请稍候',
      pubDescLabel: '描述（选填）',
      pubDescPh: '介绍一下这份题库，方便别人了解',
      pubSourceLabel: '来源声明（选填）',
      pubSourcePh: '题目来源、授权说明等',
      pubStorageLabel: '托管方式',
      pubHosted: '中心托管（推荐）',
      pubExternal: '作者外链',
      pubHostedDesc: '文件上传到拾题中心，广场上可直接导入',
      pubExternalDesc: '只在中心登记元数据，下载链接由你自己提供',
      pubUrlLabel: '下载链接',
      pubUrlPh: 'https://…（.tiku 或 .json 直链）',
      pubUrlHint: '需为能直接下载题库文件的 http(s) 直链；网盘页面链接无法直接导入',
      pubUploading: '已上传 {p}%，请保持窗口打开',
      pubServerBusy: '文件已上传，正在等待中心处理…',
      doPublish: '发布',
      cancel: '取消',
      cancelUpload: '取消上传',
      msgNeedFile: '请先选择题库文件（.tiku 或 .json）',
      msgBadFileType: '只支持 .tiku 与 .json 文件',
      msgFileTooBig: '文件超过 200MB 上限',
      msgNeedUrl: '作者外链方式需要填写下载链接',
      msgBadUrl: '下载链接需为 http(s) 直链',
      msgPublished: '已发布，可在广场查看',
      publishedTip: '「{title}」已发布',
      msgUploadCanceled: '已取消上传',
      publishFail: '发布失败，请稍后重试',
      /* ---- 编辑对话框 ---- */
      editTitle: '编辑作品信息',
      editWorkLabel: '作品',
      editVersion: '版本 {v}',
      editTitleLocked: '标题来自题库文件，不可修改',
      editHostedNoUrl: '中心托管作品的下载方式由中心管理，不能填写外链',
      editHostedFileNote: '该作品的文件已上传；如需替换文件，请下架后重新发布。',
      editClearHint: '留空并保存可清空该项',
      msgNoChange: '没有修改任何内容',
      msgUpdated: '已更新',
      updateFail: '更新失败，请稍后重试',
      /* ---- 补传文件对话框 ---- */
      fileTitle: '补传托管文件',
      filePickHint: '选择与该版本对应的 .tiku 或 .json 文件',
      fileHint: '文件内的 packageKey 与 version 必须与该版本一致',
      doUpload: '开始上传',
      msgFileUploaded: '文件已补传',
      fileUploadFail: '补传失败，请稍后重试',
      /* ---- 下架 ---- */
      removeTitle: '下架作品',
      removeAsk: '将下架「{title}」：广场上不再展示该作品（托管文件会一并删除）。确定吗？',
      msgRemoved: '已下架',
      removeFail: '下架失败，请稍后重试',
      /* ---- 通用 ---- */
      actionNeedLogin: '登录已失效，请重新登录题库广场账号',
      /* ---- 底部说明 ---- */
      exportTip: '提示：发布前建议先在题库详情页点「导出题库文件」生成 .tiku 文件。',
      gotoBanks: '去题库'
    },
    'en-US': {
      pageTitle: 'My works',
      pageDesc: 'Manage the works you published on the PickQ plaza: edit info, upload the hosted file, take a work down, or publish a new one',
      publishNew: 'Publish new work',
      refresh: 'Refresh',
      browseWeb: 'Browse on website',
      close: 'Close',
      accountPrefix: 'Plaza account: ',
      needLoginTitle: 'Log in to your plaza account first',
      needLoginDesc: 'Publishing and managing works requires a plaza account. Log in on the Discover page, then come back here.',
      goDiscover: 'Log in on the Discover page',
      totalN: '{n} works',
      emptyTitle: 'You have not published anything yet',
      emptyDesc: 'Export a .tiku file from a bank detail page, then come back and click “Publish new work”.',
      loadFail: 'Failed to load your works',
      retry: 'Retry',
      statusActive: 'Live',
      statusRemoved: 'Taken down',
      removedNote: 'Taken-down works are no longer shown in the plaza',
      tagHosted: 'Hosted',
      tagExternal: 'External link',
      noDesc: 'No description',
      versionN: 'v{v}',
      versionsN: '{n} versions',
      questionsN: '{n} questions',
      favoritesN: '{n} favorites',
      downloadClicksN: '{n} downloads',
      createdOn: 'Created {d}',
      linkPrefix: 'Download link: ',
      viewPage: 'View the work page on the website',
      openOnWeb: 'View on website',
      edit: 'Edit',
      save: 'Save',
      saving: 'Saving…',
      uploadFile: 'Upload file',
      uploadFileTip: 'This version has no hosted file yet — upload it here',
      remove: 'Take down',
      removing: 'Taking down…',
      pubTitle: 'Publish a new work',
      pubFileLabel: 'Bank file',
      pubChooseFile: 'Choose a bank file',
      pubChooseAgain: 'Choose another file',
      pubFileHint: 'Both .tiku and .json bank files are supported, up to 200MB',
      pubFileChosen: 'Selected: {name} ({size})',
      inspecting: 'Checking the bank file…',
      inspectResultTitle: 'Detected',
      inspectFail: 'The bank file did not pass the check',
      inspectFailTip: 'This file cannot be published — choose another bank file (both .tiku and .json are supported)',
      inspectTitleLabel: 'Title',
      inspectQuestionsLabel: 'Questions',
      inspectVersionLabel: 'Version',
      inspectKeyLabel: 'ID',
      questionsCount: '{n} questions',
      materialsCount: '{n} materials',
      pubTitleLabel: 'Title (optional)',
      pubTitlePh: 'Uses the title inside the bank file by default',
      pubTitleHint: 'The title comes from the bank file and can be changed here; leave empty to keep the one inside the file',
      pubBlockedTip: 'Choose a bank file and wait for the check to pass before publishing',
      msgNeedInspect: 'The bank file has not passed the local check yet — choose the file again',
      msgInspecting: 'Checking the bank file, please wait',
      pubDescLabel: 'Description (optional)',
      pubDescPh: 'Describe this bank so others can understand it',
      pubSourceLabel: 'Source note (optional)',
      pubSourcePh: 'Where the questions come from, licensing, etc.',
      pubStorageLabel: 'Storage',
      pubHosted: 'Hosted by PickQ (recommended)',
      pubExternal: 'Your own link',
      pubHostedDesc: 'The file is uploaded to PickQ and can be imported straight from the plaza',
      pubExternalDesc: 'Only metadata is registered; you provide the download link yourself',
      pubUrlLabel: 'Download link',
      pubUrlPh: 'https://… (direct link to a .tiku or .json file)',
      pubUrlHint: 'Must be a direct http(s) link to the file; cloud-drive page links cannot be imported directly',
      pubUploading: '{p}% uploaded — please keep this window open',
      pubServerBusy: 'Upload finished, waiting for the server to process…',
      doPublish: 'Publish',
      cancel: 'Cancel',
      cancelUpload: 'Cancel upload',
      msgNeedFile: 'Choose a bank file first (.tiku or .json)',
      msgBadFileType: 'Only .tiku and .json files are supported',
      msgFileTooBig: 'The file exceeds the 200MB limit',
      msgNeedUrl: 'A download link is required for the “your own link” option',
      msgBadUrl: 'The download link must be a direct http(s) URL',
      msgPublished: 'Published — you can now see it in the plaza',
      publishedTip: '“{title}” is now published',
      msgUploadCanceled: 'Upload canceled',
      publishFail: 'Publish failed, please try again later',
      editTitle: 'Edit work info',
      editWorkLabel: 'Work',
      editVersion: 'Version {v}',
      editTitleLocked: 'The title comes from the bank file and cannot be changed',
      editHostedNoUrl: 'Hosted works are served by PickQ, so an external link cannot be set here',
      editHostedFileNote: 'The file for this work is already uploaded. To replace it, take the work down and publish it again.',
      editClearHint: 'Clear the field and save to remove it',
      msgNoChange: 'Nothing was changed',
      msgUpdated: 'Updated',
      updateFail: 'Update failed, please try again later',
      fileTitle: 'Upload the hosted file',
      filePickHint: 'Choose the .tiku or .json file for this version',
      fileHint: 'The packageKey and version inside the file must match this version',
      doUpload: 'Start upload',
      msgFileUploaded: 'File uploaded',
      fileUploadFail: 'Upload failed, please try again later',
      removeTitle: 'Take the work down',
      removeAsk: 'This takes “{title}” down: it will no longer be shown in the plaza (its hosted file is deleted too). Proceed?',
      msgRemoved: 'Taken down',
      removeFail: 'Failed to take down, please try again later',
      actionNeedLogin: 'Your session expired — please log in to the plaza account again',
      exportTip: 'Tip: before publishing, export a .tiku file from a bank detail page first.',
      gotoBanks: 'Go to Banks'
    }
  }
})

/* 上传类请求不能沿用 http 实例的 15s 超时（大文件可能上百 MB），单独放宽到 30 分钟 */
const UPLOAD_TIMEOUT_MS = 30 * 60 * 1000
/* 体检请求（本地后端只解析元数据）：定 2 分钟超时，避免请求悬挂 */
const INSPECT_TIMEOUT_MS = 2 * 60 * 1000

/* ---------- 登录态 ---------- */
const currentUser = ref(null)
const meLoading = ref(true)
const accountName = computed(() => {
  const u = currentUser.value
  return u ? u.nickname || u.username || '' : ''
})

/** 恢复广场账号登录态（未登录 / 断网都不打扰用户） */
async function fetchMe() {
  try {
    const r = await http.get('/center/auth/me', { skipErrorMessage: true })
    // 兼容 data 为 {user:null} 与直接 null 两种返回
    const u = r && typeof r === 'object' && 'user' in r ? r.user : r
    currentUser.value = u || null
  } catch (e) {
    currentUser.value = null
  }
}

/* ---------- 列表 ---------- */
const size = 10
const records = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const listError = ref('')
const busyKey = ref('') // `${packageKey}:${action}`

function dateText(s) {
  return s ? String(s).slice(0, 10) : '—'
}
function formatBytes(n) {
  const b = Number(n || 0)
  if (!b) return '—'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(1)} MB`
}
function statusText(s) {
  if (s === 'ACTIVE') return t('statusActive')
  if (s === 'REMOVED') return t('statusRemoved')
  return s || '—'
}
function isBusy(w) {
  return busyKey.value.startsWith(`${w.packageKey}:`)
}
/**
 * 补传入口是否可用：「登记为托管、在架、且尚无托管文件（无 fileSha256）」。
 * 官网 file.put.ts 对已有 fileSha256 + storage_key 的登记直接 409（要求未登记过文件），
 * 应用内发布的托管作品必然带指纹 → 那些版本不出现必然失败的入口；
 * 替换文件的正途是下架后重新发布（编辑弹窗里有说明）。
 */
function canUploadFile(w) {
  return w?.storageKind === 'HOSTED' && w.status !== 'REMOVED' && !w.fileSha256
}
/** 后端可读 message（与广场页口径一致） */
function errText(e, fallback) {
  const m = e?.response?.data?.message || e?.message
  return typeof m === 'string' && m.trim() ? m : fallback
}
/** http 拦截器对「HTTP 200 + code!==200」已统一弹过提示，避免二次弹窗 */
function alreadyToasted(e) {
  return !!e && !e.response && !e.config && !e.request
}
function toastError(e, fallback) {
  if (alreadyToasted(e)) return
  ElMessage.error(errText(e, fallback))
}
function isCanceled(e) {
  return e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError'
}
/** 后端未登录 / 会话失效：同步登录态，页面回到未登录引导（消息里含"登录"即视为会话问题） */
function syncLoginState(e) {
  const msg = errText(e, '')
  if (/登录|login/i.test(msg)) {
    currentUser.value = null
    ElMessage.warning(t('actionNeedLogin'))
    return true
  }
  return false
}

async function load() {
  if (!currentUser.value) {
    records.value = []
    total.value = 0
    return
  }
  loading.value = true
  listError.value = ''
  try {
    const d = await http.get('/center/me/packs', {
      skipErrorMessage: true,
      params: { center: getCenterUrl(), page: page.value, size }
    })
    records.value = d?.records || []
    total.value = Number(d?.total || 0)
  } catch (e) {
    records.value = []
    total.value = 0
    if (syncLoginState(e)) return
    listError.value = errText(e, t('loadFail'))
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  page.value = p
  load()
}

/* ---------- 打开官网 ---------- */
function openPlaza() {
  openExternal(centerPacksUrl())
}
function openPack(w) {
  if (!w?.packageKey) return
  // 官网作品页：https://pickq.cn/packs/{packageKey}
  openExternal(`${centerPacksUrl()}/${encodeURIComponent(w.packageKey)}`)
}

/* ============================================================
   发布新作品
   ============================================================ */
const publishVisible = ref(false)
const pubInput = ref(null)
const pubFile = ref(null)
const pubForm = reactive({ title: '', description: '', source: '', storageKind: 'HOSTED', downloadUrl: '' })
const pubBusy = ref(false)
const pubPct = ref(0)
const pubTotal = ref(0) // 0 = 浏览器未提供总长度 → 进度条走不确定态
const pubServerBusy = ref(false) // 文件字节传完、等中心处理响应
const pubError = ref('')
const lastPublished = ref(null)
let pubController = null
// 离开页面时中断上传：不再弹"已取消上传"（用户已看不到本页）
let leaving = false

/* ---------- 发布前本地体检（POST /center/publish/inspect：后端只解析元数据、不转发） ---------- */
const pubInspecting = ref(false)
/** 体检结果：{ packageKey, version, title, description, source, schemaVersion, questionsCount, materialsCount } */
const pubInspect = ref(null)
const pubInspectError = ref('')
let pubInspectController = null
// 用户手工改过标题后，不再用题库文件内的标题覆盖
let pubTitleTouched = false

/** 体检通过才允许提交：未选文件 / 正在检查 / 检查未通过都禁用「发布」 */
const pubReady = computed(() => !!pubFile.value && !!pubInspect.value && !pubInspecting.value)

/** 中断未完成的体检并清空其结果（换文件、文件不合法、关闭弹窗时复用） */
function clearPubInspect() {
  pubInspectController?.abort()
  pubInspectController = null
  pubInspecting.value = false
  pubInspect.value = null
  pubInspectError.value = ''
}

const pubIndeterminate = computed(() => pubBusy.value && pubTotal.value <= 0)

function openPublish() {
  if (!currentUser.value) {
    ElMessage.warning(t('actionNeedLogin'))
    return
  }
  pubError.value = ''
  publishVisible.value = true
}

function resetPubForm() {
  pubFile.value = null
  pubForm.title = ''
  pubForm.description = ''
  pubForm.source = ''
  pubForm.storageKind = 'HOSTED'
  pubForm.downloadUrl = ''
  pubError.value = ''
  pubPct.value = 0
  pubTotal.value = 0
  pubServerBusy.value = false
  clearPubInspect()
  pubTitleTouched = false
  if (pubInput.value) pubInput.value.value = ''
}

function afterPublishClosed() {
  resetPubForm()
}

/** 上传中不允许关闭弹窗（避免用户以为已取消） */
function beforePublishClose(done) {
  if (pubBusy.value) return
  done()
}

/** 用户手工编辑过标题（程序化赋值不触发 input 事件，故不会误标为已修改） */
function onPubTitleInput() {
  pubTitleTouched = true
}

function onPickPublishFile(e) {
  const f = e?.target?.files?.[0] || null
  // 同一文件重选也要触发 change
  if (e?.target) e.target.value = ''
  if (!f) return
  // 不按扩展名拦截：.tiku（v2 zip 容器）与 .json（v1 纯 JSON）由后端按魔数/内容判定，
  // 用户选错扩展名（如把 .tiku 存成 .json）也要能正确识别；格式不对由体检返回可读 message
  if (f.size > 200 * 1024 * 1024) {
    pubFile.value = null
    clearPubInspect()
    pubError.value = t('msgFileTooBig')
    return
  }
  pubError.value = ''
  pubFile.value = f
  pubPct.value = 0
  pubServerBusy.value = false
  // 选中文件立刻本地体检：不合格就不必跨境上传 200MB
  inspectPublishFile(f)
}

/**
 * 本地体检：把文件交给本地后端只解析元数据（不导入、不转发给公网）。
 * 成功：弹窗内回显标题/题数/版本/标识，并把标题输入框填成文件内标题（用户改过则不覆盖）；
 * 失败：弹窗内红字显示后端 message，提交按钮保持禁用，用户可重新选文件。
 */
async function inspectPublishFile(f) {
  const controller = new AbortController()
  clearPubInspect() // 连续换文件时只保留最新一次检查结果
  pubInspectController = controller
  pubInspecting.value = true
  const fd = new FormData()
  fd.append('file', f)
  try {
    const r = await http.post('/center/publish/inspect', fd, {
      skipErrorMessage: true, // 由弹窗自行展示，避免全局弹窗重复提示
      timeout: INSPECT_TIMEOUT_MS,
      signal: controller.signal
    })
    // 期间用户又换了文件/清空了选择：本次结果作废
    if (pubInspectController !== controller || pubFile.value !== f) return
    pubInspect.value = r || null
    const fileTitle = typeof r?.title === 'string' ? r.title.trim() : ''
    if (fileTitle && !pubTitleTouched) pubForm.title = fileTitle
  } catch (e) {
    if (pubInspectController !== controller || pubFile.value !== f) return
    if (isCanceled(e)) return
    if (syncLoginState(e)) {
      publishVisible.value = false
      return
    }
    pubInspectError.value = errText(e, t('inspectFailTip'))
  } finally {
    if (pubInspectController === controller) {
      pubInspecting.value = false
      pubInspectController = null
    }
  }
}

async function doPublish() {
  if (pubBusy.value) return
  if (!pubFile.value) {
    pubError.value = t('msgNeedFile')
    return
  }
  if (pubInspecting.value) {
    pubError.value = t('msgInspecting')
    return
  }
  // 必须已通过本地体检；提交时不再重复体检，直接走原有发布路径
  if (!pubInspect.value) {
    pubError.value = t('msgNeedInspect')
    return
  }
  const kind = pubForm.storageKind
  const url = pubForm.downloadUrl.trim()
  if (kind === 'EXTERNAL') {
    if (!url) {
      pubError.value = t('msgNeedUrl')
      return
    }
    if (!/^https?:\/\//i.test(url)) {
      pubError.value = t('msgBadUrl')
      return
    }
  }

  const fd = new FormData()
  fd.append('file', pubFile.value)
  // 官网 storageKind 缺省是 EXTERNAL，务必显式送
  fd.append('storageKind', kind)
  if (kind === 'EXTERNAL') fd.append('downloadUrl', url)
  // 服务端字段为空则不发送（官网只认长度 > 0 的部件）
  const title = pubForm.title.trim()
  const description = pubForm.description.trim()
  const source = pubForm.source.trim()
  if (title) fd.append('title', title)
  if (description) fd.append('description', description)
  if (source) fd.append('source', source)

  // 成功提示里的作品名：用户填的标题 → 题库文件内标题 → 文件名兜底
  const publishedTitle = title || (pubInspect.value?.title || '').trim()
    || pubFile.value.name.replace(/\.(tiku|json)$/i, '')
  pubError.value = ''
  pubBusy.value = true
  pubPct.value = 0
  pubTotal.value = 0
  pubServerBusy.value = false
  pubController = new AbortController()
  try {
    const r = await http.post('/center/publish', fd, {
      params: { center: getCenterUrl() },
      skipErrorMessage: true,
      timeout: UPLOAD_TIMEOUT_MS,
      signal: pubController.signal,
      onUploadProgress: (e) => {
        const totalBytes = Number(e?.total || 0)
        const loaded = Number(e?.loaded || 0)
        pubTotal.value = totalBytes
        if (totalBytes > 0) {
          // 上传完字节后中心还要解包/落盘，进度条不满格，避免误以为已完成
          pubPct.value = Math.min(99, Math.round((loaded / totalBytes) * 100))
          pubServerBusy.value = loaded >= totalBytes
        }
      }
    })
    pubPct.value = 100
    publishVisible.value = false
    lastPublished.value = { packageKey: r?.packageKey || '', title: publishedTitle }
    ElMessage.success(t('msgPublished'))
    page.value = 1
    await load()
  } catch (e) {
    if (isCanceled(e)) {
      pubError.value = ''
      if (!leaving) ElMessage.info(t('msgUploadCanceled'))
      return
    }
    if (syncLoginState(e)) {
      publishVisible.value = false
      return
    }
    pubError.value = errText(e, t('publishFail'))
  } finally {
    pubBusy.value = false
    pubController = null
  }
}

function cancelPublish() {
  pubController?.abort()
}

/* ============================================================
   编辑作品信息（description / source / downloadUrl；标题不可改）
   ============================================================ */
const editVisible = ref(false)
const editTarget = ref(null)
const editBusy = ref(false)
const editError = ref('')
const editForm = reactive({ description: '', source: '', downloadUrl: '' })
let editOrigin = { description: '', source: '', downloadUrl: '' }

function openEdit(w) {
  editTarget.value = w
  editForm.description = w.description || ''
  editForm.source = w.source || ''
  editForm.downloadUrl = w.downloadUrl || ''
  editOrigin = { ...editForm }
  editError.value = ''
  editVisible.value = true
}

function resetEdit() {
  editBusy.value = false
  editError.value = ''
  editTarget.value = null
}
function beforeEditClose(done) {
  if (editBusy.value) return
  done()
}

async function doEdit() {
  const w = editTarget.value
  if (!w || editBusy.value) return
  const description = editForm.description.trim()
  const source = editForm.source.trim()
  const downloadUrl = editForm.downloadUrl.trim()
  // 只送发生变化的字段（官网：未提供 = 不更新；提供空串 = 清空）
  const body = {}
  if (description !== (editOrigin.description || '').trim()) body.description = description
  if (source !== (editOrigin.source || '').trim()) body.source = source
  if (w.storageKind !== 'HOSTED' && downloadUrl !== (editOrigin.downloadUrl || '').trim()) body.downloadUrl = downloadUrl
  if (!Object.keys(body).length) {
    editError.value = t('msgNoChange')
    return
  }
  if (body.downloadUrl && !/^https?:\/\//i.test(body.downloadUrl)) {
    editError.value = t('msgBadUrl')
    return
  }
  editBusy.value = true
  editError.value = ''
  try {
    await http.put(
      `/center/packs/${encodeURIComponent(w.packageKey)}/${encodeURIComponent(w.version)}`,
      body,
      { params: { center: getCenterUrl() }, skipErrorMessage: true }
    )
    editVisible.value = false
    ElMessage.success(t('msgUpdated'))
    await load()
  } catch (e) {
    if (syncLoginState(e)) {
      editVisible.value = false
      return
    }
    editError.value = errText(e, t('updateFail'))
  } finally {
    editBusy.value = false
  }
}

/* ============================================================
   补传托管文件（仅 HOSTED 版本）
   ============================================================ */
const fileVisible = ref(false)
const fileInput = ref(null)
const fileTarget = ref(null)
const filePick = ref(null)
const fileBusy = ref(false)
const filePct = ref(0)
const fileTotal = ref(0)
const fileServerBusy = ref(false)
const fileError = ref('')
let fileController = null

const fileIndeterminate = computed(() => fileBusy.value && fileTotal.value <= 0)

function openFileDialog(w) {
  fileTarget.value = w
  filePick.value = null
  fileError.value = ''
  filePct.value = 0
  fileTotal.value = 0
  fileServerBusy.value = false
  fileVisible.value = true
}
function resetFileDialog() {
  fileTarget.value = null
  filePick.value = null
  fileError.value = ''
  filePct.value = 0
  fileTotal.value = 0
  fileServerBusy.value = false
  if (fileInput.value) fileInput.value.value = ''
}
function beforeFileClose(done) {
  if (fileBusy.value) return
  done()
}

function onPickUploadFile(e) {
  const f = e?.target?.files?.[0] || null
  if (e?.target) e.target.value = ''
  if (!f) return
  if (!/\.(tiku|json)$/i.test(f.name)) {
    fileError.value = t('msgBadFileType')
    return
  }
  if (f.size > 200 * 1024 * 1024) {
    fileError.value = t('msgFileTooBig')
    return
  }
  fileError.value = ''
  filePick.value = f
  filePct.value = 0
  fileServerBusy.value = false
}

async function doUploadFile() {
  const w = fileTarget.value
  if (!w || fileBusy.value) return
  if (!filePick.value) {
    fileError.value = t('msgNeedFile')
    return
  }
  const fd = new FormData()
  fd.append('file', filePick.value)
  fileError.value = ''
  fileBusy.value = true
  filePct.value = 0
  fileTotal.value = 0
  fileServerBusy.value = false
  fileController = new AbortController()
  try {
    await http.put(
      `/center/packs/${encodeURIComponent(w.packageKey)}/${encodeURIComponent(w.version)}/file`,
      fd,
      {
        params: { center: getCenterUrl() },
        skipErrorMessage: true,
        timeout: UPLOAD_TIMEOUT_MS,
        signal: fileController.signal,
        onUploadProgress: (e) => {
          const totalBytes = Number(e?.total || 0)
          const loaded = Number(e?.loaded || 0)
          fileTotal.value = totalBytes
          if (totalBytes > 0) {
            filePct.value = Math.min(99, Math.round((loaded / totalBytes) * 100))
            fileServerBusy.value = loaded >= totalBytes
          }
        }
      }
    )
    filePct.value = 100
    fileVisible.value = false
    ElMessage.success(t('msgFileUploaded'))
    await load()
  } catch (e) {
    if (isCanceled(e)) {
      fileError.value = ''
      if (!leaving) ElMessage.info(t('msgUploadCanceled'))
      return
    }
    if (syncLoginState(e)) {
      fileVisible.value = false
      return
    }
    fileError.value = errText(e, t('fileUploadFail'))
  } finally {
    fileBusy.value = false
    fileController = null
  }
}

function cancelFileUpload() {
  fileController?.abort()
}

/* ============================================================
   下架作品
   ============================================================ */
async function doRemove(w) {
  if (isBusy(w)) return
  try {
    await ElMessageBox.confirm(t('removeAsk', { title: w.title }), t('removeTitle'), {
      type: 'warning',
      confirmButtonText: t('remove'),
      cancelButtonText: t('cancel'),
      confirmButtonClass: 'el-button--danger'
    })
  } catch (e) {
    return // 用户取消
  }
  busyKey.value = `${w.packageKey}:remove`
  try {
    await http.delete(`/center/packs/${encodeURIComponent(w.packageKey)}`, {
      params: { center: getCenterUrl() },
      skipErrorMessage: true
    })
    ElMessage.success(t('msgRemoved'))
    // 当前页只剩这一条时回退一页，避免停在空页
    if (records.value.length === 1 && page.value > 1) page.value -= 1
    if (lastPublished.value?.packageKey === w.packageKey) lastPublished.value = null
    await load()
  } catch (e) {
    if (!syncLoginState(e)) toastError(e, t('removeFail'))
  } finally {
    busyKey.value = ''
  }
}

/* ---------- 生命周期 ---------- */
onMounted(async () => {
  await fetchMe()
  meLoading.value = false
  await load()
})

onBeforeUnmount(() => {
  // 离开页面时中断未完成的上传/体检，避免悬挂请求
  leaving = true
  pubController?.abort()
  pubInspectController?.abort()
  fileController?.abort()
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 26px;
}
.page-desc {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
}
.header-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.icon-btn {
  padding: 0 10px;
}

/* 发布成功提示条 */
.ok-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 18px;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--success-soft);
  border: 1px solid var(--success);
  color: var(--success);
  font-size: 13px;
}
.ok-text {
  flex: 1;
  min-width: 0;
}

.account-line {
  margin: 18px 0 0;
  font-size: 12.5px;
}
.account-name {
  color: var(--text-primary);
  font-weight: 500;
}

/* 状态/空态 */
.works-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 80px 24px;
  text-align: center;
  color: var(--text-muted);
}
.works-state h3 {
  margin-top: 8px;
  color: var(--text-primary);
}
.works-state p {
  max-width: 520px;
  line-height: 1.7;
}
.works-state .btn {
  margin-top: 6px;
}

/* 列表 */
.works-list {
  display: flex;
  flex-direction: column;
  margin-top: 14px;
}
.list-total {
  font-size: 12.5px;
  padding: 0 4px 8px;
}
.w-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 4px;
  border-top: 1px solid var(--border);
}
.w-item:last-child {
  border-bottom: 1px solid var(--border);
}
.w-main {
  min-width: 0;
  flex: 1;
}
.w-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.title-link {
  border: none;
  background: transparent;
  padding: 0;
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  transition: color var(--ease);
}
.title-link:hover {
  color: var(--accent-text);
}
.tag {
  font-size: 11px;
  font-weight: 400;
  letter-spacing: 0.04em;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid;
  flex-shrink: 0;
}
.tag.active {
  color: var(--success);
  border-color: var(--success);
  background: var(--success-soft);
}
.tag.removed {
  color: var(--text-muted);
  border-color: var(--border-strong);
  background: var(--bg-card-2);
}
.tag.hosted {
  color: var(--success);
  border-color: var(--success);
  background: var(--success-soft);
}
.tag.ext {
  color: var(--text-secondary);
  border-color: var(--border-strong);
  background: var(--bg-card-2);
}
.w-desc {
  margin-top: 5px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
}
.w-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.w-link {
  margin-top: 5px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 2px;
}
.w-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.removed-note {
  font-size: 12px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mono {
  font-family: Consolas, Menlo, monospace;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 26px;
}
.foot-tip {
  margin-top: 26px;
  font-size: 12.5px;
  line-height: 1.8;
}
.foot-link {
  margin-left: 4px;
}

.sk-row {
  height: 78px;
  border-radius: 10px;
  margin-bottom: 12px;
  background: var(--bg-hover);
}

/* 对话框 */
.form-error {
  margin: 0 0 12px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--danger);
}
.field-tip {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
.file-pick {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.file-pick label {
  position: relative;
}
/* 视觉隐藏但保留可点性：label 点击即转发到 input（WebView2 下无需 JS 触发） */
.file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  overflow: hidden;
}
.file-name {
  font-size: 12.5px;
  color: var(--text-primary);
  max-width: 320px;
}
.file-hint {
  font-size: 12px;
}
/* 发布前本地体检：检查中 / 未通过 / 结果预览 */
.inspect-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 0 0;
  font-size: 12.5px;
}
.inspect-spinner {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  border: 2px solid var(--border-strong);
  border-top-color: var(--accent-text);
  border-radius: 50%;
  animation: inspect-spin 0.8s linear infinite;
}
@keyframes inspect-spin {
  to {
    transform: rotate(360deg);
  }
}
.inspect-fail {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--danger);
}
.inspect-detail {
  margin-top: 3px;
  font-weight: 400;
}
.inspect-box {
  margin-top: 8px;
  padding: 9px 11px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card-2);
  font-size: 12.5px;
}
.inspect-head {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 4px;
  color: var(--success);
}
.inspect-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 3px 0 0;
}
.inspect-label {
  width: 62px;
  flex-shrink: 0;
  color: var(--text-muted);
}
.inspect-value {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
}
.progress-box {
  margin-top: 14px;
}
.progress-tip {
  margin: 8px 0 0;
  font-size: 12px;
}
.edit-work {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.file-target {
  margin: 0;
  font-size: 13.5px;
  color: var(--text-primary);
}
</style>
