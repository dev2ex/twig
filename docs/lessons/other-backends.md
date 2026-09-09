# Other backends and odds and ends

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
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
