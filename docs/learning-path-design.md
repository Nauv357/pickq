# 学习路径引擎设计（Learning Path Engine）

> 状态：**设计定稿，尚未实现**（2026-09-14）。本文是实现依据，不含代码。
> 已确认的三个前提（用户拍板）：
> 1. 打标签走**两级映射**：题目 → 作者 topic/category → 技能节点；
> 2. **门控只用「作者确认」或「高置信」标签**，AI 低置信标签仅用于推荐；
> 3. **广场质量分**（标签覆盖率 / 解析完整度 / 来源标注）列入广场改版范围，作为排序与筛选维度。

---

## 0. 一句话

把"题库"从**一堆题**变成**一张能走的学习地图**：目标 → 路线 → 你的缺口 → 今日任务 → 做题/追问 → 掌握度 → 路线自适应。
错题追问是这条链上每一环的入口，不是独立功能。

**要解决的问题**：用户导入题库 → 不知道还缺什么 → 刷两题 → 放弃。

---

## 1. 产品闭环

```
① 目标与现状输入（一次，5–8 问）
② 学习路线（技能图 + 你的当前位置）
③ 缺口匹配（路线节点 ↔ 我的题库：有题 / 缺题 / 只有 1 题）
④ 今日任务（确定性生成，日常不花 token）
⑤ 练习 → 复盘 → 追问 → 闪卡
⑥ 掌握度回馈 → 门控放行 → 路线自适应（跳过已掌握、回退假掌握）
```

---

## 2. 概念模型

| 术语 | 含义 | 谁产生 |
| --- | --- | --- |
| **知识结构（技能图）** | 节点 + 前置边 + 权重。官方/社区/私有三种来源 | 模板（人写 + AI 裁剪） |
| **知识状态** | 你在每个节点上的掌握度证据集合 | 做题记录自动累积 |
| **外缘（fringe）** | "前置都已掌握、但你还没掌握"的节点集合 = **下一步** | 算法从图 + 状态算出 |
| **门控（gate）** | 某节点算不算"过关"的判定 | 确定性公式（不看模型判断） |
| **覆盖（coverage）** | 路线里有多少节点在你的题库里有题 | 标签 × 题库统计 |
| **缺口** | 没题 / 只有 1 题（证据不足）的节点 | 覆盖统计 |
| **证据（evidence）** | 一次作答记录下来的（对错、用时、是否用提示、是否追问） | 做题流程自动采集 |

**核心区分：模板给的是"图"，不是"路"。** 路径 = 你的知识状态在这张图上的外缘。同一张图，起点和已掌握集合不同 → 路径必然不同（知识空间理论 / ALEKS Learning Mode 的做法）。

---

## 3. 数据模型

### 3.1 技能图

```sql
-- 技能节点（受控词表：标签值必须引用它）
CREATE TABLE skill_node (
  node_id     TEXT PRIMARY KEY,   -- 稳定 ID：'cs.fe.react.hooks'（一旦发布不改）
  name        TEXT NOT NULL,      -- 显示名
  parent_id   TEXT,               -- 层级：学科 → 模块 → 知识点
  level       INTEGER NOT NULL,   -- 1 学科 / 2 模块 / 3 知识点
  weight      REAL DEFAULT 1.0,   -- 目标权重（面试目标 → 手写题/八股提权）
  optional    INTEGER DEFAULT 0,  -- 加分项（不参与门控）
  keywords    TEXT,               -- JSON 数组：用于把题目/题库映射到本节点
  template_id TEXT,               -- 所属模板（私有图可为空）
  source      TEXT NOT NULL,      -- official / community / private
  updated_at  TEXT NOT NULL
);

-- 前置关系（决定顺序与外缘）
CREATE TABLE skill_edge (
  from_id TEXT NOT NULL,          -- 前置节点
  to_id   TEXT NOT NULL,          -- 后继节点
  type    TEXT NOT NULL,          -- prereq / related
  PRIMARY KEY (from_id, to_id, type)
);
```

### 3.2 题目标签（三级来源）

```sql
CREATE TABLE question_skill (
  question_id INTEGER NOT NULL,
  node_id     TEXT    NOT NULL,
  source      TEXT    NOT NULL,   -- author / user / ai
  confidence  REAL    NOT NULL,   -- author/user=1.0；ai∈[0,1]
  confirmed   INTEGER DEFAULT 0,  -- 是否人工确认过（author 标注视为已确认）
  origin      TEXT,               -- 'topic-map' / 'ai-direct' / 'manual'
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (question_id, node_id)
);
CREATE INDEX idx_question_skill_node ON question_skill(node_id, confirmed, confidence);
```

**权限与优先（同一题同一节点允许多来源，取"生效值"，保留历史用于提示冲突）**

| 来源 | 生效优先 | 范围 | 参与门控 |
| --- | --- | --- | --- |
| author（作者标注） | 低于 user | 随内容包分发 | ✅ |
| user（本人修正） | **最高**（覆盖作者） | 只影响本机 | ✅ |
| ai（AI 建议） | 最低 | 本机待确认队列 | 仅当 `confidence ≥ 0.8` 或 `confirmed = 1` |

- 生效规则：`user > author > ai`；被覆盖的不删除，标记为 `shadowed`，用于"作者更新了标签，是否采用？"提示。
- `confirmed` 只在人工确认后置 1；AI 高置信可直接用于**推荐**（出题），门控要求 `confirmed=1 OR (ai AND confidence ≥ 0.8)`。

### 3.3 用户知识状态与个体输入

```sql
CREATE TABLE user_skill_state (
  node_id          TEXT PRIMARY KEY,
  mastery          REAL NOT NULL DEFAULT 0,   -- 0..1，公式见 §5
  attempts         INTEGER DEFAULT 0,         -- 参与计算的作答次数
  correct          INTEGER DEFAULT 0,
  hint_used        INTEGER DEFAULT 0,
  tutor_asked      INTEGER DEFAULT 0,
  last_practiced_at TEXT,
  last_spaced_ok   INTEGER DEFAULT 0,         -- 是否完成过"间隔 ≥7 天复测正确"
  status           TEXT DEFAULT 'LEARNING',   -- LEARNING / UNVERIFIED / CLEARED / REGRESSED
  updated_at       TEXT NOT NULL
);

-- 目标与个体输入（§7.2 用）
CREATE TABLE learner_profile (
  goal_template_id TEXT,          -- 采用哪张图
  goal_text        TEXT,          -- 用户原话，用于 AI 裁剪
  baseline_json    TEXT,          -- 首次自评问卷结果（5–8 问）
  daily_minutes    INTEGER,       -- 每日时间预算
  target_date      TEXT,          -- 目标时间点
  updated_at       TEXT NOT NULL
);
```

### 3.4 路线与任务

```sql
CREATE TABLE plan (
  plan_id     INTEGER PRIMARY KEY,
  template_id TEXT NOT NULL,      -- 官方/社区/私有模板
  goal_text   TEXT,
  stages_json TEXT NOT NULL,      -- 阶段 → 节点 → 顺序（外缘算法输出，可手动调整）
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE TABLE daily_task (
  task_date   TEXT NOT NULL,      -- yyyy-MM-dd
  node_id     TEXT,               -- 主攻节点（可空=混合复习）
  kind        TEXT NOT NULL,      -- PRACTICE / REVIEW / CARD / DIAGNOSE
  plan_json   TEXT NOT NULL,      -- 具体题 id 列表 / 卡片 id 列表（确定性生成）
  done        INTEGER DEFAULT 0,
  PRIMARY KEY (task_date, kind, node_id)
);
```

### 3.5 追问会话与卡片

```sql
CREATE TABLE tutor_session (
  session_id  INTEGER PRIMARY KEY,
  question_id INTEGER,            -- 单题追问（可空=整场复盘）
  practice_session_id INTEGER,    -- 来自哪场练习
  kind        TEXT NOT NULL,      -- PER_QUESTION / POST_REVIEW / FREE
  self_reason TEXT,               -- 答错即问的快捷选项：careless / no_knowledge / never_seen
  status      TEXT NOT NULL,      -- OPEN / CLOSED
  created_at  TEXT NOT NULL
);

CREATE TABLE tutor_message (
  message_id  INTEGER PRIMARY KEY,
  session_id  INTEGER NOT NULL,
  role        TEXT NOT NULL,      -- user / assistant
  content     TEXT NOT NULL,
  hint_level  INTEGER,            -- 助手这条属于哪一级提示（1/2/3，null=自由追问）
  tokens_in   INTEGER, tokens_out INTEGER,
  created_at  TEXT NOT NULL
);

CREATE TABLE card (
  card_id     INTEGER PRIMARY KEY,
  node_id     TEXT NOT NULL,
  question_id INTEGER,            -- 出处题（必须可溯源）
  front       TEXT NOT NULL,      -- 挖空后的提示（含 ____）
  back        TEXT NOT NULL,      -- 答案要点
  source      TEXT NOT NULL,      -- ai / user
  confirmed   INTEGER DEFAULT 0,  -- AI 生成的卡片默认未确认
  due_at      TEXT,               -- 间隔调度
  stability   REAL, difficulty REAL,   -- FSRS 参数（复用现有复习调度）
  lapses      INTEGER DEFAULT 0,
  created_at  TEXT NOT NULL
);
```

### 3.6 与现有模型的关系（复用，不重复造）

| 现有能力 | 复用方式 |
| --- | --- |
| `review_state` + 复习调度（SM-2/FSRS 类） | 卡片与题目的到期调度直接复用 |
| `study_record`（含 answer_seconds / self_score / is_correct） | 证据来源：正确性、用时、自评 |
| `practice_session` / `session_question` | 复盘模式挂在一场练习之后 |
| `AiClientService`（BYOK） | 五处 AI 调用的唯一出口 |
| `AiJobEventService`（SSE 进度） | 长 AI 任务（批量打标签、路线生成）的进度展示 |
| `QuestionAiAnalysis`（单题解析） | 追问会话的"一次性讲解"降级形态 |
| 批量建题接口 + AI 导入预览确认 | 缺口补题（生成题 → 预览确认入库） |
| `/opt/pickq/config/*.json`（远端配置通道） | 官方技能模板与技能图更新，改文件即生效、不发版 |
| `topic` / `category` | 打标签的**最强提示**（见 §4.2），保持原义不动 |

### 3.7 迁移与包格式

- DB 迁移：新增 `V17__learning_path.sql`（上表 + 索引），全部新表，不改现有表结构。
- 内容包扩展（向后兼容）：`package.json` 的每题可选 `skills: [{nodeId, source, confidence}]`，顶层可选 `skillGraph: {...}`（作者自带图时）。
  - 旧客户端忽略未知字段；新客户端读到缺失字段 = 该题无标签 → 进"未分类"池正常调度（**不会被排除在复习之外**）。
  - 校验：`nodeId` 必须匹配技能图；未知 `nodeId` 保留为"待映射"而不是丢弃。

---

## 4. 标签体系（地基）

### 4.1 粒度标准（可自检）

| 指标 | 目标区间 | 越界的后果 |
| --- | --- | --- |
| 单题库节点数 | 30–150 | 太少 → 掩盖薄弱点；太多 → 路线碎片化 |
| 每节点题目数（中位数） | 5–30 | <3 → 证据不足，不能门控 |
| 层级 | 3 级（学科→模块→知识点） | 扁平化会让权重与前置关系无从表达 |
| 节点内难度分层 | 易/中/难（用现有 `score` 或难度估计） | 无法做 85% 难度定标 |

### 4.2 打标签路径：**逐题判定为主，分组映射为优化**（2026-09-14 依据真实数据修正）

> **真实数据体检（用户本机 212 题 / 2 个题库）**：有 `topic` 的题 **3 道**、有 `category` 的 0 道、
> **两者都没有的 209 道（98.6%）**；含解析 53 道、含答案 51 道。
> 结论：**"先把 topic 组映射到节点"这条主路径在真实数据上几乎无事可做**——
> 原设计把它当主路径是错的，现修正为：

| 路径 | 何时使用 | 代价 | 附加价值 |
| --- | --- | --- | --- |
| **① 逐题判定（默认）** | 题库没有 topic/category（**当前绝大多数情况**） | 20 题/次调用 | 直接可用；题干+题型+解析（若有）作为输入 |
| **② 分组映射（优化）** | 题库**确实**填了 topic/category（如从真题卷导入、作者认真维护） | 1 次调用覆盖整组 | 同类题标签**天然一致**；token 省一个数量级 |

**② 的实现细节（仅在分组存在时启用）**：

```
① 取题库中 topic/category 的唯一值（通常 10–30 个）
② 一次 AI 调用：把"唯一值列表 + 每组 2 条样例题干 + 技能节点表"映射成 {topic → nodeId[]}
   —— 同时给每个节点产出 3–8 个 keywords（用于以后新题自动归组）
③ 同 topic 的题继承映射结果（保证同类题标签一致），并落 skill_group_map 缓存
④ 组内出现明显不一致时（例如同一组里题干差异极大）→ 提示"这组要不要拆开"
```

**③ 无论走哪条路，都必须有的三层兜底**：

1. **无标签题照常调度**（进"未分类"池，按间隔重复正常出现，绝不因缺标签被排除）；
2. **待确认队列可批量审核**（AI 建议按节点聚合 + 带样例题干，几十题 <1 分钟）；
3. **抽测降级**（假掌握会被复测打回）+ **用户标注优先**（人工标注过的题不再接受 AI 建议）。

**成本口径**（逐题判定，节点表约 30 节点 ≈ 600 token/次）：

| 题库规模 | 调用次数 | 输入 token（估） | 输出 token（估） |
| --- | --- | --- | --- |
| 200 题 | ~10 次 | ~12k | ~3k |
| 1000 题 | ~50 次 | ~60k | ~15k |
| 3000 题 | ~150 次 | ~180k | ~45k |

**这是一次性成本**（新题增量才再跑），且按 BYOK 的常见价位属于"几毛钱"量级；
分组映射路径可把 1000 题压到 1–3 次调用。

### 4.3 待确认队列与批量确认

- AI 结果写入 `question_skill(source='ai', confirmed=0)`，按**节点分组**展示：
  "`导数应用`：38 题（其中 3 题你之前标成了『函数』）→ [全部确认] [改节点] [只保留高置信]"
- 交互目标：**几十题 <1 分钟**（批量、不逐题点）。
- 未确认的后果：只影响"门控"与"覆盖缺口统计"的严格度，**不影响题目出现在练习/复习里**。

### 4.4 标签纠错（数据驱动）

- 触发：某题被标为节点 A，但**该题错误率显著高于用户在 A 上的表现**（例如 A 掌握度 0.9、该题 3 次错 2 次）→ 进纠错队列。
- 呈现："这 5 道题可能不在『函数』里，要移到『导数应用』吗？"（一键批量）。
- 反向也用：同一节点下表现差异极大的两组题 → 提示"这个知识点可能该拆成两个"（**社区养图**的输入之一）。

### 4.5 随包分发与广场质量分

- 作者确认的标签写进内容包 → 分享/发布后带着标签走 → **用心的题库更实用**。
- 广场质量分（用于排序、筛选、卡片展示）：

```
quality = 0.35 × 标签覆盖率（有确认标签的题 / 总题数）
        + 0.25 × 解析完整度
        + 0.20 × 答案完整度
        + 0.10 × 来源标注（来源/年份/卷别）
        + 0.10 × (1 − 疑似重复率)
```

- 展示口径要用户能懂："标签完整 92% · 含解析 · 来源：2024 国考"。
- 追加筛选/排序维度：题数区间、是否含解析、标签覆盖率、更新时间。
- 这一条同时是**广场改版**的输入，具体信息架构见 `docs/plaza-redesign-requirements.md`。

### 4.6 成本预算（BYOK，一次性 + 增量）

| 任务 | 批量 | 单次 token（估） | 2000 题库总量 |
| --- | --- | --- | --- |
| topic → 节点映射 | 1 次 | ~4k in / 2k out | 1 次调用 |
| 无 topic 分组提议 | 1 次 | ~8k / 2k | 1 次 |
| 逐题/分批归组 | 30 题/次 | ~6k / 1.5k | ~30–60 次 |
| 新题增量 | 30 题/次 | 同上 | 仅新题 |

缓存策略：映射表落库，题目内容未变则永不重算（用题干 hash 判定）。

---

## 5. 掌握度与门控

### 5.1 证据（每次作答产出）

```
attempt = {
  questionId, nodeId,
  correct: 0|1,
  seconds,                     // 用时（相对该题中位用时归一）
  hintLevel: 0|1|2|3,          // 提示楼梯用到第几级
  askedTutor: 0|1,             // 是否追问过
  daysSinceLastPractice,       // 距上次练同节点
  selfScore                    // 主观题自评（复用现有字段）
}
```

### 5.2 掌握度公式（确定性，可离线）

```
独立度   independence = clamp(1 − 0.15×hintLevel − 0.25×askedTutor, 0.30, 1.00)
时间权重 recency      = exp(−daysSinceLastPractice / 30)
间隔加分 spacingBonus = (correct AND daysSinceLastPractice ≥ 7) ? 1.20 : 1.00
单次证据 e            = correct × independence × recency × spacingBonus

mastery = Σ(e_i × w_i) / Σ(w_i)        // 取最近 K=8 次作答，w_i = 1（近 3 次 w=1.5）
```

- 时间/粗心证据也算：`seconds` 远低于该题中位用时且答错 → 该次 `correct=0` 但标记为"疑似粗心"，**不计入节点掌握度**（只计入题目级统计）。这直接对应"答错即问"的 `careless` 选项。

### 5.3 门控规则

```
node_cleared(node) =
     可练习题数(节点) ≥ 3                                   -- 否则 UNVERIFIED
 AND mastery ≥ 0.85
 AND 独立正确次数 ≥ 3                                       -- hintLevel=0 且 askedTutor=0
 AND last_spaced_ok = 1                                     -- 间隔 ≥7 天复测仍正确
 AND 独立作答比 ≥ 0.70                                       -- 独立度 ≥0.9 的作答占比

status: LEARNING（在练） / UNVERIFIED（证据不足，题库缺题） / CLEARED（过关） / REGRESSED（抽测掉下来）
```

- **UNVERIFIED 必须显式提示**："这个知识点你只有 1 道题，无法确认掌握 → [生成题] [去找题] [先跳过]"。
- 门控阈值是可配参数，先按上表落值，之后按真实数据校准。

### 5.4 假掌握检测（抽测降级）

- 已 CLEARED 的节点：按间隔（首次 7 天、之后 21/60 天）抽 1–2 题复测（用卡片或原题变式）。
- 复测失败 → `REGRESSED`，mastery 打折（×0.6）并重新进入外缘。
- 这同时防"标签错 + 蒙对"：**假掌握会被抽测打回**。

### 5.5 难度定标（85% 规则）

- 目标：用户在当前节点的**预测成功率 80%–90%**（对应错误率 ~15% 的最优学习区）。
- 题目难度 `d_q`：初值用题型/来源/分值估计，之后用作答更新（Elo-lite）：

```
p = 1 / (1 + 10^((d_q − θ_u) / 400))
θ_u ← θ_u + K×(实际 − p)      // K=16
d_q ← d_q − K×(实际 − p)
θ_u 初值 = 400×(2×mastery − 1)（把掌握度映射到能力分）
```

- 编排：低掌握度节点**先分块集中练**，掌握后与相邻节点**交错混练**（交错对低基础者有害、对有基础者有益）。

---

## 6. 防漏刷：五层护栏

| # | 护栏 | 机制 | 依赖标签正确性？ |
| --- | --- | --- | --- |
| 1 | **错题本**（最终防线） | 错题按题目去重，与标签无关，一定被复习 | ❌ 不依赖 |
| 2 | **多标签** | 一题挂 1–4 个节点，误标只影响权重分配 | 弱依赖 |
| 3 | **门控看证据量** | 节点题数 <3 → UNVERIFIED，不假装掌握 | ✅ 依赖（但有兜底状态） |
| 4 | **抽测降级** | 从已掌握节点抽测，错了降级 | ✅ 能自动发现假掌握 |
| 5 | **覆盖地图** | "你的题库覆盖路线的 62%，8 个节点没题、12 个只有 1 题" | ✅ 把缺口摊开给用户看 |

加分护栏：**无标签题进"未分类"池照常调度**（绝不因为没标签就不练）。

---

## 7. 学习路线与练习形态

### 7.1 外缘算法（"下一步做什么"）

```
ready(node) = 所有 prereq 均 CLEARED  AND node 未 CLEARED  AND node 不 optional
next_batch  = ready 节点中按 (stage_order, weight 降序, 与目标相关性) 取前 1–3 个
每日任务     = next_batch 的新题 + 到期复习题（review_state）+ 到期卡片
```

- 每天的任务**由公式生成**（不调模型）：打开应用就有明确下一步，且**日常零 token**。

### 7.2 路线个性化（个体差异的 5 个输入）

| 输入 | 影响 |
| --- | --- |
| 逐题对错（自动） | 决定已掌握集合 → 决定外缘 → **路径位置** |
| 首次自评问卷（5–8 问） | 冷启动定位（避免前几周练已经会的题） |
| 每日时间预算 | 每日任务量与阶段跨度（**不影响顺序**） |
| 目标权重 | 节点权重（面试 → 手写题/八股提权；考证 → 考点提权） |
| 提示/追问记录 | 负向证据 → mastery 打折 → 节点回退 |

### 7.3 三种练习模式（边界必须清楚）

| 模式 | 计时 | 题量 | AI 介入 | 定位 |
| --- | --- | --- | --- | --- |
| **练习**（现有） | ✅ | 固定 | 只记录不打断；同节点连错 2 题给一条可忽略提示条 | 模考与节奏训练 |
| **复盘**（新增） | ❌ | 一场练习的错题 | **AI 主场**：一次调用诊断整场 + 逐题一句话诊断 + 追问入口 | 把错题变成收获 |
| **讲解**（新增） | ❌ | 自适应 | 随时可追问、可跳题、提示楼梯 | 学新知识点 |

### 7.4 追问与提示楼梯（交互 + 数据）

- 入口 ①：**答错即问一句**（快捷三选：看错/蒙的 · 这个知识点不会 · 完全没见过 + 可选一句补充）→ 写 `tutor_session.self_reason`。
- 入口 ②：提示楼梯（做题中）：L1 指方向 → L2 关键一步 → L3 完整解析 → L4 自由追问；每级写 `tutor_message.hint_level`。
- 入口 ③：错题本/题目详情"问老师"（事后追问）。
- 入口 ④：触发式推送（同题错 2 次 / 同节点连错 2 题）→ 顶部可忽略提示条。
- **不做**：练习过程中自动弹聊天框。
- 追问 prompt 上下文（固定顺序，控制 token）：题干 → 选项 → 正确答案 → 解析（截断）→ 我的作答与 self_reason → 该题历史（错几次、上次何时）→ 用户在**本节点**的掌握度状态（不暴露数值，用"较弱/较好"描述）→ 最近 4 轮对话。

### 7.5 闪卡（掌握度检测）

- **生成**：从**解析**里抽"关键结论/易错点/判别口诀" → cloze 挖空卡；批量 20 题一次调用；`source='ai'` 默认未确认，用户可编辑/删除。
- **可溯源**：每张卡必须带出处题号，卡片界面点开可回原题。
- **调度**：复用 FSRS/SM-2；答错 → `lapses+1` 且该节点 mastery 打折。
- **门控参与**：卡片只作**抽测**证据（`last_spaced_ok`），不能单独把节点判为 CLEARED。
- 区分：题目测**再认**，卡片测**回忆**，两者互补。

---

## 8. 模板系统（开放、可编辑、可分享）

### 8.1 模板 JSON 格式

```json
{
  "schemaVersion": 1,
  "templateId": "official.ai-fullstack",
  "name": "AI 时代的现代化全栈",
  "version": "2026.09",
  "goalHint": "3–6 个月内胜任全栈 / AI 应用开发岗位",
  "source": { "type": "official", "updatedAt": "2026-09-14", "license": "自建" },
  "stages": [
    {
      "id": "s1", "name": "前端基础",
      "nodes": [
        { "id": "fe.html", "name": "HTML 语义化", "weight": 1.0, "prereq": [], "keywords": ["语义标签", "表单", "可访问性"] },
        { "id": "fe.css",  "name": "CSS 布局",   "weight": 1.0, "prereq": ["fe.html"], "keywords": ["Flex", "Grid", "响应式"] }
      ]
    }
  ]
}
```

- `node_id` 全局唯一且**发布后不改**（改名只改 `name`）。
- `keywords` 用于自动把新题/题库映射到节点（§4.2 的 ②）。
- 私有模板 `source.type = "private"`，不入远端。

### 8.2 三种来源与分发

| 类型 | 存在哪 | 谁能改 | 更新方式 |
| --- | --- | --- | --- |
| official | 远端配置 + 应用内置预置（离线可用） | 我们 | `https://pickq.cn/config/skill-templates.json`（索引）+ 每模板单文件；改文件即生效，客户端 1 小时缓存 |
| community | 广场（可下载的"路线模板"） | 社区作者 | 与题库并列的内容分发（后续阶段） |
| private | 用户本机 | 用户 | 随时编辑、导入导出、分享 |

### 8.3 官方模板维护（半自动）

1. 定期让模型读新 JD / 技术趋势，产出 **diff 建议**：`+ 新增节点 / ~ 合并节点 / − 废弃节点`，每条带理由与被引用的来源；
2. 人工 review 后上传到远端配置（**不发版**）；
3. 版本号 + 本地缓存 + 更新提示：官方图更新时提示"是否合并（保留你的本地改动）"。

### 8.4 用户编辑（**常态化，不限"未命中模板时"**）

- 任何时候都能"基于官方模板分叉一版我的"，改动保留。
- 编辑形态：**表格式**（阶段 → 节点 → 前置节点 + 权重 + 是否加分项），拖拽排序；**不做自由画布**。
- **粘贴生成**（把门槛降到接近零）：粘贴一段 Markdown 大纲（来自知乎/B站/roadmap 文字）→ AI 解析成节点图 → 人工确认。
- 支持从文件 / 链接导入他人模板；导出分享。
- **授权纪律**：模板自己写。中文的 `TeamStuQ/skill-map` 未声明许可证（默认不可商用），`roadmap.sh` 系列为 CC BY-NC-SA —— 只可参考其知识点划分思路，**不复制其文案**。

---

## 9. AI 调用清单（唯一出口：`AiClientService`）

| 用在哪 | 触发频率 | 输入 | 输出（结构化） | 预算（in/out） |
| --- | --- | --- | --- | --- |
| 标签提议 | 建库/体检时，30 题一批 | topic 映射表 + 题干 + 解析摘要 | `[{questionId, nodes:[{nodeId, confidence}]}]` | ~6k / 1.5k |
| 路线裁剪 | 设目标时 1 次 | 模板图 + 自评 + 题库覆盖统计 | 阶段顺序调整 + 节点取舍 + 理由 | ~3k / 2k |
| 复盘诊断 | 每场练习 1 次 | 本场错题清单 + 每题作答 + 节点标签 | 简述 + 薄弱节点 + 逐题一句话诊断 + 建议动作 | ~4k / 1.5k |
| 追问对话 | 每轮 | §7.4 的固定上下文 | 文本（流式） | ~2k / 0.8k |
| 卡片生成 | 20 题一批 | 解析文本 | `[{questionId, front, back}]` | ~5k / 2k |

- 全部**结果落库缓存**（题干/解析 hash 未变则不重算）。
- 长任务（标签批量、路线生成）走现有 SSE 进度通道。
- 强调：掌握度、外缘、门控、调度、难度选点**一律公式算**，不调模型 → 日常使用零 token。

---

## 10. 广场质量分（与改版联动）

- 排序/筛选用 §4.5 的 `quality`，并在卡片上以人能看懂的方式展示。
- 新增筛选：题数区间 / 含解析 / 标签覆盖率 / 更新时间 / 技能节点（"含『React Hooks』的题库"）。
- 未来扩展：**路线模板**作为一种可分享内容（与题库并列），以及"按路线推荐题库"。
- 具体落地见 `docs/plaza-redesign-requirements.md`（广场信息架构与质量信号的展示口径）。

---

## 11. 分阶段实施与验收

| 阶段 | 内容 | 验收标准 | 依赖 |
| --- | --- | --- | --- |
| **0 标签地基** | 技能图 + question_skill + **逐题判定（默认）+ 分组映射（有 topic 时）** + 待确认队列 + 覆盖地图 | 抽样准确率 ≥90%（当前真实数据只有 212 题，先按用户题库的 172 题做）；同类题标签一致率 100% | 无 |
| **1 追问入口** | 答错即问 + 提示楼梯 + 事后抽屉 + 复盘诊断 + **流式输出** | 复盘诊断能指出薄弱节点；追问上下文完整；流式首字 <2s | 阶段 0（诊断需要节点标签） |
| **2 只读路线** | 模板（3–5 个官方）+ 外缘 + 缺口清单 + 每日任务（不自动出题） | 用户能看懂"今天做什么""还缺什么"；日常零 token | 阶段 0 |
| **3 掌握度闭环** | 门控 + 抽测降级 + 闪卡 + 自适应调整 | UNVERIFIED 状态正确触发；假掌握能被抽测打回 | 阶段 2 |
| **4 生态** | 广场质量分与路线模板分享、缺口生成题、按路线推荐题库 | 质量分能区分用心维护的题库 | 广场改版完成 |

**阶段 0 的先行验证（不写完整功能也能做）**

0. **已完成（2026-09-14）**：真实题库体检 —— 212 题里只有 3 题有 `topic`、0 题有 `category`，
   98.6% 的题两个字段都空 → 打标签必须**以逐题判定为主**（见 §4.2 修正）；
1. 人工给 200 道真实真题打知识点标签 → 与 AI 结果比对（决定后面一切是否可靠）；
2. 3–5 人试用"做完一场 → 收到诊断 → 追问"（验证留存假设）；
3. 拿 10 个高频目标对照真实题库，估算模板命中率与缺口规模。

---

## 12. 风险与明确不做

**风险与对策**

| 风险 | 对策 |
| --- | --- |
| 门控过严 → 用户卡死 | 必须提供"我懂了 → 跳过"出口并记录，用于校准阈值 |
| AI 生成的卡片/诊断有错 → 失去信任 | 卡片默认未确认 + 必须标出处；诊断只作为"建议"，不作为判定 |
| 标签粒度失控 | §4.1 的量化区间 + 体检报告在题库页可见 |
| 模板维护成本 | 远端配置 + AI diff 提议 + 人工点确认 |
| token 成本（BYOK 敏感） | §9 的五处调用 + 缓存 + 日常零调用 |
| 用户不看计划 | 每日任务必须"打开就能做"（一键开始），并显示路线进度条 |

**明确不做**

- 通用聊天框（会被拿去和 ChatGPT / DeepTutor 比）；
- 多智能体编排、代码执行沙箱；
- 向量库 RAG（阶段 4 之后再评估；题目是短文本，标签 + 全文检索已足够）；
- 语音 / 多模态对话；
- 让模型判断"你掌握了吗"（掌握度必须可解释、可复现）。

---

## 13. 待定问题（实现前需拍板）

1. 门控阈值默认值（mastery 0.85 / 独立正确 3 次 / 间隔 7 天）是否先按此落值，之后按数据校准？
2. 掌握度是否对用户显示**数值**，还是只用"绿 / 黄 / 红 + 文字"（建议后者，避免焦虑与误读）？
3. 复盘是**强制**（一场练习结束必须看一眼）还是可跳过？
4. 题库页的"体检报告"入口放哪里（题库详情页顶部 / 我的作品页）？
5. 卡片是否允许复制/导出（Anki 用户会想要），以及导出格式（CSV / APKG）？
