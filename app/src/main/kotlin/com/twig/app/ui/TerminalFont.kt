package com.twig.app.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.twig.app.Prefs
import java.io.File

/**
 * User-supplied font for the terminal: an imported ttf/otf is stored in the
 * app-private directory (SAF's content:// permissions are not reliable across
 * process boundaries / restarts — same pattern as SFTP private key import: copy it
 * in and manage it ourselves).
 *
 * Why a custom terminal font at all: termux's `TerminalRenderer` sizes each column by
 * `measureText("X")`, and when the measured width doesn't match `column_count *
 * column_width` while drawing, it `canvas.scale(ratio, 1f)` **only horizontally** to
 * squeeze text into the grid. The system MONOSPACE has no CJK, so it falls back to
 * Noto Sans CJK (CJK at 1.0em) while a Latin "X" is only ~0.6em; the 2-column target
 * 1.2em exceeds the measured 1.0em, so CJK ends up stretched 20% horizontally. ● (U+25CF)
 * is East Asian Ambiguous — wcwidth counts 1 column, but the glyph is fullwidth, so
 * it gets squeezed to 60% width with height untouched, looking thin and tall. Switching
 * to a CJK monospace font (Latin 0.5em / CJK 1.0em, e.g. Sarasa Mono) makes both
 * ratios 1.0 and the distortion vanishes. The font isn't bundled, so the APK stays
 * exactly the same size.
 */
object TerminalFont {

    private const val DIR = "fonts"

    /** File name of the imported font; null = use system monospace. */
    fun currentName(ctx: Context): String? {
        val p = Prefs.terminalFont(ctx)
        if (p.isEmpty()) return null
        val f = File(p)
        return if (f.isFile) f.name else null
    }

    /** The typeface to use right now; falls back to system monospace if the imported file is missing or fails to load. */
    fun typeface(ctx: Context): Typeface {
        val p = Prefs.terminalFont(ctx)
        if (p.isEmpty()) return Typeface.MONOSPACE
        return runCatching { Typeface.createFromFile(p) }.getOrNull() ?: Typeface.MONOSPACE
    }

    /** Clear the custom font, returning to system monospace. */
    fun clear(ctx: Context) {
        Prefs.setTerminalFont(ctx, "")
        runCatching { File(ctx.filesDir, DIR).deleteRecursively() }
    }

    /**
     * Import the font file chosen from SAF: verify the sfnt header and try to load it
     * before persisting and remembering the path. Only one copy is kept (the directory
     * is cleared first); returns the file name on success, null on failure.
     */
    fun import(ctx: Context, uri: Uri): String? {
        val name = displayName(ctx, uri)
        val dir = File(ctx.filesDir, DIR)
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            // createFromFile does not throw on invalid input on some versions and instead
            // silently returns the default font, so check the sfnt magic ourselves first,
            // then let the system have a go at loading.
            isFont(out) && runCatching { Typeface.createFromFile(out) }.isSuccess
        }.getOrDefault(false)
        if (!ok) {
            runCatching { dir.deleteRecursively() }
            return null
        }
        Prefs.setTerminalFont(ctx, out.path)
        return out.name
    }

    /** sfnt magic numbers: TrueType (0x00010000 / "true"), OpenType-CFF ("OTTO"), collections ("ttcf"). */
    private fun isFont(f: File): Boolean = runCatching {
        val head = ByteArray(4)
        f.inputStream().use { if (it.read(head) != 4) return false }
        val tag = String(head, Charsets.ISO_8859_1)
        tag == "OTTO" || tag == "true" || tag == "ttcf" ||
            (head[0].toInt() == 0 && head[1].toInt() == 1 && head[2].toInt() == 0 && head[3].toInt() == 0)
    }.getOrDefault(false)

    private fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font.ttf"
}
