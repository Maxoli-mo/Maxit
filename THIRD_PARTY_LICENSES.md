# Third-Party Licenses

<!-- Modified for Maxit, 2026-10-20. -->

## Maxit and upstream Kai

Maxit is derived from [SimonSchubert/Kai](https://github.com/SimonSchubert/Kai).
The fork as a whole is licensed under **GPL-3.0-only**; the full GPLv3 text is in
[LICENSE.txt](LICENSE.txt). Upstream Kai code retains its Apache-2.0 terms;
the original upstream license is preserved verbatim in
[LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
See [NOTICE.md](NOTICE.md) for attribution and the dated fork modification notice.

The Apache Software Foundation's [GPL compatibility guidance](https://www.apache.org/licenses/GPL-compatibility.html)
confirms that Apache-2.0 software can be included in GPLv3 projects. Inclusion
does not erase upstream license terms or attribution requirements.

## Bundled binaries

This project includes the following third-party binaries in `androidApp/src/main/jniLibs/`.
The app shows these notices, the full license texts and source download directions under
Settings › Licenses (`composeApp/src/commonMain/composeResources/files/licenses/THIRD_PARTY_NOTICES.txt`).
The corresponding source consists of the pinned source archives below together with
[build-proot.sh](build-proot.sh), including its build flags and source changes.
See [native source instructions](androidApp/src/main/jniLibs/README.md).
Keep these pins and the bundled app notices in sync with the binaries and build script.

## PRoot (Termux fork)

- **Files:** `libproot.so`, `libproot-loader.so`, `libproot-loader32.so`
- **Source:** https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f (built by `build-proot.sh`)
- **Source download:** https://github.com/termux/proot/archive/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f.tar.gz
- **License:** GPL-2.0-or-later; Maxit uses the GPLv3 option for distribution with LGPLv3 talloc.
- **Copyright:** Copyright (C) 2015 STMicroelectronics; PRoot and Termux contributors retain their notices in the source.

PRoot is a user-space implementation of chroot, mount --bind, and binfmt_misc. It is used to run an Alpine Linux environment inside the Android app without requiring root access. PRoot is executed as a separate process and is not linked into the application code.

The GPLv2 and GPLv3 texts are included in the bundled app notices. The GPLv3 text
is also in [LICENSE.txt](LICENSE.txt).

## talloc

- **Files:** `libtalloc.so`
- **Source:** https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz (version 2.4.3, built by `build-proot.sh`)
- **License:** LGPL-3.0-or-later
- **Copyright:** Copyright (C) Andrew Tridgell 2004; Copyright (C) Stefan Metzmacher 2006; contributors retain their notices in the source.

talloc is a hierarchical memory allocator used as a dependency of PRoot. It is dynamically linked.

The full LGPLv3 text and the GPLv3 text it incorporates are included in the bundled
app notices. LGPLv3 is also available at https://www.gnu.org/licenses/lgpl-3.0.html.
