# Native binaries and corresponding source

The ABI directories contain PRoot, its loaders, and talloc. Publishing this
directory distributes these binaries, even without an APK release.

## Source downloads

- **PRoot (Termux fork):** commit `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f`.
  [Download source](https://github.com/termux/proot/archive/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f.tar.gz).
  Applies to `libproot.so`, `libproot-loader.so`, and `libproot-loader32.so`.
  Copyright (C) 2015 STMicroelectronics; additional contributor notices remain
  in the source. Upstream license: GPL-2.0-or-later. Maxit uses its GPLv3 option
  for distribution with LGPLv3 talloc.
- **talloc 2.4.3:**
  [Download source](https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz).
  Applies to `libtalloc.so`. Copyright (C) Andrew Tridgell 2004;
  Copyright (C) Stefan Metzmacher 2006; additional notices remain in the source.
  License: LGPL-3.0-or-later.

These archives are available without charge. Together with
[build-proot.sh](../../../../build-proot.sh) from this same Maxit checkout,
they provide the component sources, source changes, compiler flags, and build
commands. Use this checkout's script rather than a moving upstream `main` URL.

## Build

From the repository root, run `./build-proot.sh` with Android NDK r29, Python 3,
Git, curl, and make installed. Set `ANDROID_NDK_HOME` if the NDK is not found
automatically. The script fetches the pinned sources, applies its PRoot loader
change, and writes binaries for `arm64-v8a`, `armeabi-v7a`, and `x86_64` here.

PRoot runs as a separate process from Maxit and dynamically links talloc.
Keep the binaries, source pins, build script, and notices together when updating
or redistributing them. Anyone distributing these binaries remains responsible
for maintaining access to their corresponding source; Maxit does not direct
source requests to Kai's issue tracker.

See [THIRD_PARTY_LICENSES.md](../../../../THIRD_PARTY_LICENSES.md) and the
[bundled full license texts](../../../../composeApp/src/commonMain/composeResources/files/licenses/THIRD_PARTY_NOTICES.txt).
