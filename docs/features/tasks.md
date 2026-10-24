<!-- Modified for Maxit, 2026-10-24. -->

# Tasks

**Last verified:** 2026-10-24. Local scheduler/repository regressions do not replace physical process-kill or device-background acceptance.

Maxit's tasks feature enables the AI to schedule one-time or recurring actions for future execution. Tasks are created through AI tools, stored persistently, and executed automatically by a background scheduler that polls on a fixed interval.

## Concepts

### Task

A scheduled action containing an id (UUID), a human-readable description, a prompt to execute, a creation time, and a **trigger** that decides when it fires. Tasks track status (PENDING, RUNNING, PAUSED, or COMPLETED), the result of their last execution, a consecutive failure count for backoff, and a log of their last 10 runs (timestamp, OK/FAIL, reason).

### Trigger

How a task is dispatched. Every task carries one of three triggers:

- **TIME** — fires once at its scheduled time, then transitions to COMPLETED.
- **CRON** — recurs on a cron expression; the scheduled time holds the next fire time. Stays PENDING; the scheduler advances it after each run.
- **HEARTBEAT** — a standing addition to every heartbeat self-check. Not picked up by the time-based poll loop; instead the prompt is appended to the heartbeat message under `## Heartbeat Additions`. Stays PENDING until the user (or AI) cancels it. Scheduled time and cron are ignored.

Legacy tasks stored before triggers existed are migrated on load (and the upgrade is persisted once): tasks with a cron expression become CRON, all others TIME.

### Cron Expression

A 5-field schedule format (`minute hour day-of-month month day-of-week`) used for recurring tasks. Supports wildcards (`*`), steps (`*/n`), ranges (`1-5`), and comma-separated lists. Day-of-week follows standard cron numbering (0=Sunday, 6=Saturday). Unlike standard cron, when both day-of-month and day-of-week are restricted, a time must match **both**. The parser searches up to ~2 years ahead for the next matching time; if none is found the task is marked COMPLETED.

## Task Lifecycle

1. The AI calls the `schedule_task` tool with a description, prompt, and exactly one trigger: `execute_at`, `cron`, or `on_heartbeat: true`
2. A new task is created with a UUID and a trigger (TIME/CRON/HEARTBEAT), and persisted to storage
3. For cron-based tasks, the first execution time is computed from the cron expression
4. The background scheduler polls every 60 seconds and checks for due tasks (TIME/CRON with execution time <= now). HEARTBEAT tasks are not picked up here
5. A due occurrence is durably claimed as RUNNING before its prompt is sent. Its run owns the heartbeat conversation and sandbox session; accepted input, streamed text, tool activity, and final output are saved there, not in the foreground chat
6. TIME tasks: status is set to COMPLETED after execution
7. CRON tasks: next execution time is computed from the cron expression and the task remains PENDING
8. HEARTBEAT tasks: their prompts are appended to the heartbeat user message under `## Heartbeat Additions` during each heartbeat run; they never transition state on their own
9. The execution result and timestamp are stored on the task

## Execution Rules

- Task execution is skipped if the app is currently processing another API call
- Due tasks run one after another; if a foreground chat request starts mid-cycle, the remaining tasks wait for the next poll
- Task prompts use the shared tool loop and the normal background model selection: first usable configured remote service, otherwise an available on-device service. The heartbeat model override does not apply. Saved heartbeat work supplies continuity through bounded request projection and historical retrieval; it is not blindly replayed. Non-blank reports are saved once by their run, without a second formatted copy appended by the scheduler
- Unattended runs pause before an action requiring explicit approval, including MCP calls and writes. They do not infer permission from task text, a model assertion, or a previous approval. Read-only built-ins can proceed. SMS retains its separate draft/send approval gate
- There is no fixed tool-round ceiling. Repetition, cycles, repeated failure, and independent resource bounds can pause a run; see [tools.md](tools.md)
- Results are stored as the task's last result for audit purposes, and each run is added to the task's recent-activity log
- The scheduler also handles heartbeat checks, email polling, and (FOSS builds) SMS polling in the same poll loop. Email polling fetches headers in batches of up to 50 per account per poll and buffers them for the next heartbeat to pick up; no AI call runs during email or SMS polling itself

## Failure Handling

- Tasks track their consecutive failure count
- A provider/configuration failure before any assistant output or proposed action can use the existing retry policy: cron advances to its next scheduled occurrence; one-time work backs off by 60s × 2^failures, capped at one hour
- Cancellation, stream interruption, persistence failure, approval/loop pause, or failure after saved assistant output or proposed/dispatched actions makes that occurrence PAUSED rather than automatically replaying it. This includes an on-device timeout after a streamed prefix, even when no tool ran
- Deleting or invalidating the active background conversation pauses only that occurrence. Other due tasks, heartbeat checks, and inbox polling remain eligible, and the deleted conversation is not resurrected. Actual scheduler-job cancellation still stops the loop after settling its claim
- On successful execution, the failure counter resets to zero
- Failed tasks store the error (exception type plus message) as their last result, including what the scheduler will do next (e.g. the backoff delay or next cron time), for visibility in the settings UI
- On startup, saved RUNNING claims become PAUSED. Missing outcomes are recovered in the heartbeat transcript as unstarted or unknown, without executing them. A claimed interrupted occurrence is excluded from due work
- Storage failures that escape occurrence handling stop the scheduler loop and surface a background execution error in chat instead of an uncaught invisible failure
- After reviewing the saved heartbeat activity, the user can explicitly Resume a paused task. A confirmation warns that prior actions were not undone and may be repeated. Resume marks it PENDING for immediate execution and resets its retry counter; approval-requiring unattended actions can pause it again. Continue in the heartbeat chat is a separate interactive attempt, not an automatic task-status change

## AI Tools

| Tool | Purpose |
|---|---|
| `schedule_task` | Create a one-time, recurring, or heartbeat-triggered task with a description, prompt, and exactly one trigger |
| `list_tasks` | List all tasks, optionally filtered by PENDING, RUNNING, PAUSED, or COMPLETED |
| `cancel_task` | Remove a task by its id |

### schedule_task Validation

- **Exactly one** of `execute_at` (ISO 8601), `cron`, or `on_heartbeat: true` must be provided — they are mutually exclusive. Empty values (`"cron": ""`, `on_heartbeat: false`) count as not provided, since models often send unused optional fields as defaults
- `execute_at` accepts offset-qualified (`2026-04-22T22:32:39+02:00`, `...Z`) or naive (`2026-04-22T22:32:39`) values. Naive values are interpreted in the user's local timezone, which is surfaced to the AI via the `## Context` block's `Local time` line. Offset-qualified form is preferred to avoid UTC/local ambiguity
- Past-dated `execute_at` values (more than a minute before now) are rejected at tool-call time with an error that points at the `Local time` context — most often this is a UTC/local sign flip that the model can correct on retry
- The cron expression is not validated at call time; an expression the parser can't read schedules the task to run on the next poll
- Returns the created task's id, description, trigger, the requested `execute_at` (or "n/a"), and cron expression (or "none")

### Heartbeat-triggered tasks

Tasks created with `on_heartbeat: true` carry `trigger = HEARTBEAT`. Their prompts are appended to every heartbeat self-check under `## Heartbeat Additions`. They're the mechanism for standing heartbeat behaviour ("greet me on every heartbeat", "always summarise new emails") — the main heartbeat prompt stays untouched, and each addition is a cancellable first-class task visible via `list_tasks` and in the Scheduled Tasks UI. They stay PENDING until cancelled.

## Settings UI

The scheduled tasks section in settings provides:

- **Feature toggle** — enables or disables the scheduling feature globally; when disabled, no tasks execute and scheduling tools are unavailable to the AI
- **Task list** — shown only while the toggle is on. Each task shows its description, status (PENDING, RUNNING, PAUSED, or COMPLETED), and either a local execution time with UTC offset, a human-readable cron description, or "on every heartbeat"
- **Tap a task** — opens a details sheet with its saved instructions, trigger, status, schedule (or next run for cron), creation time, consecutive failure count (when non-zero), last result, and a recent-activity log of up to the last 10 runs (timestamp + OK/FAIL + reason). For HEARTBEAT-trigger tasks the activity list is sourced from the heartbeat-wide log instead, since they don't fire on their own schedule
- **Resume task** — available for PAUSED tasks, with an explicit review/repetition warning. Failed status writes remain visible as errors and do not publish a false pending state
- **Delete button** — available on all tasks to remove them; deletion is deferred with a snackbar "Undo" option (~4 seconds) before the task is permanently cancelled

### Cron Description

Cron expressions are converted to readable descriptions in the UI:

- `0 9 * * *` displays as "Daily at 9:00"
- `0 14 * * 1,2,3` displays as "Every Mon, Tue, Wed at 14:00"
- `0 8 15 * *` displays as "Monthly on day 15 at 8:00"
- Complex expressions fall back to the raw cron string

## Storage

- Tasks are serialized as a JSON array in app settings
- All task operations are thread-safe via mutex synchronization
- The scheduling enabled state is stored as a separate boolean setting

## Scheduler Lifecycle

The scheduler runs on its own process-lifetime background scope — it is **not** coupled to any screen's lifetime. Both the chat screen (after successful history restoration) and the Android foreground service can start the scheduler. Starts are synchronized and idempotent. The loop normally lasts for the process lifetime; an unhandled storage/execution error stops it visibly rather than continuing unsafe work.

Consequences:

- When the Activity is destroyed (user backgrounds the app, MIUI reclaims memory, etc.), the scheduler **keeps running** as long as the process is alive.
- On Android, the `DaemonService` foreground notification is what keeps the process alive. If the user disables the daemon, the process can be killed and the scheduler dies with it — pending, unclaimed tasks resume firing when the app is opened. Claimed unfinished occurrences are recovered as paused, not treated as ordinary past-due work.
- Aggressive OEM battery managers (MIUI, EMUI/Huawei) sometimes kill the foreground service while the activity is still alive in the background. To recover sooner, the main activity re-asserts the daemon on every foreground bring-up (idempotent when the service is already running), so the user only has to swipe back into the app rather than fully relaunch it.
- Task execution is gated on a "chat is busy" check supplied by the chat screen (so a foreground chat request doesn't race with a scheduled task). When the chat screen goes away, the check is reset to "never busy" so the daemon-only path keeps running unblocked.
- Scheduled tasks fire independently of heartbeat state — heartbeat being off never prevents a scheduled task from running.

## Daemon Mode

When daemon mode is active (Android foreground service), the app process is kept alive, so the scheduler scope keeps polling — tasks execute on time without user interaction. When daemon mode is off, the scheduler still runs whenever the app is open; missed tasks fire on the next open.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/ScheduledTask.kt` | Task data class and status enum |
| `composeApp/src/commonMain/.../data/TaskStore.kt` | Task CRUD, durable claims, interruption recovery, reviewed resume |
| `composeApp/src/commonMain/.../data/ConversationRun.kt` | Run-owned commits and terminal state |
| `composeApp/src/commonMain/.../data/ToolApproval.kt` | Exact-action permission and unattended pause |
| `composeApp/src/commonTest/.../data/ConversationReliabilityTest.kt` | Claim-once, restart pause, and explicit resume regressions |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Background poll loop, occurrence cancellation, and task execution |
| `composeApp/src/commonTest/.../data/TaskSchedulerAutomationRegressionTest.kt` | Isolated conversation aborts versus true scheduler cancellation |
| `composeApp/src/desktopTest/.../data/ConversationExecutionTest.kt` | Native prefix failure pauses a real scheduled occurrence without redispatch |
| `composeApp/src/commonMain/.../data/CronExpression.kt` | Cron parsing and next-execution-time computation |
| `composeApp/src/commonMain/.../tools/SchedulingTools.kt` | AI tool definitions for schedule, list, cancel |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Persisted task JSON and scheduling toggle |
| `composeApp/src/commonMain/.../ui/settings/AgentSettings.kt` | Scheduled tasks UI section and task details sheet |
| `composeApp/src/commonMain/.../ui/settings/HeartbeatSection.kt` | Cron-to-readable-description formatting used by the task list |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Scheduling toggle, reviewed resume/error handling, and undoable deletion |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Exposes the scheduling tools only while scheduling is enabled |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Starts the scheduler and supplies the "chat is busy" check |
| `composeApp/src/commonMain/.../DaemonController.kt` | Background execution support |
| `composeApp/src/androidMain/.../DaemonService.kt` | Android foreground service that keeps the process alive so the scheduler scope keeps polling |
