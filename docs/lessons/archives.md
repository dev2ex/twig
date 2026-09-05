# Archives

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Packing (`ArchiveWriter`, 2026-08-01)**: source and destination are only ever touched
  through `openInput()/openOutput()`, exactly like `CopyEngine`, so a combination like
  "SMB directory → local zip" costs nothing extra. Key points:
  - **7z requires seekable writes** (the header is written back), so it can only be written
    to a local file — when the destination is not local, write into cacheDir and move the
    finished archive across. Zip is purely sequential and streams straight into the
    destination's openOutput.
  - **Do not use `SevenZOutputFile(File)`**: internally it uses `Files.newByteChannel`
    (java.nio.file, API 26+) while minSdk is 24; pass your own
    `RandomAccessFile(f, "rw").channel`. Likewise entry timestamps go through `FileTime`, so
    wrap them in runCatching. RandomAccessFile does not truncate, so delete the old file
    before writing a local destination.
  - **Do not use LZMA2's default 8 MB dictionary**: the encoder needs roughly 11× the
    dictionary in memory (~90 MB of Java heap), which OOMs easily on a phone when compressing
    large files; set 4 MB explicitly (`ArchiveWriter.LZMA2`), at a marginal cost in
    compression ratio.
  - Progress/cancellation reuse `CopyEngine.ProgressListener`/`Cancelled`, and the read/write
    pipeline reuses `CopyEngine.pipe` (extracted from the original `pump`), which is why
    copying and compressing share the same `PaneFragment.ProgressBox`.
- **Adding a file to a zip appends, it does not rewrite the archive (★ 2026-08-11,
  `ZipFileSystem.appendEntry`)**: the old implementation called `rewrite()` for every added
  entry, and did so by **decompressing and recompressing**
  (`getInputStream().copyTo(zos)`); `CopyEngine` calls `openOutput` once per file, so
  dragging 10 files into a 1 GB archive chewed through 10 GB. Measured on a 100 MB archive,
  adding one small file: **rewrite 1875 ms, append 1 ms**. This has **nothing to do with
  whether entries are compressed** — appending never reads the old data.
  - **New entries go at the end of the file, they do not overwrite the old central
    directory.** Overwriting would save the garbage bytes but is not rollback-safe: if the
    process is killed midway, the old central directory is gone and the whole archive is
    ruined. Appending leaves every existing byte untouched, so on error `setLength` truncates
    back to the original length and the archive is intact. The cost is that each append
    leaves the old central directory as garbage in the middle (typically a few KB), which
    delete/rename — which rewrite the whole archive anyway — clean up.
  - **The old central directory records are copied byte for byte**: old entries did not move,
    so every localHeaderOffset in them is still valid and there is no need to re-parse each
    record (`ZipWriter.Base`). The EOCD must be rewritten (offsets and counts changed).
  - **Cases that fall back to a full rewrite**: an entry with the same name already exists
    (overwriting means removing the old one), a remote host (no seekable writes), or a tail
    structure we cannot parse. `atomicOverwrite()` is still true — it asks "will overwriting
    itself leave a partial file", and overwriting goes through rewrite.
  - The test's criterion is **"the first N bytes of the original archive are byte-identical"**
    (`ZipAppendTest`), not a timing threshold: timing assertions are flaky in CI, while byte
    equality directly proves "nothing was rewritten".
  - **The UI entry point is "expanding an archive = selecting the archive root"**
    (`PaneViewModelArchiveTargetTest`). The XFile on the archive's row in the tree is the
    **host file** (`isDir = false`), and `toggleFile`'s
    `if (n.file.isDir) currentDir = n.file` does not cover it — so the green highlight sat on
    the archive's row while the paste target was still the directory outside, and expanding it
    did not let you copy into it (only tapping a **subdirectory** inside worked, so an archive
    root, or an archive with no subdirectories, had no entry point at all). Now `listChildren`
    surfaces the mounted archive root through `Listing.mountRoot` and records it in
    `mountRoots`; expanding (including the cached branch) sets it as `currentDir`, and
    collapsing returns to the archive's own directory. ★ **Do not put the archive root into
    `keyFile`**: that table holds "the XFile needed to list this again", which for an archive
    must be the host file itself (a refresh has to take the archive branch to materialize it
    and handle the password).
- **Encrypted archives (★ 2026-08-11, `ZipAes`/`ZipCrypto`/`ZipWriter`)**: encryption for 7z
  and rar comes from the libraries (`SevenZFile.Builder.setPassword` /
  `Archive(File, password)`, and even `SevenZOutputFile(channel, char[])` for writing
  encrypted archives), but **the whole zip side is hand-written** — commons-compress and
  `java.util.zip` cannot even *read* encrypted zips, and zip4j is a few hundred KB of APK.
  Settled decisions:
  - **The AES-CTR counter is little-endian and starts at 1**, while the JDK's `AES/CTR` is
    big-endian — using it directly yields garbage. Encrypt counter blocks with `AES/ECB` and
    XOR them yourself (`ZipAes.Ctr`). The HMAC authentication code is computed over the
    **ciphertext**, not the plaintext; we always write AE-2 (the CRC field is 0, so no
    plaintext checksum leaks).
  - **Encrypted entries cannot go through commons-compress's `getInputStream`** (it throws
    UnsupportedZipFeature outright); parse the local header yourself using the central
    directory's offset and seek to the first data byte. Take the 0x9901 extra field **from
    the local header**, not depending on how the library handles unknown extra fields.
    Likewise `storedSlice` must exclude encrypted entries, otherwise "slice STORED data
    directly" reads ciphertext.
  - **Distinguish "wrong password" from "corrupt archive"**. Zip has a ready-made check value
    (AES's 2-byte pwVerify / ZipCrypto's last encryption-header byte), known on the first
    read; 7z has none, so it takes fully reading the first non-empty entry — its CRC is only
    verified at the end of the stream, so reading only the beginning proves nothing (with a
    wrong password the LZMA decoder usually blows up long before the CRC). The
    `SevenZFileSystem.isChecksumIssue` heuristic is **only trusted once a password has already
    been supplied**.
  - **`ZipWriter` is purely sequential** (local header → data → data descriptor, then the
    central directory + EOCD), so like `ZipOutputStream` it streams straight into a remote
    destination's `openOutput()`. Zip64 **can only be decided before the local header is
    written** (space must be reserved in extra), based on the caller's sizeHint; if the hint
    is wrong and more than 4 GB is actually written, it throws on the spot rather than writing
    a broken archive.
  - **Encrypted archives are read-only** (`writable()` also requires `!needsPassword`): a full
    rewrite would mean "decrypt everything and re-encrypt everything", where one slip corrupts
    the entire archive — a risk far outweighing the benefit.
  - **★ Encryption detection must come after `rootOf()`** (2026-08-17): an archive's bytes are
    read through `FsRegistry.of(host).openRandom(host)`, and "who the host is" is only
    registered **at the moment `rootOf` mounts it** (`ArchiveFileSystem.hosts`).
    `needsPassword()` scans the central directory, so running it before `rootOf` makes
    `hostOf()` treat a remote path as a local file — **local archives work perfectly, remote
    ones (SMB/WebDAV/S3/…) read no bytes at all**, and `firstEncrypted`'s runCatching swallows
    that into "no password needed": a remote encrypted archive therefore **shows no password
    prompt**, and only fails when you open a file inside it. No error, wrong answer — the
    hardest kind to find. See `RemoteArchiveMountTest` (which also filled in the previously
    empty test surface of "archives with a non-local host").
  - **How to verify**: round-trip tests prove nothing about interoperability. The sample
    archives were produced by the real `7z` / `zip` command-line tools (base64-embedded in
    `ArchivePasswordTest`), and the archives we write are exported with
    `TWIG_DUMP_DIR=... ./gradlew :fs-archive:test --tests "*ArchiveEncryptWriteTest*"` and
    verified with `7z t -psecret`. The control flow — where the password comes from, what
    happens when it is wrong, whether a saved one still works — is in
    `PaneViewModelArchivePasswordTest` (Robolectric).

- **Installing an apk bundle: STORED entries can carry a data descriptor, and a streaming
  reader cannot read them (★ 2026-09-03, `ApkBundleInstall`)**: the first version streamed
  `.xapk` / `.apks` / `.apkm` into the install session with `ZipInputStream` over
  `openInput()` — the appeal being that a 1 GB bundle on SMB then costs no local disk. It
  worked on the bundles Twig itself writes (`XapkPack`) and failed on **every bundle
  APKPure hands out**, at the very first entry, with
  `ZipException: only DEFLATED entries can have EXT descriptor`.
  - **Why**: APKPure stores its apks uncompressed *and* sets general-purpose flag bit 3, so
    the local header's crc and both size fields are zero and the real values trail the data.
    For a DEFLATE entry that is fine — the decompressor itself knows where the stream ends —
    but for a STORED entry there is no end marker at all, so a single-pass reader cannot know
    how many bytes the entry has. The JDK does not guess; it refuses. The information exists
    only in the **central directory**, at the end of the file.
  - **Fix**: read the bundle through `ZipFileSystem` like everything else in Twig
    (commons-compress over a seekable channel, central directory only). The "no local copy"
    property survives — a seekable read over SMB/WebDAV fetches the directory plus the apk
    bytes, nothing more — and two things get better: exact entry sizes for
    `session.openWrite`, and "there is no apk in here" reported before a byte is written
    rather than after the whole file has been read.
  - **The lesson generalises**: `ZipInputStream` is not a cheaper `ZipFile`. Any zip written
    by a tool that streams its output (which is most of them) may carry descriptors, and
    inside Twig there is never a reason to reach for the JDK's streaming reader — the
    project's own zip layer already reads remote archives without downloading them.
  - Test: `ApkBundleEntriesTest` hand-writes zips in exactly that shape (STORED, flag bit 3,
    zeroed local header, trailing descriptor) and reads them back byte for byte — a
    round-trip through our own writer would not have caught this, because our writer does
    not produce descriptors.
