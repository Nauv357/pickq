<template>
  <div class="page">
    <!-- ============ 列表视图 ============ -->
    <template v-if="mode === 'list'">
      <header class="page-header">
        <div>
          <h1 class="page-title">发现题库</h1>
          <p class="page-desc">浏览拾题题库广场的免费题库；点开作品可看详情与评论，托管作品可直接导入</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary" @click="openPlaza">
            <TikuIcon name="link" :size="15" />
            在官网浏览
          </button>
        </div>
      </header>

      <div class="toolbar">
        <div class="tabs">
          <button class="tab" :class="{ active: sort === 'new' }" @click="changeSort('new')">最新</button>
          <button class="tab" :class="{ active: sort === 'hot' }" @click="changeSort('hot')">热门</button>
        </div>
        <form class="search" @submit.prevent="doSearch">
          <input v-model="qInput" class="search-input" type="search" placeholder="搜标题、描述或作者" />
          <button class="btn btn-ghost btn-sm" type="submit">搜索</button>
        </form>
      </div>

      <div v-if="offline" class="discover-state">
        <TikuIcon name="info" :size="40" />
        <h3>网络不可用</h3>
        <p class="text-secondary">题库广场需要联网访问；本地题库的录题、刷题不受影响。</p>
        <button class="btn btn-secondary" @click="load(true)">重试</button>
      </div>
      <div v-else-if="loading && !records.length" class="discover-state">
        <div v-for="n in 4" :key="n" class="sk-card tiku-skeleton"></div>
      </div>
      <div v-else-if="!records.length" class="discover-state">
        <TikuIcon name="search" :size="40" />
        <h3>{{ errorMsg || '广场暂时没有作品' }}</h3>
        <p class="text-secondary">{{ errorMsg ? '网络异常，请稍后重试。' : '稍后再来看看，或到官网发布你的第一个题库。' }}</p>
        <button class="btn btn-secondary" @click="load(true)">重试</button>
      </div>

      <div v-else class="discover-list">
        <div v-for="w in records" :key="w.packageKey" class="d-item">
          <div class="d-main" @click="openDetail(w)">
            <p class="d-title">
              {{ w.title }}
              <span v-if="w.storageKind === 'HOSTED'" class="d-tag hosted">中心托管</span>
              <span v-else-if="w.downloadUrl" class="d-tag ext">作者外链</span>
            </p>
            <p class="d-desc">{{ w.description || '作者未填写描述' }}</p>
            <p class="d-meta">
              <span v-if="w.authorId" class="author-name" @click.stop="openAuthor(w.authorId, null)">{{ w.authorName || '匿名' }}</span>
              <span v-else>{{ w.authorName || '匿名' }}</span>
              · v{{ w.version }} · 共 {{ w.questionsCount ?? '—' }} 题 · 收藏 {{ w.favoritesCount }} · {{ dateText(w.createdAt) }}
            </p>
          </div>
          <div class="d-actions">
            <button
              v-if="w.storageKind === 'HOSTED'"
              class="btn btn-primary btn-sm"
              :disabled="importingKey === w.packageKey"
              @click="importPack(w)"
            >{{ importingKey === w.packageKey ? '导入中…' : '导入' }}</button>
            <template v-else>
              <button
                class="btn btn-primary btn-sm"
                :disabled="importingKey === w.packageKey"
                @click="importExternal(w)"
              >{{ importingKey === w.packageKey ? '处理中…' : '导入' }}</button>
              <button class="btn btn-ghost btn-sm" title="浏览器下载（网盘链接时用）" @click="downloadExternal(w)">
                <TikuIcon name="download" :size="14" />
              </button>
            </template>
            <button class="btn btn-ghost btn-sm icon-btn" title="查看详情与评论" @click="openDetail(w)">
              <TikuIcon name="chevron-right" :size="14" />
            </button>
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
    </template>

    <!-- ============ 详情视图 ============ -->
    <template v-else-if="mode === 'detail'">
      <header class="page-header">
        <div>
          <button class="btn btn-ghost btn-sm back-btn" @click="backToList">
            <TikuIcon name="arrow-left" :size="14" /> 返回列表
          </button>
        </div>
      </header>

      <div v-if="detailLoading" class="discover-state"><div class="sk-card tiku-skeleton"></div></div>
      <div v-else-if="detailError" class="discover-state">
        <TikuIcon name="info" :size="40" />
        <h3>无法加载详情</h3>
        <p class="text-secondary">{{ detailError }}</p>
        <button class="btn btn-secondary" @click="backToList">返回列表</button>
      </div>

      <template v-else-if="detail">
        <div class="detail-card">
          <div class="detail-head">
            <h2 class="detail-title">{{ detail.latest.title }}</h2>
            <span v-if="detail.latest.storageKind === 'HOSTED'" class="d-tag hosted">中心托管</span>
            <span v-else-if="detail.latest.downloadUrl" class="d-tag ext">作者外链</span>
          </div>
          <p v-if="detail.author" class="detail-author">
            作者：
            <span class="author-name" @click="openAuthor(detail.author.id, detail.latest.packageKey)">
              {{ detail.author.nickname || detail.author.username }}
            </span>
            <span v-if="detail.author.followersCount > 0" class="text-muted"> · {{ detail.author.followersCount }} 粉丝</span>
          </p>
          <p v-else class="detail-author text-muted">作者：{{ detail.latest.authorName || '匿名' }}</p>
          <p class="detail-desc">{{ detail.latest.description || '作者未填写描述' }}</p>
          <p class="detail-meta">
            共 {{ detail.latest.questionsCount ?? '—' }} 题<template v-if="detail.latest.materialsCount"> · 材料 {{ detail.latest.materialsCount }}</template>
            · 收藏 {{ detail.latest.favoritesCount }} · 下载跳转 {{ detail.latest.downloadClicks }}
            · 更新于 {{ dateText(detail.latest.updatedAt) }}
          </p>
          <p v-if="detail.latest.source" class="detail-source">来源声明：{{ detail.latest.source }}</p>
          <p v-if="detail.latest.checksum || detail.latest.fileSha256" class="detail-source">
            指纹：<span class="mono">{{ shortKey(detail.latest.checksum || detail.latest.fileSha256) }}</span>
            <span class="text-muted">（下载后可按此核对文件）</span>
          </p>

          <div class="detail-actions">
            <button
              v-if="detail.latest.storageKind === 'HOSTED'"
              class="btn btn-primary"
              :disabled="importingKey === detail.latest.packageKey"
              @click="importPack(detail.latest)"
            >{{ importingKey === detail.latest.packageKey ? '导入中…' : '导入到本应用' }}</button>
            <template v-else>
              <button
                class="btn btn-primary"
                :disabled="importingKey === detail.latest.packageKey"
                @click="importExternal(detail.latest)"
              >{{ importingKey === detail.latest.packageKey ? '处理中…' : '导入到本应用' }}</button>
              <button class="btn btn-secondary" @click="downloadExternal(detail.latest)">下载文件</button>
            </template>
            <button class="btn btn-ghost" @click="openPack(detail.latest)">在官网打开作品页</button>
          </div>
          <p v-if="detail.latest.storageKind !== 'HOSTED'" class="login-tip text-muted">
            作者外链：若链接为网盘页面无法直接导入，会自动用浏览器下载；
            下载完成后回到「题库」页点「导入」选择该文件即可。
          </p>
          <p class="login-tip text-muted">
            收藏、评论与发布需要在题库广场登录后操作——
            <span class="author-name" @click="openPlaza">前往官网</span>
          </p>
        </div>

        <!-- 版本历史（只读展示） -->
        <section v-if="detail.versions.length > 1" class="detail-section">
          <h3 class="section-title">版本历史（{{ detail.versions.length }}）</h3>
          <div v-for="v in detail.versions" :key="v.version" class="ver-row">
            <div class="ver-main">
              <p class="ver-head">
                v{{ v.version }}
                <span v-if="v.version === detail.latest.version" class="d-tag hosted ver-cur">当前</span>
              </p>
              <p class="ver-meta">
                {{ dateText(v.createdAt) }} · 共 {{ v.questionsCount ?? '—' }} 题 ·
                指纹 <span class="mono">{{ shortKey(v.checksum || v.fileSha256) }}</span>
              </p>
            </div>
            <div class="ver-side">
              <button v-if="v.version !== detail.latest.version && v.storageKind === 'HOSTED'" class="btn btn-ghost btn-sm" @click="importVersion(v)">导入此版本</button>
              <button v-else-if="v.version !== detail.latest.version && v.downloadUrl" class="btn btn-ghost btn-sm" @click="downloadExternal(v)">下载此版本</button>
            </div>
          </div>
        </section>

        <!-- 衍生作品 -->
        <section v-if="detail.derived && detail.derived.length" class="detail-section">
          <h3 class="section-title">由此派生的作品</h3>
          <div v-for="d in detail.derived" :key="d.packageKey" class="derived-item" @click="openDetail(d)">
            <span class="derived-title">{{ d.title }}</span>
            <span class="text-muted"> · {{ d.authorName || '匿名' }} · v{{ d.version }}</span>
          </div>
        </section>

        <!-- 评论区（只读） -->
        <section class="detail-section">
          <h3 class="section-title">评论（{{ comments.length }}）</h3>
          <p v-if="commentsLoading" class="text-muted">加载中…</p>
          <p v-else-if="!comments.length" class="text-muted comment-empty">还没有评论。到官网登录后可以发表。</p>
          <div v-for="c in comments" :key="c.id" class="comment-item">
            <p class="comment-head">
              <span v-if="c.userId" class="author-name" @click="openAuthor(c.userId, detail.latest.packageKey)">{{ c.authorName }}</span>
              <span v-else>{{ c.authorName }}</span>
              <span class="text-muted comment-date">{{ dateText(c.createdAt) }}</span>
              <span v-if="c.likesCount > 0" class="comment-like">有帮助 · {{ c.likesCount }}</span>
            </p>
            <p class="comment-body">{{ c.content }}</p>
          </div>
        </section>
      </template>
    </template>

    <!-- ============ 作者视图 ============ -->
    <template v-else-if="mode === 'author'">
      <header class="page-header">
        <div>
          <button class="btn btn-ghost btn-sm back-btn" @click="backFromAuthor">
            <TikuIcon name="arrow-left" :size="14" /> 返回
          </button>
        </div>
      </header>

      <div v-if="authorLoading" class="discover-state"><div class="sk-card tiku-skeleton"></div></div>
      <div v-else-if="authorError" class="discover-state">
        <TikuIcon name="info" :size="40" />
        <h3>无法加载作者信息</h3>
        <p class="text-secondary">{{ authorError }}</p>
      </div>

      <template v-else-if="authorInfo">
        <div class="detail-card author-card">
          <h2 class="detail-title">{{ authorInfo.author.nickname || authorInfo.author.username }}</h2>
          <p class="text-muted">@{{ authorInfo.author.username }} · 加入于 {{ dateText(authorInfo.author.created_at) }}</p>
          <p v-if="authorInfo.author.bio" class="detail-desc">{{ authorInfo.author.bio }}</p>
          <p class="detail-meta">
            作品 {{ authorInfo.total }} · 粉丝 {{ authorInfo.followersCount || 0 }}
          </p>
          <p class="login-tip text-muted">
            关注作者需要登录——<span class="author-name" @click="openPlaza">前往官网</span>
          </p>
        </div>

        <div class="discover-list">
          <div v-for="w in authorInfo.records" :key="w.packageKey" class="d-item">
            <div class="d-main" @click="openDetail(w)">
              <p class="d-title">{{ w.title }}</p>
              <p class="d-desc">{{ w.description || '作者未填写描述' }}</p>
              <p class="d-meta">v{{ w.version }} · 共 {{ w.questionsCount ?? '—' }} 题 · 收藏 {{ w.favoritesCount }} · {{ dateText(w.createdAt) }}</p>
            </div>
            <div class="d-actions">
              <button class="btn btn-ghost btn-sm icon-btn" title="查看详情" @click="openDetail(w)">
                <TikuIcon name="chevron-right" :size="14" />
              </button>
            </div>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import TikuIcon from '../components/TikuIcon.vue'
import { centerPacksUrl, getCenterUrl } from '../utils/center'
import { openExternal } from '../utils/external'
import http from '../api/http'

/* ---------- 列表 ---------- */
const size = 12
const records = ref([])
const total = ref(0)
const page = ref(1)
const sort = ref('new')
const q = ref('')
const qInput = ref('')
const loading = ref(false)
const offline = ref(false)
const errorMsg = ref('')
const importingKey = ref('')

/* ---------- 视图切换：list / detail / author ---------- */
const mode = ref('list')
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const comments = ref([])
const commentsLoading = ref(false)
const authorInfo = ref(null)
const authorLoading = ref(false)
const authorError = ref('')
const authorBackKey = ref(null) // 从哪个作品详情进入作者页（返回用）

function dateText(s) {
  return s ? String(s).slice(0, 10) : ''
}
function shortKey(s) {
  return s ? `${String(s).slice(0, 8)}…${String(s).slice(-4)}` : ''
}

async function load(manual = false) {
  loading.value = true
  offline.value = false
  errorMsg.value = ''
  try {
    const d = await http.get('/center/packs', {
      skipErrorMessage: true,
      params: { center: getCenterUrl(), sort: sort.value, q: q.value || undefined, page: page.value, size }
    })
    records.value = d.records || []
    total.value = d.total || 0
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || ''
    const netErr = !e?.response || /Network Error|ERR_|fetch failed|ECONNREFUSED/.test(msg)
    if (netErr) {
      offline.value = true
    } else {
      offline.value = false
      errorMsg.value = msg || '广场访问失败'
      records.value = []
    }
  } finally {
    loading.value = false
  }
}

function changeSort(s) {
  sort.value = s
  page.value = 1
  load()
}
function doSearch() {
  q.value = qInput.value.trim()
  page.value = 1
  load()
}
function onPage(p) {
  page.value = p
  load()
}

function openPlaza() {
  openExternal(centerPacksUrl())
}
function openPack(w) {
  openExternal(`${centerPacksUrl()}/${encodeURIComponent(w.packageKey)}`)
}

/** 浏览器下载外链文件（网盘链接等场景） */
function downloadExternal(w) {
  if (w.downloadUrl) {
    openExternal(w.downloadUrl)
  } else {
    ElMessage.warning('该作品没有可用的下载链接')
  }
}

/** 外链作品：尝试后端拉取直链导入；非直链（网盘页面）失败 → 回退浏览器下载 */
async function importExternal(w) {
  if (importingKey.value) return
  if (!w.downloadUrl) {
    ElMessage.warning('该作品没有下载链接')
    return
  }
  importingKey.value = w.packageKey
  try {
    const r = await http.post('/center/import-external', { url: w.downloadUrl })
    const map = {
      ALREADY_IMPORTED: { type: 'info', text: `「${w.title}」已导入过（内容一致），未重复创建` },
      BRANCHED: { type: 'warning', text: '检测到内容修改，已作为分支导入（不覆盖原题库）' },
      VERSION_ADDED: { type: 'success', text: '已作为新版本导入，与原版本并存' },
      CREATED: { type: 'success', text: '导入成功，可前往「题库」开始刷题' }
    }
    const m = map[r?.result] || { type: 'success', text: '导入成功' }
    ElMessage[m.type](m.text)
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || ''
    // 直链拉取失败（网盘页面/防盗链/格式不符）→ 自动回退浏览器下载
    downloadExternal(w)
    ElMessage.info('未能直接导入（可能是网盘页面链接），已在浏览器打开下载；下载完成后在「题库」页点「导入」选择该文件')
    if (msg) console.warn('[discover] import-external failed:', msg)
  } finally {
    importingKey.value = ''
  }
}

/* ---------- 详情 ---------- */
async function openDetail(w) {
  // URL 同步（#pack/{key}）：支持直达/刷新/前进后退
  const key = typeof w === 'string' ? w : w.packageKey
  if (typeof w === 'string') {
    // 由 hash 直达：先构造最小对象用于回退
    w = { packageKey: key, title: '' }
  } else {
    history.replaceState(null, '', `#pack/${encodeURIComponent(key)}`)
  }
  mode.value = 'detail'
  detail.value = null
  comments.value = []
  detailError.value = ''
  detailLoading.value = true
  try {
    const d = await http.get(`/center/packs/${encodeURIComponent(key)}`, {
      skipErrorMessage: true,
      params: { center: getCenterUrl() }
    })
    detail.value = d
    document.title = `${d.latest?.title || '作品'} - 发现题库`
    loadComments(key)
  } catch (e) {
    detailError.value = e?.response?.data?.message || e?.message || '加载失败'
  } finally {
    detailLoading.value = false
  }
}

async function loadComments(packageKey) {
  commentsLoading.value = true
  try {
    const c = await http.get(`/center/packs/${encodeURIComponent(packageKey)}/comments`, {
      skipErrorMessage: true,
      params: { center: getCenterUrl() }
    })
    comments.value = c.comments || []
  } catch (e) {
    comments.value = []
  } finally {
    commentsLoading.value = false
  }
}

async function importVersion(v) {
  await importPack(v)
}

function backToList() {
  mode.value = 'list'
  detail.value = null
  document.title = '发现题库'
  history.replaceState(null, '', location.pathname)
}

/* ---------- 作者 ---------- */
async function openAuthor(id, backKey) {
  if (!id) return
  authorBackKey.value = backKey || null
  mode.value = 'author'
  authorInfo.value = null
  authorError.value = ''
  authorLoading.value = true
  history.replaceState(null, '', `#author/${encodeURIComponent(id)}`)
  try {
    const a = await http.get(`/center/authors/${id}`, {
      skipErrorMessage: true,
      params: { center: getCenterUrl(), page: 1, size: 20 }
    })
    authorInfo.value = a
    document.title = `${a.author?.nickname || a.author?.username || '作者'} - 发现题库`
  } catch (e) {
    authorError.value = e?.response?.data?.message || e?.message || '加载失败'
  } finally {
    authorLoading.value = false
  }
}

function backFromAuthor() {
  if (authorBackKey.value) {
    // 回到来源作品详情（重新拉取以刷新）
    const w = { packageKey: authorBackKey.value }
    openDetail(w)
  } else {
    mode.value = 'list'
    document.title = '发现题库'
    history.replaceState(null, '', location.pathname)
  }
}

/* ---------- 导入 ---------- */
async function importPack(w) {
  if (importingKey.value) return
  importingKey.value = w.packageKey
  try {
    const r = await http.post('/center/import', {
      center: getCenterUrl(),
      packageKey: w.packageKey,
      version: w.version
    })
    const map = {
      ALREADY_IMPORTED: { type: 'info', text: `「${w.title}」已导入过（内容一致），未重复创建` },
      BRANCHED: { type: 'warning', text: '检测到内容修改，已作为分支导入（不覆盖原题库）' },
      VERSION_ADDED: { type: 'success', text: '已作为新版本导入，与原版本并存' },
      CREATED: { type: 'success', text: '导入成功，可前往「题库」开始刷题' }
    }
    const m = map[r?.result] || { type: 'success', text: '导入成功' }
    ElMessage[m.type](m.text)
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '导入失败'
    ElMessage.error(typeof msg === 'string' ? msg : '导入失败')
  } finally {
    importingKey.value = ''
  }
}

onMounted(() => {
  // 支持 #pack/{key} 直达详情、#author/{id} 直达作者页
  const hm = location.hash.match(/^#(pack|author)\/(.+)$/)
  if (hm) {
    const [, kind, raw] = hm
    const key = decodeURIComponent(raw)
    if (kind === 'pack' && key) openDetail(key)
    else if (kind === 'author' && key) openAuthor(Number(key), null)
    return
  }
  load()
})
</script>

<style scoped>
.header-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.back-btn {
  margin-bottom: 4px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0 16px;
}
.tabs {
  display: flex;
  gap: 4px;
}
.tab {
  padding: 7px 14px;
  border: none;
  background: transparent;
  border-radius: 8px;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--ease), color var(--ease);
}
.tab:hover {
  color: var(--text-primary);
}
.tab.active {
  background: var(--bg-elev);
  color: var(--text-primary);
  font-weight: 600;
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
}
.search-input {
  width: 220px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  color: var(--text-primary);
  font-size: 13px;
  outline: none;
  transition: border-color var(--ease);
}
.search-input:focus {
  border-color: var(--accent);
}
.btn-sm {
  height: 32px;
}
.icon-btn {
  padding: 0 10px;
}

.discover-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 90px 24px;
  text-align: center;
  color: var(--text-muted);
}
.discover-state h3 {
  margin-top: 8px;
  color: var(--text-primary);
}
.discover-state p {
  max-width: 480px;
  line-height: 1.7;
}

.discover-list {
  display: flex;
  flex-direction: column;
}
.d-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 6px;
  border-top: 1px solid var(--border);
}
.d-item:last-child {
  border-bottom: 1px solid var(--border);
}
.d-main {
  min-width: 0;
  cursor: pointer;
  flex: 1;
}
.d-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.d-tag {
  font-size: 11px;
  font-weight: 400;
  letter-spacing: 0.04em;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid;
  flex-shrink: 0;
}
.d-tag.hosted {
  color: var(--success);
  border-color: var(--success);
  background: var(--success-soft);
}
.d-tag.ext {
  color: var(--text-secondary);
  border-color: var(--border-strong);
  background: var(--bg-card-2);
}
.d-desc {
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
.d-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.d-actions {
  flex-shrink: 0;
  display: flex;
  gap: 8px;
}
.author-name {
  cursor: pointer;
  color: var(--text-secondary);
  border-bottom: 1px dashed transparent;
  transition: color var(--ease), border-color var(--ease);
}
.author-name:hover {
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}
.sk-card {
  height: 90px;
  border-radius: 10px;
  margin-bottom: 12px;
}

/* ---------- 详情 ---------- */
.detail-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--bg-card);
  padding: 26px 28px;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.detail-title {
  font-size: 20px;
  font-weight: 600;
}
.detail-author {
  margin-top: 10px;
  font-size: 13px;
  color: var(--text-secondary);
}
.detail-desc {
  margin-top: 12px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--text-primary);
}
.detail-meta {
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--text-muted);
}
.detail-source {
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--text-muted);
}
.mono {
  font-family: Consolas, Menlo, monospace;
}
.detail-actions {
  display: flex;
  gap: 10px;
  margin-top: 22px;
  flex-wrap: wrap;
}
.login-tip {
  margin-top: 14px;
  font-size: 12.5px;
  line-height: 1.8;
}

.detail-section {
  margin-top: 28px;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 10px;
}
.ver-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 2px;
  border-top: 1px solid var(--border);
}
.ver-head {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.ver-cur {
  font-size: 10px;
  padding: 0 6px;
}
.ver-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
.ver-side {
  flex-shrink: 0;
}
.derived-item {
  padding: 10px 2px;
  border-top: 1px solid var(--border);
  cursor: pointer;
  font-size: 13.5px;
}
.derived-item:hover {
  color: var(--accent-text);
}
.derived-title {
  font-weight: 500;
}
.comment-empty {
  padding: 8px 0;
}
.comment-item {
  padding: 14px 2px;
  border-top: 1px solid var(--border);
}
.comment-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 13px;
}
.comment-date {
  font-size: 12px;
}
.comment-like {
  font-size: 12px;
  color: var(--success);
}
.comment-body {
  margin-top: 6px;
  font-size: 13.5px;
  line-height: 1.8;
  white-space: pre-wrap;
}
</style>
