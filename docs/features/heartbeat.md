<!-- Modified for Maxit, 2026-10-24. -->

# Heartbeat

**Last verified:** 2026-10-24. Scheduler cancellation and repeated-prompt checks use synthetic data; device/background acceptance remains separate.

> Heartbeat is user-controlled (on/off toggle, interval, active hours live in the settings UI). The AI cannot enable, disable, or reschedule it. To customise *what happens on each heartbeat*, the AI creates heartbeat-triggered scheduled tasks via `schedule_task` with `on_heartbeat: true` — these are `HEARTBEAT`-trigger tasks (see [tasks.md](tasks.md)) and their prompts are appended to every heartbeat run under `## Heartbeat Additions`. Each addition is a first-class task the user can see, edit, and cancel.

Maxit's heartbeat feature enables periodic automatic self-checks. The AI reviews pending tasks, email status, newly arrived emails, and learned memories on a configurable interval, surfacing anything that needs attention without requiring user interaction.

## Concepts

### Heartbeat

A silent, scheduled prompt sent to the AI during active hours. If nothing needs attention, the AI responds with "HEARTBEAT_OK" and the user sees nothing. If something requires follow-up, the response appears as an assistant message in the chat.

### Active Hours

A configurable time window (default 8:00–22:00) during which heartbeats are allowed to fire. Outside this window, heartbeats are skipped regardless of interval.

## Configuration

Heartbeat configuration is stored as a serialized JSON object in app settings. Values are only editable from the settings UI — there is no AI tool that can flip them:

- **Enabled**: true
- **Interval**: 30 minutes between heartbeats (UI slider offers 5m, 10m, 15m, 30m, 45m, 1h, 2h, 4h)
- **Active hours start**: 8 (hour, 24h format; UI range slider covers 0–23)
- **Active hours end**: 22 (hour, 24h format; UI range slider covers 0–24, where 24 renders as 0:00 and means end-of-day)
- **Model**: optional override for which service+model to use for heartbeats. When not set, the default path prefers the first configured **remote** service, then falls back to the first on-device (LiteRT) instance if no remote is configured. Useful for selecting a cheaper or faster model for background checks

UI validation rules:

- Interval must be at least 5 minutes
- Active hours start must be 0–23; end must be 0–24 (24 = midnight next day = full-day coverage)

## Execution Flow

1. The task scheduler polls every 60 seconds
2. On each poll, it checks: is the overall scheduling-enabled toggle on (the tasks setting that gates all scheduled work)? Is heartbeat enabled? Is the current hour within active hours? Has the configured interval elapsed since the last heartbeat?
3. If all conditions are met and no other API call is in progress, a heartbeat prompt is built and sent to the AI with the full tool-calling loop. Tool calls (e.g. shell commands) are bound to the heartbeat conversation's own session, not to whichever chat the user is viewing
4. The last heartbeat timestamp is updated and a log entry is recorded
5. After a successful run, the notification store's retention limits are swept

When the overall scheduling-enabled toggle is off, heartbeats do not run regardless of the heartbeat toggle, interval, or active hours.

## Response Handling

- A response containing "HEARTBEAT_OK" does not raise a report banner or notification. Its saved run can still be inspected in the heartbeat conversation
- Every attempt owns the dedicated heartbeat conversation: input, streamed partial text, tool intent, each outcome, and final status are committed there. There is no 50-message retention cap
- Other non-blank reports set the unread indicator. The scheduler does not append a second copy after the run has already saved its answer
- A dismissable banner appears at the top of the chat when the heartbeat has something to report
- **Android push notification**: when the heartbeat produces a non-OK report *and* the app is not currently in the foreground (tracked at the process level, so any visible activity counts), a push notification titled "Maxit heartbeat" fires. Tapping it launches/foregrounds the app and opens the heartbeat conversation. The notification uses a fixed id so a fresh report replaces the previous unread one instead of stacking. The body is the heartbeat response with markdown and dynamic-UI blocks stripped, capped at 240 characters with an ellipsis; the cut lands on a word boundary when one falls within the last 40 characters, otherwise it is a hard cut. Desktop/iOS/web no-op (banner-only).
- Tapping the banner loads the heartbeat conversation so the user can read the report and reply
- The X button dismisses the banner without navigating
- Heartbeat conversations are included in the chat history list with a "Heartbeat" label badge, and can also be accessed via the banner
- Foreground chat history is not used. Saved heartbeat work supplies continuity through the same bounded request projection and historical retrieval as normal conversations; earlier instructions and outcomes are evidence, not an instruction to replay actions
- Tool actions requiring exact approval pause unattended execution rather than borrowing authorization from the task, model, or a previous attempt. Read-only built-ins can proceed; SMS still requires its separate explicit send action
- Failed/paused/interrupted attempts keep partial output and confirmed receipts. Missing dispatched outcomes are labelled unknown; unstarted proposals are labelled not started. No failure is proof that an external effect was undone
- API/approval/loop failures are logged, and pending email/SMS/notification snapshots remain queued. Deleting or invalidating a heartbeat conversation aborts that occurrence and exposes an error without stopping unrelated automation or recreating the deleted history. Actual scheduler-job cancellation still propagates; the durable conversation records interruption where storage permits
- Failed/paused work raises the heartbeat attention indicator; startup also restores attention for an unresolved latest terminal status. Scheduled occurrences interrupted after their durable claim remain paused until reviewed; see [tasks.md](tasks.md)
- An escaping scheduler/storage error is shown as a background execution error in chat. A failed write cannot publish a false saved result or authorize the next action

## Prompt Building

The heartbeat prompt is assembled by a pure, unit-tested builder; every conditional section is omitted when it has nothing to show. Sections, in order:

1. **Custom prompt** — user-defined text from settings, or the default prompt if empty. The default instructs the AI to review memories and tasks, respond "HEARTBEAT_OK" if nothing needs attention, or address anything that does, and reminds it that it cannot change the heartbeat schedule
2. **Heartbeat Additions** — the prompts of all pending heartbeat-triggered tasks, each with its description and id, under `## Heartbeat Additions`. The section tells the AI to address each one and to reply with an acknowledgement rather than HEARTBEAT_OK, since the additions themselves are the attention
3. **Pending tasks** — pending time- and cron-triggered tasks are listed with their description, id, scheduled time, and cron expression (if recurring). Heartbeat-triggered tasks appear under Heartbeat Additions instead
4. **Email status** — if email is enabled and accounts exist, each account's email address, unread count, and last sync time are included
5. **New emails** — headers (subject, from, preview) for emails polled since the last heartbeat pickup. Emails are fetched in the background by the email poll loop and buffered in a pending queue (capped at 100, FIFO). The heartbeat consumes the queue: everything the heartbeat saw is removed from the queue after a successful run, while emails that arrive during the heartbeat call remain for the next run. After consumption the heartbeat also advances each account's delivery watermark, so a follow-up `check_email` call from the user won't re-surface the same messages — Maxit tracks read/unread internally and ignores the provider's `\Seen` flag
6. **New SMS** — SMS messages received since the last heartbeat (only while SMS is enabled). Consumed analogously to emails: buffered as they arrive, surfaced once under `## New SMS`, and cleared from the pending queue after a successful run so the next heartbeat only sees newer arrivals
7. **New notifications** — Android notifications captured since the last heartbeat (only while notification access is enabled), capped at 20 newest-first; any beyond the cap stay queued for later heartbeats. Consumed analogously to emails: buffered as they arrive, surfaced once under `## New Notifications`, and cleared from the pending queue after a successful run so the next heartbeat only sees newer arrivals

Earlier reports, prompts, and attempt status stay in saved-history context; they are not automatically nested into the new heartbeat prompt as previous results. Repeated quiet cycles therefore do not recursively copy earlier prompts or consumed inbox snapshots.

The promotion-candidate section and `promote_learning` tool have been removed. Memories reach the model through the chat system prompt when memory is enabled, not through a separate heartbeat-store query. Existing custom heartbeat prompts are unchanged.

For the full contract of every prompt variation in Maxit (chat remote/local, heartbeat) see [system-prompts.md](system-prompts.md).

## Heartbeat Log

- Stores up to 5 most recent heartbeat entries
- Each entry records success/failure, a timestamp, and an optional error message
- Displayed in the settings UI under the heartbeat section
- Entries show an OK/FAIL indicator and a formatted local timestamp
- Failed entries display the error message (up to 3 lines, ellipsized) below the timestamp in the error color

## Settings UI

The heartbeat section lives in the Agent tab of settings. The toggle and reset button are always visible; every other control only appears while heartbeat is enabled:

- **Toggle** — enables or disables heartbeat with a switch
- **Interval display** — shows the current interval in minutes in the section description
- **Interval slider** — a snap-to-preset slider with positions for 5m, 10m, 15m, 30m, 45m, 1h, 2h, 4h. Displays the formatted value (e.g. "15m", "2h") next to the label
- **Active hours range slider** — a dual-thumb range slider spanning 0–24 (24-hour clock). Displays "H:00 – H:00" next to the label (unpadded hours); the upper-bound value 24 renders as 0:00 to indicate full-day coverage
- **Model picker** — shown only when more than one eligible service is configured. A dropdown button showing the selected service+model, or "Default" when no override is set. The menu lists configured remote services whose model supports tool calling (on-device models are not offered), with their icons and model IDs. Selecting "Default" clears the override and uses the remote-first default above. If the previously selected service is removed, heartbeat falls back to that default automatically
- **Custom prompt editor** — a text field (max 4000 characters) for editing the heartbeat prompt, with a save button that appears when changes are detected. Shows the default prompt text when no custom prompt is set. A character counter (X/4000) is displayed in the editor as the user types
- **Reset to default** — when a custom heartbeat prompt is set, a reset button appears in the heartbeat section header next to the toggle. Tapping it opens a confirmation dialog; confirming clears the custom prompt and restores the default
- **Log display** — a "Recent" label, followed by each log entry (if any) with an OK/FAIL indicator and timestamp
- **Manual refresh** — a refresh icon next to the "Recent" label (always shown while heartbeat is enabled) runs a heartbeat immediately, bypassing the active-hours window and the interval-due check. Only fires while heartbeat is enabled and scheduling is on; the icon shows a progress spinner during the call

## AI Tools

Heartbeat has no dedicated tool. Standing additions are created with `schedule_task(on_heartbeat=true)` and inspected or cancelled with `list_tasks` / `cancel_task` — see [tasks.md](tasks.md#heartbeat-triggered-tasks). Those prompts are appended to the main heartbeat self-check, not replaced.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/HeartbeatManager.kt` | Config, log management, wrapper that gathers inputs for the pure prompt builder |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../data/HeartbeatPromptBuilder.kt` | Pure heartbeat prompt assembly (sections, caps) |
| `composeApp/src/commonMain/.../tools/SchedulingTools.kt` | Heartbeat-triggered tasks via `schedule_task`, plus task listing and cancellation |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Poll loop, heartbeat checks, and occurrence-versus-lifetime cancellation |
| `composeApp/src/commonTest/.../data/TaskSchedulerAutomationRegressionTest.kt` | Continued automation after isolated aborts, true cancellation, and nonrecursive prompt cycles |
| `composeApp/src/commonTest/.../data/HeartbeatPromptBuilderTest.kt` | Current-cycle prompt sections and omissions |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Persisted heartbeat config, prompt, and log storage |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Run-owned heartbeat history, unread state, background model selection |
| `composeApp/src/commonMain/.../data/ConversationRun.kt` | Commit ordering and durable terminal status |
| `composeApp/src/commonMain/.../data/ToolApproval.kt` | Unattended permission pause and uncertainty handling |
| `composeApp/src/commonMain/.../ui/chat/composables/RunActivity.kt` | Saved heartbeat action inspection and interactive continuation |
| `composeApp/src/commonMain/.../ui/chat/composables/HeartbeatBanner.kt` | Dismissable notification banner UI |
| `composeApp/src/commonMain/.../ui/settings/HeartbeatSection.kt` | Heartbeat settings UI section |
| `composeApp/src/commonMain/.../ui/settings/AgentSettings.kt` | Agent tab layout that hosts the heartbeat section |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Heartbeat setting changes, eligible model list, manual refresh |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Gates scheduling tools on the scheduling switch |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Opens the heartbeat conversation when a notification tap requests it |
| `composeApp/src/commonMain/.../Platform.kt` | Platform notification boundary for background reports |
| `composeApp/src/androidMain/.../HeartbeatNotifier.android.kt` | Android notification + deep-link intent extra |
| `androidApp/src/main/kotlin/.../MainActivity.kt` | Reads the deep-link extra on launch/new intent and requests opening the heartbeat conversation |
| `androidApp/src/main/kotlin/.../KaiApplication.kt` | Tracks process foreground state for the scheduler |
