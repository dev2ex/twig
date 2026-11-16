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
- **In "all backends" mode the top-level path segment is the scheme, not the display
  name**: schemes are globally unique and derived deterministically from the connection
  label, so once a WebDAV client has saved one as a mount point, paths do not shift
  because another server got expanded. Display names are used only for the link text
  in HTML.
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
