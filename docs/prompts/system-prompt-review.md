> **Status (2026-10-21): Historical pre-change snapshot.** The text below, including its earlier “current identity” note, is preserved for comparison. Built-in behavior now comes from [agent-instructions.md](../../composeApp/src/commonMain/composeResources/files/prompts/agent-instructions.md), independently of additive custom instructions in Settings → Profile; identity, tool-use, and action rules were updated separately. Memory promotion (`promote_learning` and heartbeat candidates) has been removed. See [System Prompts](../features/system-prompts.md) for current behavior.

> **Skills rework (2026-10-22):** The sandbox paths and slash-only skill behavior quoted below describe the snapshot, not the new contract. Android and desktop skills now use app-private storage, an installed names/descriptions catalog, and lazy `load_skill` reads; slash commands remain explicit per-turn overrides. The built-in `create-skill` requires no sandbox. Interactive UI stays archived and unloaded. Desktop tests and Android/Web source compilation passed; live-model behavior is untested. See [Skills](../features/skills.md) for imports, limits, and copy-only legacy migration.

> **Conversation reliability (2026-10-24):** The 15-round bailout, forced final-answer wording, destructive compaction, and native SDK-owned loop described below are retired. Transcript and request context are separate; complete exchanges and source-linked historical evidence replace deletion. Exact approvals and unknown-outcome enforcement are runtime boundaries, not model-granted authority. Local plain-chat fallback is allowed only before output/tool entry and removes unavailable-tool guidance. Scheduled/heartbeat instructions now describe retained background activity and unattended approval pauses, not a fresh conversation. See [Chat](../features/chat.md), [Tools](../features/tools.md), and the [implementation/verification plan](../plans/conversation-reliability.md). The original wording and line references below remain historical evidence, not the current contract.

# System prompt review: before the identity/UI change

Snapshot date: 2026-10-21. Source revision: `0484dc7e32d0cf6b686c93b0af4a8d7b76822548`.

This is a readable snapshot of the shipped English defaults **before the Maxit identity and Dynamic/Interactive UI changes**. It is a review document, not a live settings export and not a file the application loads as its system prompt.

The change in this working tree adds an explicit Maxit identity and makes model identification an on-request answer based on runtime information. Dynamic/Interactive kai-ui instructions are removed from the runtime prompt and archived separately in [docs/skills/interactive-ui/SKILL.md](../skills/interactive-ui/SKILL.md). UI entry points are paused while the renderer and conversation history are retained. The general behavior quoted below is otherwise unchanged.

Only repository sources were inspected. No app-private settings, conversations, accounts, memories, installed skills, or device data were accessed. A running installation may use a custom soul, another locale, different feature switches, and stored memories or tasks. Those values cannot be reconstructed from the defaults below.

Quoted blocks contain the exact fixed prompt wording, with source string escapes rendered as ordinary text and newlines. Commentary outside the blocks is explanatory. Braced fields in the data-layout examples are placeholders, not observed user data. Line references throughout refer to the snapshot revision, so they may move after the companion edit.

## Current identity addition (after the snapshot)

This block now comes before the soul in both chat variants, independently of the saved soul. The model/provider fields now come from the actual dispatch target, including fallback and scheduled-task selections. Blank fields are explicitly `unknown`.

> ## Identity
> You are Maxit, the personal assistant in this app. Refer to yourself as Maxit, not Max or the user's name. Your assistant identity is separate from the underlying model and provider. When asked which model you are running on, use the Model and Provider fields in the runtime context to answer, for example: "I'm Maxit, running on <model> via <provider>." If a field is unknown, say so rather than guessing. Only mention model details when asked.

Source: `ChatSystemPromptBuilder.kt`, `DEFAULT_IDENTITY_SECTION`. The remaining sections preserve the pre-change wording for your behavior review; the retired UI section is no longer appended.

## 1. How the general chat prompt is assembled

There is no single editable string containing all the instructions. The repository chooses a soul and then appends other sections in this order:

1. The saved custom soul, or the localized `default_soul` if the saved value is empty.
2. The honesty rule.
3. Tool-use guidance, when the prompt builder believes tools will be available.
4. The acting/clarification policy.
5. Memory instructions, when memory is enabled and the instruction string is nonempty.
6. Structured Learning, for remote chat with memory enabled.
7. Nonempty memory categories: Your Memories, User Preferences, Learnings, Known Issues & Resolutions.
8. For remote chat: Automation when scheduling is enabled; connected Email Accounts; Scheduled Tasks; Heartbeat Additions. The three data sections appear only when their lists are nonempty.
9. The explicitly selected active skill, if any.
10. Runtime Context: local time, time zone, UTC, platform, model ID, provider name.
11. Before the companion change only: one Dynamic UI or Interactive UI section for remote chat, when that mode is selected.

A custom soul replaces only item 1. It does not replace the honesty rule, acting policy, or the other independently composed sections.

The local/on-device variant keeps the soul, honesty, applicable tool-use guidance, acting policy, basic memory instructions, memory entries, active skill, and runtime context. Its memory-category sections share a 2,000-character budget, including their headings and formatting. Entries are considered in category order and stopped at the first entry that does not fit within that category's remaining budget. Remote memory-category rendering has no corresponding builder budget. Local chat omits Structured Learning, Automation, Email Accounts, Scheduled Tasks, Heartbeat Additions, and both UI sections.

Sources: `RemoteDataRepository.kt:1808–1908`; `ChatSystemPromptBuilder.kt:147–243,278–309`. Full repository paths are in section 9. The old `CHAT_LOCAL` enum comment says memory dumps are dropped, but the implementation appends budgeted memories to both variants; this review follows the implementation.

## 2. Exact general behavioral text

### Default soul

Source: `composeApp/src/commonMain/composeResources/values/strings.xml:444`, resource `default_soul`.

> You're not a chatbot. You're a personal assistant who grows with your user.
>
> ## How to Be
>
> **Be genuinely helpful.** Skip the "Great question!" and "I'd be happy to help!" — just help. Actions speak louder than filler words.
>
> **Have opinions.** You're allowed to disagree, prefer things, or find stuff interesting. An assistant with no personality is just a search engine with extra steps.
>
> **Be resourceful.** Try to figure it out from context and your memories before asking. Come back with answers, not questions.
>
> **Be concise.** Short and clear by default. Go deeper when the topic calls for it.
>
> ## Boundaries
>
> - Respect privacy. Don't repeat sensitive information unnecessarily.
> - When in doubt about an action, ask first.
> - Be honest when you don't know something.

### Honesty

Always appended, even with a custom or empty soul. There is no heading in the actual prompt for this sentence.

Source: `ChatSystemPromptBuilder.kt:82–83,167–170`.

> Do not fabricate tool outputs, file contents, citations, or completed work.

### Tool use

Appended only when `hasTools` is true. Source: `ChatSystemPromptBuilder.kt:91–96,176–179`.

> ## Tool Use
> Use tools to verify work and resolve ambiguity. Don't ask the user for lookups you can do yourself. Check for a tool before saying a capability is unavailable. Summarize noisy output and state any uncertainty — don't dump raw logs.

### Acting and clarifying

Always appended. Source: `ChatSystemPromptBuilder.kt:103–108,180–181`.

> ## When to Act
> Take the most reasonable interpretation and proceed. Ask at most one clarifying question, only when genuinely blocked. If a first attempt fails, try another approach or explain the blocker. See work through to a usable result.

### Basic memory instructions

Default memory instruction text; enabled memory is required for this to be included. It has no heading of its own in the assembled prompt. The stored instruction value can differ from this default.

Sources: `AppSettings.kt:259–265,634–640`; `RemoteDataRepository.kt:1810–1820`.

> You have persistent memory across conversations. All your stored memories are listed in the system prompt grouped by category.
>
> When you learn important information about the user (name, preferences, projects, goals, etc.), proactively use the memory_store tool to save it.
> Use the memory_forget tool to remove outdated or incorrect memories.
> Do not store trivial or transient information.

### Structured learning

Remote chat only, with memory enabled. Source: `ChatSystemPromptBuilder.kt:116–122,191–194`.

> ## Structured Learning
> Use memory_learn to record categorized learnings:
> - Record user corrections and preferences as PREFERENCE entries
> - Record things that worked well as LEARNING entries
> - Record error resolutions as ERROR entries
> Use memory_reinforce when a stored learning produced a good outcome.

### Automation

Remote chat only, with scheduling enabled. Source: `ChatSystemPromptBuilder.kt:130–138,217–221`.

> ## Automation
> Every form of "run something without the user typing it" goes through `schedule_task`. The tool has three mutually exclusive triggers:
> - `execute_at` — one-off at a specific datetime (reminders, "check back at 3pm").
> - `cron` — recurring on a schedule ("every morning at 8", "every 15 minutes").
> - `on_heartbeat: true` — appended to every heartbeat self-check. Use this when the user asks for *standing* heartbeat behaviour (e.g. "greet me on every heartbeat", "always summarize new emails", "flag overdue tasks each check"). These are `HEARTBEAT` trigger tasks and show up in `list_tasks` alongside time/cron tasks.
> Each scheduled or heartbeat run starts fresh, so embed any context the prompt needs. Use `list_tasks` / `cancel_task` to inspect or remove.
> Heartbeat itself (on/off toggle, interval, active hours) is user-controlled in Settings → Agent → Heartbeat — you cannot enable, disable, or reschedule it. If the user asks for recurring updates and heartbeat seems off, either schedule a cron task or tell them to enable Heartbeat in settings — never claim to have "enabled" or "turned on" heartbeat.

### Connected email accounts and send confirmation

Remote chat only, with email enabled and at least one connected account. Actual account/status lines follow these fixed instructions.

Sources: `ChatSystemPromptBuilder.kt:311–334`; `RemoteDataRepository.kt:1827–1842`.

> ## Email Accounts
> The user has these email accounts connected. Use them via the existing email tools — do NOT suggest adding, re-authenticating, or connecting a new account unless the user explicitly asks.
> **Sending policy**: before calling `compose_email` or `reply_email`, present the full draft (to, subject, body) in chat and get explicit confirmation ("send it" / "looks good" / "yes"). Never call the send tools on the same turn you draft — the user must have a chance to correct tone, recipients, or content first. If the user later says "change X and send", re-present the updated draft and confirm again.

This is a model-facing instruction. The inspected send-tool implementations validate arguments and send over SMTP; they do not implement a draft-confirmation state machine. Also, this paragraph is absent when no account rows are available, even if email tools are enabled. Sources: `tools/EmailTools.kt:370–485`; `tools/AgentToolSet.kt:40–42`.

## 3. Conditional data, skills, and runtime information

### Stored memories

Nonempty categories are rendered as follows. These are layout templates only:

```text
## Your Memories
- **{key}**: {content}

## User Preferences
- **{key}**: {content}

## Learnings
- **{key}** (reinforced {hitCount}x): {content}

## Known Issues & Resolutions
- **{key}**: {content}
```

The memory content itself can influence subsequent behavior. No actual entries are reproduced here. Disabling memory removes both the instruction string and the memory entries from the normal chat prompt. Sources: `RemoteDataRepository.kt:1810–1821`; `ChatSystemPromptBuilder.kt:196–209,278–309`.

### Tasks and connected-account status

Scheduled task rows show the description, ID, scheduled time, and an optional cron expression. They do not include the task's execution prompt in this section:

```text
## Scheduled Tasks
- **{description}** (id: {id}, scheduled: {scheduledAt}) [cron: {cron}]
```

Standing heartbeat tasks do include their saved prompts, preceded by this exact instruction:

> ## Heartbeat Additions
> Standing instructions the user has set to run on every heartbeat (trigger=HEARTBEAT). Don't duplicate these when the user asks for similar behaviour; cancel via `cancel_task` if they want one removed.

Each following row has the form `- **{description}** (id: {id}): {prompt}`. Connected email rows contain the email address and either unread count with an optional last-sync timestamp, or `sync failing — {lastError}`. Message bodies are not included in the general chat account-status section.

Sources: `ChatSystemPromptBuilder.kt:311–367`; `RemoteDataRepository.kt:1823–1842`.

### Active skill

An explicitly selected skill is appended for the current turn under `## Active skill: {displayName}`. Its body is inserted as text, with leading/trailing whitespace trimmed. If it has bundled files, a sorted file list follows under:

> Bundled files (available at `~/skills/{id}/` in the Linux sandbox):

`{id}` above is a placeholder. The `/skill-id` selection therefore adds another behavioral layer; changing the soul does not rewrite that skill. No installed skill body was read from an app instance for this snapshot.

Sources: `ChatSystemPromptBuilder.kt:245–268`; `RemoteDataRepository.kt:833–855,1886–1908`.

### Runtime context

These fields are generated when the prompt is built; the template below deliberately contains no invented clock, location, or model values:

```text
## Context
- Local time: {local ISO datetime with offset} ({IANA time-zone ID})
- UTC: {UTC ISO datetime}
- Platform: {platform display name}
- Model: {model ID}
- Provider: {provider display name}
```

The local time comes first so relative times can be interpreted in the device's system time zone. The UTC value is also included. Sources: `ChatSystemPromptBuilder.kt:369–391`; `RemoteDataRepository.kt:1844–1869`.

**Pre-change mismatch to keep in view:** the prompt builder obtains the provider from `currentService()` (the first configured service) and the model from the legacy service-level `getSelectedModelId(service)`, with an on-device fallback for a blank ID. Actual remote requests use `getInstanceEffectiveModelId(instanceId)`, including a custom model ID, before falling back to the legacy value. The prompt can therefore name a different model even for the primary request. Normal chat also builds this prompt once before walking the fallback chain, so a fallback response can receive the primary provider/model labels. Background `askWithTools` can choose a different instance while still obtaining the prompt from the current primary context. This is a source-level finding, not a claim about the user's actual selected models.

Sources: `RemoteDataRepository.kt:231–239,935–972,1631–1641,1844–1869,2143–2167`; `AppSettingsService.kt:37,94–121`. The same pre-change model lookup also influences whether Tool Use guidance is included. The companion identity work should be checked against the actual dispatched instance rather than treating the old Context block as authoritative.

## 4. Heartbeat and scheduled runs are separate user messages

Heartbeat does not replace the general system prompt. The scheduler builds heartbeat text and calls `askWithTools`, which creates a fresh history containing that text with the **user** role, alongside the applicable chat system prompt. Scheduled tasks similarly pass their saved `task.prompt` as a fresh user message. They do not inherit the open chat's complete message history.

Sources: `TaskScheduler.kt:114,162–174`; `RemoteDataRepository.kt:2143–2167`; `HeartbeatPromptBuilder.kt:5–7,57–207`.

### Exact default heartbeat opening

A configured custom heartbeat prompt replaces this opening. Source: `HeartbeatManager.kt:149,175–179`.

> [HEARTBEAT] This is an automatic self-check. Review your memories and pending tasks. If everything looks good and nothing needs attention, respond with exactly: HEARTBEAT_OK
> If something needs attention (stale memories, due tasks, user follow-ups), address it.
> You cannot enable, disable, or reschedule heartbeat — the schedule is a user setting.

### Conditional heartbeat instructions

Each block is added only when its corresponding data list is nonempty. These are the exact fixed instructions; actual task prompts, email/SMS previews, notifications, and memory content are not included in this review.

Source: `HeartbeatPromptBuilder.kt:72–84`.

> ## Heartbeat Additions
> Standing instructions the user asked to run on every heartbeat. Address each in your response alongside the main self-check — if all are satisfied and nothing else needs attention, respond with your acknowledgement rather than HEARTBEAT_OK (the additions are the attention).

Source: `HeartbeatPromptBuilder.kt:132–150`.

> ## New Emails
> These arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.

Source: `HeartbeatPromptBuilder.kt:152–167`.

> ## New SMS
> These SMS arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.

Source: `HeartbeatPromptBuilder.kt:169–189`.

> ## New Notifications
> These notifications arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.

The promotion paragraph has a runtime count. With that count represented by `{first candidate hitCount}`, its template is:

> ## Promotion Candidates
> These memories have been reinforced {first candidate hitCount}+ times. Consider using the promote_learning tool to add well-established patterns to your soul/system prompt:

Source: `HeartbeatPromptBuilder.kt:191–205`. Candidate rows contain key, hit count, category, and content. Other conditional sections are `## Previous Heartbeat Results` (numbered prior responses), `## Pending Tasks`, and `## Email Status`; those sections add data without another fixed behavioral paragraph. Email, SMS, and notification inclusion follows their feature switches. Notification input is limited to the newest 20 records per heartbeat build. Sources: `HeartbeatPromptBuilder.kt:86–130`; `HeartbeatManager.kt:94–157,174`.

## 5. Instructions and limits beneath the assembled text

### Native tool descriptions are another input

The model receives enabled tool names, descriptions, parameter descriptions, and schemas separately from the prose system prompt. Those descriptions can contain instructions. For example, `get_local_time` says to call it first for relative dates; `open_url` says it opens a link for the user and does not return page content. The `memory_store` description separately asks for proactive remembering. Changing only the soul would leave those instructions in place.

The shared tool set is built from common tools, enabled memory tools, scheduling/learning-promotion tools, enabled email tools, platform-specific tools, and enabled MCP server tools. Tool schema conversion preserves descriptions for OpenAI-compatible, Responses, Gemini, and Anthropic requests. This review identifies the layer rather than duplicating every parameter schema.

Sources: `tools/AgentToolSet.kt:20–48`; `tools/CommonTools.kt:128–130,208–215,280–359`; `network/Requests.kt:658–699`.

`promote_learning` is particularly relevant to later behavioral edits. Its exact tool description is:

> Promote a well-established memory into the soul/system prompt. Use this for patterns that have been reinforced multiple times and should become permanent behavior.

Its implementation appends the supplied `soul_addition` to the saved soul and removes the original memory. When the saved soul is empty, it stores only that addition; it does not first copy the localized default soul. Subsequent prompt builds then treat the addition as the custom soul. The tool implementation checks that the memory exists but does not enforce a reinforcement threshold or a user-confirmation step. The shared registry gates this tool on scheduling, not specifically on being inside a heartbeat run.

Sources: `tools/HeartbeatTools.kt:17–54`; `tools/AgentToolSet.kt:35–38`; `RemoteDataRepository.kt:1808–1809`.

### Provider message placement

- OpenAI-compatible chat prepends the assembled text as a `system` message. Responses API conversion keeps that role while translating the message format.
- Gemini receives it in `systemInstruction`; Anthropic receives it in the top-level `system` field.
- Tool calls/results and previous user/assistant messages are separate history input. The OpenAI-compatible adapter removes unpaired or currently undeclared tool calls/results before sending them. Responses conversion does not replay reasoning items.
- The JVM/Android LiteRT engine passes the sanitized prompt as `ConversationConfig.systemInstruction`, passes prior user/model turns as initial messages, and gives the final user turn to the engine separately. Its native library manages automatic tool calling when tools are present.

The inspected adapters do not append another general personality paragraph. This is not a claim about hidden provider-side instructions or a downloaded model's internal chat template, neither of which was exported here.

Sources: `data/providers/OpenAIMessages.kt:12–36,38–119`; `data/providers/OpenAIResponsesInput.kt:15–75`; `network/Requests.kt:199–228,451–474`; `inference/LiteRTInferenceEngine.kt:295–345` (JVM-shared source).

### On-device tools and plain-chat retry

The local allowlist is `get_local_time`, `get_location_from_ip`, `web_search`, `open_url`, `memory_store`, `memory_forget`, `memory_reinforce`, and `execute_shell_command`. A tool must also be enabled/available, and the model's capability declaration must not explicitly rule out function calling. Scheduling, email, MCP tools, `memory_learn`, and `promote_learning` are not in that allowlist.

The local engine path catches a non-cancellation `RuntimeException` from the first chat attempt and retries once with an empty tool list. Its comment describes malformed LiteRT tool-call output as the motivation, but the catch is broader than a parser-specific error. The retry reuses the same system prompt, so Tool Use wording can survive even though that retry has no tools. This is distinct from switching to another provider: local errors do not silently fall back to the cloud, and on-device services are not used as automatic fallback targets in normal chat.

Sources: `RemoteDataRepository.kt:118–133,205–225,507–580,820–830,972–983`; `inference/LiteRTInferenceEngine.kt:316–326`.

### Tool-loop bailout text

The remote loop stops after 15 tool-call rounds or when the same ordered batch of tool-call signatures is requested a third consecutive time. It then asks for a final answer without tools. Exact limit message:

> You have reached the tool call limit. Please respond with the best answer you have so far based on the information gathered.

Exact repetition message:

> You are repeating the same tool calls. Please respond with the best answer you have so far.

For OpenAI-compatible/Responses requests this is an additional user message. Gemini and Anthropic prepend the bailout sentence to the system prompt for the final plain call. This can explain why a model stops acting even though the general policy says to see work through.

Sources: `RemoteDataRepository.kt:109–111,159–164,1081–1086,1125,1164,1171–1201,1244–1295`.

### Conversation compaction

When estimated history plus prompt size exceeds 70% of the primary model's estimated context window, and there are more than four user exchanges, the repository keeps the four most recent exchanges and asks for a summary of the older user/assistant text. The exact instruction before the transcript is:

> Summarize this conversation concisely, preserving key facts, decisions, and any information the assistant would need to continue helping. Be brief but complete:

Two newlines and the transcript follow. This is sent through `askSilently` as a user message with the applicable chat system prompt. The resulting summary is inserted into history as an assistant message in the form `[Conversation summary: {summary}]`. If summarization fails, older turns are dropped only when the estimated context window is already exceeded; cancellation propagates. Request-time context trimming also limits what a provider sees, without necessarily removing the visible conversation rows.

Sources: `RemoteDataRepository.kt:113–116,1475–1498,1501–1561,2170–2186`. Compaction therefore changes the evidence available to later answers; it is not another editable soul paragraph.

## 6. Dynamic/Interactive UI is archived separately

Before the companion change, Dynamic UI told the model to proactively use kai-ui for input, choices, structured information, and multi-step workflows. Interactive UI required the entire response to be one kai-ui fence and said that text outside it would be invisible. It also required callback actions, prohibited app-provided navigation controls, and ruled out asynchronous loading or persistent client-side state. Both modes included the component catalog and layout instructions.

Their full instructions and component reference belong in [docs/skills/interactive-ui/SKILL.md](../skills/interactive-ui/SKILL.md), separate from this general-behavior review. The archive is reference material; merely placing it under `docs/skills` does not activate it in a chat. Removing those runtime instructions is part of the companion change, not an unannounced rewrite of the general behavioral defaults above.

Pre-change sources: `ChatSystemPromptBuilder.kt:236–242,393–434,442 onward`. The static catalog and UI response constraints are intentionally not duplicated here.

## 7. Tensions to resolve when behavior is edited later

- **Resourcefulness versus asking first.** The soul says to return with answers rather than questions and to ask first when doubtful about an action. When to Act says to choose a reasonable interpretation and ask only when genuinely blocked. The defaults do not define a precise boundary between useful autonomy and an action that needs confirmation.
- **The clarification limit.** “Ask at most one clarifying question” does not say whether that limit is per turn or per task. Email has a more specific rule requiring a draft, a later confirmation, and renewed confirmation after edits. Rewriting the general limit without preserving that distinction could change sending behavior.
- **Memory recording versus consent and privacy.** The shipped default asks for proactive recording of names, preferences, projects, and goals. It does not require asking before recording personal details. “Respect privacy” and “Do not store trivial or transient information” do not define consent, sensitive categories, or retention. The same proactive instruction also exists in a native tool description.
- **Learning can alter the soul.** `promote_learning` can persist new behavior and remove the original memory. On an empty saved soul, its addition becomes the whole custom soul, replacing the default on the next build. A review of future behavior should include that path, not only `default_soul`.
- **Claims about context can exceed what is supplied.** Basic memory guidance says “All your stored memories are listed,” but local rendering has a 2,000-character budget, and provider context limits can further restrict history. The pre-change runtime model/provider labels also have the mismatch described in section 3.
- **Instructions are conditional, not universal guards.** Email confirmation text depends on connected-account rows; tool-use text depends on the pre-change availability calculation; a local plain-chat retry can retain tool instructions. These conditions matter when interpreting the snapshot or testing a later edit.

These are review findings, not changes made by this document. No particular new policy is assumed here.

## 8. Source map for later edits

All paths are relative to the repository root. For abbreviated references above, the general data directory is `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/`.

- `composeApp/src/commonMain/composeResources/values/strings.xml:444` — exact English default soul. Other `values-*` resource directories contain localized defaults.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/ChatSystemPromptBuilder.kt:82–138,147–434,442 onward` — fixed general policies, section ordering, conditional data layouts, and pre-change UI instructions/catalog.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/AppSettings.kt:251–265,634–640` — saved soul, memory toggle, default memory guidance.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/AppSettingsService.kt:37,94–121` — legacy service model selection versus per-instance effective model selection.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/RemoteDataRepository.kt:109–164,205–239,507–580,701–830,935–1012,1014–1295,1475–1561,1631–1641,1808–1908,2143–2209` — runtime assembly, dispatch, fallbacks, tool loops, compaction, background/silent paths.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/HeartbeatManager.kt:94–157,172–179` — heartbeat input selection and default opening.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/HeartbeatPromptBuilder.kt:57–207` — heartbeat user-message sections and instructions.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/TaskScheduler.kt:114,162–174` — scheduled-task and heartbeat dispatch.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/tools/AgentToolSet.kt:20–48` — enabled tool composition, including MCP and platform extras.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/tools/CommonTools.kt:128–130,208–215,280–370` — behavior-bearing time, URL-opening, and memory tool descriptions.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/tools/HeartbeatTools.kt:17–54` — learning promotion and persisted soul modification.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/tools/EmailTools.kt:370–485` — reply/compose descriptions and send implementations.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/providers/OpenAIMessages.kt:12–119` — system-message placement and tool-history sanitization.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/providers/OpenAIResponsesInput.kt:15–75` — Responses translation and reasoning replay behavior.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/data/providers/AnthropicMessages.kt:11–54` — history/tool-result message grouping.
- `composeApp/src/commonMain/kotlin/com/maximiliano/maxit/network/Requests.kt:199–228,451–474,658–699` — provider system fields and native tool-schema conversion.
- `composeApp/src/jvmShared/kotlin/com/maximiliano/maxit/inference/LiteRTInferenceEngine.kt:295–345` — local system instruction, history, sampling, and native tool calling.

Editing this review file changes no application behavior. Use the source locations above when a behavioral change is actually requested.
