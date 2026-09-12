# 文档索引

本目录是拾题（PickQ）的技术文档。桌面端与后端代码在本仓库；官网/广场（`web/`）、部署脚本与运维细节**不在本仓库**。

> 维护规则：文档改动与代码改动同一次提交；凡涉及「跨端契约」的改动（内容包格式、广场 API），必须同时更新对应文档，见下文第 3 节。

## 1. 按需求找文档

| 我想…… | 看这份 |
| --- | --- |
| 知道这软件能干什么、规则是什么 | [`features.md`](features.md) |
| 让别的程序读写拾题的题库文件 | [`package-format.md`](package-format.md) |
| 调本地后端接口 / 广场接口 | [`api.md`](api.md) |
| 查表结构、数据目录、怎么做数据迁移 | [`data-model.md`](data-model.md) |
| 理解整体架构与关键机制（启动、更新、备份、AI 链路） | [`architecture.md`](architecture.md) |
| 快速定位某个功能在哪个文件 | [`code-map.md`](code-map.md) |
| 写前端页面 / 加按钮前先看位置与交互约定 | [`design-ui.md`](design-ui.md) |
| 按项目约定写代码 | [`conventions.md`](conventions.md) |
| 开发/打包/测试 | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) |
| 发布新版本 | [`release-notes-guide.md`](release-notes-guide.md) |
| 做 Android 端 | [`design-mobile.md`](design-mobile.md) |
| 排查导入解析问题 | [`import-issues.md`](import-issues.md) |
| 部署官网与相关服务 | [`../deploy/README-部署.md`](../deploy/README-部署.md)（含服务器信息，注意保密） |
| 上报安全问题 | [`../SECURITY.md`](../SECURITY.md) |
| 看历史版本变化 | [`../CHANGELOG.md`](../CHANGELOG.md) |

## 2. 文档清单

### 核心（工程与契约）
- **`features.md`** — 功能地图 + 业务规则（题型与判分、错题口径、复习算法、会话模式、AI 导入流程、发布流程）。改业务规则时先改这里。
- **`package-format.md`** — `.tiku` / 题库 JSON 的格式规范：容器结构、字段表、校验规则、示例、版本演进。**跨端契约**。
- **`api.md`** — 本地后端（Spring Boot）与广场服务端（Nuxt）的全部端点、鉴权、错误模型。**跨端契约**。
- **`data-model.md`** — 本地 H2 表结构与广场 SQLite 表结构、数据目录布局、面向移动端的迁移建议。
- **`architecture.md`** — 两条主线（桌面端 / 官网）、启动链路、分层职责、关键机制、技术选型取舍、Android 复用清单。
- **`code-map.md`** — 目录说明 + 功能索引表 + 页面索引表 + 大文件清单（拆分候选）。

### 约定与协作
- **`conventions.md`** — 编码、数据库、前端、官网、跨端、发布的约定（含反例）。
- **[`../CONTRIBUTING.md`](../CONTRIBUTING.md)** — 环境搭建、构建、测试、提交与 PR 流程。
- **[`../CHANGELOG.md`](../CHANGELOG.md)** — 面向用户的版本变化记录。
- **[`../SECURITY.md`](../SECURITY.md)** — 漏洞上报渠道与安全设计边界。

### 专题与规划
- **`design-ui.md`** — 前端界面一体化规范：页面骨架五段、R1–R10 硬规则（主操作位置、破坏性确认、空态出路、编辑大弹窗规格…）、公共组件表、全局 `common.*` 词表、可运行的检查与冒烟脚本、迁移阶段。**新增页面必须遵守**。
- **`backend-audit-and-refactoring-plan.md`** — 后端审计与重构路线图：已修复项（图片路径隔离、备份解压限额、AI 配置原子写入、默认只监听本机、失效测试夹具）、已完成的服务拆分、以及下一步（`VisionLayoutContext`、流式导入、失败分类打磨）。
- **`design-mobile.md`** — Android 端设计稿：技术选型、功能映射、信息架构、数据映射、里程碑、风险、开工材料清单。
- **`import-issues.md`** — 文档导入解析的已知问题与优先级记录。
- **`release-notes-guide.md`** — 发版规范、发布检查清单、更新公告写作规则。
- **`screenshots/`** — 界面截图（中文 `*.png` / 英文 `en-*.png`），供 README 使用。

### 内部（不在本仓库）
- 官网/广场源码 `web/`、部署资产 `deploy/`、运维配置：属于内部资产，仓库中已忽略。相关说明见 `deploy/README-部署.md`（**含服务器信息，不要外传**）。
- 内部设计记录在仓库外的 `doc/` 目录（**单数**，与本文所在的 `docs/` **复数**区分）：那里放设计讨论与已存档方案，**对外契约一律以 `docs/` 为准**。

## 3. 同步规则（改动时必须一起改的文档）

| 你改了什么 | 必须同时更新 |
| --- | --- |
| 内容包字段、容器结构、校验规则 | `package-format.md` + 两端解析代码 + `sample-content-package.json` |
| 后端或广场端点、响应结构、错误码 | `api.md`（并确认向后兼容） |
| 数据库迁移（新增表/字段） | `data-model.md` + Flyway 迁移文件（**迁移只增不改**） |
| 业务规则（判分、错题、复习间隔、版本自增） | `features.md` + 对应服务的注释 |
| 新增/改动功能 | `features.md`、`code-map.md`；用户可感知的写入 `CHANGELOG.md` |
| 前端页面结构 / 按钮位置 / 确认框 / 公共组件 | `design-ui.md`（含 `npm run check:ui` 必须全绿） |
| 发版 | `release-notes-guide.md` 的检查清单（含官网下载链接、更新频道、GitHub Release） |
| 编码约定调整 | `conventions.md` + `CONTRIBUTING.md` 摘要 |

## 4. 写作约定

- 中文为主；面向国际贡献者的入口（`README.en.md`、`CONTRIBUTING.md`、`SECURITY.md`）带英文摘要。
- **只写代码里能验证的事实**：字段名、端点、常量、正则、阈值都要与代码一致；不确定的写「待确认」，不要凭印象补全。
- 表格优先于长段落；每个结论尽量标注来源文件，方便下一个人核对。
- 不写密钥、口令、服务器凭据；服务器 IP 与账号用占位符，真实值放在本地不入库的笔记里。
- 更新公告（Release 说明）只写用户可感知的功能变化，**不写安全与运维细节**。
