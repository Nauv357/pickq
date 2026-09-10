# 安全政策（Security Policy）

拾题（PickQ）是一个本地优先的桌面应用：题库、做题记录与 AI 配置都存在你自己的电脑上（默认 `~/.tiku`）。
我们认真对待安全问题，也感谢负责任地披露。

> English summary: [below](#english-summary).

---

## 1. 支持范围

| 版本 | 是否维护 |
| --- | --- |
| 桌面端**最新发布版**（当前为 `0.1.17`） | ✅ 接收并修复安全问题 |
| 桌面端更早的 `0.1.x` | ⚠️ 仅当问题在最新版仍可复现时才处理；否则请先升级到最新版再验证 |
| 从源码自行构建的版本 / 已修改的分支 | ❌ 不保证；请先在官方发布版上复现 |
| 官网与题库广场（`pickq.cn`） | 属于同一项目的在线服务，**在范围内**，但请注意它不在本仓库公开（见 §5） |

桌面端当前版本号以 `tauri/src-tauri/tauri.conf.json` 为准；应用内「设置 → 关于」也会显示版本与更新入口。

---

## 2. 如何上报

**请优先使用私密渠道，不要先开公开 Issue。**

1. **推荐：GitHub Security Advisory（私密漏洞报告）**
   在本仓库的 **Security → Report a vulnerability** 页面提交私密报告。
   > 维护者待补充：仓库公开地址与 Advisory 入口链接。发版规范（`docs/release-notes-guide.md`）
   > 里记录的仓库路径是 `Nauv357/pickq`，据此推导为 `https://github.com/Nauv357/pickq/security/advisories`
   > ——**请维护者确认后替换本段**，并同时在仓库 Settings 中打开 Private vulnerability reporting。
2. **备选：先通过公开 Issue 请求建立私密联系渠道**——只写「我有一个安全问题想私密上报，
   请提供联系方式」，**不要**在 Issue 里附带任何细节、复现步骤、日志或截图。
3. 若问题同时影响官网 / 题库广场，请在报告里注明「影响线上服务」。

> 维护者待补充（清单）：
> - 真实的安全联系邮箱（或安全页面链接）——本文件刻意不写任何邮箱，避免编造或写错地址；
> - 确认并替换 §2 中的仓库 / Advisory 链接；
> - 可选的 PGP / 加密沟通方式（如有）。

### 请不要在公开 Issue 中披露

漏洞细节、复现步骤、PoC、抓包内容、受影响的接口与参数、日志中的 token / Key、
以及「某处没有做鉴权」这类结论，都会直接变成攻击说明书。

**这是一条硬要求**：在维护者确认可以公开之前，请不要在 Issue、PR、讨论区、社交平台或群聊中披露。
如果你已经不小心公开，请立即告知，我们会优先处置。

---

## 3. 我们已知的安全设计与边界（让上报者先了解范围）

以下是**设计意图**，写在这里是为了让你判断「某个行为是否属于缺陷」，而不是给出攻击路线。
本文件不写具体阈值、参数与内部实现细节（那些属于实现，且不适合公开详述）：

- **本地后端只监听回环地址**：桌面壳拉起后端时固定传入
  `--server.address=127.0.0.1 --server.port=0`（随机端口），局域网内其他设备访问不到；端口每次启动都变。
- **本地后端不做登录鉴权**：因为它只服务本机用户、只监听回环地址。因此
  「同一台电脑上的其他本地进程能调用本地 API」**属于已知设计边界**，不算漏洞
  （能读 `~/.tiku` 的进程本来就能直接读数据文件）。反之，如果发现有办法让**局域网/互联网**上的
  第三方访问到本地后端，那是需要立刻上报的问题。
- **广场账号密码**：只存加盐派生的哈希（scrypt），不可逆；校验使用恒定时间比较。
- **会话凭证**：会话 token 在服务端只存其哈希，数据库泄露也无法直接冒用；网页端会话放在
  `httpOnly` cookie 中（生产环境带 `secure`），桌面端走 `Authorization: Bearer` 经本地代理转发。
- **登录、注册、找回密码、评论、收藏、举报、发布等接口有速率限制**：超限返回 429。
- **注册有人机验证**（Turnstile），桌面端与网页端都走同一校验。
- **内容包只读元数据，不解析题目内容**：导入 / 发布前的体检只取顶层元数据（标题、版本、数量等），
  不解压图片、不物化题目内容，尽量降低「用恶意题库文件打内存/解压炸弹」的面；
  但这**不等于**内容包格式已被视为可信输入——解析器仍按不可信输入对待。
- **AI 调用使用你自己的 API Key**：Key 只存在本机数据目录，由你自行决定是否填写；
  请求直接发往你所配置的模型服务商，不经过我们的服务器。
- **自动更新**：更新清单与安装包经 HTTPS 从项目自己的更新频道获取（壳内置清单地址），
  安装包在安装前做完整性校验。

不在范围内的典型项（请勿作为漏洞上报）：

- 需要**已获得本机文件系统读取权**或**已运行恶意本地程序**才能实现的影响（见上文的本地边界）；
- 依赖上游组件（Spring Boot / Tauri / H2 / Vue / Nuxt 等）的已知漏洞——欢迎告知，我们会跟进升级，
  但请附上游的公告编号，便于判断影响面；
- 第三方 AI 服务商的行为、计费与数据政策；
- 你自己泄露 API Key（请到模型服务商处吊销并更换）；
- 仅影响「从源码自行修改的版本」的问题。

---

## 4. 响应流程与时间预期

| 阶段 | 我们的动作 | 预期时间（以自然日计） |
| --- | --- | --- |
| 确认收到 | 回执并确认已收到报告 | 3 个工作日内 |
| 初步评估 | 判断是否成立、影响范围（仅桌面端 / 也影响线上）、严重程度分级 | 7 个工作日内 |
| 修复与验证 | 在私有分支修复并验证；必要时先出补丁版 | 视严重程度，普通问题随下个版本发布，严重问题尽快出补丁版 |
| 发布与告知 | 通过 GitHub Releases 与更新频道发布；**发版公告按 `docs/release-notes-guide.md` 的口径只写功能变化，不写安全细节** | 与修复同步 |
| 公开披露 | **在修复版本发布之后**，与报告者协商公开内容与署名 | 与报告者协商 |

说明：

- 上述时间为**目标**而非承诺；这是一个小规模的开源项目，维护者可能因个人原因延迟，
  但会尽量保持沟通、不让报告石沉大海；
- 如需协调披露时间（例如你希望先做其他系统的加固），请在报告里说明；
- 如果你希望被致谢，请告知希望使用的署名方式；不愿署名也完全可以。

---

## 5. 关于官网与题库广场

官网和题库广场（`pickq.cn`）是同一项目的**在线服务**，但其代码与部署资产**不在本仓库**
（`web/`、`deploy/`、`scripts/deploy/` 均不入 git）。因此：

- 影响线上服务的安全问题同样欢迎上报，走 §2 的私密渠道；
- 但请不要期待在本仓库的源码里能找到对应实现，也不要在公开 Issue 中讨论线上服务的内部细节
  （服务器配置、备份策略、CDN、Nginx 规则等属于运维内部信息）；
- 本文件只描述桌面端与线上服务**共同的设计边界**，不描述线上基础设施的实现。

---

## 6. 安全相关改动的提交约定（给贡献者）

如果你自己提交安全修复（已与维护者沟通过）：

- 提交信息用 `security` 前缀，或用 `fix` 但**不要**在提交信息里写可被利用的细节；
- 发版公告按 `docs/release-notes-guide.md`：**只写用户可感知的功能变化，不写安全细节与内部实现**；
- 相关约定见 [`docs/conventions.md`](docs/conventions.md) 与 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

---

## English summary

PickQ is a local-first Windows desktop app; all data (banks, practice records, AI keys) is stored on
your own machine (`~/.tiku` by default).

- **Supported**: the latest released desktop version (`0.1.17`) and the project's online services
  (`pickq.cn`). Older releases are handled only when the issue still reproduces on the latest version.
- **Reporting**: use **GitHub → Security → Report a vulnerability** (private) for this repository.
  If that is unavailable, open a public issue that says **only** "I have a security issue I would like
  to report privately — please provide a contact channel", with **no** details.
  *(Maintainer TODO: confirm the repository / advisory link — the release guide records the repo path
  `Nauv357/pickq` — and add a real security contact address; this document intentionally contains no
  e-mail address.)*
- **Please never disclose vulnerability details publicly** (issues, PRs, discussions, social media)
  before the fix is released and we have agreed on disclosure.
- **Known design boundaries**: the local backend binds to `127.0.0.1` on a random port and has **no
  authentication** (it serves only the local machine), so "another local process can call the local
  API" is a known, accepted boundary; anything that exposes it to the LAN or the internet must be
  reported immediately. Plaza passwords are stored as salted hashes, session tokens are stored hashed
  server-side, login/registration/comment endpoints are rate limited, registration requires human
  verification, and content-package inspection reads metadata only (it never materialises question
  content or unpacks images). Details, thresholds and internal implementations are deliberately not
  published here.
- **Response**: acknowledgement within 3 business days, initial assessment within 7, fix timeline
  depending on severity, coordinated disclosure after the fixed release.
- **Out of scope**: issues requiring local filesystem access, upstream dependency CVEs (tell us anyway,
  with the advisory ID), third-party AI providers, and your own leaked API keys.
