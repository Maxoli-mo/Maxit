<!-- Modified for Maxit, 2026-10-20. -->

# Getting Started

## Build and run

Maxit maintains Android (FOSS), Linux desktop, and Web. Windows, macOS, and iOS are source-only targets. Build instructions and prerequisites are in the repository's [README](../README.md#build-from-source).

```bash
# Android debug APK
./gradlew :androidApp:assembleFossDebug

# Linux desktop
./gradlew :composeApp:run

# Web development server
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

Install the Android APK from `androidApp/build/outputs/apk/foss/debug/`. For Web, open the local URL printed by the development server. This fork does not advertise an app-store listing or hosted Web deployment.

## Adding a service

1. Open Settings and select **Add Service**.
2. Pick a provider and enter its API key, if required.
3. Select a model from the dropdown.
4. Reorder services to choose the primary provider and fallbacks.

Configure a provider before chatting; there is no built-in Free proxy or automatic Free fallback. For local inference on Android or Linux, add the **Local Model** service and download or import a supported model. See [On-Device Inference](features/on-device-inference.md).

See [Multi-Service](features/multi-service.md) for provider configuration and fallback behavior.
