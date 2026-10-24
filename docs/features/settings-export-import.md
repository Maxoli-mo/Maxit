<!-- Modified for Maxit, 2026-10-20. -->

# Settings Export / Import

**Last reviewed:** 2026-10-24

**Last verified:** 2026-10-24

Users can back up and restore their Maxit configuration and data (services, agent instructions, memory, tasks, heartbeat, email, SMS, tools, MCP servers, conversations) via a human-readable JSON file. Some device-local preferences are deliberately left out — see **Excluded**. The feature is available under **Settings > General** at the bottom of the page.

The **Agent instructions** setting retains the legacy `soul_text` JSON key, `ImportSection.SOUL`, and `getSoulText()` / `setSoulText()` APIs. The label and default changed; storage was not migrated. Saved nonempty overrides are loaded verbatim. An empty value selects the [bundled Markdown default](system-prompts.md#editable-agent-instructions), which is not copied into exports.

## Behavior

### Export
- Tapping **Export** opens an **Export Preview Dialog** that lists each settings section currently holding real user data, with item counts where applicable (e.g. "Services (2)", "Memory (5)"). All sections are checked by default; the user can untick any section to leave it out of the file.
- A section is only listed when it actually has data. Pure feature-toggle flags (e.g. SMS turned off, an empty MCP server list) do not appear in the dialog. Specifically:
  - SERVICES — only if at least one service is configured
  - SOUL — only if saved agent instructions are nonblank
  - MEMORY / SCHEDULING / CONVERSATIONS — only if at least one entry exists
  - HEARTBEAT — only if a custom prompt, config, or log entries exist
  - EMAIL — only if at least one account is configured
  - SMS — only if SMS receive or send is enabled
  - MCP — only if at least one server is configured
  - TOOLS — when a full export includes `tool_overrides`. The exporter writes the current enabled flag for every platform tool id (not only user-changed overrides, and including tools without a per-tool switch), so Tools almost always appears whenever any tools exist on the platform. Its count is the number of tools marked enabled
- Confirming the dialog opens a native file-save dialog and writes `kai-settings.json` containing only the selected sections (plus a `"version": 1` field for forward-compatibility).
- Cancelling the dialog discards the export without writing a file.
- Sections listed under **Excluded** below are never exported.

### Import
- Tapping **Import** opens a native file picker filtered to `.json` files.
- After the file is selected and parsed, an **Import Preview Dialog** appears. A file that is not a valid JSON object shows an import error instead.
- The dialog detects which sections are present in the JSON and shows a checkbox for each one (all enabled by default), with item counts where applicable (e.g. "Services (2)", "Memory (5)").
- A Replace/Merge toggle controls what happens to unselected sections:
  - **Replace** (default): Unselected sections reset to their defaults.
  - **Merge**: Only apply selected sections; all other settings stay unchanged.
- Clicking **Import** in the dialog applies the selected sections. Afterwards the settings screen is rebuilt, service connections are re-checked, enabled MCP servers are reconnected, and conversations are reloaded so imported chats appear without a restart. A status line under the buttons reports success, partial success (with the number of failed sections), or failure.
- Each settings section is imported independently. If one section contains malformed data, the remaining sections are still imported and the error is counted.
- Unknown keys are silently ignored, so older exports can be imported into newer app versions.
- Scheduled tasks and memories with missing or invalid fields are auto-filled with sensible defaults (e.g. generated UUIDs for missing IDs, `PENDING` for invalid task status, `GENERAL` for invalid memory category). This ensures items are preserved even if the JSON was hand-edited or exported from a different version. Conversations that cannot be decoded are skipped individually; the rest are imported.
- Imported conversations replace all existing conversations (on database-backed platforms they are applied to the local database on the next load). In Replace mode, leaving Conversations unselected clears all conversations.
- OpenAI-compatible base URLs without a version path get `/v1` appended on import.
- Codex provider instances and model choices can transfer, but ChatGPT access and refresh tokens cannot. Importing Services, or replacing settings, clears existing Codex sessions; sign in again after import. A merge that leaves Services unselected preserves completed sessions. Any applied import cancels unfinished Codex sign-in attempts before rebuilding the screen, including merges of unrelated sections; a new attempt can start immediately.

## Import Sections

| Section | Display Name | JSON keys detected |
|---------|-------------|-------------------|
| SERVICES | Services | `configured_services`, `current_service_id`, `free_fallback_enabled`, `instance_settings` |
| SOUL | Agent instructions | `soul_text` |
| MEMORY | Memory | `memory_enabled`, `agent_memories` |
| SCHEDULING | Scheduling | `scheduling_enabled`, `scheduled_tasks` |
| HEARTBEAT | Heartbeat | `heartbeat_config`, `heartbeat_prompt`, `heartbeat_log` |
| EMAIL | Email | `email_enabled`, `email_accounts` |
| TOOLS | Tools | `tool_overrides` |
| MCP | MCP Servers | `mcp_servers` |
| CONVERSATIONS | Conversations | `conversations` |
| SMS | SMS | `sms_enabled`, `sms_poll_interval`, `sms_send_enabled` |

## Settings Included

| Category | Keys |
|----------|------|
| Services | `configured_services`, `current_service_id`, `free_fallback_enabled`, per-instance `api_key` / `model_id` / `base_url` / optional `use_custom_model` / `custom_model_id` |
| Agent instructions | `soul_text` (legacy key) |
| Memory | `memory_enabled`, `agent_memories` |
| Scheduling | `scheduling_enabled`, `scheduled_tasks` |
| Heartbeat | `heartbeat_config`, `heartbeat_prompt`, `heartbeat_log` |
| Email | `email_enabled`, `email_accounts`, per-account passwords (`email_passwords`) and sync state (`email_sync_states`), `email_poll_interval` |
| Tools | Per-tool enabled flags under `tool_overrides` (current value for every known platform tool id) |
| MCP | `mcp_servers` (including each server's custom request headers and enabled flag) |
| Conversations | `conversations` (array of conversation objects with messages) |
| SMS | `sms_enabled`, `sms_poll_interval`, `sms_send_enabled` only (pending queue, sync state, and drafts are **not** included in export/import) |

## Excluded

- `daemon_enabled` (platform-specific, should not transfer between devices)
- `app_opens` (analytics counter)
- `encryption_key` (security-sensitive)
- ChatGPT/Codex OAuth sessions, access tokens, refresh tokens, and pending device authorizations
- `ui_scale` (platform-specific, desktop may differ from mobile)
- Appearance and general preferences: `theme_mode`, `dynamic_ui_enabled`
- Free-tier preferences: `free_mode`, `free_service_primary`
- Notification reading (`notifications_enabled`, pending queue, store, sync state) — Android FOSS only
- Email pending queue (`email_pending`)
- Linux sandbox and Maxit Build settings: `sandbox_enabled`, `sandbox_distro`, `kai_build_launch_agent`
- Cached per-model context sizes (`model_context_*`)
- Session state: current conversation id and interactive mode
- On-device (LiteRT) model files and installed skills (stored as files, not settings)
- Backups of undecodable settings JSON and migration flags

## Key Files

| File | Role |
|------|------|
| `composeApp/.../data/AppSettings.kt` | `ImportSection` enum, `detectImportSections()` / `detectExportableSections()` |
| `composeApp/.../data/AppSettingsImportExport.kt` | `exportToJson()` / `importFromJson()` core logic (selective `Set<ImportSection>`), `sanitizeScheduledTasks()` / `sanitizeMemories()` helpers |
| `composeApp/.../data/DataRepository.kt` | Interface methods (`exportSettingsToJson(sections)`, `getExportPreview()`, `importSettingsFromJson(...)`) |
| `composeApp/.../data/RemoteDataRepository.kt` | Wires AppSettings to platform tool IDs, serializes JSON, runs `detectExportableSections` over a full export to drive the export preview |
| `composeApp/.../ui/settings/SettingsActions.kt` | Callbacks (`onExportSettings`, `onPrepareExport`, `onImportSettings`) |
| `composeApp/.../ui/settings/SettingsViewModel.kt` | Delegates to repository, rebuilds UI state after import, and reloads conversations so imported chats appear without a restart |
| `composeApp/.../ui/settings/GeneralSettings.kt` | Hosts the Export/Import card in the General tab |
| `composeApp/.../ui/settings/ExportImportSection.kt` | Export/Import card with FileKit dialogs, `ExportPreviewDialog`, `ImportPreviewDialog` |
| `composeApp/.../testutil/FakeDataRepository.kt` | Test stubs |
| `composeApp/.../data/AppSettingsExportImportTest.kt` | Unit tests including v1 snapshot test |
| `composeApp/src/desktopTest/.../ui/settings/SettingsCodexSignInTest.kt` | Codex sign-in lifecycle, including cancellation when importing settings |
