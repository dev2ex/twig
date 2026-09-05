package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression: **when binding an icon, `imageTintList` must not be touched at all**.
 *
 * On 2026-08-19, to color the handful of media-server entry icons, `bindFile` was given a
 * line `b.icon.imageTintList = null` (the idea being "reset it on every bind so a
 * RecyclerView recycle doesn't leak a stale color"). The result was **every icon on
 * screen turning white**: `ImageView.setImageTintList(null)` does not mean "no tint" — it
 * treats null as a tint that is **actively applied to the drawable**. `mHasDrawableTint`
 * gets set to true, so `mDrawable.mutate().setTintList(null)` runs and wipes out the
 * `android:tint` written in the drawable's XML. And `ic_folder` (yellow) / `ic_file_*`
 * (various colors) / `ic_storage` (green) all have `fillColor` set to
 * `@android:color/white` — their color comes **only** from that tint — so wiping it
 * leaves nothing but white.
 *
 * The fix is that any icon needing color is made into its own drawable carrying
 * `android:tint` (`ic_md_*`).
 *
 * The criterion here is **the actual rendered pixel color**, not "was a tint set" — the
 * latter only catches this one specific way of writing the bug, whereas what the user
 * actually sees is "the icon is white", and measuring the color is what actually matches
 * the symptom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconTintLeakTest {

    private lateinit var app: Application
    private lateinit var scheme: String

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        val conn = SavedConnection(type = "jellyfin", host = "tint.test", user = "u")
        ConnectionStore.save(app, conn)
        scheme = Connections.schemeOf(conn)
        // icons are routed by "is this a media server", judged via Connections.ofScheme -- that
        // lookup table is only populated by ensure(). Register the FakeFileSystem first so
        // ensure() does not actually try to connect.
        FsRegistry.register(FakeFileSystem(scheme, dirs = emptyMap()))
        Connections.ensure(app, conn)
    }

    private fun node(file: XFile) =
        PaneViewModel.FileNode(file, depth = 1, expandable = file.isDir, expanded = false)

    /** The row layout uses `?attr/selectableItemBackgroundBorderless`, which a bare application context cannot resolve. */
    private fun themed() = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))

    /** Renders the icon and returns the most frequent opaque color -- effectively "what color this icon looks like". */
    private fun drawnColor(icon: ImageView): Int {
        val d = requireNotNull(icon.drawable) { "no icon" }
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 48, 48)
        d.draw(Canvas(bmp))
        val hist = HashMap<Int, Int>()
        for (y in 0 until 48) for (x in 0 until 48) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) > 200) {
                val rgb = c and 0xFFFFFF
                hist[rgb] = (hist[rgb] ?: 0) + 1
            }
        }
        return hist.maxByOrNull { it.value }?.key ?: -1
    }

    private fun hex(c: Int) = String.format("#%06X", c)

    /**
     * Submit multiple rows at once and bind them in sequence with **the same ViewHolder**
     * (exactly the recycling path used while scrolling) -- each row's rendered color must
     * be the one written into its own drawable.
     */
    @Test
    fun `each icon renders in its own color, never turned white by the previous row`() {
        val rows = listOf(
            // a media-server entry icon (carries its own android:tint)
            node(XFile(scheme, "/resume", isDir = true)) to 0x26A69A,
            // an ordinary row right after it -- on the buggy version this came out all white
            node(XFile("file", "/sdcard/movie.mp4", isDir = false)) to 0xEF5350,
            node(XFile("file", "/sdcard/song.mp3", isDir = false)) to 0xAB47BC,
            node(XFile("file", "/sdcard/pic.jpg", isDir = false)) to 0x66BB6A,
            node(XFile("file", "/sdcard/dir", isDir = true)) to 0xFBC02D,
            // an ordinary directory inside a media server: the generic folder icon, also yellow
            node(XFile(scheme, "/lib/abc/xyz", isDir = true)) to 0xFBC02D,
        )
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(rows.map { it.first })
        val vh = adapter.createViewHolder(themed(), adapter.getItemViewType(0))
        val icon = vh.itemView.findViewById<ImageView>(R.id.icon)

        for ((i, row) in rows.withIndex()) {
            adapter.bindViewHolder(vh, i)
            val got = drawnColor(icon)
            val white = if (got == 0xFFFFFF) "(white = the drawable's android:tint got wiped out)" else ""
            assertEquals(
                "${row.first.file.path} rendered as ${hex(got)}, expected ${hex(row.second)}$white",
                row.second, got,
            )
        }
    }

    /** The rule itself: nothing may set `imageTintList` when binding -- setting it (even to null) wipes out the drawable's own color. */
    @Test
    fun `imageTintList is never set while binding an icon`() {
        val rows = listOf(
            node(XFile(scheme, "/resume", isDir = true)),
            node(XFile(scheme, "/lib/abc", isDir = true)),
            node(XFile("file", "/sdcard/a.mp4", isDir = false)),
        )
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(rows)
        val vh = adapter.createViewHolder(themed(), adapter.getItemViewType(0))
        val icon = vh.itemView.findViewById<ImageView>(R.id.icon)
        for (i in rows.indices) {
            adapter.bindViewHolder(vh, i)
            assertNull("${rows[i].file.path}: imageTintList was touched", icon.imageTintList)
        }
    }
}
