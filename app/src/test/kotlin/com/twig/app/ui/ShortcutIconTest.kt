package com.twig.app.ui

import android.app.Application
import android.graphics.Color
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * ★ Regression: a launcher shortcut must never arrive on the desktop as a white
 * glyph (measured on a real device, 2026-08-27).
 *
 * Every icon in this app draws its paths with `fillColor="@android:color/white"`
 * and takes its colour purely from `android:tint` (see the media-server icon
 * lesson in CLAUDE.md). Handing such a drawable to the launcher **by resource**
 * loses the colour: the launcher loads the resource itself and the vector's tint
 * is dropped, leaving a white glyph on its own light icon plate — which is what
 * happened first with ic_music_note and then again with ic_file_audio.
 *
 * There are two halves, and both are pinned below, because they fail the same way
 * while needing different fixes:
 *   - shortcuts created in code go through [ShortcutIcons], which bakes the colour
 *     into a bitmap;
 *   - the static entries in `res/xml/shortcuts.xml` are loaded by the launcher and
 *     **no code path can bake them**, so their drawables must carry literal colours.
 *
 * The criterion is the drawn pixel colour, as in [IconTintLeakTest] — "is a tint
 * set" only guards one particular way of getting it wrong, while what the user
 * sees is "the icon is white".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutIconTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun hex(c: Int) = String.format("#%06X", c)

    /** The most common opaque colour in the icon we actually hand to the launcher. */
    private fun drawnColor(icon: IconCompat): Int {
        assertEquals(
            "the icon was handed over by resource, so the launcher renders it and drops the tint",
            IconCompat.TYPE_BITMAP, icon.type,
        )
        val bmp = requireNotNull(icon.bitmap) { "the icon carries no bitmap" }
        val hist = HashMap<Int, Int>()
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) > 200) {
                val rgb = c and 0xFFFFFF
                hist[rgb] = (hist[rgb] ?: 0) + 1
            }
        }
        return hist.maxByOrNull { it.value }?.key ?: -1
    }

    @Test
    fun `shortcut icons are handed over as coloured pixels, not as a resource`() {
        val cases = listOf(
            R.drawable.ic_shortcut_music to 0xAB47BC,   // music
            R.drawable.ic_shortcut_terminal to 0x2E7D32,     // remote command
            R.drawable.ic_file_audio to 0xAB47BC,       // a file shortcut: tint must be baked
            R.drawable.ic_folder to 0xFBC02D,           // a directory shortcut
        )
        for ((res, want) in cases) {
            val got = drawnColor(ShortcutIcons.of(app, res))
            val white = if (got == 0xFFFFFF) " (white = the drawable's tint was lost)" else ""
            assertEquals("icon 0x${res.toString(16)} drew ${hex(got)}$white", want, got)
        }
    }

    /**
     * The rule itself: **no shortcut may hand its icon over by resource**. The
     * static entries in `res/xml/shortcuts.xml` could only do that, which is one
     * of the reasons they were replaced by dynamic ones (the other being the app's
     * own language setting, see [Shortcuts]); if that file ever comes back, its
     * icons must at least carry literal colours.
     */
    @Test
    fun `no shortcut hands its icon over by resource`() {
        val src = File("src/main/kotlin/com/twig/app")
        val offenders = src.walkTopDown().filter { it.name.endsWith(".kt") }.filter {
            Regex("""setIcon\(\s*IconCompat\.createWithResource""").containsMatchIn(it.readText())
        }.map { it.name }.toList()
        assertEquals(
            "the launcher would load the drawable itself and drop its tint — use ShortcutIcons",
            emptyList<String>(), offenders,
        )

        val staticXml = File("src/main/res/xml/shortcuts.xml")
        if (staticXml.exists()) {
            val names = Regex("""android:icon="@drawable/(\w+)"""").findAll(staticXml.readText())
                .map { it.groupValues[1] }.toList()
            for (name in names) {
                // strip comments first — the drawables explain this very rule in one
                val d = File("src/main/res/drawable/$name.xml").readText()
                    .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
                assertTrue(
                    "$name relies on android:tint, which the launcher drops",
                    !d.contains("android:tint"),
                )
            }
        }
    }
}
