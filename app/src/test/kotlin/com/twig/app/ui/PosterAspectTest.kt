package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.util.LruCache
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.core.CoverSource
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * When a movie/series library expands, its cover displays as a portrait poster (2:3) at its
 * **original aspect ratio**, not cropped into a square.
 *
 * Two assertions, both required:
 * - **The icon only grows once the image arrives.** Growing it to thumbnail size right at
 *   bind time would turn a screen full of cover-less directories into a row of oversized
 *   folder icons (users reported "these look huge"). So a small icon holds the spot first,
 *   and only `Thumbs.fillAspect` changes width and height together once the image is in.
 * - **The height cap must widen along with the growth.** The old cap was
 *   `maxOf(box.height, w)`; for a 2:3 poster, `natural = 1.5w` would get clamped back to a
 *   square and cropped top/bottom via MATRIX -- the portrait shape would be wasted.
 *
 * This also pins down **resetting the size on reuse**: an icon slot that grew for a poster
 * must reset when recycled for the next row's plain directory, or that row's folder icon
 * ends up poster-sized. This reset already lived in the block at the start of `VH.bind`
 * (alongside `size`/`scaleType`/`padding`/`colorFilter`/`iconVMargin`); ★ when the poster
 * feature was added, it got duplicated in `bindFile` -- reverse-verification (deleting the
 * duplicate still leaves the test green) is what exposed it as redundant (and it also
 * hardcoded 26dp, whereas `iconDp` actually varies with density). The redundancy is gone;
 * this test stays to guard the surviving copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosterAspectTest {

    private lateinit var app: Application
    private lateinit var scheme: String

    /**
     * ★ The row must really be attached into a window: `fillAspect` changes `layoutParams`
     * inside `view.post{}`, and **when the View is not attached, `post` just queues the task
     * into `HandlerActionQueue` to wait for attach** -- no amount of `Looper.idle()` will run
     * it. Using a bare, unattached FrameLayout as the parent would always measure the small
     * bind-time size.
     */
    class Host : Activity() {
        override fun onCreate(b: Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(b)
        }
    }

    private fun attachedParent(): FrameLayout {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        return parent
    }

    /**
     * A fake source with a cover. `Thumbs.canThumb`'s test for a directory is simply "is this
     * FileSystem a CoverSource", so just implementing the interface routes the directory
     * through the thumbnail path (the image comes from the cache; `openCover` is never
     * called).
     */
    private class CoverFs(private val d: FakeFileSystem) : FileSystem by d, CoverSource {
        override fun openCover(file: XFile, maxPx: Int): InputStream? = null
    }

    /**
     * ★ **A movie is a file, not a directory** (Jellyfin's Movie entries are
     * `IsFolder=false`); only series/season/album/photo-album entries are directories. The
     * first version of this test used `isDir = true` to stand in for a movie, so it only
     * covered the directory branch -- **on a real device the poster stayed square** while
     * the test passed -- the same class of mistake as "a flat test library can't reveal
     * physical directories": **the fixture had the wrong shape, so it was testing something
     * else**. Both branches are pinned now.
     */
    private fun movie(path: String) = XFile(scheme, path, isDir = false)

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setThumbs(app, true)
        val conn = SavedConnection(type = "jellyfin", host = "poster.test", user = "u")
        ConnectionStore.save(app, conn)
        scheme = Connections.schemeOf(conn)
        FsRegistry.register(CoverFs(FakeFileSystem(scheme, dirs = emptyMap())))
        Connections.ensure(app, conn)
    }

    /** Drops an image directly into Thumbs's in-memory cache so bind takes the synchronous-fill path. */
    @Suppress("UNCHECKED_CAST")
    private fun seedCache(file: XFile, w: Int, h: Int) {
        val keyOf = Thumbs::class.java.getDeclaredMethod("keyOf", XFile::class.java)
            .apply { isAccessible = true }
        val mem = Thumbs::class.java.getDeclaredField("mem")
            .apply { isAccessible = true }.get(Thumbs) as LruCache<String, Bitmap>
        mem.put(keyOf.invoke(Thumbs, file) as String, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
    }

    private fun node(file: XFile) =
        PaneViewModel.FileNode(file, depth = 1, expandable = file.isDir, expanded = false)

    /** The icon slot's original side length (`FileAdapter.rowIconDp(density=1)`, matching `item_file.xml`). */
    private fun iconPx() = dp(26)

    private fun dp(v: Int) = (v * app.resources.displayMetrics.density).toInt()

    @Test
    fun `a movie's poster grows to 2 to 3, not cropped to a square`() {
        assertPoster(movie("/lib/movies/m1"))
    }

    /** Series/album/photo-album entries **are directories**, taking a different branch, and must grow too. */
    @Test
    fun `a series directory's poster also grows to 2 to 3`() {
        assertPoster(XFile(scheme, "/lib/tv/s1", isDir = true))
    }

    private fun assertPoster(item: XFile) {
        seedCache(item, 400, 600) // 2:3 poster
        val movie = item

        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = true)
        adapter.submitList(listOf(node(movie)))
        val parent = attachedParent()
        val vh = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        parent.addView(vh.itemView)
        adapter.bindViewHolder(vh, 0)

        val icon = vh.itemView.findViewById<ImageView>(R.id.icon)
        if (item.isDir) {
            // Directory: still the small icon right at bind time -- a cover-less directory
            // stays this size, never a screenful of oversized folders (the file path already
            // grows to a square placeholder first, which is existing behavior)
            assertEquals("a directory should not grow before the image arrives", iconPx(), icon.layoutParams.width)
        }

        // fillAspect changes layoutParams inside post{} (height is only readable once infoBox finishes this layout pass)
        layoutOnce(vh.itemView)
        shadowOf(Looper.getMainLooper()).idle()

        val w = icon.layoutParams.width
        val h = icon.layoutParams.height
        assertTrue("once the image arrives it should grow to thumbnail width, actual $w", w > iconPx())
        assertTrue(
            "height should be 1.5x the width (2:3), actual ${w}x$h",
            kotlin.math.abs(h - w * 1.5) <= 2,
        )
        assertEquals(
            "should display fully at its original aspect ratio, not cropped via MATRIX",
            ImageView.ScaleType.FIT_CENTER, icon.scaleType,
        )
    }

    /** An icon slot that grew for a poster must reset when recycled for the next row's plain directory. */
    @Test
    fun `a poster's size does not leak into the next row`() {
        val movie = XFile(scheme, "/lib/movies/m2", isDir = true)
        seedCache(movie, 400, 600)
        val rows = listOf(node(movie), node(XFile("file", "/sdcard/dir", isDir = true)))

        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = true)
        adapter.submitList(rows)
        val parent = attachedParent()
        val vh = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        parent.addView(vh.itemView)
        val icon = vh.itemView.findViewById<ImageView>(R.id.icon)

        adapter.bindViewHolder(vh, 0)
        layoutOnce(vh.itemView)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("precondition: the poster really did grow", icon.layoutParams.width > iconPx())

        adapter.bindViewHolder(vh, 1) // reused for a plain local directory
        assertEquals("the size must reset", iconPx(), icon.layoutParams.width)
        assertEquals(iconPx(), icon.layoutParams.height)
    }

    /**
     * ★ A thumbnail row's **top/bottom spacing**: both branches (directory/file) must set
     * it, or adjacent covers end up flush against each other.
     *
     * The first version only set `iconVMargin` on the file branch -- while series/box
     * sets/playlists/libraries **are all directories** (`Series`/`BoxSet`/`Playlist` are all
     * in `CONTAINER_TYPES`), taking the other branch; a movie is a file. The symptom was
     * therefore "only the movie library's thumbnails have spacing, nothing else does".
     * `VH.bind` resets the margin to 0 at the top, so a missed branch means flush-together.
     */
    @Test
    fun `both branches of a thumbnail row must give it top-bottom spacing`() {
        val rows = listOf(
            XFile(scheme, "/lib/tv/s1", isDir = true),      // series: the directory branch
            XFile(scheme, "/playlists/p1", isDir = true),
            XFile(scheme, "/collections/c1", isDir = true),
            movie("/lib/movies/m9"),                        // movie: the file branch
        ).map { node(it) }
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = true)
        adapter.submitList(rows)
        val parent = attachedParent()
        val vh = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        parent.addView(vh.itemView)
        val icon = vh.itemView.findViewById<ImageView>(R.id.icon)
        for ((i, row) in rows.withIndex()) {
            adapter.bindViewHolder(vh, i)
            val lp = icon.layoutParams as android.widget.LinearLayout.LayoutParams
            assertTrue(
                "${row.file.path}'s thumbnail has no top/bottom spacing and will be flush against adjacent rows",
                lp.topMargin > 0 && lp.bottomMargin > 0,
            )
        }
    }

    private fun layoutOnce(v: View) {
        val w = dp(360)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, w, v.measuredHeight)
    }
}
