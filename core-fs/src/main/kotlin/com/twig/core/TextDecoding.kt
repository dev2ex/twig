package com.twig.core

/**
 * Pluggable hook for decoding text bytes into strings, used by the **few places
 * inside pure JVM modules where decoding genuinely cannot be deferred** (currently
 * only media-server lyrics: the server returns a blob of LRC text directly, not a
 * stream you can `openInput`, so the upper layer has nothing to feed into its own
 * decoder).
 *
 * Why this hook exists: the user's decoding priority list (BOM -> strict UTF-8 ->
 * the ordered list of encodings they configured) lives in SharedPreferences and is
 * implemented in `:app`, whereas `fs-*` are pure JVM modules — they have no Context,
 * and we shouldn't promote the whole module to an Android library just to read one
 * preference (that would cost the millisecond-fast pure-JVM unit tests). So we
 * follow the same pattern as `LocalFileSystem.changed`: leave a default here, and
 * let `:app` inject the real one at startup.
 *
 * When nothing has been injected, the default is **lenient UTF-8**, identical to
 * the old `body.string()` calls scattered around — so unit tests run directly
 * against `fs-*` modules see no behavioural difference from the missing app layer.
 *
 * ★ Every path that can hand back a **byte stream** should let the upper layer do
 * its own decoding rather than going through this hook (subtitles already work
 * that way: `MediaInfoSource.subtitlesOf` returns an XFile you can `openInput`).
 * The hook is an exit of last resort; use it too often and you erode the rule
 * that "decoding has exactly one entry point".
 */
object TextDecoding {

    @Volatile
    var decode: (ByteArray) -> String = { String(it, Charsets.UTF_8) }
}
