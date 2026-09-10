# 数据模型（本地桌面库 H2 + 题库广场库 SQLite）

> 本文只写**代码里能验证的事实**，每条事实标注来源文件。无法从代码确认的写「待确认」。
> 口径与维护规则见 [`README.md`](README.md) 第 4 节；业务规则（判分/错题/复习）见 [`features.md`](features.md)；内容包文件格式见 [`package-format.md`](package-format.md)。
>
> 事实来源：
> - 本地库：`src/main/resources/db/migration/V1..V15*.sql`（Flyway，**已逐份读完**）、`src/main/java/com/tiku/model/**`、`src/main/java/com/tiku/mapper/**`、`src/main/java/com/tiku/service/*.java`
> - 广场库：`web/server/db/migrate.ts`（迁移 001–010，**已逐份读完**）、`web/server/db/repos.ts`
> - 注意：`web/` 在 `.gitignore` 第 57–58 行被忽略（官网源码不入库），因此广场侧事实取自本机工作树的实现；若线上有差异以线上为准（**待确认**）。

---

## 0. 总览

| 库 | 引擎 | 位置 | 迁移机制 | 表数（含迁移记录表） |
| --- | --- | --- | --- | --- |
| 本地桌面库 | H2（`MODE=MySQL` 兼容模式） | `${tiku.data-dir}/tiku`（`application.yml`：`jdbc:h2:file:${tiku.data-dir}/tiku;MODE=MySQL`，`sa` / 空密码） | Flyway：`src/main/resources/db/migration/V1..V15*.sql` | 9（+ `flyway_schema_history`，由 Flyway 自动建） |
| 广场库 | SQLite（better-sqlite3） | `$DB_PATH`，默认 `<运行目录>/data/plaza.db`（`web/server/db/migrate.ts` `defaultDbPath()`） | 自管迁移：`MIGRATIONS` 数组 + `schema_migrations` 表 | 10（9 业务表 + `schema_migrations`） |

本地库的模型原则（`V1__init_schema.sql` 头部注释）：**题库 = 内容包在本地的形态**，内容包身份（`package_key` / `version` / 作者 / 来源 / `checksum`）直接挂在 `question_bank` 上，没有独立的 `content_package` / `package_version` 表。

---

# 第一部分：本地桌面库（H2）

## 1.1 迁移清单（按 Flyway 版本顺序，均已读）

| 版本文件 | 做了什么 | 影响的表 |
| --- | --- | --- |
| `V1__init_schema.sql` | 建 `question_bank` / `question` / `study_record` / `practice_session` / `practice_session_question` / `review_state` | 6 张表 |
| `V2__create_ai_import_job_table.sql` | 建 AI 导入任务表 | `ai_import_job` |
| `V3__ai_import_multi_file_and_supplement.sql` | 加 `file_names`、`ai_supplement` | `ai_import_job` |
| `V4__ai_import_current_file_index.sql` | 加 `current_file_index` | `ai_import_job` |
| `V5__ai_import_confirmed.sql` | 加 `confirmed` | `ai_import_job` |
| `V6__ai_import_thinking.sql` | 加 `thinking` | `ai_import_job` |
| `V7__subjective_material.sql` | 建 `material`；`question` 加 `material_id` / `reference_answer`，`score` 改 `DECIMAL(6,1)`；`study_record` 加 `user_answer` / `self_grade` | `material`、`question`、`study_record` |
| `V8__study_record_is_correct_nullable.sql` | `study_record.is_correct` 允许 NULL（主观题不自动判题） | `study_record` |
| `V9__ai_import_warning_hint.sql` | 加 `warning_hint` | `ai_import_job` |
| `V10__ai_import_engine.sql` | 加 `engine`（默认 `AUTO`） | `ai_import_job` |
| `V11__study_record_answer_seconds.sql` | 加 `seconds`（每题用时，交卷时前端提交） | `study_record` |
| `V12__ai_import_process_path.sql` | 加 `process_path` | `ai_import_job` |
| `V13__study_record_self_score.sql` | 加 `self_score`（主观题自由给分） | `study_record` |
| `V14__export_records.sql` | 建导出记录表 | `export_records` |
| `V15__widen_contract_columns.sql` | 放宽列宽以对齐内容包契约（见下方说明），老数据不受影响 | `question_bank`、`question`、`study_record`、`export_records` |

> `V15` 的动机：发布侧按契约允许 `description` 2000 / `packageKey` 100 / `version` 40 / `questionKey` 100，
> 而本地列原本只有 500 / 64 / 20 / 64 —— 会出现「合法内容包能发布成功、下载后导入本地却因列超长落库失败」。
> 对齐后契约上限 ≤ 本地列宽，两端一致。改动内容包契约时请同时检查这一节。

**下面各表是「V1 + 全部 ALTER 累积后的最终结构」**，不再逐版本重复。

## 1.2 表结构

### 1.2.1 `question_bank` — 题库（= 内容包本地形态）

来源：`V1__init_schema.sql`；实体 `src/main/java/com/tiku/model/QuestionBank.java`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT | 题库主键 | 被 `question.bank_id`、`material.bank_id`、`study_record.bank_id`、`practice_session.bank_id`、`review_state.bank_id`、`ai_import_job.bank_id`、`export_records.bank_id` 逻辑引用 |
| `name` | VARCHAR(120) | NOT NULL | 题库名（= 内容包 `title`） | `ContentPackageService` 导入/导出时映射；V15 由 100 放宽到 120 |
| `description` | VARCHAR(2000) | 可空 | 描述 | 同上；V15 由 500 放宽到 2000（对齐契约） |
| `package_key` | VARCHAR(100) | 可空 | 内容包稳定身份；自建题库为 NULL | `ContentPackageService.importContentPackage` 按它判「全新/已导入/版本并存/分支」；V15 由 64 放宽到 100 |
| `version` | VARCHAR(40) | 可空 | 内容包版本（如 `1.0.0`）；自建题库为 NULL | 同上；V15 由 20 放宽到 40（登记接口限 40） |
| `schema_version` | INT | 可空 | 内容包格式版本（当前 1；v2 容器导出时容器内写 2） | `PackageContainer.SCHEMA_V2` |
| `checksum` | VARCHAR(64) | 可空 | 内容指纹（题目+材料+图片规范化 JSON 的 SHA-256） | `ContentPackageService.computeChecksum` |
| `author_id` | BIGINT | 可空 | 作者账号 ID（本地不校验登录） | 内容包 `authorId` |
| `author_name` | VARCHAR(100) | 可空 | 作者展示名 | `QuestionBankUpdateRequest` 可改（`QuestionBankService.updateQuestionBank`） |
| `source` | VARCHAR(500) | 可空 | 来源声明 | 同上；V15 由 255 放宽到 500（对齐契约） |
| `sources` | TEXT | 可空 | 混编来源数组（JSON 文本） | `ContentPackageService.serializeSources/parseSources` |
| `parent_key` | VARCHAR(100) | 可空 | 派生来源（分支导入时记录原 `package_key`） | `buildBankFromFile(..., parentKey, ...)`；V15 由 64 放宽到 100 |
| `created_at` | TIMESTAMP | NOT NULL，DEFAULT `CURRENT_TIMESTAMP` | 创建时间 | — |
| `updated_at` | TIMESTAMP | NOT NULL，DEFAULT `CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | 更新时间 | — |
| `review_enabled` | TINYINT | NOT NULL DEFAULT 0 | 是否启用复习计划（默认关，用户显式开） | `QuestionBankService.setReviewEnabled`；控制「今日待复习」队列与 `mode=REVIEW` 可用性（`PracticeSessionService.selectPool`） |

唯一约束：`CONSTRAINT uk_question_bank_package_key_version UNIQUE (package_key, version)`——**同一 `package_key` 允许不同版本并存，分支导入使用新 `package_key`**（V1 注释）。

> **注意**：`question_bank` **没有** `status` / `active` / `removed` 之类的状态字段；「ACTIVE / REMOVED」是**广场库 `packs.status`** 的取值（见 2.3）。本地题库只有物理删除。

### 1.2.2 `question` — 题目

来源：`V1__init_schema.sql` + `V7__subjective_material.sql`；实体 `src/main/java/com/tiku/model/Question.java`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT | 题目主键 | `study_record.question_id`、`review_state.question_id`、`practice_session_question.question_id`、`question.material_id` 之外的引用方 |
| `external_id` | VARCHAR(100) | NOT NULL | 题目业务键（= 内容包 `questionKey`），跨版本追踪同一道题 | `ContentPackageQuestion.questionKey`；新建题自动生成 `题型_UUID前8位大写`（`QuestionService.generateExternalId`）；V15 由 64 放宽到 100（契约 KEY_RE 允许 1–100） |
| `question_number` | INT | 可空 | 题号（列表/做题/跳转排序依据） | `ContentPackageService.importQuestionsToBank`：AI 导入缺题号时按「当前最大题号+1」递增兜底 |
| `question_type` | VARCHAR(20) | NOT NULL | `SINGLE` / `MULTIPLE` / `JUDGE` / `SUBJECTIVE` | 枚举 `model/enums/QuestionType.java`（`@EnumValue`） |
| `content` | TEXT | NOT NULL | 题干（含 `[图片:文件名]` 标记） | `ImageStorageService.IMAGE_REF` |
| `options` | TEXT | 可空 | 选项 JSON（`OptionItem(key,text)` 数组），由 `OptionItemTypeHandler` 转换 | 主观题为 NULL（`QuestionService.updateQuestion` 切题型时显式清空） |
| `answer_keys` | VARCHAR(255) | 可空 | 正确答案 key，逗号分隔（如 `B`、`ABC`） | 判题 `QuestionService.checkAnswer` |
| `answer_text` | VARCHAR(2000) | 可空 | 答案文字 | V15 由 500 放宽到 2000 |
| `analysis` | TEXT | 可空 | 解析（AI 生成可保存） | `QuestionService.saveAnalysis` |
| `category` | VARCHAR(100) | 可空 | 分类 | 会话/列表筛选 |
| `topic` | VARCHAR(100) | 可空 | 主题 | 同上 |
| `volume` | INT | NOT NULL DEFAULT 0 | 第几册 | `ContentPackageQuestion.volume` |
| `score` | DECIMAL(6,1) | NOT NULL | 分值（V7 从 INT 改为支持小数，主观题部分对 = score/2） | 实体为 `Double`；`SelfGradeResponse` |
| `source` | VARCHAR(255) | 可空 | 题目来源 | — |
| `bank_id` | BIGINT | 可空（无外键） | 所属题库 | `question_bank.id`（`QuestionService.createQuestion` 强制校验题库存在：防孤儿题） |
| `favorite` | TINYINT | NOT NULL DEFAULT 0 | 收藏（做对也想二刷） | `QuestionService.setFavorite` |
| `created_at` / `updated_at` | TIMESTAMP | NOT NULL，DEFAULT 同 `question_bank` | 时间戳 | — |
| `deleted` | TINYINT | NOT NULL DEFAULT 0 | **逻辑删除标记**（MyBatis-Plus `@TableLogic`） | 单题删除、删库时置 1；查询自动过滤 `deleted=0` |
| `material_id` | BIGINT | 可空（无外键） | 共享材料引用（资料分析组内题） | `material.id`；`ContentPackageService.importQuestionsToBank` 按 `materialKey` 映射，映射不到抛 400 |
| `reference_answer` | TEXT | 可空 | 主观题参考答案（文字 + 图片标记） | `QuestionUpdateRequest.referenceAnswer` |

唯一约束：`CONSTRAINT uk_question_bank_external_id_deleted UNIQUE (bank_id, external_id, deleted)`——`questionKey` 在「题库」内唯一，跨题库允许重复（V1 注释：从题库 A 导出的包导入为题库 B，两库并存时 `questionKey` 相同合法）。

### 1.2.3 `material` — 共享材料（资料分析大题干）

来源：`V7__subjective_material.sql`；实体 `model/Material.java`；`MaterialMapper` 为纯 `BaseMapper`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT | 材料主键 | `question.material_id` |
| `bank_id` | BIGINT | NOT NULL | 所属题库 | `question_bank.id`；`MaterialService` 校验同库 |
| `content` | TEXT | NOT NULL | 材料内容（文字 + `[图片:文件名]`） | 内容包 `ContentPackageMaterial.content` |
| `sort_order` | INT | NOT NULL DEFAULT 0 | 排序（导入时按数组顺序 0,1,2…） | `ContentPackageService.insertMaterials` |
| `created_at` / `updated_at` | TIMESTAMP | NOT NULL，DEFAULT 同上 | 时间戳 | — |

索引：`KEY idx_material_bank (bank_id)`。无唯一约束（同一题库可有任意多材料）。

### 1.2.4 `study_record` — 刷题记录（历史日志，不做逻辑删除）

来源：`V1` + `V7` + `V8` + `V11` + `V13`；实体 `model/StudyRecord.java`；服务 `StudyRecordService`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT | 记录主键 | 自评接口 `PUT /api/study-records/{id}/self-grade` 按它定位 |
| `bank_id` | BIGINT | NOT NULL | 所属题库（冗余：题目删除后记录仍可按题库归类） | `question_bank.id` |
| `question_id` | BIGINT | NOT NULL | 关联题目 | `question.id`（题目软删后记录保留） |
| `question_key` | VARCHAR(100) | NOT NULL | 题目业务键冗余（题目删除后历史记录仍可读） | 复制自 `question.external_id`；V15 由 64 放宽到 100（跟随 `external_id`） |
| `selected_keys` | VARCHAR(255) | 可空 | 用户提交的答案 key，逗号分隔 | 客观题 |
| `is_correct` | TINYINT | **可空**（V8） | 是否答对：客观题自动判题；主观题为 NULL，由 `self_grade` 决定；题目未配置答案时也为 NULL | `StudyRecordService.submitAnswer` |
| `user_answer` | TEXT | 可空（V7） | 主观题用户作答文本（文字 + 图片标记） | 主观题 |
| `self_grade` | VARCHAR(16) | 可空（V7） | 主观题自评档位：`CORRECT` / `PARTIAL` / `WRONG`；NULL = 未自评或客观题 | `StudyRecordService.selfGrade`（自由给分后为派生档） |
| `self_score` | DOUBLE | 可空（V13） | 主观题自评实得分（0~满分） | `earnedScore(score, selfGrade, selfScore)`：`selfScore` 非空优先 |
| `session_id` | BIGINT | 可空 | 所属刷题会话（单题直接提交为 NULL） | `practice_session.id`（无外键） |
| `seconds` | BIGINT | 可空（V11） | 该题累计用时（秒）；交卷时由前端统计提交，NULL 时服务端按作答时间差兜底 | `PracticeSessionService.computeSecondsByQuestion` |
| `answered_at` | TIMESTAMP | NOT NULL，DEFAULT `CURRENT_TIMESTAMP` | 作答时间 | 错题口径取「最近一次」的排序键 |

索引：`KEY idx_study_record_bank_time (bank_id, answered_at)`、`KEY idx_study_record_question (question_id)`。

### 1.2.5 `practice_session` — 刷题会话

来源：`V1`；实体 `model/PracticeSession.java`；服务 `PracticeSessionService`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT | 会话主键 | `practice_session_question.session_id`、`study_record.session_id` |
| `bank_id` | BIGINT | NOT NULL | 所属题库 | `question_bank.id` |
| `mode` | VARCHAR(20) | NOT NULL | `ALL` 全部随机（未做优先）/ `SEQUENCE` 按题号顺序 / `TOPIC` 按分类 / `REVIEW` 复习队列到期题 / `WRONG` 错题 / `FAVORITE` 收藏 | 白名单常量 `PracticeSessionService.MODES`；非法值抛 400 |
| `scope_topic` | VARCHAR(100) | 可空 | 多选主题以逗号拼接（展示性字段） | `PracticeSessionService.createSession` |
| `scope_category` | VARCHAR(100) | 可空 | 多选分类以逗号拼接 | 同上 |
| `question_count` | INT | NOT NULL | 实际抽到的题数 | 整材料组不拆分，可能略超请求数量 |
| `finished_at` | TIMESTAMP | 可空 | 交卷时间（NULL = 进行中；交卷后生成成绩报告） | `finishSession` |
| `created_at` | TIMESTAMP | NOT NULL，DEFAULT `CURRENT_TIMESTAMP` | 创建时间 | 总用时 = `finished_at - created_at` |

索引：`KEY idx_practice_session_bank (bank_id)`。**成绩不落库**，从 `study_record` 实时聚合（表注释、`buildFinishReport`）。

并发控制：`PracticeSessionMapper.selectByIdForUpdate` 用 `SELECT ... FOR UPDATE` 行锁串行化「提交作答 + 标记完成」，防重复交卷（`PracticeSessionMapper.java`）。

### 1.2.6 `practice_session_question` — 会话抽取的题目

来源：`V1`；`mapper/PracticeSessionQuestionMapper.java`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `session_id` | BIGINT | NOT NULL，**复合主键(1)** | 会话 | `practice_session.id` |
| `question_id` | BIGINT | NOT NULL | 题目 | `question.id`。提交作答/交卷时用 `countBySessionAndQuestion` 校验题目属于该会话（防游离记录挂到任意会话） |
| `sort` | INT | NOT NULL，**复合主键(2)** | 抽取顺序（0 起） | 回顾页 `ORDER BY psq.sort` |

**无外键**；删库时由 `PracticeSessionQuestionMapper.deleteByBankId` 显式清理。注意：`question_id` 上没有唯一约束（同一题在一次会话内理论上可重复出现，代码未阻止）。

### 1.2.7 `review_state` — 复习状态（每道题一条）

来源：`V1`；实体 `model/ReviewState.java`；`StudyRecordService.updateReviewState`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `question_id` | BIGINT | **PK**（`IdType.INPUT`） | 题目（一题一行） | `question.id` |
| `bank_id` | BIGINT | NOT NULL | 所属题库（便于按库筛队列） | `question_bank.id` |
| `level` | INT | NOT NULL DEFAULT 0 | 熟练等级 0–5（连续答对次数封顶 5） | `Math.min(level+1, 5)` |
| `interval_days` | INT | NOT NULL DEFAULT 1 | 当前复习间隔（天） | `Math.min(1 << level, 30)` |
| `due_at` | TIMESTAMP | NOT NULL | 下次复习时间（`<= now` 进待复习队列） | 答对：`now + interval` 天；答错：`now` |
| `suspended` | TINYINT | NOT NULL DEFAULT 0 | 用户标记「不再复习此题」，不进队列（可恢复） | `PUT /api/questions/{questionId}/review-suspend` |

**无索引定义**（V1 只建了主键）。复习算法常量（`updateReviewState`）：答对 `level+1`（封顶 5）、间隔 `min(2^level, 30)` 天；答错 `level=0`、间隔 1 天、`due_at=now`。

### 1.2.8 `ai_import_job` — AI 辅助文件导入任务

来源：`V2` + `V3` + `V4` + `V5` + `V6` + `V9` + `V10` + `V12`；实体 `model/AiImportJob.java`；DTO `dto/AiJobResponse.java`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT | 任务主键 | — |
| `bank_id` | BIGINT | 可空 | 目标题库：NULL = 新建题库；否则追加 | `question_bank.id` |
| `file_name` | VARCHAR(255) | NOT NULL | 主文件展示名 | 多文件时保留主文件 |
| `file_names` | VARCHAR(500) | 可空（V3） | 所有上传文件名（逗号分隔，第一个为主文件） | `AiImportService.createJob` |
| `file_type` | VARCHAR(20) | NOT NULL | `TXT` / `MD` / `DOCX` / `PDF` / `IMAGE` | 实体注释 |
| `status` | VARCHAR(20) | NOT NULL DEFAULT `PENDING` | 取值见 1.4.5 | `AiImportService` |
| `stage` | VARCHAR(30) | 可空 | `PARSING` / `AI_GENERATING` / `VALIDATING` / `DONE` | 同上 |
| `progress` | INT | NOT NULL DEFAULT 0 | 进度百分比 | 前端轮询/SSE |
| `result_json` | TEXT | 可空 | 解析出的题目数组（`ContentPackageQuestion` 列表 JSON） | `confirmImport` 复用 |
| `error` | VARCHAR(500) | 可空 | 失败原因 | — |
| `created_at` | TIMESTAMP | NOT NULL，DEFAULT `CURRENT_TIMESTAMP` | 创建时间 | — |
| `finished_at` | TIMESTAMP | 可空 | 结束时间 | — |
| `ai_supplement` | TINYINT | NOT NULL DEFAULT 1（V3） | 是否允许 AI 补充缺失答案/解析 | `AiImportController.createJob` |
| `current_file_index` | INT | NOT NULL DEFAULT 0（V4） | 当前解析文件序号（前端显示「解析 2/3」） | — |
| `confirmed` | TINYINT | NOT NULL DEFAULT 0（V5） | 已确认导入（`confirmImport` 成功后置 1；`recent` 列表不再展示；重复 confirm 幂等） | `V5` 注释 |
| `thinking` | BOOLEAN | 可空（V6） | 本次任务是否开启思考模式（true 覆盖全局；NULL 跟随全局） | `AiJobResponse.thinking` |
| `warning_hint` | VARCHAR(500) | 可空（V9） | 题数差异检测提示（非思考模式提醒换模式） | — |
| `engine` | VARCHAR(16) | 可空，DEFAULT `AUTO`（V10） | `AUTO` 自动检测 / `LOCAL` 本地解析 / `MINERU` 云端解析（对应前端三模式：快速 / 标准 / 深度） | `V10` 注释 |
| `process_path` | VARCHAR(32) | 可空（V12） | 实际处理路径摘要：直传视觉 / MinerU 结构化 / 视觉直读 / 文本分块 / 文本整理 | `AiJobResponse.processPath` |

**无索引定义**（V2 只建了主键）。任务文件落盘目录见 1.5。

### 1.2.9 `export_records` — 导出记录（本地发布中心「我的作品」）

来源：`V14__export_records.sql`；实体 `model/ExportRecord.java`；服务 `ExportRecordService` / `LocalExportService`。

| 字段 | 类型 | 约束 | 含义 | 关联/来源 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT | 记录主键 | — |
| `bank_id` | BIGINT | NOT NULL，**不建外键** | 来源题库（导出时快照；题库删除后本表仍可读） | `V14` 注释 |
| `bank_name` | VARCHAR(100) | NOT NULL | 导出时的题库名（快照） | `ExportRecord` 类注释：后续改名不回写 |
| `package_key` | VARCHAR(100) | 可空 | 导出时实际写入包内的 `package_key` | `ContentPackageService.TikuExport`；V15 由 64 放宽到 100 |
| `version` | VARCHAR(40) | 可空 | 导出时写入包内的 `version` | 同上；V15 由 20 放宽到 40 |
| `file_path` | VARCHAR(1024) | NOT NULL | 落盘**绝对路径** | `LocalExportService`：`{dir}/{题库名或packageKey}-{version}.tiku` |
| `file_name` | VARCHAR(255) | NOT NULL | 文件名（已清理 Windows 非法字符） | `LocalExportService.sanitizeFileName` |
| `size_bytes` | BIGINT | NOT NULL DEFAULT 0 | 文件字节数 | — |
| `published` | TINYINT | NOT NULL DEFAULT 0 | 是否已上传题库广场（0/1） | `markPublished` / 从本地路径发布时联动标记 |
| `published_version` | VARCHAR(20) | 可空 | 实际上传成功的版本 | `GET /api/exports/next-version` 增量建议依据 |
| `created_at` | TIMESTAMP | NOT NULL，DEFAULT `CURRENT_TIMESTAMP` | 导出时间 | 列表按它倒序 |

索引：`KEY idx_export_records_created (created_at)`、`KEY idx_export_records_bank (bank_id, published)`。

## 1.3 表之间的关系与关系示意

| 关系 | 基数 | 实现方式 | 级联/清理规则 |
| --- | --- | --- | --- |
| `question_bank` → `question` | 1—n | `question.bank_id`（无外键） | 删库：题目**物理删除**（`deletePhysicallyByBankId`，与 V1 表注释一致）；题目归属唯一，合并/选区复制都是新建行，不存在跨库共享 |
| `question_bank` → `material` | 1—n | `material.bank_id` | 删库：**物理删除**（`materialMapper.delete`） |
| `question_bank` → `study_record` | 1—n | `study_record.bank_id` | 删库：**物理删除**（`studyRecordMapper.delete`），返回受影响记录数提示用户 |
| `question_bank` → `practice_session` | 1—n | `practice_session.bank_id` | 删库：物理删除会话 + 先删 `practice_session_question` |
| `question_bank` → `review_state` | 1—n | `review_state.bank_id` | 删库：物理删除 |
| `question_bank` → `ai_import_job` | 1—n（可空指） | `ai_import_job.bank_id` | 删库**不清理**任务行（`ai_import_job` 不在 `deleteQuestionBank` 的清理列表内）→ 残留 `bank_id` 悬挂 |
| `question_bank` → `export_records` | 1—n | `export_records.bank_id`（**刻意不建外键**） | 删库后记录保留（磁盘文件仍在，便于清理） |
| `material` → `question` | 1—n | `question.material_id` | 组内题共读大题干；`ALL`/`SEQUENCE` 抽题时按材料整组不拆散（`PracticeSessionService.buildUnits`） |
| `question` → `study_record` | 1—n | `study_record.question_id` | 单题删除不删记录（`question_key` 冗余保证可读） |
| `question` → `review_state` | 1—1 | `review_state.question_id`（主键） | 删库时按 `bank_id` 清理；单题删除**不清理** `review_state`（查询时跳过已删题） |
| `practice_session` → `practice_session_question` | 1—n | `session_id` | 删库：先 `deleteByBankId` 再删会话 |
| `question` → `practice_session_question` | 1—n | `question_id` | 同上 |
| `practice_session` → `study_record` | 1—n | `study_record.session_id`（可空） | 交卷统一判分时由 `session_id` 关联聚合成绩 |

```
                       ┌────────────────────────┐
                       │     question_bank      │  （题库 = 内容包本地形态）
                       │ PK id                  │
                       │ UK(package_key,version)│
                       └───────┬────────────────┘
         ┌──────────┬──────────┼───────────┬──────────────┬───────────────┐
         │1—n       │1—n       │1—n        │1—n           │1—n(可空)      │1—n(无外键)
         ▼          ▼          ▼           ▼              ▼               ▼
   ┌──────────┐ ┌────────┐ ┌──────────┐ ┌───────────┐ ┌──────────────┐ ┌───────────────┐
   │ question │ │material│ │study_    │ │practice_  │ │ai_import_job │ │export_records │
   │ UK(bank, │ │        │ │record    │ │session    │ │              │ │ (删库后保留)  │
   │  ext,del)│ └───┬────┘ └──────────┘ └─────┬─────┘ └──────────────┘ └───────────────┘
   └────┬─────┘     │ n                      │1—n
        │ 1         │                        ▼
        │           └──(question.material_id)─┐  ┌─────────────────────────────┐
        │1—1                                   └─▶│ practice_session_question   │
        ▼                                          │ PK(session_id, sort)        │
   ┌──────────────┐                                └─────────────────────────────┘
   │ review_state │  PK question_id
   └──────────────┘
        ▲
        │ n（question 1—n study_record；study_record.session_id 可空 → practice_session）
        └──── question ──1—n──▶ study_record
```

要点速记（一行一条）：
- `question_bank 1—n question`；`question_bank 1—n material`；`question 1—n study_record`；`question 1—1 review_state`。
- `practice_session 1—n practice_session_question n—1 question`；`practice_session 1—n study_record`（可空指）。
- `material 1—n question`（`question.material_id`）。
- `ai_import_job n—1 question_bank`（可空）；`export_records n—1 question_bank`（逻辑关联，无外键，删库不清理）。

## 1.4 关键业务约束

### 1.4.1 唯一约束清单

| 表 | 唯一约束/主键 | 语义 |
| --- | --- | --- |
| `question_bank` | `UNIQUE (package_key, version)` | 同一内容包身份允许不同版本并存；分支导入用新 `package_key`（`V1`） |
| `question` | `UNIQUE (bank_id, external_id, deleted)` | `questionKey` 在题库内唯一，跨题库可重复；`deleted` 参与唯一键（V1 注释：题库物理删除故无需含 `deleted` 之外的处理） |
| `review_state` | PK `question_id` | 每题一行复习状态 |
| `practice_session_question` | PK `(session_id, sort)` | 会话内顺序唯一 |

### 1.4.2 删除语义（物理删除 vs 逻辑删除）

| 对象 | 语义 | 来源 |
| --- | --- | --- |
| 删除题库 | **物理删除题库行**；其下题目**逻辑删除**（`deleted=1`，MyBatis-Plus `@TableLogic` 自动转 UPDATE）；刷题记录 / 复习状态 / 会话 / 会话题目 / 材料**物理删除**（同一事务） | `QuestionBankService.deleteQuestionBank` |
| 删除单题 | 逻辑删除（`question.deleted=1`）；刷题记录保留 | `QuestionService.deleteQuestion` |
| 刷题记录 | **不做逻辑删除**（历史日志，删库时物理级联） | `V1` 注释、`StudyRecord` 类注释 |
| 导出记录 | 删除记录时可选同时删磁盘文件（`deleteFile`）；文件不存在也算成功；删除失败（占用/无权限）→ 500 且不删记录 | `ExportController`、`ExportRecordService.delete` |
| AI 导入任务 | 进行中：仅标记 `CANCELED`（记录保留）；终态（`SUCCESS`/`FAILED`/`CANCELED`）：**物理删除记录 + 清文件目录** | `AiImportService.deleteJob`（`AiImportController` 注释） |

> ✅ **隐患 1（已修复 2026-09-11）**：删库时题目曾是「逻辑删除」，`question_bank` 行却已物理删除——`question` 表残留 `deleted=1` 且 `bank_id` 指向已不存在题库的孤儿行。
> 修法（与 V1 表注释「题库为物理删除（删除即级联删题删记录）」一致）：
> - `QuestionBankService.deleteQuestionBank` 改用 `QuestionMapper.deletePhysicallyByBankId`（注解 SQL 绕开 `@TableLogic`）物理删除该库全部题目（含已有软删除行）；
> - `ContentPackageService.importQuestionsToBank` 插入前用 `selectActiveIdByBankAndExternalId` 判重，同题库同 `questionKey` 已存在则**跳过**（不再插入第二条 `deleted=0` 的同键行 → 不再撞 `uk_question_bank_external_id_deleted` 报 500），跳过的条数会写进 warn 日志。
>
> 说明：题目在库内是「每库各自持有行」，合并（`BankMergeService.merge`）与选区复制（`copySelection`）都是**新建行**，不存在跨题库共享同一 `question.id` 的情况，所以物理删除不会影响其它题库。

> ✅ **隐患 2（已修复 2026-09-11）**：删库后 `ai_import_job.bank_id` 曾是悬挂引用。
> 现在 `deleteQuestionBank` 会调用 `AiImportJobMapper.clearBankId(id)` 把 `bank_id` 置为 NULL
> （NULL 语义本就是「不指向某个已存在的题库」），**任务本身保留**，历史导入记录仍可查看。

### 1.4.3 状态/标记字段取值表

| 表.字段 | 取值 | 含义 | 来源 |
| --- | --- | --- | --- |
| `question_bank.review_enabled` | 0 / 1 | 复习计划开关。关闭 = 队列「暂停」：不展示不提醒，但内部调度仍由作答推进；重新开启后到期项自然回到队列（含积压） | `QuestionBankService.setReviewEnabled`、`StudyRecordService.listReviewDue` 注释 |
| `question.favorite` | 0 / 1 | 收藏 | `QuestionService.setFavorite` |
| `question.deleted` | 0 / 1 | 逻辑删除 | `@TableLogic` |
| `question.question_type` | `SINGLE` / `MULTIPLE` / `JUDGE` / `SUBJECTIVE` | 题型；主观题不自动判题 | `QuestionType` 枚举（带中文 label） |
| `study_record.is_correct` | `true` / `false` / **NULL** | NULL = 主观题未自评，或客观题「题目尚未配置答案」（不判对错、不进错题/复习） | `V8`、`StudyRecordService.submitAnswer` |
| `study_record.self_grade` | `CORRECT` / `PARTIAL` / `WRONG` / NULL | 自由给分后为派生档：满分=对、0=错、中间=部分对；**非满分一律按答错**参与错题本与复习 | `StudyRecordService.selfGrade` |
| `practice_session.mode` | `ALL` / `SEQUENCE` / `TOPIC` / `REVIEW` / `WRONG` / `FAVORITE` | 抽题模式白名单 | `PracticeSessionService.MODES` |
| `practice_session.finished_at` | 时间 / NULL | NULL = 进行中；非空 = 已交卷（前端展示 `IN_PROGRESS` / `COMPLETED`） | `getSessionDetail` |
| `review_state.suspended` | 0 / 1 | 暂停复习（不进队列，可恢复） | `setReviewSuspended` |
| `ai_import_job.status` | `PENDING` / `PROCESSING` / `SUCCESS` / `FAILED` / **`CANCELED`** | ⚠️ `V2` 注释只写了前四个；`CANCELED` 由 `AiImportService` 实际写入（`V2` 注释与实现不一致，见 1.6） | `AiImportService` 第 189/198/276/334/854 行 |
| `ai_import_job.stage` | `PARSING` / `AI_GENERATING` / `VALIDATING` / `DONE` | 阶段（`FAILED` 时也置 `DONE`） | `AiImportService` |
| `ai_import_job.engine` | `AUTO` / `LOCAL` / `MINERU` | 解析引擎选择 | `V10` |
| `ai_import_job.process_path` | 自由文本摘要（如「MinerU 结构化」） | 实际处理路径，仅展示 | `V12`、`AiJobResponse` |
| `export_records.published` | 0 / 1 | 是否已发布到广场 | `ExportRecordService.markPublished` |

### 1.4.4 题号（`question_number`）口径

- **数据库层没有唯一约束**（`question` 表无 `question_number` 唯一键），唯一性由写入路径约定：
  - 列表/导航/做题顺序统一按 `question_number ASC, id ASC`（`QuestionBankService.buildListWrapper`、`getBankPracticeQuestions`、`PracticeSessionService.selectPool` 的 `SEQUENCE` 分支）。
  - AI 导入/AI 确认导入时题目常无题号：`ContentPackageService.importQuestionsToBank` 从「当前题库最大题号 + 1」起递增兜底，避免 NULL 排在最前导致顺序错乱。
  - `V1` 注释明确：`questionKey`（`external_id`）在题库内唯一；**题号不参与唯一约束**。
- **待确认**：是否需要「题号在题库内唯一」的强约束（当前只有排序语义，用户手工录入可写入重复题号）。

### 1.4.5 判分与统计口径（由数据派生，非落库）

| 口径 | 规则 | 来源 |
| --- | --- | --- |
| 错题 | 每题「最近一次作答为错」才进错题集合；历史答错但最近答对则移出。主观题自评 `PARTIAL`/`WRONG` 算错；未自评不算错 | `StudyRecordService.computeWrongQuestionIds` / `isWrong`（错题本、`scope=wrong`、会话 `WRONG`、导出 `wrong` 共用同一函数） |
| 学习进度 | `已答（只统计仍存在的题）/ 总题数`；正确率 = 答对记录数 / 总记录数（答对 = 客观题判对或主观题 `CORRECT`） | `StudyRecordService.getBankProgress` |
| 单题应得分 | `self_score` 非空优先（夹在 0~满分）；否则按档映射：`CORRECT=score`、`PARTIAL=score/2`、其余 0 | `StudyRecordService.earnedScore` |
| 会话成绩 | 不落库，`study_record` 实时聚合：总题数/已答/答对/总分/满分/总用时/每题用时 | `PracticeSessionService.buildFinishReport` |
| 记录导入幂等 | 同库+同题+**秒级时间窗**+同 `selected_keys`/`is_correct`/`user_answer`/`self_grade` 视为重复并跳过（可空字段用 `IS NULL` 分支比较） | `StudyRecordService.importRecords` |
| 记录导入后复习重建 | 无 `review_state` 的题按其全部记录按时间序重放，重建复习调度 | 同上 |

## 1.5 数据目录布局（`TIKU_DATA_DIR`）

根目录：`application.yml` → `tiku.data-dir: ${TIKU_DATA_DIR:${user.home}/.tiku}`；启动时 `DataDirectoryConfig` 自动创建（不存在则建，并打日志）。

| 路径 | 内容 | 来源 |
| --- | --- | --- |
| `{data-dir}/tiku` | H2 文件库（JDBC URL `jdbc:h2:file:{data-dir}/tiku;MODE=MySQL`）；实际文件名为 `tiku.mv.db` | `application.yml`；文件名见 `docs/design-mobile.md`（代码中只出现 URL） |
| `{data-dir}/images/{bankId}/{yyMMdd}/{12位hex}.{ext}` | 题目图片（题干 / 参考答案 / 材料内以 `[图片:文件名]` 引用；`name` 形如 `260829/ab12cd34ef56.png`） | `ImageStorageService`（上限 10MB；扩展名按上传名或字节头判定 png/jpg/gif/webp/bmp） |
| `{data-dir}/imports/{jobId}/images/{N}.png` | AI 导入任务的临时图片（`N` 从 1 起，全局编号；confirm 时转正式存储到 `images/`） | `AiImportService`（第 170、283、503、897 行等） |
| `{data-dir}/ai-config.json` | AI 模型配置（BYOK：`baseUrl` / `apiKey` / `model` / `visionModel` / `thinking` / `mineruKey`）。**不入库、不进日志**；含 Key，勿外传 | `AiConfigService`、`AiSettings` |
| `{data-dir}/export-prefs.json` | 导出偏好：仅存 `lastDir`（本地发布中心「上次导出目录」） | `ExportPrefsService` |
| `{data-dir}/center-auth.json` | 广场桌面会话：`{token, username}`（登录 token 由本地后端保存，前端不接触） | `CenterAuthStore` |
| `{data-dir}/restore/staged/` | 一键恢复暂存区（备份 zip 解压到此，含 `database.sql` / `images/` / `ai-config.json` / 恢复说明）；恢复完成后整个 `restore/` 目录被删除 | `BackupService.prepareRestore`、`RestoreRunner` |
| 默认导出目录 | 用户「文档」下的 `拾题`（候选顺序 `Documents` → `文档` → `OneDrive/Documents`，都不存在则回退 `~/Documents/拾题`）；导出文件名 `{题库名或packageKey}-{version}.tiku` | `ExportPrefsService.defaultDir`、`LocalExportService.buildFileName` |

备份 zip（`GET /api/backup`，流式，文件名 `tiku-backup-yyyyMMdd-HHmmss.zip`）内容（`BackupService.writeBackupZip`）：

| 条目 | 说明 |
| --- | --- |
| `database.sql` | H2 `SCRIPT TO` 一致性快照（含建表与全部数据、含 Flyway 版本表，恢复后无需重跑迁移） |
| `images/` | 题目图片按原结构复制 |
| `ai-config.json` | AI 配置（**含 API Key**，zip 内《恢复说明.txt》提醒妥善保管） |
| `恢复说明.txt` | 恢复步骤（H2 `RunScript`、目录替换、换机指引） |

恢复流程（`BackupController` + `RestoreRunner`）：`POST /api/backup/restore-prepare`（multipart `file`）→ 校验并解压到 `restore/staged` → 桌面壳带 `--tiku.restore-stage=<staged>` 重启后端 → `RestoreRunner` 执行 `DROP ALL OBJECTS` + `RunScript` + 覆盖 `images/` 与 `ai-config.json`，打印 `TIKU_RESTORE_OK`。

> 一键恢复只覆盖 database / images / ai-config.json；**`export-prefs.json` 与 `center-auth.json` 不在备份与恢复范围内**（`BackupService` 仅复制 ai-config.json 与 images）。

## 1.6 本地库已发现的不一致/隐患（供核对）

| # | 现象 | 证据 |
| --- | --- | --- |
| 1 | `ai_import_job.status` 的注释取值缺 `CANCELED`，但实现会写入 `CANCELED`；`AiJobResponse.status` 注释同样只列四个值 | `V2` 注释 vs `AiImportService` 第 189/198/334/854 行；`AiJobResponse.java` |
| 2 | `PracticeSession.mode` 实体注释缺 `SEQUENCE`（SQL 注释与白名单都有） | `model/PracticeSession.java` 第 27 行 vs `V1` 第 85 行、`PracticeSessionService.MODES` |
| 3 | ~~删库时题目仅逻辑删除 → `question` 表残留孤儿行~~ **已修复**：改为物理删除 + 导入判重（见 1.4.2 隐患 1） | `QuestionMapper.deletePhysicallyByBankId`、`ContentPackageService.importQuestionsToBank` |
| 4 | 删库不清理 `ai_import_job`（悬挂 `bank_id`） | 同上（清理列表只有会话题目/会话/复习状态/题目/记录/材料） |
| 5 | `question.score` 是 `DECIMAL(6,1)`（1 位小数），而 `study_record.self_score` 是 `DOUBLE`（全精度）→ 主观题自由给分与满分口径不同精度，聚合总分可能出现 1 位小数以外的值 | `V7` vs `V13`；`earnedScore` |
| 6 | 单题删除不清理 `review_state`：已删题在 `review_state` 留行（查询时跳过），`GET /banks/{id}/review/summary` 的 `dueTotal` 仍把这些题计入计数（`selectCount` 不 join `question`） | `QuestionService.deleteQuestion`、`StudyRecordService.getReviewSummary` |
| 7 | `GlobalExceptionHandler` 构造注入了 `View error` 但从未使用（死依赖） | `controller/GlobalExceptionHandler.java` 第 23–27 行 |

## 1.7 本地库移动端（Room）迁移建议

| 桌面表/字段 | 移动端建议 | 理由 |
| --- | --- | --- |
| `question_bank` / `material` / `question` / `study_record` / `review_state` / `practice_session` / `practice_session_question` | **可直接照搬**（字段一一对应，`options`/`sources` 仍存 JSON 文本列，不要拆表） | 与 `docs/design-mobile.md` 第 7 节一致；`options` 的 JSON 表示是内容包契约的一部分（`package-format.md`） |
| `question.deleted` | 保留性软删除（Room 加 `deleted` 字段 + 所有查询过滤），或改为物理删除并依赖 `study_record.question_key` 冗余 | 桌面错题/统计依赖「已删题不计入」，行为要与桌面一致 |
| `review_state.question_id` 主键 | 照搬（`@PrimaryKey` 非自增） | 一题一行的语义必须保持 |
| `practice_session_question` | 照搬复合主键 `(session_id, sort)`；建议额外给 `(session_id, question_id)` 建索引 | 手机端回顾页查询性能；桌面对该组合无索引 |
| `ai_import_job` | 表保留（对应 `WorkManager` 任务 + 可恢复），但 `result_json` 建议**拆分**为 `ai_import_job_question` 子表，或至少改为文件存储 | 移动端内存受限，几 MB 的题目 JSON 常驻单列易 OOM |
| `ai_import_job.bank_id` 悬挂问题 | 移动端用外键 `ON DELETE SET NULL`/`CASCADE` 显式处理 | 桌面无外键，删库后悬挂 |
| `export_records` | **可裁剪**：只保留 `package_key` / `version` / `published` / `published_version` / `created_at`；`file_path` / `file_name` / `size_bytes` 是桌面「本地导出目录」概念 | 手机端无「导出到目录」，改用 SAF/分享（`docs/design-mobile.md` 第 4 节）；`GET /api/exports/**` 整组接口本地专用（见 `api.md` 第 1.4 节） |
| 图片文件布局 `images/{bankId}/{yyMMdd}/{name}` | 保留「按题库分目录 + 相对路径」思路，落到应用私有目录；**只存相对路径**，导出时重打包进 `.tiku` | `docs/design-mobile.md` 第 10 节风险 3 |
| `ai-config.json` / `export-prefs.json` / `center-auth.json` | 改为 DataStore / `EncryptedSharedPreferences`（token 与 AI Key 是敏感数据） | 桌面端存明文 JSON（本机可读，`CenterAuthStore` 注释已声明风险面） |
| 窗口/打印相关 | **桌面端无对应数据库字段**：窗口尺寸与位置由 Tauri 壳管理（`tauri/src-tauri/`）；「打印试卷」是前端页面 `frontend/src/views/PrintPaperView.vue`，走 `POST /api/banks/{id}/export`（`scope`/`category`/`topic` 过滤）+ `window.print()`，不落库 | 移动端 `PrintPaperView` 类需求改为导出 PDF/分享（`docs/design-mobile.md` 第 4 节「打印试卷 → 不做」） |
| 桌面端 localStorage 客户端偏好 | 移动端用 DataStore 或直接丢弃：`tiku:theme`、`tiku:lang`、`tiku:daily-goal`、`tiku:default-author`、`tiku:guide-bubble-dismissed`、`tiku:skip-update-version`、`tiku:ai-presets-cache`、`tiku:dock-size:*`、`tiku:dock-pos:*` | 均来自 `frontend/src/**`（theme.js / i18n / StatsView / BankDetailView / SettingsView / BankListView / App.vue / aiConfig.js / QuestionNavDock.vue），与数据库无关 |

不建议迁移的：`GET /api/exports/**`（导出记录 + 目录偏好）、`/api/backup/**`（整目录替换 + 重启恢复）、`/api/export-tiku`（桌面文件落盘语义）。

---

# 第二部分：广场库（SQLite）

## 2.1 位置、连接与迁移机制

| 项 | 事实 | 来源 |
| --- | --- | --- |
| 数据文件 | `process.env.DB_PATH ?? join(process.cwd(), 'data', 'plaza.db')`（dev 下为 `web/data/plaza.db`，已 gitignore） | `web/server/db/migrate.ts` `defaultDbPath()` |
| 驱动 | `better-sqlite3`（同步 API），单例连接（`repos.ts` `db()` 懒加载缓存） | `repos.ts` |
| PRAGMA | `journal_mode = WAL`、`foreign_keys = ON` | `openDb()` |
| 迁移 | 自管：`MIGRATIONS` 数组按序执行未应用的 SQL，逐条记录于 `schema_migrations(name PK, applied_at)`；每个迁移包在一个事务里 | `migrate()` |
| 时间口径 | 文本时间，默认 `datetime('now')`（UTC），格式 `YYYY-MM-DD HH:MM:SS` | `sqlUtc()` |

> ⚠️ 注意：虽然开了 `foreign_keys = ON`，**10 个迁移里没有任何一条 `REFERENCES` / 外键声明**——所有关系都是逻辑关联，靠应用层维护（详见 2.4）。

## 2.2 迁移清单（001–010，均已读）

| 名称 | 做了什么 |
| --- | --- |
| `001_init` | 建 `users` / `packs` / `favorites` / `comments` / `reports` / `sessions` 与索引 |
| `002_hosted_storage` | `packs` 加 `file_sha256`（**文件字节指纹**）、`storage_key`（对象键）+ 索引 |
| `003_report_handling` | `reports` 加 `status`（默认 `OPEN`）、`handled_at`、`handled_by` + 索引 |
| `004_comment_likes` | 建 `comment_likes`；`comments` 加 `likes_count` |
| `005_follows` | 建 `follows`（关注关系）+ 两个索引 |
| `006_checksum_nullable` | **重建 `packs`**：`checksum` 改为可空（直传路径算不出内容指纹，改用 `file_sha256` 作登记凭据） |
| `007_user_banned` | `users` 加 `banned`（默认 0） |
| `008_github_oauth` | `users` 加 `github_id` / `email` / `email_verified`；`github_id` 建**部分唯一索引**（`WHERE github_id IS NOT NULL`）+ 邮箱索引 |
| `009_auth_tokens` | 建 `auth_tokens`（邮箱验证 / 重置密码 / 桌面登录 ticket 的一次性令牌）+ 索引 |
| `010_report_handle_note` | `reports` 加 `handle_note`（管理员处置备注） |

## 2.3 表结构

### 2.3.1 `users` — 账号

来源：`001_init` + `007` + `008`；类型定义 `repos.ts` `UserRow`。

| 字段 | 类型 | 约束 | 含义 | 关联 |
| --- | --- | --- | --- | --- |
| `id` | INTEGER | PK AUTOINCREMENT | 用户主键 | 被 `packs.author_id`、`favorites.user_id`、`comments.user_id`、`comment_likes.user_id`、`follows.follower_id/following_id`、`sessions.user_id`、`auth_tokens.user_id`、`reports.handled_by` 逻辑引用 |
| `username` | TEXT | **NOT NULL UNIQUE** | 用户名（注册规则 2–24 位 `[\p{L}\p{N}_-]`） | `api/auth/register.post.ts` |
| `password_hash` | TEXT | NOT NULL | scrypt 哈希，格式 `salt:hash`；**空串 = 无本地密码**（GitHub 首次登录创建的账号） | `utils/auth.ts` `hashPassword`；`repos.NO_PASSWORD_HASH = ''` |
| `nickname` | TEXT | 可空 | 昵称（≤20 字） | `PUT /api/me/profile` |
| `bio` | TEXT | 可空 | 简介（≤300 字） | 同上（001 起已存在） |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 注册时间 | — |
| `banned` | INTEGER | NOT NULL DEFAULT 0（007） | 1 = 封禁；`getSessionUser` 对封禁用户返回 null（会话即时失效） | `utils/auth.ts`、`admin/users/[id]/ban.post.ts` |
| `github_id` | TEXT | 可空；**部分唯一索引** `idx_users_github` | GitHub 用户 id（存字符串）；NULL = 未绑定 | `008` 注释；`userRepo.findByGithubId` |
| `email` | TEXT | 可空；索引 `idx_users_email` | 邮箱（统一小写存储）；当前仅由 GitHub 已验证主邮箱回填或 `/api/me/email` 验证成功后写入 | `normalizeEmail`、`setEmail` |
| `email_verified` | INTEGER | NOT NULL DEFAULT 0 | 1 = 已验证；**未验证邮箱不能作为登录凭据，也不能被他人占用** | `api/auth/login.post.ts` 第 25 行、`verify-email.get.ts` |

### 2.3.2 `packs` — 内容包登记（一行 = 一个 `package_key` + `version`；作品 = 同 `package_key` 的全部版本）

来源：`001_init` + `002` + `006`（重建后的最终结构）；类型 `PackRow`；对外 JSON 见 `utils/api.ts` `toPackPublic`。

| 字段 | 类型 | 约束 | 含义 | 关联 |
| --- | --- | --- | --- | --- |
| `id` | INTEGER | PK AUTOINCREMENT | 登记行主键 | `favorites`/`comments` 按 `package_key` 关联（非 id） |
| `package_key` | TEXT | NOT NULL，索引 | 内容包稳定身份 | 自关联：`parent_key` → `package_key`（衍生作品，`listDerived`） |
| `version` | TEXT | NOT NULL | 版本号 | 与 `package_key` 组成 `UNIQUE` |
| `title` | TEXT | NOT NULL | 作品标题（= 内容包 title，可由上传覆盖） | `upload.post.ts` `overrideTitle` |
| `description` | TEXT | 可空 | 描述（≤2000） | `PUT /api/packs/{k}/{v}` |
| `author_id` | INTEGER | 可空，索引 | 作者（服务端赋值，不信任请求体） | `users.id`；`my/packs`、作者主页 |
| `author_name` | TEXT | 可空 | 作者展示名（`nickname \|\| username` 快照） | `packs/index.post.ts` |
| `source` | TEXT | 可空 | 来源声明（≤500） | — |
| `parent_key` | TEXT | 可空 | 派生来源（分支导出时记录原 `package_key`） | `listDerived` |
| `checksum` | TEXT | **可空**（006 起） | 内容指纹（规范化序列化 SHA-256）；Node 侧无法复刻桌面 Jackson 字段序，直传路径为 NULL | `006` 注释；`utils/api.ts` 对外返回 |
| `file_size_bytes` | INTEGER | 可空 | 文件字节数（`file.put.ts` 用它校验上传大小一致） | — |
| `questions_count` | INTEGER | 可空 | 题目数 | 上传时按 `questions.length` 统计（0 题拒收） |
| `materials_count` | INTEGER | 可空 | 材料数 | 同上 |
| `download_url` | TEXT | 可空 | EXTERNAL 作品的外链（http/https 直链） | `download-clicks.post.ts` |
| `storage_kind` | TEXT | NOT NULL DEFAULT `EXTERNAL` | `EXTERNAL`（作者外链）/ `HOSTED`（中心托管，v2） | `hosted.ts` |
| `file_sha256` | TEXT | 可空，索引 | **文件字节指纹**（与 `checksum` 分工不同） | `002` 注释；下载响应头 `X-Checksum-File` |
| `storage_key` | TEXT | 可空 | 对象键，形如 `packs/{file_sha256}.tiku` | `hosted.storageKeyOf` |
| `status` | TEXT | NOT NULL DEFAULT `ACTIVE` | **`ACTIVE` 在架 / `REMOVED` 下架** | `packRepo.remove/restore/reactivate` |
| `download_clicks` | INTEGER | NOT NULL DEFAULT 0 | 下载次数（**作品维度**：同 `package_key` 全部 ACTIVE 行同步 +1） | `incDownloadClicks` |
| `favorites_count` | INTEGER | NOT NULL DEFAULT 0 | 收藏数（冗余计数，**同 `package_key` 全版本行同步，且不带 status 过滤**） | `favoriteRepo.toggle` |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 创建时间（「最新版」判定依据：`ORDER BY created_at DESC, id DESC`） | — |
| `updated_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 更新时间（**无触发器**，由各 UPDATE 语句显式 `datetime('now')`；`incDownloadClicks` 与 `favorites_count` 更新**不刷新**它） | `repos.ts` |

约束与索引：`UNIQUE (package_key, version)`；`idx_packs_package_key`、`idx_packs_status_created(status, created_at DESC)`、`idx_packs_author(author_id)`、`idx_packs_file_sha256(file_sha256)`。

### 2.3.3 `favorites` — 收藏（用户 × 作品）

来源：`001_init`；`favoriteRepo`。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `user_id` | INTEGER | NOT NULL，**复合主键(1)** | 收藏者（`users.id`） |
| `package_key` | TEXT | NOT NULL，**复合主键(2)** | 作品（`packs.package_key`，**按作品而非版本收藏**） |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 收藏时间（`/api/me/favorites` 按它倒序） |

### 2.3.4 `comments` — 评论

来源：`001_init` + `004`；`commentRepo`。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | INTEGER | PK AUTOINCREMENT | 评论主键 |
| `user_id` | INTEGER | NOT NULL | 评论者（`users.id`） |
| `package_key` | TEXT | NOT NULL，索引 `idx_comments_package(package_key, id DESC)` | 作品 |
| `content` | TEXT | NOT NULL | 评论内容（≤500 字） |
| `likes_count` | INTEGER | NOT NULL DEFAULT 0（004） | 冗余计数（「有帮助」点赞数） |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 时间 |

删除评论会连带删除其 `comment_likes`（`commentRepo.delete` 事务内）。

### 2.3.5 `comment_likes` — 评论「有帮助」（单向上赞，登录限一赞）

来源：`004_comment_likes`；`commentRepo.toggleLike`。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `comment_id` | INTEGER | NOT NULL，**复合主键(1)** | 评论（`comments.id`） |
| `user_id` | INTEGER | NOT NULL，**复合主键(2)** | 点赞者（`users.id`），索引 `idx_comment_likes_user` |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 时间 |

**不设点踩**（`004` 注释：负面对抗信号，违背产品克制原则）。

### 2.3.6 `follows` — 关注关系

来源：`005_follows`；`followRepo`。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `follower_id` | INTEGER | NOT NULL，**复合主键(1)** | 关注者（`users.id`） |
| `following_id` | INTEGER | NOT NULL，**复合主键(2)** | 被关注者/作者（`users.id`） |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 关注时间 |

索引：`idx_follows_following(following_id, created_at DESC)`、`idx_follows_follower(follower_id, created_at DESC)`。**无自关注约束**（接口层拦：`authorId === me.id` → 400「不能关注自己」）。

### 2.3.7 `reports` — 举报 / 失效上报

来源：`001_init` + `003` + `010`；`reportRepo`；对外 JSON `ReportPublic`。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | INTEGER | PK AUTOINCREMENT | 举报主键 |
| `package_key` | TEXT | NOT NULL | 被举报作品（`packs.package_key`） |
| `type` | TEXT | NOT NULL | `LINK_DOWN` / `COPYRIGHT` / `OTHER`（`api/reports.post.ts` `TYPES`） |
| `note` | TEXT | 可空 | **举报人**填写的说明（≤1000 字） |
| `status` | TEXT | NOT NULL DEFAULT `OPEN`（003） | `OPEN` / `RESOLVED`（已处置）/ `DISMISSED`（不成立）；索引 `idx_reports_status(status, created_at DESC)` |
| `handled_at` | TEXT | 可空（003） | 处置时间 |
| `handled_by` | INTEGER | 可空（003） | 处置管理员（`users.id`） |
| `handle_note` | TEXT | 可空（010） | **管理员**处置备注（≤500 字；与 `note` 区分，仅管理端可见） |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 提交时间 |

### 2.3.8 `sessions` — 登录会话

来源：`001_init`；`sessionRepo`；`utils/auth.ts`。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `token_hash` | TEXT | **PK** | `sha256(token)` 十六进制；明文 token 只在 cookie / Bearer / 响应体中出现（泄库不可用） |
| `user_id` | INTEGER | NOT NULL，索引 `idx_sessions_user` | 归属用户 |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 创建时间 |
| `expires_at` | TEXT | NOT NULL | 过期时间（创建时 +30 天；**每次命中会话滑动续期到 `now + 30 days`**） |

### 2.3.9 `auth_tokens` — 一次性令牌（邮箱验证 / 重置密码 / 桌面 GitHub 登录 ticket）

来源：`009_auth_tokens`；`authTokenRepo`；kind 取值 `AuthTokenKind`。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `token_hash` | TEXT | **PK** | `sha256(明文 token)`；明文只出现在邮件链接或桌面回环回调 URL |
| `user_id` | INTEGER | NOT NULL，索引 `idx_auth_tokens_user(user_id, kind)` | 归属用户 |
| `kind` | TEXT | NOT NULL（**无 CHECK 约束**，扩展取值无需迁移） | `verify_email`（绑定邮箱，24h）/ `reset_pwd`（重置密码，30min）/ `desktop_login`（桌面登录 ticket，60s） |
| `email` | TEXT | 可空 | 仅 `verify_email`：待验证邮箱（验证成功才写入 `users.email`） |
| `expires_at` | TEXT | NOT NULL | 过期时间 |
| `created_at` | TEXT | NOT NULL DEFAULT `datetime('now')` | 创建时间 |
| `used_at` | TEXT | 可空 | 非空 = 已使用（单次使用） |

有效令牌判定：`kind` 匹配 + `used_at IS NULL` + `expires_at > datetime('now')`（`findValid`）。发新令牌前统一 `deleteByUser(user_id, kind)` 清理旧的同类令牌（同时只有一个有效链接）。

### 2.3.10 `schema_migrations` — 迁移记录（框架表）

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `name` | TEXT | PK（迁移名，如 `001_init`） |
| `applied_at` | TEXT | NOT NULL DEFAULT `datetime('now')` |

## 2.4 关系与关系示意（全部为逻辑关联，无外键）

| 关系 | 基数 | 实现 | 清理/一致性 |
| --- | --- | --- | --- |
| `users` → `packs` | 1—n | `packs.author_id` | 无级联；封禁不影响已发布作品 |
| `users` ↔ `packs` | n—n | `favorites(user_id, package_key)` | 取消收藏时反向 `favorites_count - 1`（`MAX(0, …)` 兜底不为负） |
| `users` → `comments` | 1—n | `comments.user_id` | 删评论连带删 `comment_likes`（事务） |
| `packs`(按 `package_key`) → `comments` | 1—n | `comments.package_key` | 评论挂在**作品**（不随版本下架消失） |
| `comments` → `comment_likes` | 1—n | `comment_likes.comment_id` | 同上 |
| `users` → `comment_likes` | 1—n | `comment_likes.user_id` | — |
| `users` ↔ `users` | n—n | `follows(follower_id, following_id)` | — |
| `users` → `sessions` | 1—n | `sessions.user_id` | 管理员重置密码 / 自助重置 → `deleteByUserId` 清全部会话 |
| `users` → `auth_tokens` | 1—n | `auth_tokens.user_id` | 发新令牌前删同类旧令牌 |
| `packs`(按 `package_key`) → `reports` | 1—n | `reports.package_key` | 处置时可联动下架（`removePackWithFiles`） |
| `users` → `reports` | 1—n（可空） | `reports.handled_by` | 管理员处置记录 |
| `packs` → `packs` | 自关联 1—n | `parent_key` → `package_key` | 衍生作品列表（每作品取最新 ACTIVE 版） |
| `packs.file_sha256` / `storage_key` → 磁盘文件 | 逻辑 | `hostedStore` | 下架时若同指纹无其他 ACTIVE 引用则物理删除 |

```
 users ──1—n──▶ packs(author_id) ──自关联 parent_key──▶ packs
   │                │
   │                ├──n—n── favorites ◀── users         （按 package_key）
   │                │
   │                ├──1—n── comments ◀──1—n── users      （按 package_key）
   │                │            │
   │                │            └──1—n── comment_likes ◀──n—1── users
   │                │
   │                └──1—n── reports ◀──handled_by── users （按 package_key）
   │
   ├──n—n── follows (follower_id / following_id 均指向 users)
   ├──1—n── sessions        （token_hash PK，30 天滑动续期）
   └──1—n── auth_tokens     （verify_email / reset_pwd / desktop_login）
```

## 2.5 关键业务约束

| 约束 | 规则 | 来源 |
| --- | --- | --- |
| 作品 vs 版本 | 一行 = `package_key` + `version`；作品页 = 同 `package_key` 的全部 ACTIVE 版本；列表/详情只出**最新 ACTIVE 版**（`ROW_NUMBER() OVER (PARTITION BY package_key ORDER BY created_at DESC, id DESC)`） | `packRepo.list/findLatest/listVersions` |
| 上架状态 | `status`：`ACTIVE` 在架 / `REMOVED` 下架。下架为**整包**（`UPDATE packs SET status='REMOVED' WHERE package_key=?`），不删行；`findAnyByKey` 用于区分「不存在」与「已下架」 | `packRepo.remove/restore`、`api/packs/[packageKey].get.ts` |
| 恢复上架 | `REMOVED` → `ACTIVE`；HOSTED 且文件已随下架删除的，恢复后需重新上传（响应 `hostedNeedsUpload`） | `restore.post.ts` |
| 版本冲突 | 同 `package_key` + `version` 已存在：本人且 `REMOVED` → 重新上架（`reactivate`）；否则 `REMOVED` → 409「已被他人登记过（REMOVED）」；`ACTIVE` → 409「已登记过；如需更新请升版本号」 | `packs/index.post.ts`、`upload.post.ts` |
| 托管方式 | `EXTERNAL` 必须 `download_url`；`HOSTED` 登记时不得带 `download_url`（由中心管理下载），文件另传 | `packs/index.post.ts`、`[version].put.ts` |
| 文件校验链 | 上传托管文件：登记存在 + ACTIVE + HOSTED + 作者本人 → 大小 ≤200MB 且与 `file_size_bytes` 一致 → `file_sha256` 一致（防替身）→ 文件内 `packageKey`/`version` 与登记一致 | `file.put.ts` 第 2–8 行注释 |
| 内容寻址 | 同 `file_sha256` 只存一份物理文件；下架时若被其他 ACTIVE 登记引用则**不物理删除** | `hosted.ts`、`utils/removal.ts` |
| 内容指纹 vs 文件指纹 | `checksum` = 内容指纹（桌面 Jackson 规范化，Node 无法复刻 → 直传路径 NULL）；`file_sha256` = 文件字节指纹（登记凭据） | `002` 注释、`006` 注释 |
| 注册约束 | 用户名 2–24 位 `[\p{L}\p{N}_-]`；密码 6–128；昵称 ≤20 | `api/auth/register.post.ts` |
| 账号唯一性 | `username` 唯一；`github_id` 部分唯一（未绑定可为多 NULL）；邮箱在 `email_verified=1` 时不可被他人占用（409） | `001`/`008`、`me/email.post.ts` |
| 无密码账号 | `password_hash = ''` → 密码登录通道天然关闭（`verifyPassword` 对任意输入返回 false），只能用 GitHub 登录，直到本人设置密码 | `repos.ts` 注释、`utils/auth.ts` |
| 计数冗余 | `packs.download_clicks`、`packs.favorites_count`、`comments.likes_count` 是冗余计数，随操作同步（含 `MAX(0, …)` 防负） | `favoriteRepo.toggle`、`commentRepo.toggleLike`、`incDownloadClicks` |
| 举报处置 | 处置动作只写 `status`/`handled_at`/`handled_by`（+`handle_note`），不新增状态列；下架复用 `removePackWithFiles` | `003`/`010` 注释、`admin/reports/[id]/handle.post.ts` |
| 管理员判定 | 环境变量 `ADMIN_USERNAMES`（逗号分隔用户名）即管理员；**不落库、无角色表** | `utils/admin.ts` |

> ⚠️ **隐患 3（已核实）**：`favoriteRepo.toggle` 的计数更新是 `UPDATE packs SET favorites_count = favorites_count ± 1 WHERE package_key = ?`，**没有 `status='ACTIVE'` 过滤**，而 `incDownloadClicks` 有过滤——同一作品的 REMOVED 旧版本行也会被加减收藏数，两处口径不一致（对外展示用的是 `findLatest` → 最新 ACTIVE 行，通常无感，但如果最新行是 REMOVED 而更老的 ACTIVE 行存在，展示值可能与真实不一致）。
>
> ⚠️ **隐患 4**：`packs` 没有外键，`favorites` / `comments` / `reports` 里保留已删除作品的 `package_key` 时不会报错（当前没有删 `packs` 行的接口，只有 `REMOVED`，所以暂不触发）。
>
> ⚠️ **隐患 5**：`reports.package_key` 无唯一/去重约束，同一作品可被反复举报（接口层仅按 IP 限流 10 次/分）。

## 2.6 广场文件存储布局

| 路径/键 | 内容 | 来源 |
| --- | --- | --- |
| `$DB_PATH`（默认 `<cwd>/data/plaza.db`） | SQLite 库（WAL 模式，会有 `-wal` / `-shm` 旁文件） | `migrate.ts` |
| `$HOSTED_DIR`（默认 `<cwd>/data/hosted`） | HOSTED 作品的托管文件，**平铺存放**，文件名为 `storage_key` 的最后一段 | `utils/hosted.ts` `hostedDir()/filePath()` |
| 对象键 `packs/{file_sha256}.tiku` | 内容寻址键；v1 存量键 `packs/{sha256}.json` 仍可读 | `storageKeyOf` 注释 |

## 2.7 两端对照（同名概念、不同实现）

| 概念 | 本地桌面库 | 广场库 | 差异要点 |
| --- | --- | --- | --- |
| 题库/作品容器 | `question_bank`（含内容包身份） | `packs`（一行 = 一版本） | 本地「题库」= 一个版本的落地形态且多版本各占一行题库；广场一行对应一个版本，作品 = 同 `package_key` 的多行 |
| 内容指纹 | `question_bank.checksum`（必填语义，导入/导出一致） | `packs.checksum`（**可空**；直传时为 NULL，改用 `file_sha256`） | Node 无法复刻 Jackson 字段序（`006` 注释） |
| 题目 / 材料 | `question` / `material` 表（本地导入后展开） | **不落库**：广场只存文件与元数据（`questions_count`/`materials_count`），不解题目内容 | 广场「只存不解析」（`package-meta.ts` 注释） |
| 作者 | `question_bank.author_id` / `author_name`（本地不校验登录） | `packs.author_id` → `users.id`（服务端赋值） | 本地无账号体系 |
| 删除 | 题库与题目**都物理删除**（级联清记录/复习/会话/材料） | 作品 `status='REMOVED'`（软下架，行保留，可恢复） | 语义相反，注意别混淆 |
| 时间类型 | `TIMESTAMP`（H2） | `TEXT`（`datetime('now')`，UTC） | 迁移时需统一口径 |
| 图片 | 数据目录 `images/` 分目录文件 + `[图片:name]` 标记 | 不存图片；HOSTED 时整包（含 `media/`）作为一个 `.tiku` 文件托管 | 图片随包传输 |
| 导出记录 | `export_records`（本地文件快照 + 已发布标记） | 无对应表（作品行本身就是登记） | 两侧用途不同 |

---

## 3. 待确认问题（需要人工核对）

1. `web/` 目录被 gitignore（官网不开源），本文广场侧事实取自**本机工作树**；线上库是否与 `migrate.ts` 的 010 条迁移完全一致——**待确认**。
2. ~~`question` 表逻辑删除残留 + `uk_question_bank_external_id_deleted` 的冲突风险~~ —— **已修复（2026-09-11）**：删库物理删题、导入按 `questionKey` 判重跳过，见 1.4.2 隐患 1。
3. 是否需要给 `question_number` 加「题库内唯一」约束（当前仅排序语义）——**待确认**。
4. `packs.updated_at` 无触发器，`incDownloadClicks` / `favorites_count` 变更不刷新它；是否有意（避免热度变化刷列表排序）——**待确认**。
5. 「内容未变化禁止变更版本号」只在 `ContentPackageService.exportContentPackage` 生效；`LocalExportService` 通过「打包前改写 version」绕过该规则（作者可发同内容新版本），是否有意——**待确认**（`LocalExportService` 第 79–85 行注释称是有意设计）。
6. 本地 `ai_import_job` 是否应随删库清理（当前不清理）——**待确认**。
