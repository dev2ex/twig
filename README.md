<p align="center">
  <img src="docs/img/logo.png" width="120" alt="">
</p>

<h1 align="center">Twig</h1>

<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">中文</a></p>

A size-first, dual-pane file manager for Android: every storage source lives in
one tree and expands in place, instead of each being its own screen to enter.
Native Kotlin and XML views, no Material library, minimal dependencies. When
something can reasonably be written by hand, it is: WebDAV, S3 signing, git and
restic are all implemented from scratch.

**Version 1.16.0** (versionCode 313) · minSdk 24 / targetSdk 34 / compileSdk 36 ·
[GPL-3.0](LICENSE)

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02-dual-pane.png" width="88%" alt="Dual pane in landscape">
</p>

| One tree, every source | restic, decrypted on device | A Jellyfin library as a filesystem | Disk-usage treemap |
|---|---|---|---|
| ![](fastlane/metadata/android/en-US/images/phoneScreenshots/01-one-tree.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/03-restic-repository.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/04-media-server.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/06-space-map.png) |

<details>
<summary><strong>More</strong> — directory compare, Wi-Fi sharing, terminal</summary>

| Directory compare | Wi-Fi sharing | Built-in terminal |
|---|---|---|
| ![](fastlane/metadata/android/en-US/images/phoneScreenshots/05-directory-compare.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/07-wifi-sharing.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/08-terminal.png) |

</details>

<details>
<summary><strong>The rest of the gallery</strong> — browsing, media servers, viewers, sharing, terminal</summary>

**Browsing**

| Favourites across every source | Long-press menu on a folder | Copying between two sources | Packing into the other pane |
|---|---|---|---|
| ![](docs/img/screenshots/browse-favourites.webp) | ![](docs/img/screenshots/browse-folder-menu.webp) | ![](docs/img/screenshots/browse-copy.webp) | ![](docs/img/screenshots/browse-compress.webp) |

**Sources, appearance and shortcuts**

| A document tree named after the app that granted it | Elevated access: root or Shizuku | Light theme, landscape | Shortcuts on the home screen |
|---|---|---|---|
| ![](docs/img/screenshots/browse-document-tree.webp) | ![](docs/img/screenshots/settings-elevated-access.webp) | ![](docs/img/screenshots/browse-light-theme.webp) | ![](docs/img/screenshots/shortcuts-home-screen.webp) |

**Media servers and music**

| An Emby library's "Latest" | Music player with waveform | Playlist |
|---|---|---|
| ![](docs/img/screenshots/media-emby-latest.webp) | ![](docs/img/screenshots/media-music-player.webp) | ![](docs/img/screenshots/media-music-playlist.webp) |

**Viewers and compare**

| Text viewer, hand-written highlighting | Hex viewer | Text compare with merge arrows | A saved comparison, synced from its row |
|---|---|---|---|
| ![](docs/img/screenshots/viewer-text.webp) | ![](docs/img/screenshots/viewer-hex.webp) | ![](docs/img/screenshots/compare-text.webp) | ![](docs/img/screenshots/compare-saved.webp) |

**Sharing, terminal and git**

| Another Twig found over UDP | A share mounted back as WebDAV | Git status beside a terminal | A command pinned to the home screen |
|---|---|---|---|
| ![](docs/img/screenshots/share-devices-found.webp) | ![](docs/img/screenshots/share-as-webdav.webp) | ![](docs/img/screenshots/terminal-git.webp) | ![](docs/img/screenshots/terminal-command-shortcut.webp) |

</details>

---

## Download

The version above tracks the source tree; downloads follow the published releases.
Signed APKs are attached to every [GitHub release](https://github.com/dev2ex/twig/releases/latest):

| File | Take it if |
|---|---|
| `Twig-<version>-full.apk` | **You are not sure.** arm64-v8a + x86_64, with read-only RAR |
| `Twig-<version>-libre.apk` | You want free software throughout — no RAR, because `junrar` is not free software |
| `Twig-<version>-full-arm64-v8a.apk` / `-x86_64.apk` | You want a smaller download for one architecture |
| `Twig-<version>-full-armeabi-v7a.apk` | You have a 32-bit ARM device — experimental, untested on hardware |

All of them are signed with the same key, so any one installs over any other. The
certificate's SHA-256 is `196f633e942bf4f32f875b98c8ff181138256ba329a63364e3a39f154c1a5e62`.

**Automatic updates** without a store: add `https://github.com/dev2ex/twig` to
[Obtainium](https://github.com/ImranR98/Obtainium). A release carries several APKs, so
set *Filter APKs by Regular Expression* to pick one, or Obtainium will ask every time and
cannot update in the background — `-full\.apk$` for the default build, `-libre\.apk$` for
the free one.

**[Get Twig on F-Droid](https://f-droid.org/packages/com.twig.app/)**: the `libre`
build includes all features except RAR and uses the same signing key as the GitHub
APKs, verified through reproducible builds. F-Droid automatically tracks stable
release tags; updates appear after its build and repository publication steps.

---

## Why Twig

**One tree, every source.** Local storage, archives, FTP, SFTP, SMB, WebDAV,
S3, restic repositories and Jellyfin/Emby libraries expand in place. Keep two
sources side by side: compress an SMB folder into a local zip, or copy a file
from a remote archive straight to an FTP server.

**Small and resource-conscious.** The 1.16.0 full build's arm64-v8a download measures 8.37 MiB,
including the terminal, network backends and media player. Dependencies are added
only after their APK cost is measured.

**Tools that work together.**

- **Browse encrypted backups.** Open a restic repository, browse its snapshots as
  dated folders, and copy individual files to another source. Decryption happens
  on the device.
- **Git beside your files.** Inspect status, history, worktrees and submodules in
  local or SFTP repositories, compare changes side by side, and open a terminal
  for the directory you are working in.
- **A terminal connected to your file manager.** Open a local, SSH or privileged
  shell from a directory, then locate the shell's working directory back in the
  tree. Dedicated `SHIFT` and `|` keys make interactive tools and shell pipelines
  easier to use on a phone; fonts, colours and text size are customisable.
- **Media libraries in the same tree.** Browse Jellyfin and Emby alongside your
  storage, with posters, metadata and subtitles from the server. Resume playback
  and keep progress synced back to the library.
- **Compare across sources.** Compare an SMB directory with local storage, or a
  document tree with an archive. Review differences, then run incremental or
  mirror sync with separate confirmation for newer destination files.
- **Share beyond local storage.** Twig's HTTP/WebDAV server lets a desktop browse
  and download files from connected sources and archives through one connection.
- **Privileged access in the existing tree.** Root or Shizuku extends local access
  when ordinary permissions fall short; protected paths can participate in the
  same copy, search and thumbnail workflows as other directories.
- **Viewers and a dedicated music player.** Read and edit text in its original
  encoding, inspect bytes in hex, or preview documents and media from the source
  you are browsing. Local and network music can share playlists, with waveform
  seeking, synchronised bilingual lyrics and background playback.
- **Make websites your own.** Keep a self-hosted dashboard or web player alongside
  your files, pin it to the home screen, and tinker with its scripts, styles and
  visible elements using the built-in page tools. Downloads can go straight to
  a destination picked in Twig. See [Websites and page customization](#websites-and-page-customization).

---

## Size

Measured on the signed 1.16.0 release APKs (R8 + resource shrinking; MiB = 1,048,576 bytes):

| | Size |
|---|---|
| Full APK (`arm64-v8a` + `x86_64`) | 10.51 MiB |
| Libre APK (`arm64-v8a` + `x86_64`) | 10.45 MiB |
| **Full APK, `arm64-v8a` only** | **8.37 MiB** |
| Full APK, `x86_64` only | 8.49 MiB |
| Full APK, `armeabi-v7a` only (experimental) | 8.13 MiB |

Where it goes (compressed entries inside the full `arm64-v8a` APK):

| Component | Size |
|---|---|
| `classes*.dex` (all our code plus every JVM dependency) | 3.09 MiB |
| `libffmpegJNI.so` | 1.39 MiB |
| Bouncy Castle data files | 1.18 MiB |
| Resources (`resources.arsc` + `res`) | 1.83 MiB |
| `libsamba_jni.so` (libsmb2) | 484 KiB |
| `libtwigzstd` / `libtermux` / `libtwigpty` | 109 KiB |

Of the Bouncy Castle data, 1.16 MiB is three lookup tables for the `picnic`
post-quantum signature scheme (`lowmcL{1,3,5}.bin.properties`). Twig pulls in the
full Bouncy Castle only because Android's cut-down build lacks X25519, so this is
1.16 MiB of pure dead weight — see the roadmap.

Because every source is a separate Gradle module, a build can drop what it does
not need — omitting media playback and the network sources removes the large
majority of the above.

---

## Architecture in one sentence

Every source — local, archive, FTP, SFTP, SMB, WebDAV, S3, restic, Jellyfin —
implements
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
| **Private space** | Full read/write | App-owned storage, browsed through the local filesystem; excluded from backups and removed on uninstall |
| **Android/data/** | Read/write with permission | Saved SAF grants for the whole folder or individual app folders where the system permits; root/Shizuku access also supported |
| **System directories** (`/system`, `/vendor`, etc.) | Browse readable directories and files | Direct access to readable system paths even when listing `/` is blocked; protected contents require root/Shizuku, and read-only mounts remain read-only |
| **ZIP** | **Read + write**, legacy GBK names detected | Mounted as a filesystem; opens like a folder |
| **7z** | Read + **create** (LZMA2) | commons-compress + xz |
| **tar** | Read-only | Entries are contiguous, so they are read by slicing — a nested archive inside opens without being unpacked first |
| **gz / xz / bz2 / zst** | Read-only | One stream, not an archive: mounted as a single entry, so `foo.tar.gz` opens to `foo.tar` and expands again — `.tgz`/`.txz`/`.tbz2`/`.tzst` included |
| **zstd** | Read-only | Streaming decode through the same bundled libzstd that restic uses — no second copy of the library |
| **RAR** | Read-only (RAR4 + RAR5) | junrar, in its own module — `full` builds only, the `libre` build has no RAR |
| **Encrypted archives** | Read zip/7z/rar, **create** AES-256 zip/7z | WinZip AES and legacy ZipCrypto written by hand |
| **FTP** | Full read/write | Apache Commons Net |
| **SFTP** | Full read/write | SSHJ (with full Bouncy Castle for X25519) |
| **SMB / CIFS** | Full read/write | SMB2/3 via libsmb2 (NDK/JNI); opt-in SMB1 via jcifs-ng for legacy devices |
| **WebDAV** | Full read/write | Hand-written PROPFIND/MKCOL/MOVE, no SDK |
| **S3-compatible** | Full read/write | Hand-written SigV4 + REST — AWS S3, MinIO, R2, OSS, COS, B2 |
| **restic** | Read-only, decrypted | Written from scratch; repo format v1 and v2 |
| **Jellyfin / Emby** | Read-only virtual tree | Hand-written REST; one implementation covers both, no new dependencies |
| **SAF document tree** | Read/write | System-level fallback without `MANAGE_EXTERNAL_STORAGE` |
| **Privileged (root / Shizuku)** | Read/write | Not a separate source — a fallback for local paths ordinary APIs cannot reach |
| **Installed apps** | Read-only virtual tree | PackageManager; a split app is packed into an XAPK on the fly, so copying one out keeps its `split_config.*` |

Network sources connect on expand, support multiple servers (each gets a unique
scheme), and persist their configuration. Extraction is just a cross-source copy;
so is compression, which is why "compress a folder on SMB into a local archive"
needs no special case.
The compression dialog remembers the last ZIP/7Z format across launches, even if cancelled.

### Browsing

- **One complete tree.** Top-level nodes include internal storage, root, private space,
  removable storage, trash, LAN (SMB), FTP, SSH, WebDAV, S3, media servers, document trees,
  favourites, websites and installed apps. Archives are expandable file nodes.
  Choose root-item visibility separately for each pane; private space is visible by default.
- **Always expand in place, never "enter" a directory.** The current directory is
  the last one you tapped, and it is the target for new folders, copies and moves.
- Single pane in portrait (swipe to switch), **dual pane side by side in
  landscape**, with animated pane switching. Long-press the pane-switch button to
  pin the current directory on the other side and switch to it.
- Multi-select, cross-pane copy and move with progress and cancellation, mkdir,
  rename, recursive delete, clipboard-style paste, and sharing multiple selected
  files through the system chooser.
- **Trash** for ordinary writable local shared storage, on the original volume.
  Restore or permanently purge individual entries or batches; an empty trash root hides
  automatically. Network, SAF-only and privileged deletions use permanent deletion instead.
  Delete and purge jobs have background progress and stop between backend calls when cancelled.
- **Share into Twig** to copy incoming files or save shared text as a UTF-8 file with an
  editable name. The destination picker has two panes, sorting, search and history, and
  follows the browser's fullscreen and toolbar-placement settings.
- **Browser controls:** optionally put the toolbar at the bottom or exit the main screen
  with Back after pane navigation is exhausted. Completion vibration and foreground
  completion notifications are separate, default-off settings; background results retain
  a notification.
- **Batch rename** selected files and folders with live original/new-name previews.
  Replace text or use regular expressions, choose case sensitivity, and target names,
  extensions or both. Add numbering before or after names with a configurable start
  and zero padding, insert or overwrite text at a zero-based character offset from the beginning (left)
  or end (right), or change case (including English title case,
  with common short prepositions, articles and conjunctions lowercase except at the start
  or end of a title/subtitle). Templates accept `{name}`
  (without extension), `{ext}` (including the dot), `{number}`, and `{date}` (modification
  date as `yyyyMMdd`). Insertion offsets beyond a name’s length stop at the opposite edge;
  offset 0 inserts a prefix or suffix. Overwrite starts at the same position and replaces
  characters to its right, extending the name if needed. Duplicate and existing destination names are checked
  before renaming.
- **Favourites** and **recent locations** store *how to get there* (connection
  label + path), not a session-scoped scheme, so they survive restarts.
- **Restores your last position** across restarts, level by level, releasing
  control the moment you touch the list. With position memory off, favourites
  expand by default.
- **Grid view**, independent of the thumbnail toggle, with adaptive column count.
- **Folder sorting and views:** set global defaults or separate overrides for a folder's
  sorting and list/media-grid/all-files-grid view. Each override can optionally apply to
  subfolders; a folder's own setting wins, followed by its nearest recursive ancestor,
  then the global default. Folder names can be sorted in either direction.
- **Thumbnails** for images and videos on local and network sources, with an optional
  remembered playback-progress bar inside video thumbnails.
- **Optional folder/file counts** beneath local and SMB directories, off by default;
  configurable date formats, including the system region's format.
- **Property cards** inline under a row: EXIF, media tracks, app info, hashes —
  tap the left edge to show or hide them, and swipe between tabs. Basic fields
  appear before slower media details finish loading. Multi-selection has an inline
  card for aggregate size and counts.
- **App inspection** in Properties → App: signing certificates, Activities,
  declared shortcuts, services, receivers and providers. Launch accessible
  Activities and shortcuts directly; long-press a launchable Activity to pin it
  to the home screen.
- **Android/data through SAF**, integrated into the local tree with saved grants
  and app icons. Authorize the whole folder where permitted, or pick a user-installed
  app using icons and name/package filtering, then authorize its folder directly.
  Folder detection is optional because Android can hide existing paths. Availability
  depends on the system picker; root or Shizuku remains an alternative.
- **Readable system directories** such as `/system` and `/vendor` remain reachable
  when Android blocks listing `/`; access to their contents still follows system
  permissions.
- **Treemap disk usage** (SpaceSniffer style) embedded in the pane, pinch to zoom,
  with a reset-zoom button and the same long-press actions as the tree.
- **SD cards and USB drives** get their own rows at the root, named and sized by the
  system. No new permission — a volume the ordinary APIs cannot read can be granted
  through SAF or reached with elevation.
- **Go to path**: type a path on any root — a server, internal storage, a removable
  volume, a document tree, a favourite — and the tree expands its way down to that
  directory or file, connecting the server on the way if it is not open yet.
  Long-press or double-tap the path bar to edit the current directory’s path.
- **Long-press Up** to bring the highlighted current directory back into view.
  If its row is already visible, the same gesture collapses the tree.
- **Split-APK bundles install directly**: `.xapk`, `.apks` and `.apkm` are read out of
  the archive and written to a `PackageInstaller` session, so a bundle from a mirror
  installs without a helper app.
- **Twig works as a file picker**, both for other apps (`GET_CONTENT`) and for its
  own imports — which is how you can pick a file from inside an SMB share or an
  archive, something the system picker cannot do.
- **Home-screen shortcuts** for both directories (reveal in the tree) and individual
  files. A file shortcut also picks *how* it opens — automatic dispatch, as text, as
  hex, or with one specific app chosen at pin time (a shortcut can't re-show a
  resolver on every tap) — and its icon reuses an already-cached thumbnail when one
  exists. Runs in its own task, so tapping one never routes through the main screen.

Launchers such as Nova also list **Twig shortcut** and **Twig command shortcut** in
their shortcut/action picker. Select a directory, file or website and confirm, or
select a command location and configure its command with the same dialog used in
Twig. SFTP locations use SSH. Local directories (including `/`) use the app uid
unless **Use privileged session** is checked. This defaults off and follows the
privilege provider selected in Settings: Root (su) or Shizuku. Saved shortcuts keep
the chosen provider and Shizuku uid; an unavailable or changed identity fails
rather than falling back to another shell. The launcher can bind the result to an icon gesture (swipe up/down)
without adding another home-screen icon. Temporary archive/restic/git mounts cannot
be selected because they cannot be reopened after a process restart.

### Websites and page customization

Keep a self-hosted dashboard, web music player or favourite site in Twig's **Websites**
root, and tinker with the page to make it feel a little more like your own app.
Each bookmark opens in its own
task through Android's system WebView; pin it to the home screen for direct access.
Opening an already-running website returns to its existing page rather than loading
the URL again.

Twig can also open HTTP(S) links from other apps in temporary website tasks. They use
website defaults and enter the bookmark list only through **Add website**. Long-press
the **Websites** root to edit defaults: display choices initialise new bookmarks;
shared JavaScript, CSS, hidden-element and blocking rules apply to existing pages too.
A site's own User-Agent overrides the shared value; its scripts, styles and rules are
combined with the shared settings.

Expand **Websites → Add website…**, enter a name and URL, then choose how it opens.
Long-press its tree entry to edit it or add a home-screen shortcut. Site settings cover:

- **Display and gestures:** fullscreen, separate toolbar/status/navigation bar controls,
  screen orientation, desktop mode, pull to refresh at the top of the page, scroll
  direction locking and optional text-selection suppression. Double-tap toggles the
  toolbar by default; turn it off for sites that use their own double-tap gestures.
- **Page customization:** expand **Advanced** to add JavaScript, CSS and CSS selectors
  for elements to hide. Restyle a dashboard, remove a banner or add your own page tools.
  JavaScript runs after page load; scripts, styles and hidden elements apply to the
  saved site's same-origin main page.
- **Requests and login:** custom User-Agent, manual request headers and HTTP Basic
  authentication. Saved Basic credentials and header values use Twig's encrypted
  secret storage. Headers apply to same-origin GET page navigation, not every API or
  resource request.
- **Resource blocking:** one domain or URL pattern per line, with `*` wildcards, to
  block matching image, script or API requests exposed by WebView. Plain domains include
  subdomains. These are manual resource rules, not a full ad-block filter-list engine;
  they do not block main-page navigation.
- **Tinker on the page:** the toolbar's **Web debugging** panel provides a console,
  JavaScript execution, CSS preview and an element picker. Copy a selector or HTML,
  hide an element, and save your changes back to the bookmark. For desktop DevTools,
  enable **Computer debugging (session)** and use `chrome://inspect/#devices` with
  USB debugging; some Android development builds force WebView debugging on.
- **Downloads:** HTTP(S) downloads go to `Downloads/twig` by default. Confirm the file
  name or choose another writable destination through Twig's own picker, including
  connected storage, with transfer progress and cancellation.
- **File uploads:** choose one or multiple files through Twig's own picker, including
  files on connected storage, according to the page's upload request.
- **Camera, microphone and location:** each bookmark has separate ask/allow/deny
  controls for its saved HTTPS origin. Capture and location also require Android
  permissions; temporary pages always ask. New bookmarks do not inherit permission
  grants, and changing a bookmark's origin resets them.
- **Prevent screenshots:** an independent per-site option, off by default, also
  controls recent-task previews after unlocking.
- **Run in background:** an opt-in setting, off by default, keeps a live website session
  under a foreground service with a notification, for media or other page tasks.
  Close the page or use the notification to end it. It improves background survival;
  it cannot guarantee uninterrupted execution under Android power management or a
  site's own pause logic.

Cookies and login state persist in Twig's shared WebView profile, separate from your
other browsers; bookmarks are not isolated account containers. Website compatibility
depends on the installed system WebView.

When Twig's master password is enabled, opening a website or returning to its task
requires unlocking Twig if it is locked. Cookies and the live page are retained,
and opted-in background sessions continue. While locked, website windows block screenshots
and recent-task previews; after unlocking, the site's **Prevent screenshots** setting applies.

### Compare

- **Directory compare** in the Beyond Compare style: two trees aligned row by row with
  a status column between them — side by side in landscape, one side at a time in
  portrait with the status column always visible. Either side can be *any* source, and
  small files are compared by content rather than by size and timestamp. Full-content
  comparison can also be requested for larger files. Read failures are reported as
  errors and excluded from sync.
- **One-way sync** in a direction you pick explicitly (left or right, never "the active
  side"), **incremental** by default — push what is missing or different and leave the
  target's extra files alone — or **mirror**, which deletes them. Items that are newer
  on the target are listed separately and are *not* overwritten unless you tick them.
- **Saved comparisons** sit on the tree next to favourites, so a pair you check often is
  one tap away and can be synced straight from its row.
- **Text compare** with two columns, synchronised horizontal scrolling and per-hunk merge
  arrows; **image compare** side by side with linked zoom and pan.
- **Binary compare in hex.** Whether a file is binary is only knowable after reading it, so a
  pair that turns out to be binary — or too large for the text view — is handed on to the hex
  comparison instead of dead-ending. Offsets are aligned as they are, with no resynchronisation,
  which is the honest answer for firmware and patched binaries; the shorter side is padded with
  blank rows so the difference at the tail stays reachable.

### Viewers and player

Every viewer reads through `FsRegistry`, so local, in-archive and remote files
all work the same way.

- Text viewer with hand-written syntax highlighting, multiple themes, pinch-to-zoom and
  a Markdown preview mode
- **Text encoding is a preference, not a guess**: one decoder (`TextCodec`) tries BOM,
  then strict UTF-8, then the fallbacks you ordered (GB18030 by default). Editing is not
  limited to UTF-8 — a file is written back in the encoding it was read with, and if the
  original encoding cannot represent something you typed, Twig asks instead of silently
  substituting `?`
- Hex viewer with virtual scrolling, a draggable scrollbar and text/HEX search
- **PDF reader** on the platform renderer: continuous or single-page scrolling,
  double-tap to crop the page margins away, text selection, and full-text search
  where the system provides it (Android 15+)
- Image viewer with downsampling, horizontal or vertical swipes to switch images,
  plus a slideshow that starts on the first image found while still scanning
- **Video/audio player** (media3 + FFmpeg software decoding) supporting AVI,
  M2TS, HDMV audio tracks and PGS subtitles, with two-stage
  automatic fallback when a system decoder crashes. Seeking in M2TS can be slow
- **Episode auto-play**: the queue comes from the server on Jellyfin/Emby, and
  from filename numbering (`SxxExx`, `E01`, bare digits) everywhere else. Previous
  and next buttons appear only when a queue could actually be worked out
- **Opening and ending skips** apply to videos recognised as one series with at least
  two episodes; episode numbers need not be consecutive. Tap the opening button at
  the end of the opening, or the ending button at the start of the credits, to remember
  boundaries shared by the series. Tap a marked button again to clear it.
  Playback starting inside the opening skips to its end; reaching the ending boundary
  advances to the next episode, or finishes playback on the last episode.
- **Continue watching** has its own tab in History for unfinished local videos and
  videos on saved file-server connections. Resume, start over or locate their folder;
  removing an entry hides it without deleting its saved position. Opening or seeking
  must be followed by ten continuous seconds of playback before progress is updated.
- **Playback preferences** remember manually selected audio/subtitle tracks and
  picture scaling for the next episode and when reopening a file
- **Playback controls** include swipe seeking, brightness/volume gestures and
  temporary speed-up while holding the screen; embedded and external subtitles
  can be selected alongside audio tracks

#### Dedicated music player

- **Music across sources.** Play local, network and SAF tracks, and organise them
  in playlists. Files inside mounted archives and backups can play in temporary
  queues while their source remains mounted.
- **Cover art and lyrics.** The interface takes its colours from the cover; tap to
  switch between cover and lyrics, or see both side by side in landscape. Swipe
  horizontally on the cover to change tracks, or vertically to adjust media volume
  with an in-player percentage display.
- **Synchronised bilingual lyrics** from sidecar LRC files, embedded tags or the
  media server. Tap a timed lyric to seek to that line; the waveform also acts as
  a seek bar.
- **Playlist management** with favourites, name/metadata search, sorting, manual
  drag-to-reorder, shuffle and repeat. Export saved playlists as `.m3u8` files.
- **Background playback** with notification, lock-screen and headset/Bluetooth
  controls. The notification includes Like and Exit, and a native playback timer where
  the system displays it. The last queue can be restored when reopening the player.
  Exit through the player menu, notification, or by long-pressing Shuffle.

### Media servers (Jellyfin / Emby)

One implementation covers both — Emby is where Jellyfin was forked from, so the
endpoints share a lineage — and it adds **no dependencies**: OkHttp was already
here, and `org.json` ships with Android.

- The tree mirrors **the libraries that actually exist on the server**, under the
  names you gave them — not a fixed list of types. A movie library expands to
  every movie, a TV library to every series (seasons only when there is more than
  one), a music library to Albums / Album artists / Artists / Folders.
- **Playback position syncs both ways.** Continue Watching is the server's list,
  not a second private copy, and it keeps the server's ordering rather than the
  sort you picked for file browsing.
- **Posters, tags, lyrics and external subtitles all come from the API.** Reading
  the media file to get any of this used to cost seconds per track over a network.
  Continue Watching shows landscape stills; libraries show portrait posters at
  their true aspect ratio.
- **Search uses the server's index**, so a film scraped to a localised title is
  still found under its original name.
- Read-only by design: there is no upload API, and `DELETE /Items/{id}` would
  delete the real file on the server.

### Terminal

- **Local, SSH and privileged shells.** Open a local system shell or an SSH session
  in the selected directory, with multiple sessions and a switcher at the top.
  Root and Shizuku shells have a separate, clearly labelled entry. Local shells
  use a real PTY, supporting interactive terminal programs.
- **From files to commands and back.** “Open terminal here” starts at the directory
  you are browsing. “Locate current directory” brings the shell's working directory
  back into the file tree, including remote SSH paths when the shell reports its
  current directory.
- **Extra keys for touch screens.** Two rows put `CTRL`, `ALT`, `SHIFT`, `ESC`, `TAB`,
  arrows, Home/End, Page Up/Down and common shell symbols within reach. Tap `SHIFT`,
  then an arrow or `TAB`, to send the combination; hold an arrow to repeat it.
- **Shift combinations for interactive tools.** Examples include `Shift+←` to open
  a question for answering in [Codex], and `Shift+Tab` to cycle permission modes in [Claude Code].
- **A dedicated pipe key (`|`).** Connect one command's output to the next—for
  example, `ls -l | grep .apk`—without switching to the phone keyboard's symbol
  pages. Pipelines make it practical to filter logs, search output and combine
  command-line tools.
- **Soft-keyboard control.** A toolbar button explicitly shows or hides the Android
  keyboard. Tapping a prompt opens it; programs that hide the cursor avoid opening
  it automatically. Keyboard and orientation changes resize the terminal, including
  the remote SSH PTY.
- **Customise the terminal.** Import a font and a Termux-compatible
  `colors.properties` scheme, or use the defaults. Pinch to change text size;
  terminal colours also apply to the extra-key bar, independently of the app theme.
- **Keep screen on** can keep the session visible; the display dims after the system's
  idle timeout and returns to its previous brightness on interaction.
- **Command shortcuts**: attach a command to an SFTP directory, server or local directory,
  run it in a terminal or silently in the background, and pin it to the home screen.
  Local commands use the app uid unless the default-off **Use privileged session** is selected.

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

### Security

- Passwords, API keys, tokens and key passphrases are **encrypted at rest**. A random
  256-bit data key encrypts the fields and is itself wrapped either by a hardware-backed
  Keystore key (the default, invisible) or by scrypt over a master password. Turning the
  master password on or off only rewraps that one key — not a byte of field ciphertext is
  rewritten. No new dependency: scrypt comes from the Bouncy Castle already in the APK.
- The master password is an **app lock checked at every entry point** — the main UI,
  "open with Twig", "copy to…", the file picker, the terminal shortcut and the remote
  command shortcuts, launcher-configured actions, and website tasks. **Lock** drops the
  key from memory while music, terminals and sharing keep running. Directly pinned file
  shortcuts are an exception: local and SAF files need no decrypted secret
  to open, so those skip the prompt entirely — only a shortcut to a server file gates,
  since reconnecting there does need the saved (encrypted) credentials.
- **Fingerprint unlock** through the platform `BiometricPrompt` (not androidx.biometric —
  zero APK cost) exists alongside the master password, which stays the root key.
- **Config backup** (`.twigbak`): connections, settings and website bookmarks (including
  scripts, styles and rules) as JSON, optionally encrypted
  with a separate export password, and written through Twig's own directory picker — so a
  backup can go straight to SMB, WebDAV or S3. Import merges rather than replaces, and
  tokens, host keys and the data key are never exported. Website bookmark lists are
  restored as a whole; their Basic credentials and headers are re-encrypted for the
  destination device. Folder-specific sorting/view settings and website defaults are
  included. WebView cookies, login sessions and private-space files are not included.

---

## Modules

| Module | Contents |
|---|---|
| `:core-fs` | Pure JVM: `XFile` / `FileSystem` / `FsRegistry` / `CopyEngine` |
| `:fs-local` | `LocalFileSystem` plus `priv/` — the root/Shizuku privileged shell fallback |
| `:fs-archive` | `ArchiveFileSystem` + zip (read/write, encryption) / 7z / tar / gz·xz·bz2·zst single-stream compression, and `ArchiveWriter` |
| `:fs-archive-rar` | `RarFileSystem` (RAR4 + RAR5) — a separate module purely so the `libre` build can drop it |
| `:fs-network` | `FtpFileSystem` / `SftpFileSystem` / `WebDavFileSystem` / `S3FileSystem` / `JellyfinFileSystem` |
| `:fs-smb` | `SmbFileSystem` — libsmb2 via NDK/JNI; `Smb1FileSystem` — opt-in legacy jcifs-ng backend |
| `:fs-restic` | restic repository reader (pure Kotlin) |
| `:fs-zstd` | `NativeZstd` + `ZstdInputStream` — block and streaming zstd decoding via JNI |
| `:git-lite` | Miniature git in pure Kotlin |
| `:app` | Dual-pane UI, viewers, terminal, git views, treemap, Wi-Fi sharing, websites and page customization |

---

## Building

Use JDK 21 and the Android SDK/NDK versions pinned in `app/build.gradle.kts`.

```bash
./gradlew :app:assembleFullDebug       # installable debug APK (with RAR)
./gradlew :app:assembleFullRelease     # R8-optimised release (with RAR)
./gradlew :app:assembleLibreRelease    # F-Droid variant (no RAR, 100% FLOSS)
./gradlew :app:bundleLibreRelease     # libre AAB for store upload
./gradlew :app:assembleFullRelease -PabiSplit=true  # three single-ABI APKs
```

By default, release builds are signed only if a `keystore.properties` file exists at the repo
root (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`). Without it the
release tasks emit an **unsigned** APK — `app-<flavor>-release-unsigned.apk` —
rather than falling back to the public Android debug key. Sign it yourself, or use
a debug build for local testing. To explicitly sign a release build with the debug
key, set `debugSign=true` in `local.properties` or pass `-PdebugSign=true` (the
command-line option takes precedence). Published builds should use your own release key.

Tests:

```bash
./gradlew :core-fs:test :fs-archive:test :fs-network:test :fs-restic:test :git-lite:test
./gradlew :app:testFullReleaseUnitTest
```

Notes: build-tools are pinned to 36.1.0; only `arm64-v8a` and `x86_64` ABIs are
built by default. The opt-in ABI split also builds experimental `armeabi-v7a` APKs.
R8 is enabled, and JNI entry points for libsmb2 and zstd are kept by name.

For a release rehearsal, run `tools/release.sh` from a clean, committed tree with
release signing configured. It builds and verifies all five APKs and generates
bilingual release notes under `build/release/<version>/`, without tagging or pushing.
`tools/release.sh --publish` also tags, pushes and creates the GitHub release.
Both modes build from a fresh detached git checkout, with Gradle build and
configuration caches and Kotlin incremental compilation disabled. Downloaded
dependencies remain available; add `--offline` to require cached dependencies.

To rebuild an existing release, run `tools/release.sh --tag v1.16.0` (optionally
with `--offline`). This uses the tag even if the current checkout has newer or
uncommitted work. It keeps the original release files and writes the five verified
APKs, notes and source commit to `build/release/1.16.0/rebuild.<suffix>/`.
The build checkout stays under `build/release-checkouts/` for inspection, including
R8 mappings. Builds use this machine's `local.properties` and `keystore.properties`;
use an absolute `storeFile` path in the latter. Tag rebuilds cannot publish or
replace remote release assets.

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

Implementation decisions and the reasoning behind them are recorded in
[AGENTS.md](AGENTS.md), and the long list of bugs that were expensive to find lives one
file per area under [docs/lessons/](docs/lessons/). Read the matching file before
changing subsystems like the terminal, thumbnails or the TS demuxer.

---

## Roadmap

- `:fs-cloud` — Google Drive / Dropbox / OneDrive over plain REST, no vendor SDKs
- Global search
- Size: drop the 1.2 MB of Bouncy Castle `picnic` lookup tables, or replace the
  whole dependency with Conscrypt — either way it needs on-device verification
  that the SSH handshake still finds X25519

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
- **0.8 / 0.9** — The tree model settled: in-place expansion, expand-to-connect,
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

- **1.1** — **Jellyfin and Emby** as a first-class source: the tree mirrors the
  server's own libraries, playback position syncs back, posters/tags/lyrics/
  subtitles come from the API instead of from the media file, search uses the
  server's index, and episodes auto-play. No new dependencies. Episode auto-play
  also works on ordinary sources by reading the numbering out of filenames, and
  write actions now disappear on read-only sources instead of failing when tapped.
- **1.2** — **Directory-compare sync**: incremental or mirror, with the direction pinned
  to "left" and "right" rather than the active side, and files that are newer on the
  target confirmed separately. **RAR5** support (junrar 8.1.0 — which also fixed a wrong
  password being reported as correct), and the `libre` / `full` flavour split that moves
  junrar into its own module so the F-Droid build is 100% FLOSS. The navigation bar now
  takes the colour of whatever page sits above it, instead of being a black strip.
- **1.3** — **Security**: saved credentials encrypted behind a two-layer Keystore key,
  the master password promoted to an app-wide lock guarding all six entry points,
  fingerprint unlock, and `.twigbak` config backup written through Twig's own picker
  (so it can land on SMB or WebDAV). **SD cards and USB drives** as root entries. Row
  height and text size split into independent preferences and the one-off settings pulled
  out of the menu into the settings page; adaptive and themed launcher icon; a Terminal
  entry on the app icon's long-press menu.
- **1.4** — A document tree granted by another app is named after that app and wears its
  icon instead of showing a raw document id, and a grant can be handed back from the
  sidebar.
- **1.5** — **Go to path** from any root row; favourites and saved comparisons can point
  inside a document tree; terminal accessory bar polish (hold an arrow to repeat, one
  shared text size) and the emulator size surviving a screen off/on cycle.
- **1.6** — **tar, and gz/xz/bz2/zst as single-file compression.** Entries in a tar are read
  in place by slicing, so an archive nested inside one opens without being unpacked first and
  video inside it can seek; the single-stream formats mount as a one-entry archive, so
  `foo.tar.gz` opens to `foo.tar` and expands again — `.tgz`/`.txz`/`.tbz2`/`.tzst` included.
  Where a container does not record the original size, the size is left blank rather than
  reading `0 B`.
- **1.7** — A built-in **PDF reader**: continuous or single-page scrolling, double-tap to
  crop the page margins away, text selection, and full-text search where the system provides
  it (Android 15+). **Hex comparison** for pairs that turn out to be binary or too large for
  the text view. **Split-APK bundles** (`.xapk`, `.apks`, `.apkm`) install through
  `PackageInstaller`. SMB lists every share when the share name is left blank, and FTP, SFTP
  and S3 connections can open at a start path. GB18030 replaces GBK in the encoding
  candidates. Fixes worth naming: AVI audio reframing and B-frame order, 4K network playback
  exhausting the heap, and git worktrees over SSH.
- **1.8** — Files can pin their own **home-screen shortcut**, not just directories: pick how
  it opens — automatic dispatch, as text, as hex, or with one specific app chosen at pin time,
  since a shortcut can't re-show a resolver on every tap — and the icon reuses an
  already-cached thumbnail when one exists. The master-password gate only fires when the
  target actually needs a decrypted secret (server files reconnect through the saved,
  encrypted credentials; local and SAF files never do). Shortcuts run in their own task, so
  opening one no longer routes through — or back-navigates into — the main screen.
- **1.14** — **Website bookmarks** with per-site display controls, scripts, styles,
  authentication, downloads, page tools and optional background sessions. Website tasks
  respect the app lock. **Android/data** access through system-supported SAF grants,
  readable system-directory entries, configurable launcher shortcuts, batch rename with
  replacement and numbering, multi-file sharing and selection properties. The path bar
  supports editing by long press or double tap; pane switching and fullscreen cutouts
  are improved.
- **1.15** — Local trash, expanded batch-rename operations and templates, and clearer
  read failures in file comparison. Shared website defaults, temporary web pages, file
  uploads and per-site screenshot settings. Continue Watching in History, saved progress
  in video thumbnails and a treemap zoom-reset button.
- **1.16** — Private space, per-pane root visibility, folder-specific sorting and views
  with optional inheritance, shared-text saving and a dual-pane destination picker.
  Background deletion, batch trash recovery, optional bottom toolbar and completion
  alerts. Series opening/ending skips, music volume swipes and notification exit/timer,
  per-site capture/location permissions, and idle terminal dimming.

</details>
