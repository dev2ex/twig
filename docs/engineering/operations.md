# Selection, transfers and external file access

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

## Selection and relative output paths

Checked files belong to a pane-owned `PaneSelection`, keyed by filesystem identity,
rather than visible adapter rows. Checks span directories and survive view
recreation; folding clears checks for rows that disappear, while other visible branches
retain theirs. Aliases share one check. The path-bar count and file context menus open a selection review
with individual removal and Clear all, including when the path bar is hidden. Directory checkbox cycles and virtual select-all
affect only their branch; range selection stays within displayed siblings.
Operations normalize selected ancestors/descendants using cached tree parents (including
opaque SAF/media paths), falling back to lexical parents only for absolute paths.
Copy/move and compression confirmations offer default-off Keep directory structure and
preview relative outputs. `SourceLayout` starts at the highest selected/containing
directories, never adding their ancestors: `/photos/trip/a` and `/downloads/docs/b`
become `trip/a` and `docs/b`. Repeated anchor names receive stable numbered prefixes.
One shared highest directory supplies the action-strip archive name regardless of
the structure option; structured compression omits that directory inside the archive. Independent top-level directories
retain their prefixes. Compression from a file context menu names the batch after
the pressed entry (file stem or full directory name), even when multiple items are
checked. Structure/format toggles preserve manually edited names. An empty relative
archive path means pack that selected directory’s contents at archive root; files
must always have nonempty safe paths.
Frozen relative paths travel with the transfer work and its retries; intermediate
destination files fail rather than being replaced by directories. Flat compression
rejects duplicate top-level names; archive writing rejects duplicate/conflicting entry
paths in all formats, keeping an existing archive intact on failure. Destination
selection still uses the opposite pane’s current directory, independently of its checks.

## File comparison

File compare: ★ **a listing that fails is unknown, never empty** — `scanCompare` marks such a
directory `PairState.ERROR` (no children, sync skips it), and `statSides` / `lookupChild`
report failure separately from "not found". Reading a failed listing as empty turned the
other side's contents into "extras" that mirror sync deleted (2026-09-17 review).
`CompareActivity` (directory tree) dispatches a pair to `ImageCompareActivity`
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

## Intents, pickers and sharing files

Interop with other apps: two ways in (`SEND` → `ShareTargetActivity` "copy to…";
`VIEW` → `ViewIntentActivity` "open with Twig") and one way out
(`OpenFiles.openWith` exposes a streaming `content://` via `StreamProvider`).
Plain shared text uses the same destination picker with an editable filename and
UTF-8 bytes carried by `ShareSourceFileSystem` as an internal data URI. Incoming
data URIs remain forbidden by `IncomingUri`; attachments take precedence over text
captions. Text sources are self-contained so retries need no Activity or cache file.
File picking, the share destination picker and the main screen share `view_panes.xml`,
`PaneLayout` and the `PaneSwitch` toolbar control: single-pane mode uses one continuous
track with the strip between panes; dual-pane mode shows both panes around the middle strip.
`PickerBrowser` shares both picker screens' navigation, strip actions and pane-switch
behavior. File picking also supports locked-source panes and retains its file/path/directory
result modes. Each picker keeps independent root visibility and navigation; the toolbar
switch or horizontal swipe changes the active pane. Only that pane controls the destination
button, through a replaceable `enableOnWritableDir`
observer. The active side and edited filename survive Activity recreation.
`SortDialog` is shared with the main screen; picker strip actions delegate search
and history to the active pane, while compress and WiFi sharing stay hidden.
Both pickers follow `Prefs.fullscreen` through `StatusBarFullscreen`, including
resume and window-focus changes.
Copy/move and compression confirmation default to the other pane's directory
but can choose another writable directory directly in the opposite resident pane.
Main temporarily pins that pane active and shows a destination bar; source touch,
keyboard/accessibility focus and destructive/context actions are blocked while choosing.
Copy/move choose missing/read-only defaults first, followed by confirmation; Cancel/Back returns
to the source, restoring an existing confirmation or keeping the checked files.
Preserve the clipboard independently. A pane-owned in-memory draft retains sources,
mode and choosing flags across rotation; process death discards it. Other hosts keep
the directory-picker fallback through `PickerActivity`.
Confirm only writable targets outside the source directory/subtree, using the shared
clipboard destination checks. Compression permits the sources' containing directory,
while still rejecting selected directory subtrees. Its separate `CompressConfirmDialog`
retains filename, format, move mode and password in the shared `DestinationState` draft;
passwords never enter saved view state and are cleared when the draft is discarded.
Compression opens its form without a target too; "Save to source folder" uses the
known writable common parent and leaves the other target intact until replaced.
ZIP/7z offer Fast / Standard / Store, with the level remembered. Password-protected
7z defaults to encrypted file names, with an opt-out; ZIP file names stay visible.
Long-press transfers remain immediate for valid targets;
otherwise choose a target or show confirmation. Source-size planning is shared across recreation.
Outgoing system sharing sends directly readable local files and known-size remote
files up to 512 KiB directly. If a batch includes a larger or unknown-size nonlocal
file, prepare its nonlocal sources in a separate cache as `Transfers.Work.Share`, using `TransferService` and `TransferBox`
for progress, cancellation, background execution and notification reopening. A headless
`ShareTransfers` fragment only bridges resumed UI to the completed local URIs; there
is no separate share dialog/ViewModel. Finished shares retain their cache leases until
handoff, independently of ordinary transfer results. Preparation completion suppresses
the success toast; notification and vibration follow ordinary operation preferences,
except an entirely cached share does not vibrate.
Preparation errors still show a toast.
External `ACTION_GET_CONTENT` selections in `PickerActivity` use the same preparation
policy before returning granted URIs to the caller. Each picker owns its result by a
saved ID: other resumed screens cannot consume it or launch a share chooser. Finishing
the picker cancels its preparation and releases unclaimed results; internal imports
and path/directory selections keep their existing streaming/path behavior.
Completed files are reused by scheme/path/name/size/mtime when size and time are
known; partial files are removed on failure/cancellation. Cache hits refresh the
24-hour idle retention, and the next preparation prunes expired files except those
held by an active preparation. "Open with" continues to stream.
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

## Trash

Trash: `TrashStore` keeps local shared-storage deletions on their original volume;
`DeleteUi` distinguishes recoverable and permanent deletion across pane/viewer/compare
entry points. Confirmed deletions and trash purges use `Transfers` / `TransferService`
for background progress; cancellation stops between backend calls. `TrashFileSystem` expands in the pane; `TrashDialogs` restores or purges entries. No privileged, SAF or
network fallback for recycling. See [trash safety](../lessons/trash.md).

## Transfer retries

Transfer retries: failed copy/move/sync operations offer “Retry unfinished items” directly in
the failure dialog, along with the exact failing destination and “Skip this item”. Outside
touches must not dismiss this dialog or discard its partial files. Skipping
continues the remaining entries and keeps the skipped source (including its containing move
directory); repeated failures identify the next entry independently. `TransferCheckpoint` belongs
to that operation in memory only; there is no task history or persisted queue. Completed roots/files are skipped on retry; owned UUID
`.twigpart` siblings retain partial bytes until retry, success, cancellation or dismissal.
`ResumableWrite` is an explicit optional capability (local direct paths, FTP, SFTP, SMB1/2/3, NFSv3/v4);
other destinations use `SafeWrite` and restart incomplete files. Resume requires known source
size and modification time. Original targets survive interrupted writes; only a complete
temporary file can replace them. A moving retry must still finish a failed source deletion.

## Completion feedback

Operation completion: `OperationFeedback` tracks resumed Twig activities and checks
screen/keyguard state; a detached progress dialog does not mean the app is in the
background. Foreground completion notifications and completion vibration are
independent, default-off preferences. Background/locked-screen results retain a
notification; transfer failures also retain a notification in the foreground, independently of the
successful-completion notification preference. Cancellation still suppresses result alerts.
Completion also requests a toast regardless of app visibility.
Running transfers always keep their progress notification.
`Transfers.finish` handles feedback and service shutdown on the main thread before
notifying UI observers, which only refresh their views. Never decide whether to
post a result from `Transfers.ui`: completion itself clears that observer, making
the old worker-thread check race with dismissal of an open progress dialog.
Completion vibration uses alarm alert attributes, so silent ringer mode does not
suppress it and background completion is allowed. Do not substitute touch usage:
Android restricts that usage for background processes. See the
[Android haptics guide](https://developer.android.com/develop/ui/views/haptics/haptics-apis).
