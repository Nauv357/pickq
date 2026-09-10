# 更新公告撰写规范（发布桌面版时遵守）

桌面版每次发布都会在更新弹窗里展示 `notes`（`/opt/pickq/updates/latest.json`）。
**公告面向普通用户，只写他们能感知到的功能变化。**

## 应当写

- 新功能、界面变化、易用性改进（"新增：题库广场账号可在应用内登录——收藏、评论、点赞"）
- 用户会遇到的修复（"修复：导入带表格的 PDF 时表格显示异常"）
- 需要用户知晓的行为变化（"导入后题号按卷内顺序重排"）

## 不要写

- **安全细节**：如"本地后端改为仅监听 127.0.0.1""加强登录限流""升级依赖修复漏洞"——
  这类描述等于公开告知攻击面或曾经的弱点，用户也不需要知道；
- **运维/基础设施**：服务器迁移、备份策略、CDN、Nginx 配置等内部事项；
- **内部实现**：技术栈升级、重构、代码质量、内部工具；
- 任何用户看不到也感知不到的东西。

## 措辞

- 面向用户收益，而非实现手段（写"支持用邮箱或用户名登录"，不写"登录接口增加邮箱回退匹配"）；
- 中文优先、一到三行，可用"新增 / 优化 / 修复"开头。

## 操作

发布时两条命令链：

1. `tauri/build-desktop.ps1` 产出安装包；
2. `deploy/publish-update.ps1 -Notes "..."`（注意：脚本使用密码登录，服务器已禁用密码登录，
   现在改用密钥上传：`scp -i ~/.ssh/id_ed25519_pickq` 上传安装包与 `latest.json` 到
   `/opt/pickq/updates/`，并同步 `-AlsoFullDownload` 对应的 `/opt/pickq/downloads/`）。

若公告写错需要更正，直接改服务器上的 `latest.json`（用户端下次检查更新即拉取新内容）。

---

# 发版检查清单（每次发布逐条执行）

1. **升版本号**：`tauri/src-tauri/tauri.conf.json` 与 `Cargo.toml`（两处必须一致）。
2. **构建**：`frontend` 与 `web`（如官网有改动）分别 `npm run build`；后端 `mvn -q -DskipTests package`（先设 `JAVA_HOME` 为 JDK 21）。
3. **打包**：`tauri build --bundles nsis`（注意 `tauri/build-desktop.ps1` 会因 node 的 stderr 输出被误判为失败，必要时手动执行 tauri CLI）；再打便携版 zip。
4. **上传**：
   - 更新频道（脚本生成或手写 manifest）：`shiti-<版本>-x64-setup.exe` + `latest.json` → `/opt/pickq/updates/`
   - 全量下载：`拾题_<版本>_x64-setup.exe` + `拾题-便携版.zip` → `/opt/pickq/downloads/`；**删除上一版安装包**
   - 服务器已禁用密码登录，上传用 `scp -i ~/.ssh/id_ed25519_pickq`（`publish-update.ps1` 的 pscp 密码方式已失效）
5. **更新官网下载链接**：`web/pages/index.vue` 里的 `/downloads/拾题_<版本>_x64-setup.exe` → 重新打包 `web-src.zip` → 服务器构建部署。
   ⚠️ 替换前先确认旧版本号字符串确实存在（曾出现"替换源不匹配、静默没改动"，导致下载页停留在旧版本、甚至指向已删除的文件而 404）。
6. **线上验证**（必做）：`latest.json` 版本号、官网首页含新文件名、安装包与便携版链接均返回 200、`https://pickq.cn/` 返回 200。
7. **GitHub Release（必做，与官网同步发布）**：以 ASCII 名上传安装包与便携版作为备用渠道
   （大陆直连 GitHub 不可用，仅供有代理的用户/海外用户；上传必须走代理）：
   - 资产名：`PickQ_<版本>_x64-setup.exe`、`PickQ_<版本>_portable.zip`
   - tag：`v<版本>`；Release 说明写功能更新 + 两个下载链接
   - 走 API：`POST /repos/Nauv357/pickq/releases` 建 Release，再用 `uploads.github.com/.../releases/{id}/assets?name=...` 上传（`-x http://127.0.0.1:<代理端口>`）
   - 上传后确认两个资产出现在 Release 页面
8. **git 提交推送**：代理端口按用户当前设置（如 `git -c http.proxy=http://127.0.0.1:10808 push`）；`web/`、`deploy/`、`scripts/deploy/` 不入库属预期。

---

# 服务器上的"远端配置目录"（`/opt/pickq/config/`）

用于放**客户端运行时会拉取的远端配置**，与站点部署解耦——改文件立即生效，**不需要重新部署网站、更不需要发版**。

- 对外地址：`https://pickq.cn/config/<file>`（nginx `location /config/` → `alias /opt/pickq/config/`，见 `scripts/deploy/pickq-nginx.conf`）
- 当前内容：
  - `ai-presets.json`：AI 模型预设目录（客户端 `GET /api/ai/presets` 拉取，后端内存缓存 1 小时）
    - 结构：`{ updatedAt, note, presets[], deprecated[] }`
    - `presets[]`：`name / label / desc / baseUrl / model / visionModel / keyUrl / verifiedAt / hint`
      —— **模型名易变，只对长期稳定的写默认值**（如 `deepseek-chat`、`glm-4.6`、`mistral-large-latest`），其余留空字符串，客户端引导用户点「获取可用模型」按自己的 Key 拉取真实列表；
    - `deprecated[]`：`{ model, replacement, note }`，用于客户端在"模型不存在"报错时给出替代建议。
- 源文件版本记录在仓库 `scripts/deploy/ai-presets.json`（该目录不入 git，属本地部署资产）。
- 更新方式：改本地文件 → `scp -i ~/.ssh/id_ed25519_pickq` 上传到 `/opt/pickq/config/`（或直接改服务器上的文件）→ 客户端下次拉取（最长 1 小时缓存，用户也可在设置页点「刷新预设」立刻生效）。
- ⚠️ nginx `reload` 是平滑异步的：改完 nginx 配置后**等 1-2 秒**再验证，否则可能命中的仍是旧配置而误判为失败。
