<div align="center">

![erii](https://socialify.git.ci/spcookie/erii/image?custom_description=%EA%B0%90%EC%A0%95%2C+%EA%B8%B0%EC%96%B5%2C+%ED%9D%90%EB%A6%84+%EA%B8%B0%EB%B0%98+%EC%82%AC%EA%B3%A0%2C+%EB%8A%A5%EB%8F%99%EC%A0%81+%ED%96%89%EB%8F%99%2C+%EC%9E%91%EC%97%85+%EC%95%8C%EB%A6%BC+%EB%B0%8F+%EC%9E%90%EA%B8%B0+%EA%B7%9C%EC%B9%99+%EA%B4%80%EB%A6%AC%EB%A5%BC+%EA%B0%96%EC%B6%98+AI+%EA%B7%B8%EB%A3%B9+%EC%B1%84%ED%8C%85+%EB%B4%87.&custom_language=Kotlin&description=1&font=Source+Code+Pro&forks=1&issues=1&language=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Fspcookie%2Ferii%2F626e8833ae51f8ead0b2f41be237c4c2fb761577%2Fdoc%2Fassets%2FLOGO.svg&name=1&owner=1&pattern=Circuit+Board&pulls=1&stargazers=1&theme=Auto)

한국어 | [English](./README_en.md) | [中文](../README.md) | [繁體中文](./README_zh-TW.md)

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

[📖 문서](https://spcookie.github.io/erii-docs) • [🚀 시작하기](https://spcookie.github.io/erii-docs/quick-start) • [📦 빌드 및 실행](https://spcookie.github.io/erii-docs/dev/build)

</div>

---

## 📖 개요

erii는 실제 사람처럼 그룹 채팅에 참여하는 AI 봇입니다. 감정, 기억, 성격을 가지고 있어 대화에 따라 기분이 변하고 말투도 달라집니다. erii는 호출을 기다리기만 하지 않고, 분위기를 능동적으로 파악합니다:
할 말이 있을 때 끼어들고, 조용히 있어야 할 때는 머물며, 그룹이 조용해지면 스스로 대화를 시작합니다. 채팅이 길어질수록 사람들의 취향을 기억하고, 그룹 특유의 농담을 익히며, 점차 그룹의 자연스러운 일부가
됩니다.

**핵심 기능:**

- 🎭 **감정 시스템** — PAD 3차원 감정 모델, 답장 어조와 스타일 동적 조절
- 🧠 **기억 시스템** — 다차원 벡터 기억, 의미 검색으로 오래 대화할수록 더 잘 이해
- 🌊 **흐름 시스템** — 0-100 참여도 상태 머신, 주제 매칭 및 과열 보호
- ⚡ **능동적 행동** — 충동값 기반 끼어들기/분위기 전환/일상, 스팸 방지 피로 메커니즘
- 📜 **규칙 관리** — Markdown 규칙 파일, 다단계 범위 핫 리로드
- ⏰ **크론 작업** — 알림 및 트리거 작업, 자연스러운 언어 푸시
- 🔌 **플러그인 확장** — PF4J 플러그인 프레임워크, 4가지 확장점, MCP 도구 통합
- 🌐 **다중 LLM** — Gemini / DeepSeek / MiniMax / OpenAI / Anthropic / OpenRouter 지원

## 🏁 시작하기

### 전제 조건

- **Node.js 18+** (npm 설치) 또는 **JDK 17+** (소스 빌드)
- **NapCat** — QQ 연동 서비스
- **LLM API Key** — 최소 하나의 LLM 제공자 API 키

### npm 설치 (권장)

```bash
npm create @spcookie/erii
cd erii
erii setup     # 대화형 설정 마법사
erii server    # 서버 시작
```

또는 원라인 설치:

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/spcookie/erii-distribution/master/scripts/install.ps1 | iex
```

### Docker 배포

```bash
docker-compose up -d                                  # erii 메인 서비스
cd docker/napcat-docker && docker-compose up -d       # NapCat QQ 연동
cd docker/playwright-docker && docker-compose up -d   # Playwright 브라우저
```

### CLI 도구

```bash
go install ./erii-cli
```

| 명령어        | 설명                   |
|:--------------|:-----------------------|
| `erii config` | 대화형 TUI 설정 편집기 |
| `erii setup`  | 설정 마법사            |
| `erii manage` | 데이터 관리 TUI        |
| `erii status` | 상태 TUI               |

## 🛠️ 기술 스택

- **언어:** Kotlin 2.2+ / Go 1.21+ / Node.js 18+
- **프레임워크:** Ktor 3.3+ / Koog Agents 0.7+ / Mirai 2.16+ / Bubble Tea
- **저장소:** H2 + Exposed ORM
- **인프라:** Koin DI / JobRunr 스케줄링 / PF4J 플러그인 / Gradle 빌드

자세한 문서는 **[erii-docs](https://spcookie.github.io/erii-docs)** 를 방문하세요.

## 📄 라이선스

이 프로젝트는 [MIT](LICENSE) 라이선스로 오픈소스입니다.
