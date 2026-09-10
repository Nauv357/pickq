<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('pageTitle') }}</h1>
        <p class="page-desc">{{ t('pageDesc') }}</p>
      </div>
      <div class="header-actions">
        <button class="btn btn-secondary" :disabled="refreshing" @click="refreshAll">
          <TikuIcon name="refresh" :size="15" />
          {{ refreshing ? t('refreshing') : t('refresh') }}
        </button>
        <button class="btn btn-secondary" @click="openPlaza">
          <TikuIcon name="link" :size="15" />
          {{ t('browseWeb') }}
        </button>
        <button class="btn btn-primary" @click="openPublishManual">
          <TikuIcon name="upload" :size="15" />
          {{ t('publishManual') }}
        </button>
      </div>
    </header>

    <!-- 登录/离线状态：三区共用（离线也能打开本页并看到这段说明） -->
    <div class="status-bar" :class="currentUser ? 'ok' : 'warn'">
      <TikuIcon :name="currentUser ? 'check' : 'info'" :size="14" />
      <span class="status-text">
        {{ currentUser ? t('statusLoggedIn', { name: accountName }) : t('statusGuest') }}
      </span>
      <RouterLink v-if="!currentUser" class="status-link" to="/discover">{{ t('statusGuestLink') }}</RouterLink>
    </div>

    <!-- 刚发布成功：给一个去官网查看的入口（弹窗已关闭） -->
    <div v-if="lastPublished" class="ok-banner">
      <TikuIcon name="check" :size="15" />
      <span class="ok-text">{{ t('publishedTip', { title: lastPublished.title }) }}</span>
      <button v-if="lastPublished.packageKey" class="btn btn-ghost btn-sm" @click="openPack(lastPublished)">{{ t('openOnWeb') }}</button>
      <button class="btn btn-ghost btn-sm icon-btn" :title="t('close')" @click="lastPublished = null">
        <TikuIcon name="x" :size="14" />
      </button>
    </div>

    <!-- ============ 三区：本地题库 / 已导出文件 / 已发布作品 ============ -->
    <el-tabs v-model="zone" class="zones" @tab-change="onZoneChange">
      <!-- ① 本地题库（离线可用） -->
      <el-tab-pane :label="t('zoneBanks', { n: bankTotal })" name="banks">
        <p class="zone-lead text-muted">{{ t('banksLead') }}</p>

        <div v-if="banksLoading && !banks.length" class="zone-list">
          <div v-for="n in 3" :key="n" class="sk-row tiku-skeleton"></div>
        </div>

        <div v-else-if="banksError" class="zone-state">
          <TikuIcon name="info" :size="38" />
          <h3>{{ t('banksLoadFail') }}</h3>
          <p class="text-secondary">{{ banksError }}</p>
          <button class="btn btn-secondary" @click="loadBanks">{{ t('retry') }}</button>
        </div>

        <div v-else-if="!banks.length" class="zone-state">
          <TikuIcon name="package" :size="38" />
          <h3>{{ t('banksEmptyTitle') }}</h3>
          <p class="text-secondary">{{ t('banksEmptyDesc') }}</p>
          <RouterLink class="btn btn-primary" to="/banks">{{ t('gotoBanks') }}</RouterLink>
        </div>

        <div v-else class="zone-list">
          <p class="list-total text-muted">{{ t('banksTotal', { n: bankTotal }) }}</p>
          <div v-for="b in banks" :key="b.id" class="row-item">
            <div class="row-main">
              <p class="row-title">
                <button class="title-link" :title="t('gotoBankDetail')" @click="gotoBank(b)">{{ b.name }}</button>
                <span v-if="b.version" class="tag plain">{{ t('versionN', { v: b.version }) }}</span>
                <!-- 发布状态来自本机导出记录（免登录即可读），无条件判断 -->
                <span v-if="bankPublishedVersion(b)" class="tag active">{{ t('publishedAs', { v: bankPublishedVersion(b) }) }}</span>
                <span v-else class="tag never">{{ t('neverPublished') }}</span>
              </p>
              <p class="row-meta">
                {{ t('questionsN', { n: bankQuestionText(b) }) }}
                · {{ t('createdOn', { d: dateText(b.createdAt) }) }}
              </p>
              <p v-if="latestExportOfBank(b)" class="row-sub text-muted">
                {{ t('exportedRecord', { name: latestExportOfBank(b).fileName }) }}
              </p>
            </div>
            <div class="row-actions">
              <button class="btn btn-secondary btn-sm" @click="openPublishFromBank(b)">{{ t('publish') }}…</button>
              <button
                class="btn btn-secondary btn-sm"
                :disabled="!!exportingBankId"
                @click="quickExport(b)"
              >{{ exportingBankId === b.id ? t('exporting') : t('exportFile') + '…' }}</button>
              <button
                v-if="latestExportOfBank(b)"
                class="btn btn-ghost btn-sm"
                :title="t('openFolder')"
                @click="openRecordFolder(latestExportOfBank(b))"
              >
                <TikuIcon name="file" :size="14" />
                {{ t('openFolder') }}
              </button>
            </div>
          </div>
          <div v-if="bankTotal > bankSize" class="pager">
            <el-pagination
              background
              layout="prev, pager, next, total"
              :total="bankTotal"
              :page-size="bankSize"
              :current-page="bankPage"
              @current-change="onBankPage"
            />
          </div>
        </div>
      </el-tab-pane>

      <!-- ② 已导出文件（完全在本机后端，免登录 / 离线可用） -->
      <el-tab-pane :label="t('zoneExports', { n: exportsCount })" name="exports">
        <p class="zone-lead text-muted">{{ t('exportsLead') }}</p>

        <div v-if="exportsLoading && !exports.length" class="zone-list">
          <div v-for="n in 3" :key="n" class="sk-row tiku-skeleton"></div>
        </div>

        <div v-else-if="exportsError" class="zone-state">
          <TikuIcon name="info" :size="38" />
          <h3>{{ t('exportsLoadFail') }}</h3>
          <p class="text-secondary">{{ exportsError }}</p>
          <button class="btn btn-secondary" @click="loadExports">{{ t('retry') }}</button>
        </div>

        <div v-else-if="!exports.length" class="zone-state">
          <TikuIcon name="file" :size="38" />
          <h3>{{ t('exportsEmptyTitle') }}</h3>
          <p class="text-secondary">{{ t('exportsEmptyDesc') }}</p>
          <button class="btn btn-secondary" @click="zone = 'banks'">{{ t('gotoLocalBanks') }}</button>
        </div>

        <div v-else class="zone-list">
          <p class="list-total text-muted">{{ t('exportsTotal', { n: exportsCount }) }}</p>
          <div
            v-for="r in expPageItems"
            :key="r.id"
            class="row-item"
            :class="{ missing: r.fileExists === false }"
          >
            <div class="row-main">
              <p class="row-title">
                <span class="file-title ellipsis" :title="r.filePath">{{ r.fileName }}</span>
                <span v-if="r.published" class="tag active">{{ t('publishedAs', { v: r.publishedVersion || r.version }) }}</span>
                <span v-else class="tag never">{{ t('notPublishedYet') }}</span>
              </p>
              <p class="row-meta">
                {{ t('fromBank', { name: r.bankName || t('unknownBank') }) }}
                · {{ t('versionN', { v: r.version }) }}
                · {{ formatBytes(r.sizeBytes) }}
                · {{ t('exportedOn', { d: dateText(r.createdAt) }) }}
              </p>
              <p v-if="r.fileExists === false" class="row-warn">
                <TikuIcon name="info" :size="12" />
                {{ t('fileMissing') }}
              </p>
              <p v-else class="row-sub text-muted mono ellipsis" :title="r.filePath">{{ r.filePath }}</p>
            </div>
            <div class="row-actions">
              <button class="btn btn-secondary btn-sm" @click="openRecordFolder(r)">{{ t('openFolder') }}</button>
              <button
                class="btn btn-secondary btn-sm"
                :disabled="r.fileExists === false || busyRecordId === r.id"
                :title="r.fileExists === false ? t('publishMissingTip') : ''"
                @click="openPublishFromExport(r)"
              >{{ t('publish') }}…</button>
              <button
                class="btn btn-danger btn-sm"
                :disabled="busyRecordId === r.id"
                @click="removeExport(r)"
              >{{ t('removeRecord') }}</button>
            </div>
          </div>
          <div v-if="exportsCount > expSize" class="pager">
            <el-pagination
              background
              layout="prev, pager, next, total"
              :total="exportsCount"
              :page-size="expSize"
              :current-page="expPage"
              @current-change="onExpPage"
            />
          </div>
        </div>
      </el-tab-pane>

      <!-- ③ 已发布作品（需联网 + 登录；保留原有实现） -->
      <el-tab-pane :label="t('zoneWorks', { n: total })" name="works" lazy>
        <p class="zone-lead text-muted">{{ t('worksLead') }}</p>

        <!-- 登录态：恢复中 -->
        <div v-if="meLoading" class="zone-list">
          <div class="sk-row tiku-skeleton"></div>
          <div class="sk-row tiku-skeleton"></div>
        </div>

        <!-- 未登录：引导去「发现题库」页登录 -->
        <div v-else-if="!currentUser" class="zone-state">
          <TikuIcon name="info" :size="38" />
          <h3>{{ t('zoneLoginTitle') }}</h3>
          <p class="text-secondary">{{ t('zoneLoginDesc') }}</p>
          <RouterLink class="btn btn-primary" to="/discover">{{ t('goDiscover') }}</RouterLink>
        </div>

        <!-- 加载骨架 -->
        <div v-else-if="loading && !records.length" class="zone-list">
          <div v-for="n in 3" :key="n" class="sk-row tiku-skeleton"></div>
        </div>

        <!-- 加载失败（含会话失效：已回到未登录态，交由上方分支引导） -->
        <div v-else-if="listError" class="zone-state">
          <TikuIcon name="info" :size="38" />
          <h3>{{ t('loadFail') }}</h3>
          <p class="text-secondary">{{ listError }}</p>
          <button class="btn btn-secondary" @click="loadWorks">{{ t('retry') }}</button>
        </div>

        <!-- 空态 -->
        <div v-else-if="!records.length" class="zone-state">
          <TikuIcon name="package" :size="38" />
          <h3>{{ t('emptyTitle') }}</h3>
          <p class="text-secondary">{{ t('emptyDesc') }}</p>
          <button class="btn btn-primary" @click="openPublishManual">{{ t('publishManual') }}</button>
        </div>

        <!-- 作品列表 -->
        <div v-else class="zone-list">
          <p class="list-total text-muted">{{ t('totalN', { n: total }) }}</p>
          <div v-for="w in records" :key="w.packageKey" class="row-item">
            <div class="row-main">
              <p class="row-title">
                <button class="title-link" :title="t('viewPage')" @click="openPack(w)">{{ w.title }}</button>
                <span class="tag" :class="w.status === 'REMOVED' ? 'removed' : 'active'">{{ statusText(w.status) }}</span>
                <span v-if="w.storageKind === 'HOSTED'" class="tag hosted">{{ t('tagHosted') }}</span>
                <span v-else-if="w.storageKind === 'EXTERNAL'" class="tag ext">{{ t('tagExternal') }}</span>
              </p>
              <p class="row-desc">{{ w.description || t('noDesc') }}</p>
              <p class="row-meta">
                {{ t('versionN', { v: w.version }) }}
                <template v-if="Number(w.versionCount || 0) > 1"> · {{ t('versionsN', { n: w.versionCount }) }}</template>
                · {{ t('questionsN', { n: w.questionsCount ?? '—' }) }}
                · {{ t('favoritesN', { n: w.favoritesCount ?? 0 }) }}
                · {{ t('downloadClicksN', { n: w.downloadClicks ?? 0 }) }}
                <template v-if="w.fileSizeBytes"> · {{ formatBytes(w.fileSizeBytes) }}</template>
                · {{ t('createdOn', { d: dateText(w.createdAt) }) }}
              </p>
              <p v-if="w.storageKind === 'EXTERNAL' && w.downloadUrl" class="row-link text-muted">
                {{ t('linkPrefix') }}<span class="mono ellipsis">{{ w.downloadUrl }}</span>
              </p>
            </div>
            <div class="row-actions">
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
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 底部说明 -->
    <p class="foot-tip text-muted">
      {{ t('exportTip') }}
      <RouterLink class="foot-link" to="/banks">{{ t('gotoBanks') }}</RouterLink>
    </p>

    <!-- ============ 发布作品（本地题库 / 已导出文件 / 手动选文件 三入口共用） ============ -->
    <el-dialog
      v-model="publishVisible"
      :title="pubDialogTitle"
      width="min(94vw, 580px)"
      align-center
      :close-on-click-modal="false"
      :close-on-press-escape="!pubBusy"
      :show-close="!pubBusy"
      :before-close="beforePublishClose"
      @closed="afterPublishClosed"
    >
      <p v-if="pubError" class="form-error">{{ pubError }}</p>

      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('pubOriginLabel')">
          <p class="origin-line">{{ pubOriginText }}</p>
        </el-form-item>

        <!-- 手动选文件：选文件 + 本地体检（后端只解析元数据、不转发）：不合格就不必跨境传 200MB -->
        <el-form-item v-if="pubIsManual" :label="t('pubFileLabel')">
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
          <p class="field-tip text-muted">{{ t('pubManualHint') }}</p>
        </el-form-item>

        <!-- 从本地题库发起：导出位置（可选；桌面版可弹系统「选择文件夹」） -->
        <el-form-item v-if="pubOrigin === 'bank'" :label="t('pubDirLabel')">
          <div class="dir-pick">
            <button
              v-if="pubCanPickDir"
              class="btn btn-secondary btn-sm"
              :disabled="pubBusy || pubPicking"
              @click="chooseDir"
            >
              <TikuIcon name="file" :size="14" />
              {{ pubPicking ? t('pubDirPicking') : t('pubDirChoose') }}
            </button>
            <span class="dir-value mono ellipsis" :title="pubEffectiveDir">
              {{ pubDir ? pubDir : t('pubDirDefaultN', { path: pubEffectiveDir || t('pubDirUnknown') }) }}
            </span>
            <button v-if="pubDir" class="btn btn-ghost btn-sm" :disabled="pubBusy" @click="pubDir = ''">{{ t('pubDirReset') }}</button>
          </div>
          <p class="field-tip text-muted">
            {{ pubCanPickDir ? t('pubDirHint') : t('pubDirBrowser') }}
          </p>
        </el-form-item>

        <el-form-item>
          <template #label>{{ t('pubVersionLabel') }}</template>
          <el-input
            v-if="pubVersionEditable"
            v-model="pubForm.version"
            maxlength="20"
            :disabled="pubBusy"
            :placeholder="t('pubVersionPh')"
          />
          <p v-else class="version-ro mono">{{ pubForm.version || '—' }}</p>
          <p class="field-tip text-muted">{{ pubVersionHint }}</p>
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

      <!-- 进度：手动路径有真实上传进度；从磁盘发布无进度事件 → 不确定态 + 阶段文案 -->
      <div v-if="pubBusy" class="progress-box">
        <el-progress
          :percentage="pubPct"
          :stroke-width="10"
          :indeterminate="pubIndeterminate"
          :status="pubServerBusy ? 'success' : ''"
        />
        <p class="progress-tip text-muted">{{ pubStageText }}</p>
        <p v-if="!pubIsManual" class="progress-tip text-muted">{{ t('pubDiskTip') }}</p>
      </div>

      <template #footer>
        <button class="btn btn-ghost" :disabled="pubBusy" @click="publishVisible = false">{{ t('cancel') }}</button>
        <button v-if="pubBusy && pubIsManual" class="btn btn-danger" @click="cancelPublish">{{ t('cancelUpload') }}</button>
        <button
          v-else
          class="btn btn-primary"
          :disabled="!pubReady"
          :title="pubReady ? '' : pubBlockedTip"
          @click="doPublish"
        >{{ pubBusy ? t('publishing') : t('doPublish') }}</button>
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
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import TikuIcon from '../components/TikuIcon.vue'
import http from '../api/http'
import { centerPacksUrl, getCenterUrl } from '../utils/center'
import { openExternal, openLocalFolder, isDesktop } from '../utils/external'
import { pickDirectory } from '../utils/files'

const { t } = useI18n({
  messages: {
    'zh-CN': {
      pageTitle: '我的作品',
      pageDesc: '本地发布中心：管理本地题库、导出文件与已发布作品（查看与导出不需要登录，可离线使用）',
      publishManual: '手动选择文件发布',
      refresh: '刷新',
      browseWeb: '在官网浏览',
      close: '关闭',
      refreshing: '刷新中…',
      /* ---- 登录 / 离线状态 ---- */
      statusLoggedIn: '当前广场账号：{name}（发布、编辑、下架需要联网）',
      statusGuest: '未登录：可查看本地题库、导出题库文件、查看已导出记录并打开所在文件夹；发布作品需要登录题库广场账号',
      statusGuestLink: '去「发现题库」页登录',
      goDiscover: '去「发现题库」页登录',
      zoneLoginTitle: '登录后可发布作品',
      zoneLoginDesc: '未登录时仍可查看本地题库、导出文件与已导出记录；登录后可发布、编辑与下架作品。',
      needLoginTitle: '需要先登录',
      needLoginAsk: '发布作品需要登录题库广场账号，是否现在去「发现题库」页登录？',
      /* ---- 三区 ---- */
      zoneBanks: '本地题库（{n}）',
      zoneExports: '已导出文件（{n}）',
      zoneWorks: '已发布作品（{n}）',
      banksLead: '离线可用：导出题库文件、打开所在文件夹、查看已导出记录都不需要登录；「发布…」需要登录并联网。',
      banksTotal: '共 {n} 个本地题库',
      banksLoadFail: '加载本地题库失败',
      banksEmptyTitle: '还没有本地题库',
      banksEmptyDesc: '先在「题库」页创建或导入题库，回到这里就能导出文件并发布。',
      gotoLocalBanks: '去本地题库区',
      gotoBankDetail: '打开题库详情',
      publishedAs: '已发布 v{v}',
      neverPublished: '未发布过',
      exportedRecord: '已导出：{name}',
      publish: '发布',
      exportFile: '导出文件',
      exporting: '导出中…',
      openFolder: '打开所在文件夹',
      unknownBank: '未知题库',
      exportsLead: '离线可用：导出记录保存在本机（不上传）；可打开所在文件夹、直接发布或移除记录。',
      exportsTotal: '共 {n} 个导出文件',
      exportsLoadFail: '加载导出记录失败',
      exportsEmptyTitle: '还没有导出过题库文件',
      exportsEmptyDesc: '在「本地题库」区点「导出文件…」即可生成 .tiku 文件，导出后可在这里发布。',
      fromBank: '来源：{name}',
      exportedOn: '导出于 {d}',
      notPublishedYet: '未发布',
      fileMissing: '文件已被移动或删除',
      publishMissingTip: '文件已被移动或删除，无法发布；请重新导出后再试',
      removeRecord: '移除记录',
      expRemoveTitle: '移除导出记录',
      expRemoveAsk: '将移除「{name}」的导出记录。是否同时删除磁盘上的题库文件？',
      expRemoveWithFile: '同时删除文件',
      expRemoveKeepFile: '只移除记录',
      msgRecordRemoved: '已移除记录',
      msgRecordRemovedWithFile: '已移除记录并删除文件',
      removeRecordFail: '移除失败，请稍后重试',
      openFolderFail: '无法打开所在文件夹',
      openFolderDesktopOnly: '仅桌面版支持打开本地文件夹',
      noPath: '该记录没有文件路径',
      /* ---- ① 的即时导出 ---- */
      msgExported: '导出完成',
      msgExportedTo: '已导出到：{path}',
      exportFail: '导出失败，请稍后重试',
      /* ---- 发布对话框 ---- */
      pubTitleBank: '发布「{name}」',
      pubTitleExport: '发布已导出文件「{name}」',
      pubTitleManual: '手动选择文件发布',
      pubOriginLabel: '发布来源',
      pubFromBank: '本地题库：{name}',
      pubFromExport: '已导出文件：{name}',
      pubFromManual: '手动选择的题库文件',
      pubOriginExported: '（发布时会先导出为 .tiku 文件，再直接上传）',
      pubFileLabel: '题库文件',
      pubChooseFile: '选择题库文件',
      pubChooseAgain: '重新选择文件',
      pubFileHint: '支持 .tiku 与 .json 两种题库文件，单个文件不超过 200MB',
      pubFileChosen: '已选择：{name}（{size}）',
      pubManualHint: '手动选文件发布：文件会直接从本机上传到广场（可看进度、可取消）',
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
      pubDirLabel: '导出位置',
      pubDirChoose: '选择文件夹…',
      pubDirPicking: '选择中…',
      pubDirReset: '用默认目录',
      pubDirUnknown: '默认目录',
      pubDirDefaultN: '默认目录：{path}',
      pubDirHint: '不选则导出到默认目录；选定的文件夹会记住，下次默认打开。',
      pubDirBrowser: '浏览器版无法选择本地文件夹，导出位置由后端默认目录决定。',
      pickDirTitle: '选择题库文件的导出文件夹',
      pickDirFail: '选择文件夹失败',
      pubVersionLabel: '版本号',
      pubVersionPh: '如 1.0.0',
      pubVersionHintFree: '版本号会写进导出的题库文件，也是广场上展示的版本',
      pubVersionHintSuggest: '建议使用 {v}（按上次发布自动递增）',
      pubVersionHintLast: '上次发布版本 v{v}，本次将作为新版本发布',
      pubVersionLocked: '该文件已导出，版本由文件内容决定，不能在这里修改',
      pubVersionFromFile: '版本取自所选题库文件',
      pubTitleLabel: '标题（选填）',
      pubTitlePh: '默认使用题库名 / 题库文件内的标题',
      pubTitleHint: '标题默认取题库名或文件内标题，可在此修改；留空则沿用文件内的标题',
      pubBlockedTip: '请填好必填项（题目来源、版本、外链等）后再发布',
      pubBlockedManual: '请选择题库文件并等待检查通过后再发布',
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
      pubStageExport: '正在导出题库文件…',
      pubStagePublish: '正在上传到题库广场…',
      pubStageMark: '正在登记发布结果…',
      pubDiskTip: '文件直接从本机上传，大文件可能需要几分钟，请不要关闭窗口。',
      doPublish: '发布',
      publishing: '发布中…',
      cancel: '取消',
      cancelUpload: '取消上传',
      msgNeedFile: '请先选择题库文件（.tiku 或 .json）',
      msgNeedBank: '请选择要发布的本地题库',
      msgNeedRecord: '请选择要发布的导出文件',
      msgNeedVersion: '请填写版本号',
      msgBadFileType: '只支持 .tiku 与 .json 文件',
      msgFileTooBig: '文件超过 200MB 上限',
      msgNeedUrl: '作者外链方式需要填写下载链接',
      msgBadUrl: '下载链接需为 http(s) 直链',
      msgPublished: '已发布，可在广场查看',
      publishedTip: '「{title}」已发布',
      msgUploadCanceled: '已取消上传',
      publishFail: '发布失败，请稍后重试',
      exportNoPath: '导出成功但没有返回文件路径',
      markPublishedFail: '作品已发布，但本地导出记录未更新（刷新后可见）',
      msgContinueFail: '发布中断，请检查网络后重试',
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
      /* ---- ③ 已发布作品 ---- */
      worksLead: '需要联网与登录：这里管理已经发布到题库广场的作品。',
      totalN: '共 {n} 个作品',
      emptyTitle: '还没有发布过作品',
      emptyDesc: '先在「本地题库」区导出 .tiku 文件，再回到这里发布，或直接手动选择文件发布。',
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
      /* ---- 下架 ---- */
      removeTitle: '下架作品',
      removeAsk: '将下架「{title}」：广场上不再展示该作品（托管文件会一并删除）。确定吗？',
      msgRemoved: '已下架',
      removeFail: '下架失败，请稍后重试',
      /* ---- 通用 ---- */
      actionNeedLogin: '登录已失效，请重新登录题库广场账号',
      /* ---- 底部说明 ---- */
      exportTip: '提示：查看本地题库、导出文件与查看已导出记录不需要登录；发布、编辑、下架需要登录题库广场账号并联网。',
      gotoBanks: '去题库'
    },
    'en-US': {
      pageTitle: 'My works',
      pageDesc: 'Local publishing center: manage your local banks, exported files and published works (browsing and exporting need no login and work offline)',
      publishManual: 'Publish a file manually',
      refresh: 'Refresh',
      browseWeb: 'Browse on website',
      close: 'Close',
      refreshing: 'Refreshing…',
      /* ---- login / offline state ---- */
      statusLoggedIn: 'Plaza account: {name} (publishing, editing and taking down need a connection)',
      statusGuest: 'Not logged in: you can browse local banks, export bank files, see exported records and open their folder; publishing needs a plaza account',
      statusGuestLink: 'Log in on the Discover page',
      goDiscover: 'Log in on the Discover page',
      zoneLoginTitle: 'Log in to publish works',
      zoneLoginDesc: 'You can still browse local banks, export files and see exported records while logged out; log in to publish, edit and take works down.',
      needLoginTitle: 'Log in first',
      needLoginAsk: 'Publishing requires a plaza account. Go to the Discover page to log in now?',
      /* ---- the three zones ---- */
      zoneBanks: 'Local banks ({n})',
      zoneExports: 'Exported files ({n})',
      zoneWorks: 'Published works ({n})',
      banksLead: 'Works offline: exporting bank files, opening their folder and viewing exported records need no login; “Publish…” needs a login and a connection.',
      banksTotal: '{n} local banks',
      banksLoadFail: 'Failed to load local banks',
      banksEmptyTitle: 'No local banks yet',
      banksEmptyDesc: 'Create or import a bank on the Banks page first; then come back here to export and publish it.',
      gotoLocalBanks: 'Go to Local banks',
      gotoBankDetail: 'Open the bank detail page',
      publishedAs: 'Published v{v}',
      neverPublished: 'Not published',
      exportedRecord: 'Exported: {name}',
      publish: 'Publish',
      exportFile: 'Export file',
      exporting: 'Exporting…',
      openFolder: 'Open containing folder',
      unknownBank: 'Unknown bank',
      exportsLead: 'Works offline: export records are kept on this device (never uploaded); open their folder, publish them, or remove the record.',
      exportsTotal: '{n} exported files',
      exportsLoadFail: 'Failed to load exported files',
      exportsEmptyTitle: 'No exported bank files yet',
      exportsEmptyDesc: 'Click “Export file…” in the Local banks zone to create a .tiku file, then publish it here.',
      fromBank: 'From: {name}',
      exportedOn: 'Exported {d}',
      notPublishedYet: 'Not published',
      fileMissing: 'The file has been moved or deleted',
      publishMissingTip: 'The file has been moved or deleted, so it cannot be published — export it again first',
      removeRecord: 'Remove record',
      expRemoveTitle: 'Remove export record',
      expRemoveAsk: 'This removes the export record of “{name}”. Delete the bank file on disk as well?',
      expRemoveWithFile: 'Delete the file too',
      expRemoveKeepFile: 'Remove the record only',
      msgRecordRemoved: 'Record removed',
      msgRecordRemovedWithFile: 'Record removed and file deleted',
      removeRecordFail: 'Failed to remove, please try again later',
      openFolderFail: 'Could not open the containing folder',
      openFolderDesktopOnly: 'Opening local folders is only supported in the desktop app',
      noPath: 'This record has no file path',
      /* ---- quick export from zone 1 ---- */
      msgExported: 'Export finished',
      msgExportedTo: 'Exported to: {path}',
      exportFail: 'Export failed, please try again later',
      /* ---- publish dialog ---- */
      pubTitleBank: 'Publish “{name}”',
      pubTitleExport: 'Publish the exported file “{name}”',
      pubTitleManual: 'Publish a file manually',
      pubOriginLabel: 'Source',
      pubFromBank: 'Local bank: {name}',
      pubFromExport: 'Exported file: {name}',
      pubFromManual: 'A bank file you choose',
      pubOriginExported: ' (the file is exported first, then uploaded directly)',
      pubFileLabel: 'Bank file',
      pubChooseFile: 'Choose a bank file',
      pubChooseAgain: 'Choose another file',
      pubFileHint: 'Both .tiku and .json bank files are supported, up to 200MB',
      pubFileChosen: 'Selected: {name} ({size})',
      pubManualHint: 'Manual publish: the file is uploaded straight from this device (with progress and cancel)',
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
      pubDirLabel: 'Export location',
      pubDirChoose: 'Choose folder…',
      pubDirPicking: 'Choosing…',
      pubDirReset: 'Use the default folder',
      pubDirUnknown: 'Default folder',
      pubDirDefaultN: 'Default folder: {path}',
      pubDirHint: 'Leave unchanged to use the default folder; the folder you pick is remembered and reopened next time.',
      pubDirBrowser: 'The browser build cannot pick a local folder — the backend default folder is used.',
      pickDirTitle: 'Choose the folder to export the bank file into',
      pickDirFail: 'Could not choose a folder',
      pubVersionLabel: 'Version',
      pubVersionPh: 'e.g. 1.0.0',
      pubVersionHintFree: 'The version is written into the exported bank file and shown in the plaza',
      pubVersionHintSuggest: 'Suggested: {v} (incremented from your last publish)',
      pubVersionHintLast: 'Your previous publish was v{v} — this one will be published as a new version',
      pubVersionLocked: 'This file is already exported, so its version comes from the file and cannot be changed here',
      pubVersionFromFile: 'The version comes from the chosen bank file',
      pubTitleLabel: 'Title (optional)',
      pubTitlePh: 'Uses the bank name / the title inside the file by default',
      pubTitleHint: 'The title defaults to the bank name or the title inside the file and can be changed here; leave empty to keep the one inside the file',
      pubBlockedTip: 'Fill in the required fields (version, external link, …) before publishing',
      pubBlockedManual: 'Choose a bank file and wait for the check to pass before publishing',
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
      pubStageExport: 'Exporting the bank file…',
      pubStagePublish: 'Uploading to the plaza…',
      pubStageMark: 'Recording the publish result…',
      pubDiskTip: 'The file is uploaded straight from this device; large files can take a few minutes, please keep this window open.',
      doPublish: 'Publish',
      publishing: 'Publishing…',
      cancel: 'Cancel',
      cancelUpload: 'Cancel upload',
      msgNeedFile: 'Choose a bank file first (.tiku or .json)',
      msgNeedBank: 'Choose the local bank to publish',
      msgNeedRecord: 'Choose the exported file to publish',
      msgNeedVersion: 'Please enter a version',
      msgBadFileType: 'Only .tiku and .json files are supported',
      msgFileTooBig: 'The file exceeds the 200MB limit',
      msgNeedUrl: 'A download link is required for the “your own link” option',
      msgBadUrl: 'The download link must be a direct http(s) URL',
      msgPublished: 'Published — you can now see it in the plaza',
      publishedTip: '“{title}” is now published',
      msgUploadCanceled: 'Upload canceled',
      publishFail: 'Publish failed, please try again later',
      exportNoPath: 'The export succeeded but returned no file path',
      markPublishedFail: 'The work is published, but the local export record was not updated (refresh to see it)',
      msgContinueFail: 'Publishing was interrupted — check your connection and try again',
      /* ---- edit dialog ---- */
      editTitle: 'Edit work info',
      editWorkLabel: 'Work',
      editVersion: 'Version {v}',
      editTitleLocked: 'The title comes from the bank file and cannot be modified',
      editHostedNoUrl: 'Hosted works are served by PickQ, so an external link cannot be set here',
      editHostedFileNote: 'The file for this work is already uploaded. To replace it, take the work down and publish it again.',
      editClearHint: 'Clear the field and save to remove it',
      msgNoChange: 'Nothing was changed',
      msgUpdated: 'Updated',
      updateFail: 'Update failed, please try again later',
      /* ---- upload file dialog ---- */
      fileTitle: 'Upload the hosted file',
      filePickHint: 'Choose the .tiku or .json file for this version',
      fileHint: 'The packageKey and version inside the file must match this version',
      doUpload: 'Start upload',
      msgFileUploaded: 'File uploaded',
      fileUploadFail: 'Upload failed, please try again later',
      /* ---- published works ---- */
      worksLead: 'Needs a connection and a login: this is where you manage works already published to the plaza.',
      totalN: '{n} works',
      emptyTitle: 'You have not published anything yet',
      emptyDesc: 'Export a .tiku file in the Local banks zone and publish it here, or publish a file manually.',
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
      removeTitle: 'Take the work down',
      removeAsk: 'This takes “{title}” down: it will no longer be shown in the plaza (its hosted file is deleted too). Proceed?',
      msgRemoved: 'Taken down',
      removeFail: 'Failed to take down, please try again later',
      actionNeedLogin: 'Your session expired — please log in to the plaza account again',
      exportTip: 'Tip: browsing local banks, exporting files and viewing exported records need no login; publishing, editing and taking works down need a plaza login and a connection.',
      gotoBanks: 'Go to Banks'
    }
  }
})

const router = useRouter()

/* 上传类请求不能沿用 http 实例的 15s 超时（大文件可能上百 MB），单独放宽到 30 分钟 */
const UPLOAD_TIMEOUT_MS = 30 * 60 * 1000
/* 体检请求（本地后端只解析元数据）：定 2 分钟超时，避免请求悬挂 */
const INSPECT_TIMEOUT_MS = 2 * 60 * 1000
/* 本地导出（后端打包 zip 写盘）：大题库要几十秒，放宽到 10 分钟 */
const EXPORT_TIMEOUT_MS = 10 * 60 * 1000
/* 从磁盘直接发布（后端读本地文件 → 上传中心）：无进度事件，超时同上传口径 */
const PUBLISH_FROM_PATH_TIMEOUT_MS = 30 * 60 * 1000

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

/* ---------- 通用小工具 ---------- */
function dateText(s) {
  return s ? String(s).replace('T', ' ').slice(0, 10) : '—'
}
function formatBytes(n) {
  const b = Number(n || 0)
  if (!b) return '—'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(1)} MB`
}
function stripExt(name) {
  return String(name || '').replace(/\.(tiku|json)$/i, '')
}
/** 取文件路径的所在目录（Windows 反斜杠与正斜杠都认） */
function dirOf(p) {
  const s = String(p || '')
  const i = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'))
  return i > 0 ? s.slice(0, i) : ''
}
/** 后端可读 message（含 Rust 命令直接 reject 的字符串） */
function errText(e, fallback) {
  if (typeof e === 'string' && e.trim()) return e
  const m = e?.response?.data?.message || e?.message
  return typeof m === 'string' && m.trim() ? m : fallback
}
/** http 拦截器对「HTTP 200 + code!==200」已统一弹过提示，避免二次弹窗 */
function alreadyToasted(e) {
  return !!e && !e.response && !e.config && !e.request && typeof e !== 'string'
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
  const msg = typeof e === 'string' ? e : errText(e, '')
  if (/登录|login/i.test(msg)) {
    currentUser.value = null
    ElMessage.warning(t('actionNeedLogin'))
    return true
  }
  return false
}
/** 发布类操作需要登录：未登录则提示并可一键跳到「发现题库」页登录 */
async function requireLogin() {
  if (currentUser.value) return true
  try {
    await ElMessageBox.confirm(t('needLoginAsk'), t('needLoginTitle'), {
      type: 'warning',
      confirmButtonText: t('goDiscover'),
      cancelButtonText: t('cancel')
    })
    router.push('/discover')
  } catch (e) {
    /* 用户选择留在本页 */
  }
  return false
}

/* ============================================================
   ① 本地题库（离线可用）
   ============================================================ */
const zone = ref('banks')
const banks = ref([])
const bankTotal = ref(0)
const bankPage = ref(1)
const bankSize = 10
const banksLoading = ref(false)
const banksError = ref('')
const exportingBankId = ref(null)
/** 题库 id → 题数（GET /banks 不返回题数，逐库轻量取 total 补齐） */
const bankCounts = reactive({})

function bankQuestionText(b) {
  const v = b?.questionCount ?? b?.questionsCount ?? bankCounts[b?.id]
  return v == null ? '—' : v
}

/** 补齐题数：只取 total（size=1），失败就显示 —，不影响其它操作 */
async function fillBankCounts(list) {
  for (const b of list) {
    if (b?.id == null) continue
    if (b.questionCount != null || b.questionsCount != null) continue
    if (bankCounts[b.id] != null) continue
    try {
      const d = await http.get(`/banks/${b.id}/questions`, {
        params: { page: 1, size: 1 },
        skipErrorMessage: true
      })
      bankCounts[b.id] = Number(d?.total ?? 0)
    } catch (e) {
      /* 题数拿不到就留空 */
    }
  }
}

async function loadBanks() {
  banksLoading.value = true
  banksError.value = ''
  try {
    const d = await http.get('/banks', {
      params: { page: bankPage.value, size: bankSize },
      skipErrorMessage: true
    })
    banks.value = d?.records || []
    bankTotal.value = Number(d?.total || 0)
    fillBankCounts(banks.value)
  } catch (e) {
    banks.value = []
    bankTotal.value = 0
    banksError.value = errText(e, t('banksLoadFail'))
  } finally {
    banksLoading.value = false
  }
}

function onBankPage(p) {
  bankPage.value = p
  loadBanks()
}

function gotoBank(b) {
  if (b?.id != null) router.push(`/banks/${b.id}`)
}

/* ============================================================
   ② 已导出文件（离线可用）
   ============================================================ */
const exports = ref([])
const exportsLoading = ref(false)
const exportsError = ref('')
const expPage = ref(1)
const expSize = 10
const busyRecordId = ref(null)

/** 新→旧（后端一般已排好序，这里再兜一层，保证"最近一次导出/发布"取到的都是最新） */
const sortedExports = computed(() =>
  [...exports.value].sort((a, b) => String(b?.createdAt || '').localeCompare(String(a?.createdAt || '')))
)
const exportsCount = computed(() => exports.value.length)
const expPageItems = computed(() => sortedExports.value.slice((expPage.value - 1) * expSize, expPage.value * expSize))
/** 题库 id → 最近一条导出记录（①区判断"是否发布过/是否已导出"） */
const exportsByBank = computed(() => {
  const m = new Map()
  for (const r of sortedExports.value) {
    if (r?.bankId == null) continue
    if (!m.has(r.bankId)) m.set(r.bankId, r)
  }
  return m
})

function latestExportOfBank(b) {
  return b?.id == null ? null : exportsByBank.value.get(b.id) || null
}
/** ①区"已发布 vX"：取该题库最近一条"已发布"记录 */
function bankPublishedVersion(b) {
  const r = latestExportOfBank(b)
  if (!r?.published) return ''
  return r.publishedVersion || r.version || ''
}

async function loadExports() {
  exportsLoading.value = true
  exportsError.value = ''
  try {
    // 免登录：导出记录完全在本机后端（离线可用），不带 center 参数
    const list = await http.get('/exports', { skipErrorMessage: true })
    exports.value = Array.isArray(list) ? list : []
    // 记录变少（移除/清空）后回到第 1 页，避免停在空页
    if ((expPage.value - 1) * expSize >= exports.value.length) expPage.value = 1
  } catch (e) {
    exports.value = []
    exportsError.value = errText(e, t('exportsLoadFail'))
  } finally {
    exportsLoading.value = false
  }
}

function onExpPage(p) {
  expPage.value = p
}

/* ---------- 导出位置偏好（后端 prefs 同样免登录；选过就记住） ---------- */
const exportPrefs = ref({ lastDir: '', defaultDir: '' })

async function loadPrefs() {
  try {
    const d = await http.get('/exports/prefs', { skipErrorMessage: true })
    exportPrefs.value = { lastDir: d?.lastDir || '', defaultDir: d?.defaultDir || '' }
  } catch (e) {
    /* 拿不到就交给后端默认目录 */
  }
}

function rememberDir(dir) {
  if (!dir) return
  exportPrefs.value = { ...exportPrefs.value, lastDir: dir }
  http.put('/exports/prefs', { lastDir: dir }, { skipErrorMessage: true }).catch(() => {})
}

/* ---------- ①区「导出文件…」：纯本机操作（免登录，离线可用） ---------- */
async function quickExport(b) {
  if (exportingBankId.value) return
  exportingBankId.value = b.id
  try {
    const body = { bankId: b.id }
    if (b.version) body.version = b.version
    const r = await http.post('/exports/export', body, {
      skipErrorMessage: true,
      timeout: EXPORT_TIMEOUT_MS
    })
    ElMessage.success(r?.filePath ? t('msgExportedTo', { path: r.filePath }) : t('msgExported'))
    await loadExports()
  } catch (e) {
    toastError(e, t('exportFail'))
  } finally {
    exportingBankId.value = null
  }
}

/* ---------- 「打开所在文件夹」（桌面版 Rust open_directory） ---------- */
async function openRecordFolder(r) {
  const dir = dirOf(r?.filePath)
  if (!dir) {
    ElMessage.warning(t('noPath'))
    return
  }
  try {
    const handled = await openLocalFolder(dir)
    if (!handled) ElMessage.warning(t('openFolderDesktopOnly'))
  } catch (e) {
    ElMessage.warning(errText(e, t('openFolderFail')))
  }
}

/* ---------- 移除导出记录（询问是否同时删除磁盘文件） ---------- */
async function removeExport(r) {
  if (busyRecordId.value) return
  // 三态：确认=同时删文件；取消=只删记录；关闭对话框=放弃
  let deleteFile = false
  try {
    await ElMessageBox.confirm(t('expRemoveAsk', { name: r.fileName }), t('expRemoveTitle'), {
      type: 'warning',
      distinguishCancelAndClose: true,
      confirmButtonText: t('expRemoveWithFile'),
      cancelButtonText: t('expRemoveKeepFile'),
      confirmButtonClass: 'el-button--danger'
    })
    deleteFile = true
  } catch (action) {
    if (action !== 'cancel') return
    deleteFile = false
  }
  busyRecordId.value = r.id
  try {
    await http.delete(`/exports/${r.id}`, {
      params: { deleteFile },
      skipErrorMessage: true
    })
    ElMessage.success(deleteFile ? t('msgRecordRemovedWithFile') : t('msgRecordRemoved'))
    await loadExports()
  } catch (e) {
    toastError(e, t('removeRecordFail'))
  } finally {
    busyRecordId.value = null
  }
}

/* ============================================================
   ③ 已发布作品（需联网 + 登录；沿用原实现）
   ============================================================ */
const size = 10
const records = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const listError = ref('')
const busyKey = ref('') // `${packageKey}:${action}`

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

async function loadWorks() {
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
  loadWorks()
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

/* ---------- 刷新：三区一起（②③在发布成功后也会各自刷新） ---------- */
const refreshing = ref(false)
async function refreshAll() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await Promise.all([loadBanks(), loadExports(), loadPrefs()])
    if (currentUser.value) await loadWorks()
  } finally {
    refreshing.value = false
  }
}

/** 切到③区时首次加载（离线/未登录时③区只显示引导，不影响①②） */
function onZoneChange(name) {
  if (name === 'works' && currentUser.value && !records.value.length && !loading.value) {
    loadWorks()
  }
}

/* ============================================================
   发布作品（①本地题库 / ②导出记录 / 手动选文件 三入口共用同一弹窗）
   ============================================================ */
const publishVisible = ref(false)
/** 'bank' = 从本地题库发起（先导出再发布）；'export' = 从已导出记录发起；'manual' = 手动选文件（multipart 上传） */
const pubOrigin = ref('manual')
const pubBank = ref(null)
const pubExport = ref(null)
const pubInput = ref(null)
const pubFile = ref(null)
const pubForm = reactive({ title: '', version: '', description: '', source: '', storageKind: 'HOSTED', downloadUrl: '' })
/** 导出目录（仅①区可用；空 = 用后端默认目录） */
const pubDir = ref('')
const pubPicking = ref(false)
/** GET /exports/next-version：{ suggested, lastPublished } */
const pubNext = ref(null)
const pubBusy = ref(false)
/** 从磁盘发布的阶段：'export' | 'publish' | 'mark' | '' */
const pubStage = ref('')
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

const pubIsManual = computed(() => pubOrigin.value === 'manual')
/** 版本号只在①区可改：②的文件已定版，手动路径的版本来自文件 */
const pubVersionEditable = computed(() => pubOrigin.value === 'bank')
const pubCanPickDir = computed(() => pubOrigin.value === 'bank' && isDesktop())
/** 未显式选择时，后端会用的默认导出目录（用于展示；未选就不往请求里塞 dir） */
const pubEffectiveDir = computed(
  () => pubDir.value || exportPrefs.value.lastDir || exportPrefs.value.defaultDir || ''
)
/** 从磁盘发布没有进度事件 → 不确定态进度条 + 阶段文案 */
const pubIndeterminate = computed(() => pubBusy.value && (!pubIsManual.value || pubTotal.value <= 0))
const pubStageText = computed(() => {
  if (pubStage.value === 'export') return t('pubStageExport')
  if (pubStage.value === 'publish') return t('pubStagePublish')
  if (pubStage.value === 'mark') return t('pubStageMark')
  return pubServerBusy.value ? t('pubServerBusy') : t('pubUploading', { p: pubPct.value })
})
const pubBlockedTip = computed(() => (pubIsManual.value ? t('pubBlockedManual') : t('pubBlockedTip')))
const pubVersionHint = computed(() => {
  if (pubOrigin.value === 'bank') {
    if (pubNext.value?.lastPublished) return t('pubVersionHintLast', { v: pubNext.value.lastPublished })
    if (pubNext.value?.suggested) return t('pubVersionHintSuggest', { v: pubNext.value.suggested })
    return t('pubVersionHintFree')
  }
  return pubOrigin.value === 'export' ? t('pubVersionLocked') : t('pubVersionFromFile')
})
const pubDialogTitle = computed(() => {
  if (pubOrigin.value === 'bank') return t('pubTitleBank', { name: pubBank.value?.name || '' })
  if (pubOrigin.value === 'export') return t('pubTitleExport', { name: pubExport.value?.fileName || '' })
  return t('pubTitleManual')
})
const pubOriginText = computed(() => {
  if (pubOrigin.value === 'bank') {
    return t('pubFromBank', { name: pubBank.value?.name || '' }) + t('pubOriginExported')
  }
  if (pubOrigin.value === 'export') return t('pubFromExport', { name: pubExport.value?.fileName || '' })
  return t('pubFromManual')
})

/** 体检通过才允许提交（手动路径）：未选文件 / 正在检查 / 检查未通过都禁用「发布」 */
const pubReady = computed(() => {
  if (pubBusy.value || pubInspecting.value) return false
  if (pubIsManual.value) {
    if (!pubFile.value || !pubInspect.value) return false
  } else if (pubOrigin.value === 'bank') {
    if (!pubBank.value || !pubForm.version.trim()) return false
  } else if (!pubExport.value) {
    return false
  }
  if (pubForm.storageKind === 'EXTERNAL' && !/^https?:\/\//i.test(pubForm.downloadUrl.trim())) return false
  return true
})

/** 中断未完成的体检并清空其结果（换文件、文件不合法、关闭弹窗时复用） */
function clearPubInspect() {
  pubInspectController?.abort()
  pubInspectController = null
  pubInspecting.value = false
  pubInspect.value = null
  pubInspectError.value = ''
}

function resetPub() {
  pubOrigin.value = 'manual'
  pubBank.value = null
  pubExport.value = null
  pubFile.value = null
  pubForm.title = ''
  pubForm.version = ''
  pubForm.description = ''
  pubForm.source = ''
  pubForm.storageKind = 'HOSTED'
  pubForm.downloadUrl = ''
  pubDir.value = ''
  pubNext.value = null
  pubError.value = ''
  pubStage.value = ''
  pubPct.value = 0
  pubTotal.value = 0
  pubServerBusy.value = false
  clearPubInspect()
  pubTitleTouched = false
  if (pubInput.value) pubInput.value.value = ''
}

function afterPublishClosed() {
  resetPub()
}

/** 上传/发布中不允许关闭弹窗（避免用户以为已取消） */
function beforePublishClose(done) {
  if (pubBusy.value) return
  done()
}

/** 取该题库的下一版本建议（失败就用题库自身版本，不打扰用户） */
async function fetchNextVersion(bankId) {
  if (bankId == null) return null
  try {
    const d = await http.get('/exports/next-version', {
      params: { bankId },
      skipErrorMessage: true
    })
    return { suggested: d?.suggested || '', lastPublished: d?.lastPublished || '' }
  } catch (e) {
    return null
  }
}

/** 入口①：本地题库 → 发布（先导出文件，再从磁盘发布） */
async function openPublishFromBank(b) {
  if (!(await requireLogin())) return
  resetPub()
  pubOrigin.value = 'bank'
  pubBank.value = b
  pubForm.title = b?.name || ''
  pubForm.version = b?.version || ''
  publishVisible.value = true
  const next = await fetchNextVersion(b?.id)
  // 等待期间用户可能已关闭弹窗或换了入口：结果作废
  if (!publishVisible.value || pubOrigin.value !== 'bank' || pubBank.value?.id !== b?.id) return
  pubNext.value = next
  if (next?.suggested) pubForm.version = next.suggested
}

/** 入口②：已导出文件 → 发布（文件已在磁盘，跳过导出） */
async function openPublishFromExport(r) {
  if (r?.fileExists === false) {
    ElMessage.warning(t('publishMissingTip'))
    return
  }
  if (!(await requireLogin())) return
  resetPub()
  pubOrigin.value = 'export'
  pubExport.value = r
  pubForm.title = r?.bankName || stripExt(r?.fileName)
  pubForm.version = r?.version || ''
  pubNext.value = r?.published
    ? { suggested: '', lastPublished: r.publishedVersion || r.version || '' }
    : null
  publishVisible.value = true
}

/** 入口③（页面顶部）：手动选文件 → 原 multipart 上传路径 */
async function openPublishManual() {
  if (!(await requireLogin())) return
  resetPub()
  pubOrigin.value = 'manual'
  publishVisible.value = true
}

/** 「选择文件夹」：桌面版弹系统对话框（Rust pick_directory）；取消返回 null → 保持原值 */
async function chooseDir() {
  if (pubPicking.value || pubBusy.value) return
  pubPicking.value = true
  try {
    const dir = await pickDirectory({
      title: t('pickDirTitle'),
      defaultDir: pubDir.value || exportPrefs.value.lastDir || exportPrefs.value.defaultDir
    })
    if (dir) {
      pubDir.value = dir
      pubError.value = ''
    }
  } catch (e) {
    pubError.value = errText(e, t('pickDirFail'))
  } finally {
    pubPicking.value = false
  }
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
    // 手动路径的版本由文件决定：显示出来让用户心里有数（不可改）
    if (r?.version) pubForm.version = r.version
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

function doPublish() {
  if (pubBusy.value) return
  pubError.value = ''
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
  if (pubIsManual.value) return doPublishManual(url, kind)
  return doPublishFromDisk(url, kind)
}

/**
 * 从磁盘直接发布（①先在磁盘上导出 .tiku，②直接用已存在的文件）：
 *   ①/② → POST /exports/export（仅①）→ POST /center/publish-from-path → POST /exports/{id}/mark-published
 * 前端不读文件字节：后端读本地文件 + 上传中心，所以这里只有不确定态进度 + 阶段文案。
 */
async function doPublishFromDisk(url, kind) {
  const origin = pubOrigin.value
  if (origin === 'bank' && !pubBank.value) {
    pubError.value = t('msgNeedBank')
    return
  }
  if (origin === 'export' && !pubExport.value) {
    pubError.value = t('msgNeedRecord')
    return
  }
  const version = pubForm.version.trim()
  if (origin === 'bank' && !version) {
    pubError.value = t('msgNeedVersion')
    return
  }

  pubBusy.value = true
  pubPct.value = 0
  pubTotal.value = 0
  pubServerBusy.value = false
  pubController = new AbortController()
  let record = pubExport.value
  try {
    if (origin === 'bank') {
      // ① 导出到磁盘（选定目录则用选定目录，否则后端 prefs 默认目录）
      pubStage.value = 'export'
      const body = { bankId: pubBank.value.id }
      if (version) body.version = version
      if (pubDir.value) body.dir = pubDir.value
      record = await http.post('/exports/export', body, {
        skipErrorMessage: true,
        timeout: EXPORT_TIMEOUT_MS,
        signal: pubController.signal
      })
      if (pubDir.value) rememberDir(pubDir.value)
      if (!record?.filePath) throw new Error(t('exportNoPath'))
    }
    // ② 从磁盘直接发布（后端读本地文件上传中心）
    pubStage.value = 'publish'
    const payload = { filePath: record.filePath, storageKind: kind }
    if (kind === 'EXTERNAL' && url) payload.downloadUrl = url
    const title = pubForm.title.trim()
    const description = pubForm.description.trim()
    const source = pubForm.source.trim()
    if (title) payload.title = title
    if (description) payload.description = description
    if (source) payload.source = source
    if (record.id != null) payload.exportRecordId = record.id
    const r = await http.post('/center/publish-from-path', payload, {
      params: { center: getCenterUrl() },
      skipErrorMessage: true,
      timeout: PUBLISH_FROM_PATH_TIMEOUT_MS,
      signal: pubController.signal
    })
    // ③ 把导出记录标记为已发布（②区随即显示"已发布 vX"）
    pubStage.value = 'mark'
    const publishedVersion = r?.version || record.version || version
    if (record.id != null) {
      try {
        await http.post(
          `/exports/${record.id}/mark-published`,
          { version: publishedVersion },
          { skipErrorMessage: true }
        )
      } catch (e) {
        if (syncLoginState(e)) {
          publishVisible.value = false
          return
        }
        // 作品已发布成功，只是本地记录没更新：提示但不当作失败
        ElMessage.warning(t('markPublishedFail'))
      }
    }
    publishVisible.value = false
    lastPublished.value = {
      packageKey: r?.packageKey || record.packageKey || '',
      title: title || record.bankName || stripExt(record.fileName)
    }
    ElMessage.success(t('msgPublished'))
    page.value = 1
    // 发布成功：刷新②（导出记录）与③（已发布作品），①的"是否发布过"也随之更新
    await Promise.all([loadExports(), loadWorks()])
    loadBanks()
  } catch (e) {
    if (isCanceled(e)) {
      pubError.value = ''
      if (!leaving) ElMessage.info(t('msgContinueFail'))
      return
    }
    if (syncLoginState(e)) {
      publishVisible.value = false
      return
    }
    pubError.value = errText(e, t('publishFail'))
  } finally {
    pubBusy.value = false
    pubStage.value = ''
    pubController = null
  }
}

/** 手动选文件发布：保留原有 multipart 上传 + 进度 + 取消（路径未改动） */
async function doPublishManual(url, kind) {
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
  pubStage.value = ''
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
    // 手动路径不产生导出记录，但同样刷新②③保证页面一致
    await Promise.all([loadExports(), loadWorks()])
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
    await loadWorks()
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
    await loadWorks()
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
    await loadWorks()
  } catch (e) {
    if (!syncLoginState(e)) toastError(e, t('removeFail'))
  } finally {
    busyKey.value = ''
  }
}

/* ---------- 生命周期 ---------- */
onMounted(async () => {
  // ①②离线可用（本地题库 / 导出记录 / 目录偏好都已免登录）：先并行加载本地数据
  loadBanks()
  loadExports()
  loadPrefs()
  await fetchMe()
  meLoading.value = false
  // ③已发布作品：需要登录 + 联网
  if (currentUser.value) await loadWorks()
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

/* 登录/离线状态条（三区共用） */
.status-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 9px 13px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--bg-card-2);
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.6;
}
.status-bar.warn {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-text);
}
.status-bar.ok {
  border-color: var(--border);
  background: var(--bg-card-2);
}
.status-text {
  flex: 1;
  min-width: 0;
}
.status-link {
  flex-shrink: 0;
  color: var(--accent-text);
  font-weight: 500;
}
.status-link:hover {
  text-decoration: underline;
}

/* 发布成功提示条 */
.ok-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 12px;
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

/* 三区 Tab */
.zones {
  margin-top: 18px;
}
.zone-lead {
  margin: 2px 0 10px;
  font-size: 12.5px;
  line-height: 1.7;
}
.zone-list {
  display: flex;
  flex-direction: column;
}
.zone-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 56px 24px;
  text-align: center;
  color: var(--text-muted);
}
.zone-state h3 {
  margin-top: 6px;
  color: var(--text-primary);
}
.zone-state p {
  max-width: 520px;
  line-height: 1.7;
}
.zone-state .btn {
  margin-top: 6px;
}

/* 列表行（三区共用） */
.list-total {
  font-size: 12.5px;
  padding: 0 4px 8px;
}
.row-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 14px 4px;
  border-top: 1px solid var(--border);
}
.row-item:last-child {
  border-bottom: 1px solid var(--border);
}
/* 行尾还有分页器等元素时，:last-child 不是最后一行 → 用 last-of-type 补上闭合线 */
.zone-list > .row-item:last-of-type {
  border-bottom: 1px solid var(--border);
}
/* fileExists=false：整行灰显（文件已被移动/删除） */
.row-item.missing {
  opacity: 0.6;
}
.row-main {
  min-width: 0;
  flex: 1;
}
.row-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.file-title {
  max-width: 420px;
  font-size: 14.5px;
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
.tag.removed,
.tag.never,
.tag.plain {
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
.row-desc {
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
.row-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.row-sub {
  margin-top: 4px;
  font-size: 11.5px;
}
.row-warn {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--danger);
}
.row-link {
  margin-top: 5px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 2px;
}
.row-actions {
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
  margin-top: 24px;
}
.foot-tip {
  margin-top: 24px;
  font-size: 12.5px;
  line-height: 1.8;
}
.foot-link {
  margin-left: 4px;
}
.sk-row {
  height: 74px;
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
.origin-line {
  margin: 0;
  font-size: 13px;
  color: var(--text-primary);
}
.dir-pick {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.dir-value {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.version-ro {
  margin: 0;
  font-size: 13.5px;
  color: var(--text-primary);
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
