package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.twig.app.Prefs
import com.twig.app.R
import java.io.File
import java.util.Properties

/**
 * Terminal color scheme. Uses exactly the format termux ships officially for
 * `~/.termux/colors.properties` (`foreground` / `background` / `cursor` / `color0..255`,
 * values being `#RGB`, `#RRGGBB`, or `#AARRGGBB`), with parsing delegated to termux's
 * own `TerminalColorScheme.updateWith`, so the whole base16 family of ready-made
 * schemes (chriskempson/base16-xresources, etc.) can be imported directly.
 *
 * `TerminalColors.COLOR_SCHEME` is **statically** process-wide, and a new session's
 * `TerminalColors()` copies from it in its constructor, so after a scheme switch new
 * sessions pick it up automatically; sessions that already exist only re-read it
 * after their own `mColors.reset()` (see [applyToSessions]).
 */
object TermColors {

    private const val DIR = "colors"

    /** Prefix for built-in scheme values in Prefs; any other non-empty value is treated as the absolute path of a user file. */
    private const val BUILTIN = "builtin:"

    /**
     * Bundled schemes (assets/colors/<id>.properties, total 2KB for all four). Names
     * are the proper names of the schemes themselves and are not translated.
     */
    val PRESETS = listOf(
        "base16-atelierseaside-dark" to "Atelier Seaside Dark",
        "base16-atelierseaside-light" to "Atelier Seaside Light",
        "solarized-dark" to "Solarized Dark",
        "solarized-light" to "Solarized Light",
    )

    /** Acceptable values: #RGB / #RRGGBB / #AARRGGBB. */
    private val COLOR = Regex("^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?([0-9a-fA-F]{2})?$")

    /** Display name of the current scheme; null = termux default. */
    fun currentName(ctx: Context): String? {
        val id = Prefs.terminalColors(ctx)
        if (id.isEmpty()) return null
        if (id.startsWith(BUILTIN)) {
            val key = id.removePrefix(BUILTIN)
            return PRESETS.firstOrNull { it.first == key }?.second
        }
        val f = File(id)
        return if (f.isFile) f.name else null
    }

    /** File name of the imported custom scheme (null for built-in schemes), used to render it as a separate row in the pick list. */
    fun customName(ctx: Context): String? {
        val id = Prefs.terminalColors(ctx)
        if (id.isEmpty() || id.startsWith(BUILTIN)) return null
        val f = File(id)
        return if (f.isFile) f.name else null
    }

    /**
     * The scheme pick list: termux default + built-in schemes + (any imported custom
     * scheme) + "Import...". Picking one applies it immediately; the terminal page and
     * the settings page share this dialog, and [onChanged] refreshes their respective UIs.
     */
    fun showPicker(act: Activity, onImport: () -> Unit, onChanged: () -> Unit) {
        val ids = ArrayList<String?>() // null = the trailing "Import..."
        val labels = ArrayList<String>()
        ids += ""
        labels += act.getString(R.string.settings_term_colors_default)
        for ((key, name) in PRESETS) {
            ids += BUILTIN + key
            labels += name
        }
        customName(act)?.let {
            ids += Prefs.terminalColors(act)
            labels += it
        }
        ids += null
        labels += act.getString(R.string.settings_term_colors_import)

        AlertDialog.Builder(act)
            .setTitle(R.string.settings_term_colors)
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(Prefs.terminalColors(act))) { dlg, w ->
                dlg.dismiss()
                val id = ids[w]
                if (id == null) {
                    onImport()
                } else {
                    Prefs.setTerminalColors(act, id)
                    apply(act)
                    applyToSessions()
                    onChanged()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Apply the configuration to the global scheme. `updateWith` resets internally
     * before applying, so passing empty Properties restores termux default and
     * switching schemes does not leave residue from the previous one.
     */
    fun apply(ctx: Context) {
        val props = Properties()
        val id = Prefs.terminalColors(ctx)
        when {
            id.startsWith(BUILTIN) -> runCatching {
                ctx.assets.open("colors/${id.removePrefix(BUILTIN)}.properties")
                    .use { props.load(it) }
            }
            id.isNotEmpty() -> runCatching { File(id).inputStream().use { props.load(it) } }
        }
        runCatching { TerminalColors.COLOR_SCHEME.updateWith(sanitize(props)) }
    }

    /** Re-read colors in already-created sessions (new sessions already copy them in their constructor). */
    fun applyToSessions() {
        for (t in TermManager.list()) t.emulatorOrNull?.mColors?.reset()
    }

    fun bg(): Int = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]

    fun fg(): Int = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]

    /** Accessory key bar: blends a touch of the foreground color into the background so it stays distinguishable from the terminal, light or dark, without being harsh. */
    fun keyBarBg(): Int = blend(bg(), fg(), 0.10f)

    /** Pressed modifier key background: a notch above the key bar (still within the same hue family, not visually loud). */
    fun keyActiveBg(): Int = blend(bg(), fg(), 0.32f)

    fun clear(ctx: Context) {
        Prefs.setTerminalColors(ctx, "")
        runCatching { File(ctx.filesDir, DIR).deleteRecursively() }
        apply(ctx)
    }

    /**
     * Import the .properties chosen from SAF: only counts if at least one recognized
     * color parses out. Like font import, copies into the app-private directory
     * (content:// permissions are not reliable across restarts) and keeps only one copy.
     */
    fun import(ctx: Context, uri: Uri): String? {
        val props = Properties()
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { props.load(it) }
            sanitize(props).isNotEmpty()
        }.getOrDefault(false)
        if (!ok) return null

        val name = displayName(ctx, uri)
        val dir = File(ctx.filesDir, DIR)
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val saved = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }.isSuccess
        if (!saved) {
            runCatching { dir.deleteRecursively() }
            return null
        }
        Prefs.setTerminalColors(ctx, out.path)
        apply(ctx)
        return out.name
    }

    /**
     * Keep only recognized keys with legal color values before handing off to termux —
     * `updateWith` is "reset first, then apply each entry"; if a bad value throws in
     * the middle you'd end up with a half-applied palette. Pre-filtering is simplest.
     */
    private fun sanitize(props: Properties): Properties {
        val out = Properties()
        for ((k, v) in props) {
            val key = k as? String ?: continue
            val value = (v as? String)?.trim() ?: continue
            if (!COLOR.matches(value)) continue
            val known = key == "foreground" || key == "background" || key == "cursor" ||
                (key.startsWith("color") && key.drop(5).toIntOrNull() in 0..255)
            if (known) out[key] = value
        }
        return out
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * ratio).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "colors.properties"
}
