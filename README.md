# Khabeer

**An Android AI agent that maps Hermes Agent's workflows into a native mobile app, built on Termux.**

Khabeer brings the agent experience developed by [Nous Research's Hermes Agent](https://github.com/NousResearch/hermes-agent) to Android: persistent conversations, terminal tools, memory, reusable skills, delegated tasks and extensible providers. Its Android application and Linux execution environment are built on [Termux](https://github.com/termux/termux-app).

**Huge thanks to Termux and Hermes for making this app possible.**

This is an independent community project. The agent runtime is implemented in Java inside the Android app, adapting Hermes concepts and behavior to mobile lifecycle and storage constraints. Native chat does not require installing the Hermes Python CLI. This project does not claim complete Hermes feature parity or endorsement by either upstream project.

[Source](https://github.com/bakka22/khabeer) · [Issues](https://github.com/bakka22/khabeer/issues) · [Build checks](https://github.com/bakka22/khabeer/actions) · [License](LICENSE.md)

## Status and installation compatibility

Khabeer is under active development. This repository publishes source; APK releases are a separate step. Build locally using the instructions below. Provider integrations and background behavior need testing on your device and account.

The app currently retains the **`com.termux` application identity and shared user ID**. It cannot coexist with another installation using the same package name. Builds signed by different keys cannot update each other. Back up existing Termux data before changing installations; uninstalling removes app-private files. Khabeer is not an official Termux update, and official Termux plugin APKs are not automatically signature-compatible with it.

## Contents

- [Features](#features)
- [Relationship to Hermes Agent](#relationship-to-hermes-agent)
- [Providers and protocols](#providers-and-protocols)
- [Getting started](#getting-started)
- [Architecture](#architecture)
- [Storage and privacy](#storage-and-privacy)
- [Building from source](#building-from-source)
- [Tests](#tests)
- [Limitations and troubleshooting](#limitations-and-troubleshooting)
- [Contributing](#contributing)
- [Licensing and acknowledgements](#licensing-and-acknowledgements)

## Features

| Area | Implementation |
| --- | --- |
| Native chat | Streaming responses, Markdown, history, configurable providers/models and reasoning settings |
| Durable runs | SQLite-backed messages/run state; queue, steer or interrupt input while an agent works |
| Terminal | Local commands, working-directory selection, stdout/stderr capture, timeouts and an interactive terminal view |
| Conversations | Session branching, resume, manual context compaction and conversation management |
| Memory | Editable MEMORY.md, USER.md and SOUL.md; memory tools, session search, staged writes and review controls |
| Skills | SKILL.md discovery, bundled Hermes skill library, usage tracking, pin/adopt/archive/restore controls and maintenance |
| Delegation | Child agent runs with inspectable transcripts, step limits and timeouts |
| Task tracking | Structured todowrite tasks and durable snapshots restored during replay |
| Plugins | Manifest-based installation and enable/disable controls; provider, skill and MCP extensions |
| MCP | Server registration, tool discovery/calls, HTTP/stdio support and authentication configuration |
| Attachments | File/image import, previews and provider-specific vision message formats |
| Web tools | SearXNG or DuckDuckGo HTML search; page fetch with host, redirect and size checks |
| Android UI | Dark/light appearance, background service, approval/completion notifications and file pickers |

These are implemented code paths, not a guarantee that every provider supports every capability. Images need a vision-capable model; model-driven tools need compatible tool calling.

## Relationship to Hermes Agent

Khabeer is fundamentally a mapping of Hermes Agent into an Android application. It adapts the agent/tool loop, provider routing/authentication patterns, memory and identity, conversation recall, reusable skills, skill lifecycle management, todo tracking, branching, plugin manifests and delegation. Android services and SQLite replace assumptions about a long-lived desktop Python process.

The local Hermes checkout reviewed for these acknowledgements was commit `52e5e7c034edfbd0105d0b9c315c4eecb6be7f5e`. This records the reference checkout, not a claim that every adaptation originated at that revision. Hermes evolves independently.

Intentional differences include a built-in file memory store instead of external memory providers and manual-first mid-session compaction (warned at 90% context, automatic once at 95%). Hermes messaging gateways, all remote execution backends and desktop/CLI functionality are not automatically included. Text-to-speech is deferred.

See [the feature roadmap](docs/FEATURE_ROADMAP.md) and [memory behavior](docs/MEMORY_SOURCE_OF_TRUTH.md) for the mapping and gaps. Historical verification notes in those documents are development checkpoints, not a current certification of external services.

## Providers and protocols

The runtime implements **OpenAI Responses**, **OpenAI-compatible Chat Completions** and **Anthropic Messages**, with streaming, tool calls and provider-specific replay handling. Custom providers/plugins can select a supported dialect. Endpoint discovery and per-model endpoint memory help route compatible models.

Built-in profiles currently marked implemented include OpenAI, Anthropic, OpenCode, OpenRouter, OpenAI Codex, Fireworks AI, NovitaAI, Vercel AI Gateway, z.ai, Kimi/Moonshot, Arcee AI, GMI Cloud, MiniMax, xAI/Grok, Qwen/Alibaba, Kilo Code, Xiaomi MiMo, Tencent TokenHub, DeepSeek, Hugging Face, Gemini, NVIDIA Build, Ollama Cloud and custom endpoints.

Nous Portal, GitHub Copilot, Actual Computer, Vertex AI, Azure AI Foundry, AWS Bedrock, Qwen OAuth and LM Studio have profiles but are currently marked unimplemented. A visible profile or login helper is not complete chat support. [AiProviderProfile.java](app/src/main/java/com/termux/app/AiProviderProfile.java) is the source of truth for these flags.

Use your own API credentials or an available sign-in flow. Account eligibility, costs, models and authentication policies are controlled by providers. Local endpoints must be reachable from the phone: `127.0.0.1` means the phone, not your development computer.

## Getting started

1. Build and install the appropriate debug APK on a test device/emulator, respecting the package/signature caveat above.
2. Launch Khabeer and initialize the bundled Termux environment if prompted. If the shell is not initialized, open the terminal view to complete setup.
3. Select an implemented provider, enter credentials or complete its supported sign-in flow, then choose a model.
4. Choose a project directory and start chatting. The agent can execute commands in the local environment.
5. Use Skills for reusable instructions/plugins and More for memory, delegated runs, web configuration and licenses.

Native chat does not require root or a separate Hermes installation. Commands and MCP servers may require additional packages installed through Termux's package manager.

## Architecture

```text
AiActivity (native Android UI)
  -> AiRuntimeService (agent loop, provider adapters, tools, approvals, replay)
       -> AiDatabase (conversations, runs, tasks, attachment metadata)
       -> Memory / skills / plugins / MCP / web registries
       -> MobileKhabeerToolExecutor -> Bash in the Termux environment
  -> TermuxService -> terminal sessions -> terminal-view / terminal-emulator
```

| Module / class | Responsibility |
| --- | --- |
| app | AI application/runtime, retained Termux services and bootstrap installer |
| termux-shared | Android utilities, filesystem, shell/environment support and Termux integration |
| terminal-view | Terminal rendering and Android input |
| terminal-emulator | Emulation, session I/O and native PTY/process support |
| AiActivity | Chat, provider selection, sessions, attachments and configuration pages |
| AiRuntimeService | Foreground agent service, streaming adapters, tools, delegation and replay |
| AiDatabase | SQLite `termux_ai_runtime.db`, currently schema version 20 |
| AiProviderConfig / ProviderLogin | Settings, encrypted credentials and sign-in flows |
| AiMemoryStore / AiSkillRegistry / AiSkillCurator | Memory, identity, discovery and maintenance |
| AiPluginRegistry / AiMcpRegistry | Manifest extensions and external tool connections |
| MobileKhabeerToolExecutor | Local Bash execution and bounded output collection |

The UI uses XML resources and Java views. Native components use the Android NDK. The build embeds an ABI-specific Termux bootstrap ZIP in `libtermux-bootstrap.so`. Major Java dependencies include AndroidX, Material Components, Markwon and Guava. Module Gradle files pin the versions.

## Storage and privacy

- Termux prefix: `/data/data/com.termux/files/usr`; home: `/data/data/com.termux/files/home`.
- Khabeer files principally use `$HOME/.khabeer`: `memories/MEMORY.md`, `memories/USER.md`, `SOUL.md`, pending memory writes and skills.
- App-private SQLite stores conversations, runtime state and attachment references. This project does not claim encrypted-at-rest conversation or memory storage.
- Provider keys and supported authentication tokens use Android Keystore-backed AES-GCM storage. This does not encrypt the entire database or every plugin configuration file.
- External provider requests can contain prompts, images, memory, conversation context and tool output. Web/MCP connections communicate with their configured services.
- Commands and stdio MCP servers use the app's access to the Termux environment. A working-directory selection is not an OS sandbox. Review commands, plugins and permissions accordingly.
- Back up important files before uninstalling or changing signing keys. System backup is disabled in the manifest.

## Building from source

| Setting | Current value |
| --- | --- |
| Gradle runtime | JDK 17 |
| Java source/target | Java 8 with core-library desugaring |
| Gradle wrapper / Android Gradle Plugin | 9.2.1 / 8.13.2 |
| Compile / target SDK | 36 / 28 |
| Declared min SDK | 21; use Android 7/API 24+ for the default bootstrap and Khabeer testing |
| NDK | 27.0.12077973 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64, x86 |
| Default bootstrap | apt-android-7, pinned to 2026.02.12-r1+apt.android-7 |
| Android version metadata | versionName 0.118.0, versionCode 118 (inherited from Termux) |

Install JDK 17, Android SDK platform 36, SDK build tools required by AGP, and the pinned NDK. Set `JAVA_HOME` and `ANDROID_HOME`, or use `sdk.dir` in untracked `local.properties`. Gradle downloads dependencies and checksum-verified bootstrap archives on the first build.

```bash
git clone https://github.com/bakka22/khabeer.git
cd khabeer
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
git clone https://github.com/bakka22/khabeer.git
Set-Location khabeer
.\gradlew.bat assembleDebug
```

Outputs are in `app/build/outputs/apk/debug/`. Filenames retain the inherited format. Example installation:

```bash
adb install app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

Build overrides: `TERMUX_PACKAGE_VARIANT`, `TERMUX_APP_VERSION_NAME`, `TERMUX_APK_VERSION_TAG`, `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS` and `TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS`. The inherited apt-android-5 option is not a claim of supported Khabeer operation on Android 5/6.

The tracked `app/testkey_untrusted.jks` is Termux's intentionally **public development key**. Do not use it as a production identity. `assembleRelease` needs a separately managed signing process before distribution. Keep private keys outside Git. See [RELEASING.md](RELEASING.md) for matching-source and package obligations.

## Tests

```bash
./gradlew :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest :termux-shared:testDebugUnitTest
```

Use `.\gradlew.bat` on Windows. JUnit/Robolectric tests cover provider formats, memory, replay, branching, skills, plugins, todo state, web policy and images. Reports are in each module's `build/reports/tests/`. They do not establish live provider availability or physical-device compatibility.

GitHub Actions builds source and runs tests. It does not automatically publish APKs; binary releases require the separate release checklist.

## Limitations and troubleshooting

- **Installation conflicts:** package identity and signing certificates must match to update an existing installation. Back up before replacement.
- **Background execution:** Android battery/process limits can interrupt long runs. Check notification/background permissions and device settings.
- **Providers:** verify model, endpoint, account access and dialect. Some profiles are placeholders; login flows can change upstream.
- **Shell:** initialize the bootstrap and check storage. Tools may need additional Termux packages.
- **Vision:** images require a vision-capable model.
- **Memory:** external stores are excluded; compaction is manual-first with one automatic pass at 95% context. See the memory specification for snapshots and approvals.
- **Web:** HTML search can be rate-limited or affected by upstream changes; SearXNG is an alternative.
- **Distribution:** inherited SDK/signing settings are development constraints; this repository does not claim app-store readiness.

## Contributing

Use [Khabeer issues and pull requests](https://github.com/bakka22/khabeer/issues). Include reproduction steps, Android version, commit/build and redacted logs. See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

Git history retains Termux ancestry. In the maintainer checkout, both origin and upstream point to bakka22/khabeer by choice; upstream does not automatically synchronize with Termux.

## Licensing and acknowledgements

The **combined application is GPL-3.0-only**, with component-specific exceptions retained. See [LICENSE.md](LICENSE.md), [COPYING](COPYING), [third-party notices](LICENSES/NOTICE.md) and [modification notes](CHANGES.md).

- **Termux:** Android application, terminal foundation, shell integration and bootstrap infrastructure. [Original repository](https://github.com/termux/termux-app); GPLv3-only with documented MIT, Apache and other exceptions.
- **Hermes Agent / Nous Research:** agent architecture and workflows adapted to the native Android runtime. [Original repository](https://github.com/NousResearch/hermes-agent); Copyright (c) 2025 Nous Research; [MIT notice preserved in full](LICENSES/Hermes-Agent-MIT.txt).
- **Android Terminal Emulator and dependencies:** original copyright/license notices remain applicable. Bundled Linux packages retain their own licenses.

Khabeer contributors are recorded in Git history. Hermes's MIT license does not remove GPL obligations for the combined application. The software is provided without warranty, subject to the applicable licenses.
