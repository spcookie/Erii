<div align="center">

![erii](https://socialify.git.ci/spcookie/erii/image?custom_description=%E4%B8%80%E4%B8%AA%E5%85%B7%E6%9C%89%E6%83%85%E6%84%9F%E3%80%81%E8%AE%B0%E5%BF%86%E3%80%81%E5%BF%83%E6%B5%81%E3%80%81%E4%B8%BB%E5%8A%A8%E8%A1%8C%E4%B8%BA%EF%BC%8C%E4%BA%8B%E9%A1%B9%E6%8F%90%E9%86%92%E5%92%8C%E8%87%AA%E6%88%91%E8%A7%84%E5%88%99%E7%AE%A1%E7%90%86%E7%9A%84+AI+%E7%BE%A4%E8%81%8A%E6%9C%BA%E5%99%A8%E4%BA%BA%E3%80%82&custom_language=Kotlin&description=1&font=Source+Code+Pro&forks=1&issues=1&language=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Fspcookie%2Ferii%2F626e8833ae51f8ead0b2f41be237c4c2fb761577%2Fdoc%2Fassets%2FLOGO.svg&name=1&owner=1&pattern=Circuit+Board&pulls=1&stargazers=1&theme=Auto)

中文 | [English](./doc/README_en.md) | [繁體中文](./doc/README_zh-TW.md) | [한국어](./doc/README_ko.md)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Go](https://img.shields.io/badge/Go-1.21+-00ADD8?style=for-the-badge&logo=go&logoColor=white)](https://go.dev/)
[![Node.js](https://img.shields.io/badge/Node.js-18+-339933?style=for-the-badge&logo=node.js&logoColor=white)](https://nodejs.org/)
<br/>
[![Ktor](https://img.shields.io/badge/Ktor-3.3+-087CFA?style=for-the-badge&logo=ktor&logoColor=white)](https://ktor.io/)
[![Koog_Agents](https://img.shields.io/badge/Koog_Agents-0.7+-FF6B6B?style=for-the-badge)](https://github.com/spcookie/koog)
[![JobRunr](https://img.shields.io/badge/JobRunr-8.3+-green?style=for-the-badge)](https://jobrunr.io/)
<br/>
[![H2](https://img.shields.io/badge/H2-2.3+-0066CC?style=for-the-badge)](https://www.h2database.com/)
[![Exposed](https://img.shields.io/badge/Exposed-1.1+-F3692A?style=for-the-badge)](https://github.com/JetBrains/Exposed)
[![Koin](https://img.shields.io/badge/Koin-4.1+-F3692A?style=for-the-badge)](https://insert-koin.io/)
[![PF4J](https://img.shields.io/badge/PF4J-3.15+-blue?style=for-the-badge)](https://pf4j.org/)
<br/>
[![Bubble_Tea](https://img.shields.io/badge/Bubble_Tea-TUI-2C3E50?style=for-the-badge&logo=bubble-tea&logoColor=white)](https://github.com/charmbracelet/bubbletea)

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/spcookie/erii)
[![Visitors](https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2Fspcookie%2Ferii&labelColor=%23d9e3f0&countColor=%23263759&style=flat)](https://visitorbadge.io/status?path=https%3A%2F%2Fgithub.com%2Fspcookie%2Ferii)

[📖 文档](https://spcookie.github.io/erii-docs) • [🚀 快速开始](https://spcookie.github.io/erii-docs/quick-start) • [📦 构建与运行](https://spcookie.github.io/erii-docs/dev/build)

</div>

---

## 📖 概述

erii 是一个能像真人一样参与群聊的 AI 机器人。它有情绪、有记忆、有性格——心情会随对话起伏，说话风格也跟着变化。erii
不是被动等召唤，而是主动感知群里的氛围：该插话时插话，该安静时安静，冷场了还会自己找话题暖场。随着聊天深入，erii
会记住每个群友的喜好，学会群里的梗和黑话，越聊越有群里的味道。

**核心特性：**

- 🎭 **情绪系统** — PAD 三维情绪模型，动态调节回复语气和风格
- 🧠 **记忆系统** — 多维度向量记忆，语义检索，越聊越了解群友
- 🌊 **心流系统** — 0-100 投入度状态机，话题匹配与过热保护
- ⚡ **主动行为** — 冲动值驱动的插话/破冰/暖场，防刷屏疲劳机制
- 📜 **规则管理** — Markdown 规则文件约束言行，多级作用域热重载
- ⏰ **定时任务** — Cron 提醒和触发任务，自然语言推送
- 🔌 **插件扩展** — PF4J 插件框架，4 种扩展点，MCP 工具接入
- 🌐 **多 LLM** — 支持 Gemini / DeepSeek / MiniMax / OpenAI / Anthropic / OpenRouter

## 🏁 快速开始

### 前置要求

- **Node.js 18+**（npm 安装）或 **JDK 17+**（源码构建）
- **NapCat** — QQ 接入服务
- **LLM API Key** — 至少一个 LLM 提供商的 API Key

### npm 安装（推荐）

```bash
npm create @spcookie/erii
cd erii
erii setup     # 交互式配置向导
erii server    # 启动服务
```

或一键安装：

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.ps1 | iex
```

### Docker 部署

```bash
docker-compose up -d                           # erii 主服务
cd docker/napcat-docker && docker-compose up -d  # NapCat QQ 接入
cd docker/playwright-docker && docker-compose up -d  # Playwright 浏览器
```

### CLI 工具

```bash
go install ./erii-cli
```

| 命令          | 说明                  |
|:--------------|:----------------------|
| `erii config` | 交互式 TUI 配置编辑器 |
| `erii setup`  | 设置向导              |
| `erii manage` | 数据管理 TUI          |
| `erii status` | 状态统计 TUI          |

## 🛠️ 技术栈

- **语言:** Kotlin 2.2+ / Go 1.21+ / Node.js 18+
- **框架:** Ktor 3.3+ / Koog Agents 0.7+ / Mirai 2.16+ / Bubble Tea
- **存储:** H2 + Exposed ORM
- **基础设施:** Koin DI / JobRunr 调度 / PF4J 插件 / Gradle 构建

详细文档请访问 **[erii-docs](https://spcookie.github.io/erii-docs)**。

## 📄 许可证

本项目基于 [MIT](LICENSE) 许可证开源。