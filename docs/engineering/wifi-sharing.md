# WiFi sharing service

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

## Service architecture and protocol behavior

**WiFi sharing** (`com.twig.app.share`, 0.96.0): Twig's only "others connect to Twig"
path. `HttpServer` (HTTP/1.1 on a bare ServerSocket: request parsing, Basic auth,
Range, keep-alive, chunked request bodies, 100-continue) + `ShareHandler`
(GET/POST for browsers, PROPFIND/PUT/MKCOL/MOVE/COPY/LOCK for WebDAV clients) +
`WebUi` (self-contained HTML/CSS/JS with streaming `Multipart` upload) + `ShareRoot`
(URL path ↔ XFile) + `Discovery` (UDP probe/response) + `ShareService` (foreground
service). The UI is only `ui/ShareDialogs` (config/status dialog + scan dialog), there
is no separate Activity; the entry point is in the **action bar** (`as_share`), the icon
becomes a filled fan while the service runs (`syncShareIcon`, refresh all three action
bars), and the shared scope defaults to the green-highlighted
`PaneViewModel.currentDir` — there is no directory picker in the dialog because the
entry point sits next to the file tree and the location was already chosen before the
dialog opened. A few settled decisions:
- **Read-only by default**; every write method's check funnels through
  `ShareHandler.requireWrite` in one place, not scattered across handlers.
- **In "all backends" mode top-level segments are stable source addresses**, such as
  `WebDAV-NAS` and `External-SDCARD`. Connection collisions use deterministic suffixes;
  old scheme addresses and previous lowercase source addresses remain aliases. Removable-volume addresses are persisted so
  localization and hotplug do not change saved mount points.
- **`..` is always rejected** (`ShareRoot.segments` returns null), and uploaded file
  names are reduced to their last segment — in this feature path traversal means
  handing out things outside the shared directory.
- **`DAV: 2` must be advertised and LOCK must be implemented** (even as a fake lock
  that does not actually lock): macOS Finder and Windows Explorer mount read-only as
  soon as they see no LOCK support, which would make writing pointless. Likewise
  `PROPPATCH` must answer 207 rather than 501, or Finder decides the copy failed and
  deletes the file it just uploaded.
- **The WakeLock is held only while requests are in flight** (`HttpServer`'s `onActive`
  callback); when idle the foreground service is enough. The WiFi lock, on the other
  hand, is held for the whole sharing session (once the screen is off and WiFi sleeps,
  the device disappears from the LAN).
- **No START_STICKY**: if the process really was killed, the sockets and threads are
  gone, and the system restarting the service would only produce an empty shell with
  `session == null`. Dying with the process is more honest.

## Source presentation over WebDAV

The all-sources virtual root exposes `{urn:twig:share}scope` with value `all-sources`.
It is included in allprop/propname or an explicit request and absent (404 when requested)
on ordinary directories and single-directory shares. Other clients can ignore this property.
The source addresses use `Internal-Storage`, `External-<volume name>`, `Root`, `Apps`,
`Trash`, and protocol prefixes such as `SMB-`, `SFTP-`, `WebDAV-` and `S3-`.
Unknown backend schemes retain their original addresses. Old addresses still resolve using
the previous collision rules, including duplicate connection names and volume addresses.

The WebDAV client requests the scope marker with its existing listing. Only a successful
marker on the endpoint itself enables source presentation for its immediate directories.
`SourcePresentationSource` supplies display-only type/name information without IO;
file names and paths stay intact for copying, navigation, and addressing. File rows and
grid names show the suffix with the corresponding source icon. Internal storage, root,
apps and trash reuse the client's localized labels. Unknown names, deeper directories,
and ordinary WebDAV endpoints keep their original presentation. No extra tree level is added.

## Storage capacity over WebDAV

`PROPFIND` supports empty-body/allprop, explicit prop, allprop/include and propname
selection. Unsupported requested properties are returned in a separate 404 propstat;
malformed XML, DTDs and bodies over 64 KiB receive 400. Existing ordinary properties
remain available, and capacity is never included in plain allprop responses.

RFC 4331 `DAV:quota-available-bytes` and `DAV:quota-used-bytes` describe the capacity
or quota applying to a collection, not its recursive file size. Local collections use
ordinary app-access `StatFs`; remote sources can opt into `StorageSpaceSource`.
The all-sources virtual root has no aggregate capacity. Each source reports its own
volume, and unavailable capacity stays unknown (404), including blocked/private paths.
Queries use a bounded ten-second cache, including failures. Read-only sharing can still
report physical capacity; capacity information does not grant write access.

Twig's WebDAV client requests quotas with its existing Depth:1 directory listing,
caches successful quota propstats for the directory itself and children, and renders
known values on the endpoint and its immediate top-level directories (including shared
internal storage and `Root`) as available/total
using the existing localized capacity text. Deeper folders retain ordinary metadata.
Rendering never triggers network IO. Total means available plus used, so quota-based servers
show the allocated quota rather than a physical drive's size. Refreshing/relisting
updates snapshots. Ordinary stat/exists requests do not ask for quotas.
