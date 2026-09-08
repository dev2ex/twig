# Twig — Engineering Notes & Working Guide

> **For human readers**: this file is Twig's engineering notebook. It is written for
> "the person about to change this code", not for "someone meeting the project for the
> first time" — for an introduction see [README.md](README.md).
> The most valuable part is **"Hard-won lessons"**: one file per area under
> [docs/lessons/](docs/lessons/), indexed at the bottom of this file. Every entry is the
> closing record of a real incident, with the symptom, the wrong diagnosis, the root cause
> and the current solution. **Before touching the terminal, thumbnails, TS demuxing,
> privileged access or WiFi sharing, open the matching file and read it first** — several
> of them took two or three shipped regressions to pin down, and stepping on them again is
> expensive. Keeping them out of this file is deliberate: what gets loaded every session
> stays short, and the detail is one read away.
>
> The file keeps the name `CLAUDE.md` because Claude Code reads it automatically; the
> content applies to humans just as well. Machine-specific settings (test device
> address, release directory, …) live in `CLAUDE.local.md`, which is not committed.

A size-first, dual-pane Android file manager, one tree over every source: plain Kotlin + XML
Views, no Material library, minimal dependencies.

## Build and verify

```bash
# Build release (R8 + resource shrinking; build-tools pinned to 36.1.0)
#    ★ There are flavors: full includes RAR and is the one you install; libre is the
#      F-Droid build.
./gradlew :app:assembleFullRelease

# Verify the version with aapt (★ never trust the pipeline's exit code —
# a failed build can still leave you installing the previous APK)
"$ANDROID_HOME"/build-tools/36.1.0/aapt dump badging \
  app/build/outputs/apk/full/release/app-full-release.apk | grep -E "versionName|versionCode"
```

- **Verification discipline**: before installing, always confirm with aapt that
  `versionName` is the build you just made. A failed build once got masked by a
  pipeline `exit 0`, and an old APK shipped as a new one (the 0.20.3 == 0.20.2 incident).
- **Version numbers**: only bump `versionCode` (+1) and `versionName` in
  `app/build.gradle.kts` when you are really installing for testing or delivering
  externally; fixes bump the patch component, features bump the minor one. Small
  intermediate iterations within one round of polishing (tweaking a corner radius or
  spacing back and forth) only need a compile check
  (`compileFullReleaseKotlin` / `assembleFullRelease`), not a version bump each time.
  ★ **Bumping `versionCode` means writing a changelog in the same commit**:
  `fastlane/metadata/android/{en-US,zh-CN}/changelogs/<the new versionCode>.txt`,
  500 characters or less, and the file is named after the **versionCode, not the version
  name**. That is what F-Droid shows as the release note — miss it and that release ships
  with no description. The rest of the store copy and images live under `fastlane/`,
  rules in `fastlane/README.md`.

What happens after that — which device to install on, where builds get published, how
you get told about it — is every developer's own preference, so it is deliberately not
in this file. The setup used on the machine this was written on lives in
`CLAUDE.local.md`, which is not committed.

## Build environment

- **Release is signed with our own keystore**, whose credentials are read from
  `keystore.properties` in the repository root (path and passwords are in
  `CLAUDE.local.md`; neither the key nor that file is committed).
  ★ When the file is missing, release produces an **unsigned** APK rather than silently
  falling back to the debug config: the AOSP debug key is public, so anyone could sign an
  APK that installs over the real one, and a silent fallback would bury that trap again
  with no warning. An unsigned APK is also exactly what F-Droid wants (it signs its own,
  or does a reproducible-build comparison).
  ★ The `import java.util.Properties` at the top of `app/build.gradle.kts` cannot be
  omitted: in the Kotlin DSL, `java` resolves to Gradle's java extension, so writing
  `java.util.Properties` inline fails with "Unresolved reference: util".
  ★ `INSTALL_PARSE_FAILED_NO_CERTIFICATES` when installing means exactly one thing:
  the build found no `keystore.properties` and produced an unsigned APK.
  ★ **Changing which key signs a build makes it refuse to install over the old one**
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — the previous install has to be uninstalled
  first, taking its data with it. This is why test builds are signed with the debug key
  instead: put `debugSign=true` in `local.properties` (gitignored, so it never reaches
  another branch) and release is signed with the AOSP debug key, letting a test build
  install straight over the last one. `-PdebugSign=true|false` overrides the file.
- ABIs are limited to `arm64-v8a` + `x86_64`; for a store listing use `bundleRelease`
  to produce an AAB split per device.

## Architecture in one sentence

Every backend (local / archives / FTP / SFTP / SMB / WebDAV / restic) presents the same
`FileSystem` + `XFile` interface to the UI, and cross-backend copy/move goes through
`CopyEngine` streaming over `openInput()/openOutput()`. **Adding a backend = adding one
FileSystem implementation, with zero changes to the UI or to copying.**
Every `XFile` carries a `scheme`; `FsRegistry.of(scheme)` finds the matching FileSystem.
Multiple servers of the same kind each get a unique scheme.

**Optional capability interfaces** (`core-fs/MediaServer.kt`): `FileSystem` itself is the
floor that "every backend must answer"; capabilities that only make sense for a few
backends get their own interface, implementations opt in with `: PlaybackProgress`, and
callers always do `FsRegistry.of(file) as? XXX` and fall back to the old path when they
get null. There are currently five, most of them added for media servers:
`PlaybackProgress` (the backend owns playback position), `CoverSource` (the backend has
posters), `MediaInfoSource`, `LyricsSource`, `SearchSource`, `EpisodeSeries`.
Before adding a capability, decide whether *every* backend should be able to answer it —
if yes it belongs in `FileSystem`, otherwise it goes here; don't make 20 implementations
each write `= null`.

### Modules

| Module | Purpose |
|---|---|
| `:core-fs` | Pure JVM: `XFile` / `FileSystem` / `FsRegistry` / `CopyEngine` |
| `:fs-local` | `LocalFileSystem` (java.io.File) + `priv/` privileged fallback (`PrivilegedShell`, shared by root and Shizuku) |
| `:fs-archive` | `ArchiveFileSystem` base + Zip (read/write, auto-detects GBK) / 7z (read-only) / tar (read-only, entries read by offset slicing) / gz·xz·bz2·zst (`SingleFileSystem`, one entry, stacks into tar.gz; zstd's decoder is injected by `:app` because this module is plain JVM); `ArchiveWriter` packing (zip/7z) across backends; encrypted archives (`ZipAes`/`ZipCrypto`/`ZipWriter`) |
| `:fs-archive-rar` | `RarFileSystem` (RAR4 + RAR5, read-only). **A separate module purely for licensing** — junrar is non-free and only ships in the full flavor, see [docs/lessons/rar-and-fdroid.md](docs/lessons/rar-and-fdroid.md) |
| `:fs-network` | `FtpFileSystem` / `SftpFileSystem` (SSHJ) / `WebDavFileSystem` (hand-written PROPFIND) / `S3FileSystem` (hand-written SigV4 + REST) / `JellyfinFileSystem` (Jellyfin + Emby, read-only virtual tree) |
| `:fs-smb` | `SmbFileSystem` — libsmb2 via NDK/JNI, read/write |
| `:fs-restic` | Read-only decrypting reader for restic repositories (pure Kotlin) |
| `:fs-zstd` | zstd JNI: `NativeZstd` (whole-block, restic) + `ZstdInputStream` (streaming, `.zst` archives) |
| `:git-lite` | Mini git in pure Kotlin (`GitRepo`/`ObjectStore`/`IndexFile`/patience `Diff`) for browsing worktree status and history; worktrees/submodules via `GitLayout` + `WorktreeGitFs` |
| `:app` | Dual-pane UI, permissions, mutations, viewers, terminal, SAF fallback, WiFi sharing service |

### Key UI classes (`:app`)

- `MainActivity` + two resident `PaneFragment`s (ViewPager2 was dropped; visibility
  toggling + fling gestures, same layout in portrait and landscape).
- `PaneViewModel` owns the navigation stack and tree expansion; `FileAdapter` renders rows.
  The tree always expands/collapses **in place**, it never "enters" a directory.
- Persistence: connections in `ConnectionStore`, favorites in `FavoritesStore`,
  preferences in `Prefs` — all SharedPreferences.
- Viewers: `TextViewerActivity` / `HexViewerActivity` / `ImageViewerActivity` /
  `MediaPlayerActivity` (ExoPlayer + ffmpeg software audio decoding).
  The hex table itself is `HexPane` (one RecyclerView + one `HexSource` + the row rendering),
  shared by the viewer and `HexCompareActivity`; highlights come from a single `hits` hook, so
  search hits and byte differences take the same rendering path.
- File compare: `CompareActivity` (directory tree) dispatches a pair to `ImageCompareActivity`
  (images) or `DiffActivity` (two-column text). ★ `DiffActivity` **hands binaries and oversized
  pairs on to `HexCompareActivity`** rather than dead-ending on `diff_binary` — whether a file is
  binary is only knowable after reading it, so that routing cannot happen back on the compare page.
  Hex compare aligns **same offset against same offset** with no resynchronisation (`HexDiff`):
  inserting a byte makes everything after it differ, which is the honest answer for the case this
  page is for (firmware, headers, patched binaries — structures that keep their offsets).
  Two constraints that are easy to get wrong, both with tests in `HexDiffTest`:
  both columns must share **one** bytes-per-row and **one** offset-digit count (otherwise the two
  offset columns disagree and "the same row on both sides" is a lie), and the shorter file's column
  is **padded with blank rows** (`HexPane.padTo`) — without that it bottoms out first and, since the
  columns push their scroll positions onto each other, drags the longer side back up, so the tail
  difference can never be reached.
- Git: `GitActivity` (status/history), `DiffActivity` (two-column patience diff);
  SFTP repositories run git remotely over exec via `SshGitData`.
- Interop with other apps: two ways in (`SEND` → `ShareTargetActivity` "copy to…";
  `VIEW` → `ViewIntentActivity` "open with Twig") and one way out
  (`OpenFiles.openWith` exposes a streaming `content://` via `StreamProvider`).
  Incoming `content://` URIs are all carried by `ShareSourceFileSystem` (scheme `share`),
  whose `openRandom` seeks through `openFileDescriptor` — so archives are parsed
  streaming without materializing and video can seek.
  `ViewIntentActivity` is a headless relay: it dispatches by `OpenFiles`' extension rules
  (falling back to the MIME major type) to the right viewer, while archives go through
  `MainActivity.mountIntent` → `PaneViewModel.mountExternal` and are mounted at the top
  of the tree, expanded in place. ★ Do **not** add `FLAG_ACTIVITY_NEW_TASK` when
  dispatching: the temporary read grant on a `content://` follows the receiving task
  stack, so in a new stack the viewer throws SecurityException on first read. That is
  also why base FileSystem registration moved from MainActivity to
  `TwigApp.registerBaseFs` — these entry points can cold-start the process with no
  main UI present.
- Security: `secure/Secrets` (two-layer Keystore/master-password encryption of sensitive
  fields) + `secure/Backup` (`.twigbak` config backup) + `ui/SecurityUi` (dialogs).
  See [docs/lessons/security.md](docs/lessons/security.md).
- Settings: `SettingsActivity` (a hand-written standalone settings page: display / grid
  view / thumbnails, no preference library). Layout-affecting preferences (row height,
  grid, thumbnails) go through `Prefs.uiSignature` — `MainActivity.onResume` compares the
  signature and calls `recreate()` on change; in `PaneFragment.onViewCreated`, **when the
  VM already has rows, only call `resortAll()` and do not bootstrap again** (after
  recreate the VM survives, and a second bootstrap is either skipped by an internal guard
  or breaks position restoration).
- Thumbnails: `Thumbs` (the engine). Two cache layers: an in-memory LruCache and a
  100 MB disk LRU under `cacheDir/thumbs`; the key is md5(name:size:mtime) and is
  **path-independent** (moving a file or remounting still hits, changing it invalidates).
  There used to be an optional "share a `.thumbnails` folder next to the file" layer;
  it was removed entirely on 2026-08-06. Network images are gated by a preference —
  when off, only the embedded EXIF thumbnail in the JPEG header is read; PDFs need a
  seekable real file so they are generated for local files only. Files inside archives
  count as "network" (the host may live on SMB). Failures get a 60 s cooldown (not a
  permanent blocklist); the generation queue is FIFO with 2 threads; collapsing a
  directory calls `cancelPending` to drop its queued work.
  **App icons do not go through thumbnails** (2026-08-06): icons for APK files and for
  "Apps" tree entries (scheme `apps`) come straight from `FileIcons` asking the
  PackageManager (keys `apk <path>` and `pkg:<package>`), asynchronously and with its own
  cache, unaffected by the thumbnail preference. Routing them through the thumbnail
  pipeline would only render the same image twice and burn a second copy of disk cache
  and a queue slot.
- **Network video thumbnails** (★ see [docs/lessons/thumbnails.md](docs/lessons/thumbnails.md) for the full saga): instead of
  downloading the whole file, Twig parses the container itself to locate the keyframe at
  1/10 of the duration and downloads only ~2 MB. MP4 parses the moov sample tables
  (`findKeyframeOffset`); MKV parses EBML (SeekHead → Cues → target Cluster).
  All four network backends (SMB/WebDAV/SFTP/FTP) implement an efficient `openRandom`
  (`FileSystem.randomAccessEfficient() == true`), which player seeking benefits from too.
- Grid view (independent of the thumbnail preference): the same RecyclerView switches to
  a `GridLayoutManager` and mixes spans — ordinary rows take a full span, file cells take
  one column, and the column count adapts to the pane width / 96dp. Directories and
  expandable items (archives) always take a full row, and tree expansion logic is
  unchanged. Group sorting lives in `PaneViewModel.sortList` and applies **when the
  thumbnail master switch is on or grid view is on** (tree list and grid are treated
  alike): directories → [in the grid's "All files" mode: expandable archives] → files
  that can produce a thumbnail → everything else, with the user's chosen sort inside each
  group. Archives get their own group only in the "All files" grid — there they are the
  only full-width row in a sea of cells, and leaving one in the middle would cut the grid
  in half.
- Info card: long-press → "Properties" inserts a tab card below the file's row
  (`PaneViewModel.InfoNode` + a second viewType in FileAdapter); the content comes from
  `FileInfo` using system APIs only (EXIF / MediaMetadataRetriever / PackageManager;
  media track details are enumerated with media3's `MetadataRetriever`).
  **No-full-read rule**: anything requiring a full file read is not fetched automatically
  — remote APKs show no app info, and the hash tab computes automatically for local files
  but requires a tap for network ones.
- Space map (SpaceSniffer-style treemap): `TreemapView` (hand-written squarified layout;
  pinch zoom is a geometric transform and the font size stays fixed, so zooming in simply
  reveals more labels) + `TreemapScanner`, **embedded inside PaneFragment** (mapMode
  replaces the tree in place, it is not a new screen). The back key is routed through
  `MainActivity.onBackPressed → activePane().handleBack()` (up one level / exit); the
  block menu and the tree's long-press menu share `commonFileActions`; "Select" on the map
  stores its multi-selection in `mapSelected`, and the sidebar's copy/move/delete prefer
  it via `selectionOrCurrent()`; "Show on the other side" is
  `PaneViewModel.revealPath`, expanding level by level (local and connected server
  schemes only).
- **Navigation bar tinted to match the page** (`ui/NavBarTint`, 2026-08-21): the default
  solid black navigation bar is a jarring black strip at the bottom in the light theme,
  and even in the dark theme it is a shade off from the list background. Tinting it the
  same color makes it look transparent. **We do not make it truly transparent** — that
  requires `setDecorFitsSystemWindows(false)` and handling insets in layout ourselves
  (the music player page already does exactly that, which is why it does not use this).
  Two things matter:
  - **Take the actual background of the bottom-most layer, not `@color/bg`**: the file
    pane (`fragment_pane`) and both sides of directory compare use `@color/surface`,
    image compare is pure black, and the terminal uses the accessory key bar's
    `TermColors.keyBarBg()` (terminal colors are their own scheme, unrelated to the app
    theme, so its tinting lives in `applyColors()` and follows the scheme). One shade off
    and you can see the seam.
  - **★ A light tint is not allowed below API 26**: `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR`,
    which darkens the navigation icons, only exists from API 26. On older systems the
    icons are always white — a light tint means white icons on a white background, so
    home/back are still there but invisible. In that case keep the system default (black).
- **WiFi sharing** (`com.twig.app.share`, 0.96.0): Twig's only "others connect to Twig"
  path. `HttpServer` (HTTP/1.1 on a bare ServerSocket: request parsing, Basic auth,
  Range, keep-alive, chunked request bodies, 100-continue) + `ShareHandler`
  (GET/POST for browsers, PROPFIND/PUT/MKCOL/MOVE/COPY/LOCK for WebDAV clients) +
  `WebUi` (self-contained HTML/CSS/JS with streaming `Multipart` upload) + `ShareRoot`
  (URL path ↔ XFile) + `Discovery` (UDP probe/response) + `ShareService` (foreground
  service). The UI is only `ui/ShareDialogs` (config/status dialog + scan dialog), there
  is no separate Activity; the entry point is in the **action bar** (`as_share`), the icon
  becomes a filled fan while the service runs (`syncShareIcon`, refresh all three action
  bars), and the shared scope defaults to the green-highlighted
  `PaneViewModel.currentDir` — there is no directory picker in the dialog because the
  entry point sits next to the file tree and the location was already chosen before the
  dialog opened. A few settled decisions:
  - **Read-only by default**; every write method's check funnels through
    `ShareHandler.requireWrite` in one place, not scattered across handlers.
  - **In "all backends" mode the top-level path segment is the scheme, not the display
    name**: schemes are globally unique and derived deterministically from the connection
    label, so once a WebDAV client has saved one as a mount point, paths do not shift
    because another server got expanded. Display names are used only for the link text
    in HTML.
  - **`..` is always rejected** (`ShareRoot.segments` returns null), and uploaded file
    names are reduced to their last segment — in this feature path traversal means
    handing out things outside the shared directory.
  - **`DAV: 2` must be advertised and LOCK must be implemented** (even as a fake lock
    that does not actually lock): macOS Finder and Windows Explorer mount read-only as
    soon as they see no LOCK support, which would make writing pointless. Likewise
    `PROPPATCH` must answer 207 rather than 501, or Finder decides the copy failed and
    deletes the file it just uploaded.
  - **The WakeLock is held only while requests are in flight** (`HttpServer`'s `onActive`
    callback); when idle the foreground service is enough. The WiFi lock, on the other
    hand, is held for the whole sharing session (once the screen is off and WiFi sleeps,
    the device disappears from the LAN).
  - **No START_STICKY**: if the process really was killed, the sockets and threads are
    gone, and the system restarting the service would only produce an empty shell with
    `session == null`. Dying with the process is more honest.
- `CodeHighlighter`: hand-written lexical highlighting, shared by TextViewer and the
  two-column git `DiffActivity` — diff tokenizes each side's whole text once and then
  slices per line with `subSequence` (only that way are multi-line comment/string states
  correct). DiffActivity uses the user's chosen theme when its lightness does not clash
  with the current system light/dark mode; when it does (e.g. Monokai while the system is
  light) it temporarily substitutes the matching default (light → GitHub Light,
  dark → Monokai) so the diff area does not collide with the toolbar/status bar, which
  still follow the system. This affects only that display, `Prefs.codeTheme` is untouched.
  `listLeft`/`listRight` backgrounds switch to `theme.bg` and the default row text color
  to `theme.fg`; added/removed row backgrounds are translucent overlays, so they read as
  reddish/greenish over any background and no per-theme palette is needed —
  before 2026-07-28 there was a `diffTheme()` that **unconditionally** fell back to
  GitHub Light based on background lightness, because the background was not following
  the theme and dark themes were unreadable. The root cause was the background, not
  whether the theme should be used.

## Conventions

- **Size first**: no heavyweight dependencies; if it can be hand-written, hand-write it
  (WebDAV without an SDK, git reimplemented, restic decrypted ourselves). Before adding a
  dependency, measure the APK delta.
- **R8**: obfuscation is on. JNI (libsmb2/zstd) binds by name, so native methods and their
  class names must be kept; Termux and BouncyCastle are also kept in
  `app/proguard-rules.pro`. Read that file before changing how those libraries are called.
- **User-facing text lives in two places**; before adding a string, work out which layer
  you are in:
  - Inside `:app` everything goes through `strings.xml` (zh and en, with identical `name`
    sets). `FileInfo`, `GitVfs`, `SafFileSystem` and friends have a Context even though
    they are not Activities — there are no exceptions.
  - **Pure JVM modules** (`core-fs` / `fs-*` / `git-lite`, using the `kotlin.jvm` plugin)
    have no Context and no R, so their messages are **always English**. Their exception
    messages get toasted to the user through `it.message`, and turning those modules into
    Android libraries just to localize them would cost the millisecond-fast pure-JVM unit
    tests — not worth it. If localization is ever really needed, the way is
    "`FsException` with an error code + a lookup table in the UI layer", not moving
    resources around.
- **Commit messages**: English Conventional Commits, `type: task name — summary`
- **The settings triad**: a new display feature = its own preference (never bolted onto
  another preference; keep them orthogonal) + a row on the settings page + **only when it
  is something you flip back and forth** a quick entry in the top-bar menu (decoupling
  grid from thumbnails in 0.45.0 is where the orthogonality rule came from).
  - ★ **The menu holds only high-frequency toggles** (settled 2026-08-26): thumbnails,
    grid view, remember position, fullscreen and theme are things you may flip several
    times a day; **anything you set once and never touch again belongs on the settings
    page only** — row height, text size, language and "show hidden files" have all been
    removed from the menu. The menu is not a mirror of the settings page; every extra
    item dilutes the few that are genuinely used.
- **Row height and text size are two independent preferences** (split 2026-08-26,
  `Prefs.density` / `Prefs.textSize`): previously one setting drove row height, icon size
  and font size together, so "shorter rows but readable text" was impossible.
  ★ `textSize` **returns `density(ctx)` when it has never been set** — so the appearance
  is pixel-identical before and after the split and upgrading users see no jump.
  ★★ But "fall back to the default" must be cut off **the moment the user changes row
  height**: `setDensity` first writes the current text size out explicitly (using the
  density level from *before* the change, so the user sees nothing change), otherwise
  someone who never touched text size still gets their font size dragged along by row
  height and **the split accomplishes nothing**. The assertion is in `RowSizeSplitTest`,
  which measures the bound row height in px and font size in px, not "what the preference
  reads back as" — the latter is green even on the version where the adapter still derives
  the font size from density.
- Unit tests live in each `:fs-*` module's `test` source set (zip/ftp/sftp/webdav/restic/
  git all have them); run them with e.g. `./gradlew :fs-network:test`.

## Hard-won lessons (read the matching file before changing that code)

Each entry is the closing record of a real incident — the symptom, the wrong diagnosis,
the root cause and the current solution. They live one file per area under
**[docs/lessons/](docs/lessons/)**; this is the index, and it is deliberately only a
pointer: **before changing code in one of these areas, open its file and read it first.**
Several of these took two or three shipped regressions to pin down, and stepping on them
again is expensive. When you close out a new incident, append it to the matching file and
add its symptom here.

- **[SSH, SFTP and the terminal](docs/lessons/ssh-and-terminal.md)** — every outbound SSHJ
  call (resize included) runs on a background thread and network exceptions are never
  swallowed: an NMTE poisons the cipher stream, so the connection dies on the *next*
  packet. No local process is started; the emulator is injected reflectively. Full
  BouncyCastle must stay.
  *Explains*: "it disconnects on the first keypress" on every server, htop's blank
  margins, keystrokes dropped after a reconnect.
- **[git viewer](docs/lessons/git-viewer.md)** — never use `FileSystem.resolve()` to test
  existence or type; `.git` may be a *file*, and gitdir ≠ commondir with per-worktree
  files routed between them. Caching is three layers deep and no refresh reaches the
  bottom one.
  *Explains*: empty history/branches/diff with no error, "Worktrees (3)" that expands
  into nothing, new commits missing after a fetch.
- **[Thumbnails and frame grabbing](docs/lessons/thumbnails.md)** — never let
  MediaMetadataRetriever seek by itself over a slow network: parse the container (MP4
  sample tables, MKV EBML/Cues) and feed it only the keyframe bytes. A fat moov is its own
  bottleneck: download only the video trak and serve every other child box as a same-size
  `free` stub, never a shorter moov. An algorithm change that invalidates a cache means
  renaming the cache directory.
  *Explains*: 111 MB and 14 s for one thumbnail, green or black frames, a multi-audio 4K
  release whose thumbnail only appears if you wait and retry, a flat-looking waveform,
  cover rounding that never shows.
- **[Media playback](docs/lessons/media-playback.md)** — internal media URIs must carry a
  real extension (`twig:///media.$ext`); M2TS packets are 192 bytes and HDMV stream types
  collide with the standard registry; never allocate in a `DataSource.read()` hot path;
  the player needs an explicit `LoadControl` (media3's default buffer is 137MB against a
  256MB heap, and its small local-playback tier is picked by URI scheme, which `twig://`
  never matches); AVI needs its samples repaired before any decoder sees them — MP3 is a byte
  stream to be cut back into frames (with a double-sync check), and MPEG-4 arrives in coding
  order, sometimes two VOPs to a chunk, sometimes as 1-byte stuffing; episode queues come from
  the backend first, file names second.
  *Explains*: an AVI that plays as 300 ms of audio, no sound on a TrueHD Blu-ray, "m2ts
  seeks slowly", black AVI thumbnails, "half my AVIs open at 13 seconds and then freeze",
  "the picture keeps shaking", "this one plays at two thirds speed", "this one will not open
  at all and has no thumbnail", the next episode not starting, "4K over the network quits
  after a while and then the app asks for my master password again".
- **[Archives](docs/lessons/archives.md)** — adding an entry to a zip **appends**
  (rewriting means decompressing and recompressing everything); encryption detection must
  come *after* `rootOf()`; encrypted archives are read-only; expanding an archive selects
  its root as the paste target; never read a zip with `ZipInputStream` — a STORED entry
  with a data descriptor has no readable length outside the central directory.
  *Explains*: 10 GB of I/O to add ten small files, a remote encrypted archive that never
  asks for a password, "I cannot paste into this archive", an APKPure .xapk that fails to
  install with "only DEFLATED entries can have EXT descriptor".
- **[WiFi sharing](docs/lessons/wifi-sharing.md)** — read-only by default through one
  `requireWrite` funnel, `..` always rejected, `DAV: 2` + LOCK or Finder mounts read-only;
  addressing uses `path` and links are built from `rawPath`. A new mode needs tests *for
  that mode*.
  *Explains*: a corrupt download (multipart boundary), "I copied it and the source
  vanished", a non-ASCII directory that will not open, buttons squeezed to 1 px.
- **[Jellyfin / Emby](docs/lessons/jellyfin-emby.md)** — the biggest file, and the one to
  read in full before touching media servers: the tree follows the server's own
  organisation, paths are ids so `displayName` must travel everywhere, no metadata request
  may sit in the streaming path, and the two servers differ on Resume, lyrics and images.
  Arguments here are settled by `docker run`ning both servers, not by reasoning.
  *Explains*: "Continue watching is always empty" on Emby, square posters, slow covers,
  4K stuttering on this backend only, search at the root doing nothing, songs listed
  as "38", `Unknown Jellyfin directory: /lib`.
- **[S3](docs/lessons/s3.md)** — the request path is RFC 3986 while object names in
  responses are *form* encoded (`+` = space) and the continuation token is opaque; the
  signed string must match the bytes actually sent; never bring in the AWS SDK; abort a
  failed multipart upload.
  *Explains*: SignatureDoesNotMatch with no hint, a listing that looks fine and 404s on
  click, empty directories vanishing after a refresh.
- **[Privileged access (root / Shizuku)](docs/lessons/privileged-access.md)** — elevation
  is a fallback inside `LocalFileSystem`, not a new scheme; the stderr drain must not
  share the exec lock; the extracted `.so` must never be writable even for an instant; the
  privileged terminal is its own menu item and never falls back silently.
  *Explains*: a command that hangs until timeout, `/sdcard` listing as empty, "the dialog
  has no options at all", a root process left alive after an upgrade.
- **[RAR and F-Droid](docs/lessons/rar-and-fdroid.md)** — `full` and `libre` differ in RAR
  **alone**; check the scheme via `Archives.RAR_SCHEME`, never `RarFileSystem.SCHEME`; the
  verification criterion is the dependency graph, not that it compiles. F-Droid's scanner reads
  the whole tree, so the split alone does not get a build through — the module has to be `rm`'d.
  Also records every rejected route (unrar-free, porting, clean-room) so they are not researched
  again, and what reproducible builds took: AGP's dependency-metadata block has to be turned off,
  and AGP 8.5.2's bundled R8 is not deterministic across machines.
  *Explains*: "libre has no junrar on its classpath, so why did fdroid build stop on it",
  "F-Droid's build matches ours in every byte but four".
- **[Password encryption, config backup and the app lock](docs/lessons/security.md)** —
  toggling the master password or fingerprint rewrites no field ciphertext; on any crypto
  failure return the input unchanged; every exported Activity must call `SecurityUi.gate`;
  views and `lateinit` fields are created in `onCreate` and only data reads go in the gate
  callback; unlocking must happen before pane initialisation.
  *Explains*: a crash right after entering the master password, "after unlocking, WebDAV
  login fails until I re-save the connection", a password field rendered in plain text.
- **[SAF](docs/lessons/saf.md)** — a SAF path is a whole document URI, so nothing may
  slice it: ask the tree who contains a row instead of computing a parent. The grant is
  persistable, so SAF entries are *local* (real fd, pread), not outsiders.
  *Explains*: music opening in the single-track player, no thumbnails or PDF previews, the
  green highlight disappearing on "Up", "why is there no *go to the containing folder* here".
- **[Removable storage](docs/lessons/removable-storage.md)** — `getStorageVolumes()` does
  return invisible volumes, the event channels do not fire for them (the foreground UI
  polls), filter by `isPrimary` rather than `isRemovable`, and `/mnt/media_rw` needs
  elevation.
  *Explains*: a USB drive that only appears after restarting the app, internal storage
  listed twice.
- **[Terminal: local shell, fonts, zoom](docs/lessons/terminal-local.md)** — only
  returning to the foreground may arm the size thaw; a terminal font is acceptable only
  at a 0.5em advance; `attachSession` forks the shell itself; the key bar shares one text
  size.
  *Explains*: a garbled screen after screen-off, fat Chinese and a thin `●`, tab
  completion finding no commands, pinch zoom doing nothing, clipped key labels.
- **[Tree and adapter](docs/lessons/tree-and-adapter.md)** — a duplicate row key makes
  DiffUtil pick the wrong row; for async expansion the later tap wins, and "do not
  accordionExpand" ≠ "do not expand"; writes are gated by `isMutable()` /
  `isWritableDir()` at six entry points; selection changes use payloads, never
  `notifyDataSetChanged`.
  *Explains*: the same archive empty in one place, a directory that closes itself, a
  spinner that never stops, the whole list flickering when you tick a box.
- **[Text handling and editing](docs/lessons/text-handling.md)** — one decoding entry
  point (`TextCodec`), where order is priority; write back in the encoding the file was
  read with and never silently substitute `?`.
  *Explains*: mojibake in subtitles but not in the viewer, another program showing garbage
  after you edited one line, an invisible caret, a caret sitting on the last character.
- **[Other backends and odds and ends](docs/lessons/other-backends.md)** — launcher
  shortcut icons must not rely on `android:tint` and dynamic shortcuts follow the app's
  language; libsmb2's context is not thread-safe, so never name a member function `run`;
  do not SSH into servers to read logs.
  *Explains*: a white, invisible shortcut icon, an empty long-press menu, an SMB crash
  while thumbnails load.
