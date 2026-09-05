# WiFi sharing

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Reworking "all backends" mode (★ 2026-08-10, 0.96.0 → 0.97.0)**: the first version of that
  mode **had not a single unit test** (all cases ran against "a specific directory"), and three
  wrong assumptions made it to real devices: the page showed four entries
  (`Archive`/`7z archive`/`RAR archive`/`Share`) that error on click, "Apps" could be expanded
  but nothing could be downloaded, and any directory with a Chinese name failed to open. The
  lesson: **a new mode needs tests covering that mode** — do not expect "the other mode is
  tested" to protect it. Three root causes:
  - **`FsRegistry.all()` ≠ browsable roots.** zip/7z/rar are containers mounted onto a host
    file and their `root()` throws `Archive must be mounted via rootOf(archive)`; SAF needs a
    directory tree to be picked first; `share` is a relay for incoming content:// URIs. The
    single criterion is: can `root()` return normally? If not, do not list it. Also
    `LocalFileSystem`'s root is `/` (which the user sees as a pile of `acct`/`apex`), so it is
    split into two backends: "Internal storage"
    (`Environment.getExternalStorageDirectory()`) and "Root directory".
  - **Never assume "URL path segments joined = `XFile.path`".** `AppsFileSystem`'s path is a
    package name (`/user/com.tencent.mm`) while `XFile.name` is `WeChat 8.0.1.apk`, and SAF's
    path is a whole document URI — a URL can only contain display names, so joining them and
    resolving is guaranteed to fail. `ShareRoot.resolve` now **walks down from the backend
    root listing directories and matching by name**, obtaining the real XFile; the cost is
    absorbed by a 32-entry / 5 s `DirCache` (browsing is a level-by-level descent anyway, so
    all the ancestors are in cache).
  - **Links must be built from the undecoded path.** Taking the decoded `req.path` as a prefix
    and `encodeSegment`ing the child's name produces a half-encoded URL ("decoded prefix +
    encoded last segment"), so one directory name with a space or non-ASCII character breaks
    the whole subtree. `HttpRequest` therefore keeps both `path` (for **addressing**) and
    `rawPath` (for **building links**), and `ShareServerTest.links into a non-ASCII directory
    can be followed all the way down` requests exactly the hrefs the page hands out, so any
    prefix/suffix encoding mismatch fails.
  - Also: when a directory cannot be listed, **do not return a bare 500** — render the page as
    usual with the exception message in an error bar at the top. "Cannot open" and "opened but
    empty" look identical in a browser, and the reason is the only thread to pull on.
- **Style changes on the share page must actually be rendered and looked at (★ 2026-08-10)**:
  assertions cannot catch visual problems. Two were hit in the same round, both "the HTML is
  completely correct, the CSS does not error, and nothing is on screen":
  - **Icons are fill-rendered, so paths must enclose an area.** The download icon's vertical
    bar was written as `M12 3v10.2` (a zero-width segment), so only the arrowhead was drawn
    and the row ended in a lonely `˅`. Do not copy paths from a stroke-based icon set.
  - **Small icon buttons like `.act` need an explicit `padding:0`**: the generic
    `button{padding:7px 14px}` still matches them, stuffing 28 px of padding into a 29 px
    square, and flex squeezes the svg inside to **1 px wide**. The symptom was "the rename and
    delete buttons vanished, only download is left" — because download is an `<a>` and does
    not inherit button padding.
  - Likewise `[hidden]` needs `!important`, otherwise `button{display:inline-flex}` overrides
    it and `<button hidden>` stays visible in the toolbar.
  - The walkthrough method is in `DumpPageTest`:
    `TWIG_DUMP_DIR=/tmp/page ./gradlew :app:testReleaseUnitTest --tests "*DumpPageTest"`
    dumps the HTML, then Playwright screenshots it and queries `getBoundingClientRect`
    (that 1 px width was measured that way; by eye you can only tell "it is not showing").
- **Both WiFi sharing bugs were caught by unit tests (★ 2026-08-10,
  `ShareServerTest`/`MultipartTest`)** — on a real device they show up as "the file I
  downloaded is corrupt" and "I copied it, but the source disappeared", which are nearly
  impossible to diagnose after the fact, while one wire-level test pins them down. Before
  changing this area, run
  `./gradlew :app:testReleaseUnitTest --tests "com.twig.app.share.*"`:
  - **Multipart boundary matching must validate the two bytes that follow.** Matching only
    `\r\n--boundary` means that if the body happens to contain that prefix (the upload *is* a
    multipart document, or a binary file simply contains those bytes) it is taken as a
    boundary and the file is cut in half, with valid-looking data on both sides of the cut.
    RFC 2046 says only `--` or CRLF may follow a delimiter, so looking at two more bytes
    eliminates the false match (`Multipart.isDelimAt`).
  - **WebDAV COPY/MOVE cannot just call
    `CopyEngine.transfer(listOf(src), destDir, move)`**: that API means "copy into this
    directory keeping the name", so a rename has to be patched on afterwards. But COPY is
    perfectly allowed to target the source's own directory (`COPY /a.txt → /b.txt`), and then
    it copies the file onto itself and renames the **source** to the new name, making the
    source vanish. It now goes through `ShareHandler.transferAs`: files stream directly to the
    exact destination entry, and directories mkdir first and then move children. Also, the
    **"source and destination are the same path" 403 check must come before the delete that
    overwrites an existing destination**, or that delete removes the source itself.

