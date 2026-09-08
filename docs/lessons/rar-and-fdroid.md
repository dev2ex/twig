# RAR and F-Droid

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

**(★ 2026-08-25, `:fs-archive-rar` + the libre/full flavors)** junrar uses the **UnRAR license**
(the POM says `<name>UnRar License</name>`), which carries a usage restriction: it may not be
used to develop a RAR-compatible archiver or to reconstruct the RAR compression algorithm. That
clause puts it outside DFSG §6 / OSD §6 / FSF freedom 0, and **F-Droid requires 100% FLOSS with
no non-free section, so it would certainly be rejected**. So `:app` has two flavors:
`full` (self-built / self-distributed, with RAR) and `libre` (F-Droid, without).
**They differ in RAR alone — do not put any other difference in there.**

- **The module split exists for licensing, not architecture**: `RarFileSystem` is just a subclass
  of `ArchiveFileSystem` in the same package (`com.twig.fs.archive`), and it lives in
  `:fs-archive-rar` purely so libre can avoid depending on it at all.
  ★ The split is only possible because `ArchiveFileSystem`'s members are **`protected`, not
  `internal`** — Kotlin's `internal` is module-visible, and changing a single one to internal
  would break this split immediately.
- **Always check the scheme via `Archives.RAR_SCHEME`, never `RarFileSystem.SCHEME`**: that class
  does not exist in libre, so referencing it means libre does not compile. The two places this
  was hit are `Treemap.kt` and `PaneViewModel.archiveTarget` (both deciding "must this be
  materialized locally first").
- **Registration lives in flavor source sets** (`app/src/{full,libre}/kotlin/…/RarSupport.kt`,
  each with its own `rarFileSystem()`, the libre one returning null), with
  `?.let { register(it) }` in `TwigApp.registerBaseFs`. In libre, `.rar` degrades to an ordinary
  file: it does not expand, but nothing crashes.
- **The verification criterion is "is junrar in the dependency graph", not "does it compile"**:
  ```
  ./gradlew :app:dependencies --configuration libreReleaseRuntimeClasspath | grep junrar
  ```
  must print **nothing**, while the full configuration should show
  `com.github.junrar:junrar:8.x`. A successful compile proves nothing — what libre lacks is a
  **runtime** dependency, and it never references those classes anyway.
- **The APK path changed**: `app/build/outputs/apk/full/release/app-full-release.apk`. Steps 1
  and 2 of the ship checklist are already updated; do not use the old path (the aapt check would
  read a nonexistent file, and the pipeline's exit code will not tell you).
- **Routes investigated and rejected** (do not research them again):
  - **Building RARLAB's official unrar ourselves** — pointless. F-Droid compiles everything from
    source anyway; the blocker is the **license**, not the binary form. Fedora's wording:
    "can not be shipped... **in source or binary form**".
  - **`unrar-free`** — not an independent implementation. The whole project is `unrar.c`
    (616 lines) + `opts.c` (449 lines), starting with `#include <archive.h>`: **it is a CLI shell
    over libarchive**. The old code with its own decoders (from the RAR2 era) was abandoned
    upstream long ago.
  - **Refactoring/porting the unrar source** — a derivative work, no escape.
    **junrar is the living proof**: a completely different author rewrote it from scratch in
    Java, and its `LICENSE` file is still the UnRAR license **verbatim**. Clause 2 of that
    license explicitly says modified versions may be distributed but **must keep that notice**.
  - **A clean-room implementation** — legally viable (libarchive and The Unarchiver both did it),
    but RARLAB's official TechNote **only describes the container structure**, saying in so many
    words "for the algorithms see the UnRAR source" — so the only official algorithm reference is
    that restricted source. For scale: libarchive's rar + rar5 + ppmd7 come to about
    **9758 lines of C**.
- **If libre should ever support RAR, use libarchive (BSD-2)**; there is a ready-made route:
  `me.zhanghai.android.libarchive:library` (Maven Central, Apache-2.0 bindings over a BSD-2
  core; the arm64 `.so` adds about 0.94 MB to the APK), with **Material Files** as precedent
  (in F-Droid's main repository and also GPL-3.0).
  **The cost: libarchive does not support encrypted RAR at all** — this is not a configuration
  issue; in `rar.c`, `has_encrypted_entries = 1` is unconditionally followed by "RAR encryption
  support unavailable", and `rar5.c` says "Encryption is not supported"; the
  `archive_read_add_passphrase` symbols in the `.so` are there for zip and 7z. If size matters,
  `rar5.c` could also be ported to Kotlin (BSD-2 explicitly allows it as long as the copyright
  notice is kept; zero size cost and memory safe).

**(★ 2026-09-08, the first fdroiddata submission)** The flavor split is necessary but **not
sufficient**: `fdroid build` runs a source scanner before it compiles anything, and that scanner
reads the **whole tree**, not the flavor's classpath. So a green
`libreReleaseRuntimeClasspath | grep junrar` does not get you past it — the submission failed with

```
ERROR: Found usual suspect 'libs.junrar: com.github.junrar:junrar' at fs-archive-rar/build.gradle.kts
ERROR: Could not build app com.twig.app: Can't build due to 1 error while scanning
```

- **The fix is `rm`, not `scanignore`.** Both are common in fdroiddata (610 files use
  `scanignore`), but they say different things: `scanignore` means "this hit is a false positive,
  trust me", and junrar is genuinely non-free — it is not a false positive, it is simply unused.
  `rm` deletes it, so the build F-Droid performs contains no non-free code at all, which is a
  claim a reviewer can check rather than accept:
  ```yaml
  rm:
    - fs-archive-rar
  prebuild: sed -i '/fs-archive-rar/d' build.gradle.kts ../settings.gradle.kts
  ```
  Two references have to go with the module — `include(":fs-archive-rar")` in
  `settings.gradle.kts` and `"fullImplementation"(project(":fs-archive-rar"))` in
  `app/build.gradle.kts`. Gradle fails on a `project(...)` reference to a module that is not
  included, even from a configuration this flavor never resolves. `rm` paths are relative to the
  repository root; `prebuild` runs inside `subdir`, hence the `../` on one of them.
- **Test the deletion locally before pushing it.** `rm -rf fs-archive-rar`, apply the same sed,
  run `:app:assembleLibreRelease`, then `git checkout --` everything back. A CI round trip on
  fdroiddata costs minutes and burns reviewer attention; this costs 46 seconds.
- **`Categories` must be alphabetically ordered** or `fdroid rewritemeta` fails — it is a
  formatting check, not a content one, and it prints the exact diff it wants.
