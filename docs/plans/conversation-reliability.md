# Conversation reliability, streaming, and unrestricted tool runs

Status: all 19 review fixes are implemented; the full desktop suite, Android assembly, production WebAssembly distribution, and formatting checks pass. Runtime/release acceptance is not complete.

Last updated: 2026-10-24

## Goal

Match the continuity users expect from Claude: stopping work does not erase the conversation, completed actions remain known, a restart restores saved partial answers, and long useful tasks are not cut off by a tool-round count. This is a behavioral target, not a claim about Claude's implementation or model quality.

## Invariants

1. Commit accepted input before generation, tool intent before dispatch, and each outcome before the next model request. Publish saved state only after successful persistence. A failed write stops new execution.
2. A run owns an immutable conversation ID and its own history. Navigation never redirects its writes. A stopped/deleted run cannot start more actions or resurrect a conversation.
3. Preserve complete tool identity, arguments, provider continuation metadata, outcomes, partial assistant text, and attempt status. Cancelled or disconnected external actions may have unknown outcomes; never claim they were rolled back.
4. Retry/continue creates a new attempt from recorded evidence, not a replay of tool actions. Regeneration and editing preserve prior activity and do not undo external effects.
5. The stored transcript is independent of the model request. Context reduction never deletes the transcript. Requests contain complete valid tool exchanges, continuation evidence, and a reserved output budget. Older evidence remains available for retrieval.
6. Streams update one stable assistant message. Small committed batches keep writes and rendering bounded. Incomplete streamed tool arguments never execute. A disconnected stream is not silently restarted over partial output.
7. No fixed total tool-call/round ceiling. Bounded loop detection, per-call timeouts, bounded retries and concurrency remain. Repetition pauses durably with a reason; continuing does not erase the earlier attempt.
8. Tool enablement and execution validation remain enforced at dispatch. Repeating an ambiguous side effect is not an automatic recovery strategy. Tool output, retrieved history, and summaries never grant permissions.
9. Persist large payloads without losing text or attachments to Android's row limit. Incremental saves must not rewrite all prior messages. Storage errors and corrupt records are surfaced, not mistaken for empty conversations.

## Implementation order

### 1. Durable records and persistence

Extend backwards-compatible message JSON with tool metadata, run identity, attempt/message state, and supersession metadata. Keep stable IDs. Add an explicit persisted run marker/checkpoint if needed rather than relying on transient UI loading flags. Serialize storage mutations and commit before publishing. Recover unfinished records as interrupted/unknown on startup. Use chunked payload storage with a numbered SQL migration for oversized messages; keep export/import self-contained. Save changed records incrementally and avoid silent truncation. Preserve previous data on invalid imports.

### 2. Run ownership and interruption

Introduce a small run-scoped history writer/coordinator shared by foreground and background execution. Check cancellation before dispatch and bind native callbacks to their parent run. Checkpoint each completed parallel tool separately, before any presentation delay. Preserve unresolved tool outcomes on Stop or failure. Wait for cancellation cleanup before permitting another foreground run; keep navigation safe through immutable run identity. Prevent automatic scheduled-task replay after uncertain side effects. Keep replaced attempts for audit and continuation context.

### 3. Real streaming

Use Ktor response channels and a bounded shared SSE decoder; no new dependency. Support OpenAI-compatible Chat Completions, Responses/Codex, Anthropic, and Gemini. Accumulate tool arguments by stream index/ID, validate terminal status, and dispatch only complete validated calls. Preserve provider reasoning/continuation metadata where required. Stream native LiteRT through its asynchronous API and explicitly cancel native work. Keep the iOS bridge capability explicit and implement its streaming/cancellation boundary where supported. Unsupported endpoints may return a normal complete JSON response; do not fake streaming or automatically retry an already-started stream.

### 4. Context and execution guards

Build request copies from durable records. Preserve interrupted work and unknown side effects even when tools are disabled or a provider cannot accept the original wire format. Keep raw history and source-linked compacted context separate; expose older transcript lookup. Include tool schemas, arguments and attachment payloads in conservative context/request budgets. Handle long single-user tool runs, not only multiple user exchanges. Normalize JSON object key order for loop signatures without altering string values or array order. Catch repeated calls/results and short cycles; distinguish polling and pause before repeating uncertain actions. Remove the 15-round ceiling only after these paths are covered by tests.

### 5. UI and acceptance

Render persisted partial/interrupted/paused status, keep actions disabled on unfinished structured UI, and avoid speaking partial text or forcing scroll while the user reads older messages. Preserve the main transcript while making replaced attempts and tool activity inspectable. Page the displayed transcript. Update chat, tools, and reasoning feature specs, including actual platform limitations.

## Release gates

- Restart real persistence after interruption at input, partial text, tool intent, individual tool completion, and finalization boundaries.
- Cancel parallel tools after one succeeds; completed evidence survives even when cancellation occurs during UI presentation.
- Stop/send/navigation/delete races never overlap conversation ownership or resurrect deleted state.
- Incomplete/malformed streams never execute partial calls; stream disconnection preserves text and does not replay actions.
- Regenerate/retry/provider switches and disabled tools preserve action evidence and produce valid provider requests.
- Long useful runs exceed 15 rounds; repeated/cyclic runs pause; legitimate polling is not treated as proven failure.
- Disk/quota failures do not publish false saved state or start unrecorded actions; oversized messages and old databases round-trip safely.
- Migrations/export/import round-trip tool metadata, partial answers, and attempt states.
- Relevant common/desktop tests, native and WebAssembly compilation, Android compilation, formatting, and available device/UI checks pass. Report unavailable platform/live-provider checks explicitly.

## Delivered implementation

- Backward-compatible durable messages/attempts, incremental SQL payload chunks and migration, immutable browser manifest commits, storage/import/deletion generation fences.
- One run owner for foreground and background history. Accepted input, intent, dispatch, and individual outcomes commit before publication/next actions. No duplicate scheduler report append.
- Real remote/native stream interfaces, stable assistant records, independent 100 ms checkpoints, honest complete-response fallback, terminal validation, and preserved interruption tails.
- Non-destructive attempt supersession and context projection, complete exchange grouping, provider-scoped opaque continuation/usage, native/durable call aliases, bounded exact history retrieval, and explicit unsupported-attachment notices.
- No total round ceiling. Bounded canonical repetition/cycle/failure history, one recovery opportunity, polling backoff, per-response/argument/output bounds, four remote workers, and per-call timeouts.
- Exact one-shot approvals with independent fresh UI tokens, same-run denial suppression, uncertainty warnings, and conservative unattended pauses. Reused provider IDs in another chat cannot make a stale approval click authorize new work.
- Durable scheduled claims, restart-to-paused recovery, reviewed Resume with repeat-action warning, visible background errors, and retained heartbeat activity.
- Run-bound post-commit input acknowledgement clears unchanged submitted text and captured attachments once, including while the composer is hidden, rather than inferring acceptance from a newly visible matching message. Stop cleanup state, transcript paging, superseded/terminal activity, sticky actual-tail following with user-controlled pause/resume, and once-only speech belonging to the completed request remain integrated.

## Post-review fixes — 2026-10-24

The six-area review identified 19 actionable findings. The implementation keeps the existing durable store and run coordinator; no provider/catalog expansion or new dependency is involved.

| Findings | Correction | Focused coverage |
|---|---|---|
| 1 | Only genuine exact-action approval carries permission into final enabled-tool lookup; availability races pause before an unauthorized action | Final-lookup tests plus foreground/unattended loopback repository regressions |
| 2, 9, 19 | Reject duplicate retained IDs before import/replacement, clean new staged keys on failure without touching committed blobs, and use set membership for obsolete-key cleanup | Import and both persistence backends; pre-reload failures, finite quota, and 4,000-message checkpoints |
| 13 | Replace cached same-ID shell ownership after import; old callbacks cannot acquire the new generation or overwrite its transcript | Ownership-generation regressions and Android compilation; physical process termination remains unverified |
| 3, 15 | Preserve logical Gemini text across transport chunks and retain refusals with absent/null content | Callback, completed DTO, saved assistant content, signatures, and ordinary-JSON comparisons |
| 4, 5 | Do not nest previous prompts; distinguish one conversation's abort from scheduler lifetime cancellation | Repeated cycles, true cancellation, and deletion during real repository output followed by unrelated due tasks |
| 6, 12, 18 | Close owned native handles once, separately budget outgoing receipt excerpts with durable retrieval IDs, and preserve emitted phase whitespace | Replacement/close failures, full source versus bounded dense receipts, and split thinking markers |
| 7, 16, 17 | Settle even pre-dispatch cancellation, bind speech to the owned successful return value once, and retain acknowledgement ownership beside hidden drafts | Paused dispatcher, delayed cleanup, unrelated transcript publication, rendered hide/accept/show, and owner-aware restoration without false acceptance from reset counters |
| 8, 10, 11, 14 | Seed loops within the continued request, serialize regeneration with background ownership, pause after recorded native output, and project text-only attachments before budgeting | Independent/continued/replaced requests, blocked background regeneration, scheduled native prefix failure, and 4K/8K attachment requests |

### Post-review verification

| Check | Current result | Evidence |
|---|---|---|
| Complete common/desktop suite | **117 suites / 1,304 tests; zero failures, errors, skips, or stderr output** | `build/reliability-fixes-20261024-172100/desktop.log`; successful in 3m 55s; `desktop-summary.json` |
| Formatting | Spotless application and check passed | `build/reliability-fixes-20261024-172100/format.log`; `platforms.log` |
| Android FOSS debug | APK assembled; local signature verification passed; not installed | `build/reliability-fixes-20261024-172100/platforms.log`; `androidApp/build/outputs/apk/foss/debug/androidApp-foss-debug.apk` |
| Production WebAssembly | Source, production executable, Binaryen optimization, and browser distribution passed; not deployed | `build/reliability-fixes-20261024-172100/platforms.log`; combined formatting/Android/Web pass successful in 33m 23s |
| Source stability | 673 source files fingerprinted after the desktop pass; no subsequent source drift observed | `build/reliability-fixes-20261024-172100/verified-source-hashes.json` |

Earlier focused attempts found two test-fixture compilation errors and then passed 179 of 181 tests. The remaining thinking-marker regression exposed a newline escaping an unfinished block; filtering now withholds the whole unfinished block without an end-of-line anchor. The stale-generation test needed to drain the derived state-flow dispatcher before observing navigation's reset. Both checks, plus hidden/restored composer ownership regressions, pass in the complete suite above. Intermediate failure logs remain under `build/reliability-fixes-20261024-172100/`; the review's nine Java probes assert the old defects and are not acceptance tests for these fixes.

## Earlier verification record — before the review fixes

Initial reliability checks on 2026-10-24:

| Check | Result | Evidence |
|---|---|---|
| Complete common/desktop suite | 1,243 tests; zero failures, errors, or skips | `build/conversation-final-desktop.log`; successful in 12m 23s |
| Android FOSS debug APK | Assembled at that pass; installed later, before review fixes | `build/conversation-final-platforms.log` |
| WebAssembly source compilation | Passed for the then-current source | `build/conversation-final-platforms.log` |
| Selected Paparazzi renders | Three passed; zero failures/errors/skips; images visually inspected | `screenshotTests/build/reports/paparazzi/debug/index.html` |
| Formatting and whitespace | Final Spotless check and Git whitespace check passed | `build/conversation-final-format.log`; successful in 7s |

The final platform/render pass succeeded in 15m 13s. APK: `androidApp/build/outputs/apk/foss/debug/androidApp-foss-debug.apk`. The screenshots show visible uncertainty warnings, exact arguments and approval buttons, retained interrupted work with Continue/activity, and an unfinished Markdown fence during streaming. They use synthetic data, not a personal device capture.

The first stabilization pass passed 1,238 desktop tests, Android assembly, and production WebAssembly distribution (`build/conversation-release-checks.log`, successful in 22m 27s). A later combined pass completed those platform tasks/rendering but failed the newly added stale-token regression against its already-compiled pre-fix desktop artifact; bytecode inspection confirmed provider IDs were still used there. The fresh 1,243-test rebuild above includes and passes that fix, native no-retry-after-output/action tests, truncated ordinary-JSON response tests, run-bound input acknowledgement, and a matching-history false-acceptance regression.

The optimized WebAssembly distribution in `build/conversation-final-checks.log` predates the final composer-acknowledgement fix. It is superseded by the post-review production distribution above.

Automated coverage includes fresh-store recovery at durable transitions, SQL migration/rollback/Unicode chunking, settings quota-failure boundaries, import/deletion fencing, more than 20 retained exchanges, continuation compatibility, 22 real loopback-HTTP tool rounds, post-commit input acknowledgement before the first HTTP request, parallel Stop, and late-stream navigation. Fresh-store/in-memory database reconstruction is not a physical process-kill or power-loss test. Static screenshots do not prove interactive behavior or accessibility.

## Remaining release acceptance

- iOS/Swift compilation and runtime validation require macOS/Xcode; neither has been performed here.
- Native JNI/Swift cancellation acknowledgement, Stop during native load, real-device restart/migrations, and safe immediate reuse still need dedicated hardware/emulator checks. The pre-review APK was updated in place on the authorized phone, most recently at 16:03 on 2026-10-24; no post-review APK has been installed. No personal-app launch, capture, input, or phone acceptance was performed.
- Live-provider continuation across signatures/encrypted reasoning, real endpoint SSE quirks, billing/Stop semantics, and service switching require configured-provider acceptance. Loopback/DTO tests do not establish interoperability.
- Browser quota/reload recovery, long-session write/render performance, and actual process-kill recovery remain runtime checks.
- The rendered fixture check covers selected states only; click-through approval/task resume, Stop→send, long-history paging/scrolling, TTS, structured UI, screen-reader behavior, and navigation/restoration races still need interactive acceptance.

## Boundaries

No new model providers, embedding service, backend, generic event-sourcing framework, or full branching UI. Physical storage is finite. A model may still reason badly, and a third-party action cannot always be cancelled or deduplicated; preserve and expose that uncertainty. Never claim parity with Claude without measuring the acceptance scenarios.
