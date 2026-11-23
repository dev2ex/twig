# Viewers, thumbnails, Git and terminal integration

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

## Viewers and playback

Viewers: `TextViewerActivity` / `HexViewerActivity` / `ImageViewerActivity` /
`MediaPlayerActivity` (ExoPlayer + ffmpeg software audio decoding).
The image viewer plays GIF and animated WebP on Android 9+ using the platform decoder;
Android 7/8 and image comparison retain static frames. Only the displayed animation
owns a decoder, outside the bitmap cache and prefetch. Pause it when leaving the
foreground or switching images; never overlay static hi-res regions on live frames.
Rotation wraps the live drawable and forwards invalidations. Detach the previous
drawable before wrapping it, so ImageView does not clear the new callback chain.
Music inside mounted restic repositories and archives uses an in-memory queue
(`Playlist.TEMP`), with same-directory siblings and ordinary playback controls.
These tracks cannot be favorited, saved into playlists or exported; player exit
clears the queue. Resolve them through the live filesystem, without reopening a
repository or archive from a persisted descriptor.
Network music uses `AudioCache` to share disk blocks between playback, waveform
reads and prefetch. Entry locks protect state only: remote open/close runs outside
those locks. Eviction, retention changes and cache clearing invalidate entries and
clean up local files immediately, with remote reader closure on a single background
worker so a slow backend cannot block the UI. Late opens cannot revive disposed entries.
The video player has a session-only loop toggle floating below the toolbar at the
right, sharing control visibility and the music player's repeat icons. It defaults off
and survives activity recreation. Looping repeats the current video before auto-next;
opening/ending skip marks apply on every pass. With looping off, auto-next follows its
setting; otherwise playback stays open with controls visible, and Play restarts from zero.
Continue Watching (`PlaybackStore`) retains a `pendingNext` destination after confirmed
local/file-backend episode completion, including automatic ending skips. This is a zero-position
destination, never watched progress: quick exits, buffering and failures leave it intact;
qualified progress replaces it. Reuse the loaded episode queue, independently of auto-play,
and never create it for manual episode changes or server-owned playback. Existing next-episode
progress wins. Only durable local/saved-connection locations can become destinations.
`Prefs.startVideosWithoutAudio` defaults off and disables audio tracks before preparing each
newly opened video/episode. It is a player setting included in `twig_prefs` backups, independent
of remembered tracks. Startup policy never records a manual choice; existing audio controls can
enable sound for the current video. Disabled audio does not request audio focus; manually
enabling a track restores normal focus handling. Audio-only playback is unaffected.
**Playback choice memory** (`MediaPrefStore` + `TrackMatch`): the audio track, the subtitle
and the picture scaling a user picks by hand are applied again on the next episode and the
next time the file is opened (scaling rides along because it has the same scope — every
episode of a show shares an aspect ratio — and needs no matching, the mode is the record). Two rules
carry it: the record is the track's **identity** (language / label / codec), never its index —
episodes order their tracks differently, so an index is a wrong answer waiting to happen —
and a match below `TrackMatch.MIN_SCORE` selects **nothing**, because playing an episode in a
language nobody asked for is worse than one tap in the dialog. An external subtitle is stored
as the tail of its name relative to the video's stem (`.chs.srt`), the only part that is the
same for every episode. Scope is per file, falling back per series (`Episodes` prefix, or the
queue's first episode for a media server, whose names are titles); only deliberate choices are
written, never what the player auto-selected, or one wrong default would be frozen forever.
One switch covers all three (`Prefs.rememberMediaPrefs`) — they are one wish, not three.
The hex table itself is `HexPane` (one RecyclerView + one `HexSource` + the row rendering),
shared by the viewer and `HexCompareActivity`; highlights come from a single `hits` hook, so
search hits and byte differences take the same rendering path.

## Git

Git: `GitActivity` (status/history), `DiffActivity` (two-column patience diff);
SFTP repositories run git remotely over exec via `SshGitData`.

## Terminal to pane navigation

**Terminal → pane** (`TerminalActivity.locateCurrentDir`): "Locate current directory" takes one of
two routes, and which one depends on whether the directory is in the pane's tree at all.
**In it** — a local path, or a remote one inside the subdirectory the SFTP connection mounts —
it is an ordinary `MainActivity.revealIntent`, so the user lands in the tree they know and the pane
keeps the result. **Outside it** there is no row to reveal, so `MainActivity.locateIntent` pins the
directory on top of the tree like an externally opened archive (`PaneViewModel.mountLocated`) and
the back key takes it down, restores what was there and returns to the session — which is why that
route does not finish the terminal. Reaching such a path needs a filesystem that can:
`Connections.sftpRootTwin` registers a second one for the same server rooted at `/`, kept for the
life of the process (a copy in flight resolves its filesystem by scheme on every file).
Where the directory itself comes from is its own problem, see
[docs/lessons/terminal-local.md](../lessons/terminal-local.md).

## Thumbnails and app icons

Thumbnails: `Thumbs` (the engine). Two cache layers: an in-memory LruCache and a
100 MB disk LRU under `cacheDir/thumbs`; the key is md5(name:size:mtime) and is
**path-independent** (moving a file or remounting still hits, changing it invalidates).
There used to be an optional "share a `.thumbnails` folder next to the file" layer;
it was removed entirely on 2026-08-06. Network images are gated by a preference —
when off, only the embedded EXIF thumbnail in the JPEG header is read; PDFs need a
seekable real file so they are generated for local files only. Files inside archives
count as "network" (the host may live on SMB). Failures get a 60 s cooldown (not a
permanent blocklist); the generation queue is FIFO with 2 threads; collapsing a
directory calls `cancelPending` to drop its queued work.
**App icons do not go through thumbnails** (2026-08-06): icons for APK files and for
"Apps" tree entries (scheme `apps`) come straight from `FileIcons` asking the
PackageManager (keys `apk <path>` and `pkg:<package>`), asynchronously and with its own
cache, unaffected by the thumbnail preference. Routing them through the thumbnail
pipeline would only render the same image twice and burn a second copy of disk cache
and a queue slot.
Package directories in Android/data, Android/media, Android/obb and private app storage
use the same installed-app icon cache (`AppPackageFolders` identifies direct children,
including local aliases and external-storage SAF documents). Missing apps retain the folder icon.

## Network video thumbnails

**Network video thumbnails** (★ see [docs/lessons/thumbnails.md](../lessons/thumbnails.md) for the full saga): instead of
downloading the whole file, Twig parses the container itself to locate the keyframe at
1/10 of the duration and downloads only ~2 MB. MP4 parses the moov sample tables
(`findKeyframeOffset`); MKV parses EBML (SeekHead → Cues → target Cluster).
All four network backends (SMB/WebDAV/SFTP/FTP) implement an efficient `openRandom`
(`FileSystem.randomAccessEfficient() == true`), which player seeking benefits from too.

## Syntax highlighting

`CodeHighlighter`: hand-written lexical highlighting, shared by TextViewer and the
two-column git `DiffActivity` — diff tokenizes each side's whole text once and then
slices per line with `subSequence` (only that way are multi-line comment/string states
correct). DiffActivity uses the user's chosen theme when its lightness does not clash
with the current system light/dark mode; when it does (e.g. Monokai while the system is
light) it temporarily substitutes the matching default (light → GitHub Light,
dark → Monokai) so the diff area does not collide with the toolbar/status bar, which
still follow the system. This affects only that display, `Prefs.codeTheme` is untouched.
`listLeft`/`listRight` backgrounds switch to `theme.bg` and the default row text color
to `theme.fg`; added/removed row backgrounds are translucent overlays, so they read as
reddish/greenish over any background and no per-theme palette is needed —
before 2026-07-28 there was a `diffTheme()` that **unconditionally** fell back to
GitHub Light based on background lightness, because the background was not following
the theme and dark themes were unreadable. The root cause was the background, not
whether the theme should be used.
