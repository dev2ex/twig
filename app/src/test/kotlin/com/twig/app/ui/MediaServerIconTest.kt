package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.core.FsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Identification for a Jellyfin/Emby connection row: the same hand-drawn play glyph,
 * told apart by **each service's own official color** (for why it does not just redraw
 * the official logo, see the comment in `ic_media_jellyfin.xml`).
 *
 * The criterion is always **the rendered pixels**, never "is the resource id correct":
 *  - `pathData` is just a string as far as AAPT2 is concerned; getting it wrong still
 *    compiles fine and only blows up at runtime in `PathParser` -- only actually
 *    rendering it proves this version's rounded triangle is a valid path;
 *  - Jellyfin's color comes from a `<gradient>`, and `android:tint` would flatten the
 *    whole gradient into one solid color. Adding a stray `android:tint` also compiles
 *    fine with the same id unchanged -- only measuring pixels reveals that it collapsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaServerIconTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun pixels(res: Int): List<Triple<Int, Int, Int>> {
        val d = requireNotNull(AppCompatResources.getDrawable(app, res)) { "could not get the icon" }
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(bmp))
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (y in 0 until 96) for (x in 0 until 96) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) > 240) out += Triple(x, y, c and 0xFFFFFF)
        }
        return out
    }

    private fun hex(c: Int) = String.format("#%06X", c)

    /** Emby is solid-colored: it must render as the official green, and must actually render something (a valid path). */
    @Test
    fun `Emby's icon renders as the official green`() {
        val px = pixels(FileIcons.sourceIconOfType("emby"))
        assertTrue("not a single opaque pixel -- pathData did not render any shape", px.size > 500)
        val main = px.groupingBy { it.third }.eachCount().maxByOrNull { it.value }!!.key
        assertEquals("rendered as ${hex(main)}", 0x52B54B, main)
    }

    /**
     * Jellyfin is a gradient: purple to blue. The criterion is **bottom-left noticeably
     * redder than top-right** (#AA5CC3's R is 0xAA, #00A4DC's R is 0x00) -- once flattened
     * by a tint into a solid color, both ends would come out equally red.
     */
    @Test
    fun `Jellyfin's icon renders as a purple-to-blue gradient, not flattened into a solid color by a tint`() {
        val px = pixels(FileIcons.sourceIconOfType("jellyfin"))
        assertTrue("not a single opaque pixel -- pathData did not render any shape", px.size > 500)
        assertTrue(
            "only rendered ${px.map { it.third }.distinct().size} distinct colors, the gradient has been flattened into a solid color",
            px.map { it.third }.distinct().size > 20,
        )
        // compare the lower vs. upper portion of the shape's left half, by their average red component
        val left = px.filter { it.first < 40 }
        val redLow = left.filter { it.second > 60 }.map { (it.third shr 16) and 0xFF }.average()
        val redHigh = left.filter { it.second < 36 }.map { (it.third shr 16) and 0xFF }.average()
        assertTrue(
            "bottom-left red=$redLow top-left red=$redHigh, the gradient direction is wrong or it has already collapsed into a solid color",
            redLow - redHigh > 30,
        )
    }

    /** The two must not look the same, otherwise "telling which server it is" is lost. */
    @Test
    fun `the two services' icons are not the same resource`() {
        assertNotEquals(
            FileIcons.sourceIconOfType("jellyfin"),
            FileIcons.sourceIconOfType("emby"),
        )
    }

    /**
     * The scheme-based lookup path must be correct too. A scheme is "type + hash", and
     * `Format.schemeLabel` has to strip the hash off before it can match the type -- this
     * is where `sftp` + `a3f2...` once got misread as `sftpa`.
     */
    @Test
    fun `looking it up by scheme also gets the same icon`() {
        for (type in listOf("jellyfin", "emby")) {
            val conn = SavedConnection(type = type, host = "$type.test", user = "u")
            ConnectionStore.save(app, conn)
            val scheme = Connections.schemeOf(conn)
            assertEquals(
                "scheme=$scheme did not resolve back to $type",
                FileIcons.sourceIconOfType(type),
                FileIcons.sourceIconRes(scheme),
            )
        }
    }

    /**
     * The server row (`bindServer`) must route correctly too -- there are four separate
     * places that assign an icon by type, and this project has more than once fixed one
     * and missed the other three, so the list side gets its own dedicated pin here.
     */
    @Test
    fun `a server row uses its own type's icon, and an ordinary server is unaffected`() {
        val jf = SavedConnection(type = "jellyfin", host = "jf.row", user = "u")
        val ftp = SavedConnection(type = "ftp", host = "ftp.row", user = "u")
        listOf(jf, ftp).forEach { ConnectionStore.save(app, it) }
        FsRegistry.register(FakeFileSystem(Connections.schemeOf(jf), dirs = emptyMap()))
        Connections.ensure(app, jf)

        val rows = listOf(
            PaneViewModel.ServerNode(jf, expanded = false, connecting = false),
            PaneViewModel.ServerNode(ftp, expanded = false, connecting = false),
        )
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(rows)
        val host = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))

        val vh0 = adapter.createViewHolder(host, adapter.getItemViewType(0))
        adapter.bindViewHolder(vh0, 0)
        val jfIcon = vh0.itemView.findViewById<ImageView>(R.id.icon).drawable
        val vh1 = adapter.createViewHolder(host, adapter.getItemViewType(1))
        adapter.bindViewHolder(vh1, 1)
        val ftpIcon = vh1.itemView.findViewById<ImageView>(R.id.icon).drawable

        // the media-server row must render with a gradient (multiple colors); the FTP row keeps the generic server icon
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        jfIcon.setBounds(0, 0, 96, 96)
        jfIcon.draw(Canvas(bmp))
        val colors = HashSet<Int>()
        for (y in 0 until 96) for (x in 0 until 96) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) > 240) colors += c and 0xFFFFFF
        }
        assertTrue("the media-server row did not get the gradient icon (only ${colors.size} distinct colors)", colors.size > 20)
        assertNotEquals("the FTP row should not have switched icons too", jfIcon.constantState, ftpIcon.constantState)
    }
}
