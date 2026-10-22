<!-- Modified for Maxit, 2026-10-20. -->

# Memories

**Last reviewed:** 2026-10-21

Maxit's memory system allows the AI to learn and retain information across conversations. Memories are stored persistently, included in chat system prompts when memory is enabled, and can be reinforced over time. Memory promotion into agent instructions has been removed; existing memories and saved instructions are preserved.

## Concepts

### Memory

A persistent key-value entry containing a descriptive key, content, category, hit count, and optional source. Memories survive app restarts and are shared across all conversations.

### Category

Each memory belongs to one of four categories:

- **General** — user preferences, facts, and important information
- **Learning** — successful approaches and patterns that worked well
- **Error** — error resolutions and known issues
- **Preference** — user corrections and explicit preferences

### Reinforcement

A mechanism for tracking how often a memory proves useful. A new memory starts with one hit; each reinforcement increments the hit count and updates the timestamp. Hit counts do not modify [agent instructions](system-prompts.md#editable-agent-instructions).

## Storage

- Memories are serialized as JSON in app settings
- Thread-safe access via mutex for concurrent operations
- If the stored JSON can no longer be decoded (e.g. after a schema change), the raw blob is backed up under a separate settings key before the next write, so the only copy is never overwritten. The backup is not part of settings export
- Memory feature is enabled by default
- When disabled, memory tools are removed from available tools but stored memories are preserved

## Memory Lifecycle

1. AI stores a memory using `memory_store` (general) or `memory_learn` (categorized)
2. If a memory with the same key exists, it is updated
3. On subsequent conversations, AI can reinforce memories that prove useful via `memory_reinforce`
4. AI can remove outdated memories via `memory_forget`

## System Prompt Injection

When memory is enabled, all memories are retrieved and grouped by category in the system prompt:

- General memories listed under "Your Memories"
- Preference memories under "User Preferences"
- Learning memories under "Learnings" with reinforcement count shown
- Error memories under "Known Issues & Resolutions"

For on-device (local) chat, these category sections share a 2,000-character budget, including headings and formatting. Entries are considered in the order above; the first entry that does not fit stops that category, while later categories can still use the remaining budget. Categories with no fitting entries emit no header. Remote chat has no such builder cap and includes all memories.

## Default Instructions

Built-in memory instructions (included for every chat variant when memory is enabled) guide the AI to:

- Proactively store important information (names, preferences, projects, goals) with `memory_store`
- Use `memory_forget` for outdated or incorrect information
- Avoid storing trivial or transient information

For remote models only, an additional "Structured Learning" section tells the AI to categorize with `memory_learn` (Preference for corrections, Learning for successes, Error for resolutions) and to reinforce memories that produce good outcomes. On-device models don't get this section because `memory_learn` isn't exposed to them.

## Settings UI

The memories section lives in Settings → Agent and contains:

- **Toggle** — enables or disables the memory feature with a switch
- **Description** — explains that memories are included in every message for context
- **Memory list** — shown only while memory is enabled; the five most recently updated memories are shown inline; each entry displays the key (bold) and content (max 3 lines, truncated with ellipsis)
- **Show all button** — appears when more than five memories exist; opens a modal bottom sheet listing every memory
- **Edit memory** — tapping any memory row (inline or inside the bottom sheet) opens an edit bottom sheet that lets the user modify the memory content. The key is shown but not editable. Saving updates the memory's content and timestamp
- **Delete button** — per-memory trash icon to remove individual memories; deletion is deferred with a snackbar "Undo" option (~4 seconds) before the memory is permanently removed

## AI Tools

| Tool | Purpose |
|---|---|
| `memory_store` | Store general key-value memories (General category) |
| `memory_learn` | Store categorized memories with optional source tracking (Learning, Error, or Preference). Rejects the General category at runtime and suggests using `memory_store` instead |
| `memory_forget` | Remove a memory by key |
| `memory_reinforce` | Increment a memory's hit count |

`memory_store`, `memory_forget`, and `memory_reinforce` are also exposed to on-device models; `memory_learn` is remote-only. `promote_learning` is no longer available on any model.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/MemoryStore.kt` | Core storage, retrieval, reinforcement, deletion, categorization |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Persistence layer for memories JSON and enable flag |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Loads memories and memory instructions for the system prompt; on-device tool allowlist |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | Category-grouped memory sections, local 2,000-char budget, Structured Learning section |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Gates memory tools on the memory switch |
| `composeApp/src/commonMain/.../tools/CommonTools.kt` | AI tool definitions for memory_store, memory_learn, memory_forget, memory_reinforce |
| `composeApp/src/commonMain/.../ui/settings/AgentSettings.kt` | Memory management UI (list, all-memories sheet, edit sheet) |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Memory state management and user actions |
