<!-- Modified for Maxit, 2026-10-20. -->

# Reasoning Content Handling

**Last verified:** 2026-10-24

Reasoning-capable models (DeepSeek R1, GLM thinking, Qwen thinking, Kimi thinking, Magistral, gpt-oss, etc.) return their chain-of-thought separately from the final answer. Maxit handles reasoning along two axes: **wire-side** (whether to echo the trace back to the provider on the next request) and **display-side** (whether to show it to the user in the chat UI). When a turn also contains `tool_calls`, some providers require the chain-of-thought to be echoed back to preserve reasoning continuity across the tool round-trip — and others strictly reject the same field. This page documents what each provider does, what Maxit sends, and where we trade fidelity for simplicity.

## Why per-provider handling

OpenAI's chat-completions schema has no standardized place for prior-turn reasoning, so each provider invented their own. The result is three sources of variation:

- **Field name** — `reasoning_content` (DeepSeek lineage), `reasoning` (OpenRouter/Cerebras lineage), `reasoning_details[]` (preserves thought signatures for Anthropic/Gemini via OpenRouter), or embedded `<think>...</think>` tags inside `content` (MiniMax M2 native, older Magistral).
- **Whether echoing back is required** — some providers reconstruct internal state from it (Z.AI Coding Plan, OpenCode Zen via DeepSeek route, Kimi `k2.6` with `thinking.keep="all"`); others accept it as a no-op; others reject any unknown field outright.
- **Paired flags** — preservation sometimes requires an additional request flag (Z.AI `clear_thinking: false`, Fireworks `reasoning_history: "preserved"`).

## Outgoing assistant-message behavior matrix

Behavior of each provider when an `assistant`-role message with prior `tool_calls` carries a `reasoning_content` field.

| Provider | Status | Wire field expected | Notes | Source |
|---|---|---|---|---|
| Groq | **Rejected** | n/a — strip on send | Strict schema validator: `400 ('messages.N': property 'reasoning_content' is unsupported)` on any unknown assistant-message field | [console.groq.com/docs/reasoning](https://console.groq.com/docs/reasoning) |
| DeepSeek | **Required** | `reasoning_content` | Thinking-mode models (deepseek-chat, deepseek-v3.2, deepseek-v4-pro) return 400 (`The reasoning_content in the thinking mode must be passed back to the API`) if the field is missing on a follow-up turn after tool_calls. The legacy `deepseek-reasoner` doesn't expose tools so the gate `if (toolCalls != null)` keeps it unaffected | [api-docs.deepseek.com/guides/thinking_mode](https://api-docs.deepseek.com/guides/thinking_mode) |
| Cerebras | **Rejected (wrong field)** | `reasoning` (not `reasoning_content`) | GLM-4.7 multi-step returns 400 when sent `reasoning_content`; expects `reasoning`. Maxit sends neither (mode = `NONE`) | [inference-docs.cerebras.ai/api-reference/chat-completions](https://inference-docs.cerebras.ai/api-reference/chat-completions) |
| 1min.AI | **Rejected** | n/a — strip on send | The `/openai/v1` adapter returns 400 for any unsupported request field rather than ignoring it | [docs.1min.ai/docs/api/openai-compatible](https://docs.1min.ai/docs/api/openai-compatible) |
| Z.AI Coding Plan | **Required** | `reasoning_content` | Preserved Thinking is on by default on `/api/coding/paas/v4`; dropping the field breaks reasoning coherence | [docs.z.ai/guides/capabilities/thinking-mode](https://docs.z.ai/guides/capabilities/thinking-mode) |
| OpenCode Zen (DeepSeek route) | **Required** | `reasoning_content` | Pass-through gateway. When routing to DeepSeek-V4 Pro thinking, once any assistant message carries reasoning, all subsequent assistant messages must | [opencode.ai/docs/zen/](https://opencode.ai/docs/zen/) |
| Moonshot / Kimi | **Required for `kimi-k2.6` with `thinking.keep="all"`** | `reasoning_content` | Only that specific model + flag combination requires the echo. Other Kimi thinking models accept it as a no-op. Maxit does not currently set `thinking.keep` | [platform.kimi.com/docs/api/chat](https://platform.kimi.com/docs/api/chat) |
| Fireworks AI | **Accepted (documented)** | `reasoning_content` | Officially supported field on `ChatMessage`. Full preservation also requires `reasoning_history: "preserved"` on the request — Maxit does not set this | [docs.fireworks.ai/api-reference/post-chatcompletions](https://docs.fireworks.ai/api-reference/post-chatcompletions) |
| Z.AI standard | **Accepted (documented, inert without flag)** | `reasoning_content` | Preserved Thinking is opt-in on `/api/paas/v4`; without `clear_thinking: false` (which Maxit does not send) the echo is ignored | [docs.z.ai/guides/capabilities/thinking-mode](https://docs.z.ai/guides/capabilities/thinking-mode) |
| OpenRouter | **Accepted (alias)** | Canonical `reasoning`; `reasoning_content` is a documented alias. Anthropic/Gemini-via-OR need `reasoning_details[]` with thought signatures; received blocks are preserved and replayed for compatible complete exchanges | [openrouter.ai/docs/guides/best-practices/reasoning-tokens](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens) |
| Requesty | **Accepted** | `reasoning_content` | Gateway accepted the field on a tool round-trip with `openai/gpt-4o-mini` and `deepseek/deepseek-chat` (live check). Whether it reaches the upstream vendor depends on the route; reasoning traces come back as `reasoning_content` | [docs.requesty.ai](https://docs.requesty.ai) |
| LongCat | **Tolerated (undocumented)** | Schema documents `role` + `content` only; field is passed through silently | [longcat.chat/platform/docs/APIDocs.html](https://longcat.chat/platform/docs/APIDocs.html) |
| Venice AI | **Tolerated (undocumented)** | Pass-through policy: "Request fields not listed may be passed through but are not validated" | [docs.venice.ai](https://docs.venice.ai) |
| MiniMax M2 | **Mode-dependent** | Native mode expects `<think>...</think>` inside `content`; split mode expects `reasoning_details`, which Maxit now retains when supplied. Top-level `reasoning_content` remains undocumented | [platform.minimax.io/docs/guides/text-m2-function-call](https://platform.minimax.io/docs/guides/text-m2-function-call) |
| xAI, NVIDIA, Mistral, Ollama Cloud, Together, HuggingFace, DeepInfra, AIHubMix, Public AI, AI Horde, Perplexity, OpenAI, Free, OpenAI-Compatible API | **Accepted (silent ignore)** | Either documented or behave as permissive OpenAI-compatible proxies that drop unknown fields | (per-provider docs) |
| Anthropic, Gemini, LiteRT | Out of scope | These use entirely separate request DTOs (Anthropic Messages API, Gemini Generative Language API, on-device LiteRT). Reasoning is handled inside those code paths, not via `reasoning_content`. | — |

## What Maxit does today

Each service carries a reasoning request mode, either `NONE` or `REASONING_CONTENT`. When `REASONING_CONTENT` is set and the prior assistant turn carried `tool_calls`, Maxit emits the field on the next request.

Services currently set to `REASONING_CONTENT`: DeepSeek, OpenRouter, Requesty, LongCat, Venice, Moonshot, Z.AI, Z.AI Coding Plan, MiniMax, Fireworks, OpenCode.

All other services use the default `NONE` (the field is stripped on send). This is the safe default — any service we don't yet have evidence about will not regress.

Received reasoning traces remain in the saved message regardless of the wire-side decision, so display and provider replay are independent. Chat-completions reasoning details, raw Anthropic blocks (including signed/redacted thinking), Gemini thought signatures, and raw Responses output are retained without rewriting their opaque fields. Usage metadata is retained when supplied. Capture applies going forward; previously discarded metadata cannot be recovered.

Opaque continuation is scoped to the originating provider instance/model and complete tool exchanges. Reused native call identities have separate durable aliases so saved receipts remain unique without rewriting provider-native blocks. Switching targets or disabling tools preserves semantic action evidence as explicitly untrusted historical text, while incompatible opaque blocks are not replayed. This avoids sending one provider's signature format to another.

Fragmented gateway `reasoning_details` are assembled and can be replayed for OpenRouter, Requesty, MiniMax, and compatible custom endpoints. This does not enable every optional upstream thinking flag or guarantee behavior on every aggregator route.

## OpenAI Responses API

OpenAI's GPT-5.6 family (Sol, Terra, Luna) and every newer family are the one case where reasoning and tools cannot coexist on chat completions at all: the endpoint returns `400 Function tools with reasoning_effort are not supported for gpt-5.6-terra in /v1/chat/completions.` The documented chat-completions escape hatch is `reasoning_effort: "none"`, which turns the reasoning off. Maxit instead routes those models to the Responses API, where the combination is supported -- see [multi-service.md](multi-service.md#openai-responses-api).

On that path reasoning is a separate output item rather than a message field, so the chat-completions matrix does not apply. Maxit requests encrypted reasoning content, preserves the raw completed output, and replays compatible items together with their complete function-call/result exchange. Native call IDs must match before raw replay is used. Request projection keeps these exchanges together; incompatible or incomplete history becomes semantic historical evidence instead of a broken native continuation. Eligible reasoning summaries still feed the usual Thinking disclosure. Server-side response storage remains disabled.

Reasoning summaries are only returned to accounts eligible for them, so for most users these turns display no thinking trace -- the same as OpenAI on chat completions today.

## Display in chat UI

The chain-of-thought is always rendered when present. Each assistant bubble with reasoning content prepends a collapsible "Thinking" section above the answer: collapsed by default, the first line of the most recent reasoning segment shown as a preview; expanded reveals the full trace in a dim blockquote. Thinking-only turns (where the model returned reasoning but no answer, typically as a precursor to a tool call) surface as standalone reasoning bubbles while in flight; once the answer arrives, they're absorbed into the answer's grouped section so a multi-tool response shows a single "Thinking" disclosure rather than several.

Display depends on the provider returning an exposed trace/summary; an opaque signed/encrypted block is not a readable thinking transcript. The chat-completions and eligible Responses paths populate the thinking disclosure. Other retained provider metadata is preserved for continuation but is not necessarily rendered as a trace. Old conversations remain unchanged where no reasoning was captured.

## Known gaps

These remain known provider-specific fidelity limits; preserving received metadata does not prove full reasoning continuity on every live route:
- **MiniMax M2 native mode** — should embed `<think>...</think>` inside `content` rather than (or in addition to) sending a top-level field; currently the field is silently ignored.
- **MiniMax mode negotiation** — split details are retained when returned, but Maxit does not automatically select every provider-specific mode.
- **Z.AI standard `clear_thinking: false`** — without this paired request flag, Preserved Thinking is off and our echo is a no-op.
- **Fireworks `reasoning_history: "preserved"`** — without this paired request flag, Fireworks does not actually preserve reasoning across turns.
- **Cerebras `reasoning` field** — Cerebras uses `reasoning`, not `reasoning_content`. Mode is currently `NONE` because we have no `reasoning` field on the request DTO. Adding it would unlock GLM-4.7 multi-step on Cerebras.
- **Per-model dispatch** — the reasoning request mode is per-service. Moonshot is set to `REASONING_CONTENT` because of Kimi `k2.6`, but the flag is inert for older Kimi thinking models. Per-model precision would tighten this.

No new upstream reasoning flags or per-model mode negotiation were introduced for this reliability change. Live-provider verification remains separate from parser/projection tests.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/Service.kt` | Per-service reasoning mode assignment |
| `composeApp/src/commonMain/.../ui/chat/ChatUiState.kt` | Provider-message mapping and gated reasoning fields |
| `composeApp/src/commonMain/.../network/ChatStreaming.kt` | Fragmented reasoning, signature, usage, and raw-block capture |
| `composeApp/src/commonMain/.../data/ConversationContext.kt` | Complete-exchange and provider compatibility projection |
| `composeApp/src/commonMain/.../data/providers/OpenAIResponsesInput.kt` | Compatible raw continuation and native call/result mapping |
| `composeApp/src/commonTest/.../data/ConversationContinuationTest.kt` | Encrypted continuation, wire aliases, and opaque-field regressions |
| `composeApp/src/commonMain/.../data/providers/OpenAIMessages.kt` | Builds the OpenAI-compatible message list and passes the service's reasoning mode into the DTO mapper |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Stores the received reasoning trace on assistant turns (chat-completions and Responses paths) |
| `composeApp/src/commonMain/.../network/dtos/openaicompatible/OpenAICompatibleChatRequestDto.kt` | Request DTO with `@SerialName("reasoning_content")` on assistant messages |
| `composeApp/src/commonMain/.../network/dtos/openaicompatible/OpenAICompatibleChatResponseDto.kt` | Response fields for exposed reasoning and opaque continuation details |
| `composeApp/src/commonTest/.../ui/chat/ToGroqMessageDtoReasoningTest.kt` | Guards the per-mode emission behavior |
| `composeApp/src/commonMain/.../network/dtos/openairesponses/OpenAIResponsesResponseDto.kt` | Reads the `reasoning` output item's summary on the OpenAI Responses path |
| `composeApp/src/commonMain/.../data/Conversation.kt` | Persisted reasoning, usage, and opaque continuation metadata |
| `composeApp/src/commonMain/.../ui/chat/composables/BotMessage.kt` | Renders the dim-blockquote reasoning section above the answer when `reasoningContent` is supplied |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Groups all reasoning segments in a response under the answer-bearing assistant message; renders standalone thinking-only bubbles for in-flight turns |
