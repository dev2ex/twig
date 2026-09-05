# Jellyfin / Emby

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

**(★ 2026-08-17, `JellyfinFileSystem`)** Zero new dependencies — HTTP uses the existing
OkHttp and JSON uses `compileOnly("org.json")` (present at runtime on Android, same trick as
`fs-restic`, APK delta 0). **One implementation serves both**: Emby is the upstream fork
source of Jellyfin, and `/Users/{uid}/Views`, `/Items`, `Items/Resume`, `Items/Latest`,
`/Items/{id}/Download` and `/Sessions/Playing/…` are identically named on both; the `emby`
flag is only used to get the display name right. Settled decisions:

- **It is a virtual tree** (items are GUIDs, not paths), the same idea as `AppsFileSystem`
  using package names as paths: `path` keeps the full ancestor chain with the item id as the
  last segment, and `displayName` carries the human-readable name.
  ★ **The path must include the virtual directory prefix, not just the id** — the same movie
  appears under both "Continue watching" and "Movies", and a row's key is
  `f:<scheme>:<path>`; a collision makes DiffUtil pick the wrong row, with the symptom
  "expanding it in one place works, in the other it is empty" (same as the
  `FileNode.keyPrefix` lesson).
- **★ "Emby's Continue watching is always empty" (2026-08-17, settled against a real server
  after two wrong diagnoses)**: there were in fact **two independent** root causes, each
  sufficient on its own, with identical symptoms:
  - **The `/Sessions/Playing…` report was missing `PlaySessionId`** → Emby passes it straight
    into `SessionInfo.GetOrAddPlaySessionInfo` as a `ConcurrentDictionary` key, and null means
    `ArgumentNullException: Value cannot be null. (Parameter 'key')` — externally **HTTP 400
    on all three endpoints**. So **progress was never synced at all** and the server had not a
    single resume record — which looks like "the list query is broken", sending the
    investigation the wrong way from the start. Jellyfin tolerates the missing field, so it
    only blows up on Emby.
  - **The Resume query was missing `MediaTypes`** → Emby always answers **200 with an empty
    list**; with it, everything works. Jellyfin works either way. Audio needs it too
    (`Video,Audio`), otherwise resume for songs/audiobooks is missed.
  - **Record of the wrong diagnoses (do not repeat them)**: round one concluded "it is missing
    `Recursive=true`", on the grounds that "Emby's Resume goes through the generic items query,
    which is non-recursive by default" — **a smooth line of reasoning, and wrong**. The truth
    table printed from a real server: `MediaTypes` present or absent is the only deciding
    factor; `Recursive` returns results either way. Round two then reasoned that "omitting
    `MediaTypes` can only be broader, not narrower" — **wrong again**. Other variables ruled
    out in the same round: the value of `Fields`, the `/emby` path prefix (routing works
    without it) and `Limit`. The lesson matches the S3 encoding one: **this class of "no error,
    wrong answer" problem cannot be converged on by reasoning** —
    `docker run emby/embyserver`, create one resume record, and it is settled in a single
    round (`MinResumeDurationSeconds` defaults to **300 seconds**, so the test clip must be
    longer than 5 minutes or it never enters the resume list — that one nearly became the
    third misdiagnosis).
  - **Regression tests must verify by inversion**:
    `JellyfinLiveTest.after reporting progress, it shows up in Continue watching` closes the
    loop on both bugs (write it, then read it back); unit tests can only prove "the bytes we
    send match what we expect", not that the chain works. Verified by inversion: removing
    `MediaTypes` fails one, removing `PlaySessionId` fails two, restoring both is green.
- **★ The tree's shape follows how the server is actually organized (rework 2026-08-19)**:
  it used to be twelve hard-coded entries (Movies/Shows/Photos/Music/four "Latest"/…), which
  **assumed the server's organization**: a server with no music library still showed "Music"
  and it was empty; two libraries of the same type got merged into one; library names
  ("J-Drama", "Documentaries") were lost entirely. Now:
  ```
  Continue watching / Playlists / Collections / Latest →per library / <lib1> <lib2> … / Folders →per library
  ```
  Everything after the first entry **only appears if the server actually has it**, library
  names come from the server's `Name` and the path is `/lib/<library id>`.
- **★ Opening a library always fetches that type's items recursively; it does not show
  physical folders** (`libraryItems`).
  ⚠️ We got this wrong once: the first version used the "library root"
  (`ParentId=<library>` with no type filter), which **looked perfectly right on my own test
  library, which had no subdirectories** — but real libraries are organized by year or region,
  so the library root returns `Folder/2020` and `Folder/Japanese drama`, i.e. **the folders
  themselves**, and you have to descend level by level to reach a film.
  **Build test data with hierarchy**, otherwise this class of "invisible on a flat library"
  defect ships to users.

  | Library type | The library root gives | We now use |
  |---|---|---|
  | Movies | `Folder/2020`… | `IncludeItemTypes=Movie&Recursive` |
  | Shows | `Folder/Japanese drama`… | `IncludeItemTypes=Series&Recursive` |
  | Photos | the top-level few | `PhotoAlbum&Recursive` (every "open it and look at pictures" folder) |
  | Music | physical folders | four fixed sections, see below |
  | Unknown type | — | still the library root (such a library has no "natural level") |

  - **A music library has four fixed sections** (matching the official clients' tabs):
    Albums / Album artists / Artists / Folders, with paths using an `@`-prefixed virtual
    segment (`@albums` etc.; item ids never start with `@`). Albums use
    `IncludeItemTypes=MusicAlbum&Recursive`, and the two artist sections use
    `/Artists/AlbumArtists` and `/Artists` (both need `ParentId` + `UserId`).
    **"Folders" wants every folder that directly contains music files** (same idea as photo
    albums, rather than "start from the library root and descend"), and the server's notion of
    "a folder containing music" is exactly `MusicAlbum` — so it is currently the same query as
    "Albums". The entry is kept because the two are **semantically** different; if we ever want
    real physical structure, `musicSection` is the single place to change.
  - **When a show has only one season, skip the season level** (`childrenOrSingleSeason`):
    one more tap with no information in it. Multi-season shows are still split by season.
    The cost is one extra request when expanding a single-season show.
- **★ Do not trust `IsFolder` alone to decide "is this a directory"**: dedicated endpoints
  like `/Artists` and `/Artists/AlbumArtists` **may omit the field entirely**, so trusting it
  turns artists and albums into "files" — they cannot be opened, and `withSizes` goes off to
  probe their byte size. The check is now `IsFolder || Type in CONTAINER_TYPES`
  (`MusicArtist`/`MusicAlbum`/`Series`/`Season`/`PhotoAlbum`/`BoxSet`/…).
- **Directories on a media server get icons by path** (`FileIcons.mediaDirIcon`): those
  directories are all virtual (albums / artists / libraries / playlists / …), and drawing a
  generic folder for all of them conveys nothing while taking up space. The type can only be
  **inferred from the path** — `XFile` has no "what is this" field, and asking the server just
  for an icon is not worth it; anything that cannot be inferred (shows and photo albums both
  look like `/lib/<library>/<id>`) keeps the folder icon. All reuse existing drawables.
- **★ Media-server directory icons need their own drawables; do not take an icon from
  elsewhere and tint it** (settled 2026-08-19, `ic_md_*`). `ic_play`/`ic_history`/`ic_queue`
  were drawn for the player toolbar and have `fillColor` hard-coded to black or white, so
  dropping them into the list makes them invisible in one theme or the other.
  - **The first fix (tinting at the call site with `imageTintList`) was wrong; do not repeat
    it**: to prevent RecyclerView recycling from leaking a tint, `bindFile` got a
    "reset before every bind" line, `b.icon.imageTintList = null` — and **every icon on screen
    turned white**. `ImageView.setImageTintList(null)` does **not** mean "no tint": null is
    actively applied to the drawable, `mHasDrawableTint` is unconditionally set to true, and
    `applyImageTint()` calls `mDrawable.mutate().setTintList(null)`, **erasing** the
    `android:tint` written in the drawable XML. And `ic_folder` (yellow) / `ic_file_*` (various
    colors) / `ic_storage` (green) all have `fillColor` set to `@android:color/white` with
    their color coming **only** from that tint — erasing it leaves plain white.
    ★ Rule: **never touch `imageTintList` while binding an icon**.
  - **Current design**: following the `ic_file_*` pattern, a dedicated `ic_md_*` set (always
    `fillColor` white, color entirely from `android:tint`, one value shared by both themes),
    using the existing palette (purple `#AB47BC`, green `#66BB6A`, blue `#42A5F5`,
    orange `#FFA000`, red `#EF5350`). The ImageView's tint is never touched, so recycling leaks
    **cannot happen**, and the tint-clearing line in `Thumbs.fill` was deleted too (it had the
    same erasing side effect).
  - **Only real "entry points" get a special icon**: Continue watching / Latest / Playlists /
    Albums / Artists. Libraries, shows, seasons and photo albums all use the **generic folder
    icon** (yellow, the same as local directories) — in the user's mind those are just
    directories, and a screen full of entry-point icons hides what matters.
  - The regression test `IconTintLeakTest` asserts by **drawing the icon into a Bitmap and
    measuring the most common opaque color** (`@GraphicsMode(NATIVE)`), not "was a tint set" —
    the latter only guards against this one implementation, while what the user sees is "the
    icon is white", and measuring the color is what matches. Inverted, it reports precisely
    `drawn as #FFFFFF, should be #26A69A`.
- **Directory covers only grow the icon once the image arrives** (2026-08-19,
  `Thumbs.bind(growPx=)`): sizing to the thumbnail dimension at bind time turns a screen of
  cover-less directories into a row of giant folder icons (users reported "it looks huge"), so
  the small icon is the placeholder and `fillAspect` changes width and height only when the
  image arrives — it already changes `layoutParams` inside a `post{}`, so changing the width
  too costs no extra layout pass. The grow value is passed via
  `view.setTag(R.id.thumb_grow, px)` (the default tag is already used by `Thumbs` for
  async-fill mismatch checks, so a separate id was needed).
- **★ After growing, the height cap must be relaxed as well**: the old cap
  `maxOf(box.height, w)` squeezed a 2:3 movie poster (`natural = 1.5w`) back to a square and
  cropped it with MATRIX — wasting the portrait image. The grow path now uses
  `maxOf(box.height, grow * 2)`, still capped so an extreme long strip cannot take half the
  screen. **Tree list only**; grid cells stay square `CENTER_CROP` (they mix 4:3 photos and
  16:9 video, and switching to 2:3 there is a separate design decision).
- **★★ Posters are on the "file" path, not the "directory" path** (the first version got this
  entirely wrong, 2026-08-19): **movies are files** (Jellyfin's `Movie` items have
  `IsFolder=false`), so every row in a movie library goes through `bindFile`'s `else` branch;
  only shows/seasons/albums/photo albums take the `isDir` path. The first version changed only
  the directory branch, so on a real device posters were still square — **and the test, which
  built fake movies with `isDir = true`, was green**. Same class of error as "a flat test
  library hides physical folders": **build data with the wrong shape and you are testing
  something else**. Both paths now pass `growPx` and both are pinned by tests. The file path
  already sized to a square placeholder with `size(thumbDp)` (pre-existing behavior), so the
  "no growing at bind time" assertion only holds for directories. **Only files where
  `Thumbs.hasCover(file)` get a grow** — ordinary video/image thumbnails keep the existing
  "crop if too tall".
- **★ Thumbnail rows need all three of size / vertical margin / grow on both branches**
  (tripped twice in a row on 2026-08-19, each time only one branch was changed): in `bindFile`,
  directories (`isDir`) and files are **two independent paths**, and **shows, collections,
  playlists and libraries are directories** (`Series`/`BoxSet`/`Playlist` are all in
  `CONTAINER_TYPES`) while **movies are files** — so the symptom of missing one branch is
  always "the movie library is fine, everything else is wrong" (or the reverse). Already hit:
  posters growing only on the directory branch (movies stayed square) and `iconVMargin` only on
  the file branch (show covers touched each other vertically, because `VH.bind` had just reset
  the margin to 0). **Read both branches side by side before changing this code.**
- **★ Do not add more size resets to `bindFile`**: the block at the top of `VH.bind`
  (`size`/`tag`/`scaleType`/`padding`/`colorFilter`/`iconVMargin`) already resets everything.
  A second reset added along with posters was only exposed by **inversion testing showing that
  deleting the added block kept the test green** (and the added block also hard-coded 26dp,
  while `iconDp` actually varies with density). Lesson: inversion testing is not only for
  confirming a test works — **it also points out redundant code**.
- **★ Results from a search at the root could not be opened and had no covers** (2026-08-19,
  `ID_SEARCH`): a result's path is `<searched directory>/<item id>`, and **the server root's
  path is `/`** — so the joined path has only one segment, `list` requires the first segment to
  be a known virtual directory, and you get `Unknown Jellyfin directory: /2121089`; on the
  cover side `openCover` returns null for `segs.size < 2` (the symptom being "search results
  have no covers, but the same item in the library does"). **Searching inside a library
  produces three segments (`/lib/<library>/<id>`) and always worked** — so this bug only
  appeared when searching at the root, which is the most common entry point. Adding a
  `/search/` segment makes both shapes consistent; it is not in `rootEntries` (no such row in
  the tree).
- **★ Covers were slow because every image was preceded by an item query** (2026-08-19,
  `coverRefs`): `openCover` had to `itemOf(id)` before knowing which image to fetch, while the
  item cache lives only **30 s** and thumbnails are a **2-thread queue** — so with hundreds of
  search results, by the time one reaches the front of the queue the cache has long expired,
  giving **two serial round trips × hundreds of items**. Listing/searching now records
  (target item, image type) into `coverRefs`, so a hit sends no metadata request at all. The
  key is the **path**, not the item id: the same movie wants a landscape still under
  "Continue watching" and a portrait poster under the movie library, and keying by id would
  make them overwrite each other.
  - ★ **This regression test must clear `TtlCache` before asserting**: within one test
    `openCover` hits that 30 s cache anyway and sends no request — without clearing, **removing
    `coverRefs` keeps the test green** (verified by inversion, and stepped into right after
    writing a comment saying "a cache hit makes the difference unmeasurable"). `TtlCache`
    gained a `clear()` for this.
- **★ "Continue watching" uses landscape stills, everywhere else keeps portrait posters**
  (2026-08-19, `resumeImage`): that row is the "which one do I carry on with" entry point, and
  a still tells you at a glance which scene it is. Priority is `Thumb → Backdrop → Primary`;
  **a single episode always takes the image from the series level**, not its own
  `ImageTags.Primary` (that is a screenshot of this episode, different for every episode, and
  in a flat list you cannot tell which show it is). Only if none of the three exist does it fall
  back to the item's own Primary — "no image" is worse than "a less apt image".
  - The field names were printed from **both real servers**' Resume responses (measured to be
    **identical**), and **they are all present by default, with no extra request needed**:

    | | Movie | Episode |
    |---|---|---|
    | Thumb | `ImageTags.Thumb` → itself | `ParentThumbImageTag` + `ParentThumbItemId` |
    | Backdrop | `BackdropImageTags[]` → itself | `ParentBackdropImageTags[]` + `ParentBackdropItemId` |
    | Primary | `ImageTags.Primary` → itself | `SeriesPrimaryImageTag` + `SeriesId` |

  - **★ The thumbnail cache key must be split accordingly** (`Thumbs.keyOf`): a file's key is
    normally `name:size:mtime` (path-independence is deliberate), but the same movie appears
    **both under "Continue watching" and in the movie library** with all three identical — while
    the two places want different images, so a shared key makes them overwrite each other,
    showing up as "the same film is sometimes landscape and sometimes portrait". Media-server
    items (`hasCover`) use `scheme:path` instead, and the virtual directory prefix separates the
    two naturally; "still hits after moving backends" is meaningless for a GUID-path virtual
    tree anyway.
  - Tests: three at the wire level (a movie's three-level priority, an episode **asking for the
    series' image rather than the episode's**, and a library item as a control still taking
    Primary) plus one live test, asserting on the **image's aspect ratio**. Inverted, the live
    one reports precisely `image is 341x512 — portrait means it degraded to a poster`.
    ★ In the live test, **do not read JPEG dimensions with `javax.imageio`**: the test JVM has
    no JPEG reader, `ImageIO.read` returns null, and the whole case silently `assume`s itself
    away — it looks like "skipped" rather than "not tested", which fooled us once. Reading the
    SOF segment yourself is about ten lines.
    ★ Also do not check for skips with `'skipped' in xml`: `<testsuite skipped="0">` contains
    the word in the attribute name and is always true. Look at the numeric value of
    `skipped="N"`.
- **★ Search goes through the server's API** (`SearchSource`, the fifth optional capability in
  `core-fs`): the default BFS recursive listing (`scanSearch`) costs **one HTTP round trip per
  level** on a virtual tree, so one search downloads the entire library. The scope follows the
  level being searched: the server root = the whole server (no `ParentId`), a library or an item
  under it = scoped to it; aggregate entry points like "Continue watching" have no describable
  scope, so we return null and let the generic traversal handle it (those directories only hold
  a few dozen entries anyway). ★ **The wildcard syntax is ours and the server does not know
  it** — strip `*`/`?` and send the keyword, then filter the results with the original pattern,
  so `*night*` still works.
- **★ Directories need covers too**: `FileAdapter.bindFile`'s `file.isDir` branch used to
  **just set the folder icon and stop**, so `Thumbs.canThumb` allowing directories had no
  effect in the tree list (only the grid path reached thumbnails). When changing "where an icon
  comes from", remember **tree and grid are two paths**.
- **★ A library's "Latest" is `Latest?ParentId=<library>&GroupItems=false`** (settled by
  looking at both official web clients):
  - **Without `ParentId`, `Latest` is not scoped to a library** and dumps every type.
    ⚠️ An earlier version blamed this on "**Emby ignores `IncludeItemTypes`**" — **that
    conclusion was incomplete**: with `ParentId` added, both servers filter cleanly. The lesson
    is **go look at what the official client actually sends**, which is much faster than
    guessing at the server implementation from symptoms (nearly every pitfall in this chain was
    settled that way).
  - **`GroupItems=false` is what returns individual tracks/episodes/photos**; the default
    (`true`) returns **containers like albums and series**, so "Latest music" listed a pile of
    directories needing another tap. Both servers behave the same.
- **`Items/Latest` answers with a bare array**, while `/Items`, `Items/Resume` and `/Views`
  answer `{Items:[…],TotalRecordCount}`. Even though that endpoint is no longer used,
  `itemsOf()` still accepts both shapes — hard-coding one means that if such a shape ever
  appears it **silently becomes an empty directory**: no error, no log, just nothing when you
  open it.
- **"Continue watching" asks only for `MediaTypes=Video`**: a half-finished song there is
  pointless and would push the film you actually want out of a list that only keeps the last N
  entries.
- **Playlists and collections each get their own root entry** (`/playlists` =
  `IncludeItemTypes=Playlist`, `/collections` = `BoxSet`, both `Recursive`), so you do not have
  to descend two levels through "Folders"; correspondingly, "Folders" **filters out** library
  views whose `CollectionType` is `playlists`/`boxsets`/`folders` (`HIDDEN_VIEWS`): the first
  two already have root entries and showing them again means the same things in two places,
  while `folders` is the extra `UserView` the server adds when "display media folders" is
  enabled (measured on Jellyfin 10.11: `Type=UserView`, `CollectionType=folders`), and opening
  it just shows the same libraries — our level already browses by library, so another
  identically named "Folders" is purely an extra tap.
  - ★ **Playlist contents must not carry `SortBy`**: their order is the playback order the user
    arranged. Measured: a playlist arranged as 8,6 came back as 6,8 with
    `SortBy=IsFolder,SortName` — destroying the playlist on the spot. The UI layer blocks it
    again (see `keepsServerOrder` below).
- **★ "Continue watching" / "Latest" / playlist contents must keep the server's order and must
  not apply the user's sort** (2026-08-18, `PaneViewModel.keepsServerOrder` +
  `PaneViewModelServerOrderTest`): the server returns them in "most recently played" /
  "most recently added" order, and **that order is the entire reason those directories exist**;
  re-sorting by name or size sinks the film you were halfway through into the middle of the
  list — what the user sees is "Continue watching is not updating" (at first we even suspected
  a stale cache and looked in the wrong place). Skipping the sort applies only to those
  directories; `/movies` on the same server still follows the user's sort (the test has an
  explicit control group; verified by inversion — removing the two lines immediately produces
  alphabetical order).
  - **The server's own order is already right** and needs no `SortBy` (measured: both default to
    most-recently-played first, and Emby Web itself does not send the parameter).
    ★ But **sending only `Progress` without `Playing` (START) leaves `LastPlayedDate`
    unchanged**, so an item enters the resume list but does not move to the front — which fooled
    us into thinking "Emby's Resume is not ordered by play time". The player path now sends a
    START the first time it reports progress.
- **"Continue watching" / "Latest" directories must not use the UI layer's children cache**
  (`JellyfinFileSystem.isLiveDir` + `freshMedia` in `PaneViewModel`, the same pattern as git's
  `cheapGitSchemes`): their contents change constantly, otherwise "finish an episode, come back,
  and Continue watching still shows the old state". This applies to the directory itself, not to
  the items under it.
- **The info card uses `MediaInfoSource` rather than `MediaMetadataRetriever`**: the latter has
  to **actually read file bytes**, which takes seconds to tens of seconds on a remote backend,
  while duration/resolution/codec/audio tracks/subtitles all arrive with
  `Fields=…MediaSources` during listing — **usually with no extra request at all** (before you
  can tap properties you must have just listed that directory, so it is still in `cache`).
  That is also an additional reason not to do "list without MediaSources and query on demand".
  Related: the info card shows `MediaSources[0].Path` as the path (**the real path on the
  server**) — our own `XFile.path` is a string of GUIDs and means nothing to a human.
- **★ Photo byte sizes are not provided by the server and must be probed** (2026-08-18,
  `withSizes`): `Photo` items **have no `MediaSources`**, and the `ItemFields` enum **has no
  `Size` at all** — writing `Fields=Size` is **silently ignored** by the server (confirmed in
  `/api-docs/openapi.json`'s `ItemFields`; both servers agree). The symptom is every image in an
  album showing 0 B. The workaround is a `Range: bytes=0-0` probe whose
  `Content-Range: bytes 0-0/27836` response header carries the total length in the denominator,
  transferring a single byte. ★ `HEAD` **does not work**: both servers answer **405**.
  Several gates keep "listing a directory" from turning into hundreds of requests: if more than
  `PROBE_MAX` (80) items lack a size, drop the whole batch; concurrency 6; a 2.5 s budget for
  the batch (timeouts leave 0 rather than blocking the directory); results cached forever (file
  sizes do not change). Items that already have a size (movies have `MediaSources.Size`) send no
  request at all.
  - **The info card queries once for a single item** (`MediaDetails.size`): probing every item
    while listing a screen is too expensive, but opening properties concerns one item and is
    worth it. So **the list may show 0 while properties shows the real value** — that is not an
    inconsistency, the two places have different cost budgets.
  - **"Unknown" must not be displayed as "0 B"** (`Format.sizeOrNull`): that reads as an empty
    file. ★ The rule only fires for "media server + size 0" — local/SMB really do have 0-byte
    files, and hiding those too would be a different bug (`UnknownSizeTest` pins that boundary).
  - `Fields` therefore also carries **`Width,Height`**: a photo's pixel dimensions are only
    provided on request (video's live in `MediaStreams`), and they are more meaningful in the
    info card than the byte count.
- **★ Any backend whose path's last segment is not a file name must carry `displayName`
  everywhere** (2026-08-18): a media server's `XFile.path` ends in an item id (**and on Emby it
  is a plain number**). The playlist used to store only paths, so the list and the player title
  showed "38" and "40"; worse, **the extension was gone too** — media3 identifies containers by
  it, and without it playback degrades to sniffing extractors one by one (see the AVI lesson).
  Both storing (`PaneViewModel.trackFrom`) and rebuilding (`MusicEngine.resolve`) must carry it,
  and `PlaylistTrackNameTest` pins both ends; passing an empty string as `XFile.displayName`
  makes `name` return an empty string, so it must be `ifEmpty { null }`. SAF's document URIs are
  the same class of problem.
  ★ **Likewise, check every place that slices a path segment to show as a name**: the "Now
  playing" title used to take the last segment of `file.parentPath`, so the list was called "40"
  — it now goes through `PaneViewModel.parentLabel` (taking the `displayName` of that row in the
  tree, i.e. the album name); see `PlaylistTitleTest`.
- **A video's external subtitles are "streams", not files in a directory**, so the player's
  "list the same directory looking for `.srt`" finds nothing. `MediaInfoSource.subtitlesOf`
  wraps each subtitle stream as an **`XFile` you can `openInput()` directly** (pseudo-path
  `<item path>!sub<stream index>`, interpreted only by that implementation), so the player's
  reading/parsing/track selection needs **no changes at all** — which is also why it returns an
  `XFile` rather than a custom structure. Only **text** subtitles are taken: PGS-style bitmap
  subtitles cannot produce text and the player can already decode them as an embedded track;
  `.lrc` is excluded too (those are lyrics). Content is requested **in the original format
  first** (converting ASS to SRT flattens effect tags and multi-line dialogue), falling back to
  `.srt` when the original is unavailable (same lesson as lyrics).
  ★ **Jellyfin reports the codec as `subrip`, not `srt`**, so building a `Stream.subrip` URL is
  a guaranteed failed round trip; map it first (`subExt`).
  ⚠️ Getting the ASS source **does not mean styling is displayed**: `SubtitleParser.parseAss`
  takes plain text only (`{\pos}`/`{\fad}` and friends are stripped), and subtitles are drawn on
  a TextView pinned to the bottom — so positioning, fonts, colors and effects are currently not
  rendered. Actually supporting them means changing the renderer, not just switching format.
- **★ The two servers handle lyrics completely differently** (2026-08-18, `LyricsSource`):
  **Jellyfin** 10.9+ has `GET /Audio/{id}/Lyrics` returning structured JSON (`Lyrics[].Text` +
  `Start`, in ticks); **Emby has no such endpoint at all** — requesting that URL makes it treat
  the request as a **transcode job** and answer 500. Emby associates `.lrc` as an **external
  subtitle stream** with `Codec=lrc`, reachable at
  `Videos/{id}/{mediaSourceId}/Subtitles/{index}/Stream.lrc` (returning the raw LRC text). The
  implementation tries the first, then the second.
  ★ **The subtitle endpoint converts to the format named in the URL, and `.lrc` as an output
  format is not supported by every version**: Emby **4.9.3 answers HTTP 200 with zero bytes**
  (not a 404, not an error — the hardest kind), while 4.9.5 returns the original text. `.srt`
  works on both versions, so we fall back to it and convert back to LRC (losing end times does
  not matter, lyrics scroll by start time only). **This difference cannot be reproduced on a
  self-hosted container**; a user found it on a real server.
  - ★ **Known limitation: non-UTF-8 external text (lyrics/subtitles) fetched through a media
    server is irrecoverably corrupted, and the client cannot repair it.** Measured 2026-08-19
    by reproducing with a GBK `.lrc`: what the server emits is **mixed-encoding and already
    lossy** — a UTF-8 BOM is prepended, the bytes `d2 b9 c9 ab` (GBK for "night color") sit
    there untouched, and right next to them is `ef bf bd ef bf bd` (U+FFFD replacement
    characters) — roughly a third of the bytes have been replaced. The mechanism is presumably
    the server decoding a GBK stream as UTF-8 segment by segment: byte pairs that happen to form
    valid UTF-8 survive, invalid ones become replacement characters.
    **U+FFFD is a headstone for "we no longer know what was here"; the original bytes are
    gone**, so decoding the whole thing as GBK throws, decoding as UTF-8 gives mojibake, and
    **no encoding can recover it**.
    → The only fix is **converting the source file to UTF-8** (`iconv -f GBK -t UTF-8`).
    → This applies to **both lyrics and subtitles**, since both go through the same subtitle
    endpoint.
    → The `TextDecoding` hook and the whole encoding preference **still work for local/SMB/
    WebDAV** (there we read the raw bytes ourselves); only the media-server path is destroyed
    upstream — do not go changing client decoding logic for it.
  ★ The interface **always returns LRC text** rather than each server's structure: callers reuse
  the existing `parseLrc`, nobody has to write a parser per server type, and "a lyric line" does
  not have to become a structure in `core-fs`.
- **Music tags and cover art also go through those two optional interfaces**: title / artist /
  album / duration / sample rate come from `MediaInfoSource` (measured fields: `Name`, `Album`,
  `Artists[0]` falling back to `AlbumArtist`; the sample rate is the audio stream's
  `SampleRate`), and covers come from `CoverSource`. Both used to make
  `MediaMetadataRetriever` **pull down the whole song** just to read embedded metadata — slow
  for a whole album, when the server hands it over during listing anyway.
  ★ `Thumbs.audioCover` is **a separate path from `generate()`**, so when changing where covers
  come from, wire up both; changing only one produces "covers in the list but not on the player
  page".
- **An episode's display name is `Series - S01E02 - Episode title`**, with the series name
  first: in a flat list like "Continue watching" or "Latest episodes", `SxxExx` plus an episode
  title does not tell you which show it is. It is also a more useful file name when copying
  locally (`SeriesName` is in the response by default, no extra `Fields` needed).
  ★ **Both the series name and `SxxExx` need duplicate guards**: in a library with no scraped
  metadata, `Name` is the file name and often already contains both, so without a check you get
  `S01E01 - Show - S01E01.mkv` (measured on Emby, exactly this).
- **`Fields=Path,MediaSources` is not cheap but is required**: `Path` gives the real extension
  (the player identifies containers by it, and a fake one degrades media3 to sniffing), and
  `MediaSources` gives byte sizes. The cost is that every item carries full audio/subtitle
  stream details, and recursively listing a movie library can reach several MB. It is still
  worth it, because the alternative — "list without sizes, query when needed" — makes **every
  row in a file manager show 0 bytes**, which to a user means it is broken, and it throws off
  `CopyEngine`'s progress bar entirely.
- **Only direct original streams are used** (`/Items/{id}/Download`, falling back on 403/404 to
  `/Videos|Audio/{id}/stream?static=true` and remembering not to try again); no transcoding
  negotiation. A direct stream is a single byte stream that honors Range, so `openRandom`
  reuses `HttpRangeSource` and player seeking plus the whole network-video-thumbnail machinery
  benefit with zero changes. The cost is that codecs this device cannot decode still cannot be
  decoded — a deliberate trade-off, not a defect.
- **★ "4K stutters, seeking stutters worse, but the same file over SMB/WebDAV is smooth"
  (2026-08-17)**: the root cause was **metadata requests mixed into the streaming path**. The
  old `openMedia` called `itemOf()` before opening each Range stream, and that metadata's only
  use was deciding Video vs Audio when falling back to the stream endpoint — the normal path
  does not need it at all. The cost was **one extra serial round trip per seek**, with a
  response carrying `MediaSources` (full audio/subtitle details, several to tens of KB), while
  the item cache is only 30 s and so expires constantly. SMB (pread) and WebDAV (plain Range
  GET) do one round trip per seek, and next to them it is visible stuttering. The URL is now
  computed **once** in `Media`, and each Range request is simply a GET of it.
  - **Lesson**: the `RandomSource` returned by `openRandom` is a hot path called repeatedly for
    the **entire playback**, and any "let me just look this up" metadata request in it is
    multiplied by the number of seeks. Anything that needs looking up belongs in the single
    `openRandom`/`openInput` call.
  - The regression test asserts **"it plays even if the item endpoint does not exist at all"**
    (an unregistered route = 404, so touching it is immediately exposed), rather than counting
    requests — that would be fragile against caching/stream-pool changes, and **a cache hit
    makes the difference uncountable** (the real problem only occurs after the 30 s expiry,
    which a mock cannot reproduce).
  - Ruled out in the same round: both servers' `/Items/{id}/Download` **do support Range
    correctly** (measured: 206 + `Content-Range`), it is not "ignores Range and sends from the
    start". ★ But with query authentication (`api_key=<token>`), Jellyfin's Download answers
    **401** while the stream endpoint accepts the same query — do not let that mislead you into
    "no download permission"; with header authentication (what Twig uses) it is a 206.
- **The whole backend is read-only** (`writable() = false`): Jellyfin has no upload API, and
  `DELETE /Items/{id}` deletes the **real file** in the media library, so the cost of a
  mistouch far outweighs the benefit.
- **An API key carries no user identity**, while "Continue watching" and playback progress are
  per-user — so in API-key mode we additionally `GET /Users` to pick an identity (matching the
  user name if given, otherwise the first user).
- **Three auth headers are sent together** (`Authorization` / `X-Emby-Authorization` /
  `X-Emby-Token`): respectively Jellyfin 10.8+'s preferred form, the older name it still
  accepts which is also Emby's canonical form, and where Emby reads the token. A few dozen extra
  bytes buys "one implementation, both servers". The four Client/Device/DeviceId/Version fields
  are **mandatory** on Jellyfin — omitting them is a straight 400 — and `deviceId` must be
  stable across restarts (`Prefs.deviceId`), otherwise the server accumulates a long list of
  one-shot device records.
- **Progress reporting may only happen on a background thread** (`RemoteProgress`, a
  single-thread executor guaranteeing START is queued before PROGRESS). The player's original
  progress-writing path was on the main thread (it was only SharedPreferences), and copying that
  gives `NetworkOnMainThreadException` — and by the terminal-resize rule, worse still, a
  swallowed exception makes "synced" and "not synced" look identical. Failures now go to logcat
  plus a one-time toast.
- **The resume position must be waited for; do not start playing and then jump**: the remote
  position takes a network round trip while `surfaceCreated` usually arrives earlier. At that
  point **do not prepare yet** (the `resumeReady` gate); start once the position is back. The
  extra two or three hundred milliseconds disappear into buffering, while "start at 0 and then
  jump" is a very visible jolt. ★ There must be a timeout fallback (2.5 s), otherwise one
  network hiccup becomes "spinning forever" with nothing on screen to explain it.
- **When there is a remote progress source, do not also write the local `PlaybackStore`**:
  recording both means one film has two positions and nothing can say which is right, and the
  server's items would eat into that store's 100-entry limit.
- **A directory's thumbnail key cannot be `name:size:mtime`**: directories have no byte size
  (always 0) and mtimes within a level are often identical, so a whole level of shows or albums
  **shares one key and shows the same cover**. Directories use `scheme:path` instead (files keep
  the old scheme; "still hits after moving backends" is deliberate there).
- **Covers are not gated by the "generate thumbnails for network files" preference**: that
  preference exists to stop us **downloading a media file's own bytes for a thumbnail** (several
  MB for a video), while a poster is a small, already-scaled image the server has ready (tens of
  KB), on the same order as the listing's JSON. Gating it would mean the default (that
  preference defaults to off) shows a wall of plain file icons and the feature would be
  pointless. Shows/albums/photo albums **are directories**, so `Thumbs.canThumb` also allows
  directories on `CoverSource` backends (sorting is unaffected, since `isDir` takes precedence
  in `SortRules.groupOf`).
- **★ "Searching by the pre-scrape name finds nothing" was our own filtering (2026-08-19)**:
  the server's `Items?SearchTerm=` **matches several fields** — measured, a film scraped as
  "Ice Age" (`OriginalTitle=Ice Age`) is returned for both `ice` and its Chinese title. But
  the UI layer then filtered the results by `name` (= the display name) using the wildcard
  rules, `ice` naturally did not match, and **the hits were discarded by us**. Local filtering
  now happens only when the user **actually typed a `*` or `?`** (then they really do want name
  pattern matching).
  ★ Along the way one assumption was disproved: `/Search/Hints`, which the web client uses,
  actually **cannot find original titles** (measured: both servers answer 0 results) — "which
  endpoint the official client uses" does not mean "which endpoint is stronger", and here it is
  the opposite.
- **★ Searching at the server root did "nothing at all" (2026-08-19)**:
  `PaneViewModel.startSearch` begins by confirming there is somewhere in the tree to hang
  results, and the check was "some row's key equals `fileKey(root)`" — but **server rows and
  favorite rows have keys `s:<label>` / `fav:…`** (their attached rows hang off the directory
  that `keyFile[that key]` points to). So a search started at a server root **silently
  returned**, with nothing happening on screen and no trace in logcat. The check is now
  `hasSearchAnchor`: the key matches directly, **or** some row's `keyFile` points at the same
  file.
- **Tests come in two layers**: `JellyfinFileSystemTest` (mockwebserver, wire level) pins the
  bytes we send; `JellyfinLiveTest` (a real server, skipped entirely if the environment
  variables are unset) pins that the server really accepts them. The latter is not optional —
  whether the auth header form is accepted, whether `Latest` really answers a bare array,
  whether `MediaSources` really contains `Size`: a mock only asserts our own imagination (which
  is exactly how the S3 encoding pitfall happened).
  ```
  TWIG_JF_URL=http://192.168.1.9:8096 TWIG_JF_USER=<user> TWIG_JF_PASS=<password> \
    ./gradlew :fs-network:test --tests "*JellyfinLiveTest*"   # add TWIG_JF_EMBY=1 for Emby
  ```
- **Running both real servers locally (this is what settles arguments, reproducible in 5
  minutes).** The test clip **must be longer than 5 minutes**: both servers default
  `MinResumeDurationSeconds` to **300 seconds**, so a short clip never enters the resume list —
  testing with a 30-second sample yields the wrong conclusion "the resume query is broken" (it
  nearly became the third misdiagnosis).
  ```bash
  ffmpeg -f lavfi -i testsrc=size=320x240:rate=5:duration=400 \
         -f lavfi -i sine=frequency=440:duration=400 \
         -c:v libx264 -preset ultrafast -c:a aac -shortest "media/Movies/Long Movie (2026).mp4"
  docker run -d --name twig-emby -p 8097:8096 -v $PWD/emby:/config -v $PWD/media:/media emby/embyserver
  docker run -d --name twig-jf   -p 8098:8096 -v $PWD/jf:/config  -v $PWD/media:/media:ro jellyfin/jellyfin
  ```
  The setup wizard can be completed entirely over the API
  (`POST /Startup/Configuration` → `POST /Startup/User` → `POST /Startup/Complete`, then
  `POST /Users/AuthenticateByName` for a token and
  `POST /Library/VirtualFolders?name=Movies&collectionType=movies&refreshLibrary=true` to
  create a library). ★ **Jellyfin needs a `GET /Startup/FirstUser` first** (that is what
  creates the default user), otherwise the following `POST /Startup/User` is a **404** — while
  the OpenAPI document says that endpoint only answers 204/503/401/403, so the docs will not
  tell you. To find out whether a route or field exists, do not rely on memory: ask the server
  itself at `GET /api-docs/openapi.json` (Jellyfin 10.11 moved Resume to
  `/UserItems/Resume`, but the old `/Users/{uid}/Items/Resume` is still there — measured, an
  unauthenticated request answers 401 rather than 404 = the route exists. **That "401 vs 404"
  probe tests route existence without creating a user.**)

- **"Go to the containing folder" is not offered for a media server** (2026-08-28,
  `MusicDialogs.canLocate`): that action is `revealPath(file.parentPath, focus = file)`, and
  `revealPath` builds the chain by **cutting the path on '/' and listing every level as a row
  of the tree**. A media server's path only looks like such a chain. **Libraries sit directly
  on the server root** (`rootEntries` emits `libEntry(it, "/$ID_LIB")`), so a track's path is
  `/lib/<libId>/<albumId>/<songId>` while **`/lib` itself is nothing** — no row ever has that
  path, and listing it lands in `topLevelItems`' `else` branch:
  `Unknown Jellyfin directory: /lib`. That is exactly what the device reported.
  - Fixing it properly means letting a backend **declare its own ancestor chain** (a new
    optional capability, `FsRegistry.of(file) as? …`), because the path alone cannot express
    it. Not worth it here: this tree is the *server's* organisation (Continue watching /
    libraries / seasons) and the same item is reachable under several virtual paths at once,
    so "jump back to the folder it is in" has no single answer to begin with — the same
    judgement that keeps media servers out of "Go to path" (`supportsGoto`).
  - ★ **The test that missed this** built the fake server as `/music/album1/song1`, where
    every level exists. Against the real shape (`"/" to listOf("lib/lib1")` — the library is a
    row, `/lib` is not) the walk fails on the first level. When faking this backend, copy the
    shape from `rootEntries`, not from a file server.
