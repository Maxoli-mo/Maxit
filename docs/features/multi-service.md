<!-- Modified for Maxit, 2026-10-20. -->

# Multi-Service

**Last verified:** 2026-10-02

Maxit supports user-configured LLM providers and local models. Each provider uses one of three API formats: **OpenAI-compatible** (most services), **Gemini native**, or **Anthropic native** -- plus **LiteRT on-device** for local inference. A handful of OpenAI models additionally require OpenAI's **Responses API**; Maxit switches to it per model, transparently. Users can configure multiple service instances, reorder them, and Maxit automatically falls back through the configured chain on failure.

## Concepts

### Service

A supported LLM provider. Each service is defined by:

- A unique name and ID
- Whether it requires an API key
- Which API format it uses (OpenAI-compatible, Gemini native, or Anthropic native)
- Links to the provider's API-key management page

### Service Instance

A configured connection to a service. Users can add multiple instances of the same service (e.g. two OpenAI accounts with different keys). Each instance stores its own:

- API key
- Selected model
- Base URL (relevant for the OpenAI-Compatible API service)

### Legacy compatibility

The `free` service ID is retained only as a history/settings compatibility sentinel with an empty endpoint. It is not a usable provider, default service, or fallback. New users must configure a provider or local model. Provider-specific free-tier model badges describe those providers' offerings, not a built-in Maxit service.

## Fallback Chain

1. Configured instances are tried in the order the user arranged them
2. Instances must meet their provider's credential requirements; local models and endpoints with optional authentication do not require an API key
3. If no usable instances are configured, the user must configure a provider or local model
4. Only configured instances enter the chain; no built-in proxy is appended
5. Each individual API request retries up to 2 times with increasing delays before the service is considered failed. During a tool-use loop, each request inside the loop retries independently — a failure mid-loop never replays the loop (and its tool executions) from the start. On-device (Local Model) attempts are not retried, since their failures are deterministic rather than transient
6. On failure, the next instance in the chain is tried; if all fail, the last error is shown
7. If a fallback succeeds, the response indicates which service answered
8. While the chain is being walked, the thinking indicator shows per-attempt status — the name of the service currently being tried, or the reason the previous one failed before moving on — so silent fallbacks are visible to the user
9. Entries whose context window can't fit the current chat history are skipped during the walk
10. On-device (Local Model) failures are not silently absorbed — they short-circuit the fallback chain so the user sees the actual error rather than being quietly bumped to a cloud service
11. On-device entries are also never used as fallback targets: a local model is only tried when it is the primary (first) service in the chain. A cloud-service failure never silently starts a local model load
12. Errors that a retry can't fix skip further **per-service** retries and fail that service immediately: exhausted credits or quota, an invalid API key, an unknown model, a request the provider rejects as malformed, too large or disallowed by moderation, a history that exceeds the context window, and attachments rejected locally for size or type. Unknown or generic errors stay retryable. The fallback chain still continues to the next instance. Only on-device (Local Model) failures short-circuit the entire chain

## API Formats

Most services use the **OpenAI-compatible** chat completions format. **Gemini** uses Google's native Generative Language API. **Anthropic** uses its own Messages API with `x-api-key` header authentication and a different request/response structure. **LiteRT** runs inference on-device using Google's LiteRT LM SDK -- no HTTP, no API key, fully offline.

The **OpenAI-Compatible API** service supports a custom base URL, defaulting to `localhost:11434/v1` for local Ollama setups. The base URL should include the version path segment (e.g., `http://localhost:11434/v1` or `https://my-provider.com/api/v1`), following the OpenAI SDK convention. Maxit appends only `/chat/completions`, `/responses` or `/models` to this base URL. A pasted full chat endpoint (ending in `/chat/completions`) is trimmed back to the base first, so the path is never doubled and the models list still resolves.

### OpenAI Responses API

OpenAI rejects function tools on chat completions for GPT-5.6 (Sol, Terra, Luna) and every newer family (GPT-6, …), because those models reason by default and that combination is only supported on the Responses API. Tool-enabled chats therefore require the Responses API on those models.

Maxit reads the GPT version from the model id and, from 5.6 upward, sends their requests to the Responses API instead. The switch is automatic and per model: no setting, no separate service entry, and every other OpenAI model stays on chat completions. It applies only when the request actually reaches OpenAI -- either the OpenAI service, or the OpenAI-Compatible service with a base URL pointing at `api.openai.com`. Aggregators that resell the same models translate to the Responses API on their own side and keep receiving chat completions.

Everything ahead of the wire call is shared with the chat-completions path: system prompt placement, attachment handling, tool-call pairing, and context trimming. Only the payload shape differs -- messages become input items, tool calls become `function_call` items, tool results become `function_call_output` items, and images move from a nested `image_url` object to a flat string.

Two deliberate limits:

- **Responses are not stored** (`store: false`); Maxit replays the full conversation on each request rather than chaining response ids. Provider retention policies still apply.
- **Reasoning items are not replayed.** OpenAI recommends echoing back the reasoning that preceded a tool call, but replaying one whose following item was dropped by context trimming is a hard error. Maxit trades a re-reasoned tool round-trip for a request that cannot fail that way. Reasoning summaries, when the account is eligible to receive them, are shown in the usual "Thinking" section.

Matching on the version rather than a fixed list means a new OpenAI family is routed correctly on release, with no app update.

### Session Header (OpenCode)

OpenCode Zen identifies the client behind a request by an `x-opencode-session` header and rejects requests that arrive without one. Maxit sends the current conversation id as that session id, so one chat -- however many turns, tool round-trips or bailout retries it takes -- reads as a single session upstream, and separate chats read as separate sessions. Requests that belong to no conversation (fetching the model list, connection validation) carry a session id generated once per app process instead.

An OpenAI-Compatible service whose base URL points at `opencode.ai` -- the usual way to reach the OpenCode Go subscription gateway -- is sent the header too.

The id is Maxit's own random conversation identifier; nothing about the user or the machine is derived from it. No other provider is sent the header.

## Supported Services

| Service | `id` | Requires API Key | API Type |
|---|---|---|---|
| **Atlas Cloud** | `atlascloud` | Yes | OpenAI-compatible |
| Gemini | `gemini` | Yes | Gemini native |
| Anthropic | `anthropic` | Yes | Anthropic native |
| OpenAI | `openai` | Yes | OpenAI-compatible |
| DeepSeek | `deepseek` | Yes | OpenAI-compatible |
| Mistral | `mistral` | Yes | OpenAI-compatible |
| xAI | `xai` | Yes | OpenAI-compatible |
| OpenRouter | `openrouter` | Yes | OpenAI-compatible |
| Requesty | `requesty` | Yes | OpenAI-compatible (gateway across many upstream vendors; model ids are `vendor/model`, e.g. `openai/gpt-4o-mini`; a bad key gets a 403 from `/models`, so connection validation checks the key first and then loads the model list) |
| GroqCloud | `groqcloud` | Yes | OpenAI-compatible |
| NVIDIA | `nvidia` | Yes | OpenAI-compatible |
| Cerebras | `cerebras` | Yes | OpenAI-compatible |
| Ollama Cloud | `ollamacloud` | Yes | OpenAI-compatible |
| LongCat | `longcat` | Yes | OpenAI-compatible (ships with a curated default model list; also exposes a `/models` endpoint used during validation) |
| Together AI | `together` | Yes | OpenAI-compatible |
| Hugging Face | `huggingface` | Yes | OpenAI-compatible |
| Venice AI | `venice` | Yes | OpenAI-compatible |
| Moonshot AI | `moonshot` | Yes | OpenAI-compatible |
| Z.AI | `zai` | Yes | OpenAI-compatible |
| Z.AI Coding Plan | `zai-coding-plan` | Yes | OpenAI-compatible |
| MiniMax | `minimax` | Yes | OpenAI-compatible |
| AIHubMix | `aihubmix` | Yes | OpenAI-compatible |
| Deep Infra | `deepinfra` | Yes | OpenAI-compatible |
| Fireworks AI | `fireworksai` | Yes | OpenAI-compatible |
| OpenCode | `opencode` | Yes | OpenAI-compatible (every request carries an `x-opencode-session` header — see [Session Header](#session-header-opencode)) |
| Public AI | `publicai` | Yes | OpenAI-compatible |
| AI Horde | `aihorde` | Yes (anonymous key `0000000000` allowed at lowest priority) | OpenAI-compatible (via [oai.aihorde.net](https://oai.aihorde.net/); model list is the set of text models with online volunteer workers — availability and latency vary) |
| 1min.AI | `1minai` | Yes | OpenAI-compatible (via 1min.AI's `/openai/v1` adapter; text-only, so image attachments are not sent; requests are capped at 200 messages and 100,000 characters including tool definitions) |
| Perplexity | `perplexity` | Yes | OpenAI-compatible (Sonar; ships with a curated default model list — no authenticated `/models` endpoint for Sonar; connection validation probes the chat endpoint with an incomplete body to check the API key) |
| OpenAI-Compatible API | `openai-compatible` | No (optional) | OpenAI-compatible |
| Local Model | `litert` | No | On-device (LiteRT LM) |

## Connection Validation

When the user enters or changes an API key (or base URL), the app validates the connection after an 800 ms debounce and shows a status indicator: **checking**, **connected**, **invalid key**, **quota exhausted**, **rate limited**, **connection failed**, or **local network access denied**. Validation also runs for all services when the settings screen opens. Services validate by fetching their model list — Gemini, Anthropic, and OpenAI-compatible services (including LongCat) each call their respective models endpoint. **Perplexity** is an exception: Sonar has no authenticated models list, so validation probes the chat endpoint with an incomplete body to verify the API key, then loads the curated Sonar model list. **Requesty** answers a bad key with 403 on its models endpoint, which the shared error mapping would read as content moderation, so validation checks the key against that endpoint first and only then loads the model list. On a successful connection, the available model list is refreshed.

### Local Network Servers (Android)

Android 17+ blocks all traffic to local network hosts unless the user grants the local network permission. When a service's base URL points at a LAN host (private IP range, `.local` name, or a bare hostname — loopback is exempt), the app requests the permission before validating the connection and before sending a chat message. If the user denies it, the connection status shows "local network access denied" with an "Open settings" button that jumps straight to the app's system settings page, and chat shows an actionable error instead of a silent failure. When the user returns to the app after granting the permission there, the denied connection re-validates automatically (without ever re-prompting). Other platforms don't gate local network access, so the check is a no-op there.

## Model Selection

When a connection is validated and models are fetched, the app auto-selects a model if none is chosen — first checking for a per-service default model (e.g. LongCat defaults to "LongCat-Flash-Lite", Perplexity defaults to "sonar-pro"), then preferring "kimi-k2.5" if available, otherwise the first model in the list. Services filter their model lists:
- OpenAI shows only chat-oriented models (prefix filter)
- GroqCloud shows only models marked as active
- Together AI filters by `type == "chat"` to exclude non-chat models (embedding, code, etc.)
- Other services show all non-retired models

If a stored list-selection model id is not in the fetched list, it is kept and shown as selected rather than overwritten by auto-select.

### Custom model (OpenAI-Compatible API)

The **OpenAI-Compatible API** config card keeps the normal model dropdown (when a list is available) and adds a **Custom model** checkbox below it. When checked, a free-text field appears for a model id the server accepts even if `/models` never returns it (e.g. a free tier omitted from the list). List selection and custom id are stored separately; chat uses the custom id only while the checkbox is on. Toggling the checkbox off restores the list selection without losing the typed id.

### Model Cards

The model picker modal shows each candidate as a card with consistent metadata regardless of provider:

- **Title** (top left) — a human-readable display name from the curated catalog or the provider's API; falls back to the raw model id only when no display name is available
- **Free badge** — a green "Free" label next to the title when the model is on that provider's free tier according to the curated free-tier catalog (currently **Ollama Cloud** and **OpenRouter** only). Free-ness is per service, not global.
- **Arena score** (top right) — LMArena Elo rating as colored text, gradient from green (>= 1400) through lime/yellow to orange (< 1250)
- **Detail line** (below the title) — release date, parameter count, and context window joined into a single muted line separated by ` · ` (e.g. `Mar 2025 · 70B · 200K ctx`); any missing field is simply omitted from the line

The card representing the currently selected model is highlighted with a filled accent background so users can identify their current choice at a glance when reopening the picker.

The modal includes sort chips (Date, Score, Ctx) below the search field. Tapping a chip switches which field is active; all sorts are descending (highest or most recent first), with no ascending option. Default sort is by score. When the current service list includes any free-tier model, a **Free** filter chip also appears: selected, it shows only free-tier models while the active sort still applies; if the filtered list is empty, a short empty message is shown. The Free chip is hidden for services with no free-tier catalog entries.

Context window and release date come from two sources, merged by the mapping layer: a bundled curated catalog of well-known models, and whatever the provider's own models endpoint returns (e.g. OpenAI-compat `context_window` and `created`, Anthropic `created_at`). The curated catalog wins; provider-supplied values are used only as a fallback when the catalog has no entry for that model. The catalog is hand-maintained to correct inconsistencies in what providers report. Models not present in the catalog still render — they just use whatever the API provided, and any unknown fields are hidden.

Free-tier membership is a separate curated list in `FreeTierModels.kt` (not derived from live pricing APIs at runtime). Before updating it, verify model ids, free-tier eligibility, and usage limits against the provider's current model list and pricing.

## Chat Screen Service Toggle

The chat screen service toggle lists configured services, including on-device models. There are no Free FAST or Free EXPERT entries. When the list contains more than one entry, a service icon button beside the chat input opens a dropdown with icons, names, and model IDs. The current primary is highlighted; long lists scroll.

In **Interactive UI mode**, the same control is filtered to services whose selected model supports tool use (agentic flows), and on-device services are excluded so the user cannot switch to a model that cannot produce kai-ui.

Selecting an entry reorders the configured list so the chosen service becomes first (primary). The fallback chain picks up the new order automatically. The fallback walker skips entries whose context window cannot fit the current chat history.

## Attachments

Image attachments depend on the configured service and selected model. **DeepSeek** has no vision models, so images are dropped for it everywhere (including through an aggregator). Services with both text-only and vision models (e.g. **Z.AI**) accept images only when the selected model is vision-capable. When the active service or model cannot take images, they are dropped from the request and the file picker stops offering image types; the turn still goes through as text. Unknown models are assumed image-capable. PDF attachments are advertised only by services with native document support — currently Anthropic, Gemini, OpenAI, and OpenRouter. Anthropic and Gemini encode PDFs natively in the request; the OpenAI-compatible wire path currently drops PDF binaries (images still go through as `image_url`), so a PDF chosen while OpenAI or OpenRouter is active is accepted by the UI but not sent to the model. The on-device Local Model hides file attachment affordances entirely.

## Settings UI

Users manage services through the settings screen:
- **Add** — pick from the list of available services (can add the same service multiple times); the OpenAI-Compatible API and the on-device Local Model are pinned to the top of the picker, followed by the highlighted featured provider Atlas Cloud, with the remaining providers sorted alphabetically
- **Remove** — delete an instance and its stored credentials; deletion is deferred with a snackbar "Undo" option (~4 seconds) before the service is permanently removed
- **Reorder** — drag to change priority (first = primary, rest = fallbacks)
- **Configure** — per-instance API key, model selection, base URL (OpenAI-Compatible only; optional custom model id via checkbox)

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/Service.kt` | Service definitions, all provider metadata |
| `composeApp/src/commonMain/.../data/ModelCatalog.kt` | Curated context window / release date / Arena Elo for well-known models |
| `composeApp/src/commonMain/.../data/FreeTierModels.kt` | Runtime free-tier model ids per service (Ollama Cloud, OpenRouter) |
| `composeApp/src/commonMain/.../data/ModelTransformations.kt` | Maps provider model DTOs to `SettingsModel`, merges with catalog and free-tier flags |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Service instance storage, credential persistence, migration |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Fallback chain, request orchestration |
| `composeApp/src/commonMain/.../network/Requests.kt` | HTTP clients for all API formats |
| `composeApp/src/commonMain/.../data/ModelCapabilities.kt` | Per-model gates: tool support, image support, Responses API routing |
| `composeApp/src/commonMain/.../data/providers/OpenAIResponsesInput.kt` | Translates chat-completions messages into Responses API input items |
| `composeApp/src/commonMain/.../network/dtos/openairesponses/` | OpenAI Responses API DTOs |
| `composeApp/src/commonMain/.../network/dtos/anthropic/` | Anthropic Messages API DTOs |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Connection validation, service management UI logic |
| `composeApp/src/commonMain/.../tools/PermissionController.kt` | Runtime permission requests, including the local network gate for LAN server URLs (Android 17+) |
| `composeApp/src/commonMain/.../tools/LocalNetworkUrl.kt` | Detects LAN base URLs that need the local network permission |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Chat screen, renders ServiceSelector |
| `composeApp/src/commonMain/.../ui/chat/composables/ServiceSelector.kt` | Compact service toggle dropdown |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Wires service selection and reordering |

## Removal note — 2026-10-20

The upstream built-in Free proxy, automatic Free fallback, and FAST/EXPERT entries were removed. Legacy signup-panel code is unreachable and is not part of the current user flow; only the empty-endpoint `free` compatibility sentinel remains relevant to old history/settings.
