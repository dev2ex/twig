# Third-Party Notices

Twig as a whole is distributed under **GPL-3.0-only** (see [LICENSE](LICENSE)).
Additional permissions and notices are in
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md).

Twig builds in two flavors. **`libre`** (published on F-Droid) is 100% free
software. **`full`** additionally bundles `junrar` for read-only RAR support;
that library is **not** free software, and §2.2 applies to `full` only. Rows
below are otherwise common to both.

This file inventories every third-party component Twig uses, as of
**1.8.6 (versionCode 291)**. Keep it in sync when dependencies change.

---

## 1. Third-party source included in this repository (vendored)

All three are **unmodified upstream copies**, redistributed with this repository.

| Component | Version | License | Location |
|---|---|---|---|
| **libsmb2** | 2.3.0 | **LGPL-2.1-or-later** | `fs-smb/src/main/cpp/libsmb2/` |
| **Zstandard (zstd)** | 1.5.6 | **BSD-3-Clause** or GPL-2.0 (dual; Twig takes BSD-3-Clause) | `fs-zstd/src/main/cpp/zstd/` |
| **Termux terminal JNI** (`termux.c`) | v0.118.0 | **GPL-3.0-only** | `app/src/main/cpp/termux/` |

**libsmb2** — https://github.com/sahlberg/libsmb2
Only the `lib/` and `include/` directories are vendored (LGPL-2.1-or-later); the
upstream `examples/` directory (2-clause BSD) is not included. License texts ship
with the source: `fs-smb/src/main/cpp/libsmb2/COPYING` and
`LICENCE-LGPL-2.1.txt`. The build excludes `krb5-wrapper.c` (Kerberos) and
`aes_apple.c` (macOS-only) — see `fs-smb/src/main/cpp/CMakeLists.txt`.

**zstd** — https://github.com/facebook/zstd
Only the decompression side is vendored: `common/`, `decompress/` and the public
headers (no compressor, no CLI). License text:
`fs-zstd/src/main/cpp/zstd/LICENSE`.

**Termux terminal JNI** — https://github.com/termux/termux-app
A single file, `terminal-emulator/src/main/jni/termux.c` at tag `v0.118.0`,
matching the `terminal-emulator` artifact in §2.1. It is compiled here rather
than taken as the prebuilt `libtermux.so` inside that AAR, because every
published Termux build links for 4 KB pages and Android 15 refuses to map such a
library — the terminal would crash rather than degrade. Same source, same five
exported symbols, relinked with `-Wl,-z,max-page-size=16384`. The Java side
still comes from the AAR, so the two must be bumped together; see
`app/src/main/cpp/termux/README.md`.

---

## 2. Runtime dependencies shipped in the APK

### 2.1 Copyleft components — the reason Twig must be GPL-3.0

| Component | Version | License | Used for |
|---|---|---|---|
| **Termux `terminal-emulator`** | v0.118.0 | **GPL-3.0-only** | Terminal emulator core |
| **Termux `terminal-view`** | v0.118.0 | **GPL-3.0-only** | Terminal rendering view |
| **Jellyfin `media3-ffmpeg-decoder`** | 1.9.0+1 | **GPL-3.0** | FFmpeg audio decoding (AC3/EAC3/DTS/TrueHD) |

- **Termux** — https://github.com/termux/termux-app
  The repository as a whole is GPLv3-only. Parts of it derive from
  [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
  (Apache-2.0); the combined work is distributed under GPLv3.
  Twig contains one original file in the `com.termux.terminal` package
  (`app/src/main/kotlin/com/termux/terminal/TermBridge.kt`) that reaches a
  package-private input queue. It is Twig's own code and contains no Termux source.
- **Jellyfin AndroidX Media** — https://github.com/jellyfin/jellyfin-androidx-media
  Released under GPL-3.0 (see the `<licenses>` block in its POM). It bundles a
  prebuilt FFmpeg; FFmpeg upstream is LGPL-2.1-or-later.

> **These three are the direct reason Twig is GPL-3.0.** A fork that removes them
> could consider a more permissive license.

### 2.2 Non-free component — `full` flavor only

| Component | Version | License | Used for | Flavor |
|---|---|---|---|---|
| **junrar** | 8.1.0 | **UnRAR License** (non-free) | Read-only RAR4 + RAR5 extraction | `full` only |

The UnRAR license carries a use restriction — the code may not be used to
re-create the RAR compression algorithm. That is a field-of-endeavour
restriction, so the library fails DFSG §6, OSI OSD §6 and FSF freedom 0, and it
is incompatible with the GPL.

**The `libre` flavor does not contain it.** RAR support lives in a separate Gradle
module, `:fs-archive-rar`, which only the `full` flavor depends on; `libre` has no
UnRAR-licensed code on its runtime classpath and none in its APK. Verify with:

```bash
./gradlew :app:dependencies --configuration libreReleaseRuntimeClasspath | grep -i junrar   # must print nothing
```

For the `full` flavor, see [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md) §1 for
the additional permission and the limits of its scope, and §2 for the notice
clause 2 of the UnRAR license requires: **the RAR-handling code in this program
may not be used to develop a RAR (WinRAR) compatible archiver.**

Twig provides read-only RAR extraction and does not implement RAR compression.

> **Replacing it.** libarchive (BSD-2) reads RAR4 and RAR5 with an independently
> written implementation — Debian ships the libarchive-based `unrar-free` in
> *main* while RARLAB's own `unrar` sits in *non-free*. A ready-made Android
> binding exists (`me.zhanghai.android.libarchive:library`, Maven Central,
> Apache-2.0), used by Material Files on F-Droid. It would let `libre` have RAR
> too, at roughly +0.94 MB per ABI and with **no support for encrypted RAR**.

### 2.3 Weak copyleft with an exception

| Component | Version | License | Used for |
|---|---|---|---|
| `com.android.tools:desugar_jdk_libs` | 2.0.4 | GPL-2.0 **with Classpath Exception** | Java 8+ API desugaring (required by the Jellyfin decoder) |

The Classpath Exception permits linking without the copyleft propagating.

### 2.4 Permissively licensed components

| Component | Version | License | Used for |
|---|---|---|---|
| Kotlin stdlib | 1.9.24 | Apache-2.0 | — |
| `kotlinx-coroutines-android` | 1.8.1 | Apache-2.0 | Coroutines |
| AndroidX `core-ktx` | 1.13.1 | Apache-2.0 | — |
| AndroidX `appcompat` | 1.7.0 | Apache-2.0 | — |
| AndroidX `recyclerview` | 1.3.2 | Apache-2.0 | File list |
| AndroidX `viewpager2` | 1.1.0 | Apache-2.0 | — |
| AndroidX `constraintlayout` | 2.1.4 | Apache-2.0 | — |
| AndroidX `activity-ktx` | 1.9.1 | Apache-2.0 | — |
| AndroidX `fragment-ktx` | 1.8.2 | Apache-2.0 | Panes |
| AndroidX `lifecycle-*-ktx` | 2.8.4 | Apache-2.0 | ViewModel |
| AndroidX `media3-exoplayer` | 1.9.0 | Apache-2.0 | Player |
| Apache Commons Net | 3.10.0 | Apache-2.0 | FTP |
| Apache Commons Compress | 1.26.2 | Apache-2.0 | 7z |
| SSHJ | 0.38.0 | Apache-2.0 | SFTP / SSH |
| OkHttp | 4.12.0 | Apache-2.0 | HTTP layer for WebDAV and S3 |
| SLF4J API | 1.7.36 | MIT | Transitive dependency of junrar (`full` only) |
| Bouncy Castle (`bcprov-jdk18on`) | 1.75 / 1.78.1 | Bouncy Castle License (MIT-style) | X25519 key exchange, scrypt |
| XZ for Java (`org.tukaani:xz`) | 1.9 | Public Domain | LZMA/LZMA2 for 7z |
| Shizuku `api` / `provider` | 13.1.5 | MIT | Privileged access without root |

`org.json` is provided by the Android runtime (a `compileOnly` dependency) and is
not bundled in the APK.

### 2.5 Bundled assets

| Asset | Source | License |
|---|---|---|
| `assets/colors/base16-atelierseaside-*.properties` | [base16-xresources](https://github.com/chriskempson/base16-xresources) (Chris Kempson) | MIT |
| `assets/colors/solarized-*.properties` | [Solarized](https://github.com/altercation/solarized) (Ethan Schoonover) | MIT |

Terminal fonts are **not bundled**; users import their own, so no third-party
font license is involved.

---

## 3. Test and build only (not shipped)

| Component | Version | License |
|---|---|---|
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Robolectric | 4.14.1 | MIT |
| AndroidX Test `core-ktx` | 1.6.1 | Apache-2.0 |
| `kotlinx-coroutines-test` | 1.8.1 | Apache-2.0 |
| OkHttp MockWebServer | 4.12.0 | Apache-2.0 |
| Apache FtpServer | 1.2.0 | Apache-2.0 |
| Apache MINA SSHD (`sshd-sftp`) | 2.12.1 | Apache-2.0 |
| `io.airlift:aircompressor` | 0.25 | Apache-2.0 |
| `org.json:json` | 20240303 | Public Domain |
| Android Gradle Plugin / Gradle | 8.5.2 / wrapper | Apache-2.0 |

---

## 4. Checklist when changing dependencies

- [ ] Is the new dependency's license **GPL-3.0 compatible**? (Apache-2.0, MIT,
      BSD, MPL-2.0 and LGPL are; UnRAR, SSPL, proprietary and "non-commercial
      only" licenses are not.)
- [ ] If vendoring source, did you copy the upstream license file with it, and
      record the version and whether it was modified?
- [ ] If adding an LGPL component, is the relinking requirement satisfied?
      (Here it is, by shipping complete source.)
- [ ] Did you update this file and the dependency table in the README?
- [ ] If adding a GPL component, does it foreclose relicensing the project's own
      code later? (See [CLA.md](CLA.md).)

---

## 5. Obtaining third-party source

- libsmb2 and zstd: the complete source is in this repository.
- Everything else is resolved from Maven Central, Google Maven or JitPack.
  Coordinates and versions are in `gradle/libs.versions.toml` and each module's
  `build.gradle.kts`; source is available from those repositories or from each
  project's home page.
- Per GPL-3.0 section 6, the complete corresponding source for this program is
  available from the project repository. If you received a binary and cannot
  reach the repository, open an issue to contact the maintainer.
