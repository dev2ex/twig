package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.SizeProbe
import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Row-driven size backfill ([SizeProbes]).
 *
 * The two things that go wrong silently here: a row that was recycled while its probe was in
 * flight gets **another entry's** size written into it, and an entry already known gets asked
 * for again (on a media server that is a request per row, per scroll).
 */
@RunWith(RobolectricTestRunner::class)
class SizeProbesTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** A media-server-shaped source: listings have no size, a probe produces one. */
    private class ProbeFs : com.twig.core.FileSystem, SizeProbe {
        override val scheme = "probefs"
        override val displayName = "probe"
        val probed = ConcurrentHashMap<String, Int>()
        val cache = ConcurrentHashMap<String, Long>()
        val entered = CountDownLatch(1)
        @Volatile var release: CountDownLatch? = null

        override fun knownSize(file: XFile): Long = if (file.size > 0) file.size else cache[file.path] ?: 0L

        override fun probeSize(file: XFile): Long {
            probed.merge(file.path, 1, Int::plus)
            entered.countDown()
            release?.await(5, TimeUnit.SECONDS)
            val n = file.path.substringAfterLast('/').length * 100L
            cache[file.path] = n
            return n
        }

        override fun root() = XFile(scheme, "/", isDir = true)
        override fun resolve(path: String) = XFile(scheme, path, isDir = true)
        override fun list(dir: XFile): List<XFile> = emptyList()
        override fun openInput(file: XFile) = throw UnsupportedOperationException()
        override fun openOutput(file: XFile, append: Boolean) = throw UnsupportedOperationException()
        override fun mkdir(parent: XFile, name: String) = throw UnsupportedOperationException()
        override fun delete(file: XFile) = throw UnsupportedOperationException()
        override fun rename(file: XFile, newName: String) = throw UnsupportedOperationException()
        override fun exists(file: XFile) = true
    }

    private lateinit var fs: ProbeFs

    private fun photo(name: String) = XFile("probefs", "/album/$name", isDir = false)

    @Before
    fun setUp() {
        fs = ProbeFs()
        FsRegistry.register(fs)
    }

    @After
    fun tearDown() {
        FsRegistry.unregister(fs.scheme)
    }

    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Waits for the background probe and pumps the main looper until its callback has been
     * delivered. [until] is the condition that makes waiting pointless — polled, because the
     * worker thread is real while Robolectric's looper only advances when idled.
     */
    private fun settle(until: () -> Boolean = { false }) {
        fs.entered.await(5, TimeUnit.SECONDS)
        for (i in 0 until 100) {
            drain()
            if (until()) return
            Thread.sleep(10)
        }
        drain()
    }

    @Test
    fun `a probed size reaches the row that asked for it`() {
        val view = TextView(app)
        var got: Long? = null
        SizeProbes.request(view, photo("pic1.jpg")) { got = it }
        settle { got != null }
        assertEquals(800L, got)
        assertEquals(800L, SizeProbes.known(photo("pic1.jpg")))
    }

    /** ★ The row was recycled to another entry while the probe was in flight: no write. */
    @Test
    fun `a recycled row is not given the answer`() {
        val view = TextView(app)
        val gate = CountDownLatch(1)
        fs.release = gate
        var got: Long? = null
        SizeProbes.request(view, photo("pic2.jpg")) { got = it }
        fs.entered.await(5, TimeUnit.SECONDS)
        // the row now shows a different file (what RecyclerView does on scroll)
        SizeProbes.request(view, photo("other.png")) { }
        gate.countDown()
        settle()
        assertNull("the first answer must not land in a reused row", got)
    }

    @Test
    fun `an already known size is answered without asking again`() {
        val view = TextView(app)
        var first: Long? = null
        SizeProbes.request(view, photo("pic3.jpg")) { first = it }
        settle { first != null }
        assertEquals(1, fs.probed["/album/pic3.jpg"])

        var again: Long? = null
        SizeProbes.request(TextView(app), photo("pic3.jpg")) { again = it }
        drain()
        assertEquals("served from the cache, synchronously", 800L, again)
        assertEquals("no second request", 1, fs.probed["/album/pic3.jpg"])
    }

    @Test
    fun `entries with a size, directories and plain sources are never asked`() {
        val view = TextView(app)
        SizeProbes.request(view, photo("pic4.jpg").copy(size = 5)) { }
        SizeProbes.request(view, XFile("probefs", "/album", isDir = true)) { }
        SizeProbes.request(view, XFile("file", "/tmp/x.txt", isDir = false)) { }
        drain()
        assertEquals(0, fs.probed.size)
        assertEquals(5L, SizeProbes.known(photo("pic4.jpg").copy(size = 5)))
        assertEquals(0L, SizeProbes.known(XFile("file", "/tmp/x.txt", isDir = false)))
    }
}
