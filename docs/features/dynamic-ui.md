# Dynamic UI (kai-ui)

**Status reviewed:** 2026-10-22 — generation remains parked; the skills rework does not re-enable it.

The full UI instructions are archived in [interactive-ui/SKILL.md](../skills/interactive-ui/SKILL.md), outside app resources, and are no longer injected into model requests. Interactive-mode entry and the Dynamic UI settings toggle are hidden. Existing conversations reopen in normal chat; no UI-repair retries are sent. The parser and Compose renderer remain active so old messages with `kai-ui` JSON layouts still render. The [skills loader](skills.md) does not register or load this archive.

## Concepts

### Layout Blocks

A `kai-ui` code fence inside an assistant message contains a JSON object describing a component tree. The unified markdown parser treats kai-ui fences as first-class AST blocks, alongside headings, paragraphs, and other markdown; the renderer dispatches each block to its composable. Supports both single-object JSON and multi-line NDJSON (one JSON object per line, automatically wrapped in a column).

### Component Types

- **Layout**: column, row, card, box, divider (spacing between children is fixed by the renderer — the LLM does not control it)
- **Content**: text (with headline/title/body/caption styles), image (optional aspect ratio to prevent distortion on wide screens), icon (curated material icon set or any emoji), code (syntax-highlighted block with a built-in copy-to-clipboard icon in the top-right corner)
- **Interactive**: button (filled/outlined/text/tonal variants), text input, checkbox, switch, select dropdown, radio group, slider, chip group (single-select, multi-select, or display-only tags)
- **Feedback**: progress (determinate/indeterminate), countdown (relative duration with optional expiry action; a callback action fires only while the message is still interactive, so older countdowns never send messages when the chat is reopened or scrolled), alert (info/success/warning/error)
- **Navigation**: tabs (tabbed content), accordion (collapsible sections)
- **Display**: quote (blockquote with accent border), badge (colored count/status pill), stat (large metric display), avatar (circular image or initials)
- **Data**: list, table

### Actions

Buttons carry an action that fires on click (chip groups are form inputs, not action carriers):

- **callback** — collects form data from specified input IDs and sends a structured message back to the AI via the normal chat flow (e.g. "Pressed: event_name" or "Responded with: key: value"). The AI then responds with text or more UI. Callbacks do not trigger system operations like printing, file export, or downloads; the archived instructions retain this limitation for future integration. Clipboard access is available via the dedicated copy action below.
- **toggle** — shows/hides a target element locally without AI roundtrip
- **open_url** — opens a link in the browser
- **copy_to_clipboard** — writes a literal string to the system clipboard locally, no AI roundtrip. A button carrying this action always renders as a compact clipboard icon button regardless of its variant; the button's label is ignored. Intended for surfacing copyable values (snippets, commands, tokens) alongside the content they belong to.

### Interaction Flow

When a callback fires, the renderer collects input values and formats them as a user message of the form `Pressed: <event>` or `Responded with: <key: value>` (no special prefix). The AI receives the event name and form data in conversation history and can respond with new UI, text, or tool calls. While the callback is in flight, the clicked button keeps its label visible and pulses (a subtle scale and alpha animation); other buttons in the same assistant message become non-interactive until the response arrives.

The originating assistant's kai-ui card stays in place across the exchange. While the submission is in flight the pressed button pulses; once the reply arrives the card settles into a frozen snapshot (prior values seeded, pressed button highlighted) with an edit pencil in the top-right corner. The user's submission does not produce a separate text bubble — the filled-in form on the assistant card already shows exactly what was sent. Clicking the pencil re-enables the form seeded with the previous picks; pressing any button then triggers a resubmit that truncates from that point and re-asks the AI. The text form ("Responded with: key: value") is still what the AI receives; only the visual representation differs. Snapshots persist with the conversation, so reloading preserves them.

### Layout Lifecycle

Only the latest assistant message's layouts are interactive. Older layouts become read-only with disabled buttons and inputs. Form state is local to each layout; cross-step state lives in conversation history.

### Error Handling

Errors are absorbed as locally as possible so a single bad field never kills a whole block. The parser first repairs common JSON syntax mistakes (extra trailing braces/brackets via stack-based brace matching, truncated mid-stream responses, `"key="` typos, and missing closing braces between sibling objects in an array — e.g. when the LLM writes `,{` where a key was expected), then walks the resulting tree field-by-field. Each field reader tolerates the wrong value type (objects where a string was expected, bare strings where an object list was expected, string booleans, numeric strings) and falls back to the data-class default if nothing can be salvaged — the node still builds, the offending field is simply missing from the rendered UI. Unknown node types in `children` or `items` are silently dropped. Only JSON so malformed that the syntax repair can't produce a parseable tree falls back to rendering as a code block. Individual malformed lines in multi-line NDJSON are skipped while valid lines still render. TTS walks the kai-ui tree and reads out the human-readable labels it finds — text, button labels, alert messages, and similar — while skipping non-speakable elements like code blocks, icons, and dividers.

### Settings

The former Dynamic UI toggle is hidden. Its saved preference is retained but no longer controls prompt injection. Parsing and rendering remain active unconditionally.

## Interactive UI Mode (historical reference)

The following describes the parked full-screen design, not currently available UI. Its screen renderer remains in the source for future work, but chat no longer routes into it.

### Entering Interactive Mode

Users click "Start Interactive UI" on the empty chat state. This enters interactive mode with a text input where they describe what they want (e.g., "Build me a quiz app"). The first message goes to the AI with an enhanced system prompt, and the AI responds with a full-screen UI. The button is hidden when the primary service is on-device (LiteRT), because small local models cannot reliably produce kai-ui JSON; on-device services are also filtered out of the interactive-mode service selector.

### Screen Navigation

Each AI response replaces the previous screen entirely. Only the latest assistant message's kai-ui renders, taking the full available space. A top bar provides back and exit buttons.

### Loading Frame

While an interactive-mode response is in flight, an animated gradient frame (the same rotating purple/violet/magenta border used by the "Start Interactive UI" button) is drawn around the whole screen edge to signal the AI is working. It disappears once the response arrives.

### Back Button

The back button removes the last exchange (user message + assistant response) from conversation history, making the previous assistant response the active screen again. When only one exchange remains, pressing back clears the history and returns to the initial prompt input — interactive mode stays active. To leave interactive mode entirely, the user uses the exit button in the top bar.

### Auto-Retry on Parse Failure

Previously, a reply without valid kai-ui blocks triggered up to two repair requests. That retry path has been removed so normal text replies cannot consume extra turns while UI generation is parked.

### Conversation Persistence

Existing sessions retain their `interactive` type and message content, but load in normal chat. Stale persisted mode flags do not restore full-screen mode.

### System Prompt

The archived scheme requires one kai-ui fence per screen and prohibits text outside it. Those instructions are not sent to the model now; see [system-prompts.md](system-prompts.md).

## Key Files

| File | Purpose |
|------|---------|
| `composeApp/.../ui/dynamicui/KaiUiNode.kt` | Serializable component tree model — 28 node types, all @Immutable |
| `composeApp/.../ui/dynamicui/UiAction.kt` | Action types (callback, toggle, open_url, copy_to_clipboard) |
| `composeApp/.../ui/dynamicui/KaiUiParser.kt` | Sanitizes malformed JSON and decodes kai-ui fence bodies via `parseUiBlockBody` |
| `composeApp/.../ui/dynamicui/KaiUiNodeBuilders.kt` | Tolerant field-by-field construction of KaiUiNode instances from JsonElement |
| `composeApp/.../ui/dynamicui/KaiUiRenderer.kt` | Recursive Compose renderer for the component tree, wrapInCard option |
| `composeApp/.../ui/dynamicui/KaiUiTts.kt` | Speakable-text extraction for TTS: walks kai-ui trees for human-readable labels |
| `composeApp/.../ui/markdown/MarkdownParser.kt` | Unified markdown parser; emits `KaiUiBlock` AST nodes for kai-ui fences |
| `composeApp/.../ui/markdown/MarkdownRenderer.kt` | Compose renderer that dispatches each block (including kai-ui) to its composable |
| `composeApp/.../ui/chat/composables/BotMessage.kt` | Integration point — runs the parser, renders frozen/edit-enabled snapshots for paired user submissions, and renders past kai-ui read-only via `isInteractive = false` |
| `composeApp/.../ui/chat/ChatScreen.kt` | Normal chat routing; retained full-screen renderer is not entered |
| `composeApp/.../ui/chat/composables/EmptyState.kt` | Optional interactive-entry button, no longer supplied by chat |
| `composeApp/.../ui/chat/ChatActions.kt` | submitUiCallback, enterInteractiveMode, exitInteractiveMode, goBackInteractiveMode |
| `composeApp/.../ui/chat/ChatViewModel.kt` | Restores old interactive conversations as normal chat; no UI-repair retries |
| `composeApp/.../ui/chat/ChatUiState.kt` | isInteractiveMode state flag |
| `composeApp/.../data/DataRepository.kt` | popLastExchange, setInteractiveMode/isInteractiveModeActive |
| `composeApp/.../data/RemoteDataRepository.kt` | Interactive mode system prompt, TYPE_INTERACTIVE conversation saving |
| `composeApp/.../data/AppSettings.kt` | isDynamicUiEnabled / setDynamicUiEnabled |
| `composeApp/.../data/Conversation.kt` | TYPE_INTERACTIVE conversation type constant |
