<!-- Modified for Maxit, 2026-10-20. -->

# On-Device Inference (LiteRT)

**Last reviewed:** 2026-10-21

Maxit can run AI models directly on the user's device using Google's LiteRT LM SDK. Once a model is downloaded, inference requires no API key or network connection. The maintained targets for local inference are **Android** and **Linux desktop**. The iOS, macOS, and Windows implementations described below are retained source only.

## How It Works

Models are downloaded from HuggingFace's litert-community and stored locally on the device. When the user sends a message, the model runs entirely on-device using GPU acceleration (with CPU fallback where the platform supports it). The engine initializes on first use (~10 seconds) and stays loaded for 5 minutes of inactivity before automatically releasing memory.

### Download integrity

Every catalog model is pinned to an **immutable HuggingFace revision** rather than a branch, so the bytes served for a given app version never change. Each catalog entry also records the model file's expected SHA-256 and its exact size.

The digest is computed while the download streams to disk, so verification costs no extra read of a file that can be several gigabytes. A download is accepted only when both the length and the digest match; otherwise the partial file is discarded and the failure is reported inline in Settings. The verified digest is recorded in a marker file stored next to the model, written before the model becomes visible under its final name — a model is never present on disk without its marker.

Verification also runs at load time. Before a model is handed to the inference engine, its marker is compared against the pinned digest; when the marker is missing — which is the case for models downloaded by app versions that predate this check — the file is hashed once and the result recorded, making every later load a string comparison. A model whose bytes do not match is refused rather than loaded, and the user is told to delete it and download it again. Nothing is deleted automatically at load time: a rejected file stays on disk for the user to keep, replace, or remove from Settings. Because the observed digest is recorded either way, a file already known not to match is refused immediately instead of being re-read on every attempt.

**Imported models are exempt from verification.** An import whose file name matches a catalog model takes over that catalog slot, so it would otherwise be measured against a digest it was never meant to match — a user's own copy of a model, or one from a different upstream revision, would be rejected. Such an import records that the file is user-supplied, and the load-time check skips it. Imports that land under their own name carry no digest at all and are likewise never checked. Only files Maxit itself downloaded are held to the pinned digest.

Bumping a catalog model means updating its pinned revision, digest, and size together in `LocalModelCatalog.kt`. Verify all three against the same immutable HuggingFace revision, and check the model's license and runtime requirements before updating. `LocalModelCatalogTest.kt` checks that every download URL uses an immutable commit. A newer `main` on HuggingFace does not automatically change the bundled pins.

## Available Models

| Model | Size | GPU Memory (Android) | Default Context | Max Context | Tool calling |
|-------|------|---------------------|-----------------|-------------|--------------|
| Gemma 4 E2B IT | 2.59 GB | 676 MB | 4K tokens | 32K tokens | ✅ reliable |
| Gemma 4 E4B IT | 3.66 GB | 710 MB | 4K tokens | 32K tokens | ✅ reliable |
| Gemma 4 12B IT | 6.88 GB | 4000 MB | 8K tokens | 32K tokens | ✅ reliable |
| Qwen3 0.6B | 614 MB (~586 MiB) | 300 MB | 4K tokens | 32K tokens | ⚠️ chat-only in practice |

Models are `.litertlm` files from the [litert-community](https://huggingface.co/litert-community) organization on HuggingFace. Sizes are the exact byte counts recorded in the catalog, which are checked against the downloaded file.

**Gemma 4 12B** is pinned to the revision published in September 2026, which adds vision and audio modalities and Multi-Token Prediction for speculative decoding. That build requires LiteRT-LM 0.17 or newer, which Android and Desktop ship; the iOS bridge is older, so this entry cannot load there — academic at 6.9 GB, which no phone was going to hold.

**Removal note — 2026-10-20:** LFM2.5 1.2B Instruct was removed from the catalog because of its restricted license. Gemma 4 and Qwen3 pins are retained.

## Tool support

The application uses **litert-lm's native function calling**: each exposed Maxit tool is wrapped in an adapter, registered on the conversation, and the engine drives the tool loop internally. The model uses its trained tool format, and the engine returns the final assistant text after all tool round-trips complete. Automatic tool calling is switched on only when the conversation actually has tools. Tools are available **at any context size** — there's no threshold gating.

Two filters decide what the model actually receives.

**The model file decides whether it gets tools at all.** Before building the tool list, Maxit asks the `.litertlm` bundle what it declares about itself — a metadata read that needs no engine and no loaded weights. A bundle that declares no function-calling support carries no tool section in its chat template, and such a model does not politely ignore tools it was handed: it answers *instead* of calling them, inventing whatever the tool would have returned. Those models are given no tools and no tool guidance in the system prompt, which makes them plain chat models — which is what they are. A bundle that cannot answer (older metadata, or the iOS bridge, whose LiteRT-LM release predates the capability API) reports *unknown*, and unknown is treated as before: the allowlist is offered.

**The allowlist then narrows what a tool-capable model sees**, because small Gemma models (2-4B params) struggle to emit valid function-call syntax for tools with many parameters or complex value types, and litert-lm's strict ANTLR parser crashes the call when the syntax is malformed.

The on-device tool allowlist currently exposes: `get_local_time`, `get_location_from_ip`, `web_search`, `open_url`, `memory_store`, `memory_forget`, `memory_reinforce`, and `execute_shell_command` (when the user has enabled the shell tool in Settings). Email tools, task scheduling (`schedule_task` / `list_tasks` / `cancel_task`), MCP server tools, and structured `memory_learn` are excluded; those tools require a remote model. Heartbeat configuration is user-controlled in settings, and `promote_learning` has been removed for all models.

**Qwen3 0.6B caveat:** at 0.6 B params it rarely emits valid function-call syntax — it tends to hallucinate answers (e.g. a fictional time) instead of invoking `get_local_time`. Treat Qwen3 as a chat-only model in practice; use Gemma 4 E2B/E4B for tools when the device can run them, or configure a remote provider. Whether the capability gate above withholds tools from Qwen3 automatically depends on what its bundle declares; the caveat stands either way.

The `CHAT_LOCAL` system prompt includes Maxit identity, agent instructions, honesty and authorization rules, Tool Use when tools are available, basic memory guidance and budgeted memory categories when memory is enabled, the active skill when selected, and runtime Context. It shares the [bundled agent instructions](../../composeApp/src/commonMain/composeResources/files/prompts/agent-instructions.md) and `loadAgentInstructions` loader with remote chat; nonempty saved overrides take precedence. Memory-category sections share 2,000 characters, including headings and formatting. Structured Learning, Automation, Email Accounts, Scheduled Tasks, and Heartbeat Additions are omitted.

Dynamic/Interactive UI generation is **parked for all models**. Its instructions are archived outside runtime resources for future skills; entry points are hidden, and existing interactive conversations reopen as normal chat. The renderer and stored messages remain.

See [system-prompts.md](system-prompts.md) for the full contract.

## Sampling

Each conversation is configured with the sampling defaults the model's own bundle declares — top-k, top-p and temperature read from the same capability metadata as the tool declaration. Models converted from different upstream families want different values, so one hardcoded triple suits none of them exactly. A bundle that declares nothing reports zeroes, which mean *no opinion* rather than *decode greedily*; those fall back to Maxit's previous fixed values (top-k 40, top-p 0.95, temperature 0.8).

## Tool-call failures

If the engine throws (e.g. the model does emit malformed tool-call syntax that the ANTLR parser rejects), the application catches the failure, logs it, and retries the call **once** with no tools — the user gets a plain-chat answer instead of a hard error.

## Other limitations

- **No image input** -- the on-device engine only accepts text messages. Some catalog bundles (Gemma 4 12B since its September 2026 revision) declare vision and audio modalities, and the capability read surfaces that, but nothing consumes it yet
- **No dynamic UI** -- generation is parked for all models
- **Not available on web** -- the WASM build returns no local engine. iOS uses a platform-specific LiteRT bridge rather than the Android/JVM AAR.
- **Requires a 64-bit device (Android)** -- the LiteRT-LM AAR only ships `arm64-v8a` and `x86_64` native libraries. On pure 32-bit devices (armeabi-v7a), the LiteRT service card is hidden; the app still works with remote services.
- **Requires AVX2 on x86_64 Linux** -- the LiteRT native binary is compiled with AVX2+ instructions, so older CPUs (e.g. Intel Ivy Bridge / 3rd-gen Core) would SIGILL on model load. On Linux desktop, the engine probes `/proc/cpuinfo` for the `avx2` flag and hides the LiteRT service card when missing. Remote services are unaffected.

## Model Management

Users manage models through the LiteRT service card in Settings:

- **Download** -- each model card shows a download button with size info; disk space is validated before starting
- **Import** -- a file picker accepts any `.litertlm` already on the device (e.g. from Downloads, Files, or another app that can export the file). The file is **copied** into Maxit's private model storage (streaming — never loaded fully into memory). If the file name matches a catalog model, it fills that catalog slot and is marked as user-supplied so it is exempt from the integrity check; otherwise it appears under an **Imported** section with synthetic context/performance defaults
- **Select** -- radio button appears after download/import to set the active model; a successful import auto-selects the new model
- **Delete** -- trash icon removes the downloaded or imported model file
- **Cancel** -- active downloads and imports can be cancelled
- **Error display** -- download and import failures (network, disk space, incomplete, failed integrity check, invalid extension) are shown inline in the settings UI. A model refused at load time reports a separate integrity error in the chat area, pointing the user at Delete and re-download
- **Context size slider** -- each model has a slider to adjust context size (starting at the model's default up to its maximum, in 1K steps); available before download so users can preview performance impact. Gemma 4 12B defaults to 8K (higher than the 4K default used for the smaller E2B/E4B models). Imported custom models default to 4K (max 32K). A model whose maximum equals its default shows a size label without a slider
- **Performance indicator** -- each model shows a Good/OK/Poor label based on total device RAM vs estimated resident memory at the selected context size. The estimate sums the model file size (proxy for resident weights after mmap/PLE), a per-model baseline for GPU/KV working memory, and a per-token KV cache cost that scales with context. Thresholds: Good >= 2.5x, OK >= 1.85x, Poor < 1.85x of total device RAM -- the extra headroom over 1x accounts for OS reservation and GPU-driver overhead. Custom imports use a size-based heuristic for the GPU baseline
- **Free space** -- available device storage is shown below the model list

**Legacy LFM2.5 files:** existing downloads and filename-matched imports from older installations are moved, with their marker files, into a dedicated subdirectory under imported-model storage when models are discovered. The saved model selection, context settings, and 4K-token limit are preserved. Existing import files are never overwritten. If the destination is occupied or moving fails, the original remains available for selection and deletion; discovery can retry later. This compatibility path does not restore a download offer for the removed model.

On Android, downloads run in a foreground service with a notification so they continue when the app is backgrounded. The service is only started once the HTTP connection is established, so a pre-connection failure (e.g. offline) surfaces as an inline error without leaving a promised-but-unfulfilled foreground service. On Desktop, downloads run in a background coroutine. Import always copies into app storage (same pattern as Gallery / PocketPal); Maxit cannot silently scan other apps' private model directories.

When the last LiteRT service instance is removed, all downloaded and imported models are automatically deleted.

## Engine Lifecycle

1. **Lazy initialization** -- the engine loads only when the first message is sent
2. **GPU-first** -- attempts GPU backend, falls back to CPU if unavailable (where the platform supports both)
3. **Memory check (Android/JVM path)** -- refuses to load when available memory is below a fixed **512 MB** headroom floor (not model-size + headroom). Desktop skips the check and reports effectively unlimited available memory so the OS swap/cache path is trusted
4. **Persistent across messages** -- stays loaded for the duration of the conversation
5. **Inference timeout** -- individual inference calls are capped at 2 minutes
6. **Auto-release** -- released after 5 minutes of inactivity to free memory (always re-armed, even on errors)
7. **Status indicator** -- the chat shows "Initializing {model name}" with a pulsing dot during engine load
8. **Stop during load** -- the native model load cannot be interrupted; pressing stop cancels the message, and the load finishes in the background without marking the engine as failed. A follow-up message waits for the in-flight load instead of starting a second one

## Platform Differences

| Aspect | Android | Desktop | iOS |
|--------|---------|---------|-----|
| Model storage | `context.filesDir/litert_models` (catalog under `{id}/` with its digest marker alongside, imports under `imports/`) | `~/.kai/litert_models` (same layout) | App sandbox path via the iOS LiteRT bridge (same layout) |
| Memory check | `ActivityManager.getMemoryInfo()` vs 512 MB floor | Skipped — desktop OSes manage memory via swap and cache eviction | Platform bridge |
| Disk space | `StatFs.availableBytes` | `File.usableSpace` | Platform bridge |
| Download notification | Foreground service with notification | No notification (no OS restriction) | Platform bridge |
| Model import | FileKit file picker → stream-copy into app storage | Same | Same |
| Runtime version | litert-lm 0.18.0 (Google Maven) | Same | LiteRT-LM 0.16.1 (Swift package) — behind Android/Desktop; newer Swift package tags exist but iOS has not been moved yet |
| Capability probe | Reads the bundle's declared tool support, modalities and sampler defaults | Same | Unavailable — the Swift capability API at 0.16.1 exposes only speculative-decoding support, so the probe reports *unknown* and callers keep their defaults |

## Fallback Behavior

- Configure a Local Model instance to use on-device inference; no built-in Free proxy or fallback is available
- In chat, a local model is tried only when primary. Its failure is shown directly rather than silently switching to a cloud provider; cloud failures do not start a local model as a fallback target
- On unsupported platforms (web), LiteRT instances are silently skipped
- Background tool runs (heartbeat and scheduled tasks) use an explicitly chosen instance when there is one, otherwise prefer remote services and fall back to on-device only when no remote is configured. The on-device fallback works at any context size, since the simple-tool allowlist has no schema-overhead penalty.

## Key Files

| File | Purpose |
|------|---------|
| `composeApp/src/commonMain/.../data/Service.kt` | `Service.LiteRT` definition with `isOnDevice = true` |
| `composeApp/src/commonMain/.../inference/LocalInferenceEngine.kt` | Platform-agnostic interface for on-device inference, including the declared-capability probe and its sampler-defaults helper |
| `composeApp/src/commonMain/.../inference/LocalModelCatalog.kt` | Bundled model list (pinned revisions, expected digests, sizes, GPU baselines, context defaults) and digest-comparison helpers |
| `composeApp/src/commonTest/.../inference/LocalModelCatalogTest.kt` | Checks immutable download URLs, digest format, sizes, and catalog invariants |
| `composeApp/src/commonMain/.../inference/LocalModelImport.kt` | Import path helpers, custom model id/filename sanitization, synthetic metadata |
| `composeApp/src/commonMain/.../inference/LocalModelMigration.kt` | Retired-model compatibility metadata and id-preserving migration/discovery |
| `composeApp/src/jvmShared/.../inference/LocalModelMigration.jvmShared.kt` | Non-overwriting filesystem move for Android and desktop |
| `composeApp/src/desktopTest/.../inference/LocalModelMigrationTest.kt` | File/marker preservation, collision, failure, and repeat-discovery tests |
| `composeApp/src/commonMain/.../inference/InferencePlatform.kt` | `expect` declarations for platform-specific operations |
| `composeApp/src/commonMain/.../inference/LocalInferenceEngineProvider.kt` | `expect` factory, returns `null` on unsupported platforms |
| `composeApp/src/jvmShared/.../inference/LiteRTInferenceEngine.kt` | Shared Android+Desktop implementation wrapping LiteRT LM SDK |
| `composeApp/src/androidMain/.../inference/InferencePlatform.android.kt` | Android platform implementations (storage, memory, notifications) |
| `composeApp/src/desktopMain/.../inference/InferencePlatform.jvm.kt` | Desktop platform implementations (storage, memory) |
| `composeApp/src/iosMain/.../inference/IosLiteRTInferenceEngine.kt` | iOS LiteRT engine implementation |
| `composeApp/src/iosMain/.../inference/LiteRTSwiftBridge.kt` | Kotlin side of the bridge to the LiteRT-LM Swift package |
| `composeApp/src/iosMain/.../inference/InferencePlatform.ios.kt` | iOS platform implementations (storage, memory, disk space) |
| `composeApp/src/iosMain/.../inference/LocalInferenceEngineProvider.ios.kt` | iOS factory wiring |
| `composeApp/src/androidMain/.../inference/ModelDownloadService.kt` | Android foreground service for background downloads |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Inference dispatch, engine initialization status, local tool allowlist, and the declared-capability gate that withholds tools from models with no tool template |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | Local chat sections and shared 2,000-character memory-category budget |
| `composeApp/src/commonMain/.../network/NetworkExceptions.kt` | Maps inference failures, including a failed integrity check, to user-facing errors |
| `composeApp/src/commonMain/.../ui/settings/ServicesSettings.kt` | LiteRT service card: model list, download/import controls, progress and error display |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Hosts service settings including LiteRT model management |
