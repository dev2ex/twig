# SAF (Storage Access Framework)

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Files in a SAF tree must not be treated as outsiders (★ 2026-08-26)**: the user's words were
  "SAF feels like a completely separate world" — music did not open in the music player, images
  could only be viewed one at a time, and there were no thumbnails. The three root causes are
  unrelated to each other but all come from the same assumption: **that `XFile.path` is a
  slash-separated path**. SAF's path is an entire document URI
  (`content://…/tree/primary%3ADCIM/document/primary%3ADCIM%2Fa.jpg`), where the `/`s inside the
  child's and parent's document ids are encoded as `%2F`.
  - **What `parentPath` slices off is not the parent directory at all**, so
    `PaneViewModel.siblingsKey` finds nothing in `children` → an image opens alone and audio
    cannot assemble a play queue. The fix is not to invent a parentPath for SAF but to
    **look up which expanded directory contains this item** — which holds for every backend whose
    path is not a path, and is more reliable than string manipulation.
  - **`trackFrom` only accepted local files and connected servers**, returning null for SAF → the
    old single-track player. But SAF grants are **persistent**
    (`takePersistableUriPermission`), so it can perfectly well join "Now playing"; adding a
    `kind = "saf"` is enough, with a matching line in `MusicEngine.resolve`.
    ★ `displayName` must be stored: the URI contains no file name, and without it there is not
    even an extension (media3 needs it to identify the container).
  - **`SafFileSystem` had no `openRandom`**, so the whole tree was treated as a network backend:
    video thumbnails degraded to "only the file header can be fed" (MKV/AVI essentially never
    produced an image), player seeking became "reopen the stream and skip", and archives had to be
    fully materialized first. SAF entries are local files after all, and the fd from
    `openFileDescriptor` supports pread — copy the approach from `ShareSourceFileSystem` and turn
    `randomAccessEfficient()` on.
  - Likewise `Thumbs.genPdf` only accepted `scheme == "file"`, so a SAF PDF was queued for
    generation every time only to return null, wasting a queue slot and eating the 60 s failure
    cooldown. PdfRenderer only needs an fd, which the provider can supply.
  - **★ "Up" must not build a `parentPath` either** (added 2026-08-26): the `…/document` row it
    slices out does not exist in the tree, so currentKey points at nothing — on screen **the green
    highlight simply disappears**. The same button was also broken **inside archives**, for a
    different reason: an archive root's path is `<host>!/`, so the sliced path is right but **the
    scheme is still zip** (`f:zip…:/sdcard` does not exist either). `PaneViewModel.upTarget` now
    **asks the tree first** — whoever contains it is its parent (for an archive root, the row of
    the host file in the tree) — and only falls back to string manipulation when the ancestors are
    not expanded.
    ★ Two more classes of row **have nothing containing them in the tree** and cannot be rescued by
    the lookup either, so they answer according to their position in the tree: **rows hanging
    directly under a group header** (SAF's granted roots) have the **group header** as their
    parent (the same idea as server roots falling back to `g:lan`), and **top-level rows**
    (Internal storage / volumes / Root directory / Apps, `depth == 0`) have nothing above them, so
    it is **cleared** — a computed `/storage/emulated` would be another nonexistent row.
  - The regression test `SafSiblingsTest` builds data with **realistically shaped document URIs**
    (with `%2F`); shaped as ordinary paths, none of this is detectable.
  - **A folder inside a document tree can be favorited** (2026-08-27, `Favorite.pathName`):
    `favoriteFrom` used to answer null for `saf`, on the grounds that the location could not be
    persisted — **wrong for SAF**: the grant is `takePersistableUriPermission`, so the document
    URI survives a restart (which is exactly why the music queue already accepted `saf` tracks).
    Only the *name* does not survive: `defaultFavoriteName` takes the last segment of
    `Favorite.path`, which for a document URI is `primary%3ADCIM%2FPhotos` — so the folder's
    display name is stored alongside it, and `resolveFavorite` puts it back as the target's
    `displayName` (that XFile is what the long-press menu, the info card and a copy's destination
    name all read). The row's second line shows the decoded document id (`saf:primary:DCIM/Photos`)
    rather than the whole URI. ★ Decode it by hand, not with `URLDecoder`: that one turns `+`
    into a space (form encoding, see the S3 lesson) and a folder with a '+' in its name decodes
    wrong. **restic repositories on SAF are still unsupported** — that needs the repo location
    itself to be expressible as `saf`, which `Favorite` cannot say.
  - **Saved comparisons take a SAF side too** (`compareSideOf` / `resolveCompareSide`, same day),
    and there the name has to survive one more hop: `CompareActivity.start` passes the two sides
    through an **Intent as scheme + path**, so `displayName` had to be added there as well
    (`sidesFrom`) — without it the path bar shows the encoded id (`Format.pathLabel` returns
    `name` for `saf`) **and saving that session would store the encoded string as the folder's
    name**. ★ Also `isLocalSide` now counts `saf` as local: a document URI points at a file on
    this device, read through an fd with pread, so it takes the local content-comparison limit
    (the network one defaults to 0, i.e. no content comparison at all). That is not the case
    the "archives count as network" rule is about — an archive's host may sit on SMB.
  - **"Go to the containing folder" is deliberately not offered for a document tree**
    (2026-08-28, `MusicDialogs.canLocate`): the music player's and the playlist's menu item is
    `revealPath(file.parentPath, focus = file)`, and `parentPath` cuts on '/' — which a document
    URI does not survive (it yields half a URI, the same trap as `siblingsKey`). There is no way
    to rescue it here either: **SAF has no "get parent" API**, and the tree-lookup trick used for
    `siblingsKey` needs the parent to already be expanded, which is exactly what is missing when
    the jump starts from the player. The only remaining route is listing the whole granted tree
    recursively until something contains the track — unbounded cost for a "jump over and look"
    action. So the item is left out of the menu rather than shown and then failing.
    - **Media servers are out for their own reason** — see
      [jellyfin-emby.md](jellyfin-emby.md) (`/lib` is a synthetic prefix, not a row). The first
      version of this change kept them, on the reasoning that their path is a full ancestor
      chain; it shipped and failed on the device with `Unknown Jellyfin directory: /lib`.
      ★ The fake in the test had been built as `/music/album1/song1` — every level existing —
      so it passed. **A fixture that does not have the backend's real path shape proves
      nothing**, which is the same lesson `SafSiblingsTest` was written for, re-learned on
      another backend.
    - Both menus (player overflow, playlist row) had to stop indexing a fixed `arrayOf` with
      `when (which)` first: once an item can be absent, a written-out index silently points at
      the wrong action.

