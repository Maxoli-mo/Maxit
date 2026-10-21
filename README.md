<!-- Modified for Maxit, 2026-10-20. -->

# Maxit

Maxit is a free and open-source AI assistant forked from [SimonSchubert/Kai](https://github.com/SimonSchubert/Kai). It uses Kotlin Multiplatform and Compose Multiplatform, with configurable model providers, persistent memory, tools, MCP connections, and local inference where supported.

**Maintained targets:** Android (FOSS), Linux desktop, and Web. Windows, macOS, and iOS sources are retained but are not maintained or released by this fork. Feature availability varies by platform; see the [documentation](docs/index.md).

## Build from source

Use JDK 21 and the included Gradle wrapper. Install the Android SDK and configure `ANDROID_HOME` or `sdk.dir` in `local.properties`; Android builds also require the SDK/NDK versions declared in the Gradle configuration. Initial builds download dependencies.

```bash
# Android debug APK: androidApp/build/outputs/apk/foss/debug/
./gradlew :androidApp:assembleFossDebug

# Linux desktop
./gradlew run

# Optimized desktop release (slower build)
./gradlew runRelease

# Web development server
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Web production bundle: composeApp/build/dist/wasmJs/productionExecutable/
./gradlew :composeApp:wasmJsBrowserDistribution

# Desktop unit tests and formatting checks
./gradlew desktopTest spotlessCheck
```

Use `./gradlew run` for everyday desktop development; it avoids release optimization. An optional Bash wrapper also provides `./run`, `./run test`, `./run check`, `./run web`, and `./run release`. Run `./run help` for usage; additional arguments after a subcommand are forwarded to Gradle.

Android screenshot golden images are not tracked: `screenshotTests/src/test/snapshots/images/` is ignored. On a fresh checkout, first run `./gradlew :screenshotTests:recordPaparazziDebug` and inspect the generated images. Then run `./gradlew :screenshotTests:verifyPaparazziDebug` to compare subsequent changes against that local baseline. Recording and immediately verifying only checks the newly recorded baseline; it does not establish regression coverage against an earlier release. Preserve reviewed images separately for repeatable comparisons. Re-record only for deliberate UI changes. Add `-PincludeLocalizedScreenshots` to either command to include the localized fixture matrix.

See [Getting Started](docs/getting-started.md) for provider setup and the [Linux release smoke test](docs/desktop-release-smoketest.md) before distributing desktop builds.

Local Linux packaging instructions are in [aur/README.md](aur/README.md) and [flatpak/README.md](flatpak/README.md). These recipes use local build artifacts; they do not imply a published AUR or Flathub release.

## License and attribution

The Maxit fork is licensed under [GNU GPL version 3 only](LICENSE.txt) (`GPL-3.0-only`). Upstream Kai was created by Simon Schubert and contributors; its Apache-2.0 license is retained in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt). Existing third-party copyright notices and license terms remain applicable.

Upstream acknowledgements include [Mistral](https://mistral.ai/).
