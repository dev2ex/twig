# Removable storage (SD card / USB)

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

**(★ 2026-08-26, `StorageVolumes`)** The entry points are the rows between "Internal storage" and
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

