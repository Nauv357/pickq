# 题库文件（内容包）格式规范

> ⚠️ **本文件是跨端互通契约。**
> 桌面端（`src/main/java/com/tiku/**`）、官网广场（`web/server/**`）、未来 Android 端、以及任何第三方
> 生成/解析工具都必须遵循这里的字段名、取值与约束。**改动本文件描述的任一字段名、常量、正则或上限时，
> 必须同步修改桌面端与官网的解析/生成代码，并在两端的校验里同时放宽/收紧**——否则会出现
> "一端能导入、另一端拒收"的静默不兼容。
>
> 本文所有字段名、常量、正则、上限均**逐条取自代码**，每处都标注了来源（文件名 + 常量/方法名）。
> 代码中找不到明确依据的地方一律显式标注「**待确认**」，不做推测性描述。

**事实来源（本文只依据这些文件）**

| 文件 | 提供的权威事实 |
| --- | --- |
| `sample-content-package.json`（仓库根示例） | v1 文件的真实形状与示例值 |
| `src/main/java/com/tiku/util/PackageContainer.java` | `.tiku` 容器：`PKG_ENTRY` / `MEDIA_PREFIX` / 魔数判定 / 打包解包 / 容器上限 |
| `src/main/java/com/tiku/service/ContentPackageService.java` | 导入导出：字段序列化规则、校验规则、图片收集、checksum 算法、导入四态 |
| `src/main/java/com/tiku/service/ContentPackageInspector.java` | 桌面端「发布前体检」严格校验（与官网同口径），`KEY_RE` 等 |
| `src/main/java/com/tiku/model/ContentPackageFile.java` / `ContentPackageQuestion.java` / `ContentPackageMaterial.java` / `OptionItem.java` / `model/enums/QuestionType.java` | 文件模型：字段、类型、顺序、`@JsonInclude` 规则 |
| `src/main/java/com/tiku/service/ImageStorageService.java` | 图片命名规则、`[图片:name]` 引用正则、base64 落盘 |
| `src/main/java/com/tiku/model/Question.java`、`Material.java`、`src/main/resources/db/migration/*.sql` | 本地落库字段类型与长度（导入的实际落地约束） |
| `src/main/java/com/tiku/controller/CenterPublishController.java`、`CenterProxyController.java`、`QuestionBankController.java`、`LocalExportService.java` | 发布/导入/导出的入口与本地限流、大小校验 |
| `web/server/utils/package-meta.ts` | **官网「什么样的文件是合法内容包」的判定标准** |
| `web/server/api/packs/upload.post.ts`、`index.post.ts`、`[packageKey]/[version]/file.put.ts` | 官网服务端校验（直传 / 登记 / 补传） |
| `web/pages/packs/new.vue`、`frontend/src/views/BankListView.vue`、`frontend/src/views/BankDetailView.vue` | 前端（浏览器/桌面 UI）侧的校验与导入导出行为 |
| `frontend/src/utils/richText.js`、`src/main/java/com/tiku/service/QuestionService.java` | 富文本渲染约定（图片、公式、表格） |
| `docs/import-issues.md` | 已知导入问题（第 8 节） |

---

## 1. 两种文件形态

同一套**内容结构**（同一批 JSON 字段）有两种物理载体：

| 形态 | 扩展名 | `schemaVersion` | 图片存放 | 状态 |
| --- | --- | --- | --- | --- |
| **v2 容器** | `.tiku` | 容器内清单为 `2` | `media/` 目录下的二进制 | 推荐，桌面端导出默认 |
| **v1 纯 JSON** | `.json` | `1` | 顶层 `images` 字段内嵌 base64 | 兼容旧版拾题应用 |

依据：`PackageContainer` 类注释（v2 容器结构）、`ContentPackageService.exportContentPackage`（v1 导出写
`schemaVersion = 1`）、`exportTikuPackageWithMeta`（v2 导出写 `PackageContainer.SCHEMA_V2 = 2`）。

### 1.1 v2 `.tiku` = zip 容器

```
计算机基础测试-1.0.0.tiku        ← 本质是 zip（前 4 字节 50 4B 03 04）
├── package.json                 ← 清单（UTF-8 文本），内容结构与 v1 完全相同
└── media/                       ← 图片二进制（可选；纯文字包可以没有）
    └── 260829/
        └── ab12cd34ef56.png     ← 包内条目名 = "media/260829/ab12cd34ef56.png"
```

- 条目名常量（`PackageContainer`）：`PKG_ENTRY = "package.json"`、`MEDIA_PREFIX = "media/"`。
- `media/` 下条目的**相对路径就是图片引用名**：条目 `media/260829/ab12.png` 对应
  `[图片:260829/ab12.png]`（`ImageStorageService` 类注释、`PackageContainer.pack` 的参数说明）。
- 打包顺序固定：先写 `package.json`，再按**条目名升序**（`new TreeMap<>(media)`）写 media，
  以保证"同内容 → 同字节 → 同 `fileSha256`"（`PackageContainer.pack` 注释）。
- 空字节（`null` 或 `length == 0`）的 media 条目**不会被打包**（`PackageContainer.pack`）。
- 解包时：目录条目跳过；`package.json` 与 `media/` 前缀之外的条目**一律忽略**（如 `__MACOSX`）
  （`PackageContainer.unpack`）。

### 1.2 v1 纯 JSON（`.json`）

单文件自包含：题目、材料、图片（base64）全在一个 JSON 对象里。示例见仓库根
`sample-content-package.json`，最小示例见本文第 7 节。

- 图片 base64 为**标准 base64**（`java.util.Base64.getEncoder()`，带 `=` 填充、无换行）。
- 官方导出文件的写法：`JSON.stringify(data, null, 2)`（2 空格缩进 UTF-8）
  （`frontend/src/utils/files.js` 的 `saveJsonFile`）。

### 1.3 判定方式：按 **zip 魔数**，不是扩展名

权威判定一律是**魔数**：前 4 字节 `50 4B 03 04`（`PK\x03\x04`）⇒ `.tiku` 容器，否则按 v1 纯 JSON 解析。

| 解析方 | 判定实现 |
| --- | --- |
| 桌面端导入（后端） | `PackageContainer.isZipContainer`（`unpack` 内先判定，非法抛 `文件不是 .tiku 容器（zip 格式）`） |
| 桌面端发布体检 | `ContentPackageInspector.inspect`：`isZipContainer` ⇒ `TIKU_CONTAINER`，否则 `PLAIN_JSON` |
| 桌面端广场「拉取即导入」 | `CenterProxyController.importContentBytes`：`isZipContainer` ⇒ `importTikuPackage`，否则 `importContentPackage` |
| 官网服务端 | `web/server/utils/package-meta.ts` 的 `isZipContainer` |
| 官网发布页前端 | `web/pages/packs/new.vue` 的 `handleFile`（直接比对前 4 字节） |

**例外（重要）**：桌面端「导入题库文件」的 UI 选完文件后，是按**扩展名 / MIME** 分流到两个接口的，
不是按魔数：

```js
const isTiku = /\.tiku$/i.test(file.name) || file.type === 'application/zip'
// frontend/src/views/BankListView.vue  doImport()
```

即：一个内容是 zip 但被命名为 `.json` 的文件，从桌面 UI 导入会被当作纯文本送进
`/api/banks/import` 而失败（后端 `parseAndValidate` 报"内容包文件格式错误"）。
反向（内容是 JSON 但叫 `.tiku`）同理。**第三方工具生成文件时请让扩展名与内容一致**；
用后端接口（HTTP）导入时才是真正按魔数判定。

### 1.4 编码与其它约定

- 文本编码 **UTF-8**：容器内 `package.json` 由 `PackageContainer.pkgText` 以
  `StandardCharsets.UTF_8` 解码；前端导入以 `utf-8` 读取（`frontend/src/utils/files.js` 的 `readTextFile`）。
- **BOM**：代码中没有显式的 BOM 处理逻辑（**待确认**：浏览器 `FileReader.readAsText` 是否剥离
  BOM 未在本仓库中验证）。跨端生成文件时建议**不写 BOM**。
- `package.json` 必须是**合法 JSON 对象**：官网 `JSON.parse` 全量解析，根对象之后有多余内容会报错
  （`ContentPackageInspector.parseObject` 注释："官网走 JSON.parse 全量解析，末尾多余内容一律报错"）。
- JSON 字段顺序**不影响**解析；也不影响内容指纹（指纹只序列化 `questions` / `materials` / `images`
  三个结构，其内部顺序由 Java 类的字段声明顺序决定，见第 6.4 节）。

---

## 2. 顶层字段

字段声明顺序 = `ContentPackageFile` 中的顺序（`schemaVersion` → `packageKey` → … → `images`）。

| 字段 | 类型 | 必填 | 含义 | 约束与校验（依据） |
| --- | --- | --- | --- | --- |
| `schemaVersion` | 整数 | **是** | 内容包格式版本 | 必须是**数字** `1` 或 `2`。桌面端导入：`null` 或非 1/2 → `不支持的内容包格式版本`；官网：`字符串 "1"` 会被拒（`upload.post.ts` 用 `!==` 严格比较；`ContentPackageInspector.scalarInt` 只认数字 token，`1.0`/`2.0` 浮点视为合法，其余浮点按非法值） |
| `packageKey` | 字符串 | **是** | 内容包稳定身份，用户不可手动编辑 | 非空。官网/发布体检：`^[\p{L}\p{N}._-]{1,100}$`（`ContentPackageInspector.KEY_RE`、`upload.post.ts`/`index.post.ts` 的 `KEY_RE = /^[\p{L}\p{N}._-]{1,100}$/u`）；桌面端**导入**只查非空（`parseAndValidate`），不做正则。官方导出生成的是 `UUID.randomUUID().toString()`（`generatePackageKey`） |
| `title` | 字符串 | **是** | 内容包标题 = 题库名称 | 非空（trim 后）。登记接口 `index.post.ts` 限 120 字符、直传接口 `upload.post.ts` 对**文件内** title 不限长（表单覆盖值截断到 200） |
| `description` | 字符串 | 否 | 描述 | 发布体检/官网直传：trim 后截断到 2000（不是拒绝）；登记接口 `index.post.ts` 超 2000 直接 400 |
| `version` | 字符串 | **是** | 语义化版本号，如 `1.0.0` | 非空。发布体检/官网直传/补传：`KEY_RE`（1–100 字符）；登记接口 `index.post.ts` 额外限 40 字符。导出时缺省 `1.0.0`（`resolveVersion`）。**约定**：版本号表示"内容变化"，内容未变改版本号会被导出端拒绝（`内容未变化，无需变更版本号`） |
| `authorId` | 整数或 `null` | 否 | 作者账号 ID | 本地为 `null`；官网登记时由服务端以登录账号赋值（"不信任请求体"，`index.post.ts` 注释） |
| `authorName` | 字符串 | 否 | 作者展示名 | 官网登记时由服务端取 `user.nickname || user.username` |
| `source` | 字符串 | 否 | 来源声明 | 发布体检/官网直传截断到 500；登记接口超 500 直接 400 |
| `parentKey` | 字符串或 `null` | 否 | 派生来源（分支导入/分支导出时记录原 `packageKey`） | 存在时必须匹配 `KEY_RE`（`ContentPackageInspector.validate`、`upload.post.ts`）。由导出端生成：`mode=BRANCH` 或 AUTO 检测到内容已改时（`exportContentPackage`） |
| `checksum` | 字符串或 `null` | 否 | 内容指纹（SHA-256 十六进制，64 字符） | **文件本体不应含此字段**：v1 导出把 checksum 放在**响应**里，前端保存文件前 `delete pkg.checksum`（`BankDetailView.vue` 第 1874 行）；v2 导出显式 `setChecksum(null)`。导入端**忽略**该字段（不参与 checksum 计算）。登记接口若携带：必须 `^[0-9a-fA-F]{64}$`（`index.post.ts` 的 `HEX64_RE`） |
| `sources` | 字符串数组或 `null` | 否 | 混编来源数组 | 本地以 JSON 文本存 `question_bank.sources`（`serializeSources`）；导出时 `parseSources` 解析失败返回 `null`。官网登记不使用该字段 |
| `createdAt` | 字符串（ISO 本地时间） | 否 | 文件创建时间 = 导出时刻 | 形如 `2026-08-25T12:00:00`（`LocalDateTime.now()`，无时区偏移）。**导入不校验**（`docs/content-package-spec.md` 原文）。本项目未配置 Jackson 日期格式（`application.yml` 无 `spring.jackson.*`），实际格式由 Spring Boot 默认的 ISO-8601 决定（**待确认**：是否有意固定为 `yyyy-MM-dd'T'HH:mm:ss`） |
| `questions` | 对象数组 | **是**（发布时） | 题目数组，见第 3 节 | 桌面端导入：`null` 允许（内部置为空列表，`importContentPackage`）；发布体检/官网直传：**必须是非空数组**，否则 `题库文件中没有题目，无法发布`（`ContentPackageInspector`、`upload.post.ts`） |
| `materials` | 对象数组 | 否 | 共享材料数组（大题共用题干），见第 3.4 节 | 每项的 `materialKey` 非空，否则导入报 `内容包材料缺少 materialKey`（`insertMaterials`） |
| `images` | 对象（`name → base64`） | 否 | 图片资源；v1 内嵌 base64 | 键必须与正文 `[图片:name]` 引用一致；值为标准 base64。v2 容器内该字段的值为 `null`（见第 8.2 节第 1 条） |

**补充事实**

- `schemaVersion` 的"两种形态同结构"是明确的设计：`ContentPackageInspector` 注释——"两种格式的顶层字段名
  完全一致（v2 的 manifest 就是同一份 JSON 结构装进 zip）"。
- 未在模型里声明的额外顶层字段会被**忽略**（Spring Boot 默认关闭
  `FAIL_ON_UNKNOWN_PROPERTIES`；`package-meta.ts` 只按名字取它关心的字段）。
  **待确认**：项目未显式配置该项，属于框架默认行为。
- 官网直传路径 `upload.post.ts` **不读** `checksum` / `authorId` / `authorName` / `sources` / `createdAt`；
  登记接口 `index.post.ts` 有一个历史字段 `manifestVersion`（仅旧粘贴路径携带，非 1 报错）。

---

## 3. 题目对象（`questions[]`）

### 3.1 字段表

字段声明顺序 = `ContentPackageQuestion` 中的顺序。

| 字段 | 类型 | 必填 | 含义 | 约束与校验（依据） |
| --- | --- | --- | --- | --- |
| `questionKey` | 字符串 | **是** | 题目唯一标识（= 本地 `question.external_id`） | 桌面端导入：非空，否则 `题目缺少 questionKey`（`parseAndValidate`）。同一内容包内应唯一（本地 `question_bank` 内 `UNIQUE (bank_id, external_id, deleted)`，V1 迁移）。**跨版本应保持不变**（内容包模型注释）。不推荐用 `{type}-{序号}` 作为 key（`docs/content-package-spec.md`）。注意：本地列 `external_id VARCHAR(64)`，超长会落库失败，但**文件级校验不检查长度** |
| `volume` | 整数 | 否 | 册数 | 导入时 `null → 0`（`importQuestionsToBank`）。本地列 `INT NOT NULL DEFAULT 0` |
| `type` | 字符串 | **是** | 题型，见 3.2 | 必须是枚举名之一，`QuestionType.valueOf(...)` 抛出即报 `未知题型：xxx`（`parseAndValidate`）。**大小写敏感**，必须全大写；`null` 会走到同一条错误分支 |
| `content` | 字符串 | **是** | 题干 | 非空（trim 后），否则 `题目题干为空：{questionKey}`（`parseAndValidate`）。本地列 `content TEXT NOT NULL` |
| `options` | 对象数组 | 否（但客观题形式必需） | 选项 `[{ "key": "A", "text": "…" }]` | 结构 = `OptionItem(String key, String text)`。**文件级校验不检查**选项数量、key 唯一性、key 是否在 `answerKeys` 中；表单录入侧另有要求（`QuestionFormPanel.vue`）。本地以 JSON 文本存 `question.options`（`OptionItemTypeHandler`） |
| `answerKeys` | 字符串数组 | 否（但客观题形式必需） | 正确答案 key 数组，如 `["B"]`、`["A","C","D"]` | 导入时逐项 `trim`、丢弃空白项，再以**英文逗号**拼成 `question.answer_keys`（`importQuestionsToBank`）；全空则为 `null`。导出时反向按 `,` 切分（`toFileQuestions`）。本地列 `answer_keys VARCHAR(255)` |
| `answerText` | 字符串 | 否 | 答案文字 | 本地列 `answer_text VARCHAR(500)`；文件级不校验长度 |
| `analysis` | 字符串 | 否 | 解析 | 富文本（图片/公式/表格约定同题干） |
| `topic` | 字符串 | 否 | 主题/知识点 | 本地列 `topic VARCHAR(100)` |
| `category` | 字符串 | 否 | 分类 | 本地列 `category VARCHAR(100)` |
| `score` | 小数 | 否 | 分值 | 导入时 `null → 1.0`（`importQuestionsToBank`）。本地列 `score DECIMAL(6,1) NOT NULL`（V7 迁移，**只有 1 位小数**）——文件里的 `2.25` 会被库精度截断。注意：模型注释写"默认 1（主观题默认 5）"，但**导入路径对 `SUBJECTIVE` 也一律给 1.0**（`importQuestionsToBank` 无题型分支），"主观题默认 5"未见实现依据（**待确认**） |
| `answerSource` | 字符串或 `null` | 否 | 答案来源标记：`ORIGINAL`（原文提供）/ `AI_SUPPLEMENT`（AI 补充） | `@JsonInclude(NON_NULL)`（`null` 不序列化）。**仅供 AI 导入预览链路内部使用**：桌面端导出**不写**该字段（`toFileQuestions` 未设置），导入**不读**该字段（`importQuestionsToBank` 未使用，本地 `question` 表也没有对应列）。第三方若写入，导入时会被静默忽略 |
| `referenceAnswer` | 字符串或 `null` | 否 | 主观题参考答案（文字 + `[图片:name]` 标记） | `@JsonInclude(NON_NULL)`。`SUBJECTIVE` 专用（模型注释）；富文本按同题干渲染 |
| `materialKey` | 字符串或 `null` | 否 | 引用共享材料（`materials[]` 中的 key） | `@JsonInclude(NON_NULL)`。导入时若在 `materials` 中找不到对应 key → 报 `题目引用的材料不存在：{key}`（`importQuestionsToBank`）。导出时生成 `material-{本地id}`（`exportContentPackage`） |
| `questionNumber` | 整数或 `null` | 否 | 题号 | `@JsonInclude(NON_NULL)`。**导入**：给了就用，没给则按"当前库最大题号 + 1"递增（`importQuestionsToBank`）。**导出**：`toFileQuestions` **不写该字段**——题号不随内容包传递，往返导入后会重新编号（见第 8.2 节第 3 条） |

### 3.2 题型枚举（`model/enums/QuestionType.java`）

| 取值 | 含义 | 常见约定 |
| --- | --- | --- |
| `SINGLE` | 单选题 | 选项 `key` 用 `A`/`B`/`C`/`D`…；`answerKeys` 通常 1 项 |
| `MULTIPLE` | 多选题 | 选项 `key` 用 `A`/`B`/`C`/`D`…；`answerKeys` 通常 ≥2 项（格式不强制） |
| `JUDGE` | 判断题 | 选项固定 `A = 正确`、`B = 错误`（`sample-content-package.json` 全部判断题；`AnswerFillService` 的提示词亦为"判断题选项固定 A=正确/B=错误"）；答案 `["A"]` = 正确、`["B"]` = 错误 |
| `SUBJECTIVE` | 主观题 | 无选项、无答案键；用 `referenceAnswer`；不自动判分（`docs/features.md`） |

> 注意：`docs/content-package-spec.md` 的题目字段表只列了三种题型，实际枚举有四种；
> 请以 `QuestionType.java` 为准。

### 3.3 富文本约定

题干 `content`、选项 `text`、`analysis`、`referenceAnswer`、`materials[].content` 共用同一套纯文本
富文本约定，由前端 `frontend/src/utils/richText.js` 渲染。

#### 3.3.1 图片：`[图片:文件名]`

- 标记语法：`[图片:name]`，**全角冒号**，方括号为半角。`name` 形如 `260829/ab12cd34ef56.png`
  （日期目录 + 文件名，可含一层目录）。
- 解析/收集用的正则（**注意两处口径不同**）：
  - `ImageStorageService.IMAGE_REF = \[图片:([a-zA-Z0-9._/-]+)]` —— 导出端收集图片时用这个，
    **名字只允许字母、数字、点、下划线、斜杠**（不含中文、空格等）。
  - `QuestionService.IMAGE_REF_NAMED = \[图片:([^\]]+)]`、前端 `renderImages` 用
    `/\[图片:([^\]]+)\]/g`（再加白名单 `^[a-zA-Z0-9._/-]+$` 校验后才渲染）。
  - 结论：**`name` 只用 `[A-Za-z0-9._-]` 且最多一层目录**，两端行为才一致。
- `[图片1]` / `[图片2]`（**数字编号**）是 AI 导入预览阶段的**临时标记**，不是内容包格式：
  确认导入时会被替换为 `[图片:正式文件名]`（`AiImportService` 第 1004 行附近、`[图片N] → [图片:name]` 映射）；
  渲染层只认 `[图片:name]`（`QuestionService.normalizeLatex` 注释）。第三方生成内容包时**不要**使用 `[图片N]`。
- 图片与文本混排：标记写在它出现的位置即可（题干末尾、选项内、材料内），渲染时替换为
  `<img src="/api/banks/{bankId}/images/{name}">`。

#### 3.3.2 公式：LaTeX（KaTeX 渲染）

- **推荐写法：行内 `$...$`**（`richText.js` 的 `DOLLAR_LATEX_RE = /(\$[^$]+\$)/g`，定界内整段交给 KaTeX）。
- 兼容"无定界的 LaTeX 命令片段"：`\命令{参数}`（如 `\frac{1}{2}`、`\sqrt{3}`）也会被单独渲染
  （`LATEX_FRAGMENT_RE`）——这是历史（MinerU 输出）兼容路径，新内容建议统一用 `$...$`。
- 归一化：后端把 `\(...\)` → `$`、`\[...\]` → `$$`（`QuestionService.normalizeLatex`）。
- **`$$...$$` 不被整体识别**：`DOLLAR_LATEX_RE` 只匹配 `$非$字符$`，`$$x$$` 会被拆成
  `$` + 公式 + `$` 显示（代码事实）。**块级公式请避免使用 `$$`**，或按 `$...$` 书写。
- 渲染失败会回退为等宽源码显示（`katexHtml` 的 `latex-fallback`），不会报错或留白。

#### 3.3.3 表格与其它

- 表格用**真实 HTML** `<table>…</table>` 内嵌于文本（MinerU 路径的既有输出）：渲染层只保留白名单标签
  `table/tr/td/th/tbody/thead/tfoot/caption` 与属性 `colspan/rowspan`，其它标签剥除
  （`richText.js` 的 `sanitizeTableHtml`）。
- **Markdown 表格语法（`| --- |`）不被渲染**，会原样显示为文本——这正是 `docs/import-issues.md` 第 1 条的成因。
- 换行：`\n` 渲染为 `<br>`（`richTextToHtml`）。
- 除上述三类标记（图片、`$…$` 公式、`<table>` 块）外，其余文本一律按纯文本处理并做 HTML 转义
  （`&`、`<`、`>`、`"` 会显示为字面字符），不解析其它标记或样式。

### 3.4 材料（`materials[]`）与 `materialKey`

`ContentPackageMaterial` 只有两个字段：

| 字段 | 类型 | 必填 | 含义 | 约束（依据） |
| --- | --- | --- | --- | --- |
| `materialKey` | 字符串 | **是** | 材料在**本内容包内**唯一标识 | 导入时非空，否则 `内容包材料缺少 materialKey`（`insertMaterials`） |
| `content` | 字符串 | 否 | 材料内容（文字 + `[图片:name]` + `<table>`） | 本地列 `material.content TEXT NOT NULL`（V7 迁移）：**导入 `null` 会落库失败**（文件级不校验，**待确认**：是否需要显式默认空串） |

关联规则：

- 题目通过 `materialKey` 引用材料；导入后映射为本地 `material.id`，写入 `question.material_id`。
- 一个材料可被多道题引用（资料分析大题）。
- 导出时材料 key 生成为 `material-{本地 id}`（**跨导出版本不稳定**：同一材料在不同题库/不同库内 id
  不同 → key 不同；材料内容本身参与 checksum）。导出范围过滤（错题/收藏/打印）时**只导出被引用题目的材料**。
- 材料在库内按 `sort_order`（导出顺序）排序，导入时按数组顺序从 0 递增写 `sort_order`。

---

## 4. 图片与媒体

### 4.1 命名规则（后端生成，第三方须遵守）

`ImageStorageService.upload` / `importImage` 生成的引用名：

```
{yyMMdd}/{12位十六进制}.{ext}          例：260829/ab12cd34ef56.png
```

- 目录 = 上传/导入当天的 `yyMMdd`（`DateTimeFormatter.ofPattern("yyMMdd")`）。
- 文件名 = UUID 去连字符后前 12 位；`ext` ∈ `png | jpg | jpeg | gif | webp | bmp`
  （上传按原始扩展名白名单，AI 导入按字节头探测，缺省 `png`）。
- 单张图片上限 **10MB**（`MAX_IMAGE_BYTES = 10 * 1024 * 1024`，上传与导入两处）。
- **安全名正则（导入/读取时生效）**：
  `SAFE_NAME = ^[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._-]+)?$` —— 只允许**恰好一层可选目录**。
  不符合的名字在 `saveBase64`（导入落盘）与 `copyToBank` 中**静默跳过**（不报错）。
- 无目录的名字也允许（`foo.png`），导入时落到 `${bankId}/misc/foo.png`；导出时引用名仍是 `foo.png`。
- `media/` 下多层目录（如 `a/b/c.png`）虽然 `PackageContainer.unpack` 接受，但导入落盘时会被
  `SAFE_NAME` 拒绝并静默丢弃——**请勿生成多层目录**。

### 4.2 引用与数据的一致性

| 环节 | 行为（依据） |
| --- | --- |
| 导出收集（v1 与 v2 相同） | 扫描题目 `content`、`referenceAnswer`、各选项 `text`、**全部材料** `content` 中的 `[图片:name]`，去重后读本地文件 → base64 map（`ImageStorageService.collectBase64`，`ContentPackageService.exportContentPackage`）。**引用但文件缺失 → 跳过该图**（不报错，包内缺图） |
| v1 写文件 | `images` = `{ name: base64 }` 内嵌 |
| v2 写文件 | base64 → 二进制写入 `media/{name}`；清单里 `images` 置 `null`（`exportTikuPackageWithMeta`） |
| 导入落盘 | v1：`saveBase64` 逐项写 `${data-dir}/images/{bankId}/{name}`；v2：`unpack` 出的 media 先转回 base64 map 再走同一条路径（`importTikuPackage`）。**单张失败（名字非法 / base64 非法）静默跳过，不影响其余图片与题目导入** |
| 正文引用 | 正文里的 `[图片:name]` 文本**原样保留**（导入不重写引用），因此 v1↔v2 转换、跨库复制后引用仍然有效 |

要点：**引用名就是 media 条目路径**，导入端不重新命名（因为是内容寻址 + 名字即引用）。
因此第三方工具生成 `.tiku` 时，`media/` 下的条目名必须与正文 `[图片:name]` 完全一致（含日期目录）。

### 4.3 checksum 中图片的统一表示

无论 v1 还是 v2，图片都先转成 **base64 map** 再参与指纹计算（v2 由导入端从 `media/` 重建），
所以**同内容的 `.json` 与 `.tiku` 指纹一致**，跨格式导入不会误判（`importTikuPackage` 注释、
`PackageContainer` 类注释）。算法细节见第 6.4 节。

---

## 5. 校验规则："什么样的文件是合法的"

### 5.1 校验点归属

| 代号 | 校验点 | 代码位置 | 严格程度 |
| --- | --- | --- | --- |
| **A** | 桌面端**导入**（`/api/banks/import`、`/api/banks/import-tiku`、广场"拉取即导入"） | `ContentPackageService.parseAndValidate` | 宽松：只要能落库 |
| **B** | 桌面端**发布前体检 + 转发前把关**（`/api/center/publish/inspect`、`publish`、补传） | `ContentPackageInspector`（+ `CenterPublishController.checkFile` / `checkRegisteredIdentity`） | 严格：与官网同口径，不合法**一个字节都不出网** |
| **C** | 官网**发布页前端**（选文件后即时提示） | `web/pages/packs/new.vue` `handleFile` | 宽松 UI 前置提示 |
| **D** | 官网**服务端直传发布** | `web/server/utils/package-meta.ts` + `web/server/api/packs/upload.post.ts` | 严格（判定"合法内容包"的权威标准） |
| **E** | 官网**服务端登记**（老路径：manifest + 外链） | `web/server/api/packs/index.post.ts` | 比 D 多若干长度限制 |
| **F** | 官网**服务端补传托管文件** | `.../[packageKey]/[version]/file.put.ts` | D 的全部 + 与登记一致性 |

### 5.2 A：桌面端导入（宽松）

| # | 规则 | 失败提示 |
| --- | --- | --- |
| A1 | 必须能按 UTF-8 反序列化为内容包对象 | `内容包文件格式错误：{原因}` |
| A2 | `schemaVersion` ∈ {1, 2} | `不支持的内容包格式版本：{值}` |
| A3 | `packageKey` 非空白 | `内容包缺少 packageKey` |
| A4 | `title` 非空白 | `内容包缺少标题` |
| A5 | `version` 非空白 | `内容包缺少版本号` |
| A6 | 每题 `questionKey` 非空白 | `题目缺少 questionKey` |
| A7 | 每题 `content` 非空白 | `题目题干为空：{questionKey}` |
| A8 | 每题 `type` 能 `QuestionType.valueOf` | `未知题型：{值}` |
| A9 | 材料 `materialKey` 非空白 | `内容包材料缺少 materialKey` |
| A10 | 题目的 `materialKey` 必须能在 `materials` 中找到 | `题目引用的材料不存在：{key}` |
| A11 | v2 容器：见 5.5 的容器限制 | `.tiku 容器…` 系列 |

**不校验**：`questions` 是否为空（空题库可以导入成 0 题的题库）、`options`/`answerKeys` 是否存在、
各类字符串长度、`images` 的键是否为合法图片名（非法名静默丢弃）、`questionKey` 是否重复
（重复会撞本地唯一约束 `(bank_id, external_id, deleted)` → 落库异常）。

### 5.3 B/C/D/E/F：发布侧（严格，与官网同口径）

| # | 规则 | A/B/C/D/E/F | 失败提示（官网文案） |
| --- | --- | --- | --- |
| 1 | zip 魔数 ⇒ 容器；否则纯 JSON | B C D E F | （无提示，是分流逻辑） |
| 2 | 容器条目数 ≤ 4096 | B D | `.tiku 容器条目过多` |
| 3 | 容器内必须有 `package.json` 条目 | B D | `.tiku 容器缺少 package.json` |
| 4 | `package.json` ≤ 10MB | B D | `.tiku 容器 package.json 过大` / `题库文件解析失败：.tiku 容器内 manifest（package.json）过大` |
| 5 | `package.json` 必须是合法 JSON 对象 | B D | `.tiku 容器内 package.json 不是合法 JSON` |
| 6 | 纯 JSON 必须能 `JSON.parse`（末尾不能有多余内容） | B C D | `文件不是合法的题库文件（v1 JSON 或 .tiku 容器）` |
| 7 | `schemaVersion` 必须是**数字** 1 或 2 | B C D E F | `题库文件 schemaVersion 需为 1 或 2`（纯 JSON 且缺字段时桌面端给 `.json` 专属文案：`该 .json 不是 v1 题库文件（缺少 schemaVersion=1）`） |
| 8 | `packageKey` 非空且匹配 `^[\p{L}\p{N}._-]{1,100}$` | B D E F | `题库文件 packageKey 不合法`（`packageKey 不合法（1–100 位字母数字._-）`） |
| 9 | `version` 非空且匹配同一正则（E 额外 ≤40 字符） | B D E F | `题库文件 version 不合法` |
| 10 | `title` 非空（E ≤120；D 的表单覆盖值 ≤200） | B C D E | `题库文件缺少标题` / `缺少标题 title` |
| 11 | `parentKey` 若存在必须匹配同一正则 | B D E | `parentKey 不合法` |
| 12 | `questions` 必须是**非空数组**（D 用 `Array.isArray`，非数组按 0 计） | B C D F | `题库文件中没有题目，无法发布`（纯 JSON 时桌面端给 `该 .json 不是 v1 题库文件（缺少 questions）`） |
| 13 | `description` ≤ 2000（D 与 B 截断；E 直接 400）。发布页表单 UI 另限 200 字符（`new.vue` 的 `maxlength="200"`） | B D E（+C 的 UI 限长） | `字段长度超出限制（2000）` |
| 14 | `source` ≤ 500（同上）。发布页表单 UI 限 500（`new.vue` 的 `maxlength="500"`） | B D E | `字段长度超出限制（500）` |
| 15 | 整文件 ≤ 200MB | B D F（C 只对 >100MB 提示拆包，不拦截） | `文件超过 200MB 上限` |
| 16 | `checksum`（若随登记携带）为 64 位十六进制 | E | `checksum 需为 64 位十六进制（SHA-256）` |
| 17 | `fileSha256`（若携带）为 64 位十六进制 | E | `fileSha256 需为 64 位十六进制（文件字节 SHA-256）` |
| 18 | 补传：文件内 `packageKey`、`version` **与登记完全一致** | B（本地先查） F | `文件内 packageKey 与登记不一致` / `文件内 version 与登记不一致` |
| 19 | 补传：字节 SHA-256 与登记一致（若登记已带） | F | `文件字节指纹（SHA-256）与登记不符，文件可能被修改…` |
| 20 | 补传：文件大小与登记一致（若登记已带 `fileSizeBytes`） | F | `文件大小与登记不符（登记 N 字节，实际 M 字节）` |
| 21 | 发布频率：每用户每分钟 ≤10 次 | D E | `操作太频繁，请稍后再试`（429） |
| 22 | `downloadUrl` 为合法 http(s) 链接（EXTERNAL 必填、HOSTED 必须为空） | D E | `downloadUrl 需为合法的 http(s) 链接` |

> C 的额外限定（发布页前端）：只做"能否选这个文件"的即时反馈——`schemaVersion` 1/2、
> `packageKey`/`version`/`title` 存在、`questions` 长度 > 0；文件 > 100MB 只提示"建议按章节拆包"，
> **不强制**（`oversize` 标记，`new.vue`）。

> 关键点：**官网只读元数据，不解析题目内容**（`package-meta.ts` 头注释："服务端只取元数据，
> 不解析题目内容——口径见 center-spec §3"）。因此官网**不会**校验 `questionKey`、`content`、
> `type` 等题目内字段；那些只在桌面端导入时校验。

### 5.4 命名与长度速查表

| 项 | 规则 | 来源 |
| --- | --- | --- |
| `packageKey` / `version` / `parentKey` | `^[\p{L}\p{N}._-]{1,100}$`（Unicode 字母/数字、点、下划线、连字符；**允许中文等 `\p{L}` 字符**） | `ContentPackageInspector.KEY_RE`、`upload.post.ts`/`index.post.ts` 的 `KEY_RE`、`LocalExportService.VERSION_RE` |
| `version`（登记接口） | 额外 ≤ 40 字符 | `index.post.ts` `str(body.version, 40)` |
| `title` | ≤ 120（登记）/ ≤ 200（直传表单覆盖值）；文件内不限 | `index.post.ts`、`upload.post.ts` |
| `description` | ≤ 2000 | 同上 |
| `source` | ≤ 500 | 同上 |
| `questionKey` | 无正则校验；本地列 `VARCHAR(100)`（V15 由 64 放宽） | `parseAndValidate`、（V1 迁移 + `V15__widen_contract_columns.sql`） |
| 图片名 | `^[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._-]+)?$`（≤ 一层目录） | `ImageStorageService.SAFE_NAME` |
| 图片引用名（收集用） | `[a-zA-Z0-9._/-]+` | `ImageStorageService.IMAGE_REF` |
| `checksum` / `fileSha256` | `^[0-9a-fA-F]{64}$` | `index.post.ts` `HEX64_RE` |

**互操作建议（按最保守值写文件，两端都不会出问题）**

> 2026-09-11 起本地列宽已与契约对齐（`V15__widen_contract_columns.sql`）：
> 「官网允许的长度」现在都 ≤ 本地列宽，所以**合法的包一定能导入**。
> 下表保留为「第三方工具生成文件时的保守建议」，不再是硬性约束。

| 字段 | 建议上限 | 理由 |
| --- | --- | --- |
| `title` | ≤ 120 字符 | 本地 `question_bank.name VARCHAR(120)`（登记接口同口径） |
| `description` | ≤ 2000 字符 | 本地 `description VARCHAR(2000)` = 契约上限，两端一致 |
| `source` | ≤ 500 字符 | 本地 `source VARCHAR(500)` = 契约上限 |
| `packageKey` / `parentKey` | ≤ 100 字符 | 本地 `package_key` / `parent_key VARCHAR(100)` = 校验正则上限 |
| `version` | ≤ 40 字符 | 本地 `version VARCHAR(40)`（登记接口限 40；校验正则允许 100） |
| `questionKey` | ≤ 100 字符 | 本地 `external_id VARCHAR(100) NOT NULL`（与 `KEY_RE` 同口径） |
| `answerKeys` 拼接后 | ≤ 255 字符 | 本地 `answer_keys VARCHAR(255)`（未放宽：足够容纳常规选项数） |
| `answerText` | ≤ 2000 字符 | 本地 `answer_text VARCHAR(2000)` |
| `topic` / `category` | ≤ 100 字符 | 本地列定义 |
| `score` | 一位小数 | 本地 `DECIMAL(6,1)` |
| 单张图片 | ≤ 10MB | `ImageStorageService.MAX_IMAGE_BYTES` |

（本地列定义依据：`src/main/resources/db/migration/V1__init_schema.sql`、`V7__subjective_material.sql`、`V15__widen_contract_columns.sql`。
这条对齐关系由测试 `ContractColumnWidthTest` 守着：列宽被改窄会直接测试失败。）

### 5.5 容器安全上限

| 限制 | 桌面端 `unpack`（真导入） | 桌面端 `readPackageJson`（体检/只读） | 官网 `package-meta.ts` |
| --- | --- | --- | --- |
| 条目数 | ≤ 4096（`MAX_ENTRIES`；**目录条目也计数**） | ≤ 4096 | ≤ 4096 |
| 单条解压后 | ≤ 100MB（`MAX_ENTRY_BYTES`，按条目声明的 size 判断） | —（media 只丢弃读取，不落盘） | — |
| 解压总量 | ≤ 512MB（`MAX_TOTAL_BYTES`） | 丢弃累计 ≤ 512MB（防 zip 炸弹） | —（`unzipSync` 全量解压到内存） |
| manifest | 与其它条目同限 | ≤ 10MB（`MAX_PKG_ENTRY_BYTES`） | ≤ 10MB |
| 路径穿越 | `..` 或 `/` 开头 ⇒ 直接拒绝（`非法路径`） | **不校验**（不落盘，注释已说明） | 不校验（不落盘） |
| 整文件 | —（由调用方限） | — | 容器 > 200MB 直接拒 |
| 其它条目 | 忽略（`__MACOSX` 等） | 忽略 | 忽略 |

> 精度说明（代码事实）：`unpack` 的单条上限用的是 `entry.getSize()`；对声明 size 为 `-1`
> 的流式条目该检查不触发，只受 512MB 总量约束，而总量是在**该条目读完之后**才判定
> （`PackageContainer.unpack` 的 `readAll` + `total` 累加顺序）。官网路径没有单条/总量上限，
> 只有 200MB 文件上限与 10MB manifest 上限（`unzipSync` 全量解压）。**双方对 zip 炸弹的
> 防护强度不一致**（已在第 8.3 节第 6 条记为待确认项）。

---

## 6. 版本演进与兼容

### 6.1 v1 → v2 差异

| 维度 | v1（`.json`） | v2（`.tiku`） |
| --- | --- | --- |
| `schemaVersion` | `1` | 容器内清单写 `2`（`PackageContainer.SCHEMA_V2`） |
| 物理形态 | 单个 JSON 文本 | zip 容器：`package.json` + `media/**` |
| 图片 | `images` map 内嵌 base64（膨胀约 37%） | `media/` 二进制；清单 `images` 为 `null` |
| `checksum` | 不在文件内（导出响应携带，前端保存前删除） | 不在文件内（导出显式置 `null`） |
| 内容结构 | **完全一致**：同一批顶层字段名、同一题目/材料/选项结构 | **完全一致**（同左） |
| 解析代价 | 需整文件 `JSON.parse` | 只读 `package.json`（体检走 zip 中央目录随机读） |
| 上限 | 官网单文件 200MB | 官网单文件 200MB（内容容量因无 base64 膨胀而更大） |
| 桌面端文件名 | `{题库名}-{version}.json`（另存为对话框决定） | `{题库名或packageKey}-{version}.tiku`（导出到目录时，`LocalExportService.buildFileName`，含 Windows 非法字符清理） |

### 6.2 向后兼容策略

1. **导入双兼容**：桌面端与官网都按魔数识别，v1 `.json` 永远可导入（`ContentPackageInspector` 类注释、
   `package-meta.ts`）。桌面端 UI 的扩展名分流是唯一例外（见 1.3）。
2. **v2 容器内仍是同一份 JSON**：`ContentPackageService.exportTikuPackageWithMeta` 内部先调用
   `exportContentPackage`（v1 决策与结构），只把 `schemaVersion` 改成 2、剥离 `images`/`checksum`、
   把图片搬进 `media/`。因此**任何能读 v1 清单的代码只要忽略 `schemaVersion` 就能读 v2 清单**。
3. **checksum 跨格式一致**：图片统一以 base64 参与，v1/v2 同内容指纹相同 → 重复导入判定
   （`ALREADY_IMPORTED` / `BRANCHED`）不受文件形态影响（`importTikuPackage` 注释）。
4. **新增字段向后兼容**：`materials` / `images` / `referenceAnswer` / `materialKey` / `answerSource` /
   `questionNumber` 都是 v1 结构上的**可选扩展**（`ContentPackageFile` 注释："v1.1 可选扩展"），
   缺失即按"没有"处理，旧文件不需要迁移。
5. **导出端默认**：桌面端导出弹窗默认 `.tiku`，可另选 `.json 纯文本`（兼容旧版拾题应用）
   （`BankDetailView.vue` 的 `formatOptions`）。

### 6.3 导入结果四态与身份规则（`ContentPackageService.importContentPackage`）

| 场景 | 条件 | 行为 | `result` |
| --- | --- | --- | --- |
| 全新 | `packageKey` 不存在 | 新建题库 + 题目 | `CREATED` |
| 版本并存 | 同 `packageKey`、不同 `version` | 新建题库（旧版本保留） | `VERSION_ADDED` |
| 已导入过 | 同 `packageKey` + 同 `version` + 同 checksum | 跳过 | `ALREADY_IMPORTED` |
| 内容已改 | 同 `packageKey` + 同 `version` + checksum 不同 | **分支**：新 `packageKey` + `parentKey` 指向原包 | `BRANCHED` |

### 6.4 checksum（内容指纹）算法

`ContentPackageService.computeChecksum`：

1. `hasExtension = (materials 非空) || (images 非空)`；
2. 有扩展时，序列化 `{ "questions": [...], "materials": [...], "images": {键升序} }`
   （`LinkedHashMap` 固定 questions→materials→images 顺序，images 用 `TreeMap` 排序）；
   无扩展时退化为**只序列化 `questions` 数组**（与旧版本完全兼容）；
3. 用 Jackson 默认设置序列化成 JSON 字符串（对象内字段顺序 = Java 类字段声明顺序），
   UTF-8 字节 → SHA-256 → **小写十六进制 64 字符**；
4. **顶层元数据（`schemaVersion`/`packageKey`/`version`/`title`/`createdAt`/`author*`/`source`/
   `parentKey`/`sources`/`checksum`）一律不参与**——改名、改版本号、改描述不会改变指纹。

注意：v2 导入时 `images` 是**整份 media map**（含未被正文引用的图片条目），因此第三方工具多放
一张没人引用的图，也会让指纹与"桌面端导出的同内容包"不同。

---

## 7. 完整示例

### 7.1 最小可导入的 `.json`（通过 A 与 D 的全部校验）

`schemaVersion = 1`、`packageKey` 为 UUID、`version = 1.0.0`（匹配 `KEY_RE`）、
`questions` 非空且每题有 `questionKey`/`type`/`content`。

```json
{
  "schemaVersion": 1,
  "packageKey": "d4f8b3c2-9a1e-4f6d-8c5b-2e7a1b3c5d9f",
  "title": "计算机基础测试",
  "description": "最小可导入示例：1 道单选题",
  "version": "1.0.0",
  "authorId": null,
  "authorName": "示例作者",
  "source": "自编示例",
  "parentKey": null,
  "sources": [],
  "createdAt": "2026-08-25T12:00:00",
  "questions": [
    {
      "questionKey": "CS-SINGLE-001",
      "volume": 1,
      "type": "SINGLE",
      "content": "二进制数 1010 转换为十进制是多少？",
      "options": [
        { "key": "A", "text": "8" },
        { "key": "B", "text": "10" },
        { "key": "C", "text": "12" },
        { "key": "D", "text": "14" }
      ],
      "answerKeys": ["B"],
      "answerText": "10",
      "analysis": "1010 = 1×2³ + 0×2² + 1×2¹ + 0×2⁰ = 8 + 2 = 10",
      "topic": "进制转换",
      "category": "计算机基础",
      "score": 1
    }
  ]
}
```

说明：
- `checksum` 不写（写了导入端也会忽略）；`materials` / `images` 可省略。
- 想让它也能被**发布**到广场，以上已满足第 5.3 节的必填项（第 12 条要求 `questions` 非空）。

### 7.2 带共享材料 + 图片 + 公式的片段

```json
{
  "materials": [
    {
      "materialKey": "material-1",
      "content": "根据下表回答问题。\n[图片:260829/ab12cd34ef56.png]\n<table><tr><td>年份</td><td>产量</td></tr><tr><td>2024</td><td>12.5</td></tr></table>"
    }
  ],
  "questions": [
    {
      "questionKey": "DATA-001",
      "volume": 1,
      "type": "MULTIPLE",
      "content": "以下说法正确的是（　　）。公式 $E=mc^2$ 中的 $c$ 表示光速。",
      "options": [
        { "key": "A", "text": "选项文字" },
        { "key": "B", "text": "[图片:260829/cd34ef56ab12.png]" }
      ],
      "answerKeys": ["A", "B"],
      "analysis": "解析可含公式 $\\frac{1}{2}mv^2$ 与图片 [图片:260829/ab12cd34ef56.png]",
      "topic": "资料分析",
      "category": "行测",
      "score": 2,
      "materialKey": "material-1",
      "questionNumber": 1
    }
  ],
  "images": {
    "260829/ab12cd34ef56.png": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AF+7h0AAAAASUVORK5CYII=",
    "260829/cd34ef56ab12.png": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AF+7h0AAAAASUVORK5CYII="
  }
}
```

（两段 base64 为 1×1 PNG 占位，仅示范格式。`images` 的键必须与 `[图片:...]` 中的 `name` 逐字一致。）

### 7.3 同一内容的 `.tiku` 容器

```
计算机基础测试-1.0.0.tiku         ← 文件前 4 字节 = 50 4B 03 04
├── package.json                  ← 第 7.2 节的同一份清单结构（这里沿用第 7.1 节的题库名作示意），但：
│                                    · schemaVersion = 2
│                                    · images 的值为 null（图片在 media/）
│                                    · 不含 checksum（导出时已置 null）
└── media/
    ├── 260829/
    │   ├── ab12cd34ef56.png      ← 对应 [图片:260829/ab12cd34ef56.png]
    │   └── cd34ef56ab12.png
```

`media/` 下条目的**相对路径**（`260829/ab12cd34ef56.png`）就是清单与正文里的图片引用名。

---

## 8. 注意事项与已知问题

### 8.1 与格式相关的已知导入问题（摘自 `docs/import-issues.md`）

`docs/import-issues.md` 记录的是**源文档解析阶段**（AI 导入）的问题，状态为"仅记录，暂不修复"。
其中与格式/渲染契约直接相关的：

| # | 问题 | 与格式契约的关系 |
| --- | --- | --- |
| 1 | PDF 表格导出为 Markdown 表格语法（`\| --- \|`），但渲染出来不是表格 | **格式契约**：正文里的表格只认 `<table>` HTML，**Markdown 表格语法不被渲染**（`richText.js` 白名单）。生成内容包时表格必须用 `<table>` |
| 2 | 选项数量错认（4 个变 8 个，多出的为空） | 解析阶段问题；格式本身不校验选项数量，多出的空选项会原样入库（`options` 数组不校验） |
| 3 | 下划线标记的长段落无法同款导入 | **格式契约**：正文是纯文本 + 图片标记 + LaTeX + `<table>`，**没有段落级样式（下划线/加粗/颜色）模型**，无法保真 |
| 4 | 卷内题号分段（各 Part 从 1 重新编号）导致漏题 | **格式契约相关**：`questionNumber` 在文件里允许重复（不校验唯一性），但本地列表/做题按 `question_number, id` 排序（`PracticeSessionService` 的 SEQUENCE），且**导出不写 `questionNumber`**（见 8.2 第 3 条）——"题号不能作为唯一顺序键"的结论同样适用于本格式的消费方 |
| 5 | PDF 提取的图片大多是纯黑图 | 解析/提取阶段问题；载入内容包后就是普通 `media` 图片 |

### 8.2 实现层面的坑（读代码得到的事实，跨端实现务必注意）

1. **v2 清单里 `images` / `checksum` 以 `null` 出现，而不是"没有这个字段"。**
   `exportTikuPackageWithMeta` 只是 `setImages(null)` / `setChecksum(null)`；`ContentPackageFile`
   没有 `@JsonInclude(NON_NULL)`，项目也没有配置全局 null 策略（`application.yml` 无 `spring.jackson.*`，
   `com/tiku/config/**` 无 `ObjectMapper` Bean），因此 Jackson 默认会把它们序列化成 `"images": null`。
   与 `doc/content-package-spec.md` 中"v2 容器内 package.json **不含** `images`/`checksum` 字段"的
   表述**不一致**（语义等价：值都是空）。解析端必须容忍 `null` 与"字段缺失"两种情况。
   （**待确认**：是否有意为之或应加 `@JsonInclude(NON_NULL)`。）
2. **同名顶层字段 `sources` 可能为 `null`**：`parseSources` 在库内 `sources` 为空/非法 JSON 时返回
   `null` → 序列化后是 `"sources": null`（示例文件里是 `[]`）。解析端要同时接受 `null` 与数组。
3. **`questionNumber` 只进不出**：导入认、导出不写（`toFileQuestions` 未设置该字段）。
   因此"导出 → 再导入"会丢失原题号，由导入端按库内最大题号重新编号。若你的端需要保序，
   请用自己的字段或依赖数组顺序（数组顺序在导出时按 `question.id` 升序：`exportContentPackage`
   的 `orderByAsc(Question::getId)`），不要依赖 `questionNumber` 往返。
4. **`answerSource` 导出不写、导入不落库**：本地 `question` 表没有该列，导入时忽略、导出时不写；
   它只在 AI 导入预览链路（`ContentPackageQuestion` 作为临时 DTO，前端 `AiImportPreviewView.vue`、
   `PrintPaperView.vue` 读取）中生效。第三方生成内容包时不要依赖它表达"答案是否 AI 补充"。
5. **`score` 精度**：本地是 `DECIMAL(6,1)`，文件里的小数第二位会丢。
6. **`materials[].content` 缺省 `null` 会落库失败**（本地 `content TEXT NOT NULL`），
   文件里请写空串 `""` 而不是 `null`。
7. **图片名非法时是静默丢弃**：`saveBase64` / `copyToBank` 用 `SAFE_NAME` 过滤后 `continue`，
   不报错也不提示。生成端务必遵守 `[A-Za-z0-9._-]` + 最多一层目录。
8. **引用图片但包内缺图不会导致导入失败**（导出端也允许缺图），只是渲染时图片 404。
9. **桌面端 UI 导入按扩展名分流**（见 1.3）：扩展名与真实内容不一致的文件从 UI 导入会失败，
   但从官网/接口导入仍按魔数正确处理。
10. **官网不校验题目内容**：`content`/`type`/`questionKey` 之类的错误只在桌面端导入时暴露。
    第三方生成器若只对着"能发布"做校验，可能生成"能发布但导入即失败"的文件。
11. **`volume`、`answerKeys`、`options` 的形态差异**：`volume` 导入 `null→0`；
    `answerKeys` 导入时被 `trim` 并以 `,` 存储（**key 内不能有逗号**，否则往返会分裂）；
    `options` 走 JSON TypeHandler 原样存取。
12. **本地库长度比校验规则更严格**（见 5.4 的"互操作建议"）：官网允许 `description` 到 2000、
    `title` 到 120/200、`version` 到 40/100，但本地列只有 500/100/20——超出会在**导入端**失败。
    跨端生成文件时请按最保守值。

### 8.3 待确认清单（代码中未找到明确依据）

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | BOM 处理 | 代码中无显式 BOM 剥离逻辑；建议不写 BOM |
| 2 | `createdAt` 的固定格式 | 未配置 Jackson 日期格式，依赖 Spring Boot 默认 ISO-8601；`docs/content-package-spec.md` 写的是 `yyyy-MM-dd'T'HH:mm:ss`（无时区） |
| 3 | 未知字段的处理 | 依赖 Spring Boot 默认（`FAIL_ON_UNKNOWN_PROPERTIES=false`），项目未显式声明 |
| 4 | `SUBJECTIVE` 的默认分值 5 | `ContentPackageQuestion.score` 注释称"主观题默认 5"，但导入路径一律 `1.0`，未找到实现依据 |
| 5 | `materials[].content` 为 `null` | 本地列 `NOT NULL`，文件级不校验；是否需要显式默认空串未定 |
| 6 | 单条容器上限的健壮性 | `unpack` 对声明 size 为 `-1` 的条目不做单条限制；官网侧无单条/总量限制 |
| 7 | 官网 `title` 长度 | 直传路径对文件内 `title` 不限长，登记路径限 120；是否应统一未定 |
| 8 | `questionKey` 的长度与字符集 | 无正则与长度校验，仅本地列 `VARCHAR(64)`；跨端是否需要更严格的约定未定 |

---

## 附：字段来源速查

| 内容包字段 | 声明位置 |
| --- | --- |
| `schemaVersion` / `packageKey` / `title` / `description` / `version` / `authorId` / `authorName` / `source` / `parentKey` / `checksum` / `sources` / `createdAt` / `questions` / `materials` / `images` | `model/ContentPackageFile.java` |
| `questionKey` / `volume` / `type` / `content` / `options` / `answerKeys` / `answerText` / `analysis` / `topic` / `category` / `score` / `answerSource` / `referenceAnswer` / `materialKey` / `questionNumber` | `model/ContentPackageQuestion.java` |
| `materialKey` / `content`（材料） | `model/ContentPackageMaterial.java` |
| `key` / `text`（选项） | `model/OptionItem.java`（`record OptionItem(String key, String text)`） |
| `SINGLE` / `MULTIPLE` / `JUDGE` / `SUBJECTIVE` | `model/enums/QuestionType.java` |
| `package.json` / `media/` | `util/PackageContainer.java`（`PKG_ENTRY` / `MEDIA_PREFIX` / `SCHEMA_V2`） |
| 容器上限 4096 / 100MB / 512MB / 10MB | `util/PackageContainer.java`（`MAX_ENTRIES` / `MAX_ENTRY_BYTES` / `MAX_TOTAL_BYTES` / `MAX_PKG_ENTRY_BYTES`） |
| `KEY_RE`（1–100，`\p{L}\p{N}._-`） | `service/ContentPackageInspector.java`、`web/server/api/packs/upload.post.ts`、`index.post.ts`、`service/LocalExportService.java`（`VERSION_RE`） |
| 图片名 `SAFE_NAME` / 引用 `IMAGE_REF` / 单图 10MB | `service/ImageStorageService.java` |
| 富文本（`[图片:name]` / `$…$` / `<table>`） | `frontend/src/utils/richText.js`、`service/QuestionService.java`（`normalizeLatex`） |
| 官网「合法内容包」判定 | `web/server/utils/package-meta.ts` + `web/server/api/packs/upload.post.ts` |
| 登记/补传字段限制 | `web/server/api/packs/index.post.ts`、`[packageKey]/[version]/file.put.ts` |
| 本地落库列长度 | `src/main/resources/db/migration/V1__init_schema.sql`、`V7__subjective_material.sql` |
