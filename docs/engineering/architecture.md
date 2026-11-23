# Filesystem architecture and modules

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

Every backend (local / archives / FTP / SFTP / SMB / WebDAV / restic) presents the same
`FileSystem` + `XFile` interface to the UI, and cross-backend copy/move goes through
`CopyEngine` streaming over `openInput()/openOutput()`. **Adding a backend = adding one
FileSystem implementation, with zero changes to the UI or to copying.**
Every `XFile` carries a `scheme`; `FsRegistry.of(scheme)` finds the matching FileSystem.
Multiple servers of the same kind each get a unique scheme.

**Optional capability interfaces** (`core-fs/MediaServer.kt`): `FileSystem` itself is the
floor that "every backend must answer"; capabilities that only make sense for a few
backends get their own interface, implementations opt in with `: PlaybackProgress`, and
callers always do `FsRegistry.of(file) as? XXX` and fall back to the old path when they
get null. There are currently seven, most of them added for media servers:
`PlaybackProgress` (the backend owns playback position), `CoverSource` (the backend has
posters), `MediaInfoSource`, `LyricsSource`, `SearchSource`, `EpisodeSeries`, `SizeProbe` (the listing
has no byte count; a row can ask for its own — `:app`'s `SizeProbes` drives it per bound row,
never per listing).
Before adding a capability, decide whether *every* backend should be able to answer it —
if yes it belongs in `FileSystem`, otherwise it goes here; don't make 20 implementations
each write `= null`.

`StorageSpaceSource` (`core-fs/StorageSpaceSource.kt`) optionally exposes the capacity
or quota applying to a directory, with available/used byte counts and a checked total.
Its blocking query is for worker threads; `knownStorageSpace` is a listing-populated,
IO-free snapshot for rendering. Unsupported or invalid quota data stays null, not
zero. Protocol behavior is documented in [WiFi sharing](wifi-sharing.md#storage-capacity-over-webdav).

`SourcePresentationSource` (`core-fs/SourcePresentation.kt`) optionally supplies IO-free,
display-only source type/name information. It never changes `XFile.name` or paths;
Twig WebDAV uses it for the immediate source rows of a marked all-sources endpoint.

`UnixMetadataSource` (`core-fs/UnixMetadata.kt`) is a read-only capability for Unix properties.
Local storage uses an injected Android `lstat` reader, with root/Shizuku fallback only for
failed reads. `XFile.unixMode` carries the entry's own mode into listings; render its permissions after
folder counts and before size/time, without bind-time filesystem calls.
`Prefs.unixPermissionsOctal` defaults on and switches the list between octal and symbolic
notation. Octal labels have an outline shield matching count-icon size, tint and opacity.
The format choice participates in `uiSignature` and configuration backups. The independent,
default-off `Prefs.unixPermissions` display setting controls this row only, participates
in `uiSignature` and is included in configuration backups. The Unix properties tab stays
available with the setting off. When counts are enabled, reserve both count slots for rows
with permissions, including files and pending directory counts, when space permits.
`MetadataRow` reserves the trailing size/time first, then permissions, then both count slots;
leading groups that do not fit get zero layout bounds without changing their binding visibility.
They return on widening, and oversized size/time text truncates at the start.
The counts container exposes its first child's baseline so all metadata
text aligns across the nested layout. Numeric permissions, UID/GID, access/status-change times and available SELinux context belong in a dedicated
Unix properties tab, after all other tabs including Hash. Its initial loading tab uses listing metadata only;
unsupported backends have no Unix tab. General retains modification time and link targets.
`ctime` is inode status change, never creation time. Symlink attributes describe the link;
its listing size must still describe the followed stream for copy/resume checks.

## Modules

| Module | Purpose |
|---|---|
| `:core-fs` | Pure JVM: `XFile` / `FileSystem` / `FsRegistry` / `CopyEngine` |
| `:fs-local` | `LocalFileSystem` (java.io.File) + `priv/` privileged fallback (`PrivilegedShell`, shared by root and Shizuku) |
| `:fs-archive` | `ArchiveFileSystem` base + Zip (read/write, auto-detects GBK) / 7z (read-only) / tar (read-only, entries read by offset slicing) / gz·xz·bz2·zst (`SingleFileSystem`, one entry, stacks into tar.gz; zstd's decoder is injected by `:app` because this module is plain JVM); `ArchiveWriter` packing (zip/7z) across backends; encrypted archives (`ZipAes`/`ZipCrypto`/`ZipWriter`) |
| `:fs-archive-rar` | `RarFileSystem` (RAR4 + RAR5, read-only). **A separate module purely for licensing** — junrar is non-free and only ships in the full flavor, see [docs/lessons/rar-and-fdroid.md](../lessons/rar-and-fdroid.md) |
| `:fs-network` | `FtpFileSystem` / `SftpFileSystem` (SSHJ) / `WebDavFileSystem` (hand-written PROPFIND) / `S3FileSystem` (hand-written SigV4 + REST) / `JellyfinFileSystem` (Jellyfin + Emby, read-only virtual tree) |
| `:fs-smb` | `SmbFileSystem` — libsmb2 via NDK/JNI, read/write |
| `:fs-restic` | Read-only decrypting reader for restic repositories (pure Kotlin) |
| `:fs-zstd` | zstd JNI: `NativeZstd` (whole-block, restic) + `ZstdInputStream` (streaming, `.zst` archives) |
| `:git-lite` | Mini git in pure Kotlin (`GitRepo`/`ObjectStore`/`IndexFile`/patience `Diff`) for browsing worktree status and history; worktrees/submodules via `GitLayout` + `WorktreeGitFs` |
| `:app` | Dual-pane UI, permissions, mutations, viewers, terminal, SAF fallback, WiFi sharing service, website bookmarks and customization |

## SMB authentication

SMB2/3 uses the bundled libsmb2; SMB1 is an explicit, separate backend, never an
automatic downgrade after a connection failure. New connections leave the username
blank. Blank username and password request anonymous authentication; an explicit
`guest` with a blank password remains a guest login. Saved credentials are preserved.
The native credential bridge passes a NULL password only for anonymous access, not
for named accounts with empty passwords.

Session setup retains guest/anonymous flags, including locally known anonymous NTLM
authentication when a server omits its flag. Those sessions have no signing key and
are excluded from SMB3.1.1's mandatory TREE_CONNECT signing rule. Required signing
or encryption still rejects guest/anonymous sessions; authenticated sessions retain
normal signing/encryption. Connection teardown clears the session flags.

`python3 tools/test_smb_guest.py` builds the vendored library and tests disposable
`dperson/samba` fixtures on Linux. It covers SMB2.1/3.1.1, guest and anonymous
connections, share enumeration, file operations, named authentication, and client/server
signing and encryption requirements. It requires a cached Docker image and does not
publish ports or mount host directories.

## Navigation and metadata APIs

**`resolve()` is for navigation; `stat()` is for metadata; `exists()` answers existence**
(settled 2026-09-18). Six backends return an optimistic stub from `resolve` (SFTP/SMB never
stat, FTP and restic assume a directory, an archive invents one, SAF drops the display name),
which is why building a file to write from it produced a directory and trusting its size
stopped network audio on the first read. `stat(path): XFile?` is the honest one — real type,
size, time, display name, or null. Its default lists the parent and matches **by path**
(a media server's name is a title, a SAF path is a URI), and local / archives / restic /
SFTP / WebDAV / SAF override it with a real stat. ★ For many entries of one directory, list
that directory once instead (`MusicEngine` and `CompareActivity.statSides` batch on purpose).
