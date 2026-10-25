<!-- Modified for Maxit, 2026-10-20. -->

# Notifications

**Last verified:** 2026-10-24. All 1,313 desktop tests passed across 119 suites (zero failures, errors, or skips), including preservation of pending approval when opening the current conversation; formatting checks also passed. A synthetic alert through the production desktop sender previously produced a visible, normal-priority GNOME banner, confirmed through accessibility after recovery of the desktop's stuck notification handler. Android foreground suppression and notification-tap behavior still need on-device confirmation.

> Reading notifications is **Android-only** and requires the user's notification-access permission. Maxit ships only the FOSS Android variant.

Maxit on the FOSS Android build can **read** notifications posted by other apps and surface them to the AI, mirroring the SMS feature: per-app opt-in, capped pending queue, heartbeat summary, plus on-demand `check_notifications` / `read_notification` / `search_notifications` tools.

The app also provides a separate, enabled-by-default **Response notifications** setting. It uses the native notification surface to tell the user when the AI finishes a response or pauses for tool approval. On desktop, the app tracks its window focus and posts only while Maxit is unfocused. On Android, response and approval alerts are suppressed while any Maxit activity is in the foreground, using the same app-lifecycle signal as heartbeat alerts. A running background service does not count as foreground use. Suppressed alerts are not replayed when the user leaves the app. iOS retains its platform behavior. Completed-answer alerts include a short plain-text preview (or a generic label if there is no readable text). On Android 13+, Maxit requests the OS notification permission at startup if alerts are enabled, and again when the toggle is turned on. When denied, Settings shows an app-settings link; no alert is posted without permission. This setting is independent of notification reading and is available wherever Maxit has a native notification implementation.

Acting on a notification (replying via `RemoteInput`, dismissing) is out of scope for the first cut and would follow the SMS-send draft pattern when added.

## Availability

- **FOSS Android build**: fully available.
- **iOS / desktop / web**: unsupported. iOS does not allow third-party apps to read system notifications at all; desktop and web have no equivalent surface. No-op stubs.

The availability gate is manifest-based: the `foss` product flavor contributes `androidApp/src/foss/AndroidManifest.xml` declaring the listener service with `BIND_NOTIFICATION_LISTENER_SERVICE`. At runtime the app checks whether its own package declares the listener service to decide whether to show the feature.

## Scope

- **Read**: list / read / search notifications posted to the system tray since the listener was bound.
- **Per-app filtering is delegated to the OS.** System Notification Access already exposes an "Apps" picker per listener — if the user unchecks an app there, `onNotificationPosted` is never fired for that package. We don't duplicate that UI in Maxit; the in-app toggle is just a master switch for the whole feature.
- **Master switch pauses capture.** While the in-app toggle is off, posts are dropped even if system access is still granted.
- **Visible notifications only.** Ongoing and foreground-service notifications (media controls, downloads, navigation) are filtered out via `FLAG_ONGOING_EVENT` and `FLAG_FOREGROUND_SERVICE` — they are sticky UI affordances, not events. Posts with a blank title and blank text are also dropped at capture.
- **No reply, no dismiss, no action invocation in v1.** The listener is read-only.
- **Secret visibility skipped.** Notifications posted with `Notification.VISIBILITY_SECRET` are skipped. There is no separate "sensitive / lockscreen-redacted" flag on stored records in v1 — content is captured as posted for everything else that passes the filters.

## Opt-in flow

Notification access is granted via system settings, not a runtime permission dialog — there is no `requestPermissions` path for `BIND_NOTIFICATION_LISTENER_SERVICE`.

1. In **Settings → Agent → Notifications → "Read notifications"**, the user flips the toggle on. The toggle records **user intent** and stays on even if access is not yet granted.
2. If access is not yet granted, the app deep-links to the system notification-access screen — Maxit's own detail page where supported, otherwise the global listener list — so the user can enable Maxit.
3. On return (and whenever the settings screen resumes), the app re-checks whether listener access is granted for Maxit's component. On Android 8.1+ this asks the system directly; on Android 8.0 it falls back to reading the system's list of enabled notification listeners. Access state is shown separately ("Listener active" / inactive, plus an open-access control when intent is on but access is missing). The master toggle does **not** auto-reset when access is denied.
4. Once granted, Android binds `KaiNotificationListenerService`. From then on every `onNotificationPosted` callback that passes the visibility filters writes a record into the pending queue. Until access is granted, posts are not delivered to the listener. The user can refine *which* apps Maxit sees from the same system Notification Access screen — the "Apps" picker per listener is the source of truth.
5. On the next heartbeat, the queue snapshot is included in the prompt under `## New Notifications`. After the heartbeat run, exactly that snapshot is removed from the queue — notifications that arrived during the call survive to the next heartbeat.

If the user later revokes notification access from system settings, `onListenerDisconnected` fires, access reads as false, and the read tools stop appearing in the AI's available-tools list until access is re-granted (the intent toggle can remain on).

## Per-app filtering

Per-app filtering is **the OS's job**. Android's system Notification Access screen, when opened on a specific listener, exposes an "Apps" picker that lets the user toggle which apps the listener can read. Maxit's settings card includes a "Manage apps" button that deep-links straight there.

This was a deliberate simplification — earlier iterations of this feature shipped an app-side "Ignored apps" list, but the OS-level picker covers the same ground without duplicating UI or maintaining a parallel allowlist. A small set of packages is still **hard-blocked** at the listener callback (Maxit itself, the Android system package, and System UI) to avoid feedback loops, but everything else flows through whatever the system has approved.

## No polling interval

Unlike SMS and email, there is **no `pollIntervalMinutes`**. Notifications are push: the listener service receives `onNotificationPosted` callbacks the moment a notification fires, and writes synchronously to the store. The scheduler does not need a `checkNewNotifications` hook.

The heartbeat still drives the AI summarisation cadence — the pending queue accumulates between heartbeats and gets flushed when the heartbeat runs.

## Retention

The pending queue and the broader notification store are both bounded:

- **Pending queue capacity 100** (FIFO). Older notifications are dropped if the queue fills before a heartbeat consumes it. Identical to SMS.
- **Age cap 24h.** Records older than 24 hours are pruned from the broader store whenever a new notification is recorded, and again by a sweep after every successful heartbeat run. There is no separate retention sweep on listener bind.
- **Per-app record cap 50.** Prevents one chatty app (e.g. a group chat) from monopolising the store. Oldest-first eviction, applied on the same schedule as the age cap.

Records are persisted in the encrypted app settings store alongside email/SMS pending so they survive process death.

## Captured fields

| Field | Source | Notes |
|---|---|---|
| `id` | `StatusBarNotification.key` | Stable across `post`/`update` for the same notification. Used as the read/search lookup key; a lookup returns the newest record with that id. |
| `package_name` | `StatusBarNotification.packageName` | Used for the per-app cap, hard-block matching, and the search filter. |
| `app_label` | `PackageManager.getApplicationLabel` | Best-effort; falls back to package name on lookup failure. |
| `title` | `extras["android.title"]` | Trimmed. |
| `text` | `extras["android.bigText"]` ?: `extras["android.text"]` | Big text preferred when present. |
| `subtext` | `extras["android.subText"]` | Optional. |
| `posted_at` | `StatusBarNotification.postTime` | Epoch ms. |
| `is_ongoing` | — | Always false on stored records, since ongoing notifications are filtered out at capture time. |
| `category` | `Notification.category` | e.g. `msg`, `email`, `alarm`. |
| `preview` | First 200 chars of `text` | Shown in `check_notifications` and the heartbeat prompt. |

Text is taken from `extras["android.bigText"]` when present, otherwise `extras["android.text"]`. There is no separate `MessagingStyle` / `android.messages` parse in v1 — group-chat style notifications appear as whatever single title/text the system put on the status-bar notification.

## AI Tools

Registered on Android only, and only when all three hold: the build supports the feature (FOSS), the "Read notifications" toggle is on, and listener access is granted. Each tool also re-checks support and access when called and returns an error if either is missing:

| Tool | Purpose |
|---|---|
| `check_notifications` | List notifications currently in the heartbeat pending queue. Returns `id`, `package_name`, `app_label`, `title`, `posted_at`, `preview` for each. |
| `read_notification` | Fetch the full body of a single notification by `id` — title, text, subtext, category, app, and posted time. |
| `search_notifications` | Full-text search over `app_label` + `title` + `text`, newest first, capped at 20. Optional `package_name` filter. |

The pattern intentionally mirrors the SMS read triplet so the AI can transfer mental model: "check for new things, read a specific one, or search by text."

All three also carry a display definition, so chat shows their names ("Check Notifications") rather than raw tool ids while they run. As with SMS, the definitions are marked non-toggleable — the "Read notifications" master switch is the only control — so they do not appear in the Tools tab.

## Heartbeat surface

The heartbeat prompt gains a `## New Notifications` section, formatted parallel to `## New Emails` / `## New SMS`. It lists at most the 20 newest pending notifications, and is omitted when the queue is empty or the "Read notifications" toggle is off:

```
## New Notifications
These notifications arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.
- **WhatsApp** — Alice (id: 0|com.whatsapp|...): Hey, are we still on for tonight?
- **Gmail** (id: 0|com.google.android.gm|...): New message from boss@…
```

Same lifecycle as the SMS pending queue: the snapshot is taken before the heartbeat, only that snapshot is removed afterward, anything that arrived during the call survives. The whole snapshot is removed — including entries beyond the 20 rendered into the prompt, and even when the toggle is off and the section was omitted. Nothing is removed if the heartbeat call fails.

## Notifications

The existing heartbeat notification path reports non-trivial heartbeat responses and deep-links into the heartbeat conversation. Separately, the **Response notifications** setting reports completed foreground-chat responses and each pending tool approval through the native OS notification surface. On Android, response/approval alerts have their own high-importance channel for heads-up banners, separate from existing AI tool and heartbeat notifications. The OS may still suppress banners under Do Not Disturb or channel settings. Linux alerts identify themselves as Maxit and use normal urgency; they do not request critical priority or override Do Not Disturb. Diagnostics report completion/approval eligibility, the setting and foreground gate, and the Linux notification process exit code and returned OS notification ID. Titles, response previews, tool arguments, and conversation IDs are not logged. The process is bounded to ten seconds and never blocks the chat UI. OS acceptance and delivery to the notification list are not proof that a banner appeared. Simultaneous approvals remain separate notifications; tapping one opens the conversation containing the approval (approval decisions are never made from the notification). If that conversation is already selected, opening the alert preserves the running request and pending approval instead of reloading the conversation. Other saved conversations still open normally; missing targets leave the current conversation unchanged. If the process was killed, pending approval cannot be restored as a live request. Web remains a no-op.

## Settings UI

The Notifications section appears in **Settings → Agent** only when `isNotificationsSupported` is true (FOSS build). One card with:

- **Read notifications** toggle — records intent and deep-links to system notification-access settings when turned on; remains on even if access is still missing.
- **Access-required row with "Open notification access"** — shown instead of the rows below when the toggle is on but access has not been granted (or was revoked).
- **Listener status** — "Listener active" / "Listener inactive — check notification access" (shown once access is granted).
- **"Manage apps"** button — next to the status; deep-links to the same system Notification Access screen so the user can adjust which apps Maxit can read.
- **Queued count + Clear queue** — shown only when the pending queue is non-empty: the number of notifications waiting for the next heartbeat, with a button to flush them on demand.

There is **no poll interval slider** — the listener is push-driven.

## Key Files

| File | Purpose |
|---|---|
| `androidApp/src/foss/AndroidManifest.xml` | Declares `BIND_NOTIFICATION_LISTENER_SERVICE` and registers `KaiNotificationListenerService` in the FOSS flavor only |
| `composeApp/src/commonMain/.../data/NotificationModels.kt` | `NotificationRecord`, `NotificationSyncState` data classes |
| `composeApp/src/commonMain/.../data/NotificationStore.kt` | Pending queue + broader store + retention sweeps |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../notifications/NotificationReader.kt` | Expect interface for `getById`, `search`, `currentRecords` |
| `composeApp/src/androidMain/.../notifications/NotificationReader.android.kt` | Reads from the in-memory + persisted listener store |
| `composeApp/src/androidMain/.../notifications/KaiNotificationListenerService.kt` | `NotificationListenerService` subclass; visibility/hard-block filter + write to `NotificationStore` |
| `composeApp/src/commonMain/.../tools/NotificationTools.kt` | `check_notifications`, `read_notification`, `search_notifications` tool definitions |
| `composeApp/src/commonMain/.../tools/NotificationListenerController.kt` | Expect interface for "is access granted" + "open settings" |
| `composeApp/src/androidMain/.../tools/NotificationListenerController.android.kt` | `NotificationManager.isNotificationListenerAccessGranted` + `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` deep-link |
| `composeApp/src/androidMain/.../Platform.android.kt` | `isNotificationsSupported` gate + conditional tool registration |
| `composeApp/src/commonMain/.../data/HeartbeatPromptBuilder.kt` | `## New Notifications` section renderer |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Heartbeat snapshot/remove lifecycle for the pending queue (no poll hook — listener is push) |
| `composeApp/src/commonMain/.../ui/settings/HeartbeatSection.kt` | `NotificationsSection` Compose UI with toggle + Manage apps deep-link |
| `composeApp/src/commonMain/.../ui/settings/AgentSettings.kt` | Places the Notifications card in the Agent tab when supported |
| `composeApp/src/commonMain/.../data/PendingQueue.kt` | Shared capped (100) FIFO pending queue used by email, SMS, and notifications |
| `composeApp/src/commonMain/.../data/HeartbeatManager.kt` | Caps the heartbeat snapshot at the 20 newest and skips it when the toggle is off |
| `composeApp/src/commonMain/.../tools/CommonTools.kt` | Registers the non-toggleable notification tool display definitions |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Sends short answer previews and per-approval alerts, gated by setting and desktop focus |
| `composeApp/src/commonMain/.../ui/settings/GeneralSettings.kt` | Response notifications toggle and permission warning |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Persists toggle and requests notification permission |
| `composeApp/src/commonMain/.../App.kt` | Requests Android permission at startup and routes approval notification taps to chat |
| `composeApp/src/androidMain/.../HeartbeatNotifier.android.kt` | Checks OS permission, posts distinct Android alerts and attaches conversation targets |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Persists the enabled-by-default response-alert setting |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Delivers tapped conversation targets to the UI |
| `composeApp/src/commonTest/.../ui/chat/ChatNotificationTest.kt` | Toggle, completion, multi-approval, and notification-navigation regression checks |
| `androidApp/src/main/.../MainActivity.kt` | Accepts notification conversation deep links |
| `composeApp/src/desktopMain/.../main.kt` | Tracks window focus for desktop response alerts |
| `composeApp/src/desktopTest/.../DesktopNotificationFocusTest.kt` | Checks the focus gate and normal-urgency Linux notification arguments |
| `composeApp/src/desktopMain/.../Platform.jvm.kt` | Sends desktop notifications with the Maxit name and plain-text previews |
| `composeApp/src/androidHostTest/.../AndroidNotificationForegroundTest.kt` | Checks Android alerts against the existing process-foreground signal |
| `androidApp/src/main/.../KaiApplication.kt` | Tracks Android app foreground state shared by heartbeat and response alerts |

## Future scope (not v1)

- **`reply_notification` tool.** Inspect the captured `Notification.actions` for a `RemoteInput`-bearing reply action; if present, expose a draft tool that mirrors `reply_sms` (banner-gated send). Useful for messaging apps.
- **`dismiss_notification` tool.** Call `NotificationListenerService.cancelNotification(key)`. Low-friction so could ship behind a separate "Allow dismissing" toggle.
- **OTP redaction.** Auto-detect 4–8 digit OTP-style codes in capture and elide them from the heartbeat prompt by default; let the AI request the full body via `read_notification` only when it's clearly a non-sensitive context.
- **Per-channel filtering.** Within an allowed app, let the user pick which `NotificationChannel` IDs to capture (e.g. allow Slack mentions, drop Slack reactions).
