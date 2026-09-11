# API 契约（本地后端 Spring Boot + 题库广场 Nuxt）

> 本文只写**代码里能验证的事实**，每条标注来源文件；无法确认的写「待确认」。
> **覆盖统计**：本地后端 **101 个端点**（15 个 `@RestController`，按方法级映射注解逐个数出）；广场服务端 **42 个端点**（`web/server/api/**` 全部路由文件）。
> 相关文档：[`data-model.md`](data-model.md)（表结构）、[`package-format.md`](package-format.md)（内容包格式，跨端契约）、[`features.md`](features.md)（业务规则）、[`design-mobile.md`](design-mobile.md)（Android 方案）。
> 注意：`web/` 被 `.gitignore` 忽略（官网不开源），广场侧事实取自本机工作树实现（**待确认**线上是否一致）。

---

## 0. 两部分总览

| | 本地后端 | 广场服务端 |
| --- | --- | --- |
| 技术 | Spring Boot（内嵌 Tomcat），Java | Nuxt（Nitro / h3 server routes），TypeScript + better-sqlite3 |
| 监听 | 桌面版由 Tauri 壳以 `--server.address=127.0.0.1 --server.port=0` 启动（随机端口）；开发态默认 8080 | 由官网部署（默认 `https://pickq.cn`） |
| 鉴权 | **无**（本地功能，不校验登录；仅 `/api/center/**` 的写操作要求广场登录态） | Cookie `pickq_session` 或 `Authorization: Bearer <token>`；管理员另判用户名白名单 |
| 响应包装 | `ApiResponse{code,data,message}`（成功 `code=200,message="ok"`） | `{code,data,message}`（`okResp`）；错误走 h3 错误体 |
| 错误 | `GlobalExceptionHandler` 统一映射 HTTP 状态码 + `ApiResponse{code,message}` | `apiError(status,message)` → `createError({statusCode,statusMessage,data:{code,message}})` |

关键事实来源：`tauri/src-tauri/src/main.rs` 第 172–173 行（本地回环 + 随机端口）、`SECURITY.md` 第 55 行与第 139 行（本地后端绑定 127.0.0.1、**无鉴权**是已知设计边界）、`src/main/java/com/tiku/dto/ApiResponse.java`、`web/server/utils/api.ts`。

---

# 第一部分：本地后端 API（Spring Boot）

## 1.1 统一响应与错误模型

### 统一响应（`dto/ApiResponse.java`）

```json
{ "code": 200, "data": { ... }, "message": "ok" }
```

- 成功：`ApiResponse.success(data)` → `code=200, message="ok"`（HTTP 200）。
- 失败：`ApiResponse.error(code, message)` → `data=null`，HTTP 状态码由 `GlobalExceptionHandler` 的 `@ResponseStatus` 决定（见下表）。

**不走 `ApiResponse` 包装的例外**（二进制/代理/HTML 场景，前端必须区别对待）：

| 路径 | 形态 | 来源 |
| --- | --- | --- |
| `POST /api/banks/{id}/export-tiku` | `application/zip` 字节流，`Content-Disposition: attachment; filename="content.tiku"` | `QuestionBankController` 第 144–153 行 |
| `GET /api/backup` | `application/octet-stream` 流式 zip（`StreamingResponseBody`） | `BackupController` |
| `GET /api/banks/{bankId}/images/{date}/{file}` | 图片原始字节 + `Content-Type` | `ImageController` |
| `GET /api/ai-import/jobs/{id}/images/{num}` | 原始字节，`Content-Type: image/png`（**硬编码 png**） | `AiImportController` 第 98–104 行 |
| `GET /api/ai-import/jobs/{id}/stream` | `text/event-stream`（SSE） | `AiImportController` |
| `/api/center/**` 的透传端点 | **远端官网响应体原文**（不解析、不包装），`Content-Type: application/json` | `CenterProxyController.textJson`、`CenterPublishController.textJson` |
| `GET /api/center/auth/github/callback` | `text/html; charset=utf-8` 结果页（成功/失败**都是 HTTP 200**） | `CenterAuthController.resultPage` |
| `GET /api/ai/presets` | 远端预设 JSON 原文（`application/json`，无包装） | `AiConfigController.getPresets` |

### 全局异常 → HTTP 状态码映射（`controller/GlobalExceptionHandler.java`）

| 异常 | HTTP | `code` | `message` |
| --- | --- | --- | --- |
| `NoSuchElementException` | 404 | 404 | 异常自带文案（如「题库不存在」「题目不存在：12」「图片不存在：…」） |
| `NoResourceFoundException` | 404 | 404 | `资源不存在：{path}` |
| `HttpRequestMethodNotSupportedException` | 405 | 405 | `请求方法不支持：{method}` |
| `IllegalArgumentException` | 400 | 400 | 异常自带文案（业务参数错误，见各端点） |
| `IllegalStateException` | **500** | 500 | 异常自带文案（业务状态错误，如「题目未配置正确答案：1」；会打 ERROR 日志） |
| `IOException`（普通请求） | 500 | 500 | `文件读写失败：{msg}` |
| `IOException`（异步/响应已提交/SSE 断开） | **204** | — | 无响应体（静默） |
| `AsyncRequestTimeoutException` | 204 | — | 无响应体（静默） |
| `MethodArgumentNotValidException` | 400 | 400 | 各字段 `@NotBlank/@NotNull/@Min` 的 message 用 `: ` 拼接 |
| `HttpMessageNotReadableException` | 400 | 400 | `请求体格式错误：{原因，截断 200 字}` |
| `MethodArgumentTypeMismatchException` | 400 | 400 | `参数类型错误：{name}={value}` |
| `MissingServletRequestPartException` / `MissingServletRequestParameterException` | 400 | 400 | `缺少必要参数：{msg}` |
| `MaxUploadSizeExceededException` | 400 | 400 | `上传文件过大（超过 200MB 限制）` |
| 其它 `Exception` | 500 | 500 | `内部服务器错误`（真实原因只进日志） |

上传限制：`spring.servlet.multipart.max-file-size=200MB` / `max-request-size=210MB`（`application.yml`）。

> ⚠️ **隐患 1**：本地端「连不上广场」用的也是 `IllegalStateException`（转发层的 `无法连接题库广场：{msg}`、`题库广场返回错误（HTTP {code}）`），因此**远端/网络故障会被表达为本地 500**（语义上更接近 502/504）。前端只能靠 message 文案区分。
>   远端错误体是 JSON 时，优先取其中的 `message` → `data.message` → `statusMessage` 作为文案；非 JSON 才回落到 `题库广场返回错误（HTTP {code}）`。
> ⚠️ **隐患 2**：`IllegalStateException` 一律 500，而其中不少是「客户端配置错误」（如 `请先登录题库广场账号`、`已有人工智能解析正在生成中，请稍候再试`），前端按 500 做通用提示会丢失可操作性。

## 1.2 本机 API 的鉴权现状（安全边界）

- 本地后端**完全没有登录/令牌机制**：所有 `/api/banks/**`、`/api/questions/**`、`/api/study-records/**`、`/api/exports/**`、`/api/backup/**`、`/api/ai/**`、`/api/stats/**` 均匿名可用（`SECURITY.md` 第 139 行明确列为已知设计边界）。
- 唯一的「鉴权」是**转发到广场**的写操作：`CenterPublishController.requireLogin()` 在本地无 token 时直接抛 `IllegalStateException("请先登录题库广场账号")`，**不发任何远程请求**；`CenterProxyController` 的只读端点匿名透传（已登录则自动附加 `Authorization: Bearer`，用于返回「我是否收藏/点赞」等个性化字段）。
- 桌面版由 Tauri 以 `127.0.0.1` 随机端口启动；`application.yml` 也显式设置 `server.address=127.0.0.1`，因此开发态 `java -jar` 同样仅监听本机。若未来需要局域网访问，必须另行设计身份验证与 CSRF 防护，不能仅放开监听地址。
- 广场 token 存 `{data-dir}/center-auth.json`（明文 JSON，`CenterAuthStore` 注释已声明「风险面与浏览器 cookie 相同」）。

## 1.3 端点清单（按控制器）

**约定**：下表「鉴权」列一律标注是否需要广场登录态；`/api/center/**` 之外的所有端点在本地均无鉴权。分页端点统一返回 `PageResult{records,total,pageNum,pageSize,pages}`（`dto/PageResult.java`）。

### 1.3.1 `HomeController` — 主页概览

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/home/overview` | 无 | — | `HomeOverviewResponse{bankCount,questionCount,dueTotal,wrongTotal,lastSession{sessionId,bankId,bankName,mode,modeLabel,correctCount,answeredCount,finishedAt}}` | 无（跨库聚合，`dueTotal` 只统计已启用复习的题库） |

### 1.3.2 `QuestionBankController` — `/api/banks`（17 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/banks` | 无 | body `{name*, description}` | `Long`（新题库 id） | 400 `题库名不能为空`（`@NotBlank`） |
| `GET /api/banks` | 无 | query `page=1`、`size=20`、`keyword`、`sort=created\|updated\|name` | `PageResult<QuestionBankResponse{id,name,description,version,authorName,source,createdAt}>` | 无 |
| `GET /api/banks/{id}` | 无 | — | `QuestionBankDetailResponse{id,name,description,packageKey,version,schemaVersion,authorId,authorName,source,parentKey,createdAt,reviewEnabled}` | 404 `题库不存在` |
| `GET /api/banks/{id}/questions` | 无 | query `page,size,keyword,questionType,category,topic,scope=all\|favorite\|wrong\|undone` | `PageResult<QuestionSummaryResponse{questionId,questionType,typeLabel,questionNumber,content,score,category,topic,favorite,answerKeys,analysis,materialContent}>`（含答案与解析，供「解析」弹窗一次到位） | 404 `题库不存在`；`scope=wrong` 无错题时返回空页（不报错） |
| `GET /api/banks/{id}/question-nav` | 无 | 同上的筛选参数（无分页） | `List<QuestionNavItemResponse{questionId,questionType,questionNumber}>`（全量轻量，`questionNumber ASC, id ASC`） | 404 `题库不存在` |
| `GET /api/banks/{id}/questions/practice` | 无 | query `page,size` | `PageResult<QuestionPracticeResponse{questionId,questionType,typeLabel,questionNumber,content,options,score,category,topic,favorite,materialId,materialContent,referenceAnswer}>`（**不含答案/解析**） | 404 `题库不存在` |
| `PUT /api/banks/{id}` | 无 | body `{name,description,source,authorName}`（null 不更新；身份字段不可改） | `null` | 400 `题库名不能为空`；404 `题库不存在` |
| `DELETE /api/banks/{id}` | 无 | — | `DeleteBankResult{deletedQuestions,affectedRecords}` | 404 `题库不存在` |
| `POST /api/banks/import` | 无 | body = 内容包 JSON 原文（`String`，非 JSON 对象也可） | `ImportResultResponse{result,bankId,message}`；`result` ∈ `CREATED`/`ALREADY_IMPORTED`/`VERSION_ADDED`/`BRANCHED` | 400 `内容包文件格式错误：{原因}` / `不支持的内容包格式版本：{v}` / `内容包缺少 packageKey` / `内容包缺少标题` / `内容包缺少版本号` / `题目缺少 questionKey` / `题目题干为空：{key}` / `未知题型：{type}` / `题目引用的材料不存在：{key}` / `内容包材料缺少 materialKey` |
| `POST /api/banks/import-tiku` | 无 | body = `.tiku` zip 字节（`application/octet-stream`） | 同 `import` | 400 `.tiku 容器读取失败：{msg}` + 上表校验错误 |
| `POST /api/banks/{id}/export` | 无 | body 可选 `{version,authorName,mode=UPGRADE\|BRANCH\|null,scope,category,topic}` | `ContentPackageFile`（内容包对象，含 `checksum`；前端保存文件时应剥离 `checksum`） | 404 `题库不存在：{id}`；400 `题目内容未变化，无需变更版本号（保持 {v} 原样导出）` |
| `POST /api/banks/{id}/export-tiku` | 无 | body 同上 | **zip 字节流**（`content.tiku`） | 同上 |
| `POST /api/banks/merge` | 无 | body `{name*,description,sourceBankIds*（≥2）}` | `MergeResult{bankId,name,questionsCopied,materialsCopied}` | 400 `新题库名称不能为空` / `请至少选择两个题库进行合并` |
| `POST /api/banks/{id}/questions/selection-copy` | 无 | body `{questionIds*（非空）,name,description,targetBankId}` | `MergeResult` | 400 `请先勾选要复制的题目`；404 `题库不存在` |
| `POST /api/banks/{id}/questions/ai-fill-answers` | 无 | body 可选 `{questionType,withAnalysis}` | `FillResult{total,filled,undetermined,failed}` | 400（AI 未配置等，来自 `AiConfigService`/`AiClientService`）；**同步执行较慢，前端勿设短超时**（控制器注释） |
| `PUT /api/banks/{id}/review-enabled` | 无 | body `{enabled*}` | `null` | 400 `状态不能为空`；404 `题库不存在` |
| `POST /api/banks/{id}/questions/batch` | 无 | body `{questions:[{volume*,questionType*,questionNumber,content*,options,topic,category,score*,answerKeys,answerText,analysis,materialId,referenceAnswer}]}` | `Integer`（插入数量） | 400 `题目列表不能为空` / `册数不能为空` / `题型不能为空` / `题干不能为空` / `分值不能为空` / `分值必须大于0` / `选项不能为空`（客观题）；404 `题库不存在` |

### 1.3.3 `QuestionController` — `/api/questions`（9 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/questions/{id}` | 无 | — | `QuestionDetailResponse{id,externalId,volume,questionType,typeLabel,questionNumber,content,options,topic,category,score,answerKeys[],answerText,analysis,favorite,materialId,referenceAnswer,materialContent}` | 404 `题目不存在：{id}` |
| `POST /api/questions/{id}/answer` | 无 | body `{selectedKeys*}` | `AnswerResultResponse{correct,correctKeys[],correctText,analysis}`（**不落库**，纯判题） | 400 `答案不能为空` / `主观题不支持自动判题，请提交作答后自行评分`；404 `题目不存在`；500 `题目未配置正确答案：{id}` |
| `POST /api/questions` | 无 | body = `QuestionCreateRequest`（`volume*`,`questionType*`,`content*`,`score*≥1`,`bankId*` 等） | `Long`（新题 id） | 400 校验文案（`册数不能为空`/`题型不能为空`/`题干不能为空`/`分值不能为空`/`分值必须大于0`/`选项不能为空`）；404 `题库不存在` |
| `PUT /api/questions/{id}` | 无 | body = `QuestionUpdateRequest`（null 不更新） | `null` | 400 `选项不能为空`；404 `题目不存在` |
| `DELETE /api/questions/{id}` | 无 | — | `null` | 404 `题目不存在` |
| `PUT /api/questions/{id}/favorite` | 无 | body `{favorite*}` | `null` | 400 `收藏状态不能为空`；404 `题目不存在` |
| `POST /api/questions/{id}/ai-analysis` | 无 | — | `String`（解析文本，**不落库**） | 404 `题目不存在`；500 `已有 AI 解析正在生成中，请稍候再试`（单飞锁）；AI 相关 400/500 |
| `POST /api/questions/ai-analysis-draft` | 无 | body `{bankId,questionTypeLabel,content*,options,answerKeys,answerText,referenceAnswer,materialContent}` | `String` | 400 `题干不能为空`；500 `已有 AI 解析正在生成中，请稍候再试` |
| `PUT /api/questions/{id}/analysis` | 无 | body `{analysis*}` | `null` | 400 `解析内容不能为空`；404 `题目不存在` |

### 1.3.4 `StudyRecordController` — 刷题记录 / 错题 / 复习（12 个端点，路径前缀 `/api`）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/study-records` | 无 | body `{questionId*,selectedKeys[],userAnswer,sessionId}` | `StudyRecordSubmitResponse{correct,correctKeys[],correctText,analysis,recordId,note}` | 400 `答案不能为空`（客观题）/ `主观题作答内容不能为空` / `题目不属于该会话的题库` / `会话已交卷，无法再提交作答（主观题可到回顾页自评赋分）` / `题目不属于该会话，无法提交`；404 `题目不存在：{id}` / `会话不存在：{id}`。**题目未配置答案时不报错**：`correct=null` + `note="本题尚未配置答案：作答已记录但无法判定对错…"` |
| `PUT /api/study-records/{id}/self-grade` | 无 | body `{earnedScore*}` | `SelfGradeResponse{recordId,selfGrade,earnedScore}` | 400 `自评得分不能为空` / `客观题自动判题，无需自评` / `自评得分需在 0 ~ {满分} 分之间（满分 {满分} 分）`；404 `刷题记录不存在：{id}` / `题目不存在：{id}` |
| `GET /api/banks/{bankId}/records` | 无 | query `page,size` | `PageResult<StudyRecordResponse{id,bankId,questionId,questionKey,selectedKeys[],correct,userAnswer,selfGrade,answeredAt}>`（时间倒序） | 404 `题库不存在` |
| `GET /api/questions/{questionId}/records` | 无 | query `page,size` | `PageResult<StudyRecordResponse>` | 404 `题目不存在：{id}` |
| `GET /api/banks/{bankId}/wrong-questions` | 无 | query `page,size` | `PageResult<WrongQuestionResponse{questionId,…,selectedKeys,userAnswer,selfGrade,lastAnsweredAt,wrongCount}>`（最近一次答错倒序） | 404 `题库不存在` |
| `GET /api/banks/{bankId}/review/due` | 无 | query `page,size` | `PageResult<ReviewDueItemResponse{questionId,…,level,intervalDays,dueAt,overdueDays,referenceAnswer}>` | 404 `题库不存在`；**复习未开启时返回空页**（不报错） |
| `GET /api/banks/{bankId}/review/summary` | 无 | — | `ReviewSummaryResponse{dueTotal,overdueTotal}`（开关关闭时均为 0） | 404 `题库不存在` |
| `DELETE /api/banks/{bankId}/review-states` | 无 | — | `Integer`（清除条数；作答记录与错题不受影响） | 404 `题库不存在` |
| `PUT /api/questions/{questionId}/review-suspend` | 无 | body `{suspended*}` | `null` | 400 `状态不能为空`；404 `题目不存在：{id}` |
| `GET /api/banks/{bankId}/progress` | 无 | — | `BankProgressResponse{bankId,totalQuestions,answeredQuestions,recordsCount,correctCount,accuracy,progressPercent}` | 404 `题库不存在` |
| `POST /api/study-records/import` | 无 | body = 记录文件 JSON 原文 | `RecordImportResultResponse{imported,skippedDuplicates,missingBanks[],missingQuestions[]}` | 400 `刷题记录文件格式错误：{原因}` / `不支持的记录文件格式版本：{v}` |
| `POST /api/study-records/export` | 无 | — | `StudyRecordFile{schemaVersion=1,exportedAt,records[{packageKey,packageVersion,questionKey,selectedKeys,isCorrect,userAnswer,selfGrade,answeredAt}]}` | 无 |

> 记录文件格式属于跨端契约，详见 `doc/study-record-spec.md` 与 `model/StudyRecordFile*.java`（`docs/design-mobile.md` 第 149 行也建议纳入正式契约）。

### 1.3.5 `PracticeSessionController` — 会话（5 个端点，路径前缀 `/api`）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/banks/{bankId}/sessions` | 无 | body 可选 `{mode, topic[], category[], count, startQuestionId, keyword, questionType, scope}`（空 body 等价「全部随机」） | `SessionCreateResponse{sessionId,total,questions[]}` | 400 `不支持的会话模式：{m}（ALL/SEQUENCE/TOPIC/REVIEW/WRONG/FAVORITE）` / `没有符合条件的题目，请调整练习范围` / `startQuestionId 仅支持 mode=SEQUENCE（从指定题按题号顺序往后做）` / `起点题目不属于该题库：{id}` / `复习计划未开启，请先在题库详情中开启「复习计划」`；404 `题库不存在` |
| `GET /api/banks/{bankId}/sessions` | 无 | query `page,size` | `PageResult<SessionResponse{id,mode,questionCount,answeredCount,correctCount,totalScore,maxScore,totalSeconds,status(IN_PROGRESS/COMPLETED),createdAt,finishedAt}>` | 404 `题库不存在` |
| `GET /api/sessions/{sessionId}` | 无 | — | `SessionDetailResponse{id,bankId,mode,questionCount,answeredCount,correctCount,totalScore,maxScore,totalSeconds,status,createdAt,finishedAt,questions[SessionQuestionItem]}`；**答案/解析仅在交卷后返回**（`finished ? … : null`） | 404 `会话不存在：{id}` |
| `POST /api/sessions/{sessionId}/finish` | 无 | body 可选 `{answers:[{questionId,selectedKeys[],userAnswer,seconds}]}`；**幂等**，允许未答完交卷 | `SessionFinishResponse{sessionId,totalQuestions,answeredCount,correctCount,totalScore,maxScore,totalSeconds,questions[{questionId,correct,score,seconds,earnedScore,selfGrade}]}` | 400 `会话已交卷，无法再提交作答`（已交卷又带 answers）/ `答案缺少题目` / `题目不属于该会话的题库` / `题目不属于该会话，无法提交`；404 `会话不存在：{id}` |
| `GET /api/banks/{bankId}/categories` | 无 | — | `BankCategoriesResponse{topics[],categories[]}`（去重排序） | 404 `题库不存在` |

### 1.3.6 `MaterialController` — 共享材料（4 个端点，`/api/banks/{bankId}/materials`）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/banks/{bankId}/materials` | 无 | — | `List<Material{id,bankId,content,sortOrder,createdAt,updatedAt}>`（`sortOrder,id` 升序） | 404 `题库不存在` |
| `POST /api/banks/{bankId}/materials` | 无 | body `{"content":"…"}`（`Map<String,String>`；**不是强类型 DTO**） | `Long`（材料 id） | 400 `材料内容不能为空`；404 `题库不存在` |
| `PUT /api/banks/{bankId}/materials/{id}` | 无 | body `{"content":"…"}` | `null` | 400 `材料内容不能为空`；404 `材料不存在：{id}`（跨库访问同样返回 404） |
| `DELETE /api/banks/{bankId}/materials/{id}` | 无 | — | `null`（组内题 `material_id` 置空，题保留） | 404 `材料不存在：{id}` |

### 1.3.7 `ImageController` — 题目图片（2 个端点，`/api/banks/{bankId}/images`）

| 方法 + 路径 | 鉴权 | 请求 | 响应 | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/banks/{bankId}/images` | 无 | multipart `file` | `{"name":"260829/ab12cd34ef56.png"}`（题干内用 `[图片:name]`） | 400 `图片文件为空` / `图片过大（超过 10MB）`；500 `图片保存失败` |
| `GET /api/banks/{bankId}/images/{date}/{file}` | 无 | 路径两段（`date/file`） | 图片原始字节 + `Content-Type`（png/jpg/gif/webp/bmp/octet-stream） | 400 `非法图片名`；404 `图片不存在：{name}`；500 `图片读取失败` |

### 1.3.8 `StatsController` — 学习统计（2 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` |
| --- | --- | --- | --- |
| `GET /api/stats/summary` | 无 | — | `StatsSummaryResponse{todayCount,todayDecided,todayCorrect,streakDays,longestStreak,totalAnswered,decidedTotal,correctTotal,totalSeconds,dueToday,overdue,reviewQuestionCount,daily[365]{date,count,decided,correct},dueForecast[30]{date,count},levelDist[6]}` |
| `GET /api/stats/detail` | 无 | — | `StatsDetailResponse{banks[BankStat{bankId,name,total,answered,decided,correct}],wrongHeal{currentWrong,wrongTrend[6]{month,count},reproduceDecided,reproduceCorrect,recentlyHealed[5]},recentSessions[10]{sessionId,bankName,mode,finishedAt,questionCount,answered,correct}}` |

（两者均为全库聚合，无错误分支；口径见 `StatsSummaryResponse` 类注释。）

### 1.3.9 `BackupController` — 备份/恢复（2 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/backup` | 无 | — | 流式 zip（`tiku-backup-yyyyMMdd-HHmmss.zip`：`database.sql` + `images/` + `ai-config.json` + `恢复说明.txt`） | 500 `备份打包失败`（日志；IO 异常走全局处理） |
| `POST /api/backup/restore-prepare` | 无 | multipart `file`（备份 zip） | `{"dataDir":"<数据目录>"}`；桌面壳随后带 `--tiku.restore-stage` 重启后端执行恢复 | 400 `请选择备份文件` / `备份包为空` / `备份包条目过多` / `备份包含非法路径：{name}` / `备份包路径越界：{name}` / `备份包解压后过大` / `备份包缺少 database.sql，不是有效的拾题备份文件` / `备份文件处理失败：{msg}` |

> 解压安全：条目名按**路径段**校验（拒绝 `..`、`.`、反斜杠与绝对路径），并在归一化后要求仍落在 `restore/staged` 内；
> 解压总大小上限默认 **5GB**（可用 `tiku.backup.max-restore-bytes` 覆盖），且在**写入过程中**逐块累计判断，
> 超限立即中止并清理暂存目录——不会先把压缩包完整展开再校验。

> 实测提醒（2026-09-11）：`GET /api/backup` 是**流式 zip**（无 `Content-Length`、分块传输），
> 用 PowerShell `Invoke-WebRequest -OutFile` 抓会得到一个**截断的坏 zip**（报 "End of Central Directory record could not be found"）。
> 想脚本化取备份请用 `curl.exe -o backup.zip http://127.0.0.1:<port>/api/backup`，或直接用应用内「完整备份」按钮。
> （注意：应用运行时 `tiku.mv.db` 会被 H2 独占锁定，因此**不能用复制文件的方式**做热备份，这也是该接口存在的意义。）

### 1.3.10 `ExportController` — `/api/exports`（7 个端点，**全部免登录**，见类注释）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/exports/export` | 无 | body 可选 `{bankId*,version,dir}` | `ExportRecordResponse{id,bankId,bankName,packageKey,version,filePath,fileName,sizeBytes,published,publishedVersion,createdAt,fileExists}` | 400 `缺少题库 ID（bankId）` / `导出目录不能为空` / `导出目录必须是绝对路径：{dir}` / `导出目录指向的是文件，不是目录：{path}` / `版本号不合法（只能是字母、数字、点、下划线、连字符，1-100 个字符）：{v}`；404 `题库不存在：{id}`；500 `无法创建导出目录：{path}（{msg}）` / `导出目录不可写：{path}` / `导出文件写入失败：{path}（{msg}）` |
| `GET /api/exports` | 无 | — | `List<ExportRecordResponse>`（导出时间倒序） | 无 |
| `DELETE /api/exports/{id}?deleteFile=false\|true` | 无 | query `deleteFile`；`deleteFile=true` 时同时删磁盘文件（文件不存在也算成功） | `DeleteExportRecordResult{id,fileDeleted}` | 400 `导出记录的文件路径不合法：{path}`（记录指向异常路径时）；404 `导出记录不存在：{id}`；500 `删除导出文件失败：{msg}` |
| `POST /api/exports/{id}/mark-published` | 无 | body 可选 `{version}` | `ExportRecordResponse` | 400 `请提供发布版本号（version）`（记录与该 body 都无版本时）；404 `导出记录不存在：{id}` |
| `GET /api/exports/prefs` | 无 | — | `ExportPrefsResponse{lastDir,defaultDir,dirExists{lastDir?:bool}}` | 无 |
| `PUT /api/exports/prefs` | 无 | body 可选 `{lastDir}`（空 = 清除记忆） | `ExportPrefsResponse` | 400 目录校验文案（同 `export`）；500 `保存导出偏好失败：{msg}` |
| `GET /api/exports/next-version?bankId=` | 无 | query `bankId*` | `NextVersionResponse{suggested,lastPublished}` | 400 `缺少题库 ID（bankId）`；404 `题库不存在：{id}` |

> `DELETE /api/exports/{id}` 的「缺少导出记录 ID」分支：路径变量为必填，实际由 400「参数类型错误」或 404 覆盖（`require(id)` 的 null 分支在 HTTP 路径下不可达）——**冗余分支**。

### 1.3.11 `AiConfigController` — `/api/ai`（2 个端点，免登录）

| 方法 + 路径 | 鉴权 | 请求 | 响应 | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/ai/presets?center=&refresh=false` | 无 | query `center`（默认 `https://pickq.cn`）、`refresh` | **远端 `{center}/config/ai-presets.json` 原文**（`application/json`，内存缓存 1 小时；`refresh=true` 强制刷新） | 400 `广场地址需为 http(s) 链接` / `广场地址不合法`；500 `无法获取模型预设：{原因}`（前端据此回退内置预设） |
| `POST /api/ai/models` | 无 | body 可选 `{baseUrl*,apiKey}` | `AiModelsResponse{models[],resolvedBaseUrl,count}` | 400 `请填写服务地址（baseUrl）` / `请先填写 API Key`（公网地址且本机未存 Key）/ `baseUrl 不能为空` / `baseUrl 必须使用 http(s) 链接` / `http 仅支持本机或局域网地址（如 192.168.x.x），公网请使用 https`（后三条来自 `AiConfigService.validateBaseUrl`） |

### 1.3.12 `AiImportController` — `/api/ai-import` 与 AI 配置（13 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/ai-import/jobs` | 无 | multipart `files`（多选）+ query `bankId,aiSupplement,thinking,engine` | `Long`（任务 id） | 400 `请至少选择一个文件` / `文件不能为空` / `请先在「设置-AI 配置」中填写模型信息` / `已选择 MinerU 云端解析，但「设置」中未配置 MinerU 解析 API Key` / `文件读取失败：{msg}` / `AI 导入任务较多，请等待进行中的任务完成后再试`；400 `上传文件过大（超过 200MB 限制）` |
| `GET /api/ai-import/jobs/{id}` | 无 | — | `AiJobResponse{id,status,stage,progress,fileName,fileType,aiSupplement,thinking,engine,processPath,confirmed,fileCount,currentFileIndex,questions[],materials[],warningHint,errorCode,error,createdAt,finishedAt}`；`errorCode` 为稳定失败码，`error` 为可直接展示的安全提示 | 404 `任务不存在：{id}`（`NoSuchElementException`） |
| `GET /api/ai-import/jobs/active` | 无 | — | `List<AiJobResponse>`（进行中） | 无 |
| `GET /api/ai-import/jobs/recent?limit=5` | 无 | query `limit` | `List<AiJobResponse>`（未确认导入的最近任务） | 无 |
| `DELETE /api/ai-import/jobs/{id}` | 无 | — | `null`（进行中 → 标记 `CANCELED`；终态 → 物理删除 + 清文件） | 404 `任务不存在：{id}` |
| `GET /api/ai-import/jobs/{id}/images` | 无 | — | `List<JobImageInfo>`（编号/文件名/扩展名，按编号排序） | 404 任务不存在 |
| `GET /api/ai-import/jobs/{id}/material-snippets` | 无 | — | `List<ContentPackageMaterial>`（资料分析材料素材） | 404 任务不存在 |
| `GET /api/ai-import/jobs/{id}/images/{num}` | 无 | 路径 `num`（从 1 起） | 图片原始字节（`Content-Type: image/png` 硬编码） | 400 `图片不存在：{num}`（`IllegalArgumentException`）；`num` 非整数 → 400 `参数类型错误：num=…` |
| `GET /api/ai-import/jobs/{id}/stream` | 无 | — | SSE：阶段变化/完成/取消实时推送（与轮询并存，断开自动回退轮询） | 任务终态时立即推最终快照并关闭；SSE 超时/断开 → 204 静默 |
| `POST /api/ai-import/jobs/{id}/confirm` | 无 | body 可选 `{bankId,questions[],materials[]}`（`bankId` 空 = 新建题库；不传 questions = 用后端保存的 AI 结果） | `AiImportConfirmResponse{bankId,importedCount}` | 404 `任务不存在：{id}`；400 `任务未完成或已失败，无法导入` / `没有可导入的题目` / `题目数量超出上限（5000）` / `材料数量超出上限（500）` / `材料缺少 materialKey` + 导入管线校验；重复 confirm 幂等（`confirmed=1` 后 `recent` 不再展示） |
| `GET /api/ai/settings` | 无 | — | `AiSettingsResponse{baseUrl,hasKey,maskedKey,model,visionModel,thinking,hasMineruKey,maskedMineruKey}`（Key 脱敏，前端永不接触完整 Key） | 无 |
| `POST /api/ai/settings` | 无 | body `{baseUrl,apiKey,model,visionModel,thinking,mineruKey}`（空字段 = 保留旧值；`apiKey` 空 = 保留旧 Key） | `null` | 400 `请填写 apiKey`（合并后地址为公网）/ `baseUrl…` 地址校验文案；500 `保存 AI 配置失败` |
| `POST /api/ai/settings/test` | 无 | — | `TestResult`（来自 `AiClientService.test`） | 400 `请先填写完整的模型配置`；配置类错误（Key 无效/模型名错/限流）→ 被转成 400 + 真实原因（`IllegalStateException` → `IllegalArgumentException`） |

### 1.3.13 `CenterAuthController` — `/api/center/auth`（7 个端点，桌面端经本地代理登录广场）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/center/auth/login` | 本地无；转发时带 `X-Desktop: 1` | body `{center,username*,password*}` | `{"user":{…}}`（**token 不返回给前端**，由本地后端存 `center-auth.json`） | 400 `请输入用户名和密码` / `广场地址需为 http(s) 链接` / `广场地址不合法`；500 官网错误原文（如 `账号或密码错误`、`账号已被封禁`、`该账号通过 GitHub 登录且尚未设置密码…`）/ `登录成功但未返回会话令牌，请重试` / `无法连接题库广场：{msg}` |
| `POST /api/center/auth/register` | 同 | body `{center,username*,password*,nickname,turnstileToken}` | `{"user":{…}}` | 400 同上 + 官网注册校验（`用户名需为 2–24 位…`/`密码至少 6 位`/`该用户名已被注册`(409)/`人机验证未通过…`）；500/409 由官网状态透传为 500（见隐患 1） |
| `POST /api/center/auth/logout` | 本地无 | body 可选 `{center}` | `null`（尽力通知官网，失败也清本地） | 无（远程失败被吞） |
| `GET /api/center/auth/me?center=` | 本地无 | — | `{"user":{…}\|null}`；本地无 token 时直接返回 `user:null`；本地有 token 但官网无此会话 → **清除本地 token** | 500 `无法连接题库广场：{msg}` / `题库广场响应格式异常` / 官网错误原文 |
| `GET /api/center/auth/status` | 本地无 | — | `{"loggedIn":true\|false}`（不发远程请求） | 无 |
| `POST /api/center/auth/github/start` | 本地无 | body 可选 `{center}` | `{"url":"…"}`（官网 GitHub 授权地址，前端用系统浏览器打开；`state` 为本地生成的 32 位 hex，**5 分钟、单次**） | 400 广场地址文案；500 `无法获取本地服务端口，请重启拾题后重试` |
| `GET /api/center/auth/github/callback?ticket=&state=&error=` | 匿名（浏览器回环回调） | query | **HTML 结果页，HTTP 一律 200**：`state` 无效/过期 → 「登录链接无效或已过期…」；`error=banned` → 「该账号已被封禁…」；`error=github` → 「GitHub 授权未完成…」；成功 → 「已登录为 {username}，请回到拾题应用」 | 不抛 JSON 错误（页面稳定渲染优先） |

### 1.3.14 `CenterProxyController` — `/api/center`（只读浏览 + 登录态写转发，11 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 | 对应广场端点 |
| --- | --- | --- | --- | --- |
| `GET /api/center/packs` | 本地无（已登录自动带 Bearer） | query `center,sort=new,q,page=1,size=12` | 官网 `/api/packs` **原文** | `GET /api/packs` |
| `GET /api/center/packs/{packageKey}` | 同上 | query `center` | 官网原文 | `GET /api/packs/{packageKey}` |
| `GET /api/center/packs/{packageKey}/comments` | 同上 | query `center` | 官网原文 | `GET /api/packs/{packageKey}/comments` |
| `GET /api/center/authors/{id}` | 同上 | query `center` | 官网原文 | `GET /api/authors/{id}` |
| `POST /api/center/import` | 本地无 | body `{center,packageKey*,version*}` | `ApiResponse<ImportResultResponse>`（拉取官网文件字节 → **按 PK 魔数判 zip / 否则按 v1 JSON** → 走本地导入管线） | `GET /api/packs/{k}/{v}/file` |
| `POST /api/center/import-external` | 本地无 | body `{url*}` | `ApiResponse<ImportResultResponse>` | 任意 `http(s)` 直链 |
| `POST /api/center/packs/{packageKey}/favorite` | **需广场登录**（未登录官网返回 401，错误透传） | query `center`；body 原样透传 | 官网原文 | `POST /api/packs/{k}/favorite` |
| `POST /api/center/packs/{packageKey}/comments` | 同 | query `center`；body 原样透传 | 官网原文 | `POST /api/packs/{k}/comments` |
| `DELETE /api/center/packs/{packageKey}/comments/{commentId}` | 同 | query `center` | 官网原文 | `DELETE /api/packs/{k}/comments/{id}` |
| `POST /api/center/packs/{packageKey}/comments/{commentId}/like` | 同 | query `center`；body 原样透传 | 官网原文 | `POST /api/packs/{k}/comments/{id}/like` |
| `POST /api/center/authors/{authorId}/follow` | 同 | query `center`；body 原样透传 | 官网原文 | `POST /api/authors/{id}/follow` |

通用错误（全部为本地 500 + 文案，除 400 的地址/参数校验）：400 `广场地址需为 http(s) 链接` / `广场地址不合法` / `广场地址不能包含查询参数或片段` / `下载链接需为 http(s) 链接` / `下载链接不合法` / `外链方式需要提供内容包下载链接（http/https）` / `广场未返回内容包文件`；500 `无法连接题库广场：{msg}` / 官网返回的 message 原文（从远端错误体 `message` → `data.message` → `statusMessage` 依次取，非 JSON 时回落为 `题库广场返回错误（HTTP {code}）`） / `广场返回的内容包文件过大`（>512MB）。超时按请求传入：连接 8s；读 30s（内容包下载 60s；发布与上传 300s）。

### 1.3.15 `CenterPublishController` — `/api/center`（发布侧，7 个端点，**全部要求已登录广场**）

| 方法 + 路径 | 鉴权 | 请求 | 响应 | 对应广场端点 |
| --- | --- | --- | --- | --- |
| `POST /api/center/publish/inspect` | 需本地已登录（`requireLogin`） | multipart `file`（`.tiku`/`.json`，≤200MB） | `ApiResponse<Inspection{packageKey,version,title,description,source,schemaVersion,questionsCount,materialsCount}>`——**纯本地体检，不导入、不落库、不发远程请求** | 无（本地） |
| `POST /api/center/publish` | 需登录 | multipart `file` + `storageKind`（缺省 `HOSTED`）+ `downloadUrl` + `title` + `description` + `source`；`center` 可作 query | 官网 `/api/packs/upload` **原文** | `POST /api/packs/upload` |
| `POST /api/center/publish-from-path` | 需登录 | body `{filePath*,storageKind,downloadUrl,title,description,source,exportRecordId}`；query `center` | 官网上传响应原文；带 `exportRecordId` 时顺带把导出记录标记为已发布（标记失败只记日志） | `POST /api/packs/upload` |
| `GET /api/center/me/packs` | 需登录 | query `center,page,size`（上游默认 20，上限 50） | 官网原文 `{records,total,page,size}` | `GET /api/me/packs` |
| `DELETE /api/center/packs/{packageKey}` | 需登录 | query `center` | 官网原文 | `DELETE /api/packs/{packageKey}` |
| `PUT /api/center/packs/{packageKey}/{version}` | 需登录 | query `center`；body 原样透传（官网只接受 `description`/`source`/`downloadUrl`） | 官网原文 | `PUT /api/packs/{k}/{v}` |
| `PUT /api/center/packs/{packageKey}/{version}/file` | 需登录 | multipart `file`；query `center` | 官网原文（`{…,'文件已托管'}`） | `PUT /api/packs/{k}/{v}/file` |

本地前置校验（**不合法则一字节不出网**，`IllegalArgumentException` → 400）：

| 校验 | 文案 |
| --- | --- |
| 未登录 | 500 `请先登录题库广场账号`（`IllegalStateException`） |
| 空文件/超限 | 400 `请选择要上传的内容包文件（.tiku 或 .json）`、`内容包文件超过 200MB 上限` |
| 托管方式 | 400 `托管方式只能是 HOSTED 或 EXTERNAL` |
| 外链 | 400 `外链方式需要提供内容包下载链接（http/https）` / `下载链接需为 http(s) 链接` / `下载链接不合法` |
| 本地路径（`publish-from-path`） | 400 `缺少请求体（需要 filePath）` / `请提供要发布的内容包文件路径（filePath）` / `内容包文件路径不合法：{p}` / `内容包文件不存在：{p}` / `内容包文件路径是目录，不是文件：{p}` / `内容包文件为空：{p}`；500 `读取内容包文件失败：{msg}` |
| 补传身份核对 | 400 `文件内 packageKey 与登记不一致（文件 {a}，登记 {b}）` / `文件内 version 与登记不一致（文件 {a}，登记 {b}）` |
| 内容包体检（`ContentPackageInspector`） | 同 `.tiku`/`.json` 校验文案（见 `package-format.md`） |

超时：连接 8s、读取 **300s**（大文件上传/发布较慢）。

## 1.4 本地端点不覆盖的能力（明确没有的接口）

| 能力 | 现状 |
| --- | --- |
| 打印试卷 | **无专用端点**：「打印试卷」是前端页面 `frontend/src/views/PrintPaperView.vue`，调用 `POST /api/banks/{id}/export`（`scope/category/topic` 过滤）+ 浏览器 `window.print()`（同文件第 238 行） |
| 窗口尺寸/位置持久化 | 无后端端点（Tauri 壳与前端 localStorage 管理） |
| 学习提醒/通知 | 无 |
| 多用户/账号 | 无（本地库无用户表） |
| 广场侧写操作直连 | 桌面端**必须**经 `/api/center/**` 代理（token 不出本机），见 `CenterAuthController` 类注释 |

---

# 第二部分：广场服务端 API（Nuxt / h3，`web/server/api/**`）

## 2.1 统一响应与错误模型

### 成功响应（`web/server/utils/api.ts`）

```json
{ "code": 200, "data": { ... }, "message": "ok" }
```

`okResp(data, message='ok')`；各端点的 `message` 多为中文成功文案（如「登录成功」「已下架」「评论成功」）。

### 错误响应

`apiError(status, message)` = `throw createError({ statusCode: status, statusMessage: message, data: { code: status, message } })`。

- HTTP 状态码 = 传入的 `status`；响应体为 h3 错误体，代码把 `{code,message}` 放在 `data` 里，外层另有 `statusCode`/`statusMessage`。
- 桌面端的错误提取顺序（`CenterAuthController.extractError`：顶层 `message` → `data.message` → `statusMessage`；`CenterProxyController.extractRemoteError`：`message` → `statusMessage`）说明线上实际出现过多种形态，**客户端应以 `statusCode` + `statusMessage` 为主、`data.message` 为辅**。
- ⚠️ **隐患 3**：`utils/auth.ts` 的 `requireUser` 抛的是 `createError({statusCode:401, statusMessage:'请先登录'})`，**不带 `data.{code,message}`**，与 `apiError` 的形态不一致；客户端若只解析 `data.code` 会把 401 当成未知错误。
- 未捕获异常 → Nuxt 默认 500（错误页/JSON），文案不可控（**待确认**生产环境是否统一包装）。

### `apiError` 文案总表（按端点）

见 2.3 各表「常见错误（文案）」列；跨端点复用的固定文案：

| 文案 | 状态码 | 出现位置 |
| --- | --- | --- |
| `请先登录`（`requireUser`，无 `data.code`） | 401 | 所有需登录端点 |
| `需要管理员权限` | 403 | 所有 `/api/admin/**` |
| `操作太频繁，请稍后再试` | 429 | 发布/评论/收藏/关注/下载计数/举报/密码等 | 
| `未找到该作品` / `该作品已下架` | 404 | 作品相关读写 |
| `无效的评论 id` / `未找到该评论` | 400 / 404 | 评论相关 |
| `人机验证未通过，请刷新页面后重试` | 400 | 注册 / 忘记密码 / 绑定邮箱（带 token 时） |

## 2.2 认证与鉴权（`web/server/utils/auth.ts`、`admin.ts`、`github.ts`、`mailer.ts`、`turnstile.ts`、`ratelimit.ts`）

### 2.2.1 密码

- 算法：Node `crypto.scryptSync(password, salt, 64)`；存储格式 `salt:hash`（salt 16 字节 hex）。校验用 `timingSafeEqual`（`hashPassword` / `verifyPassword`）。
- 规则：注册/重置 6–128 位（`api/auth/register.post.ts`、`reset-password.post.ts`、`admin/users/[id]/password.post.ts`）。
- **无密码账号**：GitHub 首次登录创建的账号 `password_hash = ''`（哨兵 `NO_PASSWORD_HASH`），`verifyPassword` 对任意输入返回 false → 密码登录通道关闭，直到本人在 `PUT /api/me/password` 设置密码（或管理员重置）。

### 2.2.2 会话与凭证形态

| 项 | 事实 |
| --- | --- |
| Cookie 名 | `pickq_session`（`SESSION_COOKIE`），`httpOnly`、`sameSite=lax`、`path=/`、`maxAge=30 天`、`secure` 仅 `NODE_ENV=production` |
| Bearer | `Authorization: Bearer <token>`（桌面端经本地代理使用，**同一张 session 表**；`getSessionUser` 优先 Bearer，其次 cookie） |
| 令牌生成 | `randomBytes(32).toString('hex')`（64 字符）；DB 只存 `sha256(token)` |
| 有效期 | 30 天；`findUserByTokenHash` 命中即**滑动续期**到 `now + 30 days` |
| 桌面端下发 token | 登录/注册在请求头 `X-Desktop: 1` 时，响应体额外返回 `token`（`okResp({user, token})`）；桌面本地后端保存到 `center-auth.json`，前端不接触 |
| 登出 | `POST /api/auth/logout` 删除该 token 会话 + 清 cookie（Bearer 或 cookie 二选一） |
| 封禁 | `users.banned=1` → `getSessionUser` 一律返回 `null`（**即时生效，无需清理会话表**）；登录在密码校验通过后返回 **403 `账号已被封禁`**（与 401 区分）；GitHub 回调按 `error=banned` 处理 |
| 重置密码 | 自助重置：改 hash + 清空该用户**全部会话** + 标记 token 已用（同一事务）；管理员重置同理（旧登录立即失效） |
| 修改密码 | `PUT /api/me/password` 成功后**保留当前会话**（不踢自己） |

### 2.2.3 登录方式

| 方式 | 端点/流程 | 要点 |
| --- | --- | --- |
| 用户名/邮箱 + 密码 | `POST /api/auth/login` | 字段名固定为 `username`，值可以是用户名或**已验证邮箱**（`account.includes('@')` 且 `email_verified=1` 才按邮箱匹配）；未验证邮箱不作为凭据 |
| 注册 | `POST /api/auth/register` | 用户名 `[\p{L}\p{N}_-]{2,24}`；昵称 ≤20；可选 Turnstile；重名 409 |
| GitHub OAuth（网页） | `GET /api/auth/github/start` → GitHub → `GET /api/auth/github/callback` | state 存 httpOnly cookie `pickq_gh_state`（10 分钟，一次性）；账号策略：① 已绑定 `github_id` 直接登录 → ② 未绑定但 GitHub 已验证主邮箱命中本地账号则合并绑定 → ③ 新建无密码账号（用户名候选 `login` / `login_github` / `login_gh2…`）；失败一律 302 `/login?error=github`（state 不匹配是唯一保留 400 的错误） |
| GitHub OAuth（桌面，RFC 8252） | 本地 `POST /api/center/auth/github/start` → 官网 `start?desktop=1&callback=…&state=…` → 官网回调 302 到 `http://127.0.0.1:{port}/api/center/auth/github/callback?ticket=…` → 本地 `POST /api/auth/desktop-exchange` | 桌面 `callback` 白名单**只允许** `http://127.0.0.1:<port>/api/center/auth/github/callback`（正则 `DESKTOP_CALLBACK_RE`，`localhost`/`::1`/其它域名一律 400）；`desktopState` 16–128 位 `[A-Za-z0-9_-]`；ticket 一次性、**60 秒**、库里只存 sha256 |
| 忘记密码 | `POST /api/auth/forgot`（匿名）→ 邮件链接 `/reset?token=…` → `POST /api/auth/reset-password` | 重置令牌 30 分钟、单次；防枚举：**任何情况都返回同一句**「如果该账号存在且已绑定邮箱，重置邮件已发送，请查收」 |
| 邮箱绑定 | `POST /api/me/email`（需登录）→ 邮件链接 `GET /api/auth/verify-email?token=…` | 待验证邮箱存 `auth_tokens.email`，**验证成功才写 `users.email`**；令牌 24 小时、单次；邮箱已被他人验证 → 409 `该邮箱已被其他账号使用`；验证结果 302 到 `/me/profile?email=ok\|fail` |

### 2.2.4 管理员判定

- `process.env.ADMIN_USERNAMES`（逗号分隔用户名）命中即管理员（`utils/admin.ts`）；**不落库、无角色表**。
- 管理端端点一律「`requireUser` → `isAdminUser` → 否则 403 `需要管理员权限`」。
- 管理端**禁止对自己执行封禁**（400 `不能对自己的账号执行封禁操作`）。
- 管理端返回的用户列表/举报列表**绝不返回邮箱原文**，只给掩码（`maskEmail`：`a***@qq.com`）与 `notifiable` 布尔。

### 2.2.5 邮件与人机验证（能力开关）

| 能力 | 开关 | 未配置时的行为 |
| --- | --- | --- |
| 邮件发送 | `RESEND_API_KEY`（+`MAIL_FROM`）优先，或 `SMTP_HOST` + `MAIL_FROM`（`SMTP_PORT` 默认 465、`SMTP_SECURE` 按端口判定），超时 10s | `isMailConfigured()=false`：`forgot` / `me/email` 返回 **503 `邮件服务尚未配置，请联系管理员`**；管理员处置举报时不发信（`notified:false, reason:'mail-not-configured'`） |
| Cloudflare Turnstile | `TURNSTILE_SECRET` | **未配置直接放行**（本地开发/灰度不阻塞）；配置后校验失败 → 400 `人机验证未通过，请刷新页面后重试` |
| GitHub 登录 | `GITHUB_CLIENT_ID` + `GITHUB_CLIENT_SECRET` | 503 `未配置 GitHub 登录` |
| 站点基址 | `PUBLIC_BASE_URL` 优先，其次请求头推断，兜底 `https://pickq.cn`（OAuth 回调与邮件链接共用） | — |

### 2.2.6 限流（`utils/ratelimit.ts`：**内存 Map，单实例**，多实例部署不共享）

| 键 | 阈值 | 端点 |
| --- | --- | --- |
| `li:{ip}` / `lu:{account}` | 20 / 分、10 / 分 | `POST /api/auth/login` |
| `re:{ip}` | 5 / 10 分钟 | `POST /api/auth/register` |
| `fgip:{ip}` / `fga:{account}` | 8 / 10 分钟、5 / 10 分钟 | `POST /api/auth/forgot` |
| `rsp:{ip}` | 10 / 10 分钟 | `POST /api/auth/reset-password` |
| `dx:{ip}` | 20 / 分 | `POST /api/auth/desktop-exchange` |
| `ghcb:{ip}` | 20 / 分 | `GET /api/auth/github/callback` |
| `pub:{userId}` | 10 / 分 | `POST /api/packs`、`POST /api/packs/upload` |
| `cm:{userId}` | 20 / 分 | `POST /api/packs/{k}/comments` |
| `fav:{userId}` / `fol:{userId}` | 60 / 分 | 收藏、关注 |
| `dl:{ip}:{packageKey}` | 20 / 分 | `POST /api/packs/{k}/download-clicks` |
| `rp:{ip}` | 10 / 分 | `POST /api/reports` |
| `meem:{userId}` | 3 / 10 分钟 | `POST /api/me/email` |
| `pws:{userId}` / `pwi:{ip}` | 10 / 分、20 / 分 | `PUT /api/me/password` |

IP 取值：`getRequestIP(event, { xForwardedFor: true }) ?? 'unknown'`（依赖反向代理正确设置 `X-Forwarded-For`——**待确认**部署是否清洗该头，未清洗时客户端可伪造绕过 IP 限流）。

## 2.3 端点清单

**约定**：所有成功响应均为 `{code:200,data,message}`；下表只写 `data` 与错误。路径中 `{packageKey}`/`{version}`/`{id}` 为路径参数。

### 2.3.1 认证（`web/server/api/auth/**`，10 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误（HTTP + 文案） |
| --- | --- | --- | --- | --- |
| `POST /api/auth/login` | 匿名 | `{username*,password*}` | `{user}`；`X-Desktop: 1` 时另含 `token`；`message="登录成功"` | 400 `请输入账号和密码`；400 `该账号通过 GitHub 登录且尚未设置密码，请先到网页端「编辑资料 → 登录密码」设置密码`；401 `账号或密码错误`；403 `账号已被封禁`；429 `尝试过于频繁，请稍后再试` |
| `POST /api/auth/register` | 匿名 | `{username*,password*,nickname?,turnstileToken?}` | `{user}`（桌面端含 `token`），`message="注册成功"` | 400 `用户名需为 2–24 位中文、字母、数字、下划线或连字符` / `密码至少 6 位` / `密码过长` / `昵称最长 20 字` / `人机验证未通过，请刷新页面后重试`；409 `该用户名已被注册`；429 `注册过于频繁，请稍后再试` |
| `POST /api/auth/logout` | 需登录 | — | `null`，`message="已退出登录"` | 401 `未登录` |
| `GET /api/auth/me` | 匿名 | — | `{user\|null, isAdmin}` | 无 |
| `POST /api/auth/forgot` | 匿名 | `{account*,turnstileToken?}` | `{sent:true}`，`message="如果该账号存在且已绑定邮箱，重置邮件已发送，请查收"` | 400 `人机验证未通过，请刷新页面后重试`；429 `请求过于频繁，请 10 分钟后再试`；503 `邮件服务尚未配置，请联系管理员` |
| `POST /api/auth/reset-password` | 匿名 | `{token*,password*}` | `{reset:true}`，`message="密码已重置，请用新密码登录"` | 400 `密码至少 6 位` / `密码过长` / `链接无效或已过期，请重新申请`；429 `操作太频繁，请稍后再试` |
| `GET /api/auth/verify-email?token=` | 匿名 | query `token` | **302** → `/me/profile?email=ok` 或 `?email=fail`（不抛 JSON 错误） | — |
| `GET /api/auth/github/start?next=&desktop=&callback=&state=` | 匿名 | query | **302** → GitHub 授权页 | 400 `桌面登录回调地址不合法` / `桌面登录状态不合法`；503 `未配置 GitHub 登录` |
| `GET /api/auth/github/callback?code=&state=` | 匿名 | query | **302** → `next`（默认 `/packs`）或 `/login?error=github`；桌面模式 → 回环 `callback?ticket=…&state=…` 或 `?error=github\|banned&state=…` | 400 `登录状态校验失败，请重试`（state 不匹配）；503 `未配置 GitHub 登录` |
| `POST /api/auth/desktop-exchange` | 匿名 | `{ticket*}` | `{token,user}`，`message="登录成功"` | 400 `登录票据无效或已过期，请回到应用重新登录`（无效/过期/已用统一）；403 `账号已被封禁`；429 `尝试过于频繁，请稍后再试` |

### 2.3.2 作品（`web/server/api/packs/**`，17 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/packs` | 匿名 | query `sort=new\|hot`、`q`（截断 100）、`page=1`、`size=12`（1–50） | `{records[toPackPublic],total,page,size}`；每作品只出**最新 ACTIVE 版**；`hot` 排序 = `favorites_count/(age_days+7)` | 无 |
| `GET /api/packs/{packageKey}` | 匿名（带登录态则返回 `favorited`） | — | `{packageKey,latest,versions[],derived[],author{…,followersCount}\|null,favorited}` | 404 `未找到该作品` / `该作品已下架` |
| `GET /api/packs/{packageKey}/{version}` | 匿名 | — | `{packageKey,version,pack,author\|null}` | 404 `该作品已下架`（整包 REMOVED）/ `未找到该版本` |
| `PUT /api/packs/{packageKey}/{version}` | 需登录 + **作者本人** | `{description?,source?,downloadUrl?}`（`undefined`=不改，`null`/`""`=清空） | `{packageKey,version,updatedAt}`，`message="已更新"` | 400 `字段类型错误` / `字段长度超出限制（{max}）` / `托管（HOSTED）作品的下载方式由中心管理，不能填外链` / `downloadUrl 需为合法的 http(s) 链接` / `请求体为空` / `没有可更新的字段`；403 `只能编辑自己的作品`；404 `未找到该登记` |
| `DELETE /api/packs/{packageKey}` | 需登录 + 作者本人 | — | `{packageKey,filesRemoved}`，`message="已下架"` | 403 `只能下架自己的作品`；404 `未找到该作品` / `该作品已下架` |
| `POST /api/packs/{packageKey}/restore` | 需登录 + 作者本人 | — | `{packageKey,hostedNeedsUpload}`，message `已恢复上架` / `已恢复上架，请重新上传托管文件` | 403 `只能恢复自己的作品`；404 `未找到该作品`；409 `该作品已在架上` |
| `POST /api/packs/upload` | 需登录（10/分） | multipart：`file*`（`.json`/`.tiku`，≤200MB）＋可选 `storageKind`（缺省 `EXTERNAL`）/`downloadUrl`/`title`/`description`/`source` | `{packageKey,version,storageKind,fileSha256}`，message `发布成功` / `发布成功，文件已托管` / `已重新上架…` | 400 `缺少文件（multipart/form-data：file 字段）` / `缺少文件字段 file` / `文件为空` / `文件超过 200MB 上限` / `题库文件解析失败` / `题库文件 schemaVersion 需为 1 或 2` / `题库文件 packageKey 不合法` / `题库文件 version 不合法` / `题库文件缺少标题` / `parentKey 不合法` / `题库文件中没有题目，无法发布` / `外链方式需要提供题库文件下载链接（http/https）` / `downloadUrl 需为合法的 http(s) 链接`；409 `该 packageKey+version 已被他人登记过（REMOVED）` / `该 packageKey+version 已登记过；如需更新请升版本号`；429 `操作太频繁，请稍后再试` |
| `POST /api/packs` | 需登录（10/分） | `{packageKey*,version*,title*,description,source,parentKey,checksum,fileSizeBytes*,questionsCount*,materialsCount*,storageKind,fileSha256,downloadUrl,manifestVersion}` | `{packageKey,version,storageKind}`（重新上架时含 `reactivated:true`），message `发布成功` / `登记成功，请上传题库文件` / `已重新上架…` | 400 `请求体为空` / `packageKey 不合法（1–100 位字母数字._-）` / `version 不合法` / `缺少标题 title` / `字段类型错误` / `字段长度超出限制（{max}）` / `checksum 需为 64 位十六进制（SHA-256）` / `仅支持 manifestVersion = 1` / `parentKey 不合法` / `fileSizeBytes 需为非负整数` / `questionsCount 需为非负整数` / `materialsCount 需为非负整数` / `fileSha256 需为 64 位十六进制（文件字节 SHA-256）` / `downloadUrl 需为合法的 http(s) 链接` / `缺少下载链接 downloadUrl（EXTERNAL 登记需要作者外链）` / `HOSTED 登记无需 downloadUrl（文件直传中心，登记后调用上传接口）`；409 同上；429 同上 |
| `GET /api/packs/{packageKey}/{version}/file` | 匿名 | — | **文件流**：`Content-Type: application/octet-stream`、`Content-Disposition` 含 ASCII 名 + `filename*`（RFC 5987 中文名）、`X-Checksum-File: {file_sha256}` | 404 `未找到该版本` / `该版本非托管文件` / `文件尚未上传` / `托管文件缺失` |
| `PUT /api/packs/{packageKey}/{version}/file` | 需登录 + 作者本人 | multipart `file*` | `{packageKey,version,fileSha256,sizeBytes}`，`message="文件已托管"` | 400 `缺少文件字段 file（multipart/form-data）` / `文件为空` / `文件超过 200MB 上限` / `文件大小与登记不符（登记 {n} 字节，实际 {m} 字节）` / `该登记不是托管（HOSTED）方式` / `题库文件解析失败` / `题库文件 schemaVersion 需为 1 或 2` / `文件内 packageKey 与登记不一致` / `文件内 version 与登记不一致`；403 `只能为自己的作品上传文件`；404 `未找到该登记`；409 `该版本已上传过文件；如需替换请先下架重新登记` / `文件字节指纹（SHA-256）与登记不符，文件可能被修改——请使用登记清单对应的原文件` |
| `GET /api/packs/{packageKey}/comments` | 匿名（登录附 `likedByMe`） | — | `{comments:[{id,userId,authorName,content,likesCount,likedByMe,createdAt}]}`（最多 200 条，新→旧） | 无 |
| `POST /api/packs/{packageKey}/comments` | 需登录（20/分） | `{content*}` | `{packageKey}`，`message="评论成功"` | 400 `评论内容不能为空` / `评论最长 500 字`；404 `该作品已下架` / `未找到该作品`；429 |
| `DELETE /api/packs/{packageKey}/comments/{id}` | 需登录（**本人或管理员**） | — | `{id}`，`message="评论已删除"` | 400 `无效的评论 id`；403 `只能删除自己的评论`；404 `未找到该评论` |
| `POST /api/packs/{packageKey}/comments/{id}/like` | 需登录 | `{like?}`（缺省 = 切换；与当前状态相同时直接返回当前值，不切换） | `{id,liked,count}` | 400 `无效的评论 id` / `like 需为布尔值`；404 `未找到该评论` |
| `POST /api/packs/{packageKey}/download-clicks` | **匿名**（IP 限流 20/分/作品） | `{version?}`（缺省最新 ACTIVE 版） | `{packageKey,version,storageKind,downloadUrl}`；HOSTED 返回站内文件端点 URL，EXTERNAL 返回作者外链；**每次调用即 +1 下载数** | 404 `该作品已下架` / `未找到该作品` / `未找到该版本` / `该版本文件尚未上传` / `该版本暂无下载链接`；429 `操作太频繁，请稍后再试` |
| `GET /api/packs/{packageKey}/favorite` | 需登录 | — | `{packageKey,favorited,count}` | 404 `未找到该作品`；401 `请先登录` |
| `POST /api/packs/{packageKey}/favorite` | 需登录（60/分） | `{favorite?}`（缺省 = 切换） | `{packageKey,favorited,count}` | 400 `favorite 需为布尔值`；404 `该作品已下架` / `未找到该作品`；429 |

### 2.3.3 作者（`web/server/api/authors/**`，2 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/authors/{id}` | 匿名（登录附 `isFollowing`/`isSelf`） | query `page=1`、`size=12`（1–50） | `{author,records[],total,page,size,followersCount,followingCount,isFollowing,isSelf}` | 400 `无效的作者`；404 `未找到该作者` |
| `POST /api/authors/{id}/follow` | 需登录（60/分） | `{follow?}`（缺省 = 切换） | `{authorId,following,followersCount}` | 400 `无效的作者 id` / `不能关注自己` / `follow 需为布尔值`；404 `未找到该作者`；429 |

### 2.3.4 我的（`web/server/api/me/**`，6 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `GET /api/me/packs` | 需登录 | query `page=1`、`size=20`（1–50） | `{records[toPackPublic + versionCount],total,page,size}`（**含已下架**，每作品最新一行 + 版本数） | 401 `请先登录` |
| `GET /api/me/favorites` | 需登录 | query `page=1`、`size=20` | `{records[toPackPublic + favoritedAt],total,page,size}`（含 REMOVED，标注状态） | 401 |
| `PUT /api/me/profile` | 需登录 | `{nickname?,bio?}`（空串/null = 清空） | `{user}`，`message="资料已更新"` | 400 `请求体为空` / `昵称最长 20 字` / `简介最长 300 字` / `没有可更新的字段` |
| `GET /api/me/password` | 需登录 | — | `{hasPassword,hasGithub,email,emailVerified}`（**不返回任何密码材料**） | 401 |
| `PUT /api/me/password` | 需登录（10/分/用户 + 20/分/IP） | `{password*,currentPassword?}` | `{hasPassword:true}`，message `密码已更新` / `密码已设置` | 400 `密码至少 6 位` / `密码过长` / `请输入当前密码` / `新密码不能与当前密码相同`；401 `当前密码不正确`；429 `尝试过于频繁，请稍后再试` |
| `POST /api/me/email` | 需登录（3/10 分钟） | `{email*,turnstileToken?}` | `{sent:true}`，`message="验证邮件已发送，请到邮箱点击链接完成绑定"` | 400 `请输入有效的邮箱地址` / `人机验证未通过，请刷新页面后重试`；409 `该邮箱已被其他账号使用`；429 `操作太频繁，请稍后再试`；502 `验证邮件发送失败，请稍后重试`；503 `邮件服务尚未配置，请联系管理员` |

### 2.3.5 举报（`web/server/api/reports.post.ts`，1 个端点）

| 方法 + 路径 | 鉴权 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- | --- |
| `POST /api/reports` | **匿名**（IP 限流 10/分） | `{packageKey*,type*,note?}`；`type` ∈ `LINK_DOWN`/`COPYRIGHT`/`OTHER` | `{packageKey,type}`，`message="已收到，感谢反馈"` | 400 `packageKey 不合法` / `type 需为 LINK_DOWN / COPYRIGHT / OTHER` / `说明最长 1000 字`；429 `操作太频繁，请稍后再试` |

### 2.3.6 管理后台（`web/server/api/admin/**`，6 个端点，全部要求 `ADMIN_USERNAMES` 命中）

| 方法 + 路径 | 请求 | 响应 `data` | 常见错误 |
| --- | --- | --- | --- |
| `GET /api/admin/reports` | query `status=OPEN\|RESOLVED\|DISMISSED\|ALL`（默认 OPEN）、`page=1`、`size=30`（1–100） | `{records[ReportPublic{id,packageKey,type,note,status,handleNote,handledAt,handledBy,createdAt} + pack + author{id,username,notifiable,emailMasked}],total,page,size}`；排序：OPEN 优先，再按时间倒序 | 403 `需要管理员权限` |
| `POST /api/admin/reports/{id}/handle` | `{status*:RESOLVED\|DISMISSED, removePack?, note?(≤500), notifyAuthor?（默认 true）}` | `{id,status,removed,filesRemoved?,packMissing?,alreadyRemoved?,notified,reason?,notifyError?,handleNote}` + message（如「已处置并下架作品（清理 1 个托管文件），已邮件通知作者」） | 400 `无效的举报 id` / `status 需为 RESOLVED 或 DISMISSED` / `removePack 需为布尔值` / `notifyAuthor 需为布尔值` / `note 需为字符串` / `处理备注最多 500 字` / `不成立的举报不能下架作品`；403；404 `未找到该举报`；500 `下架作品失败：{msg}`（举报保持待处理，可重试） |
| `GET /api/admin/users` | query `q`、`page=1`、`size=20`（1–100） | `{users[{id,username,nickname,createdAt,banned,packsCount,commentsCount,favoritesCount}],total,page,size}`（id 倒序） | 403 |
| `POST /api/admin/users/{id}/ban` | `{banned*}` | `{id,banned}`，message `已封禁该账号` / `已解除封禁` | 400 `无效的用户 id` / `不能对自己的账号执行封禁操作` / `banned 需为布尔值`；403；404 `未找到该用户` |
| `POST /api/admin/users/{id}/password` | `{password*}`（6–128） | `{id}`，`message="密码已重置，该用户的登录状态已失效"`（同事务清空该用户全部会话） | 400 `无效的用户 id` / `密码至少 6 位` / `密码过长`；403；404 `未找到该用户` |
| `POST /api/admin/packs/{packageKey}/remove` | — | `{packageKey,filesRemoved}`，`message="已强制下架"` | 403；404 `未找到该作品`；409 `该作品已下架` |

> 举报处置的 `reason` 取值与中文说明（`handle.post.ts` `REASON_TEXT`）：`pack-missing`「作品登记不存在，无可通知的下架」、`already-removed`「作品此前已下架，未重复通知」、`mail-not-configured`「邮件服务未配置」、`no-author`「作品未关联作者账号」、`no-email`「作者未绑定邮箱」、`email-unverified`「作者邮箱未验证」、`send-failed`「邮件发送失败」。

## 2.4 服务端能力的缺口（移动端/第三方需要的、当前不存在的）

| 缺口 | 依据 |
| --- | --- |
| 移动端 OAuth 回调白名单 | `github.ts` 的 `DESKTOP_CALLBACK_RE` **只允许** `http://127.0.0.1:<port>/api/center/auth/github/callback`（`docs/design-mobile.md` 第 156 行同结论） |
| 分片/断点续传上传 | 只有整包 multipart（≤200MB，上传端点 `MAX_BYTES`） |
| 推送通道 | 无（`docs/design-mobile.md` 第 162 行） |
| 客户端版本握手 | 无 |
| 第三方 OAuth（GitHub 以外）/ 短信登录 | 无 |
| 服务端「题库全文检索」 | 只有 `title/description/author_name` 的 `LIKE` 搜索（`packRepo.list`） |

---

# 第三部分：桌面端代理 ↔ 广场端点对应关系（`/api/center/**`）

| 桌面本地端点 | 广场端点 | 方向/说明 |
| --- | --- | --- |
| `GET /api/center/packs` | `GET /api/packs` | 只读透传（默认 `size=12`，非队列默认的 12 与广场默认一致） |
| `GET /api/center/packs/{packageKey}` | `GET /api/packs/{packageKey}` | 只读透传 |
| `GET /api/center/packs/{packageKey}/comments` | `GET /api/packs/{packageKey}/comments` | 只读透传（桌面端**没有**发评论的本地端点？有：`POST /api/center/packs/{k}/comments`） |
| `GET /api/center/authors/{id}` | `GET /api/authors/{id}` | 只读透传 |
| `POST /api/center/import` | `GET /api/packs/{packageKey}/{version}/file` | 拉取文件字节 → 本地导入（按 PK 魔数判 zip/JSON），**字节不经过浏览器** |
| `POST /api/center/import-external` | 任意 `http(s)` 直链 | 同上（网盘网页链接会失败，前端回退浏览器下载） |
| `POST /api/center/packs/{k}/favorite` | `POST /api/packs/{k}/favorite` | 写转发（带 Bearer） |
| `POST /api/center/packs/{k}/comments` | `POST /api/packs/{k}/comments` | 写转发 |
| `DELETE /api/center/packs/{k}/comments/{id}` | `DELETE /api/packs/{k}/comments/{id}` | 写转发 |
| `POST /api/center/packs/{k}/comments/{id}/like` | `POST /api/packs/{k}/comments/{id}/like` | 写转发 |
| `POST /api/center/authors/{id}/follow` | `POST /api/authors/{id}/follow` | 写转发 |
| `POST /api/center/publish/inspect` | —（纯本地体检） | 用 `ContentPackageInspector`（与官网 `package-meta.ts`/`upload.post.ts` 同口径） |
| `POST /api/center/publish` | `POST /api/packs/upload` | 手写 multipart 流式转发（`setFixedLengthStreamingMode`，200MB 不进堆）；**先本地体检，不合法一字节不出网** |
| `POST /api/center/publish-from-path` | `POST /api/packs/upload` | 从本地磁盘路径直读转发（不经前端），成功后联动标记导出记录 |
| `GET /api/center/me/packs` | `GET /api/me/packs` | 透传（要求本地已登录） |
| `DELETE /api/center/packs/{packageKey}` | `DELETE /api/packs/{packageKey}` | 透传（下架整包） |
| `PUT /api/center/packs/{k}/{v}` | `PUT /api/packs/{k}/{v}` | 透传（元数据） |
| `PUT /api/center/packs/{k}/{v}/file` | `PUT /api/packs/{k}/{v}/file` | 补传托管文件（先本地体检 + 身份核对） |
| `POST /api/center/auth/login` | `POST /api/auth/login`（带 `X-Desktop: 1`） | 登录并保存 token |
| `POST /api/center/auth/register` | `POST /api/auth/register`（带 `X-Desktop: 1`） | 注册并保存 token |
| `POST /api/center/auth/logout` | `POST /api/auth/logout` | 尽力通知 + 清本地 |
| `GET /api/center/auth/me` | `GET /api/auth/me` | 透传；官网无会话则清本地 token |
| `GET /api/center/auth/status` | — | 纯本地（读 `center-auth.json`） |
| `POST /api/center/auth/github/start` | `GET /api/auth/github/start?desktop=1&callback=…&state=…` | 生成一次性 state（5 分钟）+ 返回授权地址 |
| `GET /api/center/auth/github/callback` | `POST /api/auth/desktop-exchange` | 校验 state（单次）→ 用 ticket 换 token → 存本地 → 渲染 HTML 结果页 |

**为什么经本地代理**（`CenterAuthController` 类注释）：token 由本地后端保存、前端不接触；服务器到服务器转发无跨域问题；内容包字节不落浏览器内存。桌面端**未登录也能浏览广场**（只读端点匿名透传）。

---

# 第四部分：错误模型汇总（两端对照）

| 维度 | 本地后端 | 广场服务端 |
| --- | --- | --- |
| 成功体 | `{code:200,data,message:"ok"}` | `{code:200,data,message:"<中文文案>"}` |
| 错误体 | `{code:<status>,data:null,message:"<中文文案>"}`（HTTP 状态与 code 一致） | h3 错误体：`statusCode`/`statusMessage` + `data:{code,message}`（**401 例外：无 `data`**） |
| 404 | `NoSuchElementException` → 404 `题库不存在`/`题目不存在：{id}`/`图片不存在：{name}`；未知路径 → `资源不存在：{path}` | `apiError(404,…)`：`未找到该作品`/`该作品已下架`/`未找到该版本`/`未找到该评论`/`未找到该作者`/`未找到该用户`/`未找到该登记`/`未找到该举报` |
| 400 | 参数/校验错误（`IllegalArgumentException`、`@Valid` 失败、请求体畸形、类型不匹配、缺参数、超限） | `apiError(400,…)`：字段校验与业务前置条件（见 2.3） |
| 401 | **不存在**（本地无鉴权） | `请先登录`（`requireUser`）、`未登录`（logout）、`当前密码不正确`（修改密码） |
| 403 | 不存在 | `需要管理员权限`、`账号已被封禁`（登录/换票）、`只能编辑/下架/恢复自己的作品`、`只能删除自己的评论`、`只能为自己的作品上传文件` |
| 405 | `请求方法不支持：{method}` | Nuxt/h3 默认（**待确认**文案） |
| 409 | 无显式使用（唯一键冲突会退化为 500 `内部服务器错误`） | `该用户名已被注册`、`该 packageKey+version 已登记过…`、`该邮箱已被其他账号使用`、`该版本已上传过文件…`、`该作品已在架上` |
| 429 | **不存在**（本地无限流） | 见 2.2.6 限流表 |
| 500 | `IllegalStateException` / 未知异常（`内部服务器错误`）/ IO 失败 | `下架作品失败：{msg}`（举报处置）；其余未捕获异常由 Nuxt 兜底 |
| 502 / 503 | 不存在 | `验证邮件发送失败…`(502)、`邮件服务尚未配置，请联系管理员`(503)、`未配置 GitHub 登录`(503) |
| 204 | IO 中断（SSE/异步）+ 异步超时 | 不使用 |

---

# 第五部分：移动端（Android）需要注意

## 5.1 可直连广场的接口（无需本地后端）

- **全部** `web/server/api/**` 端点都是纯 HTTP/JSON，移动端可直接调用（`docs/design-mobile.md` 第 155 行结论：移动端直连 `https://pickq.cn`，不经本地后端代理）。
- 鉴权用 **Bearer**（复用桌面流程）：`POST /api/auth/login` / `register` 带 `X-Desktop: 1` 拿到 `token`，或走 GitHub 一次性 ticket + `POST /api/auth/desktop-exchange`。**cookie 会话（`pickq_session`）不适合 App**（`design-mobile.md` 第 43 行同结论）。
- 只需要读的接口：`GET /api/packs`、`GET /api/packs/{k}`、`GET /api/packs/{k}/{v}`、`GET /api/packs/{k}/comments`、`GET /api/authors/{id}`、`POST /api/packs/{k}/download-clicks`、`GET /api/packs/{k}/{v}/file`、`GET /api/auth/me`。
- 需要登录的写接口：收藏、评论、点赞、关注、`/api/me/**`、`/api/packs/upload`、`PUT/DELETE /api/packs/**`、`/api/reports`（举报可匿名）。

## 5.2 必须依赖服务端能力（当前缺失，开工前需对齐）

| 需求 | 缺什么 | 依据 |
| --- | --- | --- |
| App 登录回环 | OAuth 回调白名单只允许 `http://127.0.0.1:{port}/api/center/auth/github/callback`，**没有 deep link（如 `pickq://auth/callback`）**；需要服务端新增白名单规则 | `web/server/utils/github.ts` `DESKTOP_CALLBACK_RE`；`docs/design-mobile.md` 第 156、204 行 |
| 弱网大包上传 | 只有整包 multipart（≤200MB），**无分片/断点续传**；桌面端限制来自本地（200MB）与官网（200MB） | `upload.post.ts` `MAX_BYTES`；`CenterPublishController` `MAX_FILE_BYTES` |
| 强制升级提示 | 无版本握手接口 | `docs/design-mobile.md` 第 161 行 |
| 复习提醒推送 | 无服务端推送通道，先用本地通知（WorkManager） | `docs/design-mobile.md` 第 162 行 |
| 广场全文检索 | 只有标题/描述/作者名 `LIKE` | `packRepo.list` |

## 5.3 移动端体验上要留意的行为

| 行为 | 事实 | 移动端建议 |
| --- | --- | --- |
| 人机验证 | 注册 / 忘记密码 / 绑定邮箱：服务端配置 `TURNSTILE_SECRET` 时**必须**通过 Cloudflare Turnstile（未配置则放行） | App 需内置 Turnstile 或 WebView 承载；不要假设「不需要验证」 |
| 登录限流 | IP 20/分 + 账号 10/分，超限 429 `尝试过于频繁，请稍后再试` | 弱网重试要退避；多设备同账号共用同一 IP 时容易触发 |
| 注册限流 | IP 5/10 分钟 | — |
| 忘记密码 | IP 8/10 分钟 + 账号 5/10 分钟；**返回统一文案**（防枚举），无法据此判断账号是否存在 | UI 不要承诺「已发送到你的邮箱」 |
| 下载计数 | `POST /api/packs/{k}/download-clicks` **每次调用都 +1**（IP 20/分/作品限流） | 只在真正开始下载时调用一次，避免预取/重试放大计数 |
| 下载计数与真实下载分离 | `file.get.ts` 本身不计数，靠前端先调 `download-clicks` | 移动端若只下载 HOSTED 文件而不调计数接口，数据会偏少（是否可接受需产品确认） |
| 上传限流 | `pub:{userId}` 10/分 | 发布失败重试要退避 |
| 评论/收藏/关注限流 | 20/分、60/分、60/分 | 批量操作需节流 |
| 封禁 | `banned=1` 后所有会话立即按未登录处理（401），登录返回 403；GitHub 回调 `error=banned` | App 遇到 403 `账号已被封禁` 应清本地 token 并给明确提示 |
| 会话滑动续期 | 每次带 token 请求都会把 `expires_at` 续到 30 天后 | 长期不用会自然失效，需处理 401 → 重新登录 |
| Token 生命周期 | 重置密码（自助或管理员）会**清空全部会话** | App 需处理「token 突然失效」 |
| 错误体形态不一致 | 401 来自 `requireUser`（无 `data.code`），其余错误来自 `apiError`（有 `data.code`） | 统一按 `statusCode` + `statusMessage` 判定，`data.message` 作为兜底 |
| 直连需注意 CORS/来源 | 广场端未配置 CORS（服务端到服务端代理无此问题）；App 用原生 HTTP 不受同源限制 | 无需 CORS；但不要把广场 API 暴露给 WebView 里的第三方页面 |

## 5.4 本地后端接口在移动端的处置

| 本地端点组 | 移动端 |
| --- | --- |
| `/api/banks/**`、`/api/questions/**`、`/api/study-records/**`、`/api/sessions/**`、`/api/stats/**`、`/api/home/**` | **不复用**：逻辑照搬到 Kotlin（Room + Repository），规则口径见 `features.md` 与本文各端点的错误分支 |
| `/api/exports/**`（本地导出目录 + 导出记录 + 发布标记） | 不复用；改为 SAF/分享导出后直接调广场 `POST /api/packs/upload` |
| `/api/backup/**` | 不复用（依赖整目录替换 + 重启）；移动端改为「导出/导入备份文件」 |
| `/api/ai/**`、`/api/ai-import/**` | 不可直连（BYOK Key 存本地、任务依赖本地文件系统）；移动端需自建 AI 调用与任务表（对应 `ai_import_job`） |
| `/api/center/**` | 不复用（它就是代理）；移动端直连对应广场端点（见 2.4 对应表） |

---

## 6. 待确认问题

1. **广场错误体形态**：`apiError` 把 `{code,message}` 放在 `error.data`；桌面端两种提取逻辑（`message`/`data.message`/`statusMessage`）说明线上形态可能有差异——生产环境 Nuxt 是否统一回传 `data`？**待确认**。
2. **405 文案**：广场侧未自定义，由 Nuxt/h3 决定；**待确认**。
3. **`GET /api/ai-import/jobs/{id}/images/{num}` 的 Content-Type 硬编码为 `image/png`**，即使实际是 jpg/webp（`ImageStorageService.detectExtension` 支持多格式）——前端可能依赖浏览器嗅探；**待确认**是否有意。
4. **`DELETE /api/exports/{id}` 的 `缺少导出记录 ID` 分支在 HTTP 路径下不可达**（冗余代码）——**待确认**是否清理。
5. **`POST /api/center/publish/inspect` 强制要求广场登录**（`requireLogin`），但它本身是纯本地操作——产品上是否希望「未登录也能先体检」？**待确认**。
6. **`GET /api/center/packs` 的 `size` 缺省 12** 与广场 `GET /api/packs` 缺省一致，但广场上限 50、桌面未做上限校验（`size` 原样透传）；**待确认**是否需要本地 clamping。
7. **`POST /api/center/auth/me` 之外的只读代理不做登录校验**：本地任意程序可借 `/api/center/**` 使用已保存的广场 token（本机风险）——**待确认**是否接受（当前 `SECURITY.md` 已把「本地后端无鉴权」列为设计边界）。
8. **举报/评论等写接口在广场侧无「同一用户/IP 去重」**（仅限流），滥用治理策略**待确认**。
9. **`POST /api/center/publish` 的校验顺序**：当前先校验上传文件、再校验广场地址（基线相反）；两者同时非法时返回的 400 文案不同——**待确认**是否统一为「先地址后文件」。