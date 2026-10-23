<!-- Modified for Maxit, 2026-10-20. -->

# Maxit

Maxit is a FOSS AI assistant forked from [SimonSchubert/Kai](https://github.com/SimonSchubert/Kai). Android, Linux, and Web are maintained. Windows, macOS, and iOS remain source-only targets; references to their implementations below describe retained source, not supported releases.

[:material-download: Get Started](getting-started.md){ .md-button .md-button--primary }

## Overview

Maxit is built with Kotlin Multiplatform and Compose Multiplatform. Configure a provider or on-device model before chatting. Fallback uses only configured providers; there is no built-in Free proxy. Maxit remembers important details across conversations and can act autonomously via scheduled heartbeats and tool execution.

## Key Features

- **Persistent memory** — Maxit remembers important details across conversations and uses them automatically
- **Customizable soul** — Define the AI's personality and behavior with an editable system prompt
- **Multi-service fallback** — Configure multiple providers; Maxit automatically tries the next one on failure
- **On-device inference** — Run downloaded models locally with LiteRT on Android and Linux desktop
- **Tool execution** — Web search, notifications, calendar events, shell commands, and more
- **MCP server support** — Connect to remote tool servers via the Model Context Protocol
- **Interactive UI (parked)** — Generation is disabled; archived instructions and rendering of old messages are retained
- **Skills** — Android and desktop instruction bundles, independent of the sandbox; GitHub or local `.md`/`.zip` installs, catalog discovery, and lazy loading
- **Autonomous heartbeat & scheduled tasks** — Periodic self-checks plus one-time or recurring tasks the AI schedules
- **Linux sandbox** — On Android, the AI can run shell commands, scripts and packages in a Debian or Alpine environment
- **Maxit Build** — An Android coding environment with terminals and OpenCode
- **Local storage** — Android and Linux encrypt settings and credentials, but their conversation SQLite databases are unencrypted at the application layer. Web stores settings, credentials, and conversations unencrypted in browser localStorage. See [storage details](features/encryption.md).
- **Settings export/import** — Back up and restore all settings as a JSON file
- **Text to speech** — Listen to AI responses
- **Image attachments** — Attach images to any conversation

## How It Works

```
                    ┌────────┐
                    │  User  │
                    └───┬────┘
                        │ message
                        ▼
           ┌─────────────────────────┐
           │          Chat           │
           │                         │
           │  prompt + memories      │
           │        │                │
           │        ▼                │
           │    ┌────────┐           │
           │    │   AI   │◀─┐        │
           │    └───┬────┘  │        │
           │        │   tool calls   │
           │        │   & results    │
           │        ▼      │        │
           │    ┌────────┐ │        │
           │    │ Tools  │─┘        │
           │    └───┬────┘          │
           │        │               │
           └────────┼───────────────┘
                    │ store / recall
                    ▼
           ┌─────────────────┐    hitCount >= 5
           │     Memory      │───────────────────┐
           │                 │                   │
           │  facts, prefs,  │                   ▼
           │  learnings      │          ┌────────────────┐
           │                 │◀─delete──│ Promote into   │
           └─────────────────┘          │ System Prompt  │
                    ▲                   └────────────────┘
                    │ reviews
                    │
           ┌─────────────────┐
           │    Heartbeat    │
           │                 │
           │  autonomous     │
           │  self-check     │
           │  every 30 min   │
           │  (8am–10pm)     │
           │                 │
           │  all good?      │
           │  → stays silent │
           │  needs action?  │
           │  → notifies user│
           └─────────────────┘
```

## Supported Services

| Service | API Type |
|---|---|
| [Atlas Cloud](https://www.atlascloud.ai) | OpenAI-compatible |
| [Anthropic](https://console.anthropic.com) | Anthropic native |
| [OpenAI](https://openai.com) | OpenAI-compatible (Responses API for models that require it) |
| [Gemini](https://aistudio.google.com) | Gemini native |
| [DeepSeek](https://www.deepseek.com) | OpenAI-compatible |
| [Mistral](https://mistral.ai) | OpenAI-compatible |
| [xAI](https://x.ai) | OpenAI-compatible |
| [OpenRouter](https://openrouter.ai) | OpenAI-compatible |
| [Requesty](https://requesty.ai) | OpenAI-compatible |
| [Groq](https://groq.com) | OpenAI-compatible |
| [NVIDIA](https://developer.nvidia.com) | OpenAI-compatible |
| [Cerebras](https://cerebras.ai) | OpenAI-compatible |
| [Ollama Cloud](https://ollama.com) | OpenAI-compatible |
| [LongCat](https://longcat.chat) | OpenAI-compatible |
| [Together AI](https://together.ai) | OpenAI-compatible |
| [Hugging Face](https://huggingface.co) | OpenAI-compatible |
| [Venice AI](https://venice.ai) | OpenAI-compatible |
| [Moonshot AI](https://moonshot.cn) | OpenAI-compatible |
| [Z.AI](https://z.ai) (standard and Coding Plan) | OpenAI-compatible |
| [MiniMax](https://minimax.io) | OpenAI-compatible |
| [AIHubMix](https://aihubmix.com) | OpenAI-compatible |
| [Deep Infra](https://deepinfra.com) | OpenAI-compatible |
| [Fireworks AI](https://fireworks.ai) | OpenAI-compatible |
| [OpenCode](https://opencode.ai) | OpenAI-compatible |
| [Public AI](https://publicai.co) | OpenAI-compatible |
| [AI Horde](https://stablehorde.net/) | OpenAI-compatible |
| [1min.AI](https://1min.ai) | OpenAI-compatible |
| [Perplexity](https://www.perplexity.ai) | OpenAI-compatible |
| OpenAI-Compatible API (Ollama, LM Studio, etc.) | OpenAI-compatible |
| Local Model (Android, iOS, desktop) | LiteRT on-device |

Providers require user configuration. Local inference needs a downloaded or imported model.

## Platforms

| Platform | Status |
|---|---|
| Android | Maintained FOSS build |
| Linux | Maintained desktop build |
| Web | Maintained browser build |
| iOS, macOS, Windows | Retained source only |

## Feature Documentation

- **[Chat & Conversations](features/chat.md)** — Message history, conversation persistence, image attachments, and speech output
- **[Multi-Service](features/multi-service.md)** — Provider configuration, fallback chain, and connection validation
- **[Tools](features/tools.md)** — Available tools, execution flow, safety guards, and enablement
- **[Memories](features/memories.md)** — Memory lifecycle, categories, reinforcement, and promotion
- **[Heartbeat](features/heartbeat.md)** — Autonomous self-checks, active hours, and configuration
- **[Tasks](features/tasks.md)** — Scheduled tasks, future execution, and task management
- **[Daemon](features/daemon.md)** — Background service for scheduled tasks and heartbeat execution
- **[MCP Servers](features/mcp.md)** — Remote tool servers over the Model Context Protocol
- **[Dynamic UI](features/dynamic-ui.md)** — Parked generation, archived instructions, and retained rendering of old messages
- **[On-Device Inference](features/on-device-inference.md)** — Local LiteRT models, downloads, and integrity checks
- **[Reasoning](features/reasoning.md)** — How reasoning traces are sent back to providers and shown
- **[System Prompts](features/system-prompts.md)** — How each prompt variant is composed
- **[Skills](features/skills.md)** — App-private SKILL.md bundles, imports, lazy loading, and explicit slash commands
- **[Linux Sandbox](features/sandbox.md)** — On-device Debian/Alpine shell for the AI and the user (Android)
- **[Maxit Build](features/maxit-build.md)** — Coding environment with terminals and coding agents (Android)
- **[Notifications](features/notifications.md)** — Reading other apps' notifications (Android, FOSS build)
- **[SMS](features/sms.md)** — Reading SMS and drafting replies for the user to send (Android, FOSS build)
- **[Appearance](features/appearance.md)** — Theme picker
- **[Encryption](features/encryption.md)** — Secure settings storage per platform
- **[Settings Export & Import](features/settings-export-import.md)** — JSON backup and restore

## License and attribution

Maxit uses [GPL-3.0-only](../LICENSE.txt). Upstream Kai is by Simon Schubert and contributors; its [Apache-2.0 license](../LICENSES/Apache-2.0.txt) and third-party notices remain applicable. See the [README](../README.md) for build commands and acknowledgements.
