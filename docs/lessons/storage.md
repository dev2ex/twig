# Storage (internal, app clones, SD card and USB)

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [AGENTS.md](../../AGENTS.md); the other sections live
> beside this file in [docs/lessons/](.).

## Internal storage and app clones

- **Additional emulated storage shortcuts (2026-11-06, #55)**: storage-volume
  discovery for the current user alone can miss app-clone storage. On API 33+ with
  all-files access, use `StorageManager.getStorageVolumesIncludingSharedProfiles()`
  and `StorageVolume.directory` for mounted emulated volumes. This public API covers
  profiles that share media with the caller, not arbitrary users. Cache those paths
  separately from removable volumes; readable numeric directories under
  `/storage/emulated` are only a supplementary discovery source. Exclude the current
  primary storage by canonical path. Never assume the clone user id is 95. Render
  the primary first, then additional ids in numeric order, as one configurable
  Internal Storage slot with shared visibility, icons and capacity presentation.
  Pre-register all shortcut keys, protect whole roots from default destructive
  actions, and start reveal navigation at the matching shortcut. An unreadable
  parent must not discard system-reported roots. Older Android/vendor profile
  behavior still needs device verification; do not invent entries or claim that
  adding a shortcut grants cross-user access.

## Removable storage (SD card / USB)

**(★ 2026-08-26, `StorageVolumes`)** The entry points are the rows after "Internal storage" and
"Root directory", sourced solely from `getStorageVolumes()` (plus one fallback derived from
`getExternalFilesDirs`), with no new permissions.

- **★ Plugging one in did nothing, and it appeared after restarting the app — the event channels
  are simply unreliable.** Measured on this Sony device (Android 16), plugging in a USB drive: the
  system mounts the volume **invisibly to apps** (`dumpsys mount` shows `mountFlags=0`, against
  `PRIMARY|VISIBLE_FOR_WRITE` for the primary volume), `getDirectory()` returns
  `/mnt/media_rw/E1F9-09F6`, and it does not exist under `/storage` at all. Such a volume
  **fires neither `ACTION_MEDIA_MOUNTED` nor `StorageManager.StorageVolumeCallback`** — both were
  registered on the real device and `sm unmount`/`sm mount` never triggered either. So "appears
  when plugged in" and "disappears when removed" can only come from the main UI **polling every
  few seconds while in the foreground** (`MainActivity.pollVolumes`, 3 s, `repeatOnLifecycle
  (RESUMED)`, stopping automatically when it leaves the foreground). The two event channels are
  kept for ordinary SD cards.
- **★ And `getStorageVolumes()` did return that volume all along.** Round one blamed "not
  scannable" on "invisible volumes do not appear in the list" — **wrong**; the "list
  `/mnt/media_rw` on the privileged side as a supplementary source" that followed from that wrong
  conclusion **was later deleted wholesale** — it gained nothing and brought three problems: after
  removing the drive, that half only updated on a full rescan (leaving a dead volume-id row in the
  tree), the name was just a hex string, and it needed de-duplication by volume id against the
  visible volume. Do not reason about "did the system give it to us", print the scan result with
  one `Log.i` (`twig-vol`) and settle it in one go.
- **`/mnt/media_rw` is `root:external_storage 0750`, unreadable even by shell (2000)** (measured
  after `adb unroot`: Permission denied) — so opening a volume mounted there **requires elevation**
  (`LocalFileSystem.elevation` takes over automatically), and Shizuku running as shell is equally
  useless; it has to be the root-identity kind. ★ Do not take `adb shell`'s results as the truth:
  on a userdebug device adbd is root and appears to read everything.
- **Filter with `isPrimary`, not `isRemovable`**: on some ROMs the primary volume also reports
  `isRemovable = true`, and filtering by it makes internal storage appear twice at the root.
- **Getting the path**: API 30+ uses `getDirectory()`, earlier versions use the public `getUuid()`
  to build `/storage/<uuid>`; **do not reflect `getPath()`** (a restricted non-SDK interface on
  Android 10). The last-resort fallback derives it from `getExternalFilesDirs` and requires the
  `/storage/<volume id>` shape — comparing only against `getExternalStorageDirectory()`'s string
  would make internal storage look like a card on ROMs that write private directories as
  `/sdcard/…`.
- **`refresh()` must not obtain "before" via `cached()`**: that scans on demand if nothing has been
  scanned yet, so the first call always concludes "nothing changed" and the refresh that inserts
  the first card is lost (verified by inversion in `StorageVolumesTest`).
- Volume roots are treated like internal storage and the root directory, and are excluded from
  `currentSelection()` (so a whole card cannot be deleted by accident).

- **Source icons are not hardware detection** (2026-10-24): `StorageVolume` does not expose
  a public SD/USB transport flag. Explicit SD/USB descriptions choose the matching icon;
  custom labels and UUIDs default to the SD-card icon (updated 2026-11-03). On the test
  device, `opi_root` is a real SD card (`dumpsys mount`: `ADOPTABLE|SD`, `mmcblk1`).
  The fallback is a display choice, not hardware detection. Do not infer hardware from
  a UUID, or turn a one-device label into a global SD rule.

- **Register shortcut keys before walking the tree** (2026-10-24): Root is displayed
  before removable volumes. An expanded `/storage` can therefore emit a volume before
  its shortcut is drawn. Pre-register internal, root and all cached volume identities
  before rendering rows, so nested volume rows always receive `NESTED_STORAGE_PREFIX`.
  Otherwise the two entries have the same DiffUtil key merely because their order changed.
  `StorageVolumesTest` covers the expanded-root case and the new top-level order.

- **Capacity reads froze the UI during a device change (2026-11-03)**: the exit record
  showed an input-dispatch ANR, not a Java crash. The main thread was in
  `StatFs` / `statvfs`, called by `PaneViewModel.capacity` during tree rebuilding.
  Filesystem capacity queries can block inside the kernel on an unhealthy or
  disappearing mount; catching exceptions does not prevent the freeze. Render
  cached capacity values and read each root independently on the IO dispatcher,
  with one in-flight request per path and a ten-second refresh/failure cooldown.
  A blocked syscall may outlive coroutine cancellation, so never wait for it on
  the UI thread or enqueue another read of that path while it remains in flight.
  `StorageCapacityTest` verifies deferred reads, success/failure caching and a
  blocked mount that leaves rendering and other capacity reads available.
