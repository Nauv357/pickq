# 代码地图（Code Map）

> 用途有两个：**贡献者快速定位**「某个功能在哪个文件」，以及 **Android 端移植时逐块对照**。
>
> 行数口径：按 `\n` 计数的文件行数（含空行，不含末尾空行），与 `git diff --numstat` / `wc -l` 一致。
> 注意 PowerShell 的 `Measure-Object -Line` 口径不同（会少算），本文件的数字**不要**用那种方式复核。
> 行数会随代码演进漂移，**以代码本身为准**；本文件保证的是「文件 → 职责」的对应关系。
> 表中数字为 2026-09-11 快照；此后各表数字可能滞后，改动过的文件请顺手更新对应行。
>
> 相关文档：架构与关键机制 → [`architecture.md`](architecture.md)；业务规则 → [`features.md`](features.md)；
> 表结构 → [`data-model.md`](data-model.md)；内容包字段 → [`package-format.md`](package-format.md)；
> 编码约定 → [`conventions.md`](conventions.md)。

---

## 1. 目录结构

### 1.1 仓库顶层

```
Tiku/
├── pom.xml                     Maven 根工程：Spring Boot 3.4.4 父 POM、Java 21；
│                               两份 <resource>：src/main/resources 与 frontend/dist → classpath:/static
├── mvnw / mvnw.cmd / .mvn/      Maven Wrapper
├── src/                        后端（Spring Boot + MyBatis-Plus + H2 + Flyway）
│   ├── main/java/com/tiku/**   142 个 .java（config 6 / controller 16 / dto 64 / mapper 9 / model 16 / service 27 / util 3 + TikuApplication）
│   ├── main/resources/         application.yml + db/migration/V1..V14__*.sql
│   └── test/java/com/tiku/**   19 个测试与工具类（含依赖私有样例的 GenerateSampleFiles / GraphPositionProbeTest）
├── frontend/                   桌面端 Vue 3 SPA（Vite 6 + Element Plus 2 + vue-router 4 + vue-i18n 9 + ECharts 6）
│   ├── index.html              防闪屏内联脚本（先按 localStorage['tiku:theme'] 给 <html> 加 dark）
│   ├── vite.config.js          端口 5173；/api 代理到 localhost:8080；无 outDir/alias/base
│   ├── package.json            scripts 仅 dev/build/preview（无 lint、无测试运行器）
│   └── src/                    44 个文件（见 §3/§5）
├── tauri/                      桌面壳（Tauri 2）与打包流水线
│   ├── build-desktop.ps1       一键打包：mvn package → jlink → 复制资源 → tauri build --bundles nsis → 便携 zip
│   ├── ui/                     壳自带的静态页：index.html（启动占位页）、error.html（启动失败页）
│   ├── portable-assets/        便携版附加文件（使用说明.txt、创建桌面快捷方式.bat）
│   └── src-tauri/              Rust 壳：src/main.rs（943 行）、tauri.conf.json、Cargo.toml、
│                               capabilities/default.json、permissions/*.toml、icons/、jre/ 与 app.jar（构建产物，gitignore）
├── web/                        ⚠️ 官网 + 题库广场（Nuxt 3 + Nitro + SQLite）——**不入 git**
├── docs/                       公开技术文档（本目录，已入 git）
├── doc/                        ⚠️ 内部设计与过程文档——**全部不入 git**（.gitignore 第 68 行 `doc/`）
├── deploy/                     ⚠️ 服务器部署资产——**不入 git**（.gitignore 第 59 行）
├── scripts/deploy/             ⚠️ nginx/fail2ban/sshd/备份/归档脚本——**不入 git**（.gitignore 第 60 行）
├── sample-content-package.json v1 内容包示例（已入 git，是贡献者能看到的公开样例，189 行）
├── sample-ai-files/            ⚠️ 本机测试样例（真题/AI 样例，不入 git）
├── dist/                       ⚠️ 旧 scripts/package.ps1 的分发产物（不入 git）
└── target/                     Maven 构建产物（不入 git）
```

### 1.2 `src/`（后端）

```
src/main/java/com/tiku/
├── TikuApplication.java        @SpringBootApplication + @MapperScan；main() 里按 -Dtiku.watch-parent 启动父进程守护
├── config/                     基础设施装配（6）
│   ├── AiSettings.java           AI 配置 POJO（不落库，序列化为 ai-config.json）
│   ├── AsyncConfig.java          aiImportExecutor（单线程串行）+ aiChunkExecutor（固定 3 路 daemon）
│   ├── DataDirectoryConfig.java  @PostConstruct 创建并打印数据目录
│   ├── MybatisPlusConfig.java    分页插件等
│   ├── SpaForwardConfig.java     /** 资源处理 + SPA 回退（/api/** 不回退）
│   └── WebConfig.java            CORS：仅放行 localhost:3000 / localhost:5173
├── controller/                  HTTP 层（16 个文件：15 个 controller + GlobalExceptionHandler）——见 §2
├── dto/                         请求/响应 record（64 个文件 / 73 个 record）+ ApiResponse / PageResult
├── mapper/                      MyBatis-Plus（9）——6 个为空的 BaseMapper，3 个写注解 SQL（两处 FOR UPDATE 行锁 + 一处关联查询/级联删除）
├── model/                       实体与枚举（16）
│   ├── QuestionBank / Question / Material / StudyRecord / ReviewState
│   ├── PracticeSession / StudyRecordFile / StudyRecordFileItem
│   ├── AiImportJob / ExportRecord
│   ├── ContentPackageFile / ContentPackageQuestion / ContentPackageMaterial / OptionItem
│   ├── enums/QuestionType.java   SINGLE / MULTIPLE / JUDGE / SUBJECTIVE
│   └── handler/OptionItemTypeHandler.java   options 列 ↔ List<OptionItem> 的 JSON 类型处理器
├── service/                     业务规则与事务边界（27）——见 §2
└── util/                        无状态工具（3）
    ├── NetAddress.java           本机/私网地址判定（http 是否放行、是否强制填 Key）
    ├── PackageContainer.java     .tiku（zip）容器读写、魔数判定、安全上限
    └── Paging.java               分页参数钳制（page ≥ 1，size ∈ [1,100]）
```

### 1.3 `frontend/`（桌面 SPA）

```
frontend/src/
├── main.js                     入口：注册 Element Plus / i18n / router，先 initTheme() 再 initLang()
├── App.vue                     根组件：el-config-provider + router-view + 两个 Teleport（图片灯箱、更新进度弹窗）
├── router/index.js             12 条路由，全部懒加载；只有 afterEach 设 document.title（无鉴权守卫）
├── api/                        薄封装（10）：http.js（唯一 axios 实例）+ 按后端资源分文件
├── components/                 复用组件（7）——见 §3.2 与 §5.2
├── i18n/                       index.js（建实例，全局字典为空）+ lang.js（切换/持久化/Element Plus 联动）
├── layouts/AppLayout.vue       唯一外壳：侧栏导航 + AI 任务全局监控（5s 轮询 + Notification）
├── styles/main.css             设计令牌与全局样式（502 行）
├── utils/                      纯函数与平台适配（9）——见 §5.1
└── views/                      路由页面（11）——见 §3.1
```

### 1.4 `tauri/`

```
tauri/
├── build-desktop.ps1                     5 步打包（不自带前端构建、不做版本号提升、不产出更新清单）
├── ui/index.html                         壳启动占位页（bundle.frontendDist = "../ui"）
├── ui/error.html                         启动失败页（空壳，具体原因在窗口标题与 desktop.log）
├── src-tauri/src/main.rs                 全部壳逻辑（943 行）：单实例、spawn 后端、解析端口、导航、
│                                         另存为/选目录/打开目录、open_url、自动更新、一键恢复重启
├── src-tauri/tauri.conf.json             productName「拾题」/ version 0.1.17 / identifier cn.shiti.desktop /
│                                         frontendDist "../ui" / targets ["nsis"] / resources ["jre","app.jar"] /
│                                         createUpdaterArtifacts false / security.csp null
├── src-tauri/Cargo.toml                  依赖仅 tauri 2 / base64 / rfd / serde / serde_json（**无 updater/dialog/fs/shell 插件**）
├── src-tauri/capabilities/default.json   windows ["main"] + remote.urls ["http://127.0.0.1:*"] + 7 条权限
└── src-tauri/permissions/*.toml          5 个自定义命令权限（save-dialog / folder-picker / open-url / updater / restore）
```

### 1.5 `web/`（官网 + 广场，**不入 git**）

> `web/`、`deploy/`、`scripts/deploy/`、`doc/` 都被根 `.gitignore` 忽略（第 57–68 行）。
> **贡献者在本仓库看不到这些目录**；`git check-ignore -v web/nuxt.config.ts` → `.gitignore:58:web/`。
> 本节列出它们，是为了让「桌面端与它们的交互点」可定位（改动跨端契约时要知道对面在哪）。

```
web/
├── nuxt.config.ts              41 行；零 Nuxt 模块、nitro: {}（未指定 preset）、runtimeConfig 只覆盖 Turnstile
├── package.json                nuxt ^3.16 / better-sqlite3 ^13 / fflate / nodemailer；scripts 无迁移脚本
├── pages/                      14 个页面（Vue，SSR）——见 §4.1
├── server/api/                 42 个 Nitro 端点文件——见 §4.2（无 server/routes、无 middleware、无 plugins）
├── server/utils/               10 个横切工具——见 §4.3
├── server/db/migrate.ts        265 行；MIGRATIONS 数组 001–010 + schema_migrations（惰性迁移，见 §4.4）
├── server/db/repos.ts          863 行；7 个 repo（user/follow/session/authToken/pack/favorite/comment/report）
├── composables/                useSiteI18n.ts（自研 i18n）、useMe.ts（当前用户 + isAdmin）
├── layouts/default.vue         唯一布局（顶栏 + slot + 页脚）
├── components/                 BrandSeal.vue（CSS 竖排方印）、ShiTiSeal.vue（SVG 朱印，feTurbulence 毛边）
└── data/                       运行数据（DB + 托管文件），gitignore
```

### 1.6 三份外部资产的位置（不入 git，排查问题时用）

| 资产 | 位置 | 内容 |
| --- | --- | --- |
| 服务器部署主文档 | `deploy/README-部署.md` | 首次搭建、日常部署、环境变量、安全加固、故障排查（含服务器信息，不要外传） |
| 发版上传脚本 | `deploy/publish-update.ps1` | 生成 `latest.json`（version/url/sha256/size/notes）并上传更新频道 |
| 现网 nginx | `scripts/deploy/pickq-nginx.conf` | `pickq.cn`/`www` + Certbot TLS + `/` 反代 `127.0.0.1:3000` + `/updates/`、`/config/`、`/downloads/` 三个 alias |
| 远端 AI 预设源文件 | `scripts/deploy/ai-presets.json` | 11 个 preset + 10 条 deprecated 替代建议 |
| 广场库备份 | `scripts/deploy/backup-plaza.mjs` | better-sqlite3 `backup()` 快照，保留 14 份（**不含托管内容包文件**） |
| 内部设计文档 | `doc/*.md` | `ai-import-spec.md`、`api-spec.md`、`center-spec.md`、`content-package-spec.md`、`study-record-spec.md` 等 |

---

## 2. 后端功能索引表

> 端点统一前缀 `/api`。**Controller 只做 HTTP 层**，业务规则在 Service（`conventions.md` §1.3）。

### 2.1 核心业务

| 功能 | Controller（文件 / 行数） | Service（文件 / 行数） | 关键类 / 辅助 |
| --- | --- | --- | --- |
| **题库 CRUD** | `QuestionBankController.java` 203 | `QuestionBankService.java` 314 | `model/QuestionBank`；`dto/QuestionBank{Create,Update,Response,DetailResponse}`；`dto/HomeOverviewResponse` |
| **主页概览卡带** | `HomeController.java` 27 | `QuestionBankService.getHomeOverview` | `dto/HomeOverviewResponse` |
| **题库合并（多库合一）** | `QuestionBankController` `/merge` | `BankMergeService.java` 401 | 复制题目/材料/图片 + 血缘 `parentKey`，源库不动 |
| **选题另存 / 并入** | `QuestionBankController` `/{id}/questions/selection-copy` | `QuestionBankService` | `dto/QuestionSelectionCopyRequest` |
| **题库分类聚合** | `QuestionBankController` `/{id}/categories` | `QuestionBankService` | `dto/BankCategoriesResponse`（会话筛选项） |
| **录题 / 改题 / 删题** | `QuestionController.java` 85 | `QuestionService.java` 429 | `dto/Question{Create,Update,Detail,Summary}Request/Response`；`OptionItemTypeHandler` |
| **题目列表 / 检索 / 筛选** | `QuestionBankController` `/{id}/questions` | `QuestionBankService`（keyword + questionType/category/topic + scope） | `dto/PageResult`、`util/Paging` |
| **做题用题目（无答案）** | `QuestionBankController` `/{id}/questions/practice` | `QuestionBankService` | `dto/QuestionPracticeResponse`（**不含 answerKeys/analysis**，防剧透） |
| **题号导航盘** | `QuestionBankController` `/{id}/question-nav` | `QuestionBankService` | `dto/QuestionNavItemResponse`；前端 `QuestionNavDock.vue` |
| **单题判题（预览用）** | `QuestionController` `/{id}/answer` | `QuestionService.checkAnswer` | `dto/AnswerRequest`、`AnswerResultResponse` |
| **收藏** | `QuestionController` `/{id}/favorite` | `QuestionService.setFavorite` | `dto/FavoriteRequest` |
| **刷题会话（六模式）** | `PracticeSessionController.java` 55 | `PracticeSessionService.java` 548 | `PracticeSessionMapper`（`FOR UPDATE` 行锁防重复交卷）、`PracticeSessionQuestionMapper`；`dto/Session*` |
| **提交作答（判题 + 记录 + 复习联动）** | `StudyRecordController.java` 105 | `StudyRecordService.java` 611 | `dto/StudyRecordSubmit{Request,Response}`；`model/StudyRecord` |
| **主观题自评** | `StudyRecordController` `/study-records/{id}/self-grade` | `StudyRecordService.selfGrade` / `earnedScore` | `dto/SelfGrade{Request,Response}` |
| **错题本** | `StudyRecordController` `/banks/{id}/wrong-questions` | `StudyRecordService.computeWrongQuestionIds` | `dto/WrongQuestionResponse`（**全仓共用的错题口径**） |
| **学习进度** | `StudyRecordController` `/banks/{id}/progress` | `StudyRecordService.getBankProgress` | `dto/BankProgressResponse` |
| **复习计划（间隔重复）** | `StudyRecordController` `/banks/{id}/review/{due,summary}`、`/questions/{id}/review-suspend`、`/banks/{id}/review-states` | `StudyRecordService.updateReviewState` 等 | `model/ReviewState`、`ReviewStateMapper`；`dto/ReviewDueItemResponse`、`ReviewSummaryResponse`、`ReviewSuspendRequest`、`ReviewEnabledRequest` |
| **学习统计** | `StatsController.java` 34 | `StatsService.java` 332 | `dto/StatsSummaryResponse`（含嵌套 `DailyStat`/`DueDay`）、`StatsDetailResponse` |
| **共享材料（资料分析大题干）** | `MaterialController.java` 45 | `MaterialService.java` 86 | `model/Material`；`dto` 用实体直传（少量例外） |
| **题目图片** | `ImageController.java` 45 | `ImageStorageService.java` 223 | `[图片:文件名]` 引用；存 `{dataDir}/images/{bankId}/{yyMMdd}/{name}` |

### 2.2 内容包与本地发布中心

| 功能 | Controller（文件 / 行数） | Service（文件 / 行数） | 关键类 / 辅助 |
| --- | --- | --- | --- |
| **内容包导入（v1 JSON / v2 .tiku）** | `QuestionBankController` `/import`、`/import-tiku` | `ContentPackageService.java` 599 | `util/PackageContainer.java` 253（魔数判定、打包解包、安全上限）；四态结果 `dto/ImportResultResponse` |
| **内容包导出（JSON / .tiku）** | `QuestionBankController` `/{id}/export`、`/{id}/export-tiku` | `ContentPackageService.exportContentPackage` / `exportTikuPackageWithMeta` | `dto/ExportRequest`；身份/版本决策与 checksum 都在这里 |
| **发布前「体检」** | `CenterPublishController` `/publish/inspect` | `ContentPackageInspector.java` 335 | **只读元数据**，不导入不落库不转发；与官网 `package-meta.ts` 同口径 |
| **导出到本地目录** | `ExportController.java` 127（**全部免登录**） | `LocalExportService.java` 158 | 文件名 `{题库名或packageKey}-{version}.tiku`（清理 Windows 非法字符 / 保留设备名） |
| **导出记录（我的作品·本地侧）** | `ExportController` 列表 / 删除 / 标记已发布 / 下一版本号 | `ExportRecordService.java` 222 | `model/ExportRecord`、`ExportRecordMapper`、`V14__export_records.sql`；补丁号自增 `suggestNextVersion` |
| **导出目录偏好** | `ExportController` `/prefs` | `ExportPrefsService.java` 162 | `{dataDir}/export-prefs.json`；默认目录「文档\拾题」 |
| **一键完整备份** | `BackupController.java` 79 | `BackupService.java` 270 | H2 `SCRIPT TO` 一致性快照 + images/ + ai-config.json + 《恢复说明.txt》，流式 zip |
| **一键恢复** | `BackupController` `/restore-prepare` | `BackupService.prepareRestore` + `RestoreRunner.java` 125 | 安全解压到 `{dataDir}/restore/staged`；壳带 `--tiku.restore-stage` 重启后 `DROP ALL OBJECTS` + `RunScript`，末行打印 `TIKU_RESTORE_OK` |

### 2.3 广场（题库广场 / 内容包中心）

| 功能 | Controller（文件 / 行数） | 依赖 Service / 工具 | 说明 |
| --- | --- | --- | --- |
| **广场只读浏览代理** | `CenterProxyController.java` 144 | `CenterBrowseService` | `/packs`、`/packs/{key}`、`/packs/{key}/comments`、`/authors/{id}` 原样透传；收藏/评论/点赞/关注走 `forwardJson` 写转发 |
| **拉取即导入** | `CenterProxyController` `/import`、`/import-external` | `ContentPackageService`、`util/PackageContainer.isZipContainer` | 按**魔数**分流 v2/v1；外链仅支持 http(s) 直链（网盘网页链接失败 → 前端回退浏览器下载） |
| **应用内发布** | `CenterPublishController.java` 114 | `CenterPublishService`、`CenterHttpClient`、`ContentPackageInspector`、`ExportRecordService` | `/publish`（multipart）、`/publish-from-path`（本地路径，不经前端中转）、`/me/packs`、`DELETE /packs/{key}`、`PUT /packs/{key}/{version}[ /file ]` |
| **发布性能策略** | 同上 | 内部 `Staged` record | ≤4MB 留内存、更大落临时文件；`setFixedLengthStreamingMode` 定长流式写（200MB 只有 64KB 级内存峰值）；体检与转发**共用同一次暂存** |
| **广场账号（桌面端）** | `CenterAuthController.java` 345 | `CenterAuthStore.java` 64、`CenterHttpClient` | 登录/注册/登出/me/status + GitHub 回环登录（`/github/start`、`/github/callback`）；token 存 `{dataDir}/center-auth.json`，**前端不接触** |
| **AI 模型预设远端化** | `AiConfigController.java` 113（**免登录**） | `AiPresetService.java` 123、`AiModelCatalogService.java` 256 | `GET /api/ai/presets`（原样透传 + 1h 内存缓存）、`POST /api/ai/models`（探测可用模型列表，响应不含 Key） |

### 2.4 AI 链路

| 功能 | Controller（文件 / 行数） | Service（文件 / 行数） | 关键类 / 辅助 |
| --- | --- | --- | --- |
| **AI 导入任务（建/查/列表/删/SSE/确认）** | `AiImportController.java` 168 | `AiImportService.java` **4155** + 14 个 `AiImport*` 组件 | `model/AiImportJob`、`mapper/AiImportJobMapper`（`FOR UPDATE` 保证 confirm 幂等）；`dto/AiJobResponse`（含 `errorCode`）、`AiImportConfirmRequest/Response` |
| **任务事件流（SSE）** | `AiImportController` `/{id}/stream` | `AiJobEventService.java` 75 | 与轮询并存；断开自动回退 |
| **文档解析（本地，全离线）** | — | `DocumentParserService.java` 814 | txt/md 直读、docx→POI（含 MathType WMF/EMF 预览图转 PNG）、pdf→PDFBox（文本层 + 内嵌图 + 按页渲染）、图片直读 |
| **MinerU 云端解析** | — | `MineruParseService.java` 1259 | mineru.net Standard API：上传 → 轮询 → 下载 zip → 用 `content_list_v2.json` 重建「增强文本」；失败自动回退本地 |
| **AI 输出 → 题目结构（确定性解析）** | — | `MdQuestionParser.java` 340 | Markdown 模板 → 题目；替代分块路径的 JSON 解析（题号取自标题，免源文回填） |
| **答案格式识别** | — | `AiAnswerFormat.java` 160 | 兼容公考样式（`1.B`、`答案：B`）与学科卷样式（`【1题答案】B`、区间式 `1-8：B D C…`） |
| **模型客户端** | — | `AiClientService.java` 202 | OpenAI 兼容：文本 + 多模态 data URL、`response_format` 降级、Key 仅进 Authorization 头且不写日志 |
| **BYOK 配置存取** | `AiImportController` `/ai/settings*` | `AiConfigService.java` 121 | `{dataDir}/ai-config.json`；Key 脱敏 `sk-***abc`；地址规则 http 仅本机/私网 |
| **AI 批量补答案** | `QuestionBankController` `/{id}/questions/ai-fill-answers` | `AnswerFillService.java` 307 | 串行 10 题/批思考模式；无法确定一律留空（不写库） |
| **单题 AI 解析 / 草稿** | `QuestionController` `/{id}/ai-analysis`、`/ai-analysis-draft`、`/{id}/analysis` | `QuestionService`（`ReentrantLock` 限并发槽） | `dto/AnalysisDraftRequest`、`SaveAnalysisRequest`；前端 `QuestionAiAnalysis.vue` |

### 2.5 基础设施

| 事项 | 文件 | 职责 |
| --- | --- | --- |
| 统一响应体 | `dto/ApiResponse.java`（11） | `{code,data,message}`；`success`/`error` 两个静态工厂 |
| 分页响应 | `dto/PageResult.java` | `IPage` → 统一分页形状，列表接口都用它包一层 |
| 异常 → 状态码映射 | `controller/GlobalExceptionHandler.java` 139 | 404/405/400/500/204 的统一出口；**错误文案即契约**（见 `conventions.md` §1.2） |
| 启动与父进程守护 | `TikuApplication.java` 45 | `@MapperScan`；`-Dtiku.watch-parent=true` 时启动 2s 轮询的 daemon 守护线程 |
| 数据目录初始化 | `config/DataDirectoryConfig.java` 30 | `@PostConstruct` 建目录并打日志 |
| SPA 回退 | `config/SpaForwardConfig.java` 56 | 无扩展名且非 `/api` → `index.html`；类注释标了「约束（勿破坏）」 |
| 线程池 | `config/AsyncConfig.java` 38 | `aiImportExecutor`（单线程）+ `aiChunkExecutor`（3 路 daemon） |
| 分页参数钳制 | `util/Paging.java` 20 | `page ≥ 1`、`size ∈ [1,100]`（越界钳制而非报错） |
| 本机/私网判定 | `util/NetAddress.java` 117 | 与前端 `utils/netAddress.js` 同口径 |
| 逻辑删除 | `model/Question.java` 的 `@TableLogic deleted` | 单题删除是 UPDATE；删库时题库物理删除、题目逻辑删除 |

---

## 3. 前端页面索引表

### 3.1 路由页面（`frontend/src/views/`，11 个，合计 17,492 行）

| 路由 | name | 文件 | 行数 | 职责 | 主要子组件 |
| --- | --- | --- | --- | --- | --- |
| `/` | `bank-list` | `BankListView.vue` | **1347** | 首页：概览卡带（最近练习 / 今日待复习）、题库分页 + 名称筛选、新建题库、合并题库、三种导入入口（AI / JSON / .tiku）、首次使用引导 | `TikuIcon`、`AiImportDialog` |
| `/banks/:id` | `bank-detail` | `BankDetailView.vue` | **2848** | 题库详情与**题库级全部操作**：信息编辑、导出、删除、复习计划开关、复习队列/重置、进度与错题、题目检索分页与收藏、题号盘、批量选择另存/并入、批量建题、AI 补答案、材料 CRUD、图片上传、开练习会话、打印入口 | `TikuIcon`、`QuestionFormPanel`、`QuestionNavDock`、`AiImportDialog`、`QuestionAiAnalysis` |
| `/banks/:id/practice` | `practice` | `PracticeView.vue` | **1822** | 做题页（会话制）：计时、逐题作答、收藏、暂停复习、交卷 → 成绩报告、报告内主观题自评、进入回顾 | `TikuIcon`、`QuestionAiAnalysis` |
| `/banks/:id/sessions` | `session-history` | `SessionHistoryView.vue` | **1016** | 双视图：历史列表（继续练习）/ 会话回顾（每题对错、答案、解析、材料折叠、自评、题号状态盘） | `TikuIcon`、`QuestionAiAnalysis`、`QuestionNavDock` |
| `/banks/:id/print` | `print-paper` | `PrintPaperView.vue` | 528 | **独立页面（无侧栏）**：按范围（全部/错题/收藏/未做）+ 分类本地过滤渲染，切换`仅题目/含答案解析`，`window.print()` | `TikuIcon` |
| `/ai-import/jobs` | `ai-import-jobs` | `AiImportJobsView.vue` | 414 | AI 导入任务列表：5s 轮询、状态/耗时展示、进入预览、两阶段取消/删除 | `TikuIcon` |
| `/ai-import/:jobId` | `ai-import-preview` | `AiImportPreviewView.vue` | **2341** | 预览与确认：进度（轮询兜底）、题目/材料卡片就地编辑、拖拽排序、图片素材拖入/删除标记、确认入库或取消 | `TikuIcon`、`FieldImages`、`QuestionNavDock` |
| `/stats` | `stats` | `StatsView.vue` | **1213** | 学习统计：KPI、日热力图、复习健康、正确率趋势（ECharts 按需引入）、题库掌握度、错题治愈、最近练习 | `TikuIcon`、`StatsHeatmap` |
| `/discover` | `discover` | `DiscoverView.vue` | **1730** | 题库广场：列表/详情/作者三视图、搜索排序分页、评论与点赞、收藏、一键导入、广场账号体系（登录/注册/GitHub OAuth 轮询/Turnstile） | `TikuIcon` |
| `/my-works` | `my-works` | `MyWorksView.vue` | **2574** | 我的作品：本地题库 / 已导出文件 / 已发布作品三区；导出到目录、发布三入口（本地题库 / 导出记录 / 手动选文件）、作品编辑/补传/下架 | `TikuIcon` |
| `/settings` | `settings` | `SettingsView.vue` | **1659** | 设置 6 个 panel：备份恢复、外观与语言、记录导入导出、AI 模型配置（含预设/动态模型列表/下线自愈）、作者信息、关于与更新 | `TikuIcon` |

`router/index.js`（90 行）：`createWebHistory`；全部页面懒加载；**无 `beforeEach` / 无鉴权守卫**，只有 `afterEach` 设 `document.title`（`${meta.title} · 拾题`，meta.title 是中文硬编码，**不随语言切换**）。

### 3.2 布局与全局组件

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `layouts/AppLayout.vue` | 660 | 唯一外壳：品牌印、6 个导航、主题快捷切换；AI 任务全局监控（5s 轮询 + 从 active 消失时查终态并 `new Notification`）；侧栏折叠由路由正则与视口宽度共同决定 |
| `App.vue` | 519 | 根组件：`<el-config-provider>` + `<router-view>` + 2 个 `Teleport to="body"`（图片灯箱、更新下载弹窗）；文档级事件委托（外链→系统浏览器、`img.rich-img`→灯箱）；启动后自动检查更新（`tiku:skip-update-version` 可跳过） |
| `main.js` | 26 | 注册 Element Plus / i18n / router；`initTheme()` → `initLang()` |

### 3.3 复用组件（`frontend/src/components/`，7 个，合计 3330 行）

| 文件 | 行数 | 职责 | 接口 |
| --- | --- | --- | --- |
| `QuestionFormPanel.vue` | **1360** | 录题/编辑题面板：题型、题干、选项、答案、解析、材料选择/新建、图片上传插入 `[图片:name]`、实时富文本预览、AI 草稿解析、创建/更新、上一题/下一题、未保存离开确认 | props `bankId` / `mode` / `initial` / `nextNumber` / `nav` / `jumpRequest`；emits `saved` / `closed` / `navigate` / `jump-to` |
| `AiImportDialog.vue` | 849 | AI 导入对话框：校验 AI 配置 → 选文件（多选可逐个移除）→ 目标题库 → 处理模式（快速/标准/深度）→ 补充答案与思考开关 → 提交并订阅 SSE（**8s 无事件回退轮询**） | props `bankId` / `bankName`；emits `done`；`defineExpose({ open })` |
| `QuestionNavDock.vue` | 427 | 可拖动题号盘：题号按钮 + 状态色（对/错/部分/未答）、跳转输入、三档尺寸、位置与尺寸持久化到 localStorage | props `title`/`items`/`activeId`/`showLegend`/`hint`/`draggable`/`storageKey`/`activeFill`；emits `select` |
| `StatsHeatmap.vue` | 285 | 每日做题量热力图：30 天/90 天/全年三视图、分级上色、点击展开当日题量与正确率 | props `daily` |
| `QuestionAiAnalysis.vue` | 222 | 单题 AI 解析：生成/重新生成、保存为正式解析、`autoStart`、模型失效时引导去设置页 | props `questionId`/`bankId`/`autoStart`；emits `saved`；`defineExpose({ generate })` |
| `FieldImages.vue` | 127 | 把 `obj[field]` 文本里的 `[图片N]` 渲染为任务临时图片缩略图（可预览、悬停删除该编号引用） | props `obj`/`field`/`jobId` |
| `TikuIcon.vue` | 60 | **无依赖内联 SVG 图标组件**（24×24 stroke，随 `currentColor`），内置 30 个图标；全仓唯一图标来源（不引入图标库） | props `name`/`size`/`filled` |

---

## 4. 官网索引表（`web/`，不入 git）

### 4.1 页面（`web/pages/**`，14 个，合计约 7297 行）

| 路由 | 文件 | 行数 | 职责 |
| --- | --- | --- | --- |
| `/` | `pages/index.vue` | **710** | 落地页：Hero + 界面 mockup + 场景 + 原理 + 分享三步 + 下载区（`#download` 锚点，链接指向站内 `/downloads/*`） |
| `/guide` | `pages/guide.vue` | 274 | 发布指南（导出 / 上传发布 / 分享维护）+ FAQ |
| `/login` | `pages/login.vue` | 232 | 登录（用户名或已验证邮箱 + 密码）、GitHub 整页跳转、读 `?error=github\|banned` |
| `/register` | `pages/register.vue` | 340 | 注册 + 可选 Turnstile + GitHub 入口 |
| `/forgot` | `pages/forgot.vue` | 235 | 忘记密码（只展示后端统一提示，防账号枚举） |
| `/reset` | `pages/reset.vue` | 260 | 凭邮件一次性 token 重置密码 |
| `/packs` | `pages/packs/index.vue` | 541 | 广场列表：新/热门、搜索、分页；发表前必读公告 |
| `/packs/new` | `pages/packs/new.vue` | 551 | 网页发布：选/拖 `.tiku`/`.json` → 前端解包读元数据（`fflate` 动态导入）→ HOSTED 直传 / EXTERNAL 前端算 `fileSha256` 后登记 |
| `/packs/:packageKey` | `pages/packs/[packageKey].vue` | **1057** | 作品页：最新版 + 版本历史 + 衍生 + 作者摘要 + 收藏 + 下载（先计数再跳转）+ 评论/有帮助/删除 + 上报问题 |
| `/me` | `pages/me/index.vue` | 551 | 我的作品：编辑元数据、补传托管文件、下架、恢复上架 |
| `/me/profile` | `pages/me/profile.vue` | 599 | 编辑资料、邮箱绑定、登录密码（设置 vs 修改） |
| `/me/favorites` | `pages/me/favorites.vue` | 318 | 我的收藏（标注已下架、可取消收藏） |
| `/authors/:id` | `pages/authors/[id].vue` | 353 | 作者主页：资料 + 作品分页 + 关注/取关 + `isSelf` 入口 |
| `/admin` | `pages/admin.vue` | **1276** | 治理后台：举报列表与处置（联动下架 / 处置备注 / 邮件通知开关）、用户管理（搜索/封禁/重置密码，掩码邮箱） |

### 4.2 服务端端点（`web/server/api/**`，42 个文件，约 1867 行）

> Nitro 文件路由 = URL。**没有** `server/routes/**`、没有 `server/middleware/**`、没有 `server/plugins/**`。
> 鉴权三级：匿名 / `requireUser(event)` / `requireUser` + `isAdminUser`（`conventions.md` §4.3）。

| 分组 | 文件 → 方法 + URL | 说明 |
| --- | --- | --- |
| **认证** | `auth/login.post.ts` → `POST /api/auth/login` | 用户名**或已验证邮箱** + 密码；IP 20/分 + 账号 10/分双限流；`X-Desktop: 1` 时响应体额外返回 token |
| | `auth/register.post.ts` → `POST /api/auth/register` | 用户名 2–24 位 `[\p{L}\p{N}_-]`、密码 6–128；IP 5 次/10 分 + 可选 Turnstile |
| | `auth/logout.post.ts` → `POST /api/auth/logout` | 删会话行 + 清 cookie |
| | `auth/me.get.ts` → `GET /api/auth/me` | 当前用户 + `isAdmin`（对外只在这里暴露管理员身份） |
| | `auth/forgot.post.ts` → `POST /api/auth/forgot` | 发重置邮件；账号不存在/未验证/发信失败**一律同一提示**（防枚举）；token 30 分钟 |
| | `auth/reset-password.post.ts` → `POST /api/auth/reset-password` | 单次 token 重置 + 清空该用户全部会话（同事务） |
| | `auth/verify-email.get.ts` → `GET /api/auth/verify-email?token=` | 验证落地，成败均 302 `/me/profile?email=ok\|fail` |
| | `auth/github/start.get.ts` → `GET /api/auth/github/start` | 302 跳 GitHub；state 存 httpOnly cookie（10 分钟）；`desktop=1` 时严格白名单校验回环回调 |
| | `auth/github/callback.get.ts` → `GET /api/auth/github/callback` | 校验 state → 换 token → 按 `github_id` / 已验证邮箱 / 新建无密码账号解析账号；网页模式建会话并 302 到 `next`，桌面模式签一次性 ticket 302 回本地回环 |
| | `auth/desktop-exchange.post.ts` → `POST /api/auth/desktop-exchange` | 桌面 GitHub 登录第二步：一次性 ticket（60 秒、单次、库里只存 sha256）换 session token；失败**统一 400** 不区分原因 |
| **内容包 / 广场** | `packs/index.get.ts` → `GET /api/packs` | 列表（同 `package_key` 只出最新 ACTIVE 版）；`sort=new\|hot`、`size ≤ 50` |
| | `packs/index.post.ts` → `POST /api/packs` | 登记（兼容/脚本路径）：校验 manifest；EXTERNAL 必须有 http(s) 外链、HOSTED 不得带外链；冲突 409（同作者 REMOVED → 重新上架） |
| | `packs/upload.post.ts` → `POST /api/packs/upload` | **直传发布主路径**：multipart ≤200MB → 服务端解析元数据自动登记；HOSTED 落托管、EXTERNAL 弃文件；`checksum` 记 NULL（Node 复刻不了 Jackson 字段序），凭据用 `file_sha256`；0 题拒收；账号 10/分限流 |
| | `packs/[packageKey].get.ts` → `GET /api/packs/{key}` | 作品页聚合：最新版 + 全部版本 + 衍生 + 作者摘要 + 我的收藏态；区分「未找到」与「已下架」 |
| | `packs/[packageKey].delete.ts` → `DELETE /api/packs/{key}` | 作者下架整包（仅本人） |
| | `packs/[packageKey]/[version].get.ts` → `GET /api/packs/{key}/{version}` | 指定版本详情 + 作者公开信息 |
| | `packs/[packageKey]/[version].put.ts` → `PUT /api/packs/{key}/{version}` | 作者更新元数据（description/source/downloadUrl）；标题与身份不可改；HOSTED 禁填外链 |
| | `packs/[packageKey]/[version]/file.get.ts` → `GET .../file` | 匿名下载托管文件流；RFC 5987 双写中文名；返回 `X-Checksum-File`；**本端点不计下载数** |
| | `packs/[packageKey]/[version]/file.put.ts` → `PUT .../file` | 托管文件补传（作者本人）：大小与登记一致、`file_sha256` 必须一致、文件内 `packageKey`/`version` 与登记一致；已上传过 409 |
| | `packs/[packageKey]/restore.post.ts` → `POST /api/packs/{key}/restore` | 恢复整包上架；HOSTED 且文件已删时回 `hostedNeedsUpload: true` |
| | `packs/[packageKey]/favorite.get.ts` / `favorite.post.ts` | 收藏态查询 / 收藏切换（用户 60/分） |
| | `packs/[packageKey]/download-clicks.post.ts` → `POST .../download-clicks` | 下载计数 +1 并返回目标 URL（HOSTED→本站 file 端点，EXTERNAL→作者外链）；IP+key 20/分 |
| | `packs/[packageKey]/comments.get.ts` / `comments.post.ts` / `comments/[id].delete.ts` / `comments/[id]/like.post.ts` | 评论列表（匿名可读，最多 200 条）/ 发表（≤500 字）/ 删除（本人或管理员，连带清点赞）/ 「有帮助」单向上赞 |
| **作者与举报** | `authors/[id].get.ts` → `GET /api/authors/{id}` | 作者主页：资料 + 作品分页 + 粉丝/关注数 + `isFollowing`/`isSelf` |
| | `authors/[id]/follow.post.ts` → `POST /api/authors/{id}/follow` | 关注/取关（禁止自关注 400；60/分） |
| | `reports.post.ts` → `POST /api/reports` | 举报/失效上报（匿名，IP 10/分；`LINK_DOWN`/`COPYRIGHT`/`OTHER`） |
| **我的** | `me/packs.get.ts` / `me/favorites.get.ts` | 我的作品（含 REMOVED + `versionCount`）/ 我的收藏 |
| | `me/profile.put.ts` | 昵称 ≤20、简介 ≤300 |
| | `me/password.get.ts` / `me/password.put.ts` | 登录方式状态（`hasPassword`/`hasGithub`/`email`/`emailVerified`）/ 设置或修改密码 |
| | `me/email.post.ts` | 绑定/换绑邮箱：**不直接改 `users.email`**，待验证邮箱存 `auth_tokens.email`（24h token）；用户 3 次/10 分 |
| **管理** | `admin/reports.get.ts` / `admin/reports/[id]/handle.post.ts` | 举报列表（附掩码邮箱）/ 处置（RESOLVED/DISMISSED + 可选下架 + 备注 + 邮件通知） |
| | `admin/users.get.ts` / `admin/users/[id]/ban.post.ts` / `admin/users/[id]/password.post.ts` | 用户列表（含三计数）/ 封禁解封（即时生效）/ 重置密码（清空该用户全部会话） |
| | `admin/packs/[packageKey]/remove.post.ts` | 管理员强制下架（与作者下架同一路径） |

### 4.3 服务端工具（`web/server/utils/**`，10 个，726 行）

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `auth.ts` | 76 | 密码 scrypt 哈希/校验、会话创建、cookie 读写清、`getSessionUser`（**Bearer 优先，cookie 兜底**）、`requireUser` |
| `api.ts` | 49 | `okResp`/`apiError`（`{code,data,message}`）、`maskEmail`（**任何接口都不得返回邮箱原文**）、`toPackPublic`（snake→camel 的唯一收口点） |
| `github.ts` | 285 | GitHub OAuth 工具：配置判定、`siteBase`（`PUBLIC_BASE_URL` → 请求头 → 兜底）、state cookie、`safeNext`（防开放重定向）、**桌面回环回调白名单**、code→token、拉资料/主邮箱。**不是** GitHub Release 代理 |
| `hosted.ts` | 53 | 托管文件存储适配层 `hostedStore{put,has,stream,remove}`；键 `packs/{fileSha256}.tiku`；`HOSTED_DIR` 默认 `<cwd>/data/hosted` |
| `package-meta.ts` | 55 | 内容包元数据解析（与桌面端 `ContentPackageInspector` 同口径）：zip 魔数、只取 `package.json`、条目/大小上限 |
| `mailer.ts` | 131 | 发信：Resend HTTP API 优先，回退 SMTP（nodemailer）；超时 10s |
| `turnstile.ts` | 24 | Cloudflare Turnstile `siteverify`；未配置 `TURNSTILE_SECRET` 直接放行 |
| `removal.ts` | 21 | 下架公共逻辑：置 REMOVED + 无其他 ACTIVE 引用时物理删托管文件 + 清托管登记 |
| `ratelimit.ts` | 20 | **进程内内存**限流 `rateLimit(key,max,windowMs)`（注释自认单实例） |
| `admin.ts` | 12 | 管理员判定：`ADMIN_USERNAMES` 白名单（不落库、无角色表） |

### 4.4 数据库层（`web/server/db/**`）

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `migrate.ts` | 265 | `MIGRATIONS` 数组（`001_init` … `010_report_handle_note`）+ `schema_migrations` 去重；`openDb()` 设 `journal_mode=WAL`、`foreign_keys=ON` 并调用 `migrate()`；`defaultDbPath() = DB_PATH ?? <cwd>/data/plaza.db` |
| `repos.ts` | **863** | 7 个 repo：`userRepo` / `followRepo` / `sessionRepo`（滑动续期 +30 天）/ `authTokenRepo`（只存 sha256）/ `packRepo`（窗口函数取每作品最新版、`hot` 排序）/ `favoriteRepo` / `commentRepo`（含点赞）/ `reportRepo`；模块级惰性单例 `db()` |

**迁移触发时机（重要）**：`openDb` 的唯一调用点是 `repos.ts` 的惰性单例 → **迁移在「首次有请求触达数据库」时同步执行**，不是启动钩子、没有 npm 脚本、部署步骤里也没有（`web/package.json` 只有 `dev`/`build`/`preview`/`postinstall`）。
生产环境首次请求会同步跑完所有未应用迁移（其中 `006_checksum_nullable` 会重建 `packs` 表）。

---

## 5. 公共设施索引

### 5.1 后端工具类 / 公共设施

| 设施 | 文件 | 被谁用 | 备注 |
| --- | --- | --- | --- |
| 统一响应 | `dto/ApiResponse.java` | 全部 controller | 前端 `http.js` 只认 `code === 200` |
| 分页形状 | `dto/PageResult.java` | 列表类接口 | — |
| 分页钳制 | `util/Paging.java` | 所有分页 service | 越界钳制而非报错 |
| 异常映射 | `controller/GlobalExceptionHandler.java` | 全局 | 状态码语义见 `conventions.md` §1.2 |
| 内容包容器 | `util/PackageContainer.java` | `ContentPackageService`、`ContentPackageInspector`、`CenterProxyController`、`CenterPublishController` | **跨端契约的实现层**，改动须与官网 `package-meta.ts` 同步 |
| 本机/私网判定 | `util/NetAddress.java` | `AiConfigService`、`AiConfigController`、`AiModelCatalogService` | 与前端 `utils/netAddress.js` 双实现同口径 |
| options JSON 转换 | `model/handler/OptionItemTypeHandler.java` | `Question.options` | MyBatis 类型处理器 |
| 时间 | 各 service 显式 `LocalDateTime.now()` | — | 无 `MetaObjectHandler`（为了导入时保留原时间） |
| 行锁 | `mapper/PracticeSessionMapper`、`mapper/AiImportJobMapper` | 交卷、确认导入 | 唯一两处 `FOR UPDATE` 注解 SQL |

### 5.2 前端公共设施

| 设施 | 文件 | 行数 | 说明 |
| --- | --- | --- | --- |
| HTTP 封装 | `api/http.js` | 40 | 唯一 axios 实例：`baseURL: '/api'`、`timeout: 15000`；响应拦截解包 `{code,data,message}`；错误分支支持 `config.skipErrorMessage` 跳过全局 `ElMessage` |
| 图标组件 | `components/TikuIcon.vue` | 60 | 30 个内联 SVG；**全仓唯一图标来源**（不引入图标库） |
| 富文本渲染 | `utils/richText.js` | 163 | 转义 + `[图片:name]` → `<img src="/api/banks/{bankId}/images/...">` + KaTeX（含 MinerU 无定界片段，失败回退源码）+ `<table>` 白名单清洗 |
| 文件读写 | `utils/files.js` | 107 | 桌面走 Tauri（`save_dialog_file` / `pick_directory`），浏览器退回 `<a download>` / 隐藏 `input[type=file]` |
| 桌面更新 | `utils/updater.js` | 90 | `isDesktop` / `app_version` / `check_update` / `download_update` / `cancel_update` / `install_update`；订阅 `shiti://update-progress` |
| 外链与本地目录 | `utils/external.js` | 44 | 桌面走 Rust `open_url` / `open_directory`，浏览器退回 `window.open` |
| 本机地址判定 | `utils/netAddress.js` | 81 | 与后端 `NetAddress.java` 同口径 → 决定本地模型是否免填 Key |
| 主题 | `utils/theme.js` | 55 | 三态（system/light/dark）→ 切 `html.dark` 联动 Element Plus 暗色变量 |
| 模型错误自愈 | `utils/aiModelHelp.js` | 139 | 识别「模型不存在/已下线」→ 查 `deprecated` 表给替代 → 弹窗引导去设置页 |
| 广场地址 | `utils/center.js` | 22 | `CENTER_URL = 'https://pickq.cn'`（硬编码，忽略历史 localStorage 覆盖值） |
| 格式化 | `utils/format.js` | 22 | 日期（空值 `—`）与分数（整数 / 保留 1 位） |
| i18n 实例 | `i18n/index.js` | 36 | legacy:false、globalInjection、`fallbackLocale: 'zh-CN'`；**全局字典为空** |
| i18n 基建 | `i18n/lang.js` | 50 | `currentLang`（ref）、`elLocale`（computed）、`setLang`、`initLang`、`getLangPref` |
| 全局样式 | `styles/main.css` | 502 | 设计令牌（纸/墨/朱）+ 全局基础样式（含「对错需图标+文字双编码」） |
| 启动防闪屏 | `frontend/index.html` | 28 | 内联 IIFE 先按 `localStorage['tiku:theme']` 给 `<html>` 加 `dark` |

**i18n 字典分布**（无常量 locale 文件；每个组件自带局部字典，zh-CN 侧约 1034 个 key 节点，en-US 同量）：

| 文件 | 顶层 key | 含嵌套合计 |
| --- | --- | --- |
| `views/MyWorksView.vue` | 193 | 193 |
| `views/DiscoverView.vue` | 112 | 112 |
| `views/BankDetailView.vue` | 108 | 108 |
| `views/AiImportPreviewView.vue` | 70 | 70 |
| `views/StatsView.vue` | 58 | 58 |
| `components/AiImportDialog.vue` | 43 | 43 |
| `views/PracticeView.vue` | 42 | 42 |
| `components/QuestionFormPanel.vue` | 34 | 34 |
| `views/BankListView.vue` | 32 | 32 |
| `layouts/AppLayout.vue` | 23 | 32 |
| `views/SessionHistoryView.vue` | 23 | 23 |
| `views/AiImportJobsView.vue` | 22 | 22 |
| `views/PrintPaperView.vue` | 20 | 20 |
| `App.vue` | 19 | 19 |
| `components/QuestionAiAnalysis.vue` | 16 | 16 |
| `components/QuestionNavDock.vue` | 13 | 13 |
| `components/StatsHeatmap.vue` | 11 | 11 |
| `views/SettingsView.vue` | 8 | 134 |
| `components/TikuIcon.vue` / `FieldImages.vue` | 0 | 0（无文案） |

### 5.3 官网站点公共设施

| 设施 | 文件 | 说明 |
| --- | --- | --- |
| i18n | `composables/useSiteI18n.ts`（89） | 模块级 `siteLang` ref + cookie `pickq:lang` / localStorage 双写；SSR **每请求重置**；`useSiteT(messages)` → `{t, lang, toggle}`，`{n}` 插值 |
| 当前用户 | `composables/useMe.ts`（66） | SSR 用 `useRequestFetch()('/api/auth/me')` 转发 cookie；`useState('pickq-me-user'/'pickq-me-admin')`；`refresh()` 带去重 |
| 布局 | `layouts/default.vue`（340） | 顶栏（品牌印、导航、账号区、中英切换、下载按钮）+ slot + 页脚 |
| 品牌元素 | `components/BrandSeal.vue`（84）、`components/ShiTiSeal.vue`（48） | 纯 CSS 竖排方印 / SVG 朱印（`feTurbulence` 毛边，`useId()` 唯一化 filter id） |
| 响应与映射 | `server/utils/api.ts`（49） | `okResp`/`apiError`/`maskEmail`/`toPackPublic` |
| 鉴权 | `server/utils/auth.ts`（76） | session cookie `pickq_session`（httpOnly + Lax + 30 天滑动）/ Bearer |

### 5.4 观察清单：多处重复、建议抽公共层（**只列观察，不改代码**）

| # | 现象 | 位置 | 建议（供讨论） |
| --- | --- | --- | --- |
| 1 | ~~三个 `checkBase()` + 远端错误提取几乎一模一样~~ **已部分解决（2026-09-11）**：Center 三兄弟已共用 `CenterUrlPolicy`（地址校验）+ `CenterHttpClient`（Bearer 附加、超时、远端错误解析、multipart 转发） | 仍各自持有 `checkBase`/`getText` 的是 `AiConfigController` 与 `AiPresetService` | 把这两个也切到 `CenterUrlPolicy` / `CenterHttpClient`，彻底收敛为一份口径 |
| 2 | ~~三套 `HttpURLConnection` 转发样板~~ **已解决（2026-09-11）**：`getText` / `getBytes` / `forwardJson` / `forwardMultipart` 已统一到 `CenterHttpClient`，multipart 骨架由 `CenterPublishService` 的 `Staged` 复用 | `CenterHttpClient.java`（277）、`CenterPublishService.java`（304） | 保持；后续若有第二个使用方再考虑把 multipart 骨架独立成 `MultipartSkeleton` |
| 3 | **`NetAddress` 双实现**（Java 与 JS 各一份，需人工保持一致） | `src/main/java/com/tiku/util/NetAddress.java`（117）↔ `frontend/src/utils/netAddress.js`（81） | 要么生成一份共享规则表（JSON）由两端读取，要么在两端各加同一批用例的测试（现状：`NetAddressTest.java` 存在，前端无测试） |
| 4 | **AI 任务轮询/订阅逻辑分散在 4 处** | `AppLayout.vue`（5s 轮询 + Notification）、`AiImportJobsView.vue`（5s 轮询）、`AiImportDialog.vue`（SSE + 8s 看门狗回退）、`AiImportPreviewView.vue`（轮询兜底） | 抽 `composables/useAiJob(jobId)`（前端目前**没有** `composables/` 目录）；把「SSE 优先 + 看门狗回退轮询 + 终态处理」收口一处 |
| 5 | **桌面判定 `window.__TAURI_INTERNALS__` 写了三遍** | `utils/updater.js:10`、`utils/external.js:9`、`utils/files.js:20,59` | 统一到一个 `utils/platform.js`（`isDesktop()` / `invoke` 包装）；`files.js` 直接摸 `__TAURI_INTERNALS__` 而不经 `@tauri-apps/api`，Android 端必然要替换这一层 |
| 6 | **视图绕过 `api/` 直连**：`DiscoverView.vue` 13 处、`MyWorksView.vue` 15 处 `import http` 调 `/center/*` 与 `/exports/*` | 两个视图文件 | 补 `api/center.js`、`api/exports.js`（`utils/center.js` 只有 URL 常量）。这违反 `conventions.md` §3.3 的「一个后端资源一个文件」 |
| 7 | **i18n 字典无共享出口**：18 个组件的局部字典、约 1034 个 zh key + 等量 en key，无自动校验 | 全部 `*.vue` | 生成共享 JSON 资源 + 一个 key 集合对比脚本（`conventions.md` §7 明确把这件事列为「很受欢迎的贡献」） |
| 8 | **官网 `pages/index.vue` 重复调用 `useSeoMeta` 两次**（参数完全相同） | `web/pages/index.vue` L188–191 与 L193–196 | 删除一处 |
| 9 | **`web/server/db/repos.ts` 单文件 7 个 repo（863 行）** | `web/server/db/repos.ts` | 按 rep o 拆文件（`repos/` 目录）；现状改一处要滚 800 行 |
| 10 | **后端 `/api` 顶层挂载风格不统一** | `AiImportController`、`PracticeSessionController`、`StudyRecordController` 是 `@RequestMapping("/api")` 后在方法上写全路径；其余 controller 是 `@RequestMapping("/api/xxx")` + 方法相对路径 | 统一为后者（前者让「路径散在方法上」，`grep` 端点时不直观） |
| 11 | **测试工具类混在测试树里** | `src/test/java/com/tiku/GenerateSampleFiles.java`（344） | 属样例生成器而非测试；建议移到 `tools/` 或加 `@Disabled` 说明 |
| 12 | **前端无测试基建**：`devDependencies` 有 `playwright-core` 但仓库内无任何 spec 文件；`package.json` 无 test 脚本 | `frontend/package.json` | 要么补齐 e2e，要么移除该依赖（`conventions.md` §8.3 已记录该债务） |
| 13 | **`GlobalExceptionHandler` 构造注入了 `View error` 但从未使用** | `controller/GlobalExceptionHandler.java:23-27` | 死依赖，可删（`data-model.md` §1.6 已记录） |
| 14 | **两套迁移机制 + 两套 i18n + 两个内容包解析器** | Flyway ↔ `MIGRATIONS`；vue-i18n ↔ `useSiteT`；`ContentPackageInspector` ↔ `package-meta.ts` | 都是「两端独立演进」的必然代价，**不建议强行统一**；但至少让内容包解析器的上限常量（4096 / 10MB / 200MB）有一处共同出处 |

---

## 6. 大文件清单（拆分候选）

### 6.1 后端（`src/main/java/com/tiku/**`，前 10）

| # | 文件 | 行数 | 职责 | 建议 |
| --- | --- | --- | --- | --- |
| 1 | `service/MineruParseService.java` | 1259 | MinerU 客户端：上传、轮询、下载 zip、`content_list_v2.json` → 增强文本（表格 HTML、公式 LaTeX、图片提取） | 建议拆「HTTP 客户端 / 结果重建 / 图片与素材提取」三层 |
| 2 | `service/AiImportModelCallService.java` | **1128** | AI 导入的三条模型调用路径（文本分块 `chatChunked` / 视觉单次 `chatVisionSingle` / 视觉分页 `chatVisionPages`）：分块规划、并行调用、重试与合并 | 2026-09-12 从 `AiImportService` 迁出；若继续拆，建议按「文本路径 / 视觉路径」分两个类 |
| 3 | `service/AiImportService.java` | **947** | AI 导入**编排**：`executeJob`（310 行流程编排）、`confirmImport`（确认入库）、`createJob`（建任务并提交执行器）、预览校验、控制器门面 | 已是编排层：新增业务规则请落在对应协作组件，不要再往这里堆 |
| 4 | `service/AiImportVisionLayoutService.java` | **905** | PDF 版面算法：图形块检测、坐标换算、按带裁剪、选项格切分、占位符→图片分配 | 2026-09-12 从 `AiImportService` 迁出；只做版面计算（不读任务表、不发 AI 请求），可脱离模型单测 |
| 5 | `service/DocumentParserService.java` | 814 | 本地解析：txt/md/docx(POI)/pdf(PDFBox)/图片；PDF 文本层清洗（页眉页脚、折行、孤立题号） | 建议按格式拆（`DocxParser` / `PdfParser` / `ImageParser`），清洗规则单独成类并配测试 |
| 6 | `service/ContentPackageService.java` | 613 | 导入四态决策、导出身份/版本决策、checksum 规范化、图片收集、材料映射 | 建议把「checksum 规范化」与「导出决策」拆出，便于单测（当前只能整类测） |
| 7 | `service/StudyRecordService.java` | 611 | 提交作答、判题、错题口径、进度、复习调度、记录导入导出与复习重建 | 建议把「复习调度」与「记录文件导入导出」拆出（两者都可独立测试） |
| 8 | `service/PracticeSessionService.java` | 548 | 六种模式抽题、材料整组、会话详情、交卷报告与用时统计 | 建议把「抽题策略」按 mode 拆成策略类 |
| 9 | `service/QuestionService.java` | 429 | 题目 CRUD、判题、收藏、图片引用替换、AI 解析（含并发槽） | 建议把 AI 解析相关拆出（它与题目 CRUD 无耦合） |
| 10 | `service/BankMergeService.java` | 401 | 多库合并：题目/材料/图片复制 + 血缘 + 题号重排 | 可接受；若继续增长可把「图片复制」下沉到 `ImageStorageService` |

> 紧随其后：`controller/CenterAuthController.java` 345、`service/MdQuestionParser.java` 340、
> `service/ContentPackageInspector.java` 335、`service/StatsService.java` 332、`service/QuestionBankService.java` 321、
> `service/AnswerFillService.java` 307、`service/CenterPublishService.java` 304、`service/AiImportJobLifecycleService.java` 281。

### 6.1.1 AI 导入链路的分层（2026-09-12 拆分后）

| 类 | 行数 | 只做这件事 |
| --- | --- | --- |
| `AiImportService` | 947 | 编排：建任务 → 跑流程 → 确认入库 + 控制器门面 |
| `AiImportModelCallService` | 1128 | 调模型：分块、并行、重试、合并（三条路径） |
| `AiImportVisionLayoutService` | 905 | 版面计算：图形块/坐标/裁剪/选项切分 |
| `AiImportJobLifecycleService` | 281 | 任务行与临时文件的生命周期、取消语义、SSE 快照 |
| `AiImportJobStorageService` | 237 | 任务目录/输入文件/图片素材落盘与清理 |
| `AiImportTextStructure` / `AiImportTexts` | 264 / 44 | 文本结构分析（题号、切块、修剪）与纯文本小工具 |
| `AiImportPromptFactory` / `AiImportResultParser` / `AiImportResultCodec` | 291 / 178 / 71 | 提示词、模型输出解析、结果序列化 |
| `AiImportAnswerService` / `AiImportFormulaService` / `AiImportSourceTextService` | 316 / 264 / 419 | 答案证据与补充、公式转写、源文定位回填 |
| `AiImportVisionQualityService` / `AiImportVisionMissingPageService` / `AiImportImageReferenceService` | 145 / 177 / 81 | 视觉结果去重与残题过滤、缺页补跑、临时图→正式图引用 |
| `AiImportDocumentPipeline` / `AiImportFailureClassifier` | 190 / 74 | 文档解析与 MinerU 回退、失败分类 |

### 6.2 前端（`frontend/src/**`，前 10）

| # | 文件 | 行数 | 职责 | 建议 |
| --- | --- | --- | --- | --- |
| 1 | `views/BankDetailView.vue` | **2848** | 题库详情 + 题库级**全部**操作（编辑、导出、删除、复习、错题、进度、题目检索与分页、题号盘、批量选择、批量建题、AI 补答案、材料 CRUD、图片上传、开会话、打印） | **强烈建议拆分**。这是前端最大的单文件，混了「列表/编辑面板/复习面板/材料面板/批量操作」多条业务线。建议按面板拆子组件（`BankInfoPanel` / `ReviewPanel` / `WrongPanel` / `MaterialPanel` / `BatchActions`），或把「复习」与「错题」独立成路由页 |
| 2 | `views/MyWorksView.vue` | **2574** | 本地题库 / 已导出文件 / 已发布作品三区 + 导出到目录 + 发布三入口 + 作品编辑/补传/下架 | 建议按「三区」拆子组件，把「发布流程」抽成 composable（现在发布编排与 UI 混在一个文件的 ~900 行里） |
| 3 | `views/AiImportPreviewView.vue` | **2341** | 预览与确认：进度、题目卡就地编辑、材料卡、拖拽排序、图片素材拖入/删除 | 建议把「题目卡片」「材料卡片」「素材区」「拖拽逻辑」拆出；拖拽排序可抽 composable |
| 4 | `views/PracticeView.vue` | 1822 | 做题、计时、交卷、成绩报告、主观题自评、回顾 | 建议把「成绩报告 + 自评」拆成独立组件（与 `SessionHistoryView` 的回顾逻辑有重叠） |
| 5 | `views/DiscoverView.vue` | 1730 | 广场三视图 + 账号体系（登录/注册/GitHub/Turnstile）+ 评论点赞收藏 | 建议拆「广场列表/详情/作者」三个组件 + 把账号流程抽 composable；同时把 13 处直连 `http` 收进 `api/center.js` |
| 6 | `views/SettingsView.vue` | 1659 | 6 个设置 panel（备份恢复、外观语言、记录、AI 配置、作者、关于更新） | 天然的 6 个 panel 可各自成组件；AI 配置 panel 尤其重（预设/模型列表/自愈/本地免 Key 判定） |
| 7 | `components/QuestionFormPanel.vue` | 1360 | 录题/编辑面板（字段、选项、材料、图片、预览、AI 草稿、导航、离开确认） | 建议拆「字段区 / 预览区 / 导航区」，把 AI 草稿与富文本预览抽 composable |
| 8 | `views/BankListView.vue` | 1347 | 首页：概览卡带 + 题库列表 + 新建/合并/三种导入 | 建议把「导入三入口 + 合并对话框」拆出组件 |
| 9 | `views/StatsView.vue` | 1213 | 统计 KPI、热力图、趋势（ECharts）、深度分析 | 建议把三个 ECharts 渲染函数抽到 `utils/charts.js`（现为文件内三段 `render*`） |
| 10 | `views/SessionHistoryView.vue` | 1016 | 历史列表 + 会话回顾 | 建议把「回顾」拆组件，与 `PracticeView` 的报告区共用 |

> 紧随其后：`components/AiImportDialog.vue` 849、`layouts/AppLayout.vue` 660、`views/PrintPaperView.vue` 528、
> `App.vue` 519、`components/QuestionNavDock.vue` 427、`views/AiImportJobsView.vue` 414。

### 6.3 官网（`web/**`，前 10，补充）

| # | 文件 | 行数 | 建议 |
| --- | --- | --- | --- |
| 1 | `pages/admin.vue` | 1276 | 拆「举报处置」与「用户管理」两个组件/页 |
| 2 | `pages/packs/[packageKey].vue` | 1057 | 拆「版本历史 / 评论 / 上报 / 作者摘要」 |
| 3 | `server/db/repos.ts` | 863 | 按 repo 拆文件（见 §5.4 第 9 条） |
| 4 | `pages/index.vue` | 710 | 落地页各 section 拆组件；顺带删掉重复的 `useSeoMeta` |
| 5 | `pages/me/profile.vue` | 599 | 拆「资料 / 邮箱 / 密码」三区 |
| 6 | `pages/me/index.vue` | 551 | 拆「作品卡片 / 编辑弹窗 / 补传弹窗」 |
| 7 | `pages/packs/new.vue` | 551 | 拆「文件解析校验」为 composable（`fflate` 解包 + 字段校验） |
| 8 | `pages/packs/index.vue` | 541 | 可接受 |
| 9 | `pages/authors/[id].vue` | 353 | 可接受 |
| 10 | `pages/register.vue` | 340 | 与 `login.vue` 有表单重复，可共用表单组件 |

### 6.4 桌面壳（`tauri/**`）

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `src-tauri/src/main.rs` | **943** | 单文件承载：单实例（手写 Win32）、资源定位、spawn 与端口解析、日志、另存为对话框、Win32 选目录、`open_url`、自动更新五命令、`kill_backend`、`restart_with_restore`、`launch_backend`、错误窗口。建议按模块拆（`single_instance` / `win_folder` 已经是内联 mod，可各自成文件；更新逻辑可独立成 `updater.rs`） |

---

## 7. Android 移植对照速查

> 移植时的**逐块对照入口**；技术路线与理由见 [`architecture.md`](architecture.md) §7 与 [`design-mobile.md`](design-mobile.md)。

| 要移植的东西 | 桌面端位置 | 移动端目标 |
| --- | --- | --- |
| 内容包读写（最关键，需逐字节对齐） | `util/PackageContainer.java`、`service/ContentPackageService.java`、`service/ContentPackageInspector.java` | Kotlin `.tiku` 解析/生成器（zip 魔数、4096/10MB/512MB 上限、`media/` 命名、打包排序） |
| 业务规则 | `service/*.java`（27 个） | Repository / UseCase（判分、错题、复习、自评、会话模式、版本自增） |
| 数据模型 | `src/main/resources/db/migration/V1..V14*.sql` + `model/*.java` | Room 实体 + 手写迁移（映射建议见 `data-model.md` §1.7） |
| 页面 | `frontend/src/views/*.vue`（11 个）+ `components/*.vue`（7 个） | Compose Screen（交互按手机重做，不做平移） |
| 接口封装 | `frontend/src/api/*.js`（10 个） | Retrofit Service（本地后端那部分**不要移植**，改为直连或本地 Repository） |
| 广场直连 | `controller/Center*.java`（转发层） | Retrofit + OkHttp 直连 `https://pickq.cn`，Bearer 鉴权；**不移植转发层** |
| 认证 | `service/CenterAuthStore.java` + `web/server/utils/auth.ts` + `web/server/api/auth/desktop-exchange.post.ts` | EncryptedSharedPreferences 存 token；需官网新增 deep link 回调白名单 |
| AI 导入 | `service/AiImportService.java`（高层编排）+ 独立的解析/提示词/结果/答案/视觉组件 + `DocumentParserService` + `MineruParseService` | WorkManager + 任务表；提示词与 Markdown 解析规则可搬，**编排要重做**（分块/并发/后台策略） |
| 富文本与公式 | `frontend/src/utils/richText.js` + `QuestionService`（图片引用替换） | `AnnotatedString` parser + WebView 内 KaTeX；`[图片:x]` 标记语法必须一致 |
| i18n 文案 | 18 个组件的 zh-CN/en-US 字典 | 共享 JSON 资源（`design-mobile.md` §3.4） |
| **不要移植** | `tauri/**`（全 Windows 专属）、`/api/center/**` 与 `/api/exports/**` 与 `/api/backup/**`（桌面代理/目录/重启语义）、H2 + Flyway | — |
