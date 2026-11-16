# Panes, navigation and file rows

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

## Pane layout

`MainActivity` + two resident `PaneFragment`s (ViewPager2 was dropped; visibility
toggling + fling gestures). `Prefs.paneLayout` chooses follow-orientation (default),
single or dual panes; `Prefs.dualPane` drives visibility, focus, swipe handling and
directory-count subscriptions across Main and both pickers. In Main, toolbar visibility
follows orientation unless the default-off `Prefs.hideTitleBarPortrait` hides it in
portrait too. Hidden toolbar actions stay pinned above the strip with the same
separator, using one column in portrait and two in landscape. Action-strip density
still follows orientation. Hiding the toolbar disables and dims its bottom-placement
setting without clearing the saved placement; showing it restores that setting immediately.
Both settings participate in `uiSignature` and
configuration backups. `Prefs.hidePathBarSinglePane` defaults off and hides the address
bar whenever the effective layout is single-pane, including both pickers. Dual-pane
layouts retain it. The setting participates in `uiSignature` and configuration backups.
`Prefs.pathBarAtBottom` defaults off and places each pane’s address bar below its file
list in Main and both pickers, independently of toolbar placement and path-bar hiding.
Its settings row follows single-pane path-bar hiding; it participates in `uiSignature`
and configuration backups.

## Tree navigation and protected branches

`PaneViewModel` owns the navigation stack and tree expansion; `FileAdapter` renders rows.
Tapping the path bar reveals horizontally scrollable path buttons; its source icon toggles
the compact display; clicking a tree node also restores it. Buttons have square corners,
fill the bar height, omit slash separators and prefer display aliases. Only the selected
segment changes background. `pathSegments` uses
real tree keys and mount roots, retaining child segments when selecting a parent and
replacing them on another branch. `selectPathSegment` opens the selected directory with
only its direct children visible and all child directories collapsed, keeping ancestors
open; never split SAF/media-server paths. Long press and double tap still edit paths.
`TreeIndentation` subtracts the shallowest visible depth from display indentation,
with a shared 250 ms transition; partially visible rows retain their depth, and
properties cards use their owner's depth. Grid cells retain their full width.
`Prefs.autoTreeIndent` defaults on, has a settings-only toggle, participates in
`uiSignature` and is backed up with `twig_prefs`. Actual node depths never change.
Expanding a directory reuses its cached children immediately, then re-lists in the background
(`relistQuietly`) on every backend, including server and favorite roots. Only changed listings
update the rows. Expanded local directories stay live through inotify
(`PaneFragment.syncObservers`); resume also re-lists the current directory on any backend.
The tree always expands/collapses **in place**, it never "enters" a directory.
`Prefs.allowMultipleExpandedNodes` defaults off; enabling it retains other expanded
branches across folders, archives, sources and path navigation. Independent visible
loads can finish without replacing the selected destination; folding an ancestor or
Collapse All prevents pending loads from reopening hidden branches. Disabling it
restores accordion folding on the next expansion. Path buttons still collapse the
selected directory’s descendants. The setting participates in `uiSignature` and
configuration backups through `twig_prefs`.
Tapping a row's **icon** (not its name or chevron) is the "keep this branch"
gesture: a small pin at the icon’s upper-left marks that row as protected from the
accordion, so expanding another node does not fold it away (`PaneViewModel.keptOpen` / `toggleKeepingOpen`). The
icon is still a toggle — tapping it again collapses the branch and drops the
protection, and so do Collapse All and a path-button selection. Explicitly folding
any ancestor clears all descendant expansion and protection in either tree mode;
reopening shows only direct children while reusing cached listings. The protected node's
ancestors stay open as well, otherwise it would be invisible. Capture the clicked
row’s ancestor chain when pinning: favorites and source listings can share a file
key, and another visible occurrence must not change which ancestors are protected. With the setting above
enabled the gesture changes nothing (nothing folds anyway).

## Usage scan overlays

Returning from the usage treemap keeps the existing tree expansion and adds pane-local
scan totals: proportional child-row backgrounds using the selection color, recursive directory sizes and descending
size order within each listing. The scan target has no usage background; selected rows use
a stronger overlay over the selection background. These snapshots
live only in `PaneViewModel`, survive view recreation and never rewrite saved sort settings.
Analyzed directories temporarily render all entries as list rows, overriding global and
directory grid choices without saving changes; clearing the scan restores those choices.
Every descendant's proportion uses the scan target's total as its denominator.
Folding/reopening child directories, including accordion folding, retains the scan.
Folding the scan target or any ancestor discards that scan; Collapse All clears all scans.

## Directory counts

Directory counts (`DirectoryCounts`) use separate background workers for direct local
storage and other sources. Only attached rows in visible panes request probes;
prefetch does not enumerate directories. Detaching/recycling a row, hiding its pane
or stopping its screen releases that row's subscription, while another pane's
subscription to the same directory remains valid. Pending work without subscribers
is dropped. Queued counts follow live adapter positions, from top to bottom in the
displayed sort order, including after pane activation or a resort. Local entry
processing checks cancellation; an already-running blocking
backend call may finish, without holding up the local count worker. Ordinary listings
populate the shared cache, and failed reads stay unknown rather than becoming zero.
`DirectoryCountSource` optionally provides streaming count-only enumeration. SFTP
checks cancellation per entry, skips unused metadata and stops once both visible
counts reach 1000 (`999+`); rarer categories still require reaching the end to be exact.
Hidden and all-entry counts are cached separately. Never cap ordinary `list` results.
Probes have a three-second soft deadline starting when IO begins, excluding queue
wait. Streaming/local entry processing checks it; late blocking results are discarded.
Timeouts keep existing cache entries, deliver no zero and share the failure cooldown.
Ordinary expansion/refresh listings can still populate counts after any duration.
Confirmed empty directories hide their row chevron across supported backends,
retaining click/destination behavior and arrows for synthetic children. Reuse
ordinary listings even with counts disabled; extra nonlocal probes remain opt-in.

## Persistence, history and directory overrides

Persistence: connections in `ConnectionStore`, favorites in `FavoritesStore`,
preferences in `Prefs` — all SharedPreferences.
Recent locations also record directories used inside restic snapshots, storing the repository
connection/path, snapshot selector and internal path rather than a session scheme. Fixed
snapshot metadata is frozen for display; `latest` remains a dynamic alias. Restoring reconnects
and unlocks as needed, expands the repository chain, then matches snapshot rows by path ID
(their display names are dates). Favorites and Recent share the repository unlock resolver;
a live unlocked repository needs no password prompt. Missing snapshots/listings report errors
without changing the selected directory. Newer navigation or Collapse abandons pending restores.
Favorites and websites keep their stored order. Long-pressing either heading offers
inline reordering with drag handles; favorite children are temporarily hidden, websites
temporarily use list rows even in grid mode, and drops
save the order for both panes. A tap outside the drag handles or Back restores normal
expansion; scrolling leaves sorting active.
`SortDialog` provides separate sorting and view dialogs, each with defaults or overrides
for the selected directory. Resetting one leaves the other unchanged.
Defaults remain in `twig_sort` / `twig_prefs`; `DirectoryViewStore` stores only explicit
directory overrides in `twig_directory_views` (included in `.twigbak`). Sorting and
view mode inherit independently. Each has a default-off recursive flag: a directory's own
override wins, otherwise the nearest recursive ancestor wins, then the global default.
Nonrecursive overrides do not propagate or block more distant recursive ancestors.
Resolve once per directory listing/render, without scanning descendants or persisting copies;
ordinary paths derive ancestry, while opaque paths use parent links learned from listings.
Listing identities (`mountRoots` / `keyFile`) identify the containing directory, including
favorites, servers and opaque SAF paths. `FileNode.gridMode` carries its effective view
to the adapter; directory overrides are parsed per settings refresh, not per rendered row.
Network display paths share `formatLocationPath` / `Format.pathLabel` and use
`type://server/path`; aliases follow history, raw addresses are used for favorites and
playlist exports. M3U network locations use `type://server/path`; malformed single-slash
source addresses are skipped.
Internal `scheme:path` keys remain separate from display text.

## Grid view

Grid view (independent of the thumbnail preference): the same RecyclerView switches to
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

## SQLite browsing

SQLite: database files expand into dedicated table nodes; tables expand into a
pane-width virtual data grid, with row/column counts and Columns/DDL property tabs.
`SqliteSession` owns read-only source leases and bounded typed previews; `SqliteTree`
owns cancellable jobs and an eight-page cache per table. Never model tables as writable
filesystem directories or use displayed row offsets for future edits. Nonlocal sources
use disposable snapshots including WAL; opaque parents come from the tree, and failed
sibling listings cannot prove WAL absence. See [SQLite browsing](../sqlite-browser.md)
for limits, source consistency and the planned editing/query boundaries.

## Properties cards

Info card: long-press → "Properties" inserts a tab card below the file's row
(`PaneViewModel.InfoNode` + a second viewType in FileAdapter); the content comes from
`FileInfo` using system APIs only (EXIF / MediaMetadataRetriever / PackageManager;
media track details are enumerated with media3's `MetadataRetriever`).
**No-full-read rule**: anything requiring a full file read is not fetched automatically
— remote APKs show no app info, and the hash tab computes automatically for local files
but requires a tap for network ones.

## Space map

Space map (SpaceSniffer-style treemap): `TreemapView` (hand-written squarified layout;
pinch zoom is a geometric transform and the font size stays fixed, so zooming in simply
reveals more labels) + `TreemapScanner`, **embedded inside PaneFragment** (mapMode
replaces the tree in place, it is not a new screen). The back key is routed through
`MainActivity.onBackPressed → activePane().handleBack()` (up one level / exit); the
block menu and the tree's long-press menu share `commonFileActions`; "Select" on the map
stores its multi-selection in `mapSelected`, and the sidebar's copy/move/delete prefer
it via `selectionOrCurrent()`; "Show on the other side" is
`PaneViewModel.revealPath`, expanding level by level (local and connected server
schemes only).

## Storage capacity labels

Local internal/removable/root shortcuts read available/total capacity asynchronously,
with independent in-flight reads and a ten-second success/failure cooldown. Connected
WebDAV server rows and their immediate top-level directories render `StorageSpaceSource`
listing snapshots using the same capacity format; deeper folders retain ordinary metadata,
including when pinned. Unsupported servers retain ordinary metadata.
Remote labels update on listing/refresh. See [WiFi sharing](wifi-sharing.md#storage-capacity-over-webdav)
for quota semantics and protocol behavior.
