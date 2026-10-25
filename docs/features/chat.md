<!-- Modified for Maxit, 2026-10-25. -->

# Chat & Conversations

**Last verified:** 2026-10-25. See the [verification plan](../plans/conversation-reliability.md) for remaining runtime and platform acceptance checks.

Conversations survive Stop, failed requests, navigation, and app restarts without deliberately discarding saved work. Switching providers changes the request format, not the saved conversation. See the [implementation and verification plan](../plans/conversation-reliability.md) for acceptance results and outstanding platform checks.

## Conversation Lifecycle

- Each conversation has a stable ID, title, creation/update times, and a normal, heartbeat, or legacy interactive type.
- The current-conversation pointer survives launches, including an empty new chat the user deliberately opened. A missing/deleted pointer opens an empty chat.
- On the first upgrade from a version without that pointer, the most recently updated saved conversation becomes current.
- New Chat preserves previous conversations. A new ID is reserved when sending; accepted input and its running-attempt marker are committed together before generation starts.
- The title comes from the first user message, cut at a word boundary near 50 characters. An established title is retained.
- There is no 20-exchange chat cap or separate 50-message heartbeat cap. Saved messages remain until the user deletes or explicitly replaces them.
- Android's plain-text share sheet opens a new conversation with the shared text unsent. The previous conversation remains saved. Launcher and assist entry points keep their existing behavior.

## Saving and Run Ownership

The visible saved transcript follows successful storage commits, not speculative updates. Accepted messages, assistant checkpoints, tool proposals, dispatch markers, and individual tool outcomes are saved before publication or subsequent actions. A failed write stops execution rather than allowing an action without recorded intent.

Each attempt owns its original conversation and independent transcript. Navigation cannot redirect callbacks into another chat. Deleted conversations reject stale writes; replacing imported storage invalidates old attempts. Foreground requests wait for prior cancellation cleanup, and foreground/background model execution is serialized.

The transcript preserves tool names, exact arguments, linked results, partial output, provider continuation metadata and usage when supplied, and attempt status. Older versions' missing or discarded records cannot be reconstructed.

## Sending and Streaming

- Configure a remote provider or local model; there is no built-in Free service.
- While input and attachments are being prepared, the draft and queued files are retained until their own accepted user record is committed, including synchronous completion. Opening the sandbox or collapsing the composer does not lose that acknowledgement. Only unchanged submitted text and submitted files clear; later edits remain. Rejected input remains available to send again. Continue does not consume files queued for an unsent message.
- A restored pending draft can still receive its own live request's acknowledgement. After request ownership is recreated, reset counters are not treated as proof that an unacknowledged draft was sent.
- Remote Chat Completions, Responses/Codex, Anthropic, and Gemini consume real incremental response streams. Transport chunks do not become new paragraph boundaries on completion, and refusal text remains visible when normal content is absent or null. A normal JSON response is an honest complete-response fallback, not simulated typing.
- Missing or incorrect streaming content-type headers do not force an event stream through the JSON decoder. One initial byte-order mark is removed before decoding the first event. Responses streams with an empty final output list require a gap-free sequence of completed items starting from zero, including items seen only through text or argument fragments. Missing completion, unindexed output fragments, or missing received answer text fail instead of authorizing partial calls or overwriting saved text.
- A stable assistant message receives the first text checkpoint immediately, then pending deltas are checkpointed on a roughly 100 ms timer. Completion and interruption flush the remaining received tail. Storage/decoder backpressure can delay presentation.
- Only committed batches are guaranteed after abrupt process death. Bytes still in network/native buffers or an uncommitted checkpoint can be lost.
- Incomplete streamed tool arguments never execute. Missing completion, malformed events, truncation, and stream disconnects surface as failures, preserving committed text rather than silently replaying the request.
- A complete bare tool-call JSON envelope from remote Chat Completions receives at most one correction continuation per attempt, using saved work rather than replaying generation. Printed JSON is never dispatched; repeated envelopes pause visibly. Quoted examples remain text. An unquoted, whole-envelope example can trigger correction because its intent is ambiguous. See [Tools](tools.md#tool-call-protocol-recovery) for the detection scope and limits.
- Legacy wrapped calls are accepted only as standalone, complete answer content for declared tools. Reasoning and quoted, fenced, nested, incomplete, or prose-mixed examples stay text. Native calls retain precedence; ordinary approval and receipt rules still apply.
- Potentially harmful actions require one-shot approval of their exact arguments. Read-only operations normally do not require that gate, but a previously uncertain outcome still requires approval and an additional warning. Enabling a tool during execution does not manufacture permission. Tool output cannot supply authorization.
- Tool activity shows running work and expandable saved arguments/results. See [tools.md](tools.md) for execution safeguards.

## Stop, Failure, and Restart

- Stop requests cancellation of the network/native operation and in-flight tools. The input remains in a stopping state until coordinated finalization finishes. Cancelling before dispatch also returns the composer to idle; an older attempt's cleanup cannot settle a newer submission.
- Saved user messages, partial text, and completed outcomes remain. A completed parallel result is committed without waiting for its siblings.
- An unstarted proposal is recorded as not started. A dispatched call without a confirmed receipt is recorded as **outcome unknown**; cancellation does not prove that an external action was undone.
- Startup settles unfinished saved records as interrupted and reconstructs missing outcome records without executing them.
- Failed, interrupted, and paused attempts remain inspectable. Continue starts a new attempt using the saved evidence; it does not replay recorded tool calls.
- If storage cannot save finalization, the UI shows the storage failure; it does not pretend the terminal state was saved. The last committed checkpoint remains authoritative.
- Native cleanup is bounded. An engine whose cleanup cannot be acknowledged is blocked from reuse instead of overlapping a second native run. Actual device cancellation remains a runtime acceptance requirement.

## Retry, Regenerate, and Editing

Retry/Continue asks the model to proceed from recorded work. Regenerate and editing mark replaced messages as previous attempts instead of deleting them. Supersession waits for the same execution ownership as background output, so a running background attempt cannot overwrite the replacement decision. Earlier action receipts and uncertainty remain available as historical evidence; external effects are not rolled back.

Whole-attempt fallback/retry is restricted after streamed output or tool activity. Pre-output request failures can still use the configured bounded retry/fallback policy; see [multi-service.md](multi-service.md).

## Context Is Not the Transcript

Request copies reserve output space, include tool-schema and message/attachment estimates, and retain complete tool exchanges plus the current user input. Older material becomes bounded, source-linked, explicitly untrusted historical evidence. This reduction never rewrites the saved transcript and makes no separate summarization-model request.

The `read_conversation_history` tool can search/page saved records, including previous attempts, and read complete large payloads in bounded character slices using saved message or tool-call IDs. Provider switches, disabled tools, and incomplete native exchanges convert incompatible tool traffic to quoted historical text rather than silently dropping its outcomes. Compatible opaque continuation blocks are replayed only for the matching provider instance/model and complete exchanges.

Budgets are conservative character estimates, not exact tokenizer counts or a guarantee that every third-party endpoint accepts the projected request. Binary attachment token costs are approximate.

## Attachments

Files can be added individually through the picker or drag-and-drop and removed through their chips. Unsupported types show an error. Size is checked before reading the full file.

### Images

- Maximum raw size: 50 MB; maximum compressed size: 15 MB. Images are compressed to JPEG and Base64-encoded.
- Sent in the target provider's image format. The picker hides images for known text-only targets and 1min.AI.
- User bubbles show thumbnails up to 200 dp wide. Tapping opens a full-screen pinch/double-tap zoom viewer with pan and dismissal controls.

### Text files

- Maximum size: 200 KB per file. Common text, source, configuration, and data extensions are supported.
- Decoded content is prepended to the request with a filename header. The original attachment remains saved.
- Text attachments in restored remote history can also be read by the text-only local path.

### PDFs

- Maximum size: 20 MB; Base64-encoded without compression.
- Offered for Anthropic, Gemini, OpenAI, and OpenRouter. Sent as document/inline data, a file content part, or a Responses input-file item, as appropriate.
- Endpoint/model-specific PDF support still applies; a provider can reject an unsupported model or an over-budget document.

Binary attachments unsupported by a selected target are explicitly described as unavailable in its request, not silently treated as inspected. Text-only context accounting uses the decoded text or unavailable notice before budgeting, rather than charging an unsupported binary as though it would be sent. They stay in the saved conversation for a compatible target. The local picker remains hidden because local inference accepts text only. Long filenames are ellipsized while preserving extensions. Legacy single-file records are upgraded on load without dropping their bytes.

## Speech and Structured Output

- The top-bar speech toggle can play the successfully completed answer belonging to that request, once, including across screen remounts. A failed, stopped, or paused new request does not cause an earlier answer to play. Partial, interrupted, failed, and superseded output is not automatically spoken.
- Markdown is stripped for speech. The system-selected speech engine and its normal voice are preferred; Android falls back to Google's engine only if the system default cannot start.
- Interactive UI generation remains parked. Retained structured content is not executable/resubmittable while incomplete or superseded, or while another run is loading.

## Conversation Storage

- Native platforms use SQLite with conversation metadata, ordered message rows, and payload chunks. Saves update changed messages rather than serializing/replacing all prior messages.
- Large text and attachments are stored in cursor-safe chunks, including surrogate-safe Unicode boundaries. Old oversized rows are recovered with bounded database substring reads instead of being skipped. There is no storage-only text/attachment truncation.
- Browser storage uses immutable message blobs and snapshot envelopes behind an atomically replaced manifest. A failed preparation/manifest write leaves the previous committed snapshot readable and best-effort removes only that write's new blobs immediately, without waiting for reload. Committed or reused blobs are not removed on failure. Successful checkpoints reuse unchanged messages; obsolete-key cleanup avoids quadratic scans.
- Native SQLite and browser localStorage conversations are **not application-encrypted**; see [encryption.md](encryption.md). Browser quota, available disk space, and OS durability still apply. Concurrent app processes/browser tabs sharing a store are not a supported multi-writer mode.
- Legacy encrypted-file and settings-blob imports commit to the new store before removing their source. Missing keys, corrupt records, or failed writes surface errors rather than masquerading as empty history. A malformed conversation import or duplicate retained conversation IDs reject the entire conversation section before replacement, instead of skipping records or corrupting the old data.
- Settings exports remain self-contained and retain attempts, tool records, attachments, and continuation metadata. Import replacement is explicit and fences callbacks from the old storage generation.
- A separate sandbox console tail remains limited to about 10,000 characters; this convenience tail is not the complete tool transcript or a backup of workspace files. Sessions predating storage replacement cannot overwrite the new console snapshot. The next shell access replaces stale cached ownership so new commands on an imported conversation can save their output.
- SQL table changes include numbered migrations so existing databases upgrade in place.

## History and UI

- The history sheet lists saved chats newest-first, with title, date, active styling, and a Heartbeat badge where appropriate. Legacy interactive chats keep their distinct border styling.
- Deletion has an approximately four-second Undo window. Deleting the active chat clears its selection; stale callbacks cannot resurrect it.
- The chat initially renders a 200-record tail, with Load earlier messages for older records. Tool activity follows the displayed page; load earlier records to inspect older parts of a long run.
- Opening a conversation starts at the latest work. While following the bottom, the view stays at the actual end of a growing reply, including replies taller than the viewport, approval/loading/error rows, Markdown reflow, and viewport resizing.
- Scrolling upward pauses following immediately; generation continues without pulling the reader back down. Touch/trackpad/mouse-wheel input, scrollbar dragging, and accessibility scrolling retain user control. Following resumes only after scrolling back to the bottom or using the down-arrow button. Loading earlier history does not force a jump.
- The down-arrow is available while following is paused or any content remains below the viewport, including the lower part of a tall final message. Restoring the screen preserves both its reading position and whether following was paused.
- Run status, previous-attempt labels, approvals, Continue, pending SMS drafts, background execution errors, and ordinary request errors remain visible in the chat surface.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/Conversation.kt` | Durable messages, attachments, attempt metadata |
| `composeApp/src/commonMain/.../data/ConversationHistory.kt` | Storage conversion and interrupted-record settlement |
| `composeApp/src/commonMain/.../data/ConversationRun.kt` | Run ownership and commit-before-publication |
| `composeApp/src/commonMain/.../data/StreamedAssistant.kt` | Stable streamed identity, timed checkpoints, tail flush |
| `composeApp/src/commonMain/.../data/ConversationStorage.kt` | Published storage state, migration, deletion and generation fences |
| `composeApp/src/commonMain/.../data/ConversationPersistence.kt` | Incremental SQL and manifest-backed browser persistence |
| `composeApp/src/commonMain/sqldelight/com/maximiliano/maxit/db/conversation.sq` | Versioned schema and chunk queries |
| `composeApp/src/commonMain/sqldelight/com/maximiliano/maxit/db/1.sqm` | Existing-database payload migration |
| `composeApp/src/commonMain/.../data/ConversationContext.kt` | Non-destructive request projection and historical retrieval |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Foreground/background dispatch, history, attempts, approvals |
| `composeApp/src/commonMain/.../data/ToolApproval.kt` | Exact-action approval and uncertain-outcome matching |
| `composeApp/src/commonMain/.../network/ChatStreaming.kt` | Bounded SSE/JSON response decoding |
| `composeApp/src/commonMain/.../network/CodexResponses.kt` | Responses streaming, completed-item reconstruction and native-call validation |
| `composeApp/src/commonMain/.../data/FileClassification.kt` | Attachment categories and size validation |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Request lifecycle, restore, navigation, file acceptance |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Transcript paging, scroll integration, completion gates |
| `composeApp/src/commonMain/.../ui/chat/ChatScrollState.kt` | Layout-driven bottom following and user-scroll ownership |
| `composeApp/src/desktopTest/.../ui/chat/ChatScrollStateTest.kt` | Off-screen rendered streaming, user-scroll, resume, and restoration regressions |
| `composeApp/src/commonMain/.../ui/chat/composables/RunActivity.kt` | Status, approval, and saved tool activity cards |
| `composeApp/src/commonMain/.../ui/chat/composables/QuestionInput.kt` | Controlled composer and submission gestures |
| `composeApp/src/commonMain/.../ui/chat/ComposerSubmission.kt` | Hoisted pending submission and durable acknowledgement ownership |
| `composeApp/src/commonTest/.../ui/chat/ChatViewModelCompletionTest.kt` | Cancellation ownership and once-only successful-answer speech |
| `composeApp/src/desktopTest/.../ui/chat/ComposerSubmissionLifecycleTest.kt` | Hidden composer, delayed acceptance, owner-aware restoration, rejection, and later edits |
| `composeApp/src/commonMain/.../ui/chat/composables/ChatHistorySheet.kt` | Saved conversation browsing and deletion |
| `composeApp/src/androidMain/.../sandbox/LinuxSandboxManager.kt` | Generation-bound console-tail persistence |
| `androidApp/src/main/AndroidManifest.xml` | Plain-text share registration |
| `androidApp/src/main/kotlin/.../MainActivity.kt` | Share/notification entry points |
| `composeApp/src/commonTest/.../data/ConversationReliabilityTest.kt` | Durability, storage failure, stream timer, task recovery regressions |
| `composeApp/src/desktopTest/.../data/ConversationExecutionTest.kt` | Loopback execution, reasoning/example isolation, legacy/native precedence, stream headers, approval races, and interruption |
| `composeApp/src/commonTest/.../data/SettingsConversationStagingTest.kt` | Duplicate IDs, failed staging/quota recovery, and long-history checkpoints |
| `composeApp/src/commonTest/.../data/TextOnlyConversationContextTest.kt` | Target-specific attachment budgeting without changing saved files |
| `composeApp/src/commonTest/.../network/ChatStreamingTest.kt` | Initial BOM decoding, saved-text preservation, refusals, and continuation boundaries |
| `composeApp/src/commonTest/.../network/CodexResponsesTest.kt` | Gap-free reconstruction, delta-only items, terminal authority, and native-call validation |
| `composeApp/src/desktopTest/.../data/ConversationDatabaseRecoveryTest.kt` | Migration, rollback, and fresh-store recovery boundaries |
