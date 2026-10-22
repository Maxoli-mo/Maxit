<!-- Modified for Maxit, 2026-10-20. -->

# Desktop Release Smoke Test

**Upstream checklist last verified:** 2026-10-02. Re-run on Maxit before distributing a Linux release.

Before tagging a new version, walk through this checklist on a locally-built **release** desktop binary (not `./gradlew :composeApp:run` — that uses the unminified classpath and hides ProGuard bugs).

## Why this exists

Desktop release builds run through ProGuard. The desktop policy shrinks Compose packages and keeps the rest of the classpath intact because libraries use reflection, `ServiceLoader`, JNI symbol lookup, or jar-manifest discovery that ProGuard cannot trace statically. A build can still package successfully and crash on a particular code path. CI runs desktop unit tests but does not publish releases or exercise the packaged release binary; run this checklist locally on Linux.

## How to run

```bash
./gradlew :composeApp:runRelease
```

Use JDK 21. Linux is the maintained desktop target; macOS and Windows sources are retained without release support.

## Checklist

Each step exercises a library that has historically broken under ProGuard. A crash here means the release is not shippable.

- [ ] **App launches to main screen** — exercises Compose runtime, Skiko native load, and the Koin DI graph
- [ ] **First outbound network call succeeds** (any provider ping, any chat message) — exercises Ktor CIO engine discovery and the kotlinx.serialization JSON plugin (both `ServiceLoader`-registered)
- [ ] **Send a chat message and receive a response** — exercises the full Ktor HTTP client + content-negotiation + streaming path
- [ ] **Toggle text-to-speech on a message** — exercises `nl.marc_apps.tts` and FreeTTS voice directory discovery (jar manifest + `Class.forName`)
- [ ] **Change a setting and restart the app** — exercises multiplatform-settings persistence via the encrypted file backend
- [ ] **After the restart, reopen an earlier conversation** — exercises SQLite-backed conversation storage, which needs the `java.sql` module in the bundled runtime
- [ ] **Load a downloaded on-device model and send a message** — exercises LiteRT's JVM bindings and native library loading
- [ ] **Open the file picker, pick a file, save a file** — exercises FileKit and its JNA-backed native dialogs
- [ ] **Render a markdown response with code blocks** — exercises the multiplatform markdown renderer and code highlighter
- [ ] **Open Settings → export/import** — exercises the JSON serialization path end-to-end

## When a new crash surfaces

The crash signature dictates the fix:

- **`ServiceLoader: Provider X not found`** — add `-keep class * implements <ServiceInterface> { <init>(); }` to `composeApp/proguard-rules.pro`. Prefer this pattern over hard-coding the implementation class name so the rule self-adapts if the library renames its classes.
- **`ClassNotFoundException` / `NoClassDefFoundError` on a library-internal class** — usually jar-manifest or `Class.forName` reflection. Keep the whole package with `-keep class <pkg>.** { *; }`.
- **`SHA-256 digest error for X.class`** — the jar is cryptographically signed and must not be modified. Keep the entire library untouched: `-keep class <pkg>.** { *; }` plus `-dontwarn <pkg>.**`.
- **`NoSuchMethodException` / `NoSuchFieldException` with an obfuscation-looking name** — should not happen because `obfuscate` is off by default in Compose Desktop; if it does, check that `buildTypes.release.proguard` in `composeApp/build.gradle.kts` has not been changed to enable obfuscation.
- **`VerifyError` ("Bad return type") in coroutine builders** — ProGuard method specialization; the desktop rules disable it globally, so check that line has not been removed.

Desktop-only rules go in `composeApp/proguard-desktop.pro`; rules that Android R8 also needs go in `composeApp/proguard-rules.pro` (both are applied to desktop).

Always add rules with a comment explaining which library needs it and why, so future readers (and future-you) can tell which rules are still load-bearing.

## Known non-shrinkable libraries

On desktop, everything outside the Compose packages is kept wholesale by `composeApp/proguard-desktop.pro`. The libraries below are additionally kept in the shared rules (which Android R8 uses too) because they cannot be safely minified anywhere. Do not try to tighten these rules without a corresponding runtime test.

| Library | Reason | Rule location |
|---|---|---|
| FreeTTS (`com.sun.speech.freetts.**`) | Voice directories loaded via jar manifest + `Class.forName`; cannot be traced | `composeApp/proguard-rules.pro` |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/proguard-rules.pro` | Shared ProGuard/R8 rules for Android and Desktop |
| `composeApp/proguard-desktop.pro` | Desktop-only policy: shrink Compose, keep the rest of the classpath, no method specialization |
| `composeApp/build.gradle.kts` | `compose.desktop { application { buildTypes.release.proguard { ... } } }` and jlink modules |
| `.github/workflows/test.yml` | Read-only Linux CI: formatting checks, desktop tests, Android FOSS and Web builds |
