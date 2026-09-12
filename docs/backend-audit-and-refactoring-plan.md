# Java 后端审计与重构路线图

初次审计日期：2026-09-11  
路线图更新日期：2026-09-11  
审计范围：`src/main/java`、`src/test/java`、`src/main/resources` 与 Maven 构建配置。  
审计方式：先执行只读代码审查与基线测试，再在本副本中实施重构。原始项目目录未被修改。

## 基线结论

- 架构：Spring Boot 3.4、Java 21、MyBatis-Plus、H2、Flyway。
- DTO 已统一使用 Java `record`，Controller → Service → Mapper 的主分层清晰。
- 初始审计时，`DocumentParserServiceTest` 引用了仓库中不存在的 `sample-ai-files/2024安徽高考真题物理（教师版）.docx`，导致测试夹具失效。
- 构建机的 `JAVA_HOME` 指向 JDK 17，直接执行 Maven 会因 `pom.xml` 要求 Java 21 而失败；显式设置 JDK 21 后可以编译并运行测试。这是环境配置问题，不是业务代码编译错误。

## 问题清单

### P0：图片引用校验可绕过题库目录隔离（已修复）

`ImageStorageService` 的正则允许 `.` 与 `..` 作为目录段，且读写时直接 `resolve(name)`，没有将最终路径规范化后校验其仍位于对应题库的图片目录内。导入内容包中的 `../x.png` 会落到图片根目录而不是该题库目录，破坏题库数据隔离。

**落实结果：** 已集中图片引用与路径校验，拒绝 `.` / `..`、绝对路径、反斜杠和多层路径；读取、导入和跨库复制均在规范化后验证目标仍位于题库图片目录内，并有回归测试覆盖。

### P0：备份恢复的解压大小限制在文件完整写入后才生效（已修复）

`BackupService.prepareRestore` 使用 `Files.copy(zis, target)` 完整写入 ZIP 条目后，才累计并检查 5GB 上限。恶意高压缩比条目可在校验前耗尽磁盘空间；`IllegalArgumentException` 路径也没有统一清理已解压的暂存文件。

**落实结果：** 已改为有界流式复制，写入过程中累计总量并在超限时立即中止；异常与校验失败路径统一清理暂存目录，并有超限和失败清理测试。

### P1：AI 配置直接覆盖写入且读取错误被静默重置（已修复）

`AiConfigService.save` 直接写目标文件；进程中断或磁盘异常可能留下半写入 JSON。`load` 对所有 `IOException` 直接返回空配置，会让用户表现为“配置丢失”，也掩盖可诊断的配置损坏。

**落实结果：** 已采用临时文件写入后原子替换；未配置仍返回空配置，已有文件无法解析时记录明确告警，避免把损坏配置误判为“用户未配置”。

### P1：本地无鉴权 API 未显式限制监听地址（已修复）

应用包含导入、备份、文件读取等本地 API，但默认 Spring Boot 会绑定所有网络接口。CORS 不是访问控制，局域网可达时不能保护这些接口。

**落实结果：** 默认配置已显式绑定 `127.0.0.1`。未来若支持局域网访问，必须以独立开关、身份验证、CSRF 策略和访问日志作为一个完整功能交付。

### P1：测试夹具与仓库内容脱节（已修复）

`DocumentParserServiceTest` 固定引用“教师版”文件，但仓库只有 `2024安徽高考真题物理.docx`，导致完整测试集不稳定。

**落实结果：** 测试已自动定位受版本控制的物理 DOCX 夹具，并保留图片可解码、锚点和答案区断言；判断推理 PDF 作为另一类版式回归夹具保留。

### P2：AI 导入服务过度集中（风险已显著降低）

审计时 `AiImportService` 约 6,100 行，兼任任务编排、文件暂存、PDF/DOCX 解析、OCR/视觉策略、AI 调用、结果修复、图片裁剪和持久化，异常处理大量使用宽泛的 `catch (Exception)`。这使回归定位、单元测试和后续演进成本很高。

**已实施（本轮）：** 已抽出 `AiImportJobStorageService`（任务文件、图片素材、过期目录与清理）、`AiImportResult` / `AiImportResultCodec`（结果契约与历史 JSON 兼容）、`AiImportPromptFactory`（文本/视觉提示词及模型配置）、`AiImportDocumentPipeline`（本地解析、MinerU 显式调用与回退、跨文件页号/图片对齐、解析进度回调）、`AiImportTextStructure`（题号识别、文本分块、图片配额）、`AiImportSourceTextService`（源文定位、题干回填、答案证据校验）、`AiImportResultParser`（Markdown/JSON 兼容解析和结果安全校验）、`AiImportFormulaService`（LaTeX 规范化、公式图转写兜底）、`AiImportAnswerService`（原文答案恢复、AI 补充、材料引用收尾）、`AiImportVisionQualityService`（视觉结果去重、残题过滤、图片引用边界与排序）、`AiImportVisionMissingPageService`（逐页差异检测与缺题重跑）以及 `AiImportImageReferenceService`（临时图片落盘和正式图片引用替换）。

`AiImportService` 现在只保留任务生命周期、确认入库，以及文本／视觉模型路径的高层编排；从约 6,148 行降至约 4,580 行。视觉分页补漏、结果质量控制和图片引用处理已迁出。

**第二轮（2026-09-12，本轮已实施）：** 按上文"先引入不可变上下文，再整体迁移"的判断继续拆，`AiImportService` 从 4,156 行降到 **947 行**：

1. **先删死代码 1,046 行**（18 个方法）。审计时主服务里混着大量"已退役但没删"的实现（例如 `resolvePdfStemImages` 398 行在原始版本里就从未被调用；`detectMaterialGroups` 的注释直接写着"已退役"）。判定方式：私有 + 全文只出现一次声明 + 无 `::` 方法引用，迭代到不动点，每步都用 `mvn compile` 兜底。
2. **PDF 版面算法整体迁出 → `AiImportVisionLayoutService`（905 行）**：11 个方法（占位符→图片分配、图形块检测、按带裁剪、选项格切分、PDF 图题归位等）与 5 个版面阈值常量。**结论修正**：这些方法并没有共享可变缓存——它们全部"参数进、结果出"，只依赖传入的页文本/渲染页/行坐标/临时目录，因此**不需要** `VisionLayoutContext` 也能整体搬走；该上下文只在后续要拆 `AiImportVisionLayoutService` 内部时才需要。搬迁用逐字对比验证（11/11 方法体一致）。
3. **任务生命周期 → `AiImportJobLifecycleService`（281 行）**：13 个方法（状态机、取消语义、启动自愈、任务快照、SSE 订阅、终态清理）。主服务保留同名门面，控制器与启动流程仍只依赖一个入口。
4. **模型调用编排 → `AiImportModelCallService`（1128 行）**：20 个方法（三条调用路径 `chatChunked`/`chatVisionSingle`/`chatVisionPages` + 分块/合并/重试辅助 + 对 textStructure/resultParser 的薄转发）与 10 个该层常量。主服务只负责"什么时候调用哪条路径"。

**验证方式：** 每次搬迁都对"改动前的方法体"与"改动后的方法体"做**逐字比对**（生命周期 13/13、模型调用 20/20 一致；模型调用两个入口的唯一差异是新增的 `lifecycle.` 委托前缀）；全量 159 项测试保持全绿；jar 冒烟（Flyway V1..V16、Tomcat 启动、AI 导入与题库接口 200）通过。

**遗留：** `executeJob` 仍是 310 行的流程编排方法（解析 → 选路径 → 后处理 → 落库），其内部步骤已全部委托给协作组件；若要继续减小，需要把"选路径"的判定与"后处理链"再各抽一层，属可选项而非阻塞项。

**原判断与修正：** 第一轮认为 PDF 图形块识别、页面坐标、图像裁剪与选项格切分"共享同一份页面渲染/坐标系/图片缓存，不宜拆分"。第二轮实测后发现这些方法其实都是"参数进、结果出"（渲染页与行坐标由调用方传入），并不共享可变状态，因此已整体迁到 `AiImportVisionLayoutService`；`VisionLayoutContext` 只在需要进一步拆分该服务内部时才需要。**仍然成立的纪律：不得继续在 `AiImportService` 中添加新的业务规则**——新规则落到对应协作组件。

**剩余风险：** AI 调用和第三方解析失败仍有部分宽泛 `catch (Exception)` 路径。它们当前以“单块失败、结果尽量可预览”为设计取舍，后续应补充错误分类、失败原因码与任务诊断信息，而不是直接收紧为一次失败即整单失败。

### P2：控制器承担过多远程转发细节（已修复）

`CenterPublishController`（约 739 行）与 `CenterProxyController`（约 335 行）同时承担 HTTP 客户端、参数规范化、文件暂存、远程错误解析和接口编排。

**落实结果：** 已抽出 `CenterUrlPolicy`（中心根地址、外链地址和路径拼接规则）、`CenterHttpClient`（会话令牌、超时、JSON/流式 multipart、远端错误映射）、`CenterPublishService`（本地体检、临时文件、发布与导出记录联动）和 `CenterBrowseService`（浏览、下载、导入和互动转发）。`CenterPublishController` 已从约 739 行缩至 114 行，`CenterProxyController` 缩至 144 行；`CenterAuthController` 也复用了相同的地址策略与 HTTP 客户端，不再直接创建连接。

## 本轮实施完成项

1. 修复图片目录越界、备份 ZIP 解压上限、AI 配置原子写入、默认监听地址和失效测试夹具。
2. 将 AI 导入拆为任务文件、结果编解码、提示词、文档解析、文本结构、源文证据、输出解析、公式、答案、图片引用、视觉质量和视觉分页补漏等独立组件。
3. 为新增组件补充无模型单元测试；保留 DOCX、判断推理 PDF 和本地解析/MinerU 回退回归测试。
4. 拆分中心发布／代理／认证的远程调用边界，补充地址策略与 HTTP 客户端回归测试。
5. 增加 Windows JDK 21 Maven Wrapper 脚本，避免环境的 `JAVA_HOME` 指向 JDK 17 时产生误导性编译失败；未创建提交。

## 本轮验证结果

- 使用 `C:\Program Files\Java\jdk-21` 执行完整 Maven 测试：159 项测试，0 失败、0 错误、0 跳过；`scripts/maven-java21.ps1 -MavenArguments @('-DskipTests', 'package')` 已生成可运行 JAR。
- `DocumentParserServiceTest` 同时覆盖物理 DOCX（公式图片、锚点、答案区）与 `sample-ai-files/专项智能练习（判断推理）(1).pdf`（行尾题号拆分、题干归位），避免用一种文件类型替代另一种回归场景。
- 未执行 `git commit`、`git push` 或改写 Git 历史；所有修改仅在 `D:\PickQ\Tiku` 工作副本中完成。

## 后续路线图（按优先级）

### P1：建立可复现的 AI 导入契约回归（已完成基础覆盖）

当前单元测试覆盖解析、分块、答案证据、公式定界符和视觉质量规则，但不应使用真实模型端点作为 CI 前置条件。应沉淀脱敏后的 Markdown、JSON、视觉分页结果样本，并以固定输入断言最终题目、材料、图片引用和诊断状态。

**落实结果：** 已新增文本、单次视觉、视觉分页、MinerU 回退四类脱敏固定响应夹具；测试在不调用真实模型的情况下断言答案证据、图片编号边界、页序归位与材料引用。现有 `AiImportDocumentPipelineTest` 同时覆盖 MinerU 不可用时的本地解析回退。

### P1：为 AI 导入建立可观测的失败分类（已完成）

将模型超时、网络错误、限流、响应格式错误和文档解析失败区分为稳定的任务错误码；用户取消继续保留为独立的 `CANCELED` 任务状态。同时保留脱敏诊断摘要，不要把完整模型响应、API Key 或用户文档正文写入常规日志。

**落实结果：** 已新增 `AiImportFailureClassifier` 和 `ai_import_job.error_code`。超时、限流、连接、协议、文档解析等失败写入稳定错误码和可操作的安全提示；前端既有 `error` 展示路径可直接显示该提示，API 同时返回 `errorCode`；日志按任务 ID 记录脱敏诊断摘要，不再直接打印异常对象。

### P2：引入 `VisionLayoutContext` 后再迁移 PDF 版面算法

页面坐标、渲染图片、文本位置与临时图片编号应封装为不可变上下文，再将图形块识别、裁剪和选项格切分整体迁入 `AiImportVisionLayoutService`。这是一项完整迁移，不应按单个私有方法零碎拆分。

**验收条件：** 视觉布局服务不读取任务 Mapper、不发布任务事件；同一 PDF 的布局测试可不调用模型执行。

### P2：降低大文件导入的堆内存峰值

评估上传路径中 multipart 的内存复制，改为受大小上限保护的流式落盘，并在任务取消或失败时确保清理临时文件。

**验收条件：** 用接近上传上限的文件测试时，Java 堆增长可预测；取消和失败不遗留任务目录。

### P3：固化 Java 21 构建环境（已修复）

已新增 `scripts/maven-java21.ps1`：优先识别正确的 `JAVA_HOME`，否则从 Windows 默认 JDK 目录寻找 JDK 21，再调用仓库自带 Maven Wrapper。README 与贡献文档已给出一键构建命令。

**验收结果：** 在 `JAVA_HOME` 人为指向 JDK 17 的条件下，脚本仍选择 `C:\Program Files\Java\jdk-21` 并成功执行 `compile`。CI 环境仍建议在其工作流镜像中显式安装 JDK 21。
