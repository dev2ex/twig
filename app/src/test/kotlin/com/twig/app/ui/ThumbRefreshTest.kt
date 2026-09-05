package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Prefs
import com.twig.app.SavedConnection
import com.twig.core.CoverSource
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Refresh thumbnails" must also clear **the directory's own cover**.
 *
 * On a media server (Jellyfin/Emby), series/season/album/photo-album/box-set/library
 * entries are **all directories**, and their covers hang off the directory row itself; the
 * old `walkInvalidate` only recursed into children and never cleared the directory's own
 * key (`scheme:path:mtime`). Symptom: the server changes the poster, and no matter how many
 * times you refresh, Twig still shows the old image -- while **a movie is a file**, so the
 * same menu item works fine in a movie library, making it look like "sometimes it works,
 * sometimes it doesn't". Same class of mistake as "both thumbnail branches must be given
 * everything" in CLAUDE.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbRefreshTest {

    private lateinit var app: Application
    private lateinit var scheme: String

    /** A fake source with a cover: implementing [CoverSource] routes a directory through the thumbnail path (the image comes straight from the cache). */
    private class CoverFs(private val d: FakeFileSystem) : FileSystem by d, CoverSource {
        override fun openCover(file: XFile, maxPx: Int): InputStream? = null
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setThumbs(app, true)
        val conn = SavedConnection(type = "jellyfin", host = "refresh.test", user = "u")
        ConnectionStore.save(app, conn)
        scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            CoverFs(
                FakeFileSystem(
                    scheme,
                    dirs = mapOf(
                        "/lib/tv" to listOf("s1"),
                        "/lib/tv/s1" to listOf("e1.mkv"),
                    ),
                    files = mapOf("/lib/tv/s1/e1.mkv" to "x"),
                )
            )
        )
        Connections.ensure(app, conn)
        Thumbs.clearCache(app)
    }

    private fun keyOf(f: XFile): String =
        Thumbs::class.java.getDeclaredMethod("keyOf", XFile::class.java)
            .apply { isAccessible = true }.invoke(Thumbs, f) as String

    @Suppress("UNCHECKED_CAST")
    private fun mem(): LruCache<String, Bitmap> =
        Thumbs::class.java.getDeclaredField("mem").apply { isAccessible = true }
            .get(Thumbs) as LruCache<String, Bitmap>

    /** Puts one copy in both the memory and disk layers; invalidate must clear both. */
    private fun seed(f: XFile): String {
        val key = keyOf(f)
        mem().put(key, Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888))
        File(File(app.cacheDir, "thumbs").apply { mkdirs() }, key).writeBytes(byteArrayOf(1))
        return key
    }

    private fun cached(key: String) =
        mem().get(key) ?: File(File(app.cacheDir, "thumbs"), key).takeIf { it.isFile }

    private fun dir(path: String) = XFile(scheme, path, isDir = true)

    @Test
    fun `refreshing a series library clears the covers of the library itself and every level inside it`() {
        val lib = seed(dir("/lib/tv"))          // the directory being refreshed itself (library/series)
        val season = seed(dir("/lib/tv/s1"))    // the intermediate directory (season/album)
        val ep = seed(XFile(scheme, "/lib/tv/s1/e1.mkv", isDir = false, size = 1))

        val done = CountDownLatch(1)
        Thumbs.invalidateDir(app, dir("/lib/tv")) { done.countDown() }
        // onDone runs via main.post, and Robolectric's main Looper only runs it once pumped;
        // the walk itself runs on a background thread, so wait for it to finish first
        // (polling rather than idling directly, to avoid idle spinning before the walk starts).
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && done.count > 0L) {
            org.robolectric.Shadows.shadowOf(app.mainLooper).idle()
            Thread.sleep(10)
        }
        org.junit.Assert.assertTrue("invalidateDir did not call back within 5s", done.await(1, TimeUnit.SECONDS))

        assertNull("the refreshed directory's own cover was not cleared", cached(lib))
        assertNull("the intermediate directory's (season/album) cover was not cleared", cached(season))
        assertNull("the thumbnail of a file inside the directory was not cleared", cached(ep))
    }

    /** Control group: unrelated entries are unaffected -- refreshing "only touches this branch", not a disguised full wipe. */
    @Test
    fun `refreshing only affects this branch, other directories' covers remain`() {
        val other = seed(dir("/lib/movies"))
        val done = CountDownLatch(1)
        Thumbs.invalidateDir(app, dir("/lib/tv")) { done.countDown() }
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && done.count > 0L) {
            org.robolectric.Shadows.shadowOf(app.mainLooper).idle()
            Thread.sleep(10)
        }
        assertNotNull("an unrelated directory's cover was cleared", cached(other))
    }
}
