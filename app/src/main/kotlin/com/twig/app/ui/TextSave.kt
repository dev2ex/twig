package com.twig.app.ui

import android.content.Context
import com.twig.app.R
import com.twig.app.TextCodec
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Write text back to its original source. Originally a private implementation in
 * [TextViewerActivity]; the two-column diff's "merge this hunk to the other side"
 * also needs to write back, so it was lifted out and shared — every branch here is
 * the result of a past incident, and a copy of it would inevitably drift.
 */

/** Result of reading a text file: contents, whether it was truncated, the charset and BOM that decoded it (used when writing back). */
internal class TextRead(
    val text: String,
    val truncated: Boolean,
    /** null = no charset decoded it strictly; [text] is the result of lenient UTF-8 — which cannot be written back. */
    val charset: Charset?,
    val bom: ByteArray?,
)

/**
 * Read up to [max] bytes of text. Decoding is delegated to [TextCodec]
 * (BOM → strict UTF-8 → the user-ordered encodings from settings); the encoding it
 * recognizes also decides whether the file is editable: a strict decode means every
 * byte has a known meaning, so we can write it back as-is; when nothing decoded
 * strictly we can only show a lenient decode (illegal bytes become U+FFFD), and
 * writing that back would be irreversible damage.
 *
 * ★ A truncation point can easily fall in the middle of a multi-byte character —
 * that's **our** cut, not a flaw in the file's encoding (truncation already disables
 * editing, no need to layer another reason on top).
 */
internal fun readTextFile(file: XFile, max: Int): TextRead {
    FsRegistry.of(file).openInput(file).use { input ->
        val buf = ByteArray(max)
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        val truncated = input.read() >= 0
        val d = TextCodec.decode(buf, 0, read)
        return TextRead(d.text, truncated, d.charset ?: if (truncated) Charsets.UTF_8 else null, d.bom)
    }
}

/**
 * Strict UTF-8 decode: returns null if the input is not valid UTF-8 (no lenient
 * substitution).
 *
 * The two-column diff's "merge to other side" uses this to decide whether each side
 * is writeable. U+FFFD produced by lenient decoding, written back, is **irreversible
 * damage**, so any write path has to clear this gate first.
 */
internal fun strictUtf8(bytes: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

/**
 * Save back to the source. Default path is "write a same-directory temp file → delete
 * the original → rename the temp into place":
 * [com.twig.core.FileSystem.openOutput] truncates the original and writes into it in
 * most implementations, so a half-finished write over the network leaves a fragment
 * and the original is gone forever. Sources that advertise
 * [com.twig.core.FileSystem.atomicOverwrite] (zip: rewrite the whole archive to
 * .twigtmp and replace) just write directly, saving two extra whole-archive rewrites.
 */
internal fun Context.writeAtomically(file: XFile, bytes: ByteArray) {
    val fs = FsRegistry.of(file)
    if (fs.atomicOverwrite()) {
        fs.openOutput(file).use { it.write(bytes) }
        return
    }
    val parent = fs.resolve(file.parentPath)
    val tmp = fs.createFile(parent, "${file.name}.twigtmp")
    runCatching { if (fs.exists(tmp)) fs.delete(tmp) } // clean up leftover from a previous failed attempt

    // ★ "Can modify this file" and "can create a new file in this directory" are two
    // independent permissions (SMB/NTFS's FILE_WRITE_DATA vs the directory's
    // FILE_ADD_FILE). In practice some shares only grant the former — creating a temp
    // file comes back as STATUS_ACCESS_DENIED. The user clearly has permission to
    // modify this file, so we shouldn't fail to save just because we picked this
    // implementation, so we fall back to directly overwriting the original (no atomic
    // guarantee, but it does save).
    // The fallback only fires for "open failed": a failure mid-write is a network/disk
    // issue, the fallback would still fail, and would truncate the original file —
    // at that point the original is still intact, so failing more honestly is safer.
    val out = runCatching { fs.openOutput(tmp) }.getOrNull()
    if (out == null) {
        runCatching { if (fs.exists(tmp)) fs.delete(tmp) }
        fs.openOutput(file).use { it.write(bytes) }
        return
    }
    try {
        out.use { it.write(bytes) }
    } catch (e: Throwable) {
        runCatching { if (fs.exists(tmp)) fs.delete(tmp) }
        throw e
    }

    // ★ Past this line, tmp is the only complete copy of the content, and the
    // original file is about to be deleted — whatever fails later we must **not**
    // delete tmp again (an earlier version cleaned up here too, which meant wiping
    // out what the user had just written). Just surface tmp's name so the user
    // can rescue it.
    if (fs.exists(file)) fs.delete(file)
    try {
        fs.rename(tmp, file.name)
    } catch (e: Throwable) {
        throw FsException(
            getString(R.string.err_saved_but_rename_failed, tmp.name, file.name, e.message ?: ""),
            e,
        )
    }
}

/** Whether this entry can be written right now: the whole-source read-only case is covered by `writable()`; the per-entry writable bit is checked live. */
internal fun canWriteTo(file: XFile): Boolean {
    val fs = FsRegistry.of(file)
    // The default-true canWrite is not trustworthy (an XFile built from intent/path
    // never asked the source), so resolve once.
    return fs.writable() && runCatching { fs.resolve(file.path).canWrite }.getOrDefault(true)
}
