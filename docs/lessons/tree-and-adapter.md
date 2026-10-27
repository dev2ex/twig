# Tree and adapter (`PaneViewModel` / `FileAdapter`)

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [AGENTS.md](../../AGENTS.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Moving/deleting the selected directory left a hidden transfer destination (2026-10-26)**:
  refreshed rows dropped the source directory but currentKey/currentDir kept its old
  path, so the other pane could still copy or move into it. Before replacing a
  successful parent listing, check whether it removes the selected row or an ancestor
  in the visible branch. Select that listing's parent key and directory together,
  using its mounted root for archive parents and preserving favorite/server aliases.
  Apply the same rule to quiet observer refreshes. Failed listings prove nothing;
  unrelated selections made while IO runs must survive. PaneDirectoryRefreshTest and
  RootDirectoryBrowsingTest cover direct removal, whole removed subtrees, favorite
  parents, local observer refreshes, failures and navigation during refresh.

- **Rotating an untouched share destination picker crashed (2026-10-25, issue #28)**:
  after measuring a two-column action strip, GridLayout writes assigned row/column
  indices into child LayoutParams. Reducing columnCount to one validates those old
  indices first and throws IllegalArgumentException. StripGrid.setColumns resets
  automatic row/column specs, including hidden buttons, before changing the count;
  keep equal column weights and fill alignment. Main and share screens use this
  common path. A rotation test that only calls configuration callbacks misses it:
  SharedTextTest measures/layouts between twelve actual configuration changes for
  text, single-file and multiple-image shares; it reproduces the original exception
  before the fix. StripGridTest covers both grids,
  hidden actions, equal widths and retained click handlers on API 24 and 34.

- **Content comparison reported read failures as differences** (2026-10-21, #17):
  `contentEquals` swallowed stream failures and returned false, making sync offer to
  overwrite an unreadable pair. Propagate read errors to `compareFiles`, which records
  `PairState.ERROR`; cancellation still propagates. Both sync entry points skip these
  entries and include them in their unreadable summary. `ContentComparisonTest` covers
  open/read failures on either side, both sync directions, stream cleanup, cancellation,
  and bounded streaming beyond the old 16 MB option cap. The new unlimited cap is opt-in
  for each tier; network comparison stays off by default.

- **Batch-rename preview columns shifted until scrolling** (2026-10-19): top alignment
  alone did not fix weighted columns during the dialog's initial `AT_MOST` measure.
  `BatchRenameDialog.NameColumns` divides the available width explicitly for both
  headers and rows. `BatchRenameTest` covers first measurement and rebound long names.
  Rename preflight uses tree parents for opaque URIs and rejects failed listings,
  conflicting destinations and selected ancestors before changing any names.
  Insertion/overwrite, case conversion and template modes (#13, 2026-10-21) share that same
  preview/execution path. Templates expand once so braces in an original filename stay
  literal; dates use modification metadata and fail explicitly when unavailable. Case
  conversion uses `Locale.ROOT`. Hidden controls must not affect another mode's rule.
  Insertion replaces the original prefix/suffix mode: offset 0 starts at the selected
  left/right edge, counts Unicode code points, and clamps to each name's length. Reject
  invalid offsets before renaming; an invalid hidden offset must not block other modes.
  Overwrite shares the insertion position and replaces as many Unicode code points to its
  right as the entered text contains, appending any overflow. From-end changes only how
  that position is found: offset 0 still appends. Empty text never deletes characters.
  The dialog fixes method/scope and rule controls above a single scrolling preview
  RecyclerView. Pair related fields horizontally to preserve preview space; do not wrap
  the settings in a ScrollView or include them as a scrolling list header. Preview rows
  inherit the dialog background; original and unchanged names use primary text so dark
  mode stays readable. Regex and case-sensitivity checkboxes share one row.
  `BatchRenameLayoutTest` uses native font measurement to check fixed settings in every
  mode, reaching the last of 80 preview rows, dark colors and larger-font radio choices.

- **Website fullscreen plus a hidden toolbar took three Back gestures (2026-10-17)**:
  a separate application fullscreen-exit state consumed Back on top of system navigation
  revealing immersive bars. The original tests called the Activity dispatcher directly,
  bypassing that system layer, and incorrectly treated application callback counts as
  device gesture counts. Bookmark fullscreen is now a display shortcut only: it hides
  the toolbar and system bars without adding a Back stage before history/finish. The label
  no longer promises "Back to exit fullscreen". Chromium's video/page custom view still
  closes before history navigation because that view must be detached explicitly.
  `WebsiteFullscreenTest` covers the reported combination, immediate history/finish and
  custom-view cleanup; these tests do not claim to simulate system gesture interception.
  Manual entry through the toolbar is a separate temporary action: Back restores that
  toolbar before history, with the entry source saved across recreation. If the bookmark
  has since enabled permanent toolbar hiding, skip the otherwise invisible exit step.
  Do not apply this manual-entry Back handler to bookmarks opened fullscreen.

- **Website video fullscreen buttons did nothing (2026-10-16)**: WebView does not host
  fullscreen content automatically. `WebsiteActivity` handles the engine's
  `onShowCustomView` / `onHideCustomView` callbacks in a black overlay, temporarily hiding
  the ordinary page and system bars. Back exits the overlay before navigating history;
  exit restores the bookmark's independent bar settings without reloading the page.
  Keep the Activity across orientation/size changes so rotation retains the engine view.
  Clear ownership before notifying the callback (it may reenter), and detach the view and
  release keep-screen-on on exit or destruction. `WebsiteFullscreenTest` covers these
  transitions, duplicate requests, rotation and navigating to an edited bookmark URL.

- **Website pull-to-refresh fired midway through content below a fixed header (2026-10-16)**:
  `WebView.canScrollVertically(-1)` only sees the outer document, whose offset stays zero
  when a nested element owns scrolling. The start-of-gesture DOM probe checks the touched
  element and its scroll ancestors, including open shadow roots and same-origin frames;
  unknown/cross-origin frames cannot authorize a refresh. Probe coordinates account for
  desktop viewport scaling and pinch zoom. A late reply cannot capture a gesture that
  already started scrolling or authorize a newer gesture. `WebsiteGesturesTest` covers
  these races, `WebsiteFeatureTest` exercises the asset/callback path, and
  `tools/test_website_scroll.py` checks real DOM scrolling in headless Chromium.

- **Fast pane swipes also clicked a node (2026-10-16)**: the gesture listener switched
  panes but returned false, leaving the row's click active. Intercept recognized swipes
  through release; check UP too when no MOVE arrives. Property cards also classify UP,
  cancel child actions and dispatch the tab change after child cancellation. Shared
  intent/cancellation helpers live in `SwipeGesture`; the pane and card tests cover both.
  Treemaps also cancel their tap detector on swipes/pinches and inspect release displacement;
  `TreemapSwipeTest` covers taps, missing MOVE, cancellation and zoomed panning.
  Directory comparison uses `FlingSwipeListener`: keep its fling-speed threshold, but use
  release displacement/time when no MOVE crossed slop. Its tests share the pane's list fixture.

- **Properties ripple disappeared on the first tap, especially with folder counts (2026-10-16)**:
  a row rebind briefly hid its button, ending the ripple. Set final visibility directly;
  do not hide then show during reset. Press-delay changes did not fix this.
  `PropertiesButtonTest` covers count updates rebinding a pressed or released button.

- **Folder counts are optional direct-child counts, limited to local and SMB sources**
  (`DirectoryCounts`, 2026-10-15). The metadata line starts with monochrome folder/file
  outline icons and separate numbers; date/time stays at the end. Two equal-width text-scaled
  slots keep the folder/file positions fixed; the group uses the idle selection check’s opacity.
  Zero values use INVISIBLE, preserving their
  slot, and compound drawables center icons beside the numbers. Counts follow the hidden-file
  setting. A bound row can request one background listing; ordinary expansion/refresh
  results populate the same cache and supersede a slower count request. Local probes
  use `listForSize` with only name/type metadata, retaining its permission fallback;
  SMB probes use the backend listing. Never recurse, read file contents, or infer zero
  from failure. One worker, a 32-entry LIFO queue, 512 count-only cache entries and a
  60-second lifetime bound the extra work. Recycled rows reject old callbacks; collapsing
  cancels queued descendants. The setting defaults off and participates in `uiSignature`.
  `DirectoryCountsTest` / `FolderCountsRowTest` cover actual local reads, SMB listing reuse,
  hidden entries, failure, stale callbacks, rendering and updates after resume. The weighted
  timestamp TextView must use maxLines/ellipsize: singleLine with end gravity can leave a
  horizontally scrolling text layout wider than its visible bounds and hide the date.

- **Cached remote directories stayed stale after reopening or returning from another Activity
  (2026-10-14)**: only local directory expansion ran a quiet re-list, and onResume only refreshed
  local directories. All cached directory expansions (including server and favorite roots) now
  re-read behind the cached rows. Resume also re-reads the current directory on every backend,
  retaining watched local ancestors. Compare the complete sorted XFile list before rebuilding;
  errors preserve the old listing, and a result cannot overwrite a cache replaced while IO ran.
  A collapsed current directory may refresh its cache without reopening. Do not restore the
  unconditional `adapter.notifyDataSetChanged()` on resume: it rebinds unchanged thumbnail rows
  and resets their geometry. Default-opener icons use a separate icon-only payload.
  Covered by `PaneDirectoryRefreshTest`, `ResumeIconRefreshTest` and `SlowExpandRaceTest`.

- **Collapsing Apps / Installed crashed after restarting (2026-10-12)**: an app APK
  explicitly opened as an archive was saved as an `apps` expansion descriptor. Restore
  fabricated `isDir = true` for every such descriptor, and `AppsFileSystem.list` treated
  any non-root path as a category listing. The APK's cached children therefore included
  itself. Its archive row looked closed because the explicit archive flag was not
  restored, but its key remained expanded; collapsing Installed recursively collected
  that hidden cycle until `StackOverflowError`. Switching to another tree removed the
  orphaned expanded key and temporarily concealed the problem.
  - Restore Apps entries through `stat`, preserving the actual file type and restoring
    the explicit archive flag for successfully mounted APKs. Do not infer directories
    from saved paths.
  - Apps listings accept only the root and the two category directories, rejecting
    package paths even if a caller fabricates a directory flag.
  - Cancellation walks cached children iteratively, visiting each row key once and
    retaining external-mount prefixes. A cyclic or deep cache must not overflow the stack.
  - `AppsRestoreTest` follows open-as-archive → save → restore → collapse, and separately
    covers cyclic caches, 10,000 levels, and external-mount keys.

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

- **Landscape Terminal button disappeared without a session (2026-10-12)**: the
  portrait toolbar always offered Terminal, but the landscape strip still removed
  the button when the session list was empty. That rule predates local sessions:
  Terminal now creates a local shell when no session exists. Keep all four pinned
  buttons present, ordered Terminal / Panes, then Music / Menu. `IssueOneLayoutTest`
  checks the empty-session case and the actual two-row geometry.

- **Fullscreen retained an empty band above the toolbar on cutout screens (2026-10-12)**:
  hiding the status bar alone allowed the window manager to reserve the cutout area.
  Permitting short-edge cutouts and drawing the surround in the toolbar color was
  insufficient: adding the cutout's safe top inset recreated the same empty band in
  the app itself. In status-bar-only fullscreen, MainActivity lays out into that top
  area and does not add cutout padding. Navigation and IME insets
  are still respected; the navigation bar remains visible. Do not substitute the
  hidden status bar's height or apply another copy of the insets in descendants.
  Leaving fullscreen restores the system-fitted layout and original background/padding.
  `StatusBarFullscreenTest` checks the toolbar's actual top position and API 24/28
  compatibility. Vendor cutout behavior still needs a device check.

- **Landscape fullscreen still left an empty cutout strip (2026-10-18)**: the window
  already permitted short-edge cutouts, but the shared root-padding policy kept their
  left/right safe insets. Fullscreen must ignore cutout insets on every edge, retaining
  navigation and IME insets. `StatusBarFullscreenTest` checks content bounds with a
  cutout on either side and a narrower navigation inset on the same edge.

- **Website hidden-status-bar mode kept the landscape cutout strip (2026-10-20)**:
  WebsiteActivity has independent status/navigation bar settings and its own inset listener;
  fixing the shared fullscreen helper did not change it. Ignore cutout insets on every edge
  when the status bar is hidden, preserving visible navigation and keyboard insets. The
  Chromium fullscreen overlay likewise fills the cutout area. `WebsiteFullscreenTest`
  covers either side, navigation visibility, keyboard space and restoring status-bar mode.
