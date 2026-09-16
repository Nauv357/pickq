# 功能与模块分配审计（2026-09-16）

> 起因：用户提出"检查目前的功能和模块的分配情况，如有不合理或者缺失的要修改或补充"。
> 做法：三路只读审计（前端信息架构 / 后端模块边界 / 死资源与文档漂移）+ 逐条人工判断，
> 分三级：**必修**（真 bug 与明确违规，本轮已改）、**建议**（值得做但代价明确，排期另定）、**记录**（口径问题，先写下来）。
>
> 结论一句话：**前端没有死组件/死路由（这块很干净），后端只有少量真死代码；真正的病在两头——
> 同一套业务口径被复制成多份且互不相等，以及文档快照严重过期。**

---

## 一、必修：本轮已经改掉的

### A. 业务口径被复制成多份（最严重的一类）

| # | 问题 | 证据（改前） | 现在的样子 |
| --- | --- | --- | --- |
| A1 | **「最近一次作答算不算错」有 5 份实现，且互不相等** | 权威版 `StudyRecordService.isWrong`；`StatsService.isWrongDecide`（优先级**相反**）、`StatsService.decide`（只看 correct）、`TutorService.isWrongAttempt`（漏一条）、`TutorService.buildQuestionContext`（只看 correct）、`AdaptiveService.abilityOnQuestionNodes`（完全忽略自评） | 统一成 `StudyRecordService.isWrong / isCorrect / effectiveGrade` 三个静态方法（自评优先、未判定返回 null），6 处调用点全部改为调它 |
| A2 | **「未判定」在会话详情/会话列表里被算成"答错"** | `PracticeSessionService.getSessionDetail` 与 `toSessionResponse` 把 `correct == null` 落成 `"WRONG"`（成绩报告里却是 null） | 两处改用 `StudyRecordService.effectiveGrade`；未判定的题不再被算错 |
| A3 | **85% 规则有两份实现，生产用的那份没有 80–90% 区间优待** | `AdaptiveService.orderByTargetSuccess`（带区间优待，**零调用**）vs `PracticePlanService` 的私有副本（纯距离）→ 文档承诺的"优先挑 80%–90% 成功率"实际没生效 | 删掉私有副本，配题改调共享方法；难度缺省值也走 `AdaptiveService` 的按题型初值（原先硬编码 400） |
| A4 | **`mastery()` 的注释与公式不符** | 注释与设计稿写"独立度（提示/追问打折）"，`mastery()` 里根本没有这一项，也没有 `tutor_message` 的 join | 注释改成实际公式，并写明"独立度项未实现" |
| A5 | **`interleaveRatio`（交错混练）零调用** | 文档说"掌握后混入相邻知识点的题"，代码里没人调它 | 方法保留但注释写明**未接入**；`features.md` 同步降级这句承诺 |

**新增回归测试** `WrongAnswerRuleConsistencyTest`（5 项）：用同一批数据（客观错 / 客观对 / 判题列对但自评部分对 / 自评错 / 未判定）把
**错题集合、统计页、讲解复盘、会话详情与报告、能力分**五个出口一起问一遍——任何一处口径漂移都会立刻变红。
这类"同一规则多处实现"的 bug 以前只能靠人肉比对发现，现在有网兜着了。

### B. 死代码（删除）

| 项 | 说明 |
| --- | --- |
| `POST /api/skills/templates/{id}/sync` + `SkillGraphSyncService` + `SkillNode/SkillEdge` 实体与 mapper | 它把内置模板写进 `skill_node`/`skill_edge`，而**这两张表从来没有被读过**（读路径一直是内存图）。留着只会让后来者以为库里那份才是真相。表本身按既有决定保留（不动迁移） |
| `GET /api/banks/{id}/questions/practice` + `QuestionBankService.getBankPracticeQuestions` | 会话制之前的"分页取做题数据"，前端包装零调用 |
| `SessionQuestionItem.fromEntity` | 它把 `referenceAnswer`/`materialContent` 一律填 null，与真正在用的构造语义冲突——没人敢用，所以没人用 |
| `NoteService.deleteByBank / deleteByQuestion` | 级联删除实际由 `QuestionBankService` / `QuestionService` 直接调 mapper 完成，这两个方法从未执行 |
| `GlobalExceptionHandler` 的 `View error` 注入 | 构造器参数从来没用过（写它的时候是"顺手注入"，之后没人碰） |
| 前端 5 个零调用 API 包装 | `getPracticeQuestions`、`submitStudyAnswer`、`getBankRecords`、`getQuestionRecords`、`submitAnswer` |
| 前端 7 个零调用工具函数 | `setCenterUrl`、`hasImageMarker`、`hasLatex` + 4 个"只该内部用却导出了"的（`BUILTIN_DEPRECATED` 等改为不导出） |
| 13 个死词典 key | `aiFillTip/exitSelectMode/selectModeTip`、`act.createShort/newTag/dialogs.createDesc/meta.counts`、`byAuthor/searchResult` 等 |
| `BankListView` 的死导入 `openLocalFolder`；`utils/updater.js` 里 `isDesktop` 的第二份实现 | 后者是"同一个判断写两遍"，两处一旦漂移就会出现"更新走桌面路径、打开链接走浏览器路径" |

### C. 明确的功能/文案缺陷（修改）

| # | 问题 | 现在的样子 |
| --- | --- | --- |
| C1 | 「我的笔记」里点「第 N 题」**跳过去没有任何反应**（`?q=` 参数没人读） | 题库详情支持 `?q=<题目 id>`：自动翻到该题所在页、展开它的讲解面板并滚到视野中间（找不到时如实提示"不在当前筛选结果里"） |
| C2 | 删除题目/材料的**确认按钮写着「删除题库」**（复用了值为「删除题库」的 `delete` 键） | 新增 `confirmDeleteQuestion`/`confirmDeleteMaterial`，按钮文案=动作本身（R3） |
| C3 | 统计页卡片叫「题库 × **掌握度**」，内容其实是"已做/共 + 正确率" | 改名「题库 × 进度与正确率」；顺带清掉 `deepSub` 里的"掌握度"措辞——我们不做掌握度宣判（§4.8） |
| C4 | 统计页"加载失败"分支**永远不可达**（外层已判 `wrongHeal`），失败时页面伪装成"还没有记录" | 失败态显示原因 + 「重试」；错题卡与最近练习卡分别给出路 |
| C5 | 笔记页保存/删除失败时**界面什么都不显示**；加载失败被当成"还没有笔记" | 失败就显示原因 + 重试 |
| C6 | 做题页快捷键提示**没走 i18n**（英文界面仍中文），且写死「A~H」而实现按题目实际选项键判断 | 提示进词典；文案改成与实现一致（"选项字母"、"数字 1 秒内连按可跳多位数"） |
| C7 | 题目行 title 里还在说「右侧按钮可做题 / AI 解析 / 删除」（AI 解析已退役） | 改成实际菜单项；编辑器里的草稿解析按钮也从「AI 解析」改为「AI 生成解析」，与「讲解」区分开 |
| C8 | 三种"加题"入口名字分不清：AI 追加 / 批量导入 / 添加题目 | 「AI 导入文档…」/「粘贴 JSON 建题…」/「新增题目」（按门槛递减排） |
| C9 | 题库详情页同屏**三个主按钮**（开始练习 + 今日待复习 + 添加题目），违反 R1 | 「今日待复习」降为次级样式（到期题本来就含在一键配题里） |
| C10 | 题目区 6 个按钮平铺，违反 R2 | 只留"三种加题 + 更多"；共用材料 / AI 补答案 / 选题另存收进题目区「更多」 |
| C11 | 「我的笔记」只有二级菜单一个入口，且看不出里面有没有东西 | 「更多」里的「我的笔记」显示条数（与「错题（N）」「收藏（N）」同口径） |
| C12 | 「恢复复习计划」确认框的取消按钮写「再想想」（R3 要求统一「取消」） | 统一为「取消」 |
| C13 | 导出题库文件三种说法：「导出文件…」/「导出题库文件…」/「导出并保存文件」 | 统一为「导出题库文件」 |
| C14 | 题库列表菜单里「重命名 / 改描述…」与详情页「编辑题库」混用（两者字段并不一样） | 列表页改成「改名称与描述…」（说清它只改这两样） |
| C15 | 题库子页面折叠侧栏的正则漏了 `/banks/:id/notes`（同是题库子页，笔记页却留着宽侧栏） | 规则统一：题库子页（详情/做题/历史/笔记）都折叠侧栏 |

---

## 二、建议（值得做，但要单独排期）

1. **R1–R17 大面积欠账 = 既有 P4 迁移**：`PageHeader` 12 页只用了 4 页；`Pager` 有 12 处直接写 `el-pagination`；
   `EmptyState` 有 28 处自写空态（不少没有出路）；`useConfirm` 有 12 处绕过；`ActionMenu` 只有两页接入了右键。
   这是一次"界面一致性"专项，动的是布局与交互，风险中等、收益是"每个页面都长一个样"。
2. **8 处 `ElMessageBox` 手写确认框收敛到 `useConfirm()`**：其中退出登录（`AppLayout`）最值得改。
3. **i18n 欠账 247 行硬编码中文 / 13 个文件**：`PracticeView`、`AiImportPreviewView`、`SettingsView` 是重灾区。
   注意 `QuestionFormPanel` 的脏数据确认框（"保存并关闭/放弃修改"）是 `useConfirm` 表达不了的语义，不要硬套。
4. **做题页缺鼠标跳题入口**：详情页与练习历史都挂了 `QuestionNavDock`，做题页只能靠 ←/→ 与数字。
   建议加一个紧凑的「答题卡」浮层（而不是把 1990 层的悬浮盘整个搬过去，会挤占做题区）。
5. **能力分/掌握度口径分散在 5 处**（`PracticePlanService.mastery`、`AdaptiveService.abilityOnQuestionNodes`、
   `TutorService.performanceCounts/performanceLabel`、`StatsService.BankStat`）：用途不同（排序 vs 展示 vs 库级），
   但**公式不同**。建议至少加一张对照表写进注释，避免下一次"26 与 16"式的数字打架。
6. **`PracticePlanService` 职责过载**：算法 + 桶配额 + 薄弱点选择 + **中文解释文案**混在一个类里（英文界面会看到中文）。
   建议把"解释"改成返回结构化数字、由前端 i18n 拼句子。
7. **`question_difficulty` 没有出口**：难度只影响排序，用户与开发者都无法查看/重置（一度被推到极端后只有 clamp 保护）。
   建议给设置页一个"重置难度估计"的小按钮。
8. **`CardService`（465 行）与 `card` 表是纯测试资产**：界面已下线、无端点、无调用方。
   按 §4.8 的决定保留不删，但 `features.md` 里"配题/复习调度仍然用它们"的说法不成立（已改）。
9. **`skill_group_map` 表有读写但无出口**；`daily_task`/`learner_profile`/`practice_session.scope_node`
   是"零读写"的空表（按决定保留）。建议在 `data-model.md` 里明确标注这三类的状态，免得下一个人以为漏了实现。
10. **打印页是否豁免 R1/R2/R5**：它是独立布局（无侧栏），现在主按钮不在页头、筛选在右侧、无分页。需要一次口径确认。

---

## 三、记录（先不改，等产品口径）

- **「再想想」还是「取消」**：全库曾把 4 处危险确认的取消按钮写成「再想想」；本轮统一成「取消」（因为 R3 是硬规则）。
  如果那是刻意的"劝退文案"，请告诉我，我改回并把它写进规范。
- **卡片勾选框的位置**：R13 字面要求"对象左上/行首"，但 R13 的另一段又写"卡片用标题行右侧槽位"，规范内部冲突，
  实现按后者。需要在规范里二选一。
- **侧栏主题快捷开关**：`AppLayout` 页脚与设置页都能切主题（R16 意义上的重复入口），
  三处注释都引用了并不存在的"规范 v2.1 §4.10"。要么承认它是刻意的便利，要么删掉一个。
- **`R13` 的 Shift 连选只做了题库列表**，题目列表没有；批量入口一个在工具条、一个在内容区标题行。
- **`web/`（官网/广场）与服务器侧文档无法核实**（`.gitignore` 忽略且本机不存在），相关数字只能标注"待核"。
- **`deploy/tools/check-vue-i18n-keys-2/3.mjs`** 像是重复的临时工具；`frontend/scripts/shot-routes.mjs` 没有 npm script
  且内部 stub 把 `POST /api/ai/models` 当 GET。要么补脚本+修 stub，要么删。

---

## 四、文档漂移：本轮改了什么、还剩什么

**已改**（这些会直接误导下一个改代码的人）：
- `docs/api.md`：**补上整个 `SkillController`（15 个端点）**——已发布功能此前在 API 文档里零出现；
  端点/控制器总数改为实测值；补 `merge-category-into-topic`；记下已退役的 `ai-analysis` 与 `sync`。
- `docs/architecture.md`：`POST /api/questions/{id}/ai-analysis` 不再是"现存端点"（已退役，改指 `tutor/explain`）。
- `docs/features.md`：错题口径段落改成"统一实现 + 指向回归测试"；掌握度公式注释纠偏；交错混练降级为"未接入"；
  `CardService` 保留理由改成"纯测试资产，界面已下线"。
- `docs/data-model.md`：迁移到 V21、表数 20、补 `note` 表结构。
- `CHANGELOG.md`：末尾"当前版本 0.1.17"改为 0.1.20。
- `docs/code-map.md`：结构数字（服务/控制器/DTO/mapper/model/迁移/端点数）按实测更新，并加一句
  **"行数是快照，会漂移"**的提醒（逐文件行数不再假装精确）。
- `CONTRIBUTING.md`：打包脚本实际是 **6 步且第 1 步就是 `npm run build`**（原文说"不会替你构建前端"）；
  npm 脚本数按实际更新。
- `docs/design-ui.md` / `conventions.md`：硬编码中文行数（247 行 / 13 文件）、冒烟断言条数按实测更新；
  `api/` 文件清单去掉不存在的 `center.js`。
- `docs/import-issues.md`：样例生成器路径改为真实存在的 `src/test/java/com/tiku/GenerateSampleFiles.java`。
- `docs/learning-path-design.md`：删掉对已删除脚本/测试（`e2e-roadmap-live.mjs`、`e2e-cards-live.mjs`、`RoadmapServiceTest`）的引用。

**还剩**（记录在案，逐次随改动顺带修，不做一次性大扫）：
- `code-map.md` 的**逐文件行数**仍会漂移（已有提醒）；i18n 词典分布表需重生成。
- `docs/README.md` 未收录 `plaza-redesign-requirements.md` 与本文。
- `design-ui.md` 的 P4「待办」状态与实际完成度不符（部分页面已迁移）。

---

## 五、验证

- 后端全量 `mvn -o test`：**253 通过 / 0 失败**（新增 `WrongAnswerRuleConsistencyTest` 5 项；
  同步修正了 `SkillControllerHttpTest` 对已删 sync 端点的断言、5 个 HTTP 测试对 `GlobalExceptionHandler` 构造的用法）。
- 前端 `check:ui` 全绿、`build` 通过；冒烟：`smoke:bank` 76、`smoke:skills` 44、`smoke:editor` 28、
  `smoke:keys` 12、`smoke:ai` 13、`smoke:mineru` 15、`smoke:import` 13；真后端 `e2e:tutor` / `e2e:skills` 见提交说明。
- 本轮改动**没有动数据库结构**（只删了没人读的 `skill_node`/`skill_edge` 的**写入口**，表与迁移保留），
  升级无需迁移。
