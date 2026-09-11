# 贡献指南（Contributing to 拾题 / PickQ）

> English summary: [below](#english-summary). This document is Chinese-first; if you need an English
> version of a specific section, open an issue and we will add it.

拾题（PickQ）是一个 Windows 桌面刷题应用：把手头的 PDF / Word / 照片用你自己的 AI API Key
整理成题库，刷题、复习、统计都在本机完成（数据目录 `~/.tiku`，H2 文件库，离线可用）。

感谢你有兴趣参与。**Issue 与 Pull Request 都欢迎**——包括 bug 报告、导入失败的原始样例（脱敏后）、
界面文案的英文润色、文档修正。

---

## 1. 贡献范围（先看这里）

| 目录 | 是否接受贡献 | 说明 |
| --- | --- | --- |
| `src/` | ✅ | 后端（Spring Boot / Java 21） |
| `frontend/` | ✅ | 桌面端前端 SPA（Vue 3 + Vite） |
| `tauri/` | ✅ | 桌面壳（Rust）与 Windows 打包脚本 |
| `docs/` | ✅ | 仓库内文档：文档索引（`docs/README.md`）、功能与规则、内容包格式、API、架构、数据模型、代码地图、约定、发版规范、截图与 Logo |
| `doc/` | ⚠️ 本地内部文档 | 设计与过程文档，**不入 git**（见 `.gitignore`），改动不会出现在 PR 里 |
| `web/` | ❌ | 官网 / 题库广场（Nuxt 3）。**不开源、不入 git**，不接受对外 PR |
| `deploy/`、`scripts/deploy/` | ❌ | 服务器部署资产（含服务器与密钥相关信息），**不入 git** |

一句话：**贡献范围是桌面端（前端 + 桌面壳）与后端。** 官网 `web/` 是内部资产，
但桌面端会与它的 API 交互，所以涉及「广场 API / 内容包格式」的改动请看 [§7](#7-提交与-pr-流程)。

最受欢迎的三类贡献：

1. **导入解析问题的可复现样例**：一份能复现问题的试卷文件（**请自行脱敏，不要提交受版权保护的整卷**），
   或一份最小化的 .tiku / .json，外加「期望 vs 实际」；
2. **修复**：导入、刷题、复习、统计、备份恢复、自动更新等桌面端功能；
3. **国际化**：`en-US` 文案的润色与遗漏补齐（见 [`docs/conventions.md`](docs/conventions.md) 的 i18n 红线）。

---

## 2. 环境搭建

| 组件 | 版本要求 | 本仓库事实依据 |
| --- | --- | --- |
| JDK | **21**（必须） | `pom.xml` → `<java.version>21</java.version>`（Spring Boot 3.4.4） |
| Maven | **3.9+**（仓库自带 wrapper） | `.mvn/wrapper/maven-wrapper.properties` 指向 Maven 3.9.16 |
| Node.js | **20+**（本机实测 v24.14.1） | `frontend` 使用 Vite 6 + `@vitejs/plugin-vue` 5 |
| Rust | **stable**（edition 2021） | `tauri/src-tauri/Cargo.toml`（Tauri 2） |
| Windows 工具链 | MSVC + WebView2 | `tauri/src-tauri/tauri.conf.json`（`webviewInstallMode = downloadBootstrapper`） |

### ⚠️ 第一个坑：`JAVA_HOME` 必须是 JDK 21

只把 `PATH` 上的 `java` 换成 21 不够——Maven 用的是 `JAVA_HOME`。若 `JAVA_HOME` 指向 JDK 17，
`mvn test` / `mvn package` 会以类似下面的错误失败（实测）：

```
has been compiled by a more recent version of the Java Runtime (class file version 65.0),
this version of the Java Runtime only recognizes class file versions up to 61.0
```

```powershell
# Windows PowerShell：本次会话内指定 JDK 21
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
java -version      # 期望输出 21.x

# 或直接用仓库脚本：自动寻找已安装的 JDK 21，再调用 Maven Wrapper
.\scripts\maven-java21.ps1 test
```

`tauri\build-desktop.ps1` 也会优先用 `$env:JAVA_HOME`，取不到时回退到 `C:\Program Files\Java\jdk-21`
（脚本内硬编码的候选路径，见 [§4](#4-构建与打包)）。

### 安装依赖

```bash
cd frontend && npm install     # 前端依赖（含 @tauri-apps/cli，桌面打包也用它）
# 后端依赖由 Maven 首次构建时下载，无需额外步骤
```

---

## 3. 跑起来（开发模式）

推荐两个终端：后端 8080，前端 5173（Vite 已配置 `/api` 代理到 `http://localhost:8080`，
见 `frontend/vite.config.js`）。

### 后端

```bash
# 首次或改了后端：先打包（跳过测试，见 §5 测试现状）
mvn package -DskipTests
java -jar target/Tiku-0.0.1-SNAPSHOT.jar      # http://localhost:8080

# 或用 Spring Boot 插件直接跑（spring-boot-maven-plugin 已在 pom 中）
mvn spring-boot:run
```

- 本地数据目录默认 `~/.tiku`（H2 文件库 + 图片 + AI 配置），可用环境变量 `TIKU_DATA_DIR` 覆盖；
- **不要**为了让测试或实验「干净」而删除 `~/.tiku`——那可能是你自己真实在用的题库；
  需要隔离时用 `TIKU_DATA_DIR` 指到临时目录。

### 前端

```bash
cd frontend
npm run dev        # http://localhost:5173，/api 代理到 8080
npm run build      # 产物 frontend/dist（见 §4）
npm run preview    # 预览构建产物（仍需后端在 8080）
```

### 桌面壳（本地调试）

桌面壳（`tauri/src-tauri`）启动本地后端并托管 `tauri/ui/index.html` 作为加载页，本身不包含业务前端。
因此本地调试桌面壳前，需要先把打包资源放到壳目录下（`tauri.conf.json` 的 `bundle.resources` 声明了
`jre/` 与 `app.jar`）：

```powershell
# 1) 先构建前端（否则 jar 里没有界面）
cd frontend; npm run build; cd ..
# 2) 打后端 jar
mvn -q -DskipTests package
# 3) 生成裁剪 JRE（jlink）并复制 jre/ + app.jar 到 tauri/src-tauri/
#    等价于 tauri\build-desktop.ps1 的第 1-3 步；也可以直接跑完整脚本，只是会顺带出一个安装包
.\tauri\build-desktop.ps1
# 4) 启动桌面壳（本仓库没有 tauri/package.json，CLI 从 frontend 的依赖里调用）
cd tauri\src-tauri
node ..\..\frontend\node_modules\@tauri-apps\cli\tauri.js dev
```

壳会把后端拉起在 `127.0.0.1` 的**随机端口**上（`--server.address=127.0.0.1 --server.port=0`，
见 `tauri/src-tauri/src/main.rs`），与「浏览器 + `mvn spring-boot:run` 固定 8080」是两种不同形态，
调试时别混淆。

---

## 4. 构建与打包

### 前端产物如何进入后端 jar（重要机制）

`frontend/dist` **不是**构建到 `src/main/resources/static`，而是由 `pom.xml` 的 `<resources>` 在打包时
映射进 jar 的 classpath：

```xml
<!-- pom.xml -->
<resource>
    <directory>frontend/dist</directory>
    <targetPath>static</targetPath>   <!-- jar 内 BOOT-INF/classes/static/ -->
</resource>
```

由此推出三条实务结论：

1. **`frontend/dist` 必须先存在**：`mvn package` 不负责构建前端。目录不存在时 Maven 只告警、不失败，
   于是你会得到一个「能启动、但页面 404」的 jar——这是最常见的「为什么界面没更新」；
   `tauri\build-desktop.ps1` 同样只跑 Maven（第 1 步），**不会**替你 `npm run build`。
2. 开发时用 5173 的 Vite dev server（热更新）；要验证打包形态，必须 `npm run build` 后重新 `mvn package`。
3. 生产形态下前端由后端同源托管，路由回退由 `SpaForwardConfig` 负责：无扩展名且非 `/api` 的路径回退到
   `index.html`（深链刷新可用），而 `/api/**` 一律 404 JSON——**别改这个语义**，前端 axios 依赖它。

### 后端

```bash
mvn package                 # 产出 target/Tiku-0.0.1-SNAPSHOT.jar（含 static 前端）
mvn package -DskipTests     # 只关心产物时
```

### Windows 桌面版一键打包

```powershell
.\tauri\build-desktop.ps1
```

脚本步骤（与文件内注释一致）：

1. `mvn -nsu -q -DskipTests package` → `target/Tiku-0.0.1-SNAPSHOT.jar`；
2. `jlink` 裁剪 JRE（已存在 `target/jre` 时跳过）→ `target/jre`；
3. 复制 `jre/` 与 `app.jar` 到 `tauri/src-tauri/`（`tauri.conf.json` 的 `bundle.resources`）；
4. `tauri build --bundles nsis` → `tauri/src-tauri/target/release/bundle/nsis/`；
5. 便携版 zip → `tauri/src-tauri/target/release/bundle/zip/拾题-便携版.zip`（含 `tauri/portable-assets/` 里的说明与快捷方式脚本）。

脚本的路径假设：`JAVA_HOME`（或回退 `C:\Program Files\Java\jdk-21`）与
`C:\Maven\apache-maven-3.9.9\bin\mvn.cmd`（或回退 `PATH` 上的 `mvn`）；`@tauri-apps/cli` 从
`frontend/node_modules` 调用，所以要先 `npm install`。

> 发版（版本号、更新频道、GitHub Release、官网下载链接）不是普通贡献流程，
> 按 [`docs/release-notes-guide.md`](docs/release-notes-guide.md) 的检查清单执行。

---

## 5. 测试

### 后端：`mvn test`

测试基座与数据隔离（`src/test/resources/application-test.yml`）：`@ActiveProfiles("test")` 把数据目录指到
`${java.io.tmpdir}/tiku-test-data`、数据库换成内存 H2——**测试绝不读写 `~/.tiku`**，也不会与正在运行的
桌面端抢 H2 文件锁（否则会出现 `Database may be already in use`）。

覆盖方向（`src/test/java`）：

| 方向 | 代表测试类 |
| --- | --- |
| 应用上下文冒烟 | `TikuApplicationTests` |
| AI 配置 / 模型目录接口契约（含假远端转发、鉴权头、缓存命中、错误文案） | `AiConfigControllerHttpTest`、`AiConfigServiceTest`、`AiModelCatalogServiceTest`、`AiClientServiceAuthTest`、`AiSettingsSaveTest` |
| 内容包发布链路（本地体检、元数据口径、转发字节原样、不合法=零请求） | `CenterPublishInspectTest`、`CenterPublishInspectHttpTest`、`CenterPublishForwardTest`、`PublishFromPathTest` |
| 导出（含导出中心、记录、偏好） | `ExportControllerHttpTest`、`ExportCenterServiceTest` |
| 文档解析（docx 公式图渲染、图片锚点、PDF 表格/题号版式） | `DocumentParserServiceTest` |
| Markdown 题目解析 / AI 答案格式 / 网络地址工具 | `MdQuestionParserTest`、`AiAnswerFormatTest`、`NetAddressTest` |
| 广场鉴权端口（拿不到 WebServer 上下文也能构造 controller） | `CenterAuthControllerPortTest` |

**已知注意点（请先读，能省你半小时）：**

1. **部分测试依赖不入 git 的私有样例文件**（`sample-ai-files/` 在 `.gitignore` 中，含版权材料）。
   干净克隆上这两处依赖会自动 **skipped（跳过）而不是失败**（用 JUnit `Assumptions` 判断文件是否存在），
   所以 `mvn test` 在干净克隆上也是绿的；本地放了样例就照常执行完整断言：
   - `DocumentParserServiceTest` 读 `sample-ai-files/2024安徽高考真题物理.docx`、
     `sample-ai-files/专项智能练习（判断推理）(1).pdf`；
   - `GraphPositionProbeTest` 按「文件字节数 681562」匹配 `sample-ai-files/` 下的一张 PDF
     （一个临时的调研探针，非交付测试）。

   想连跳过也看不到，可显式排除这两个类：

   ```bash
   mvn test -Dtest='!DocumentParserServiceTest,!GraphPositionProbeTest'
   ```

   注意：`-Dtest` 一旦指定就会**覆盖** Surefire 的默认包含规则——只给排除项时它会执行测试目录下的
   **全部**类，包括平时不跑的 `GenerateSampleFiles`（于是会在仓库根生成 `sample-ai-files/`，
   该目录不入 git）。
2. `GenerateSampleFiles` 虽然带 `@Test`，但类名不匹配 Surefire 默认包含规则（`*Test` / `Test*` / `*Tests` / `*TestCase`），
   **不会**在 `mvn test` 时执行。它是样例生成工具，按需单独跑：`mvn -Dtest=GenerateSampleFiles test`
   （会在仓库根生成 `sample-ai-files/`，该目录不入 git）。
3. **不要**在测试里写真实数据目录、真实外网 AI 端点或真实广场地址。现有测试的做法是
   `@TempDir` + 本机 `com.sun.net.httpserver.HttpServer` 假远端（见 `AiConfigControllerHttpTest` 类注释：
   「全程不连公网；AI 配置写进 `@TempDir`，绝不碰用户真实 `~/.tiku`」）——新测试请沿用。
4. 断言里的**错误文案就是契约**（前端弹窗直接展示这些 message，且与官网 `web/server/utils/*` 同口径），
   改文案等于改契约：请同时更新测试与文档。

### 前端：目前没有测试基建

如实说明：`frontend/package.json` 的 `scripts` 只有 `dev` / `build` / `preview`，没有 `test`；
`devDependencies` 里的 `playwright-core` 目前未被任何源码引用。**欢迎你补上测试基建**
（Vitest + Vue Test Utils 单测，或 Playwright 冒烟）——建议先开 Issue 说明方案再动手，避免与后续规划冲突。

### 桌面壳

`tauri/src-tauri` 没有 Rust 单测。涉及壳的改动请手动验证：单实例、随机端口启动、退出时后端进程被清理、
自动更新流程、备份/恢复。

---

## 6. 代码约定

**请直接读 [`docs/conventions.md`](docs/conventions.md)**（每条都带规则 + 理由 + 真实代码示例）。这里只给摘要：

- **后端**：所有 controller 返回统一包装 `ApiResponse<T>`（`code === 200` 才算成功）；
  异常 → HTTP 状态码由 `GlobalExceptionHandler` 统一映射（`IllegalArgumentException`→400、
  `NoSuchElementException`/`NoResourceFoundException`→404、`HttpRequestMethodNotSupportedException`→405、
  `IllegalStateException` 及未知异常→500）；DTO 一律用 `record`；controller 只做参数绑定与调用，
  业务在 `service`，`mapper` 保持为 `BaseMapper` 空接口（复杂 SQL 用注解写在接口上）；注释用中文。
- **数据库**：Flyway 迁移 `V<n>__snake_case.sql`，**只增不改**（已发布的迁移禁止修改）；
  列名 snake_case、Java 字段 camelCase、多词列显式 `@TableField("created_at")`；
  时间用 `TIMESTAMP` + `LocalDateTime`，服务层显式 `LocalDateTime.now()`。
- **前端**：组件级 i18n 字典 `useI18n({ messages: { 'zh-CN': {...}, 'en-US': {...} } })`，
  **两种语言必须成对**、禁止裸中文文案、命名插值统一 `{n}` / `{d}` / `{name}` 风格；
  路由页放 `views/`、可复用组件放 `components/`、接口封装放 `api/`、纯函数工具放 `utils/`；
  需要调用方自己处理报错时给请求加 `skipErrorMessage: true`；图标统一用 `TikuIcon.vue`（不引图标库）。
- **跨端契约**：内容包格式与广场 API 的改动规则见 `docs/conventions.md` 第 5 节——格式改动必须同时改
  两端解析代码与格式文档，广场 API 变更必须向后兼容。

---

## 7. 提交与 PR 流程

### 分支

从最新的 `main` 开分支，命名建议 `feat/<短描述>`、`fix/<短描述>`、`docs/<短描述>`
（与提交前缀对应，仓库现状如此，无强制校验）。

### 提交信息

沿用现有 git log 的实际风格：**英文（或中文）+ 约定式前缀 + 可选的 scope**
（历史提交里中英混用都存在，例如 `feat(plaza): ...`、`i18n：题库详情页模板收尾`，前缀是稳定的，
语言不是）。前缀取：

| 前缀 | 用途 |
| --- | --- |
| `feat` | 新功能（`feat(ai)`、`feat(plaza)`、`feat(publish-center)`、`feat(preview)`、`feat(editor)`） |
| `fix` | 缺陷修复（`fix(updater)`、`fix(editor)`） |
| `docs` | 文档 |
| `chore` | 杂项（版本号、锁文件同步） |
| `i18n` | 国际化文案迁移 / 补齐 |
| `security` | 安全相关改动（**不要在提交信息里写可被利用的细节**） |

写清「改了什么、为什么」，避免只有 `update` / `fix bug`。

### PR 描述应包含

1. **改了什么、为什么**（对应 Issue 号如果有）；
2. **验证方式**：跑过的命令与手动验证步骤（后端 `mvn test` 结果、前端是否 `npm run build` 通过、
   是否真机试过导入/做题等路径）；
3. **用户可感知的变化**：一句话，发版公告会参考它（见 `docs/release-notes-guide.md` 的措辞规范）；
4. **截图 / GIF**（界面改动强烈建议；`docs/screenshots/` 已有中英两套，改动界面时请注意是否要同步）；
5. **契约影响**（见下）。

### 改动涉及契约时必须同步的文档

完整的「改了什么 → 必须同时更新哪份文档」表在 [`docs/README.md`](docs/README.md) 的**同步规则**一节
（**以它为准**）。下表是常见几类：

| 改动 | 需同步 |
| --- | --- |
| **内容包格式**（`.tiku` / v1 JSON 字段、`schemaVersion`、图片与材料结构） | [`docs/package-format.md`](docs/package-format.md) + 两端解析/序列化代码 + 根目录 `sample-content-package.json`。两端代码：`src/main/java/com/tiku/service/ContentPackageInspector.java`、`src/main/java/com/tiku/util/PackageContainer.java`、`ContentPackageService`（桌面端）、`web/server/utils/package-meta.ts`（广场侧） |
| **广场 API**（`/api/packs/**`、鉴权、字段） | [`docs/api.md`](docs/api.md) + 桌面端代理与前端调用处；**必须向后兼容**：只加可选字段，不改既有字段语义与类型（内部设计记录：`doc/api-spec.md`、`doc/center-spec.md`，不入 git） |
| **本地后端 API**（`/api/**`） | [`docs/api.md`](docs/api.md) + 对应控制器 DTO + 既有测试 |
| **数据库结构** | [`docs/data-model.md`](docs/data-model.md) + 新增一个 Flyway 迁移（**不改旧迁移**） |
| **业务规则**（判分、错题定义、复习间隔、版本自增） | [`docs/features.md`](docs/features.md) + 对应 service 的注释 |
| **发版流程 / 公告口径** | [`docs/release-notes-guide.md`](docs/release-notes-guide.md)（检查清单；公告只写功能） |

### 提交前自检

```bash
mvn -q -DskipTests package                 # 后端能编译打包（JAVA_HOME=JDK 21）
mvn test -Dtest='!DocumentParserServiceTest,!GraphPositionProbeTest'   # 无私有样例时的跑法，见 §5
cd frontend && npm run build               # 前端能构建
```

- 新增/修改的界面文案：`zh-CN` 与 `en-US` **都**在字典里，且没有裸中文（见 `docs/conventions.md` 第 7 节
  的 i18n 质量红线）；
- 改了内容包 / API：按上表同步文档；
- 改了界面：确认 `zh-CN` 与 `en-US` 下都不溢出、不截断。

---

## 8. 不要提交什么

- `web/`（官网与广场，含服务端代码）、`deploy/`、`scripts/deploy/`——**不入 git、不开源**；
- **任何密钥与服务器信息**：API Key、`.env`、`application-local.yml`、SSH 私钥、服务器 IP / 账号 /
  部署脚本里的 `scp` 目标、Resend / SMTP / Turnstile 的密钥样例（真实值）；
- **用户数据与本机数据**：`~/.tiku`、`*.mv.db`、`target/`、`frontend/dist/`、`frontend/node_modules/`、
  `tauri/src-tauri/{jre,app.jar,target,gen}`、`tauri/icon-source.png`、`dist/`（以上均已在 `.gitignore`）；
- **测试与调研产物**：`sample-ai-files/`（个人样例、含版权材料）、`doc/ai-import-audit/`、
  `mineru-test-output/`；
- **受版权保护的整卷材料**：报告解析问题请提供最小复现样例或自行脱敏的片段，不要提交原卷。

提交前 `git status` 扫一眼，确认没有被 `.gitignore` 之外的意外文件混进来。

---

## 9. License

本项目以 [MIT](LICENSE) 发布。提交贡献即表示你同意以同一许可证发布你的改动。

---

## English summary

PickQ is a local-first Windows desktop app that turns your own PDFs/Word files/photos into a
practice question bank using your own AI API key; everything (questions, records, review schedule)
stays on your machine.

- **Scope of contributions**: the desktop app and its backend — `src/` (Spring Boot, Java 21),
  `frontend/` (Vue 3 + Vite), `tauri/` (Rust shell + packaging), `docs/`.
  The marketing website / community plaza (`web/`, Nuxt 3) and the deployment assets
  (`deploy/`, `scripts/deploy/`) are **internal, not in git, and not open to PRs**.
- **Setup**: JDK **21** (make sure `JAVA_HOME` points at JDK 21, not 17 — Maven uses `JAVA_HOME`),
  Maven 3.9+ (wrapper included), Node 20+, Rust stable (Tauri 2) + MSVC + WebView2 for the shell.
- **Run**: `mvn package -DskipTests && java -jar target/Tiku-0.0.1-SNAPSHOT.jar` (port 8080),
  `cd frontend && npm run dev` (port 5173, proxies `/api` to 8080),
  `.\tauri\build-desktop.ps1` for a one-shot NSIS installer + portable zip.
  Build the frontend **before** packaging the backend — `frontend/dist` is mapped into the jar as
  `classpath:/static` by `pom.xml`, and a missing `frontend/dist` only warns.
- **Test**: `mvn test` (18 test classes / 115 cases under Surefire's default naming rules).
  Tests use an in-memory H2 and a temp data dir (`src/test/resources/application-test.yml`), never
  `~/.tiku`. Two classes depend on private sample files that are not in git
  (`DocumentParserServiceTest`, `GraphPositionProbeTest`) — see §5 for how to skip them.
  The frontend has **no test infrastructure yet**; contributions to set it up are welcome.
- **Conventions**: see [`docs/conventions.md`](docs/conventions.md) (response envelope + exception→HTTP
  mapping, Flyway migrations are append-only, DTOs are records, component-level i18n dictionaries with
  paired `zh-CN`/`en-US` entries, `{n}`-style named interpolation, cross-platform package/API contracts).
- **Commits/PRs**: conventional prefixes (`feat` / `fix` / `docs` / `chore` / `i18n` / `security`) with an
  optional scope; PRs should state what changed, why, how it was verified, and any contract/documentation
  updates. Never commit secrets, server details, user data, or copyrighted exam papers.
- **License**: MIT.
