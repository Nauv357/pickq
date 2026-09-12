# 前端界面规范（一体化设计约定）

> 目的：让用户在任意页面都能凭**位置记忆**找到同一个功能，而不是每页重新找一遍。
> 本文是硬约定（可被脚本检查的部分优先做成脚本，见 §6）；视觉细节不在本文范围。
>
> 状态：**规范已定 + 公共组件骨架已落地**（2026-09-12）。迁移按 §7 分阶段进行，
> 未迁移页面维持现状，但**新增页面必须遵守本文**。

---

## 1. 为什么要统一（现状问题，都有具体出处）

用户反馈"很难在题库编辑页找到重点功能，按钮太多要想很久"。审计后的实际数据：

| 现象 | 现状 |
| --- | --- |
| 页面头结构 | 3 套不同写法（`page-title` 有的 24px、有的 26px，位置与对齐各不相同） |
| 主操作位置 | 4 个不同位置：页头右侧 / 工具栏 / 列表项内 / 页面右下 |
| 一屏主按钮数量 | 0～3 个不等（同一页面内 `btn-primary` 最多出现 13 次） |
| 分段控件 | 4 种实现（`.tab` / `.type-tab` / `el-radio-group` / 手写按钮组） |
| 空态 | 11 个类名，5 处只有"暂无数据"、没有下一步出口 |
| 分页条 | `.pager` 在 5 个文件里各写一份，显示条件与样式不一致 |
| 破坏性操作确认 | 19 处手写确认框，其中 4 处**完全没有确认**（删题 / 删材料 / 删评论） |
| 确认框按钮文案 | 20 处硬编码中文，英文界面下确认按钮仍写"删除/取消" |
| i18n | 14 个 key 只用了没定义 → 界面直接显示 `historyTitle` 这类裸 key |

结论：不是"页面不好看"，而是**同一个动作在不同页面位置、名字、确认方式都不同**，
用户每进一个新页面都要重新建模。统一骨架的收益是"学一次，处处适用"。

---

## 2. 页面骨架（固定五段，顺序不可变）

```
┌───────────────────────────────────────────────────────────┐
│ 1 PageHeader   ← 可选返回 | 标题 + 标签 | 一句话说明 | 摘要 | 右侧操作（主操作最右）
├───────────────────────────────────────────────────────────┤
│ 2 Toolbar      ← 左：搜索 / 筛选 / 视图切换    右：计数 / 排序 / 次要操作
├───────────────────────────────────────────────────────────┤
│ 3 Content      ← 列表 / 卡片 / 图表；三种状态：加载(骨架) / 空(EmptyState) / 有数据
├───────────────────────────────────────────────────────────┤
│ 4 Pager        ← 仅内容超过一页时出现，居中
└───────────────────────────────────────────────────────────┘
   （BulkBar：进入多选时出现，吸底或替换 Toolbar，退出多选即消失）
```

- 只有这五段的位置固定；段内具体长什么样由页面决定。
- 详情页（如题库详情）同样适用：PageHeader 之后是 Toolbar + Content。

---

## 3. 硬规则（R1–R10）

| # | 规则 | 为什么 |
| --- | --- | --- |
| **R1** | 每屏**最多一个**主按钮（`btn-primary`），且必须落在 PageHeader 最右 | 用户视线动线是"左上标题 → 右上下一步"，主按钮位置固定才不用思考 |
| **R2** | 次级操作超过 3 个 → 收进「更多」；不与主操作平铺 | 平铺 = 每个都要读一遍才知道该点哪个 |
| **R3** | 破坏性操作（删除/覆盖/重置/退出登录）**必须二次确认**；确认按钮红色 + 文案等于动作本身（"删除"而不是"确定"）；取消按钮文案统一"取消" | 盲确认 = 没有确认 |
| **R4** | 空态必须给一条出路（一个可点的下一步），并说清"为什么空" | "暂无数据"是死路 |
| **R5** | 列表页固定：Toolbar 左搜索、左筛选、右计数；Pager 在 Content 下方居中；每页条数一致（默认 50） | 找搜索框不该靠眼睛扫 |
| **R6** | 同一动作在所有页面同一个词：删除/编辑/导出/开始做题/返回题库/确定/取消 | 词表见 §5（`common.*`） |
| **R7** | 同一实体的编辑统一用**大弹窗**（不跳页、不在页面流里插入表单） | 编辑是"临时聚焦"，不应打断列表上下文 |
| **R8** | 反馈统一用 `ElMessage`；错误文案由 axios 拦截器统一给，页面不重复包装 | 避免同一错误弹两次 |
| **R9** | 用户可见文案一律走 `t()`；公共组件自带词典或走 `common.*` | 英文界面不出现裸 key / 中文 |
| **R10** | 图标按钮必须有 `title`（走 i18n），`title` 写"点了会发生什么" | 图标语义不唯一 |

**R7 的大弹窗规格**（题库详情编辑已按此实现，可作为模板）：

```html
<el-dialog
  v-model="open"
  class="editor-dialog"
  :width="mode === 'edit' ? 'min(calc(100vw - 236px), 1040px)' : 'min(96vw, 1120px)'"
  top="4vh"
  :z-index="1900"
  :show-close="false"
  :close-on-click-modal="false"
  :close-on-press-escape="false"
  destroy-on-close
  append-to-body
>
```

- 弹窗只负责"外框 + 唯一滚动容器"，表单自带 sticky 头与吸底操作条（见 `main.css` 的 `.editor-dialog` 规则）。
- 编辑态右侧留 212px 通道给悬浮组件（题号盘），所以宽度用 `100vw - 236px`。
- 关闭只能走表单自己的按钮（`show-close=false` + 关遮罩/ESC），保证脏数据确认不会被绕过。
- 悬浮层序：弹窗/遮罩 `1900` → 悬浮组件 `1990` → Element Plus 后续弹窗（确认框等）`2000+`。

---

## 4. 公共组件

| 组件 | 位置 | 作用 | 关键约束 |
| --- | --- | --- | --- |
| `PageHeader` | `src/components/PageHeader.vue` | 统一页头 | props：`title`(必填) / `desc` / `backTo` / `backText` / `note`；插槽：`title-extra` / `meta` / `actions`（主操作放最右） |
| `EmptyState` | `src/components/EmptyState.vue` | 统一空态 | props：`icon` / `title` / `desc` / `compact`；默认插槽放出路按钮 |
| `Pager` | `src/components/Pager.vue` | 统一分页 | props：`page` / `size` / `total` / `pageSizes` / `small`；`total > size` 才渲染 |
| `useConfirm()` | `src/composables/useConfirm.js` | 统一确认框 | `useConfirm(t)` → `{ confirm, confirmDanger }`，返回 `Promise<boolean>`；`confirmDanger` 用红色确认按钮且按钮文案=标题 |

规划中（迁移过程中按需补，避免过度设计）：
`Toolbar`、`MoreMenu`（R2）、`BulkBar`、`ImmersiveBar`（R7 之外的"沉浸式单题"入口）。

---

## 5. 文案词表（全局 `common.*`）

`src/i18n/index.js` 的 `messages` 里有一份**全局共用词表**（其余词典仍按组件局部维护，见 `docs/conventions.md`）：

```
common.ok 确定 / OK          common.cancel 取消 / Cancel
common.close 关闭 / Close     common.save 保存 / Save
common.delete 删除 / Delete   common.edit 编辑 / Edit
common.more 更多 / More       common.moreOps 更多操作 / More actions
common.back 返回 / Back       common.reset 重置 / Reset
common.retry 重试 / Retry     common.search 搜索 / Search
common.loading 加载中…        common.thinkAgain 再想想 / Keep it
common.opFailed 操作失败，请稍后重试
```

规则：**跨页面复用的词进 `common.*`，页面专有文案留在组件局部词典**。
局部词典解析不到时回落到 `common.*` 与 `fallbackLocale: 'zh-CN'`，所以永远不会显示裸 key
（前提是 key 真的存在——由 §6 的脚本保证）。

---

## 6. 可检查性（把规范变成脚本，不靠自觉）

前端没有单测基建，但下面这些检查已经落地，**改前端后必跑**：

```powershell
cd frontend
npm run check:ui      # 伪插值 + i18n 重复键 + 未定义 key（纯静态，秒级）
npm run check:i18n    # 模板里未走 t() 的硬编码中文（当前 240 行，迁移中逐页消除）
npm run smoke:editor  # 编辑大弹窗冒烟（Playwright + 本机 Chrome，需先起 dev server）
```

| 脚本 | 抓什么缺陷 | 实例 |
| --- | --- | --- |
| `check-pseudo-interp.mjs` | 属性值伪插值 `title="{{ t('x') }}"`、双冒号属性 `::title=` | 题库详情 5 处 title/placeholder 原样显示 `{{ t('delete') }}` |
| `check-i18n-dup.mjs` | 同一词典里同名 key 重复（后者静默覆盖前者） | `AiImportDialog` 的 `target` 中英各重复一次 |
| `check-i18n-keys.mjs` | `t('x')` 用了但词典没定义 → 界面显示裸 key | `SessionHistoryView` 的 `historyTitle`/`sessionsTotal` 等 14 个 |
| `check-hardcoded-zh.mjs` | 模板里未走 i18n 的中文（英文界面直接显示中文） | 当前 240 行 / 10 个文件，属迁移清单 |
| `smoke-editor-dialog.mjs` | 弹窗层级、滚动容器、脏数据确认、题号盘位置等**只能靠跑**才能确认的行为 | 28 项断言（1440×900 / 1920×1080 两档） |
| `shot-routes.mjs` | 给若干路由拍图，人工核对骨架（假数据，不需要后端） | `<out>/<route>.png` |

> 说明：`check-i18n-keys.mjs` 直接 eval `useI18n({ messages })` 里的对象字面量再展开成
> `a.b.c` 键集，比手写花括号扫描可靠（三目表达式、数组、嵌套对象都不会误判）。
> `smoke` / `shot` 脚本只拦 `pathname` 以 `/api/` 开头的请求——不能用 `**/api/**` 通配，
> 否则会拦掉 Vite 自己的 `/src/api/*.js` 模块请求导致白屏。

---

## 7. 迁移阶段（每阶段独立可交付、可回归）

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| P0 | 题库详情编辑改大弹窗（R7）+ 4 个确定性缺陷 | ✅ 本轮完成 |
| P1 | 建公共组件（PageHeader / EmptyState / Pager / useConfirm）+ 全局词表 | ✅ 本轮完成 |
| P2 | 迁移设置页、统计页、练习历史页（页头 / 空态 / 分页） | ✅ 本轮完成 |
| P3 | 迁移题库列表、我的作品、发现题库、AI 任务页；补 `Toolbar` / `MoreMenu` | 待办 |
| P4 | 练习页 / 打印页（交互重，最后动）；模板硬编码中文清零 | 待办 |

验收口径（每阶段都适用）：

1. `npm run check:ui` 全绿；
2. `npm run build` 通过；
3. 改动页面用 `shot-routes.mjs` 产出前后对比图人工确认；
4. 涉及交互（弹窗/确认/分页）的改动补 `smoke:*` 断言；
5. 不改变后端接口与数据。

---

## 8. 明确不做

- 不引入 UI 组件库/原子化 CSS（项目已有 Element Plus + 手写 CSS 的既有风格）。
- 不为了统一而重做视觉（配色、圆角、间距、字号维持现状）。
- 不为低频页面造组件（先有 2 个以上真实使用方再抽象）。
