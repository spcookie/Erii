<div align="center">

![erii](https://socialify.git.ci/spcookie/erii/image?custom_description=%E4%B8%80%E5%80%8B%E5%85%B7%E5%82%99%E6%83%85%E6%84%9F%E3%80%81%E8%A8%98%E6%86%B6%E3%80%81%E5%BF%83%E6%B5%81%E3%80%81%E4%B8%BB%E5%8B%95%E8%A1%8C%E7%82%BA%EF%BC%8C%E4%BB%A5%E5%8F%8A%E4%BA%8B%E9%A0%85%E6%8F%90%E9%86%92%E8%88%87%E8%87%AA%E6%88%91%E8%A6%8F%E5%89%87%E7%AE%A1%E7%90%86%E7%9A%84+AI+%E7%BE%A4%E8%81%8A%E6%A9%9F%E5%99%A8%E4%BA%BA%E3%80%82&custom_language=Kotlin&description=1&font=Source+Code+Pro&forks=1&issues=1&language=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Fspcookie%2Ferii%2F626e8833ae51f8ead0b2f41be237c4c2fb761577%2Fdoc%2Fassets%2FLOGO.svg&name=1&owner=1&pattern=Circuit+Board&pulls=1&stargazers=1&theme=Auto)

繁體中文 | [English](./README_en.md) | [中文](../README.md) | [한국어](./README_ko.md)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Go](https://img.shields.io/badge/Go-1.21+-00ADD8?style=for-the-badge&logo=go&logoColor=white)](https://go.dev/)
[![Node.js](https://img.shields.io/badge/Node.js-18+-339933?style=for-the-badge&logo=node.js&logoColor=white)](https://nodejs.org/)
<br/>
[![Ktor](https://img.shields.io/badge/Ktor-3.3+-087CFA?style=for-the-badge&logo=ktor&logoColor=white)](https://ktor.io/)
[![Koog_Agents](https://img.shields.io/badge/Koog_Agents-0.7+-FF6B6B?style=for-the-badge)](https://github.com/spcookie/koog)
[![Mirai](https://img.shields.io/badge/Mirai-2.16+-00C4B3?style=for-the-badge)](https://github.com/mamoe/mirai)
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

[📖 文件](https://spcookie.github.io/erii-docs) • [🚀 快速開始](https://spcookie.github.io/erii-docs/quick-start) • [📦 建置與執行](https://spcookie.github.io/erii-docs/dev/build)

</div>

---

## 📖 概述

erii 是一個能像真人一樣參與群聊的 AI 機器人。它有情緒、有記憶、有性格——心情會隨對話起伏，說話風格也跟著變化。erii
不是被動等召喚，而是主動感知群裡的氛圍：該插話時插話，該安靜時安靜，冷場了還會自己找話題暖場。隨著聊天深入，erii
會記住每個群友的喜好，學會群裡的梗和黑話，越聊越有群裡的味道。

**核心特性：**

- 🎭 **情緒系統** — PAD 三維情緒模型，動態調節回覆語氣和風格
- 🧠 **記憶系統** — 多維度向量記憶，語義檢索，越聊越了解群友
- 🌊 **心流系統** — 0-100 投入度狀態機，話題匹配與過熱保護
- ⚡ **主動行為** — 衝動值驅動的插話/破冰/暖場，防刷屏疲勞機制
- 📜 **規則管理** — Markdown 規則檔案約束言行，多級作用域熱重載
- ⏰ **定時任務** — Cron 提醒和觸發任務，自然語言推送
- 🔌 **插件擴展** — PF4J 插件框架，4 種擴展點，MCP 工具接入
- 🌐 **多 LLM** — 支援 Gemini / DeepSeek / MiniMax / OpenAI / Anthropic / OpenRouter

## 🏁 快速開始

### 前置需求

- **Node.js 18+**（npm 安裝）或 **JDK 17+**（原始碼構建）
- **NapCat** — QQ 接入服務
- **LLM API Key** — 至少一個 LLM 提供商的 API Key

### npm 安裝（推薦）

```bash
npm create @spcookie/erii
cd erii
erii setup     # 互動式設定精靈
erii server    # 啟動服務
```

或一鍵安裝：

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.ps1 | iex
```

### Docker 部署

```bash
docker-compose up -d                                  # erii 主服務
cd docker/napcat-docker && docker-compose up -d       # NapCat QQ 接入
cd docker/playwright-docker && docker-compose up -d   # Playwright 瀏覽器
```

### CLI 工具

```bash
go install ./erii-cli
```

| 命令          | 說明                  |
|:--------------|:----------------------|
| `erii config` | 互動式 TUI 設定編輯器 |
| `erii setup`  | 設定精靈              |
| `erii manage` | 資料管理 TUI          |
| `erii status` | 狀態統計 TUI          |

## 🛠️ 技術堆疊

- **語言:** Kotlin 2.2+ / Go 1.21+ / Node.js 18+
- **框架:** Ktor 3.3+ / Koog Agents 0.7+ / Mirai 2.16+ / Bubble Tea
- **儲存:** H2 + Exposed ORM
- **基礎設施:** Koin DI / JobRunr 排程 / PF4J 插件 / Gradle 構建

詳細文件請造訪 **[erii-docs](https://spcookie.github.io/erii-docs)**。

## 📄 授權

本專案基於 [MIT](LICENSE) 授權條款開源。

## Repobeats

![Alt](https://repobeats.axiom.co/api/embed/341cfbaa0a0048c8c95abe32707d6760903d13e0.svg "Repobeats analytics image")

## Star History

<a href="https://www.star-history.com/?repos=spcookie%2Ferii&type=date&logscale=&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=spcookie/erii&type=date&theme=dark&logscale&legend=top-left&sealed_token=qH79hCL-Kh4sv8moangZ4Rd6h9bVvzPLOZ6vx4gd4iaOX0KHVk6xDqkCHPU8M8mKegfxInengia-KNVIBR4K3wt6PSSWColTuqSFnMS_UyyZVmHqk-cUc_LhLqqFLwbpZwmBAXLrUR0edR5m0hPkpvrqYeTSbvDw-cW-5mPcS5qqAhLYTaMDLaaTTKq8" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=spcookie/erii&type=date&logscale&legend=top-left&sealed_token=qH79hCL-Kh4sv8moangZ4Rd6h9bVvzPLOZ6vx4gd4iaOX0KHVk6xDqkCHPU8M8mKegfxInengia-KNVIBR4K3wt6PSSWColTuqSFnMS_UyyZVmHqk-cUc_LhLqqFLwbpZwmBAXLrUR0edR5m0hPkpvrqYeTSbvDw-cW-5mPcS5qqAhLYTaMDLaaTTKq8" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=spcookie/erii&type=date&logscale&legend=top-left&sealed_token=qH79hCL-Kh4sv8moangZ4Rd6h9bVvzPLOZ6vx4gd4iaOX0KHVk6xDqkCHPU8M8mKegfxInengia-KNVIBR4K3wt6PSSWColTuqSFnMS_UyyZVmHqk-cUc_LhLqqFLwbpZwmBAXLrUR0edR5m0hPkpvrqYeTSbvDw-cW-5mPcS5qqAhLYTaMDLaaTTKq8" />
 </picture>
</a>
