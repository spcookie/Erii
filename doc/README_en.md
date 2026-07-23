<div align="center">

![erii](https://socialify.git.ci/spcookie/erii/image?custom_description=An+AI+group+chat+bot+with+emotions%2C+memory%2C+flow-state+cognition%2C+proactive+behavior%2C+task+reminders%2C+and+self-rule+management.&custom_language=Kotlin&description=1&font=Source+Code+Pro&forks=1&issues=1&language=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Fspcookie%2Ferii%2F626e8833ae51f8ead0b2f41be237c4c2fb761577%2Fdoc%2Fassets%2FLOGO.svg&name=1&owner=1&pattern=Circuit+Board&pulls=1&stargazers=1&theme=Auto)

English | [中文](../README.md) | [繁體中文](./README_zh-TW.md) | [한국어](./README_ko.md)

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

[📖 Docs](https://spcookie.github.io/erii-docs) • [🚀 Quick Start](https://spcookie.github.io/erii-docs/quick-start) • [📦 Build & Run](https://spcookie.github.io/erii-docs/dev/build)

</div>

---

## 📖 Overview

erii is an AI group chat bot that feels like a real person. It has moods, memories, and a personality — its mood shifts
with the conversation, and its speaking style follows. erii doesn't just wait to be called; it actively reads the room:
jumping in when it has something to say, staying quiet when it doesn't, and starting conversations when the group goes
silent. The longer it chats, the better it knows everyone — it remembers preferences, picks up inside jokes, and
gradually becomes a natural part of the group.

**Core Features:**

- 🎭 **Emotion System** — PAD 3D emotion model, dynamically adjusts reply tone and style
- 🧠 **Memory System** — Multi-dimensional vector memory with semantic retrieval
- 🌊 **Flow System** — 0-100 engagement state machine with topic matching and overheat protection
- ⚡ **Volition System** — Impulse-driven interject/icebreak/routine, with anti-spam fatigue
- 📜 **Rule Management** — Markdown rule files with multi-level scope and hot-reload
- ⏰ **Cron Scheduling** — Reminder and trigger tasks with natural language push
- 🔌 **Plugin Extension** — PF4J plugin framework, 4 extension points, MCP tool integration
- 🌐 **Multi-LLM** — Supports Gemini / DeepSeek / MiniMax / OpenAI / Anthropic / OpenRouter

## 🏁 Getting Started

### Prerequisites

- **Node.js 18+** (npm install) or **JDK 17+** (source build)
- **NapCat** — QQ integration service
- **LLM API Key** — At least one LLM provider API key

### npm Install (Recommended)

```bash
npm create @spcookie/erii
cd erii
erii setup     # Interactive configuration wizard
erii server    # Start the server
```

Or one-line install:

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.ps1 | iex
```

### Docker Deployment

```bash
docker-compose up -d                                  # erii main service
cd docker/napcat-docker && docker-compose up -d       # NapCat QQ integration
cd docker/playwright-docker && docker-compose up -d   # Playwright browser
```

### CLI Tool

```bash
go install ./erii-cli
```

| Command       | Description                   |
|:--------------|:------------------------------|
| `erii config` | Interactive TUI config editor |
| `erii setup`  | Setup wizard                  |
| `erii manage` | Data management TUI           |
| `erii status` | Status TUI                    |

## 🛠️ Tech Stack

- **Languages:** Kotlin 2.2+ / Go 1.21+ / Node.js 18+
- **Frameworks:** Ktor 3.3+ / Koog Agents 0.7+ / Mirai 2.16+ / Bubble Tea
- **Storage:** H2 + Exposed ORM
- **Infrastructure:** Koin DI / JobRunr Scheduling / PF4J Plugins / Gradle Build

For detailed documentation, visit **[erii-docs](https://spcookie.github.io/erii-docs)**.

## 📄 License

This project is open source under the [MIT](LICENSE) license.

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
