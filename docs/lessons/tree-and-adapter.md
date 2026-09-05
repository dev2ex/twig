# Tree and adapter (`PaneViewModel` / `FileAdapter`)

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Two rows with the same key = one of them expands to nothing (★ 2026-08-17,
  `FileNode.keyPrefix`)**: a row's key (`f:<scheme>:<path>`) is the **only** thing DiffUtil uses to
  identify rows in `submitList`, so a duplicate makes it pick the wrong row. The worst collision:
  another app does "Open with Twig" on an archive → `mountExternal` mounts it at the top of the
  tree, while **that archive's original row in the storage tree is still there** (as long as its
  directory is expanded). The symptom is "the same archive expands fine in one place and is empty
  in the other", while both actually share the same `children[key]` — so it looks like "it cannot
  be read" when in fact the rendering picked the wrong row.
  - The fix is to give the externally mounted subtree a whole-tree key prefix (`x:`), and
    **the prefix must be carried down through child recursion**, otherwise the entries inside the
    archive collide with the ones at its original location again.
  - **Attached rows (info cards / search results) must NOT carry the prefix**: `addAttachments`
    de-duplicates with the unprefixed `fileKey` (`attachedKeys`), so one file only ever attaches at
    the first place it appears in the tree and cannot duplicate.
  - The reproducing test must **`mountExternal` first and then re-expand that directory**
    (`accordionExpand` collapses it during the mount, and without re-expanding the collision cannot
    happen) — the first version of the test "passed" for exactly that reason.
- **Attached rows do not depend on expansion state, so every "collapse" must clear them itself**
  (★ 2026-08-19, `dropSearchUnder`): `addAttachments` is deliberately written not to depend on
  `exp` (so an unexpanded directory can still host a search or an info card), at the cost that
  **collapsing does not make them disappear on its own** — `toggleFile` had long since handled this
  for ordinary directories, while `toggleServer` / `toggleFavorite` never did. The symptom: search
  at a server root, tap the server row, the children collapse and a pile of search results hangs
  there orphaned, and cannot be dismissed.
  - ★ **Mismatched keys are the other half**: search results are stored under **that directory's
    `fileKey`**, while a server row's own key is `s:<label>` and a favorite's is `fav:<id>` — so
    `remove`ing by the row's key deletes nothing. Remembering to delete is not enough; you must
    first map the row key to the directory via `keyFile[rowKey]` and then compute the key.
  - Both branches need it: **on collapse**, clear them; and **when the row was not expanded and
    only has search results underneath**, treat the tap as "collapse" (just close the search) and
    do not fall through to the branch that expands the whole server.
- **Expansion is asynchronous while the accordion keeps only one chain — a slow one landing late
  collapses whatever you opened afterwards** (★ 2026-08-24, `PaneViewModel.pendingExpand`):
  open a slow directory (network, or just large), get impatient and open another, and as soon as
  the first finishes it calls `accordionExpand` on itself and **collapses the one the user just
  opened**. On screen: "the directory I just opened closed itself and the earlier one popped up".
  The rule is **the later tap wins**:
  - Every "expand this row" entry point first claims it with `claimExpand(key)`, which immediately
    hides the spinner of whatever is still in flight; on landing, `landExpand(key)` answers "does
    this still count", and if not it only caches the children with `putListing` (so the next tap
    expands instantly) and **does not expand, does not move `currentDir`, and does not show its
    error** (the user has already given up on it).
  - **The synchronous cache-hit branch must claim too**: it also calls `accordionExpand`, and
    without claiming, the slow one can still collapse it. There are seven entry points:
    `toggleFile` / `toggleServer` / `toggleRestic` / `toggleFavorite` / `unlockRestic` /
    `mountExternal` / `applyReveal`.
  - **★ Giving up must stop the spinner immediately** (added 2026-08-26, `abandoned` + `busy()`):
    the first version only cleared `loadingKeys`, while **a server row's spinner and "Connecting"
    text look at `connecting`** — so on a slow backend like SFTP the abandoned server kept spinning
    with "Connecting", which looks like a frozen UI. The request cannot be cancelled (see below),
    so `loadingKeys`/`connecting` mean "is there work in flight", and **the spinner is decided
    separately**: `busy(key) = key !in abandoned && in flight`. All four render sites (file rows /
    servers / favorites / restic) go through it.
  - **★★ "Do not accordionExpand" ≠ "do not expand"** (2026-08-26, `applyRestored(expand=)`):
    `applyRestored`, called when a server/favorite lands, contained an **unconditional**
    `expanded.add(r.key)` that bypasses `accordionExpand` — so the abandoned server **expanded
    itself anyway**, leaving two unrelated paths open in the tree (the directory the user opened
    plus that server). The symptom only surfaced once the spinner was hidden. The bookkeeping must
    still happen (the connection is established and the children are back, so the next tap should
    expand instantly), it just must not enter `expanded`.
    ★ When adding this kind of gate, **find every place on the landing path that writes
    `expanded`**; watching only `accordionExpand` misses them.
  - **"Still in flight" is still useful information**: if the user taps the row again before it
    lands, just re-claim it and put the spinner back, and **do not issue a second request** —
    `toggleServer`/`toggleFavorite` already had a `connecting.contains` branch (changed to
    `rebuild()`, otherwise the spinner is restored but nothing repaints), and `toggleFile` got a
    matching `loadingKeys.contains(key) -> rebuild()` (it previously had **no de-duplication at
    all**, so a second tap meant a second `listChildren`).
  - **★ Do not cancel that coroutine**: `listChildren` is blocking IO and cancelling does not
    interrupt it; worse, `runCatching` catches the `CancellationException` and treats it as "listing
    failed" — so the one the user abandoned themselves pops up an error.
  - The reproducing test must make the **first** tap land **last** (`SlowExpandRaceTest`): on an
    ordinary `StandardTestDispatcher` the two taps run to completion in order, the first lands
    first, nothing steps on anything, and **the test is green even on the buggy version**. Give the
    slow one its own `HoldDispatcher` that holds the runnable (`vm.io` is a writable field exactly
    for this), let the fast one complete, then `release()`.
- **Ticking a checkbox made the whole list flicker (★ 2026-08-02)**: `FileAdapter` used to call
  `notifyDataSetChanged()` for any selection change, rebinding every visible row. The height of a
  thumbnail row in the tree list is decided **asynchronously** — `bind` first sizes the icon box to
  a square thumbDp and `FileIcons.bind` resets the image to the type icon, then
  `Thumbs.fillAspect`'s `view.post{}` (which has to wait for infoBox to finish this layout pass
  before the real height can be read) restores the height according to the image's aspect ratio.
  So on every tick, all thumbnail rows went through "collapse to a square → restore next frame",
  and the collective row-height jitter is what the eye sees as flicker. **The fix is not to rebind
  whole rows**: selection changes go through
  `notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)`, and the payload overload of
  `onBindViewHolder` only calls `bindSelection()` to change the check colors and
  `root.isActivated`. Lesson: whenever binding contains "reset now, fill in asynchronously"
  (thumbnails, async icons), the cost of a full `notifyDataSetChanged` is not performance but
  **visible flicker**; partial updates need payloads.
- **Write operations are gated by exactly two predicates; never special-case by backend name**
  (★ 2026-08-19, `XFile.isMutable()`):
  - `isMutable()` = **can this entry itself be changed** (rename / the "delete source" half of a
    move / delete)
  - `isWritableDir()` = **can we write into this directory** (new folder / new text file / paste /
    copy destination)
  - **Copy, compress and share are pure reads of the source and are never blocked for any backend**
    — that is the main use of a read-only backend.

  Both are composed from `FileSystem.writable()` (the whole backend is read-only: media servers /
  restic / 7z / RAR / the git view / "Apps") and `XFile.canWrite` (this entry has no permission),
  **so adding a read-only backend requires no changes anywhere**. `commonFileActions` used to
  special-case with `file.scheme == AppsFileSystem.SCHEME`, which means missing a place for every
  new read-only backend.
  - **There are six entry points; check every one when adding a write operation**:
    ① `commonFileActions` (shared by the tree and the space map) ② `showBatchMenu` (multi-select)
    ③ the two "New…" items on directories in `longClickFile` ④ `dirRowActions` (server roots,
    favorites, storage roots) ⑤ the delete built into `treemapMenu` (the one with the size
    confirmation dialog — **a separate path from the generic delete in `commonFileActions`**)
    ⑥ the action bar + the clipboard bar's paste. Actually hit: ③ only checked "New text file" and
    not "New folder"; ⑤ did not check at all; ⑥ had no check whatsoever.
  - **Disabling in the action bar needs `isEnabled`, not just `alpha`**: alpha alone means "looks
    greyed out but still works when tapped". `isEnabled = false` makes `View.onTouchEvent` stop
    dispatching the click (true for `LinearLayout` too). The refresh hangs off
    `MainActivity.onClipTargetChanged` (`PaneFragment.render` calls it on every state refresh, so
    changing directory or ticking items updates it) plus `setActiveIndex` — ★ the latter must come
    **after** `activeIndex` is updated, otherwise it reads the previous pane. All three action bars
    must be refreshed, same as `syncShareIcon`.
- **"Go to path" descends by name; it does not join a path** (2026-08-27,
  `PaneViewModel.revealUnder`): a root row (server / internal storage / root directory /
  removable volume / favorite) carries a menu item that takes a typed path and expands its way
  down to that directory, or to that file's row. It deliberately does **not**
  reuse `revealPath`: that one reconstructs each level by cutting the target path on `/`, which
  only holds for local paths and connected servers — **a SAF path is a whole document URI**
  (the `/`s inside the document ids are `%2F`) and a media server's path is a chain of item ids,
  so a joined path is not any row in the tree (the same lesson as `siblingsKey`). Descending
  costs one `list` per level, which is exactly what `planReveal` pays anyway.
  - **The row's key must be passed in, not recomputed**: children hang on `s:<label>` /
    `fav:<id>` / `f:…` depending on the kind of root, and only the row knows which.
  - A pasted **absolute** path is the common input, so a root prefix is stripped first — but only
    when the root's own path is slash-shaped; for the opaque backends above, names are all there is.
  - **A server that was never opened this session still jumps** (`root == null`): it is connected
    inside the IO phase, like `planReveal`'s scheme lookup, and its group row is named explicitly
    rather than read off the visible rows (landing folds away everything outside the chain).
  - The regression test's decisive case is a backend whose path is **not** a path
    (`PaneViewModelGotoTest`) — build the fixture with slash-shaped paths and it passes on an
    implementation that just joins strings.
  - **Media servers, document trees (SAF) and "Apps" deliberately do not get the item**
    (`supportsGoto`): the descent handles them fine — there is simply nothing to type. A media
    server's tree is the server's own organisation (Continue watching / libraries / seasons),
    a SAF grant is one folder browsed by tapping, and an "Apps" row is a package name. The
    predicate sits outside `PaneFragment` so `GotoTargetTest` can pin the exclusions; a menu
    item quietly coming back is not something a device walkthrough catches.

