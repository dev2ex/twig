# Text handling and editing

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Text decoding priority (★ 2026-08-18, `TextCodec` + the "Text encoding" setting)**: the text
  viewer, subtitles, lyrics and m3u each used to implement "BOM → try UTF-8 → fall back to GBK"
  separately, and inconsistently (some used a strict decoder, some looked for U+FFFD in the decoded
  string), so the same file rendered correctly in the viewer and as mojibake in subtitles. There is
  now a single entry point, `TextCodec.decode`: BOM → strict UTF-8 → the list the user arranged in
  settings, each tried **strictly**, first one that decodes and does not look like mojibake wins;
  only if none work do we decode UTF-8 leniently and mark `charset = null`.
  - **Order is priority, and GB18030 / windows-125x accept almost any byte sequence** — anything
    after them never gets a turn. The default list is ordered "pickiest first", and users
    rearranging it are on their own. `plausible()` (the ratio of control characters) only catches
    absurdities like decoding UTF-16 as a single-byte encoding; it cannot catch GBK↔Big5 confusion
    (both decode fine, the content is just wrong), for which there is no reliable general test.
  - **★ Writing back must use the encoding the file was read with** (`fileCharset` + `fileBom`).
    Always writing UTF-8 back **looks completely fine inside Twig** (it reads it too), but the user
    changed one line and every other program now shows a screen of mojibake. When the original
    encoding cannot represent the new content (an emoji inserted into GBK), `TextCodec.encode`
    returns null and a dialog asks whether to convert to UTF-8; **it never silently substitutes
    `?`**. The BOM is stripped on decode and restored on write, so it never leaks into the body.
  - **The "can it be edited" test changed from "is it UTF-8" to "was it strictly decoded"**: GBK
    files are editable too. Truncated files are a separate case — a cut landing in the middle of a
    multi-byte character is **our own doing** and does not mean the file's encoding is suspect
    (truncation already disables editing anyway).
  - **Media servers (Jellyfin/Emby) take two paths; do not assume wiring one covers both**:
    **subtitles** come from `MediaInfoSource.subtitlesOf` as XFiles you can `openInput`, so the
    bytes reach `:app` before being decoded and follow this preference automatically; **lyrics**
    are a **String** returned directly by `LyricsSource.lyricsOf`, decoded inside `fs-network`,
    which has no Context and cannot read preferences. The old `body.string()` meant "whatever the
    response header says, defaulting to UTF-8", while Emby's subtitle endpoint may return the raw
    file bytes (users' `.lrc` files are often GBK) → a page of replacement characters, looking like
    "the lyrics the server gives are simply broken". It now goes through `core-fs`'s `TextDecoding`
    hook (injected by `:app` in `TwigApp.onCreate`), the same pattern as `LocalFileSystem.changed`;
    **when the response header explicitly states another charset, that still wins**.
    ★ Any path that can hand over a byte stream should not use this hook — the hook is the exit of
    last resort, everything else should let the upper layer decode.
  - Tests: `TextCodecTest` (priority / BOM / encoding round trip, `plausible` verified by
    inversion), `JellyfinFileSystemTest.GBK lyrics are decoded with the injected decoder…` (the
    same case first asserts "without injection it is a page of replacement characters", pinning the
    symptom itself; inverted, reverting to `body.string()` fails immediately), `TextFileCharsetTest`
    (read → edit → write back, byte-level round trip) and `TextCharsetPickerTest` (the options are
    really rendered and the order is really saved). ★ Under Robolectric, **a dialog button's
    callback is dispatched through a Handler message** (AlertController's `mButtonHandler`), so
    `performClick()` alone does nothing and every assertion sees the initial values, looking like
    "the click had no effect at all" — follow it with `shadowOf(mainLooper).idle()`.
- **The text editor's caret (★ 2026-08-16, `TextViewerActivity`/`CodeEditText`)**: two symptoms,
  both rooted in the framework rather than in our state machine:
  - **"The caret is invisible until you type a character"**: the blink timer is only restarted in
    `Editor.onFocusChanged`, and `setCursorVisible(true)` only `invalidate()`s once. But the body
    text **already has focus from layout time** (`setTextIsSelectable` makes it the only focusable
    view on the page), so `requestFocus()` returns immediately and does nothing — and if the frame
    drawn happens to fall in the "off" phase of the blink, nothing ever redraws it (a text change
    goes through `handleTextChanged → makeBlink`, which is why typing fixes it). The fix is to
    `clearFocus()` and then `requestFocus()` when entering edit mode, forcing a real focus change.
  - **"At the end of a line the caret sits on top of the last character"**:
    `Editor.clampHorizontalPosition` pulls the caret left by one caret width to keep it inside the
    visible area as soon as its x reaches the text area's right edge, and a `wrap_content` text
    area's width is **exactly the width of the longest line**, so the caret reaching the end of
    that line always triggers it. Padding does not help (`viewClippedWidth` already excludes
    padding); it has to be added to the **measured width** — `CodeEditText.onMeasure` adds 3dp, and
    the word-wrap path's `maxWidth` must subtract the same amount, otherwise wrapped text is those
    few pixels wider than the viewport and gains a pointless horizontal scroll.
- **Copied images did not appear in the gallery (★ 2026-08-16, `MediaScan`)**: MediaStore only knows
  what it has scanned, and writing a file with `java.io` does not trigger a scan — without notifying
  it, an image copied from a network drive into `DCIM/` simply never appears in the gallery, and not
  notifying on delete/rename leaves dead records that error when tapped. The hook hangs off
  `LocalFileSystem.changed` (**the single exit for all local writes**), so copying, extracting,
  saving in the editor and WiFi-sharing uploads are all covered at once with no changes at the call
  sites. Three points: **a write is reported when the stream closes** (reporting at open time scans
  a 0-byte shell); `FilterOutputStream`'s `write(ByteArray,Int,Int)` **must** be overridden (the
  default forwards byte by byte); and the `:app` side only reports paths under `/storage` (the media
  library does not index private directories or cacheDir anyway) and batches them at 800 ms
  intervals — copying a directory produces one callback per file.

