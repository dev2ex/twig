# Twig 🌿

**English** · [简体中文](README.zh-CN.md)

A size-first, dual-pane file manager for Android — in the spirit of X-plore.
Native Kotlin and XML views, no Material library, minimal dependencies. When
something can reasonably be written by hand, it is: WebDAV, S3 signing, git and
restic are all implemented from scratch.

**Version 1.1.1** (versionCode 270) · minSdk 24 / targetSdk 34 / compileSdk 36 ·
[GPL-3.0](LICENSE)

<!-- TODO: screenshots / GIFs go here. Four that make the case:
     1. cross-source copy: an SMB folder compressed straight into a local zip
     2. in-place tree expansion, with an archive opening like a directory
     3. the treemap disk-usage view, pinch to zoom
     4. Wi-Fi sharing mounted as a network drive from a desktop file manager -->

---

## Why Twig

**One tree, every source.** Local storage, archives, FTP, SFTP, SMB, WebDAV, S3
and restic repositories are all the same thing to the UI. Copying between any two
of them is the same code path — an SMB folder can be compressed straight into a
local zip, a file inside a remote archive can be streamed to an FTP server.

**It stays small.** A per-device download is about 6.7 MB *including* an FFmpeg
audio decoder, an SMB implementation and a video player. Comparable file managers
ship several times that. Dependencies are added only after their APK cost is
measured.

**It goes where other file managers stop.**

- Browse **restic backup repositories** read-only, decrypted on device, snapshots
  presented as dated folders — as far as we know, nothing else on Android does this.
- **Thumbnails for network video without downloading the file.** Twig parses the
  container itself (MP4 sample tables, Matroska EBML cues), locates the keyframe
  at 1/10 of the duration, and fetches roughly 2 MB. A 74 GB remote remux gets a
  thumbnail in seconds.
- A **git client written from scratch in Kotlin** — status, history, side-by-side
  patience diff, worktrees and submodules, over local *and* remote repositories.
- **Real Blu-ray M2TS playback**: 192-byte BDAV packets, HDMV private stream
  types (DTS-HD MA, TrueHD via its embedded AC-3 core) and PGS bitmap subtitles,
  none of which media3 handles out of the box.
- **Privileged access via root or Shizuku** as a *fallback for the local
  filesystem*, not a separate tree — so `/data/data/…` copies to SMB, thumbnails
  and search all work with no extra code.
- **Wi-Fi sharing**: Twig becomes an HTTP and WebDAV server, so a desktop can
  mount it — and download files that live on an SMB share or inside an archive,
  because to the server they are all just `openInput()`.

---

## Size

Measured on the 1.1.1 release build (R8 + resource shrinking):

| | Size |
|---|---|
| Release APK (`arm64-v8a` + `x86_64`) | 9.0 MB |
| **Per-device download (`arm64-v8a`)** | **≈ 6.7 MB** |

Where it goes:

| Component | Size |
|---|---|
| `classes.dex` | 2.6 MB |
| Bouncy Castle | 1.3 MB |
| `libffmpegJNI.so` | 1.4 MB |
| `libsamba_jni.so` (libsmb2) | 464 KB |
| Resources (`resources.arsc` + `res`) | 793 KB |
| `libtwigzstd` / `libtermux` / `libtwigpty` | 108 KB |

Because every source is a separate Gradle module, a build can drop what it does
not need — omitting media playback and the network sources removes the large
majority of the above.

---

## Architecture in one sentence

Every source — local, archive, FTP, SFTP, SMB, WebDAV, S3, restic — implements
the same `FileSystem` + `XFile` interface, and `CopyEngine` moves bytes between
any two of them through `openInput()` / `openOutput()`.

> **Adding a source = adding one `FileSystem` implementation. The UI and the copy
> engine do not change.**

Two things fall out of this for free: any source can copy to any other source,
and the app can be built in trimmed configurations by dropping modules.

---

## Features

### Storage sources

| Source | Capability | Implementation |
|---|---|---|
| **Local** | Full read/write | `java.io.File` |
| **ZIP** | **Read + write**, legacy GBK names detected | Mounted as a filesystem; opens like a folder |
| **7z** | Read + **create** (LZMA2) | commons-compress + xz |
| **RAR** | Read-only (RAR4) | junrar |
| **Encrypted archives** | Read zip/7z/rar, **create** AES-256 zip/7z | WinZip AES and legacy ZipCrypto written by hand |
| **FTP** | Full read/write | Apache Commons Net |
| **SFTP** | Full read/write | SSHJ (with full Bouncy Castle for X25519) |
| **SMB / CIFS** | Full read/write | libsmb2 via NDK/JNI |
| **WebDAV** | Full read/write | Hand-written PROPFIND/MKCOL/MOVE, no SDK |
| **S3-compatible** | Full read/write | Hand-written SigV4 + REST — AWS S3, MinIO, R2, OSS, COS, B2 |
| **restic** | Read-only, decrypted | Written from scratch; repo format v1 and v2 |
| **SAF document tree** | Read/write | System-level fallback without `MANAGE_EXTERNAL_STORAGE` |
| **Privileged (root / Shizuku)** | Read/write | Not a separate source — a fallback for local paths ordinary APIs cannot reach |

Network sources connect on expand, support multiple servers (each gets a unique
scheme), and persist their configuration. Extraction is just a cross-source copy;
so is compression, which is why "compress a folder on SMB into a local archive"
needs no special case.

### Browsing

- **One complete tree.** Top-level nodes are internal storage, root, LAN (SMB),
  FTP, SSH, WebDAV, S3, document tree and favourites. Archives are expandable
  file nodes.
- **Always expand in place, never "enter" a directory.** The current directory is
  the last one you tapped, and it is the target for new folders, copies and moves.
- Single pane in portrait (swipe to switch), **dual pane side by side in
  landscape**.
- Multi-select, cross-pane copy and move with progress and cancellation, mkdir,
  rename, recursive delete, clipboard-style paste.
- **Favourites** and **recent locations** store *how to get there* (connection
  label + path), not a session-scoped scheme, so they survive restarts.
- **Restores your last position** across restarts, level by level, releasing
  control the moment you touch the list.
- **Grid view**, independent of the thumbnail toggle, with adaptive column count.
- **Property cards** inline under a row: EXIF, media tracks, app info, hashes —
  all from system APIs, and never reading a whole file just to fill a field.
- **Treemap disk usage** (SpaceSniffer style) embedded in the pane, pinch to zoom,
  with the same long-press actions as the tree.
- **Twig works as a file picker**, both for other apps (`GET_CONTENT`) and for its
  own imports — which is how you can pick a file from inside an SMB share or an
  archive, something the system picker cannot do.

### Viewers and player

Every viewer reads through `FsRegistry`, so local, in-archive and remote files
all work the same way.

- Text viewer with hand-written syntax highlighting, multiple themes
- Hex viewer
- Image viewer with downsampling, plus a slideshow that starts on the first image
  found while still scanning
- **Video/audio player** (media3 + FFmpeg software decoding) covering AVI, real
  Blu-ray M2TS, HDMV private audio tracks and PGS subtitles, with two-stage
  automatic fallback when a system decoder crashes
- Music player with waveform display

### Terminal

- **Local shell** on a real PTY, and **SSH sessions** over SSHJ, in one session
  list with a switcher at the top
- **Privileged terminal** via root or Shizuku, always a separate, clearly labelled
  entry — never a silent upgrade of the ordinary shell
- Importable fonts and colour schemes (any Termux `colors.properties` works);
  pinch to resize, which resyncs the remote PTY
- **Command shortcuts**: attach a command to an SFTP directory or server, run it
  in a terminal or silently in the background, and pin it to the home screen

### Git

- `:git-lite` implements `GitRepo`, `ObjectStore`, `IndexFile` and a patience
  `Diff` from scratch
- Status and history views, side-by-side diff sharing the text viewer's highlighter
- Local repositories read directly; SFTP repositories over remote `git` exec
- **Worktrees and submodules** handled properly, including the case where the
  absolute path recorded in `gitdir:` does not exist on this machine
- A virtual **Worktrees** node listing the repository's other worktrees, each
  opening into its own full status/branches/history view

### Wi-Fi sharing

A minimal HTTP/1.1 server written directly on `ServerSocket` — no new dependency.
Point a browser at the device for a file listing with range downloads and
drag-and-drop upload; the same port also speaks **WebDAV**, so Finder, Windows
Explorer or another copy of Twig can mount it. **Read-only by default**, optional
Basic auth, foreground service with a Wi-Fi lock, and UDP discovery so another
Twig can find it and save it as a connection.

---

## Modules

| Module | Contents |
|---|---|
| `:core-fs` | Pure JVM: `XFile` / `FileSystem` / `FsRegistry` / `CopyEngine` |
| `:fs-local` | `LocalFileSystem` plus `priv/` — the root/Shizuku privileged shell fallback |
| `:fs-archive` | `ArchiveFileSystem` + zip (read/write, encryption) / 7z / RAR, and `ArchiveWriter` |
| `:fs-network` | `FtpFileSystem` / `SftpFileSystem` / `WebDavFileSystem` / `S3FileSystem` |
| `:fs-smb` | `SmbFileSystem` — libsmb2 via NDK/JNI |
| `:fs-restic` | restic repository reader (pure Kotlin) |
| `:fs-zstd` | `NativeZstd` — zstd via JNI |
| `:git-lite` | Miniature git in pure Kotlin |
| `:app` | Dual-pane UI, viewers, terminal, git views, treemap, Wi-Fi sharing |

---

## Building

```bash
./gradlew :app:assembleDebug      # installable debug APK
./gradlew :app:assembleRelease    # R8-optimised release
./gradlew :app:bundleRelease      # AAB for store upload (smaller per-device download)
```

Tests:

```bash
./gradlew :core-fs:test :fs-archive:test :fs-network:test :fs-restic:test :git-lite:test
./gradlew :app:testReleaseUnitTest
```

Notes: build-tools are pinned to 36.1.0; only `arm64-v8a` and `x86_64` ABIs are
built; R8 is enabled, and JNI entry points for libsmb2 and zstd are kept by name.

### Adding a new source

1. Create a module `:fs-xxx` implementing `com.twig.core.FileSystem`
2. Register it at startup: `FsRegistry.register(XxxFileSystem())`
3. Give the user a way to navigate to `XxxFileSystem.root()`

Browsing, copying, moving, deleting, thumbnails and search then work unchanged.

---

## License

Twig is **GPL-3.0-only** — see [LICENSE](LICENSE).

That is inherited rather than chosen: the Termux terminal emulator and the
Jellyfin FFmpeg decoder are both GPLv3, and linking them makes the application
GPLv3.

- [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md) — additional permission for
  linking with the UnRAR-licensed `junrar`, the notice that license requires, LGPL
  relinking, and the trademark reservation
- [THIRD_PARTY.md](THIRD_PARTY.md) — every third-party component, its version and
  its license

**Trademarks.** The "Twig" name and the application icon are not covered by the
GPL grant. Fork and modify the code freely; please rename and re-icon anything
you redistribute, so users are not misled about where it came from.

**RAR notice.** As required by the UnRAR license: the RAR-handling code in this
program may not be used to develop a RAR (WinRAR) compatible archiver. Twig only
extracts RAR archives; it does not implement RAR compression.

---

## Contributing

Contributions are welcome. Please read [CLA.md](CLA.md) first — it is short, it
does not take your copyright, and it explains why the project needs the right to
relicense its own code.

To sign, add one line to your pull request description:

```
I have read the CLA (CLA.md) and I agree to its terms.
```

Implementation decisions and the reasoning behind them — including a long list of
bugs that were expensive to find — are recorded in [CLAUDE.md](CLAUDE.md). Read
the relevant section before changing subsystems like the terminal, thumbnails or
the TS demuxer.

---

## Roadmap

- `:fs-cloud` — Google Drive / Dropbox / OneDrive over plain REST, no vendor SDKs
- Global search
- Size: slim down Bouncy Castle (Conscrypt-only, needs on-device verification)

---

<details>
<summary><strong>Release history</strong></summary>

- **Phase 1** — `FileSystem`/`XFile` abstraction and `CopyEngine`; local
  filesystem; dual-pane UI; multi-select, copy, move, mkdir, rename, delete;
  R8 + resource shrinking + AAB splits.
- **Phase 1.5 / 1.6** — Text, hex and image viewers through `FsRegistry`; opening
  files in other apps; copy progress and cancellation; SAF fallback.
- **Phase 2 / 2.5** — Archive mounting: ZIP read then read/write with GBK
  detection; 7z and RAR read-only; extraction reuses `CopyEngine`.
- **Phase 3 / 3.5 / 3.6** — FTP, then SMB via libsmb2, then SFTP via SSHJ and
  hand-written WebDAV; persistent multi-server connections.
- **Phase 3.7** — restic repositories, read-only and decrypted, formats v1 and v2.
- **0.8 / 0.9** — X-plore-style UX: in-place tree expansion, expand-to-connect,
  single pane in portrait and dual pane in landscape, dark theme.
- **0.12** — Favourites across all sources, connecting on demand.
- **0.45** — Grid view decoupled from the thumbnail toggle (orthogonal switches).
- **0.62** — Slideshow: play while still scanning, true index under shuffle,
  auto-hiding controls.
- **0.65** — Position restore fixes and recent-locations history; "jump to
  containing folder" scrolls to the exact row.
- **0.70** — **Compression**: pack to the opposite pane, zip or 7z, optional
  move mode, cross-source by construction.
- **0.73** — Landscape layout rework: toolbar hidden, action column doubled up,
  path bar shows `type:/server/path`.
- **0.74** — Group sorting extended to the tree list; fixes for a remembered
  expansion state and a full-list flicker on selection.
- **0.77** — Terminal appearance: font import (the criterion is a 0.5em advance),
  colour schemes, and a pinch-zoom fix. Twig became usable as a file picker.
- **0.78** — SFTP command shortcuts, runnable in a terminal or silently, pinnable
  to the home screen.
- **0.79** — **Local shell terminal** on Termux's native PTY — no bridge threads,
  no reconnect logic, no resize probing needed.
- **0.80** — Made the local shell usable: `CmdShims` works around PATH
  directories being unreadable to the app uid, `SshHome` gives OpenSSH a config
  with absolute paths, and `.mkshrc` binds prefix history search to the arrow keys.
- **0.96** — **Wi-Fi sharing**: a hand-written HTTP/1.1 server that also speaks
  WebDAV, exposing the whole `FsRegistry` — a browser can download a file that
  lives on SMB or inside an archive.
- **0.97 / 0.98 / 0.99** — Sharing rework: "all sources" mode filtered by whether
  a root is actually browsable, name-based path resolution, correct link encoding
  for non-ASCII paths; a rebuilt web UI with inline preview, sorting and batch
  operations; entry point moved to the action column.
- **1.00** — **Archive passwords**: read encrypted zip, 7z and rar, and create
  AES-256 archives. The zip side is hand-written, because neither
  commons-compress nor `java.util.zip` can even read encrypted zips.
- **1.01+** — Appending to an existing zip became a true append (1875 ms → 1 ms on
  a 100 MB archive); expanding an archive now selects its root as the paste
  target; **S3-compatible object storage** with hand-written SigV4; privileged
  access via root and Shizuku, including a privileged terminal built on
  `bindUserService` with a PTY allocated on the privileged side.

</details>
