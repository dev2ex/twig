# Tree and adapter (`PaneViewModel` / `FileAdapter`)

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [AGENTS.md](../../AGENTS.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Copy/move required selecting the other pane's destination first (2026-10-30,
  #34)**: `performCopy` returned early on a missing or read-only sibling directory,
  leaving users unable to choose a destination from the operation they had started.
  Main now chooses directly in the opposite resident pane, then shows
  `CopyConfirmDialog`; valid defaults go straight to confirmation with an option to
  choose another location. Missing/read-only defaults choose first. Cancel/Back returns
  to the existing file selection or restores a prior confirmation. Share clipboard
  checks for same-directory and directory-into-self destinations without modifying
  the clipboard. Keep a frozen source list, destination, move mode and shared size
  plan in a pane-owned in-memory draft across picker cancellation and rotation;
  process death discards it because transient source schemes cannot be reconstructed.
  The draft's choosing flags restore the target side and destination bar after rotation.
  Only the opposite pane can supply the destination; source touch and keyboard/accessibility
  focus are blocked. Convert root-relative visible bounds to screen coordinates before
  comparing raw touches, including in offset split-screen/freeform windows. Block pane-switch
  long presses before mounting a directory into the other pane; blocking only the later
  focus switch still changes the source location. Hide the clipboard bar without changing its contents, disable
  transfer/destructive actions and context menus, and leave target navigation available.
  Other pane hosts retain a directory-picker fallback. Selection only edits the draft;
  confirmation starts the existing transfer path.
  The state owns one observable size plan and its cancellable job; the draft holds
  operation inputs only. UI updates and confirmation share one target validation
  path, including rechecking a connection that disappears after picker return.
  Size planning checks the owning coroutine's cancellation before each entry and
  before returning totals. Cancelling the draft stops subsequent recursive listings;
  an already-issued blocking backend request may finish, without interrupting its
  thread or closing the shared connection. Never publish partial totals on cancellation.
  Long-press copy/move uses valid targets immediately; missing/read-only targets are
  picked before confirmation, while same-directory/self targets show the error there.
  `CopyDestinationTest` covers actual copy/move, missing/read-only/recursive targets,
  cancellation and recreation, single size planning across rotation, source restrictions,
  both copy directions, clipboard preservation, offset-window touch isolation, pane-switch
  long presses and landscape focus restoration;
  `StripWriteActionsTest` retains immediate-transfer and readable-root coverage.

- **External multi-file row selection also selected folders (2026-10-31 review)**:
  the file-only picker's tap-to-select flag applied to every ordinary tree row,
  so tapping a folder name checked an entry that confirmation later discarded.
  In external multi-file mode, only files use row selection; folders keep normal
  navigation even with an existing selection and the general row-selection preference
  enabled. Keep the external-mode branch separate from that preference, or it can
  select folders again once a file is checked. `FileFocusTest` covers both preferences,
  empty/existing selection and list/grid configurations on API 24 and 34.

- **File picking lacked the destination picker's pane switch and navigation actions
  (2026-10-28)**: `PickerActivity` still used a single-pane layout and configured its
  strip separately. Both pickers now use `view_panes.xml` and `PickerBrowser` for pane
  state, switching, strip visibility and action routing. Keep confirmation tied to the
  active pane, replacing directory/shortcut observers on each switch; inactive panes
  must not change the current confirmation state. Locked-source selections apply the
  same restriction to both panes with distinct pane indices. `PickerBrowserTest`
  covers action parity, independent roots, switching/recreation, measured rotations,
  directory confirmation, source restrictions and shortcut observers.

- **Directory imports could not select readable, read-only sources (2026-10-29
  review)**: music import reused the directory picker's save-destination intent,
  whose confirmation requires write access. Directory picking now defaults to
  writable destinations, with an explicit readable-source mode for imports.
  Both modes share the active-pane observer and disabled-button handling;
  importing must not weaken destination checks for backups or playlist exports.
  `PickerBrowserTest` checks read-only selection, unreadable/non-directory rejection
  and pane switching; `PlaylistImportUiTest` checks the import intent's access mode.

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

- **Empty local folders hide their expand indicator (2026-10-28, issue #41)**:
  local (`file`) directory rows request `DirectoryCounts` even with item counts off.
  One result updates both the indicator and optional counts; expansion/refresh also
  populates the cache regardless of that setting. Turning counts off must not clear
  this shared cache. Hidden entries follow the display setting. Unknown/failed reads
  retain the arrow, and search/Git/restic/access-hint children keep it even with zero
  visible files. Empty rows remain clickable destinations. Remote and archive rows
  also reuse confirmed counts to hide empty indicators (2026-10-30), without extra
  nonlocal probes when counts are disabled. Both callback owner views release their
  subscriptions on detach and recycle. `EmptyDirectoryIndicatorTest` covers API 24/34, shared in-flight reads,
  setting toggles, failures, recycled rows and ordinary listing/refresh reuse.

- **Folder counts are optional direct-child counts across backends**
  (`DirectoryCounts`, 2026-10-15). The metadata line starts with monochrome folder/file
  outline icons and separate numbers; date/time stays at the end. Two equal-width text-scaled
  slots keep the folder/file positions fixed; the group uses the idle selection check’s opacity.
  Zero values use INVISIBLE, preserving their
  slot, and compound drawables center icons beside the numbers. Counts follow the hidden-file
  setting. A bound row can request one background listing; ordinary expansion/refresh
  results populate the same cache and supersede a slower count request. Local probes
  use `listForSize` with only name/type metadata, retaining its permission fallback;
  other backends use their ordinary listing. Never recurse, read file contents, or infer zero
  from failure. Separate local/remote workers, 32-entry queues in displayed row order,
  512 count-only cache entries and a 60-second lifetime bound the extra work. Detached
  rows release their subscriptions. The setting defaults off and participates in `uiSignature`.
  A subordinate local-only setting defaults on; disabling it enables counts on other
  services too, excluding Jellyfin/Emby media trees (2026-10-29). Gate rendering and
  additional probes with the same scope, while cached empty-folder indicators remain
  independent. Both settings participate in
  `uiSignature` and configuration backups.
  `DirectoryCountsTest` / `FolderCountsRowTest` cover actual local reads, backend listing reuse,
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

- **Image quick-scale and the long-press menu must be mutually exclusive**
  (2026-10-28, issue #42). `ZoomableImageView` feeds both a `ScaleGestureDetector`
  and a `GestureDetector`. Double-tap-and-hold starts the former's quick-scale mode,
  but the latter can still deliver a long press on the second touch. Guarding only
  `isInProgress` is too late: the user may hold before moving far enough to start scaling.
  Suppress the menu from `onDoubleTap` until the next DOWN; also suppress it when a
  pinch begins. During scale or multiple-pointer movement, ignore ordinary scroll
  callbacks so the same MOVE cannot both scale and pan. Remember whether the touch
  scaled through UP, because the scale detector ends before the fling callback and
  must not accidentally turn a page. Ordinary double-tap mode cycling, long presses,
  one-finger panning and fresh page swipes remain supported. `ImageGestureTest` feeds
  real single/multiple-pointer events and advances long-press timers on API 24/34.

- **Invisible remote/keyboard focus (2026-10-30, #41)**: file rows and grid cells
  shared a background with only `state_activated` (multi-selection). Neither layout
  explicitly opted into keyboard focus on older Android versions. Both now accept
  focus, with a separate focused border taking precedence over the selection fill.
  Keep focus distinct from selection; requesting focus must not check a file.
  `FileFocusTest` checks both layouts and remote confirmation on API 24 and 34.

- **Encrypted PDFs reported as unreadable (2026-10-30, #34)**: collapsing the
  renderer's `SecurityException` into null hid the password requirement and retried
  identical bytes through the cache. Preserve this failure separately and close its
  fd immediately. API 35+ uses `LoadParams` for password entry/retry; older devices
  explain the limitation without offering a password field.
  SecurityException can also mean unsupported encryption, so never claim it proves
  the password is wrong. Passwords are not saved in view state. The archive-style prompt offers optional
  remembering: save only after a successful open, remove a rejected saved password,
  and encrypt it through Secrets. Configuration backups decrypt/re-encrypt the
  separate PDF password store; system backup/transfer excludes it. `PdfPasswordTest`
  checks the failure/descriptor handling, dialog branches and password remembering with
  a simulated renderer on API 24, 34 and 35; real PDF rendering still needs a device.

- **PDF password entry displayed plaintext (2026-10-30)**: calling `setSingleLine`
  after setting a password input type replaces its masking transformation. Archives
  and PDFs now share `EncryptedFilePasswordDialog`, configuring single-line behavior
  before the password type. Keep entered text out of saved view state, clear it on
  dismissal, and retain the prompt on empty confirmation. PDF password entry has no
  external-app button. Tests check the actual transformation, not just inputType.

- **Resetting root items and action panels (2026-10-30, #35)**: Reset updates only
  the dialog draft. Cancel preserves saved ordering and visibility; OK applies the
  defaults to both root panes or the shared action panel. `ConfigurationResetTest`
  checks the draft, cancellation and persistence paths.

- **OLED path and menu backgrounds (2026-10-30, #35)**: active path bars are black
  too; the existing text-alpha difference marks the active pane. Menu popups have
  their own backgrounds, independent of dialog floating colors. Override AppCompat
  popup/overflow and platform popup styles in the OLED theme. `AppThemeTest`
  checks all three popup routes.

- **Permission display format (2026-10-30, #35)**: file-list permissions default
  to four octal digits, retaining special bits. The default-on format setting can
  restore symbolic notation independently of permission visibility. Octal labels
  reuse the counts' outline-icon sizing/padding and muted text color/opacity;
  clear recycled compound drawables when switching format or binding an
  unknown-mode row. Unix properties retain both symbolic and numeric notation.

- **Old count probes delayed local counts after navigation (2026-10-30)**:
  the global single worker serialized local counts behind slow remote listings.
  Recycling only invalidated a callback tag, leaving old queue entries to perform
  filesystem work. Split direct-local and other-source workers, with bounded
  queues and background priority. Bind-time prefetch may render cached counts but
  only attached rows in visible panes request IO. Detach/recycle, pane hiding and
  Activity stop release view-owned subscriptions; a second pane can keep the same
  directory's shared probe alive. Do not cancel by file identity when collapsing
  one pane, because that also cancels the other pane's subscription. Never close a
  shared connection to abort a count: already-issued blocking backend calls may
  finish, but their abandoned results are ignored. Local per-entry processing checks
  cancellation. `DirectoryCountsTest` holds a remote request open while local counts
  complete and checks abandoned queues and shared subscriptions;
  `DirectoryCountLifecycleTest` covers prefetch, detach and hidden-pane cancellation.

- **SFTP counts still read entire large directories (2026-10-30)**: the count worker
  used ordinary `list`, which buffers and sorts every entry and reads link targets.
  `DirectoryCountSource` is an optional count-only capability; SFTP counts directly
  from READDIR attributes, following links only to distinguish directories, and
  saturates each category at 1000. Stop early only after both visible categories
  saturate; stopping after 1000 total entries would invent an exact value for the
  rarer category. Hidden entries have their own cached totals. SSHJ's filter false
  means discard, not stop; a private control exception exits its scan and closes
  the directory handle. Catch that exception inside the retry boundary so stopping
  does not tear down a shared connection. Transport retries reset the accumulator.
  Ordinary listings always remain complete for browsing, transfers and comparison.
  `SftpFileSystemTest` checks fewer READDIR requests, cancellation, connection reuse,
  mounted roots, symlinks and hidden/rare categories; count accessibility uses the
  same capped labels as the visible row.

- **Counts loaded in reverse or arbitrary row order (2026-10-30)**: LIFO preferred
  the last attached row, and pane activation iterated a holder HashSet. Count jobs
  now read their subscribers' live adapter positions before dispatch, choosing the
  topmost row first (FIFO ties across panes). This follows any displayed sort rather
  than sorting paths again. A shared job uses its earliest subscriber's position;
  queue overflow drops the lowest-priority row. Detached rows still release their
  jobs, so scrolling needs no separate newest-first policy. A running call finishes
  or observes cancellation; sorting only reorders queued work. Unit tests cover
  changed positions, reversed attachment and pane activation.

- **Count-only probes need a time budget (2026-10-30)**: allow three seconds per
  directory from worker execution, excluding queue wait. Use elapsed realtime and
  check the deadline through the streaming continuation predicate and local entry
  conversion, then again before accepting any backend result. A blocking network
  request may finish late; never interrupt its thread or close a shared connection
  to enforce this soft deadline. Timeout behaves like an unknown count, preserving
  existing cache entries and using the ordinary 60-second failure cooldown rather
  than immediately retrying. Invalidation allows a retry; successful ordinary
  expansion/refresh supersedes the failure without a deadline. Tests advance the
  clock across the exact three-second boundary, check independent queued budgets,
  reject late empty results, and verify retry and ordinary-listing recovery.

- **Remote empty folders kept their expand arrow (2026-10-30)**: count callbacks
  updated indicators only for the local scheme, even after a successful remote
  count confirmed zero visible children. Apply known counts to every supported
  directory, retaining loading/synthetic-child guards and click behavior. Ordinary
  expansion and refresh now cache counts independently of the count display setting;
  remote probes still require opting into counts for that source. Failures/timeouts
  stay unknown with their arrow. Tests cover remote/archive cached counts, hidden
  entries, synthetic children, live failures/timeouts, no extra disabled-scope probes,
  and remote ordinary-listing recovery with counts disabled on API 24 and 34.
