# Local Arch package

`PKGBUILD` packages a locally built Linux x86_64 distributable as `maxit-bin`. It does not download an upstream Kai release and is not a published AUR package.

From the repository root, build and stage the archive. Set `version` to the `pkgver` in `aur/PKGBUILD` and ensure it matches the application build version. Use an empty `aur/build/` staging directory for each release.

```bash
./gradlew :composeApp:createDistributable
version=3.3.0
mkdir -p aur/build/Maxit/LICENSES
cp -a composeApp/build/compose/binaries/main/app/Maxit/. aur/build/Maxit/
install -m644 LICENSE.txt NOTICE.md THIRD_PARTY_LICENSES.md aur/build/Maxit/
install -m644 LICENSES/Apache-2.0.txt aur/build/Maxit/LICENSES/
tar -C aur/build -czf "aur/Maxit-${version}-linux-x86_64.tar.gz" Maxit
```

The archive must contain `Maxit/bin/Maxit`, its application/runtime files, and these notices:

- `Maxit/LICENSE.txt` (GPL-3.0-only fork license)
- `Maxit/LICENSES/Apache-2.0.txt` (retained upstream license)
- `Maxit/NOTICE.md`
- `Maxit/THIRD_PARTY_LICENSES.md`

Keep any notices already included in the distributable. The package also installs the four files above under `/usr/share/licenses/maxit-bin/`.

From `aur/`, inspect metadata without building or installing:

```bash
makepkg --printsrcinfo
```

When changing `PKGBUILD`, regenerate `.SRCINFO` with the modification notice:

```bash
{ printf '# Modified for Maxit, 2026-10-20.\n'; makepkg --printsrcinfo; } > .SRCINFO
```

To build the local package, run `makepkg` from `aur/` after staging. `SKIP` is intentional for this locally generated archive; it provides no integrity check. Before distributing a fixed artifact, replace it with that archive's SHA-256 checksum and regenerate `.SRCINFO`. Complete the [Linux smoke test](../docs/desktop-release-smoketest.md) before distributing packages.
