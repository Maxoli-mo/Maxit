# Local Flatpak package

This recipe packages a local Linux x86_64 Maxit distributable. It does not fetch a published binary or imply a Flathub listing.

From the repository root, run:

```bash
./gradlew :composeApp:createDistributable
flatpak-builder --arch=x86_64 flatpak/build flatpak/com.maximiliano.maxit.yaml
```

Install `flatpak-builder` and the `org.freedesktop.Platform` and `org.freedesktop.Sdk` 24.08 runtimes first. The manifest reads `composeApp/build/compose/binaries/main/app/Maxit/` and the four license/notice files directly from this checkout: `LICENSE.txt`, `LICENSES/Apache-2.0.txt`, `NOTICE.md`, and `THIRD_PARTY_LICENSES.md`. They are installed under `/app/share/licenses/maxit/`; notices bundled in the application/runtime remain in the distributable.

Maxit is a GPL-3.0-only derivative of Apache-2.0 Kai; the upstream license and notices remain applicable. Run the [Linux smoke test](../docs/desktop-release-smoketest.md), including the sandboxed launch, before distributing a Flatpak.
