# 功能清单与业务规则（拾题 PickQ）

> 面向两类读者：想了解"这东西到底能干什么"的用户，以及准备改代码 / 做移动端的贡献者。
> 每节末尾的「规则」是从**代码里提取**的确定性行为，不是设想。代码依据以文件名为准。

## 0. 一句话定位

拾题是一个**本地优先（local-first）的题库工具**：你自己录题或从文档用 AI 批量导入，做题记录、错题本、复习计划、备份全部存在本机；需要分享时，把题库导出成 `.tiku` 文件，或登录账号发布到官网广场供他人下载。

- 桌面端：Windows 桌面应用（Tauri 壳 + 内置 JRE + 本地 Spring Boot 服务 + Vue 3 界面），**数据不出本机**，唯一的外部调用是你自己配置的 AI 接口。
- 官网/广场（pickq.cn）：账号、题库广场、评论、关注、后台管理（此部分不开源，`web/` 不入库）。
- 移动端：规划中，见 `docs/design-mobile.md`。

## 1. 功能地图

| 模块 | 能做什么 | 入口页面 | 关键代码 |
| --- | --- | --- | --- |
| 题库管理 | 新建/导入题库、改信息、合并、删除、分类与检索 | 题库列表 `BankListView.vue`、题库详情 `BankDetailView.vue` | `QuestionBankController` / `QuestionBankService` |
| 录题与编辑 | 手动新增、批量粘贴、单题编辑（13 个字段）、图片、公式、材料 | 题库详情、`QuestionFormPanel.vue` | `QuestionController` / `QuestionService` |
| AI 批量导入 | 上传文档 → AI 解析成题目 → 预览校对 → 入库 | `AiImportJobsView.vue`、`AiImportPreviewView.vue` | `AiImportController` / `AiImportService` / `DocumentParserService` / `MineruParseService` |
| 做题 | 顺序/随机/分类/错题/收藏/复习 六种会话，客观题自动判分、主观题自评 | `PracticeView.vue` | `PracticeSessionController` / `StudyRecordService` |
| 错题与复习 | 错题本、间隔重复复习队列、暂停某题复习、重置复习进度 | `BankDetailView.vue`（错题/复习页签）、`PracticeView.vue` | `StudyRecordService.updateReviewState` |
| 学习统计 | 总览、每日热力、题库维度、会话历史 | `StatsView.vue`、`SessionHistoryView.vue` | `StatsController` / `StatsDetailResponse` |
| 打印试卷 | 纯题目版 / 带答案解析版，浏览器打印或另存 PDF | `PrintPaperView.vue` | 前端渲染 + `window.print()` |
| 内容包导入导出 | `.tiku` / `.json` 导入导出，跨端与跨人交换 | 题库详情 | `ContentPackageService` / `PackageContainer` / `docs/package-format.md` |
| 本地作品管理 | 导出记录、批量导出到目录、版本号自增、标记已发布 | `MyWorksView.vue` | `ExportController` / `LocalExportService` / `ExportRecordService` |
| 发布与广场 | 发布题库到广场、改版本、替换文件、下架；浏览/搜索/收藏/评论/关注/下载导入 | `MyWorksView.vue`、`DiscoverView.vue` | `CenterPublishController` / `CenterProxyController` |
| 账号 | 账号密码注册登录、邮箱验证、找回密码、GitHub 登录、桌面端内登录广场 | `SettingsView.vue`、官网 `login/register/forgot` | `CenterAuthController`、`web/server/api/auth/**` |
| AI 配置 | 30+ 供应商模板、远端预置目录、动态拉取模型列表、连通性测试、本地模型免 Key | `SettingsView.vue` | `AiConfigController` / `AiPresetService` / `AiModelCatalogService` / `AiConfigService` |
| 备份恢复 | 一键完整备份（含图片与配置）、从备份恢复并重启 | `SettingsView.vue` → 完整备份 | `BackupController` / `StudyRecordService.exportRecords` |
| 外观与语言 | 主题（浅/深）+ 中文/英文双语 | `SettingsView.vue` | 组件级 i18n 字典、`vue-i18n` |
| 自动更新 | 检查更新、下载（可取消）、校验、静默安装 | `App.vue` 更新对话框 | `frontend/src/utils/updater.js` + Tauri 命令 |

## 2. 题库管理

- 题库字段：名称、描述、作者、来源/出处、分类、`packageKey`（包唯一标识）、版本、复习开关（`review_enabled`）。
- 导入方式：JSON、`.tiku` 容器、AI 导入；导出方式：JSON、`.tiku`。
- 合并：把另一个题库的题目并入当前题库（`POST /api/banks/merge`）。
- 选题复制：按条件从题库挑选题目复制到另一个题库（`selection-copy`）。

**规则**
- 删除题库会连带删除其题目、材料、答题记录、复习状态、会话（由 service 在一个事务里完成，不留下孤儿行）；
  题目与题库都是**物理删除**，删掉即不再占用该题库的题号标识。
- 同一题库内按题号标识（`questionKey`）去重：**重复导入同一份题目会跳过已存在的题**（同一 AI 导入任务重复确认、同一份文件导入两次都不会产生重复题或报错）。
- `packageKey` 是跨端/广场的稳定标识；改题库**显示名**不影响已导出的包。
- 复习功能按题库开关：关闭后做题不更新复习状态、详情页不显示复习入口。

## 3. 录题与题型

四种题型（`model/enums/QuestionType.java`，AI 侧同为这四个字符串 `AiImportService.VALID_TYPES`）：

| 题型 | 取值 | 判分方式 | 必填 |
| --- | --- | --- | --- |
| 单选题 | `SINGLE` | 自动判分（选项与答案集合相等） | `options` ≥2、`answerKeys` 非空 |
| 多选题 | `MULTIPLE` | 自动判分（集合相等） | 同上 |
| 判断题 | `JUDGE` | 自动判分 | 答案（正确/错误） |
| 主观题 | `SUBJECTIVE` | **不自动判分**：展示参考答案，用户自评 | `referenceAnswer` 可选，无选项无答案键 |

题目字段（`QuestionFormPanel.vue` 的 13 项脏检查基线）：题干、题型、选项、答案、解析、分类、知识点/主题、分值、题目编号、题号所属卷/部分、材料关联、参考答案、答案来源（`answerSource`）。

**规则**
- 富文本：题干/选项/解析/参考答案中的图片以 `[图片:文件名]` 标记内嵌，渲染时替换为本地图片 URL（详见 `docs/package-format.md`）。
- 主观题允许空答案：选择题若被 AI 判成 `SUBJECTIVE` 但选项齐全，会自动修正为 `SINGLE`；选项为空的"选择题"一律保持 `SUBJECTIVE`（避免"空选项单选"卡住确认导入）。
- AI 补答案（`ai-fill-answers`）只处理非主观题：「有无答案」判断基于 `answerKeys`，因此**主观题不显示"AI 补充答案"标记**（已知取舍）。

## 4. AI 批量导入

流程：`上传文件 → 解析（本地抽取或云端 OCR）→ 分块交给 AI 逐块出题 → 预览校对 → 确认入库`。

- 支持文件：`.txt` `.md` `.markdown` `.docx` `.pdf` `.png` `.jpg` `.jpeg` `.webp` `.bmp`。
  - `.doc` 老格式明确不支持，提示用户另存为 `.docx`。
  - 扫描版 PDF（无文本层）：可配置 **MinerU** 云端解析（设置项 `mineruKey`），或按页渲染成图片交给视觉模型。
- 本地解析（`DocumentParserService`，全部离线）：docx 走 POI（按 body 元素顺序，段落与表格交错；提取插图与 MathType 的 WMF/EMF 公式预览图并解码为 PNG），pdf 走 PDFBox（文本层提取 + 内嵌图片提取 + 按页渲染兜底）。小于阈值的装饰图会被过滤；无法解码的矢量图跳过并给出警告。
- 任务与进度：任务落库，前端通过 SSE（`/ai-import/jobs/{id}/stream`）看进度与日志；任务可删除；`active` 用于应用重启后恢复"进行中的导入"。
- 预览校对（`AiImportPreviewView.vue`）：默认**渲染优先**（直接看题），可切换单题编辑 / 全部编辑；解析出的材料单独成块；图片素材区可预览。
- 确认导入：选择目标题库（可新建）→ 校验 → 写库，临时图片转正到正式图片目录。

**规则**
- 解析出的题目**先不落库**，只有「确认导入」才写入题库；放弃任务即丢弃。
- 题号/卷号冲突（如多个 Part 各自从 1 开始编号）会导致导入数量少于预期，属已知问题，见 `docs/import-issues.md`。
- AI 调用用**你自己的 Key**（BYOK）；本地/内网地址（如 Ollama）允许不填 Key。

## 5. 做题、判分与错题

会话模式（`PracticeSessionService.normalizeMode`）：`ALL`（整库顺序）/ `SEQUENCE`（从指定题往后做）/ `TOPIC`（按知识点）/ `REVIEW`（到期待复习）/ `WRONG`（错题）/ `FAVORITE`（收藏）。

- `SEQUENCE` 是唯一支持 `startQuestionId` 的模式。
- 材料题按材料分组为"单元"（`buildUnits`）：同材料的多道小题在会话中连续出现。
- 附加筛选 `scope`：`favorite` / `wrong` / `undone`。

判分与自评：
- 客观题提交即判分（`correct`）。
- 主观题：用户写答案（`userAnswer` 必填）→ 展示参考答案 → 自评 `CORRECT` / `PARTIAL` / `WRONG`；实得分 = 满分 / 半分 / 0（`StudyRecordService.earnedScore`）。

**错题口径（全局统一，务必遵守）**
> 一道题"**最近一次作答为错**"才算错题；历史答错但最近已答对的题**不算**。主观题自评 `PARTIAL`/`WRONG` 算错（此时 `is_correct` 为 null，不能只看该列）；**未自评不算错**。
> 实现：`StudyRecordService.computeWrongQuestionIds`，被错题本、会话 `WRONG`、题目列表 `scope=wrong`、导出范围 `wrong` 共用。

**复习算法（简化间隔重复，`updateReviewState`）**
- 每个"题目"维护一条复习状态（`review_state`：`level`、`interval_days`、`due_at`、`suspended`）。
- 答对：`level = min(level+1, 5)`，间隔 `= min(2^level, 30)` 天 → 实际序列 `1→2→4→8→16→30` 天封顶。
- 答错：`level = 0`，间隔 1 天，`due_at = now`（立即可复习）。
- 可对单题「暂停复习」（`suspended`），可一键重置某题库全部复习进度。
- 复习汇总（待复习数、已过期天数 `overdueDays`）供详情页与统计页使用。

## 6. 统计

- 总览（`StatsSummaryResponse`）：总题数、已答、已判定、正确数/正确率、待复习、连续天数等 + 每日明细（日期、作答数、判定数、正确数）。
- 明细（`StatsDetailResponse`）：题库维度统计（`BankStat`：总量/已答/已判定/正确）+ 会话维度统计（`SessionStat`：模式、题数、正确率、耗时）。
- 设计原则（页面副标题即产品意图）："每个数字都指向下一步（去做题 / 去复习）"——统计页的每个数字都能点击跳到对应动作。

## 7. 打印试卷

- 独立路由 `/banks/:id/print`（无侧栏，固定布局），入口在题库详情。
- 两个版本：**纯题目**（学生作答）/ **带答案 + 解析**（教师核对）；范围与分类筛选即时生效。
- 用浏览器打印（`window.print()`），可选「另存为 PDF」导出 PDF；打印样式在 `.print-page` 的 `@media print` 中定义。

## 8. 内容包与本地作品

- 导出：`POST /api/banks/{id}/export`（JSON）、`POST /api/banks/{id}/export-tiku`（`.tiku` 容器）。
- 本地作品页的「导出文件」：`POST /api/exports/export` 直接写入目标目录，文件名 `{题库名或packageKey}-{版本}.tiku`（清理 Windows 非法字符，同名覆盖以最新为准），并落一条导出记录。
- 默认导出目录：`文档\拾题`，用户改过之后记住（`{数据目录}/export-prefs.json`）。
- 版本号自增：解析当前版本后补丁号 +1（`1.2` → `1.2.1`；无法解析 → `1.0.0`）。
- 导出记录可删除；发布成功后标记「已发布」（`mark-published`）。
- `/api/exports/**` **不需要登录**（纯本地数据）；`/api/center/publish*` 需要登录。

格式细节（容器结构、字段表、校验规则）见 **`docs/package-format.md`**——那是跨端契约，改动必须两端同步。

## 9. 广场与账号

- 客户端能力：浏览/搜索题库、看作者主页、收藏、评论/回复/点赞、关注作者、下载并导入（"导入"直接把广场包的题目写进本地题库）。
- 作者能力（"我的作品"）：发布新版本、修改版本说明、替换包文件、下架、查看下载/收藏/评论数。
- 桌面端登录：应用内直接用账号密码或 GitHub 登录广场，token 保存在 `{数据目录}/center-auth.json`；请求以 `Authorization: Bearer` 转发。
- 官网（浏览器）：cookie 会话（`pickq_session`）+ 人机验证（Cloudflare Turnstile）。
- 权限：普通用户 / 管理员（由服务器环境变量 `ADMIN_USERNAMES` 指定），管理员可进后台管理用户与题库。
- 内容治理：举报与下架流程由官网侧实现（`removal.ts`）。

**规则**
- 一个账号在桌面端与官网是同一账号，登录任一即可管理自己的作品。
- 发布是"先导出为 `.tiku` 再上传"的两步：导出 → 校验（inspect）→ 上传 → 标记已发布。失败不会影响本地题库。

## 10. AI 配置

三层结构：
1. **内置模板**（前端常量）：DeepSeek、OpenAI、Anthropic、Gemini、通义千问、Kimi、Groq、Mistral、智谱、本地 Ollama 等。
2. **远端预置目录**：`https://pickq.cn/config/ai-presets.json`（服务器 `/opt/pickq/config/`，**与站点部署解耦，改文件即时生效**），客户端经本地后端 `GET /api/ai/presets`（内存缓存 1 小时）取回并按名称合并；远端可显式用空字符串覆盖内置项。
3. **动态模型列表**：`POST /api/ai/models` 探测候选接口（`{base}/models` → `{base}/v1/models` → 本地 `/api/tags`），Anthropic 用 `x-api-key` + `anthropic-version`。
- 弃用自愈：远端 `deprecated[]` 给出旧模型 → 新模型的映射与提示，前端 `utils/aiModelHelp.js` 在用户选中废弃模型时给出替换建议。
- 连通性测试：`POST /api/ai/settings/test`；Key 只回显掩码。

**规则**
- 必须有 `baseUrl` + `model` 才算配置完成；**公网地址必须填 Key，本地/内网地址可以留空**（`NetAddress.isLocalOrPrivate`：`localhost`/`::1`/`127.0.0.0/8`/`10/8`/`172.16-31`/`192.168/16`），留空时请求不带 Authorization 头。
- AI 调用与文档解析都在本机进行；除你配置的模型服务外，不上传任何内容。

## 11. 备份与恢复

- 完整备份：数据库 + 图片 + 配置，生成一个备份文件（用户选择保存位置）。
- 恢复：`POST /api/backup/restore-prepare` 准备恢复 → 壳负责退出应用、替换数据目录、重启（因此恢复过程中会看到应用自动关闭并重新打开）。
- 学习记录可单独导出/导入 JSON（`/api/study-records/export|import`），用于跨机器迁移刷题进度。

## 12. 外观、语言与更新

- 主题：浅色/深色（设置页「外观与语言」）；界面语言：简体中文 / English，**组件级字典**（每个 `.vue` 自带 `zh-CN`/`en-US` 两份文案），命名插值用 `{n}` 风格。
- 更新：检查 `https://pickq.cn/updates/latest.json` → 下载（可取消，取消后对话框立即关闭并提示"已取消下载"）→ SHA 校验 → 安装。GitHub Releases 作为镜像/备用渠道。
- 更新公告（Release 说明）**只写用户可感知的功能变化**，不写安全与运维细节——这条是硬约定，见 `docs/release-notes-guide.md`。

## 13. 明确不做 / 已知边界

- 不做云端同步：题库与做题数据默认只在本机；跨设备靠 `.tiku` 文件与广场。
- 不做账号强绑定：不登录也能用全部本地功能。
- 不做移动端网页版：手机端计划是独立原生应用（见 `docs/design-mobile.md`）。
- 已知问题清单（导入解析、题号冲突等）见 `docs/import-issues.md`。
