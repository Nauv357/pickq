# 代码约定（Conventions）

这份文档把仓库里**已经在用、但过去只存在于代码注释与习惯里**的约定写下来。目的很具体：

- 新人（以及未来的自己）不用靠翻十个文件来猜「这里该怎么写」；
- 评审时能引用条款，而不是争论个人偏好；
- 每条约定都给出**规则 → 为什么 → 示例/反例**，示例全部来自本仓库的真实代码（附文件与行号，
  行号可能随代码演进漂移，以代码本身为准）。

本文档只描述**现状与共识**。如果你认为某条约定应该改，请开 Issue 或 PR 一起改本文档，
而不是默默按新写法提交（这会让仓库出现两套风格）。

目录：

1. [后端](#1-后端约定)
2. [数据库与迁移](#2-数据库与迁移约定)
3. [前端（桌面端）](#3-前端桌面端约定)
4. [官网 `web/`（内部资产）](#4-官网-web内部资产约定)
5. [跨端契约](#5-跨端契约)
6. [发布约定](#6-发布约定)
7. [i18n 质量红线（提交前自检）](#7-i18n-质量红线提交前自检)
8. [已知债务与待补充](#8-已知债务与待补充)

---

## 1. 后端约定

技术栈：Spring Boot 3.4.4 / Java 21 / MyBatis-Plus 3.5.10 / H2（文件模式 `MODE=MySQL`）/ Flyway。
包结构固定为 `config` / `controller` / `service` / `mapper` / `model` / `dto` / `util`。

### 1.1 所有接口返回统一包装 `ApiResponse`

**规则**：controller 的返回值一律是 `ApiResponse<T>`，成功用 `ApiResponse.success(data)`，
失败用 `ApiResponse.error(code, message)`；前端只在 `code === 200` 时取 `data`。

**理由**：只用一个 JSON 形状，前端一个拦截器就能处理所有接口（成功取值、失败弹提示），
不需要每个调用点判断 HTTP 状态。

**示例**：

```java
// src/main/java/com/tiku/dto/ApiResponse.java:3-10
public record ApiResponse<T>(int code, T data, String message) {
    public static <T> ApiResponse<T> success(T data){ return new ApiResponse<>(200, data, "ok"); }
    public static <T> ApiResponse<T> error(int code, String message){ return new ApiResponse<>(code, null, message); }
}
```

```java
// src/main/java/com/tiku/controller/QuestionBankController.java:40-42
@PostMapping
public ApiResponse<Long> createQuestionBank(@Valid @RequestBody QuestionBankCreateRequest request) {
    return ApiResponse.success(questionBankService.createQuestionBank(request));
}
```

前端对应（`frontend/src/api/http.js:19-27`）：`body.code === 200` 时 `return body.data`，
否则 `ElMessage.error(msg)` 并 reject。

**反例**：直接 `return ResponseEntity.ok(wrapper)` 混用、或某个接口返回裸 `Map` / `List`
（前端就拿不到 `data`，只能特判——历史上所有 `code !== 200` 的失败都会掉进同一个弹窗分支）。

### 1.2 异常 → HTTP 状态码由 `GlobalExceptionHandler` 统一映射

**规则**：业务代码按语义抛异常，不自己拼状态码；映射关系（`GlobalExceptionHandler.java`）：

| 异常 | HTTP | 典型场景 | 代码位置 |
| --- | --- | --- | --- |
| `NoSuchElementException` | **404** | 按 id 找不到资源，如「题库不存在」 | `:29-33` |
| `NoResourceFoundException` | **404** | 路径不存在（含已删除的接口） | `:36-40` |
| `HttpRequestMethodNotSupportedException` | **405** | 路径存在但方法不对 | `:43-47` |
| `IllegalArgumentException` | **400** | 参数/输入不合法（**面向用户可读**的 message） | `:49-53` |
| `MethodArgumentNotValidException` | **400** | `@Valid` 校验失败（拼接字段 message） | `:95-102` |
| `HttpMessageNotReadableException` | **400** | 畸形 JSON / 未知枚举（原因截断 200 字） | `:107-116` |
| `MethodArgumentTypeMismatchException` | **400** | 路径/查询参数类型不符（如 `/jobs/abc`） | `:119-123` |
| 缺 multipart part / 缺查询参数 | **400** | `MissingServletRequestPartException` 等 | `:126-131` |
| `MaxUploadSizeExceededException` | **400** | 超过 200MB 上传限制 | `:134-138` |
| `IllegalStateException` | **500** | 业务状态异常（模型调用失败、AI 输出非法），**保留真实原因**给用户排查 | `:56-61` |
| `IOException`（非异步/未提交响应） | **500** | 磁盘/图片读写失败 | `:66-80` |
| `IOException`（SSE 等异步中断） | **204** | 客户端断开，静默忽略 | `:70-76` |
| 其它 `Exception` | **500** | 兜底，message 统一为「内部服务器错误」，原因只进日志 | `:88-93` |

**理由**：让「谁的责任」体现在状态码上——客户端输入错误是 400，资源不存在是 404，
服务端自己的问题是 500。没有这套映射时，所有输入错误都会变成 500「内部服务器错误」，不可诊断
（`:104` 的注释就是在记录这次修复）。

**示例**：`QuestionBankService.findByIdOrThrow`（`:230-234`）找不到时抛
`new NoSuchElementException("题库不存在")` → 404；参数校验失败抛
`IllegalArgumentException` → 400。

**反例**：在 controller 里 `try/catch` 后 `return ApiResponse.error(500, e.getMessage())`
——把用户输入错误伪装成服务端故障，前端还会以为要重试。

**❗ 两条容易踩的约束**

- **错误文案就是契约**：`IllegalArgumentException` / `IllegalStateException` 的 message 会原样弹给用户，
  并且部分文案与官网侧同口径（见 `CenterPublishInspectTest` 的类注释：「断言里的错误文案与官网
  `web/server/utils/package-meta.ts` + `upload.post.ts` 一一对应，改文案即改契约」）。改文案要同步测试。
- **IO 中断只在异步上下文静默**：`:64-65` 的注释明确写了原因——普通请求的 `IOException`
  必须如实返回 500，否则会被吞成 204「静默成功」，错误完全不可见。

### 1.3 controller / service / mapper 职责边界

**规则**

- **controller**：只做 HTTP 层的事——路由、参数绑定（`@RequestParam` / `@PathVariable` / `@Valid @RequestBody`）、
  调用 service、包 `ApiResponse`。不写业务分支，不直接碰 mapper。
- **service**：业务逻辑与事务边界，编排多个 mapper；`@Transactional` 加在这里。
- **mapper**：MyBatis-Plus 的 `BaseMapper<T>` 空接口；只有确实需要 SQL 特性（如行锁、联表）时才加注解 SQL。
  没有 XML mapper 文件（`src/main/resources` 下无 `mapper-locations`）。

**理由**：分层可测、可读；业务规则集中在一处，改规则不用翻 controller。把查询写进 service 而不是
mapper XML，是为了避免「一半 Java 一半 XML」的上下文切换。

**示例**

```java
// src/main/java/com/tiku/mapper/QuestionMapper.java:6 —— 空接口是常态
public interface QuestionMapper extends BaseMapper<Question> { }
```

```java
// src/main/java/com/tiku/mapper/PracticeSessionMapper.java:12 —— 需要行锁时才写注解 SQL
@Select("SELECT * FROM practice_session WHERE id = #{id} FOR UPDATE")
```

```java
// src/main/java/com/tiku/service/QuestionBankService.java:213-228 —— 多表写入集中在一个事务方法里
//删除题库：物理删除题库 + 级联逻辑删除题目 + 物理删除刷题记录/复习状态/会话/材料（一个事务，原子完成）
@Transactional
public DeleteBankResult deleteQuestionBank(Long id){
    findByIdOrThrow(id);
    sessionQuestionMapper.deleteByBankId(id);
    ...
}
```

**反例**：在 controller 里 `questionMapper.selectById(id)` 直接查；或把「删除题库要级联删哪些表」
的知识散落在三个 controller 里。

### 1.4 DTO 一律用 `record`

**规则**：`dto` 包下全部是 `record`（共 64 个文件、73 个 record，无一个 class），
请求/响应分开命名（`XxxRequest` / `XxxResponse`），
需要从实体转换时在 record 内提供静态工厂 `fromEntity(...)`；校验注解直接标在 record 组件上；
只服务于某个响应的聚合结构用**嵌套 record**，不单独建文件。

**理由**：DTO 是**数据载体**，不可变更安全，也省掉 getter/setter 噪音；record 的组件顺序即 JSON 字段顺序，
读一眼就知道契约。命名成对让「入参」与「出参」不会混用。

**示例**

```java
// src/main/java/com/tiku/dto/QuestionBankCreateRequest.java:5-10
public record QuestionBankCreateRequest(
        @NotBlank(message = "题库名不能为空")
        String name,
        String description
) { }
```

```java
// src/main/java/com/tiku/dto/QuestionBankResponse.java:8-27
public record QuestionBankResponse(Long id, String name, ... ) {
    public static QuestionBankResponse fromEntity(QuestionBank questionBank) { ... }
}
```

```java
// src/main/java/com/tiku/dto/StatsSummaryResponse.java:35-38 —— 只服务本响应的结构用嵌套 record
public record StatsSummaryResponse( ... ) {
    public record DailyStat(String date, int count, int decided, int correct) { }
    public record DueDay(String date, int count) { }
}
```

**反例**：用 `Map<String, Object>` 当响应（前端拿不到字段名提示，也不知道哪些字段一定有）；
或把实体 `QuestionBank` 直接返回给前端（会把 `checksum`、`package_key` 等内部字段一起漏出去）。

`PageResult<T>`（`dto/PageResult.java`）是分页响应的统一形状，列表接口都用它包一层。

### 1.5 Lombok：现状是「实体用、DTO 不用」，请勿擅自扩大

**规则（现状）**：

- `model` 下的持久化实体用 `@Data` + `@NoArgsConstructor`（如 `QuestionBank.java:10-12`），
  因为它们需要 MyBatis-Plus 的可变 setter；
- 打日志的类用 `@Slf4j`（如 `GlobalExceptionHandler.java:20`、多个 service）；
- **`dto` 下不使用 Lombok**（全部是 `record`，见 1.4）。

**理由**：这是仓库事实。`record` 出现后 DTO 不再需要 Lombok，但实体仍需可变 bean。

> ⚠️ **维护者待确认**：贡献指南的预期口径里曾写「不使用 Lombok」，与仓库现状（`pom.xml:73-77`
> 引入 `lombok` 为 `provided`，31 处 `import lombok.*`）不一致。
> 本文档**以现状为准**。若决定弃用 Lombok，请单独开 Issue 做迁移（涉及所有 `model` 实体），
> 并同步修改本节与 `CONTRIBUTING.md`。

**反例**：在新写的 `dto` 里加 `@Data class XxxResponse`（与本包其余 `record` 风格割裂，
且会引入可变性）；或在 `service` 里手写 `private static final Logger log = LoggerFactory.getLogger(...)`
（仓库统一用 `@Slf4j`）。

### 1.6 注释：中文、说明「为什么」

**规则**：代码注释一律中文。类/方法级用 Javadoc `/** ... */`，把**背景、约束、失败模式、历史原因**
写清楚（不是复述代码）；行内用 `//`（`//` 后不空格，与现有代码一致），一句话点明意图。

**理由**：仓库里信息密度最高的注释都是「为什么不能这样改」，这是最贵、最容易丢失的知识。
例如 `SpaForwardConfig` 用「约束（勿破坏）」段说明为什么 `/api/**` 不能回退，
`GlobalExceptionHandler` 用一段注释解释为什么 IO 异常不能一律静默——都是后来者会踩的坑。

**示例**

```java
// src/main/java/com/tiku/config/SpaForwardConfig.java:18-21
// 约束（勿破坏）：
// - /api/** 一律不回退：controller 未命中时走默认 404 → GlobalExceptionHandler 的
//   NoResourceFoundException → JSON 错误体（前端 axios 依赖此语义）；
// - 带文件扩展名的真实文件（/assets/*.js 等）照常解析，不回退。
```

```java
// src/main/java/com/tiku/controller/CenterProxyController.java:28-29
// 注：用 HttpURLConnection 而非 JDK HttpClient（RestClient 默认底层）——
// 实测前者与 Nuxt dev server 兼容（后者连接被服务端立即断开）。
```

**反例**：`// 设置名称` 这种复述型注释；用英文写「业务原因」（与仓库其余部分不一致）。

### 1.7 时间字段在 service 层显式赋值

**规则**：没有统一的 `MetaObjectHandler` 自动填充。`createdAt` / `updatedAt` / `finishedAt` /
`answeredAt` / `dueAt` 都由 service 显式 `LocalDateTime.now()` 赋值（或由数据库 `DEFAULT CURRENT_TIMESTAMP` 兜底）。

**理由**：显式赋值让「这一行的时间是谁写的、在哪个事务里写的」一目了然；自动填充对
「批量导入历史记录时要保留原始时间」这类场景反而碍事（例如导入刷题记录时会传入历史时间）。

**示例**：`QuestionService.java:209` → `update.setUpdatedAt(java.time.LocalDateTime.now())`；
`StudyRecordService.java:539` → `record.setAnsweredAt(item.getAnsweredAt() == null ? LocalDateTime.now() : item.getAnsweredAt())`。

**反例**：新加一个 `MetaObjectHandler` 全局填充（会与「导入保留原时间」的逻辑打架），
或在前端传时间戳由后端直接落库。

### 1.8 长耗时任务与流式接口

**规则**：AI 导入这类长任务走**异步任务表 + 前端轮询/SSE**，不在请求线程里同步跑完；
`aiImportExecutor` 单线程串行（`config/AsyncConfig.java:18-27`：避免并发触发模型限流），
单任务内部的分块调用再用 `aiChunkExecutor`（固定 3 路、daemon 线程，`AsyncConfig.java:29-37`）。
涉及 SSE 的接口，客户端断开必须按 1.2 的 IO 规则处理。

**理由**：一次整卷 AI 整理可能几分钟，同步请求必然超时；串行执行让「模型限流」变成可预期的排队而不是批量失败。

**反例**：在 controller 里 `await` 一个模型调用再返回（前端 axios 默认 15s 超时，
`frontend/src/api/http.js:12`）。

---

## 2. 数据库与迁移约定

### 2.1 Flyway 迁移：命名 `V<n>__snake_case.sql`，**只增不改**

**规则**

- 文件放 `src/main/resources/db/migration/`，命名 `V<序号>__<下划线描述>.sql`（当前到 `V14`）；
- 序号连续递增，**永不修改、永不删除已发布的迁移**；要改结构就新增一个迁移；
- SQL 里带注释说明「这一版为什么加这些列/表」；
- 迁移文件里的 SQL 必须**在 H2（`MODE=MySQL`）上真实可执行**：`ADD COLUMN` 与
  `ALTER COLUMN ... SET NULL` 都可用（后者见 `V8`），所以后端至今没有出现过「重建表」的迁移；
  若某条语句 H2 不支持，就换一种等价写法和新的迁移，不要靠改旧文件绕过；

**理由**：用户的 `~/.tiku/tiku.mv.db` 里已经记录了已应用的迁移，改旧文件在**已升级的用户机器上不会重放**，
只会造成「新装用户」与「老用户」结构不一致——这是最难排查的一类问题。

**示例**

```sql
-- src/main/resources/db/migration/V8__study_record_is_correct_nullable.sql:2-5
-- V8: study_record.is_correct 允许 NULL（主观题不自动判题，正确性由 self_grade 决定）
ALTER TABLE study_record ALTER COLUMN is_correct SET NULL;
```

迁移的编写方式本身也是记录：`V1__init_schema.sql:29` 用注释解释了「题库为物理删除，
唯一约束无需包含 deleted」；`V1__init_schema.sql:26-27` 解释了 `review_enabled` 为什么默认关闭。

**反例**：改了 `V3__ai_import_multi_file_and_supplement.sql` 去加一列——老用户库里不会有这一列，
只有在干净环境才「看起来正常」。

> 官网侧（`web/`，SQLite）是**自管的迁移数组**（`web/server/db/migrate.ts`，仿 Flyway：
> 按序执行未应用的 SQL，记录在 `schema_migrations`），命名是 `00N_description`。
> 同一条「只增不改」的规则；SQLite 不支持改列约束，所以那里的 `006_checksum_nullable`
> 用「建新表 → 拷数据 → 换名」重写（`migrate.ts:133-179`），**并在注释里写明了原因**。

### 2.2 列名 snake_case、Java 字段 camelCase，多词列显式 `@TableField`

**规则**：数据库列一律 snake_case；Java 字段 camelCase；**只要列名与字段名不能靠驼峰自动对应
（即多词列），就显式写 `@TableField("列名")`**；主键 `@TableId(type = IdType.AUTO)`；表名 `@TableName(...)`。

**理由**：显式映射让「字段 ↔ 列」无需依赖 MyBatis-Plus 的全局驼峰策略就能读懂；
`createdAt` ↔ `created_at` 这种靠约定能对，但显式写出来后，`grep created_at` 能直接命中 Java 代码。

**示例**

```java
// src/main/java/com/tiku/model/QuestionBank.java:12-13, 24-25, 55-56
@TableName(value = "question_bank", autoResultMap = true)
public class QuestionBank {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("package_key")
    private String packageKey;
    @TableField("created_at")
    private LocalDateTime createdAt;
```

**反例**：`@TableField("Name")` 之类的例外；或列名用 camelCase（H2 会按 `MODE=MySQL` 的大小写规则处理，
迁移与查询容易对不上）。

### 2.3 时间字段

**规则**：列类型 `TIMESTAMP`；创建时间 `NOT NULL DEFAULT CURRENT_TIMESTAMP`，更新时间
`NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`；Java 侧用 `java.time.LocalDateTime`
（**不用** `Date` / 时间戳数字）。跨端传输的时间是 ISO-8601 字符串（`sample-content-package.json`
里的 `"createdAt": "2026-08-25T12:00:00"`）；官网侧统一用 `sqlUtc()` 生成 UTC 的 SQLite datetime
字符串（`web/server/db/repos.ts:271-275`，与表的 `datetime('now')` 默认值同格式）。

**理由**：`LocalDateTime` + `TIMESTAMP` 在 H2/MySQL 语义一致，避免时区二次转换；
数据库默认值保证「任何写入路径都不会漏时间」。

**示例**：`V1__init_schema.sql:24-25`（`created_at` / `updated_at` 的默认值）；
`StudyRecordService.java:243` 用 `LocalDateTime.now().plusDays(...)` 算复习到期时间（`due_at`）。

**反例**：用 `long` 存毫秒（前端与内容包格式都要二次转换，且时区语义丢失）；
把「到期时间」在前端计算后传给后端落库。

### 2.4 删除策略：软删除与物理删除**并存，按表的语义选**

**规则**

- **题目**：软删除 —— `question.deleted`（`TINYINT NOT NULL DEFAULT 0`），Java 侧标 `@TableLogic`
  （`model/Question.java:60-62`），`delete()` 会自动变成 `UPDATE ... SET deleted=1`；
  唯一约束要带上 `deleted`（`uk_question_bank_external_id_deleted`），否则「删掉再导入同一题」会撞唯一键。
- **题库**：**物理删除**（`V1__init_schema.sql:29` 注释写明理由：删除即级联删题删记录，
  唯一约束无需包含 deleted）。题库删除时题目走逻辑删除、记录/会话/复习状态/材料走物理删除，
  全在一个事务里（`QuestionBankService.java:213-228`）。
- **刷题记录**：不软删除（它是历史日志，随题库级联物理删除，`V1__init_schema.sql:62-63`）。
- **状态型字段**（如官网的 `packs.status = ACTIVE / REMOVED`）用于「下架但保留记录」，不要和 `deleted` 混用。

**理由**：软删除的意义是「保留可追溯的历史」。题目需要（历史记录要能回看），
刷题记录本身就是历史所以不需要，题库删除是用户明确的破坏性操作所以要告知影响面
（`DeleteBankResult` 返回删除题数与受影响记录数，前端据此提示）。

**示例**：`QuestionBankService.java:222-224` 的注释直接写明
「MyBatis-Plus `@TableLogic` 自动转为 `UPDATE question SET deleted=1 WHERE bank_id=? AND deleted=0`」。

**反例**：给 `question_bank` 也加 `deleted`（与「物理删除」的产品语义冲突，且所有查询都要加过滤条件）；
或在软删除表上用不含 `deleted` 的唯一约束。

### 2.5 状态 / 枚举字段的取值

**规则**：状态值用**大写英文常量字符串**，取值集合写在列注释或 Java 字段注释里；
只有进入业务判断的「题目类型」才做成 Java 枚举（`model/enums/QuestionType.java`）。

| 字段 | 取值 | 定义位置 |
| --- | --- | --- |
| `question.question_type` | `SINGLE` / `MULTIPLE` / `JUDGE` / `SUBJECTIVE` | `model/enums/QuestionType.java:6-9`（带中文 label） |
| `practice_session.mode` | `ALL` / `SEQUENCE` / `TOPIC` / `REVIEW` / `WRONG` / `FAVORITE` | `V1__init_schema.sql:85-86` |
| `ai_import_job.status` | `PENDING` / `PROCESSING` / `SUCCESS` / `FAILED` | `model/AiImportJob.java:62` |
| `ai_import_job.stage` | `PARSING` / `AI_GENERATING` / `VALIDATING` / `DONE` | `model/AiImportJob.java:65` |
| `ai_import_job.engine` | `AUTO` / `LOCAL` / `MINERU` | `model/AiImportJob.java:43-45` |
| `review_state.suspended` | `0` / `1`（`1` = 用户标记不再复习） | `V1__init_schema.sql:106` |
| 官网 `packs.storage_kind` | `EXTERNAL` / `HOSTED` | `web/server/db/migrate.ts:36` |
| 官网 `packs.status` / `users.banned` | `ACTIVE` / `REMOVED`；`0` / `1` | `web/server/db/migrate.ts:37`、`007_user_banned` |
| 官网 `reports.status` | `OPEN` / `RESOLVED` / `DISMISSED` | `web/server/db/migrate.ts:97` |

**理由**：字符串状态在数据库里可读、可手工排查；枚举只在需要编译期穷尽判断的地方用。
取值集合必须写在注释里，否则「`SUCCESS` 之外还有没有 `CANCELED`」只能靠猜。

**反例**：新增状态值时不写注释、也不更新本表；用中文当状态值落库（导出、跨端、i18n 都会出问题）。

---

## 3. 前端（桌面端）约定

技术栈：Vue 3（`<script setup>`）+ Vite 6 + vue-router 4 + Element Plus 2 + ECharts 6 + vue-i18n 9。

### 3.1 组件级 i18n 字典：`zh-CN` 与 `en-US` **必须成对**

**规则**：需要用户可见文案的组件/页面，在 `<script setup>` 里用
`useI18n({ messages: { 'zh-CN': {...}, 'en-US': {...} } })` 提供**局部字典**，模板与脚本用 `t('key')`；
两个语言的 key 集合必须一一对应；**禁止裸中文文案**（模板里直接写中文、JS 里直接拼中文提示）。

**理由**：i18n 是**增量迁移**的（`frontend/src/i18n/index.js:5-7` 写明了策略），所以字典放在组件内，
不建巨型全局字典——组件删除时字典一起消失，不会留孤儿 key。
`fallbackLocale: 'zh-CN'`（`index.js:29`）保证未翻译的 key 回落中文而不是显示裸 key，
但**这是兜底，不是免于翻译的理由**。

**示例**

```js
// frontend/src/views/BankListView.vue:334-348（节选）
const { t } = useI18n({
  messages: {
    'zh-CN': {
      pageTitle: '题库',
      toolbar: { searchPh: '搜索题库名称 / 描述', match: '匹配 {n} 个题库' },
      createdOn: '创建于 {d}'
    },
    'en-US': {
      pageTitle: 'Question Banks',
      toolbar: { searchPh: 'Search bank name / description', match: '{n} banks matched' },
      createdOn: 'Created {d}'
    }
  }
})
```

**命名插值**：统一用花括号命名参数（`{n}`、`{d}`、`{name}`、`{tip}`、`{detail}`、`{id}`），
**不要**用位置参数拼接字符串：

```js
// frontend/src/views/BankListView.vue:407
msgMergeDone: '合并完成：{n} 道题已复制进「{name}」（源题库保留，题号已从 1 重新编排）'
```

**反例（就来自本仓库，请勿照抄）**：

```vue
<!-- src/views/AiImportPreviewView.vue:83 —— 模板里裸中文（切到 en-US 时仍是中文） -->
<span>题干已包含材料全文（后端已补齐），编辑时请勿删除材料部分</span>
```

```vue
<!-- src/views/AiImportPreviewView.vue:96,102,402,407,439 —— 同上 -->
<span>素材区</span>
图片{{ jobImages.length ? `（${jobImages.length}）` : '' }}
<label class="field-label">题干 <span class="required">*</span></label>
placeholder="题目内容"
<span>添加选项</span>
```

```js
// 反例：脚本里拼字符串（无法翻译、语序无法调整）
ElMessage.success('已导入 ' + n + ' 道题')
```

**已知遗留**（写在这里是为了不让下一个人以为「已经全量双语」）：

- `frontend/src/views/AiImportPreviewView.vue` 的**模板区仍有大量裸中文**（该文件 1–578 行中约 95 行含中文，
  含注释；其中模板文案如「素材区」「题干」「选项」「添加选项」「参考答案」「正确答案」「题目内容」
  都未走字典，而同一文件已有 `t('unnumbered')` 这类字典调用）——迁移不完整；
- `frontend/src/router/index.js:87` 用固定的中文设置 `document.title`
  （`` `${to.meta.title} · 拾题` ``），`frontend/src/views/DiscoverView.vue:805,836` 同样硬编码中文标题；
- `frontend/src/components/QuestionFormPanel.vue:558` 的 `typeLabels` 是中文常量表。

这些是**尚未迁移**的残留，新增/修改代码请不要照抄；顺手修掉它们是很好的入门贡献。

### 3.2 语言基建（切换、持久化、Element Plus 联动）

**规则**：语言相关逻辑放在 `frontend/src/i18n/`，组件不要自己读写语言状态：

- 支持语言常量 `SUPPORTED_LANGS = ['zh-CN', 'en-US']`，偏好存 `localStorage['tiku:lang']`，
  取值优先级「显式选择 > 跟随系统 `navigator.language`」（`i18n/index.js:13-23`）；
- 切换语言用 `i18n/lang.js` 的 `setLang()`（`'system'` 表示跟随系统），持久化并即时生效；
- Element Plus 组件自身的文案（弹窗按钮、分页等）通过 `<el-config-provider :locale="elLocale">`
  联动（`App.vue:3`、`i18n/lang.js:15`），**不要**单独给某个组件传 locale。

**理由**：语言状态只有一处真相，否则会出现「应用文案切了、Element Plus 弹窗还是中文」这类不一致。

**反例**：在组件里 `i18n.global.locale.value = 'en-US'` 直接改（绕过持久化与 Element Plus 联动，
刷新就丢）；给 `<el-pagination>` 单独传 `:locale`。

### 3.3 目录分工

| 目录 | 放什么 | 约束 |
| --- | --- | --- |
| `frontend/src/views/` | **路由页面**（与 `router/index.js` 一一对应）：`BankListView`、`PracticeView`、`StatsView`… | 一个页面一个文件；页面可以很胖，但只处理「这一页」的事 |
| `frontend/src/components/` | **可复用组件**：`AiImportDialog`、`QuestionFormPanel`、`QuestionNavDock`、`StatsHeatmap`、`QuestionAiAnalysis`、`FieldImages`、`TikuIcon` | 被两个及以上页面/组件用到，或本身是复杂独立交互单元（对话框、面板） |
| `frontend/src/layouts/` | 外壳布局（`AppLayout.vue`：侧栏、导航、页脚、AI 任务侧栏） | 只放**全局外壳**与跨页共享的状态；具体业务规则放对应 view / component |
| `frontend/src/api/` | 接口封装，**一个后端资源一个文件**（`banks.js` / `questions.js` / `sessions.js` / `stats.js` / `aiImport.js` / `aiConfig.js` / `center.js` / `backup.js` / `materials.js` / `studyRecords.js`） | 薄函数，不做数据处理 |
| `frontend/src/utils/` | 纯函数与平台适配（`format.js`、`files.js`、`richText.js`、`theme.js`、`updater.js`、`external.js`、`netAddress.js`、`aiModelHelp.js`、`center.js`） | 不 import 组件、不直接弹 UI 提示 |
| `frontend/src/styles/` | 设计令牌与全局样式（`main.css`） | 组件私有样式写在组件的 `<style>` 里 |

**理由**：判断「该新建文件还是改现有文件」的标准是**复用范围**而不是文件长度；
把接口封装集中到 `api/` 后，后端改路径只需要改一个文件。

**反例**：把只在 `PracticeView` 用的一次性 UI 抽进 `components/`（多一层跳转、参数耦合）；
或在 `views/` 里直接 `axios.get('/api/banks')` 绕过 `api/`（baseURL、错误提示、`skipErrorMessage` 全丢）。

### 3.4 `api/` 薄封装与 `skipErrorMessage`

**规则**

- 所有请求经统一的 axios 实例（`api/http.js`，`baseURL: '/api'`，超时 15s），写在 `api/*.js` 里，
  函数体保持一行式薄封装：

```js
// frontend/src/api/banks.js:4-6
export const createBank = (data) => http.post('/banks', data)
export const getBanks = (params) => http.get('/banks', { params })
```

- **调用方要自己处理错误（或错误属于预期）时**，在 config 里加 `skipErrorMessage: true`，
  全局拦截器就不会弹 `ElMessage`，由调用方决定怎么提示：

```js
// frontend/src/api/http.js:29-33
// 调用方自行处理错误时（如发现页离线空态），跳过全局弹窗
if (error.config?.skipErrorMessage) {
  return Promise.reject(error)
}
```

**理由**：默认「错误自动弹提示」能让 90% 的调用点不用写错误处理；但发现广场离线、
自动静默的预取（如 `/center/auth/me`、`/exports`）这类场景如果也弹窗，会变成打开页面就一串报错。
`skipErrorMessage` 是显式的「我接了」，比全局开关或按接口白名单更可控。

**示例**：`frontend/src/api/aiConfig.js:5,17`（「预设拉取失败一律静默……由调用方回退内置/缓存并决定是否提示」）；
`views/DiscoverView.vue:960`（`/center/auth/me` 探测登录态）、`views/MyWorksView.vue:1680`
（注释：「由弹窗自行展示，避免全局弹窗重复提示」）。

**反例**：加了 `skipErrorMessage: true` 却不处理错误（用户永远看不到失败原因）；
或对用户主动点击触发的写操作加它（失败静默，用户以为成功了）。

### 3.5 图标与 Element Plus 使用范围

**规则**

- **图标一律用 `TikuIcon`**（`components/TikuIcon.vue`）：内联 24×24 stroke SVG，
  `name` 从内置 `paths` 表取，颜色随 `currentColor`，`filled` 表示实心（收藏选中态）；
  **不引入图标库**（文件头注释：「避免引入图标库」）。
- **Element Plus 用于通用控件**：表单、对话框、表格、分页、下拉、开关等（`el-*`，全量注册于
  `main.js:23`），**外观统一由 `styles/main.css` 的设计令牌覆盖**，不在业务组件里堆 `!important` 改主题色。
- 业务特有可视化（答题卡、热力图、看板图）自己画，用 ECharts 或原生元素。
- **语义色必须双编码**：对/错、警告等状态不能只靠颜色区分，要同时有图标或文字
  （`styles/main.css:36`：「判题/警示；对错需图标+文字双编码」）。

**理由**：图标库会带来一整套尺寸/线宽/填充风格，与产品克制的视觉不一致，也增加包体；
设计令牌集中管理才能保证深浅两套主题都生效。

**示例**

```js
// frontend/src/components/TikuIcon.vue:2-6
// 轻量内联 SVG 图标（24x24 stroke 风格，随 currentColor 变色），避免引入图标库
const paths = {
  'book': [...],
  'package': [...],
```

**反例**：`npm i @element-plus/icons-vue` 后混着用（同一屏出现两种线宽/两种填充规则）；
在组件 `<style>` 里写死 `#c3272b` 而不是 `var(--cta)`（暗色主题下不跟随）。

---

## 4. 官网 `web/`（内部资产）约定

> `web/`（Nuxt 3）与 `deploy/`、`scripts/deploy/` **不在本仓库**（`.gitignore`），
> 也不接受对外 PR。本节写在这里，是因为桌面端与它交互，改动跨端契约时必须知道它的规矩。
> 本节内容对贡献者**只读**，请勿据此刻意修改 `web/`。

### 4.1 `useSiteT` 字典：`zh` / `en` 成对，缺 key 逐级回落

**规则**：页面/布局用 `const { t } = useSiteT({ zh: {...}, en: {...} })`，
`t('key')` 或 `t('key', { n: 3 })`；插值语法 `{n}`（`useSiteI18n.ts:84-86`）；
找不到的 key 依次回落 `zh` → key 本身。

**理由**：官网不做 vue-i18n（页面是 SSR 直出，字典就在组件里最简单），
回落链保证「英文漏翻」时页面不会出现空白或裸 key。

**示例**：`web/pages/me/favorites.vue:80-111`（`qCount: '共 {n} 题'` / `'{n} questions'`）。

### 4.2 SSR / cookie 语言：每请求重置，组件里要调用 `initSiteLang()`

**规则**：语言状态 `siteLang` 是模块级 ref；`initSiteLang()` **必须在页面/布局里调用一次**；
服务端按 cookie 重置，客户端 cookie 优先、其次迁移 localStorage（`useSiteI18n.ts:35-62`）。

**理由**：Nitro 进程长驻、模块级状态跨请求共享——不按请求重置，上一个请求的语言会污染后续所有请求
（`useSiteI18n.ts:37-38` 的注释就是在记录这个坑）。写 cookie 是为了 SSR 首屏即目标语言，
避免 hydration 闪烁/mismatch。

**反例**：在 `onMounted` 之后才决定语言（首屏会是默认 `zh`，随后跳变）；
把 `siteLang` 换成请求外的单例而不复位。

### 4.3 server route 鉴权三级

| 级别 | 写法 | 典型路由 |
| --- | --- | --- |
| **匿名**（公开读） | 不调用鉴权函数 | `web/server/api/packs/index.get.ts`（作品列表） |
| **需登录** | `const user = requireUser(event)`（未登录抛 401） | `web/server/api/me/favorites.get.ts:8`、评论/收藏/发布等 |
| **需管理员** | `requireUser(event)` 后再 `if (!isAdminUser(user)) apiError(403, '需要管理员权限')` | `web/server/api/admin/users.get.ts:10-12`、报告处置、封禁等 |

- 登录态解析在 `web/server/utils/auth.ts`：`Authorization: Bearer`（桌面端经本地代理转发）
  或 `httpOnly` cookie，二者共用同一张会话表；**被封禁的账号一律按未登录处理**（`auth.ts:55-63`），
  这样封禁即时生效、不用清理会话表。
- 管理员判定是**环境变量白名单** `ADMIN_USERNAMES`，不落库、不设角色表（`utils/admin.ts:1-4`）。
- 对外暴露「是不是管理员」只在 `auth/me.get.ts:10`（前端据此显示入口），权限判断仍在服务端。

**理由**：三级覆盖了官网所有路由；把管理员判定做成环境变量，是因为这是个人规模站点，
加角色表属于过度设计（注释里写明了「如未来需要细分角色再迁移」）。

**反例**：新路由忘了 `requireUser` 就成了匿名写接口；只在前端隐藏入口而后端不校验。

### 4.4 响应与错误：`okResp` / `apiError`，snake→camel 收口在一个函数

**规则**：成功 `return okResp(data)`；失败 `apiError(status, message)`
（抛出 h3 错误，`data` 内嵌 `{ code, message }`）；数据库行 → 对外 JSON 的
snake_case → camelCase 转换只在 `toPackPublic()` 这类映射函数里做，别在路由里手写。

**理由**：与桌面端后端的 `ApiResponse` 保持同一形状（`center-spec §5`），前端只有一套解析逻辑；
字段映射集中一处，改字段名时不会漏。

**示例**：`web/server/utils/api.ts:5-12`（`okResp` / `apiError`）、`:26-49`（`toPackPublic` 里
`p.package_key → packageKey`）。另有 `maskEmail()`（`:18-23`）——管理端只显示掩码邮箱，
**任何接口都不得返回邮箱原文**。

**反例**：在路由里 `return { code: 200, data: { packageKey: row.package_key } }` 手搓响应；
把邮箱原文返给前端。

---

## 5. 跨端契约

### 5.1 内容包（`.tiku` / v1 JSON）格式改动必须三处同步

**规则**：改动内容包格式时，必须**同时**更新：

1. **格式文档**：[`docs/package-format.md`](package-format.md)（本仓库的公开规范：两种文件形态、
   顶层字段、题目/材料对象、图片命名、校验规则、版本演进与完整示例）；
   内部还有未入 git 的 `doc/content-package-spec.md`、`doc/format-evolution.md`；
2. **两端解析/序列化代码**：
   - 桌面端：`src/main/java/com/tiku/service/ContentPackageInspector.java`（只读元数据）、
     `src/main/java/com/tiku/util/PackageContainer.java`（`.tiku` zip 容器）、
     `ContentPackageService`（导入/导出）；
   - 官网/广场：`web/server/utils/package-meta.ts`（服务端同样只取元数据）；
3. **仓库根的 `sample-content-package.json`**（v1 示例，已入 git，是贡献者能看到的公开样例）。

**理由**：格式是**跨端唯一契约**——桌面端导出的包要在广场被登记，广场登记的包要被桌面端导入。
两端各写一份解析器，字段/校验/错误文案必须同口径：`ContentPackageInspector` 的类注释明确写了
「与官网 package-meta.ts / upload.post.ts 同口径」，`schemaVersion` 必须是 1 或 2、
`questions` 必须是数组等判定都一一对应。

**示例**：`ContentPackageInspector.java:43-47` 的「内存策略」——**只读元数据，不物化题目内容与图片**：
v1 JSON 用 Jackson 流式解析只取顶层标量、`questions/materials` 只数元素个数；
`.tiku` 只解出 manifest（≤10MB），图片条目完全不解压。任何格式改动都要保持这个性质。

**反例**：只在桌面端加一个字段就发版（广场侧 `parseContentPackageMeta` 不认识，
上传被拒或元数据缺失）；改了校验错误文案却不改测试（文案即契约，见 1.2）。

### 5.2 广场 API 变更保持向后兼容

**规则**：`/api/packs/**`、`/api/auth/**`、`/api/me/**`、`/api/authors/**` 的变更：

- **只增不改**：可以加字段（可选、有默认值），不要改已有字段的名称、类型与语义；
- 桌面端与广场的关系是**只读代理 + 服务端到服务端转发**：桌面端不解析广场业务数据，
  只做转发与「拉取即导入」（`CenterProxyController` 的类注释）；
- 认证头统一 `Authorization: Bearer <token>`，桌面端 token 由登录/注册接口下发并本地保存
  （`web/server/utils/auth.ts:51-58`）；
- 错误体形状统一 `{ code, message }`（见 4.4），前端依赖它取提示文案。

**理由**：桌面端**用户手里的版本是旧的**——他们可能几个月不升级，广场先改了字段就意味着一片 500。
所以服务端必须比客户端「宽」：老客户端请求新服务端要能工作。

**反例**：把 `favoritesCount` 改名成 `favoriteCount`（旧桌面端读到 undefined，界面显示 0/NaN）；
把某个字段从可选改成必填。

---

## 6. 发布约定

**规则**：发布桌面版时**逐条**执行 [`docs/release-notes-guide.md`](release-notes-guide.md) 的检查清单
（升版本号 → 构建 → 打包 → 上传更新频道与全量下载 → 更新官网下载链接 → 线上验证 → GitHub Release → 提交推送），
其中与「写代码」强相关的两条：

1. **版本号同步**：`tauri/src-tauri/tauri.conf.json` 与 `tauri/src-tauri/Cargo.toml` **必须一致**
   （当前 `0.1.17`）；后端 `pom.xml` 的 `0.0.1-SNAPSHOT` 与桌面端版本号不同步，属预期。
2. **更新公告只写功能**：更新弹窗展示的 `notes` 与 `CHANGELOG.md`
   **只写用户可感知的功能变化**，不写安全细节（「本地后端改为仅监听 127.0.0.1」「加强登录限流」
   这类描述等于公开攻击面）、不写运维/基础设施、不写内部实现。
   完整规范与措辞示例见 `docs/release-notes-guide.md`。

**理由**：公告是给用户看的，不是给攻击者看的技术复盘；同时它是唯一能触达已安装用户的变化说明。

**反例**：公告写「升级依赖修复 CVE-xxxx」（＝告知攻击面）；CHANGELOG 里照抄 git log 的
`security P0: bind desktop backend to 127.0.0.1; ...`。

---

## 7. i18n 质量红线（提交前自检）

新增或修改任何用户可见文案时，**下面每一条都必须成立**：

1. **两种语言都在字典里**：`zh-CN` 与 `en-US` 的 key 集合一一对应，**不允许**只写中文靠
   `fallbackLocale` 回落（回落是兜底，不是免责）；
2. **没有裸中文文案**：模板文本、`ElMessage` / `ElMessageBox`、`document.title`、
   图表标题与图例、空态与占位符、`aria-label`、打印页文案、导出文件名提示——都走字典；
3. **插值统一花括号命名风格**：`{n}` / `{d}` / `{name}` / `{tip}` / `{detail}`，
   不做字符串拼接；
4. **英文不是逐字直译**：与既有英文文案的术语保持一致（仓库现状，出处见各组件 `en-US` 字典）：
   `题库 → Question Banks / Banks`、`题目 → questions`、`刷题 · 练习 → practice`、
   `错题 → Mistakes`、`复习计划 → Review plan`、`今日待复习 → due today`、
   `收藏 → Favorite / Favorites`、`发现题库 → Discover`、`导入 → Import`、
   `单选/多选/判断/主观 → Single / Multiple / True-False / Subjective`
   （`AiImportPreviewView.vue:635`），并注意英文长度（按钮不换行、不溢出）；
5. **语言可切换处要真的切**：新增的独立文案不要写死在组件里，否则「设置 → 语言」切了它不变；
6. **Element Plus 文案不用管**：它随 `<el-config-provider>` 联动，不要去翻译组件内部文案。

**提交前自检（人工）**

```bash
# 1) 模板里是否还有中文（排除注释后人工判断；有输出就要逐条确认是字典值还是裸文案）
rg -n '[\p{Han}]' frontend/src/views frontend/src/components frontend/src/layouts

# 2) 每个新增 key 是否两种语言都有：打开对应组件，确认 'zh-CN' 与 'en-US' 两段都加了同一个 key
```

> 说明：仓库目前**没有**自动化的 i18n 校验脚本（这是一个已知缺口，见第 8 节），
> 所以第 2 步只能人工逐 key 核对；如果你愿意写一个小脚本（扫描各组件字典、比较两个语言段的 key 集合），
> 这会是很受欢迎的贡献。

---

## 8. 脚本（PowerShell）约定

Windows 上默认的 `powershell.exe` 是 **Windows PowerShell 5.1**，它在读取**没有 BOM 的 `.ps1` 时按 ANSI 解码**。
只要脚本里有中文，就会出现两类真实故障（本仓库已各踩过一次）：

1. **解析期直接报错**（`字符串缺少终止符` / `意外的标记` / `'<' 运算符保留` 之类）——脚本完全跑不起来；
2. **运行期中断**：原生命令（`java -version`、`npm run build`、`tauri build`）会把正常日志写进 **stderr**，
   在 `$ErrorActionPreference = 'Stop'` 下被 PowerShell 当成错误抛出，打包/构建中途失败。

**规则**

- 含中文的 `.ps1` **必须存为 UTF-8 with BOM**（`tauri/build-desktop.ps1` 就是这么存的）。
  只写 ASCII 的脚本可以不带 BOM，但要在文件头注明「keep this file ASCII-only」（`deploy/publish-update.ps1`）。
- 调用原生命令一律走包装函数，不要裸调：

  ```powershell
  function Invoke-Native {
    param([scriptblock]$Body)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Body 2>&1 | ForEach-Object { Write-Host $_ } } finally { $ErrorActionPreference = $prev }
    return $LASTEXITCODE
  }
  ```

  之后**只按退出码判断成败**（`if ($code -ne 0) { exit 1 }`），不要依赖 `$ErrorActionPreference` 帮忙中止。
- **改完 `.ps1` 必须复查 BOM**：不少编辑器/工具在保存时会悄悄去掉它（本仓库的 `edit` 工具就会）。
  一行检查：`$b=[IO.File]::ReadAllBytes($p); '{0:X2}{1:X2}{2:X2}' -f $b[0],$b[1],$b[2]` → 含中文时应为 `EFBBBF`。
- 语法自检（不必真的执行）：

  ```powershell
  $e=$null;$t=$null; [void][Management.Automation.Language.Parser]::ParseFile((Resolve-Path $p),[ref]$t,[ref]$e); $e
  ```

**反例**：`scripts/maven-java21.ps1` 最初既没有 BOM、又裸调了 `java -version`，
结果在用户机器上（PowerShell 5.1）连解析都失败——两处都修好后才真正可用。

---

## 9. 已知债务与待补充

以下都是**仓库现状**（不是已完成事项），写在这里避免下一个人重复困惑：

> 文档之间的同步责任由 [`docs/README.md`](README.md) 的「同步规则」表统一维护，本节只列**代码侧**的债务。
> 两处若冲突，以 `docs/README.md` 为准，并顺手修正本文件。

1. **i18n 残留**：
   - `frontend/src/views/AiImportPreviewView.vue` 模板区仍有大量裸中文（2026-09 的「预览改为按需编辑」
     改动引入的编辑区未走字典，见 3.1 的反例）；
   - `document.title` 仍是中文硬编码（`router/index.js:87`、`DiscoverView.vue:805,836`）；
   - `QuestionFormPanel.vue:558` 的 `typeLabels` 是中文常量；
   - **没有自动化的 i18n 校验脚本**（见第 7 节）。
2. **前端无测试基建**：`frontend/package.json` 只有 `dev` / `build` / `preview`，
   `devDependencies` 里的 `playwright-core` 目前未被任何代码引用。
3. **后端测试依赖私有样例**：`DocumentParserServiceTest`、`GraphPositionProbeTest` 依赖
   `sample-ai-files/` 下不入 git 的文件（版权材料）。**2026-09-11 起改为条件跳过**
   （`Assumptions` 判断文件是否存在）：干净克隆上这两个类标记为 skipped，`mvn test` 全绿。
   注意这意味着 CI 上这两类断言**不会真正执行**——本地有样例时才跑得到（详见 `CONTRIBUTING.md` §5）。
4. **Lombok 口径不一致**：见 1.5（现状用 Lombok，与「不使用 Lombok」的期望不符，待定）。
5. **`tauri/build-desktop.ps1` 的步骤编号**：屏幕输出是 `1/4`、`2/4`、`3/4`、`4/5`、`5/5`，
   编号与注释里的「5 步」不完全对应（功能性无影响，属文案瑕疵）。
6. **`doc/` 全部不入 git**：内部设计文档（`ai-import-spec.md`、`api-spec.md`、`center-spec.md`、
   `content-package-spec.md`、`format-evolution.md`、`ui-redesign-spec.md` 等）对贡献者不可见。
   跨端契约类的结论应沉淀进 `docs/`（**不写密钥、服务器地址与账号**）。
