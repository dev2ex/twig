# Other backends and odds and ends

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [AGENTS.md](../../AGENTS.md); the other sections live
> beside this file in [docs/lessons/](.).

- **★ A launcher shortcut icon must not rely on `android:tint`** (2026-08-27, `ShortcutIcons`):
  hand the launcher an icon **by resource** (`IconCompat.createWithResource`, or
  `android:icon` in `res/xml/shortcuts.xml`) and it loads the drawable itself — **the vector's
  tint is dropped**. Every icon in this app draws its paths with
  `fillColor="@android:color/white"` and takes its colour purely from that tint (see the
  media-server icon lesson), so what lands on the desktop is a **white glyph**, invisible on the
  launcher's own light icon plate. Two fixes went in before the cause was understood: the music
  shortcut was moved from `ic_music_note` (white by design — it is the dark action strip's and the
  notification's icon) to `ic_file_audio`, **which is white paths + a purple tint and therefore
  came out white too**.
  - **Shortcuts created in code go through `ShortcutIcons.of()`**, which renders the drawable into
    a bitmap so the colour is ours; there are three pin sites (`RemoteCmd.pin` /
    `MainActivity.pinMusicShortcut` / `PaneFragment.pinFileShortcut`) and the last one hands over
    arbitrary `FileIcons.baseIconRes` icons, so baking is the only approach that covers them all.
  - **Static shortcuts cannot be baked** (the launcher reads `android:icon` itself), so their
    drawables must carry **literal** fill colours — `ic_shortcut_music` / `ic_shortcut_terminal`.
  - **★ The long-press menu is published dynamically, not from `res/xml/shortcuts.xml`**
    (2026-08-27, `ui/Shortcuts`): a static shortcut's labels are resource references and
    **the launcher resolves them in the system locale**, while Twig has its own language
    setting (`LanguagePref`, a per-app locale). With the system in English and the app set to
    Chinese, the menu came out English and nothing in the app could change it (measured:
    `cmd locale get-app-locales com.twig.app` = `[zh]`, `persist.sys.locale` = `en-US`).
    Manifest shortcuts are **immutable** and cannot be updated through the API, so following the
    app's language means dropping them and publishing from an Activity with already-resolved
    text; `MainActivity.onCreate` republishes, which a language change triggers anyway (it
    recreates every Activity). The cost is that the menu is empty until Twig has been opened once.
    Two preconditions cost a round each, and **both look identical from the desktop — an empty
    menu**, which is why that call must never swallow its exception (it is the only clue there is):
    - **A dynamic shortcut must name an owning activity, and it must be a launcher activity**
      (`setActivity`), or it is refused with `Cannot publish shortcut: target activity is not set`.
      `requestPinShortcut` fills that in for us, which is why the pinned shortcuts had always
      worked without it.
    - **★ The ids of the manifest shortcuts cannot be reused**: a manifest shortcut's id stays
      reserved for as long as some launcher keeps it pinned, **even after the declaration is
      gone** — `IllegalArgumentException: Manifest shortcut ID=music may not be manipulated via
      APIs`, and the refusal takes the whole batch with it (so the other entry disappears too).
      Hence `open_music` / `open_terminal`.
  - **Shortcut targets are entry points that skip the main UI**, so they must call
    `SecurityUi.gate` (pinned by `EntryGateTest`) — but they must **not** be exported: the system
    launches a shortcut as this app, while exporting `TerminalActivity` would hand every app on
    the device "open a session with the saved credentials".
  - **Already-pinned shortcuts do not change**: launchers cache the icon and re-pinning the same id
    updates little or nothing, so testing requires deleting the shortcut and adding it again.
  - `ShortcutIconTest` pins both halves: the **drawn pixel colour** of the icon actually handed over
    (as in `IconTintLeakTest` — "is a tint set" would be green on the broken version) and "no
    drawable named in `shortcuts.xml` relies on a tint". ★ That XML check must strip comments first,
    or the drawable's own comment explaining the rule matches `android:tint`.

- **Launcher gesture factories must return results without file descriptors**
  (2026-10-19, `CreateShortcutActivity`). Selecting **Twig shortcut** in Nova's
  gesture editor crashed `ChooseActionIntentActivity.onActivityResult` with
  `Not allowed to write file descriptors here`, inside `Parcel.LazyValue.writeToParcel`
  while forwarding the result through `finishActivity`. The initial tests only read
  the returned name and launch Intent under Robolectric, which did not exercise
  Android's lazy cross-process Bundle forwarding. A two-Activity probe on the phone
  reproduced exactly the same failure with the original APK.
  The factory used `ShortcutManagerCompat.createShortcutResultIntent` with a rendered
  bitmap icon; bitmap parceling can carry an ashmem file descriptor. A gesture editor
  may pass the entire result onwards without unpacking it, so even valid shortcut
  metadata is not necessarily safe to forward. Return only the legacy shortcut name,
  opaque-id launch Intent and `ShortcutIconResource` instead. The icon resource must
  have literal colors (`ic_shortcut_launcher` / `ic_shortcut_terminal`), as above.
  Do not include bitmap extras or a platform pin request in this result. Ordinary
  system-managed pinned/dynamic shortcuts keep their existing bitmap path.
  `LauncherShortcutTest` checks the complete result-extra set, rather than just the
  fields the caller normally reads. Device forwarding is still required to establish
  compatibility; Robolectric cannot prove native Parcel file-descriptor behavior.
  The user verified successful Nova shortcut creation after this change.

- **SMB**: `smb2_context` is not thread-safe, so `NativeSmbClient` serializes with a reentrant lock,
  and streaming reads/writes hold the lock until the stream is closed. `exec()` (formerly `run`)
  dispatches to a single-threaded executor — **do not name a member function `run`/`let` or any
  other stdlib scope function**: inside an anonymous inner class it resolves to the standard library
  version and runs on the calling thread, bypassing serialization → concurrent entry into libsmb2 →
  crash (hit while reading thumbnails concurrently).
- **zstd/aircompressor**: uses `Unsafe`, which is nominally risky on newer devices; keep an eye on
  restic/zstd when changing test hardware.
- **Do not SSH into servers to read logs**: automatic mode blocks "remote shell reads against
  unauthorized hosts". If a remote diagnosis is needed, ask the user to run it.

- **★ Saving a connection must not move it in the list** (2026-09-09, `ConnectionStore.save`):
  the stored order **is** the order the sidebar lists servers in (`PaneViewModel.addGroup`
  walks `ConnectionStore.all` as-is), and `save` used to be "drop the entry with this label,
  append the new one" — so any write reordered the list. Most writes are not user actions at
  all: `Connections.rememberHostKey` (SFTP TOFU) and `rememberAuth` (Jellyfin/Emby token) fire
  on the **first successful connect**, i.e. the moment you expand the row. The report was
  "import a backup, expand the first server, it jumps to the bottom" — the backup deliberately
  exports `token` / `userId` / `hostKey` empty (`Backup.kt`), so after an import *every* server
  rewrites itself once, which is why the reshuffle showed up there and nowhere else.
  `save` now replaces in place and only appends a genuinely new label; editing a server goes
  through `ConnectionStore.replace(old, conn)` instead of remove + save, because an edit can
  change `label()` itself (host, port and the root directory are all part of it) and matching
  on the new label would append. Pinned by `ConnectionStoreOrderTest`.

- **★ `ContentProvider.requireContext()` is API 30, and nothing warns you** (2026-09-09,
  `StreamProvider`): minSdk is 24, the call compiles, R8 keeps it, and every device from
  API 30 up runs it fine — so it shipped in 1.6.0 and was only reported at 1.9.0, from an
  Android 9 phone. The idiom is borrowed from `Fragment`, where `requireContext()` has
  always existed; on `ContentProvider` it was added much later. Below API 30 **every**
  provider entry point dies with `NoSuchMethodError: No virtual method requireContext()`,
  and it dies on **the caller's** thread — media3's `MetadataRetriever` / ExoPlayer's
  loader calling `openFileDescriptor()` — which is outside every `runCatching` we own, so
  the process is killed. The report read "tapping *track info* in the music page shows
  nothing and closes the music page": `FileInfo` builds the media section through a
  `content://` from `StreamProvider`, so the dialog never got a chance to appear.
  A provider uses `requireNotNull(context)`; `StreamProvider.ctx()` is now the single
  place that reads it. `ProviderRequireContextTest` pins the rule as a source check
  (lint's `NewApi` cannot be enforced in `:app` — the module has a large backlog of
  unrelated lint errors — and no API < 30 device is in the test matrix).
  ★ The general lesson: an "obviously safe" framework call inherited from a *different*
  base class needs its `since` checked against minSdk, because the compiler, R8 and every
  modern test device all stay silent.
- **SMB refuses `append = true`** rather than silently truncating (2026-09-17 review): the
  native open always truncates, and nothing asks for append today — a future resumable copy
  would otherwise wipe what it meant to extend. WebDAV already refused the same way.
- **SMB1 is a separate, explicit connection mode** (`Smb1FileSystem`, jcifs-ng): the
  default stays on libsmb2 (SMB2/3), with no downgrade after authentication or negotiation
  failure. Each legacy connection owns its CIFSContext, closes it when dropped, and pins
  both min/max dialects to SMB1. Stream owners live until the stream closes. jCIFS expects
  literal path names, **not percent-encoded names**: encoding before constructing SmbFile
  really creates `%E4...` and `%20` files on the server. The live test against an NT1-only
  Samba checks Unicode, spaces, `#`, `%`, append, seek, share enumeration and subdirectory
  mounts; it is opt-in via `TWIG_SMB1_TEST_HOST` (optional `_USER` / `_PASSWORD`).
  The three-argument NtlmPasswordAuthenticator constructor always chooses USER, including
  for `guest`: explicit passwordless guest access needs AuthenticationType.GUEST. Keep
  `allowGuestFallback=false` so a named login cannot silently connect as somebody else.
- **SMB1 cold connections can wait on the authentication domain, even with an IP host**
  (2026-10-21, issue #18). The report was slow first access and fast subsequent access
  on two servers. Server warm-up was the initial hypothesis, but a standalone NT1-only
  `dperson/samba` fixture reproduced the wait with the current backend: jcifs-ng's DFS
  discovery tried to resolve the supplied `WORKGROUP` authentication domain. With DNS
  unavailable for that name, NetBIOS retries took about 6 seconds; the negative DFS
  cache made subsequent listings fast. Server and client both using `WORKGROUP` did
  not prevent it. Mounting a share directly only moved the wait from listing into
  `connect()`. An ordinary workgroup is not an authentication domain: new SMB
  connections now leave the domain blank, and saving a blank field must not put
  `WORKGROUP` back. The shared dialog, saved-connection default and backend config
  agree on this. Keep explicit saved domains (including `WORKGROUP`) and the old JSON
  fallback for records without a domain field; do not silently migrate credentials or
  globally disable DFS. Clearing the domain or disabling DFS reduced cold access from
  about 6.13 s to 0.12 s in the fixture; all six existing SMB1 unit/live tests passed.
  This establishes the mechanism, not the reporter's exact network cause: Android
  confirmation is still needed. `ConnectionStoreOrderTest` covers domain preservation.
- **SMB1 share-root attributes do not describe permission to create children**
  (2026-10-22, issue #18). Connecting to a server with no share configured made its
  enumerated shares appear read-only in Twig, while their subdirectories were writable.
  The same `twig-smb1-test` Samba container reproduced it: actual mkdir and file writes
  at the share root succeeded. jcifs-ng's `ShareEnumIterator` supplies synthetic
  `ATTR_READONLY | ATTR_DIRECTORY` attributes, and `SmbFile.exists()` also leaves those
  defaults for `TYPE_SHARE` rather than querying ordinary directory attributes.
  Passing `SmbFile.canWrite()` directly into `XFile` therefore disabled the UI's
  `isWritableDir()` gate. Direct-share tests missed this because `root()` defaults to
  writable; `stat("/")` exposed the same bad flag even on a direct mount. Treat
  `TYPE_SHARE` as a possible write destination and let the server enforce actual
  permissions, while retaining ordinary entry flags and `requireEntry` protection
  against deleting or renaming the share itself. Live regressions cover both mount
  modes, the UI gate before and after stat, actual root writes, subdirectory metadata
  and share deletion/rename rejection. Share enumeration alone is not a write test.
- **Server XML goes through `SafeXml`** (WebDAV multistatus, S3 listings): DOCTYPE and external
  entities off, each feature set on its own because Android's factory throws on unknown ones.
  On the JVM the old code really did expand `<!ENTITY x SYSTEM "file:…">` into a file name
  (`WebDavFileSystemTest.externalEntitiesAreNotResolved` fails on it); Android's parser does
  not today, which is an implementation detail, not a guarantee.
- **restic base64 reports a bad character as corrupt key data** — it used to crash with
  ArrayIndexOutOfBounds on anything non-ASCII.

- **Remote system sharing needs preparation progress, separately from streaming viewers**
  (2026-10-27, issue #39). Sharing a large SFTP file to WeChat left the user staring
  at a spinner until the recipient appeared. `OpenFiles.share` handed out the remote
  URI directly, so reads happened on the recipient/system side without Twig progress.
  A batch of local files and known-size remote files up to 512 KiB shares directly.
  A larger or unknown-size remote file sends the batch through preparation, with a
  cancellable "Preparing to share" dialog; leave "Open with"
  streaming so external players can seek without a complete download. Execute it as
  `Transfers.Work.Share` in the existing foreground `TransferService`, with the existing
  `TransferBox` for progress, cancellation, backgrounding and notification reopening.
  The headless `ShareTransfers` fragment only attaches that box and hands off completed
  results while resumed, unlocked and interactive. Rotation does not own/restart the job.
  Queue completed shares separately from ordinary transfer results, preserving each cache
  lease until handoff; a subsequent copy must not overwrite a pending share.
  Share preparation suppresses its success toast, but preserves ordinary completion
  notification/vibration rules and the shared ongoing foreground progress notification.
  An entirely cached preparation does not vibrate, even when completion vibration is enabled.
  Preparation errors still show a toast.
  Completed cache files are immutable and keyed by scheme, path, display name, size
  and source modification time. Reuse requires positive size/time and matching cached
  length; missing metadata means prepare afresh. Write to a unique partial file and
  publish only after EOF and length checks. Preserve display names in the signed URIs.
  On cancellation or failure remove partial files, while complete entries remain reusable
  even if a later file in the selection fails. Hits and lease release refresh the
  24-hour idle retention; prune expired entries on the next preparation, excluding files
  held by active preparations, through `CacheDirs.trim` with an age floor and protected
  files. Stream copying reuses `CopyEngine.pipe`, including its progress/cancellation
  and overlapping reads/writes. Consuming the result after handoff only drops its lease,
  preserving files for delayed or repeated recipient reads. Cancellation is checked
  by the shared copy pipeline; stream closure can still wait for a blocked backend read.
  Regression tests cover mixed/local sources, duplicate names/selections, metadata changes,
  unknown sizes/times, truncated reads/cache, failures, cancellation, reuse, expiry,
  retention of leased entries, rotation and deferred handoff after resume.
