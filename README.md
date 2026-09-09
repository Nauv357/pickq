<p align="center">
  <img src="docs/logo.png" width="84" alt="拾题" />
</p>

<h1 align="center">拾题（PickQ）· 自建题库刷题应用</h1>

<p align="center">
  <b>把自己手上的试卷和资料，变成能刷的题库。</b><br/>
  PDF / Word / 照片 → AI 整理成题目；刷题、复习、统计都在本机完成。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="license MIT" />
  <img src="https://img.shields.io/badge/platform-Windows-0078d6" alt="platform Windows" />
  <a href="https://pickq.cn"><img src="https://img.shields.io/badge/官网-pickq.cn-2f81f7" alt="website" /></a>
</p>

<p align="center">
  <b>中文</b> · <a href="README.en.md">English</a>
</p>

<p align="center">
  📥 只想用？直接下载 Windows 桌面版 → <a href="https://pickq.cn">pickq.cn</a>（安装包 / 便携版，解压即用）
</p>

---

## 目录

- [这是什么](#这是什么)
- [为什么是拾题](#为什么是拾题)
- [怎么用](#怎么用)
- [分享你的题库](#分享你的题库)
- [快速开始](#快速开始)
- [技术架构](#技术架构)
- [仓库结构](#仓库结构)
- [常见问题](#常见问题)
- [License](#license)

---

## 这是什么

拾题是一个 Windows 桌面应用：上传你手头的学习材料（讲义 PDF、真题扫描件、错题截图、Word / txt），AI 把它们整理成标准题目（单选、多选、判断、主观题等），逐题校对后即可刷题。做题记录、错题与复习安排都保存在本机——不需要注册账号，不联网也能用。

## 为什么是拾题

市面上的题库工具大致两类：用别人的题库，内容不由自己决定；自己建题库，整理全靠手工录入。近年部分工具加入了 AI 辅助整理，但对带图的资料处理往往不完整。拾题针对三个实际场景：

- **带图的资料不丢图**：扫描件、图表、截图中的图片会被保留，并出现在对应题目的题干、选项或材料里；存储、导出、再导入全程不丢失——拿一份带图试卷试一次就能验证；
- **资料不出本机**：AI 整理使用你自己开通的模型 API Key（DeepSeek / 通义 / Kimi 等，官网申请，也可用本地 Ollama），原始资料、题目与记录都留在本机；
- **数据是普通文件**：题库可以导出成单个文件（.tiku / .json），备份、换电脑、分享给别人都不依赖任何账号或服务。

## 怎么用

### 方式一：把手头的资料变成题库（最常用）

1. 首页点「导入」→ 选「我手上有试卷 / 学习资料」；
2. 上传 PDF / Word / 图片，选择模型（首次使用需在「设置 → AI 模型配置」填入你的 API Key，应用内有分步教程）；
3. 预览页逐题校对（改选项、补答案、处理图片）→ 确认入库 → 开始刷题。

![导入入口](docs/screenshots/import.png)

### 方式二：导入别人分享的题库文件

收到 .tiku / .json 文件时，点「导入」→「别人发来了题库文件」即可，重复导入会自动识别并去重。

### 方式三：直接使用现成题库

应用内「发现题库」页可以浏览题库广场的作品并一键导入（广场地址 https://pickq.cn）。

### 日常使用

- **刷题**：会话制——抽一题组作答后交卷统一判分；支持答题卡、错题、收藏；
- **复习**：按记忆间隔产生到期提醒，避免"会了又忘"；
- **统计**：正确率、错题是否被"治愈"、复习分布，都能回看。

![题库列表](docs/screenshots/home.png)　![学习统计](docs/screenshots/stats.png)

## 分享你的题库

一套题的价值，不在于被谁拥有，而在于它帮助过多少人。

拾题把题库还原成它本来的样子：一套可以自由传递的普通文件。分享不需要任何门槛——

- **直接发给需要的人**：导出 .tiku 文件，聊天窗口、网盘、U 盘都可以，对方导入即可用；
- **发布到题库广场**：让不认识的人也能通过搜索与收藏发现它（pickq.cn，免费发布，不要求绑定账号）；
- **任何你习惯的方式**：QQ 群、公众号、校园论坛……只要文件能到达，分享就能发生。

广场上的每一套题，最初都来自某个愿意花时间整理的人。如果你曾从别人的分享里受益，
也请把自己认真整理过的资料分享出去——当越来越多的人愿意分享，找资料就不再是
一场碰运气的苦差事，而认真整理的人，也终将被更多人看见。

## 快速开始

### 下载桌面版

从官网下载安装包或便携版（解压即用）：<https://pickq.cn>

已安装旧版本的用户，打开应用即会收到更新提示（v0.1.1 起内置自动更新）。

### 从源码构建

```bash
# 1. 前端构建（产物打进后端 jar 的 static/）
cd frontend && npm install && npm run build

# 2. 后端打包并运行（Java 21 + Maven）
mvn package -DskipTests
java -jar target/Tiku-0.0.1-SNAPSHOT.jar    # http://localhost:8080
```

Windows 桌面版一键打包：

```powershell
.\tauri\build-desktop.ps1   # 后端 jar → jlink 裁剪 JRE → NSIS 安装包 + 便携 zip
```

数据目录默认 `~/.tiku`（H2 文件库 + 图片 + AI 配置），可用环境变量 `TIKU_DATA_DIR` 覆盖。

## 技术架构

```
tiku-desktop.exe（Tauri 2 / Rust 壳）
  ├─ 单实例、随机端口、父进程守护
  └─ 捆绑裁剪 JRE + Spring Boot jar（本地 H2 数据库，同源托管 Vue 前端）
        ↓ http://127.0.0.1:随机端口
     WebView2 窗口
```

- 后端：Spring Boot 3 / Java 21 / MyBatis-Plus / H2 / Flyway
- 前端：Vue 3 + Element Plus + ECharts（SPA，与后端同源托管）
- 桌面壳：Tauri 2（Rust），自带裁剪 JRE，无需用户安装 Java
- 自动更新与一键备份恢复由桌面壳配合后端实现

## 仓库结构

```
src/          后端（Spring Boot）
frontend/     前端 SPA
tauri/        桌面壳与打包脚本
docs/         Logo 与界面截图
```

> 官网（Nuxt3）、部署脚本与设计文档为内部资产，不在本仓库。

## 常见问题

- **AI 整理需要什么条件？** 一个模型 API Key（DeepSeek / 通义 / Kimi 等，几块钱即可开始；也可用本地 Ollama，零成本）。Key 只保存在本机。
- **换电脑怎么迁移？** 「设置 → 完整备份」下载备份包；新电脑装好后「从备份恢复」，应用会自动完成还原。
- **会收费吗？** 应用免费。AI 调用费由你自己的 API Key 承担。

## License

[MIT](LICENSE)
