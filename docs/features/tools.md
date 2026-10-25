<!-- Modified for Maxit, 2026-10-25. -->

# Tools

**Last verified:** 2026-10-25. See the [verification plan](../plans/conversation-reliability.md) for remaining provider and real-device acceptance checks.

Maxit's tools feature allows the AI to execute external functions during conversations — web search, notifications, calendar events, shell commands, memory operations, and more. Tools are defined with a schema, executed with safety guards, and managed through per-tool toggles in settings.

## Concepts

### Tool

An executable function the AI can invoke during a conversation. Each tool declares a schema (name, description, parameters), a timeout (default 30 seconds), and an execute method that receives parsed arguments and returns a result.

### Tool Schema

The machine-readable definition sent to the AI provider so it knows which tools are available and how to call them. Contains the tool name, a natural-language description, and a map of parameter definitions (type, description, required flag).

### Tool Info

Display metadata used in the settings UI. Contains an id, human-readable name and description (with optional localized string resources), the current enabled state, and a flag for whether the tool gets its own switch in the Tools tab. Returned by the platform layer for all tools regardless of whether they are currently enabled.

Every tool a platform can execute must have a definition, including tools no per-tool switch controls. Chat resolves a tool's display name by looking its id up in this list, so a missing definition surfaces the raw id (`check_notifications`) instead of a name. Tools whose availability is decided elsewhere — a master toggle in Settings → Agent, or a platform capability — are marked as not user-toggleable at their declaration, which is what keeps them out of the Tools tab without hiding them from the name lookup.

### Tool Executor

The component that looks up a tool by name, parses JSON arguments into a typed map, runs the tool with its declared timeout, validates execution permission, and returns complete results or explicit errors. Acts as the bridge between raw AI tool-call JSON and the typed tool implementations.

An argument explicitly sent as JSON `null` is treated as not provided, so the tool falls back to its default instead of receiving the text "null". Failures (unknown tool, unparseable arguments, timeout, exception) come back to the AI as a JSON object with `success: false` and an `error` message, built as proper JSON so messages containing quotes or newlines stay valid.

## Tool-call protocol recovery

For remote OpenAI-compatible Chat Completions, native structured calls take precedence over answer text. Both streamed and complete JSON responses reject blank call identities, unsupported call types, duplicate identities, non-object or malformed arguments, oversized arguments, and batches above 256 calls before dispatch. Received answer text is retained on failure; invalid responses do not trigger automatic generation replay.

A complete answer consisting only of a `tool_calls` JSON envelope for declared tools, without native calls, gets **one correction continuation per attempt**. The original reply remains saved as text; the continuation receives the transcript, prior outcomes, and a short instruction to use the actual tool interface or quote a requested example. The printed JSON is **never converted into executable calls**. A real subsequent call still follows the ordinary execution guard, exact approval, dispatch, and receipt rules. If the model repeats the bare envelope later in the same attempt, execution pauses with an explanation rather than looping or reporting success.

Quoted/fenced examples, surrounding prose, ordinary JSON containing other data, unknown tool names, and incomplete envelopes do not trigger this narrow recovery. Reasoning-only content is not inspected for recovery. A raw, unquoted example consisting exactly of a supported call envelope is inherently ambiguous: it may receive the one correction request, but the text itself never runs. This is not a general JSON-to-action parser or a guarantee that every provider/model will follow the correction.

Legacy wrapped-call compatibility accepts only actual answer content consisting entirely of complete wrappers for declared tools. Native calls still take precedence. Reasoning, mixed prose, quoted/fenced examples, nested wrappers, unknown tools, and incomplete batches stay unchanged as text; no valid-looking fragment is extracted from an otherwise invalid batch. This intentionally stops accepting legacy calls mixed with prose. Standalone unquoted wrappers remain a legacy call format and still pass through ordinary execution guards and approvals.

Regression tests cover SSE and complete JSON responses, standalone legacy wrappers, example isolation, exact approvals, repeated faults, interrupted streams, gap-free output recovery, and saved-text preservation. Provider and real-device execution require separate acceptance testing.

## Responses stream completion

The Responses transport retains fully completed streamed output items when a successful terminal event omits its output list. Text, native calls, and opaque continuation data keep their original order. A supplied final output list wins instead of duplicating earlier copies. Empty-terminal reconstruction requires completed items at every index starting from zero, including items observed only through text or argument fragments. Unindexed output fragments or missing received answer text stop recovery and preserve the saved checkpoint. Calls still require terminal success, complete item evidence, unique nonblank identities, names, object arguments within 1,048,576 characters, and batches of at most 256 calls. Missing completion, unfinished items, conflicting item copies, or invalid arguments fail without dispatch or automatic replay. Buffered reconstruction is limited to 512 output indexes and 8,388,608 characters; these are per-response bounds, not a total-run ceiling.

An event stream is also recognized when the response header is missing or incorrectly says JSON. The bounded prefix check does not consume text, execute content, or turn ordinary JSON answers into tool calls. All four stream formats accept one initial byte-order mark without losing the first event; later marks inside event data are retained.

## Available Tools

### Common (cross-platform)

| Tool | Description | Default |
|---|---|---|
| `web_search` | Search the web for current information | Enabled |
| `get_local_time` | Get the current local date and time | Enabled |
| `get_location_from_ip` | Get estimated location from IP address | Enabled |
| `open_url` | Open a URL, link, or local file on the device | Enabled |
| `fetch_url` | Fetch an http(s) URL and return the response body to the agent (GET, POST, HEAD). Blocks loopback, private, link-local and CGNAT addresses (including IPv4-mapped IPv6 and numeric shorthands like `2130706433`), checked again on every redirect hop (GET/HEAD follow up to 5 redirects; POST never redirects). Hostnames that only resolve to private addresses are not detected. Reads at most 512 KB of the body; that bounded result is retained without an additional global 20K cut. Used for reading pages and acting on links from emails (e.g. RFC 8058 one-click unsubscribe). | Enabled |

#### open_url platform behavior

The `open_url` tool accepts both web URLs and `file://` URIs. Each platform opens URLs using its native mechanism:

- **Android** — Uses `ACTION_VIEW` intents. `file://` URIs are limited to the Linux sandbox's `/root` (e.g. `file:///root/site/index.html`) and resolved exactly like `open_file`, then served through FileProvider so the file opens in the appropriate app (e.g. `.html` files open in the browser). Any other local path is refused, so the model can't hand app-private files (settings, databases) to another app.
- **Desktop** — Uses `java.awt.Desktop.browse()`.
- **iOS** — Uses `UIApplication.openURL()`.
- **Web** — Uses `window.open()` with `_blank` target.

### Skills (Android and desktop)

| Tool | Description |
|---|---|
| `load_skill` | Read an installed skill's instruction body by id, or a bundled text file by id and relative path |
| `create_skill` | Self-review-guided creation of a new `SKILL.md` plus optional bundled files at the user's explicit request; validates paths, encoding, limits, and non-overwriting installation without running code |

The model receives installed skill names and descriptions in its prompt and loads the body only when useful. A leading `/<skill-id>` remains an explicit override for that turn. `load_skill` reads app-private bundles and built-in resources without a sandbox; it rejects paths outside the selected bundle and does not return binary assets as text. Neither installation nor loading executes bundled scripts or grants additional permissions. See [skills.md](skills.md) for imports, storage, and limits.

### Memory

| Tool | Description |
|---|---|
| `memory_store` | Store or update a memory with a descriptive key |
| `memory_forget` | Delete a stored memory by its exact key |
| `memory_learn` | Store a structured learning with a category |
| `memory_reinforce` | Reinforce a stored memory by incrementing its hit count |

Memory tools cannot be individually disabled. They are available whenever the memory feature is enabled (the default). See [memories.md](memories.md).

### Scheduling & Heartbeat

Scheduling tools are available when the scheduling feature is enabled (the default). Heartbeat additions use `schedule_task`; there are no dedicated heartbeat or memory-promotion tools. See [tasks.md](tasks.md) and [heartbeat.md](heartbeat.md).

| Tool | Description |
|---|---|
| `schedule_task` | Schedule a prompt to run later, on a recurring schedule, or on every heartbeat |
| `list_tasks` | List scheduled tasks with their ids and status |
| `cancel_task` | Cancel a scheduled task by id |

### Email

Email tools are available whenever the email feature is enabled (the default) on Android, iOS, and desktop — including before any account is connected, so the assistant can connect one from chat. The web build has no email tools.

| Tool | Description |
|---|---|
| `setup_email` | Connect an email account (auto-detects server settings for common providers) |
| `check_email` | List emails that arrived since Maxit last surfaced new mail |
| `read_email` | Read the full body of an email |
| `search_email` | Search the whole inbox by sender, subject, or date |
| `reply_email` | Reply to an email with proper threading |
| `compose_email` | Compose and send a new email |

Tools that operate on a specific account accept either the internal account ID or the account's email address, and the account reference can be omitted entirely when only one account is connected. When a reference doesn't match any connected account, the error lists the connected accounts so the assistant can correct itself instead of concluding that no account is set up.

After a reply or a newly composed email is sent, a copy of the outgoing message is saved to the account's Sent folder on the mail server so sent mail stays auditable from any mail client. The folder is resolved in order: the folder configured during account setup, the Sent mailbox the server itself advertises (which also covers localized folder names), then common Sent folder names. If none exists — typical for freshly created mailboxes — the folder is created and the copy saved there. Gmail accounts are skipped because Gmail stores sent messages itself. Saving the copy is best-effort — if it fails, the send still succeeds and the tool result carries a warning instead.

### SMS and notifications (Android, FOSS build only)

SMS tools (`check_sms`, `read_sms`, `search_sms`, `send_sms`, `reply_sms`) and notification tools (`check_notifications`, `read_notification`, `search_notifications`) exist only on the FOSS Android build. They follow their opt-in toggles in Settings → Agent plus the matching system permission or listener access, and have no per-tool switches. See [sms.md](sms.md) and [notifications.md](notifications.md).

### Platform-specific (Android)

| Tool | Description | Default |
|---|---|---|
| `send_notification` | Send a push notification to the device | Enabled |
| `create_calendar_event` | Create a calendar event on the device | Enabled |
| `set_alarm` | Set an alarm or countdown timer | Enabled |
| `execute_shell_command` | Execute a shell command in the Linux sandbox | Follows the sandbox switch; no switch of its own |
| `manage_process` | Manage background shell processes (list, log, kill, remove) | Follows the sandbox switch; no switch of its own |
| `ssh_configure_host` | Register a named SSH host alias for the Linux sandbox so subsequent shell calls can use `ssh <alias>`. Rides along whenever the sandbox is installed and enabled. SSH multiplexing (ControlMaster) is intentionally not enabled — Android blocks the `link()` syscall OpenSSH uses for control sockets, so every `ssh` call does a full TCP and authentication handshake. | Follows the sandbox switch; no switch of its own |
| `open_file` | Open a file from the sandbox in an Android app (browser, image viewer, etc.) | Enabled |

#### Linux Sandbox (Android)

When the Linux Sandbox is set up and enabled, `execute_shell_command` routes commands through proot into a Debian or Alpine rootfs instead of running them in Android's native shell. This provides:

- A full Linux userland with bash, coreutils, and standard utilities
- Package management via `apt` (Debian) or `apk` (Alpine)
- Optional Python installation (`python3`, pip)
- Network access (shares the host network stack)

The tool's description is composed per distribution, so the model is told the right package manager, the right distribution name and the right pre-installed set. Handing it "Alpine" and an `apk add` example while a Debian rootfs is mounted costs turns on commands that do not exist.

**Setup flow:** The sandbox requires a one-time setup that downloads a rootfs — Debian (~150 MB, the default and the one Maxit Build shares) or Alpine (~4 MB). Proot is bundled in the APK as a native library. After setup, users can optionally install the extra package bundle. The distribution can be changed at any time afterwards; each keeps its own install, so switching re-points the tool rather than reinstalling anything, and the tool's description follows the newly selected system.

**Mirror fallback (Alpine):** The downloader tries the primary Alpine CDN first, then falls back through a list of official mirrors (kernel.org, RWTH Aachen, ETH Zürich, Waterloo, Tsinghua) so setup succeeds in regions where the primary CDN is unreachable. The same mirror list is also used to pick `/etc/apk/repositories` during setup — `apk update` is retried against each mirror until one succeeds, so later `apk add` calls resolve through a reachable mirror. Debian resolves a single architecture-matched URL from the Linux Containers image index.

**Architecture:** Proot is a user-space chroot implementation that intercepts syscalls via ptrace. No root access is required. The rootfs and tmp directory live in the app's internal files directory — one directory per distribution, which is what lets both exist and be switched between — and `/root` is part of the rootfs so binaries installed into the home directory can actually be executed. `/root/projects` is bind-mounted from the externally-visible app directory. FileProvider is configured for both areas, so `open_file` works anywhere in the tree. Sandboxes installed before the Debian option existed keep their older layout, with the whole of `/root` bound in from external storage.

**Settings:** The sandbox section appears in Settings > Linux Sandbox on Android and contains a single card — titled with the installed distribution, or the one about to be installed — with a distribution picker (before install only), the install / install-basic-packages / uninstall actions and the "use sandbox vs native shell" toggle. Day-to-day usage (terminal, file browser, packages) is **not** in Settings — it lives behind the chat-bar shortcut.

**Chat-bar toggle:** A terminal icon next to the new-chat button in the chat top bar (Android only) toggles the chat body between the conversation view and the inline sandbox view — no navigation, no separate screen. The icon adopts a primary-tinted "selected" pill while the sandbox is open, and the message-input bar is hidden so the terminal/file browser have full vertical space. The other top-bar buttons (settings, history, +, TTS) stay visible and operational; tapping **+** or selecting a saved chat from the history sheet auto-collapses the sandbox view so the user lands on the chat they just chose. When the sandbox is ready the inline view hosts three sub-tabs — **Terminal** (interactive shell, default), **Files** (built-in file browser starting at `/root` — tap files to open in the user's default Android app via the same FileProvider/Intent path as `open_file`, or fall back to a built-in editable text editor with a Save action; the listing refreshes on its own each time the tab becomes visible, so files the assistant created or changed through the shell show up without any user action; an import action copies a file from device storage into the directory on screen, which is the only way to get a file into the sandbox without going through the agent), and **Packages** (search / install / uninstall / upgrade through whichever package manager is installed — see the sandbox doc for the search ranking rules). When the sandbox isn't installed yet, the inline view shows the install button so users don't have to dive into Settings before they can start.

#### Open File (Android)

The `open_file` tool fires an `ACTION_VIEW` Intent for a single file at `path` (relative to `/root`). Browser opens HTML, image viewer opens PNG/JPG, PDF viewer opens PDF, etc. Uses the existing FileProvider declared in the main manifest. Path is rejected if it contains a leading slash or `..` segments; the resolved path is canonicalized and verified to stay under the sandbox home root before the Intent is fired.

The Intent's MIME type is resolved from the file extension. Android's built-in extension table is the primary source, but it is overridden where it is wrong or absent for a Linux sandbox: `.apk` has no entry at all (so the intent used to go out as a wildcard and the package installer never appeared in the chooser), and `.ts` resolves to an MPEG transport stream when in this context it is nearly always TypeScript. Source and config extensions that Android does not know — the bulk of what the sandbox produces — fall back to plain text so a viewer is offered instead of an empty chooser.

Handing an `.apk` to the system package installer requires the "install unknown apps" permission. Maxit's FOSS Android build declares it; the user must grant access through the system prompt.

`open_file` operates on a single file. FileProvider grants access only to the specific URI in the Intent, so it does not work for multi-file content (e.g. an HTML page that loads sibling `styles.css` / `script.js`). For HTML, write self-contained files with inline CSS and JavaScript; the shell and `open_file` tool descriptions tell the agent to do this.

#### Shell command parameters

Android's `execute_shell_command` supports these parameters:

| Parameter | Type | Description |
|---|---|---|
| `command` | string (required) | The shell command to execute |
| `timeout` | integer | Timeout in seconds (default 30, max 60) |
| `working_dir` | string | Working directory for the command |
| `env` | object | Environment variables to set as key-value pairs |
| `background` | boolean | Run in background and return a session_id immediately |
| `fresh` | boolean | Run this command in a one-shot proot invocation isolated from the conversation's persistent bash session |

When `background=true`, the command starts asynchronously and returns a `session_id`. The AI then uses the `manage_process` tool to check status, retrieve output, or terminate the process.

#### Persistent bash session (Android)

On Android, each conversation gets its own persistent bash session inside the Linux sandbox. This is the default execution mode for `execute_shell_command`: `cd`, environment variable exports, shell functions, and other in-shell state carry across calls within the same conversation, so the AI can build up working context the same way a human terminal user does. Passing `fresh: true` opts out of the persistent session for a single call — that command runs in a one-shot proot invocation with no shared state, useful when the AI wants a clean environment without disturbing the ongoing session.

#### Process management

The `manage_process` tool is automatically available when the shell command tool is enabled. It supports these actions:

| Action | Description |
|---|---|
| `list` | Show all running and finished background processes |
| `log` | Get output from a process (with offset/limit for paging) |
| `kill` | Terminate a running process |
| `remove` | Remove a finished process from the list |

#### Output handling

Command output uses smart middle truncation: when output exceeds the limit, both the beginning and end are preserved with a truncation marker in the middle. This ensures error messages (typically at the end) are not lost.

Android shell capture retains its own 15,000-character per-stream limit. There is no additional global 20,000-character cut after a tool returns; tool-specific capture limits still apply.

### Desktop skills and execution

Desktop skills use the same catalog and `load_skill` interface as Android, with bundles stored in `~/.maxit/skills/`. Legacy `~/.kai` app data is [copied without modifying the original](encryption.md#desktop-data-directory-migration). Skill loading is a file read, not host command execution. Desktop already provides optional `execute_shell_command` and `manage_process` tools behind the shell toggle (disabled by default). They run with the host user's permissions, not in an Android-style sandbox. A bundle cannot enable those tools or install missing runtimes merely by being loaded.

## Execution Flow

1. The model produces a complete, terminally validated proposal: tool names, identities, and JSON arguments. Partial streamed arguments are never dispatched.
2. The assistant proposal is committed to the run's own conversation before any action.
3. Potentially harmful actions request approval of their exact arguments. Unattended runs pause instead of inferring authorization. Read-only operations are exempt; SMS sending retains its separate draft/send gate.
4. A dispatch marker is committed before entering execution. Enabled-tool lookup, argument parsing/size validation, exact permission checks, and the tool's timeout remain enforced.
5. Remote calls execute with at most four concurrent workers. Each result is committed immediately when it finishes, independently of slower siblings, and executing indicators are removed.
6. The next model request uses a compatible, bounded copy of the recorded exchanges. The model may request further tools, finish, or encounter a recoverable guard pause.

Stop preserves proposals, completed receipts, and partial text. A proposal that never started is labelled not started; a dispatched action without a confirmed outcome is labelled outcome unknown. A timeout or generic execution exception also warns that the effect may already have happened. Neither cancellation nor regeneration proves rollback. Previously uncertain side effects require explicit review before repetition, even for a normally read-only tool or if the model claims permission. Re-enabling a tool between proposal classification and final lookup does not grant approval; the action is recorded as not started and pauses if its exact invocation was not authorized. A denied exact invocation is not re-requested within the same attempt. Approval buttons identify a fresh request: an old click cannot authorize different work in another run/chat even when the provider reuses a call ID.

OpenAI-compatible Chat Completions, Responses/Codex, Anthropic, Gemini, and supported native inference share these durability and authorization rules. Native inference drives its own app-owned tool loop rather than relying on an SDK-owned total-round ceiling.

## Safety Guards

### No total-round ceiling

There is no fixed maximum number of tool rounds or calls per run and no automatic tools-less final answer after 15 rounds. Useful work may continue. Guard state is bounded rather than the task's total length.

### Repetition, cycles, and failures

The last 24 call/result rounds are inspected for repeated canonical batches and cycles of one to four rounds. Canonicalization sorts JSON object keys without changing strings or array order. Only the logical request being continued seeds the detector after Continue; a new user request or scheduled occurrence starts fresh, and replaced attempts do not count toward the replacement's cycle history. All saved outcomes remain available for approval and uncertainty checks.

A detected repetition allows one different recovery approach; renewed non-progress pauses with a durable reason. Six consecutive failed rounds similarly allow one different safe diagnostic attempt, then pause if failure continues. A final answer is not forced back into another tool request. These are deterministic heuristics, not proof that arbitrary changing output represents meaningful progress.

Running-process list/log/status polling is not classified as a dead loop merely because its output stays the same. Repeated running-process polling backs off by two seconds. Process termination is not exempted as polling.

### Independent resource bounds

- At most 256 proposed actions in one response/batch; larger batches pause before dispatch and must be split.
- Tool arguments are bounded at 1,048,576 characters per call.
- Remote execution has four concurrent workers, not an unlimited parallel launch.
- Stream events and response payloads have separate size/framing bounds. Malformed or incomplete output is not an executable proposal.
- Each tool retains its declared timeout, normally 30 seconds. Shell calls retain their platform-specific timeout and output-capture policies.

These limits protect a single operation or response; none is a replacement total-call ceiling.

### Cancellation and outcomes

Android foreground shell cancellation interrupts the command and escalates signals/reset as needed; desktop attempts to terminate the process tree. A background process or a third-party service may continue after cancellation. Even successful process termination cannot undo earlier file writes, sent messages, or other completed external effects. Verify ambiguous outcomes rather than blindly repeating the action.

### Complete results and bounded request context

Ordinary returned results are saved without a global 20,000-character cut. Individual tools can still have explicit capture/read bounds. `load_skill` retains its 256,000-character limit and returns an error rather than silently dropping instructions beyond it; skill/reference read limits remain documented in [skills.md](skills.md).

Request projection reserves output headroom and estimates system text, schemas, arguments, reasoning, and attachments. It retains the current user request and complete tool exchanges, while older work becomes bounded, source-linked, explicitly untrusted historical evidence. The `read_conversation_history` tool searches/pages the saved transcript and retrieves complete large records in bounded character slices. Stored history is never compacted or deleted to fit a model request; no separate summarization-model call is made.

Native outgoing receipts have their own budget, including system instructions, schemas, current input, action arguments, framing, and output reserve. Oversized receipts become explicitly partial excerpts with durable retrieval IDs, total size, next offset, and uncertainty warnings; the stored result remains complete. Mandatory input that cannot fit pauses honestly. Text-only requests budget supported decoded attachments and unsupported-format notices before context selection.

For provider switches, disabled tools, and incomplete exchanges, incompatible tool traffic is quoted as historical evidence rather than silently removed. Opaque reasoning/signature blocks are replayed only for the matching provider instance/model and complete exchanges. Strict native tool-call/result pairing remains enforced. Context estimates are not exact tokenizer accounting, and endpoint-specific limits can still reject a request.

## MCP Servers

See [mcp.md](mcp.md) for the full MCP feature spec.

## Tool Enablement

Tool availability is controlled at multiple levels:

- **Feature-level gates** — memory tools require memory enabled, scheduling tools require scheduling enabled, email tools require email enabled
- **Skills** — catalog discovery, `load_skill`, and `create_skill` on Android and desktop do not depend on sandbox install state. Bundled `create-skill` guides direct creation through the native tool. Reading instructions does not create a skill; creation is reserved for explicit user requests
- **Skill creation arguments** — remote `create_skill` accepts required `markdown` and optional `files: [{path, content, encoding?}]`; text defaults to `utf8`, with `base64` for binary assets. The local-model schema deliberately keeps only `markdown`. Self-review is instructed by the guide; the backend validates structure and bounds, not semantic correctness or runtime behavior
- **Sandbox install gate (Android)** — `execute_shell_command`, `manage_process`, and `ssh_configure_host` are surfaced only when the Linux sandbox is actually installed (Ready) *and* the sandbox toggle is on. Until the sandbox is installed these tools are not sent to the model at all; the sandbox toggle itself is hidden until install completes, so there is no state in which they ride along without a working sandbox behind them
- **Per-tool toggles** — individual tools can be enabled or disabled in settings, persisted with a `tool_enabled_` key prefix
- **Default state** — most tools default to enabled. On Android the shell has no per-tool switch — it follows the sandbox switch, which is on by default once the sandbox is installed
- **Master-toggle-only** — memory, scheduling, email, SMS, and notification tools have no individual per-tool toggle; they are on whenever their master switch in Settings → Agent is on. Android sandbox tools follow the sandbox switch and likewise carry no switch of their own
- **On-device (LiteRT) allowlist** — when the active model is an on-device LiteRT model, only a small allowlist of tools is exposed regardless of which other tools are enabled. This includes `get_local_time`, `get_location_from_ip`, `web_search`, `open_url`, `memory_store`, `memory_forget`, `memory_reinforce`, `load_skill`, and `create_skill`. Android's `execute_shell_command` also requires its sandbox gate. Memory tools beyond the three listed, email tools, and scheduling tools are not surfaced to local models even when their master switches are on. Models without function-calling support receive no tools. Tool-capable engines also receive bounded saved-history retrieval. The retained iOS engine declares no tool execution and receives no tools.

The platform layer assembles the final list of available tools by checking all gates and per-tool settings, and only enabled tools are sent to the AI provider.

## Settings UI

The tools tab in settings displays a responsive grid of toggle cards:

- 3 columns when the screen is at least 800dp wide
- 2 columns when at least 500dp wide
- 1 column on narrow screens

Each card shows the tool name, a short description, and a toggle switch. Clicking anywhere on the card toggles the tool. Cards use a semi-transparent surface variant background (transparent with an outline border in the OLED theme).

The grid sits below the MCP servers section and, on Android and desktop, the sandbox-independent Skills section (see [skills.md](skills.md)).

Only individually toggleable tools appear in the grid. Tools whose only control is a master toggle in Settings → Agent (memory, scheduling, email, SMS, notifications) are not listed here — they appear and disappear with their feature switch. The same applies to Android sandbox tools, which follow the sandbox switch.

## Chat UI

### Shared pulsing status indicator

The standard chat uses a shared pulsing status indicator that shows:
- A pulsing dot (scale 0.6→1.0, alpha 0.4→1.0, 800ms reverse animation)
- Cycling status text ("Thinking…", "Working…", "Brewing…" rotating every 3 seconds with AnimatedContent fade)
- An optional inline tool summary separated by " · ":
  - **1 tool executing**: shows the tool's display name (e.g., "Thinking… · Learn Memory")
  - **Multiple tools executing**: shows a grouped count (e.g., "Working… · 2 Tools")
  - **No tools**: shows only the cycling status text

The indicator accepts styling parameters (dot size, colors, text style).

### Waiting response row (standard chat)

When loading, a chip appears at the bottom of the chat list with the pulsing indicator, surface variant colors, and a 16dp dot. Standard chat explicitly distinguishes generating, waiting for approval, and stopping; tool summaries remain visible where applicable. The chip animates size changes over 300ms. Stop remains in cleanup state until the old job settles.

### Interactive mode loading feedback

Dynamic/Interactive UI generation and entry points are parked; previous interactive conversations reopen in normal chat. The retained renderer code has these loading states (see [system-prompts.md](system-prompts.md)):
- **Clicking a kai-ui action button**: the clicked button keeps its label and pulses (scale/alpha animation) until the response arrives; other buttons in the same message become disabled.
- **First load** (no assistant response yet): a centered waiting row is shown.
- **Typed-and-sent input**: the trailing send icon swaps to a stop icon in the input, as in standard chat.

### Common behavior

- TOOL_EXECUTING entries are not rendered as separate list items
- Completed tool results (TOOL role) are not shown in the UI

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../network/tools/Tool.kt` | Tool interface, ToolSchema, ParameterSchema |
| `composeApp/src/commonMain/.../network/tools/ToolInfo.kt` | Display metadata for settings |
| `composeApp/src/commonMain/.../data/ToolExecutor.kt` | Execution, exact permission checks, JSON parsing, timeouts, complete results |
| `composeApp/src/commonMain/.../network/ChatStreaming.kt` | Bounded stream detection, SSE/JSON native-call validation and non-replaying interruption handling |
| `composeApp/src/commonMain/.../network/CodexResponses.kt` | Completed Responses item reconstruction and native-call validation |
| `composeApp/src/commonTest/.../network/CodexResponsesTest.kt` | Gap-free empty-terminal recovery, delta-only items, opaque retention, and native-call validation |
| `composeApp/src/commonMain/.../network/dtos/openaicompatible/InlineToolCallParser.kt` | Bare-envelope correction detection; standalone complete legacy wrappers only |
| `composeApp/src/commonTest/.../network/ChatStreamingTest.kt` | SSE/JSON validation parity, initial BOM decoding, and saved-text preservation |
| `composeApp/src/commonTest/.../network/dtos/openaicompatible/InlineToolCallParserTest.kt` | Bare envelopes, examples, unknown tools, and malformed text |
| `composeApp/src/commonMain/.../data/ConversationRun.kt` | Commit ordering and stable run ownership |
| `composeApp/src/commonMain/.../data/ToolApproval.kt` | Exact approvals, same-attempt denial, uncertain-outcome checks |
| `composeApp/src/commonMain/.../data/ToolLoopGuard.kt` | Bounded repetition/cycle/failure detection and polling backoff |
| `composeApp/src/commonMain/.../data/ConversationContext.kt` | Historical evidence, complete exchanges, full-record retrieval |
| `composeApp/src/commonMain/.../ui/chat/composables/RunActivity.kt` | Approval prompts and saved action inspection |
| `composeApp/src/commonTest/.../data/ConversationContextAndLoopTest.kt` | Long useful runs, request-scoped loop seeding, polling, context compatibility |
| `composeApp/src/commonTest/.../data/ToolApprovalRaceTest.kt` | Final lookup rejects missing or mismatched exact authorization |
| `composeApp/src/desktopTest/.../data/ConversationExecutionTest.kt` | Durable loopback execution, mislabelled stream headers, approval races, bounded protocol correction, example/no-replay safeguards |
| `composeApp/src/commonMain/.../inference/NativeToolProjection.kt` | Bounded native receipts without truncating saved outcomes |
| `composeApp/src/commonTest/.../data/ConversationContinuationTest.kt` | Wire identity, reasoning, denial, retrieval and recovery regressions |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Tool loop (OpenAI-compatible, Responses API, Gemini, Anthropic), parallel execution, context trimming, on-device allowlist |
| `composeApp/src/commonMain/.../data/providers/OpenAIMessages.kt` | OpenAI-compatible message building + tool-call pairing sanitization |
| `composeApp/src/commonMain/.../tools/CommonTools.kt` | Common tool implementations and the cross-platform definition list |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Shared gating every platform's available-tools list is built from |
| `composeApp/src/commonMain/.../tools/WebSearchTool.kt` | `web_search` |
| `composeApp/src/commonMain/.../tools/FetchUrlTool.kt` | `fetch_url` with private-address and redirect checks |
| `composeApp/src/commonMain/.../tools/SchedulingTools.kt` | `schedule_task`, `list_tasks`, `cancel_task` |
| `composeApp/src/commonMain/.../tools/EmailTools.kt` | Email tools |
| `composeApp/src/commonMain/.../tools/SmsTools.kt` | SMS tools (FOSS Android) |
| `composeApp/src/commonMain/.../tools/NotificationTools.kt` | Notification tools (FOSS Android) |
| `composeApp/src/desktopMain/.../Platform.jvm.kt` | Desktop tool gating and definition list |
| `composeApp/src/androidMain/.../tools/ShellCommandTool.kt` | Android sandbox shell tool (per-distribution description) |
| `composeApp/src/androidMain/.../tools/SshConfigureHostTool.kt` | `ssh_configure_host` |
| `composeApp/src/androidMain/.../sandbox/PersistentSandboxShell.kt` | Per-conversation persistent bash session and command interruption |
| `composeApp/src/commonMain/.../Platform.kt` | Platform expect declarations for available tools |
| `composeApp/src/androidMain/.../Platform.android.kt` | Android tool gating and the Android definition list |
| `composeApp/src/androidMain/.../tools/SendNotificationTool.kt` | Android notification-posting tool |
| `composeApp/src/androidMain/.../tools/CreateCalendarEventTool.kt` | Android calendar-event tool |
| `composeApp/src/androidMain/.../tools/SetAlarmTool.kt` | Android alarm / countdown-timer tool |
| `composeApp/src/androidMain/.../sandbox/LinuxSandboxManager.kt` | Sandbox lifecycle, setup, proot management |
| `composeApp/src/androidMain/.../sandbox/ProotExecutor.kt` | Proot command building and execution |
| `composeApp/src/androidMain/.../linux/` | Shared Linux runtime: distro specs, rootfs download and extraction, installer, proot launcher, guest-path resolution |
| `composeApp/src/commonMain/.../linux/LinuxDistro.kt` | The distributions, their package sets, and their package-manager commands and parsers |
| `composeApp/src/jvmShared/.../tools/ProcessManagerTool.kt` | Process management tool shared by Android and desktop |
| `composeApp/src/desktopMain/.../tools/ProcessManager.kt` | Desktop background process tracking (host processes) |
| `composeApp/src/androidMain/.../tools/ProcessManager.kt` | Android background process tracking (proot sandbox) |
| `composeApp/src/androidMain/.../tools/OpenFileTool.kt` | Open sandbox file in an Android app via FileProvider Intent |
| `composeApp/src/androidMain/.../sandbox/SandboxFiles.kt` | Path translation, MIME resolution, text-vs-binary reads, streamed imports, and the FileProvider open helper — all shared with the file browser |
| `androidApp/src/main/res/xml/file_paths.xml` | FileProvider path config (includes `sandbox-home/`) |
| `composeApp/src/commonMain/.../SandboxController.kt` | Cross-platform sandbox interface |
| `composeApp/src/commonMain/.../ui/sandbox/SandboxTabsContent.kt` | Terminal/Files/Packages sub-tab UI rendered inline inside the chat screen body |
| `composeApp/src/commonMain/.../ui/sandbox/SandboxFileBrowserScreen.kt` | User-facing file browser UI, pointed at either Linux environment |
| `composeApp/src/commonMain/.../ui/sandbox/SandboxFileBrowserViewModel.kt` | State for browsing and editing sandbox files |
| `composeApp/src/commonMain/.../ui/settings/ToolsSettings.kt` | Tools tab: MCP and Skills sections plus the per-tool toggle grid |
| `composeApp/src/commonMain/.../ui/settings/SandboxSettings.kt` | Linux Sandbox settings card |
| `composeApp/src/commonMain/.../ui/chat/composables/ToolMessage.kt` | Executing/completed UI indicators |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Tool enabled state persistence |
