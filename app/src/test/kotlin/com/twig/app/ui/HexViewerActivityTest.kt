package com.twig.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Whether the hex viewer actually opens, and whether it has rows once open.
 *
 * Why this is worth a unit test: this screen is a three-stage asynchronous relay
 * ("read file -> compute layout -> lay out rows"); if any stage fails to connect, the
 * symptom is always the same — **a blank screen** — and on a real device you cannot tell
 * which stage broke. In particular the ordering "the file finishes reading before the
 * view has even been measured for width" may not reproduce on whatever device is in hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HexViewerActivityTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `rows are listed after opening, and each row's byte count falls in a sane range`() {
        val data = ByteArray(4096) { it.toByte() }
        val rv = launch(data)
        assertTrue("the list should not be empty", rv.adapter!!.itemCount > 0)
        // row count = total bytes / bytes per row, at least 4 and at most 64 per row
        val bpr = data.size / rv.adapter!!.itemCount
        assertTrue("$bpr bytes per row, not within 4..64", bpr in 4..64)
    }

    @Test
    fun `rows are still laid out even if the width has not been measured by the time the file finishes reading`() {
        // prepare the source before laying out, reproducing the ordering "reading finishes faster than the first layout pass": the layout callback must catch it
        val data = ByteArray(1024) { it.toByte() }
        val rv = launch(data, layoutFirst = false)
        assertTrue("when layout arrives late, the row count must not stay stuck at 0", rv.adapter!!.itemCount > 0)
    }

    @Test
    fun `an empty file does not crash, it just has no rows`() {
        val rv = launch(ByteArray(0))
        assertEquals(0, rv.adapter!!.itemCount)
    }

    /** Starts the Activity and drains the main thread's message queue; [layoutFirst] controls whether layout happens before or after the file finishes reading. */
    private fun launch(data: ByteArray, layoutFirst: Boolean = true): RecyclerView {
        FsRegistry.register(ByteFs(data))
        val intent = Intent(ctx, HexViewerActivity::class.java)
            .putExtra("scheme", "bytes")
            .putExtra("path", "/f.bin")
            .putExtra("name", "f.bin")
            .putExtra("size", data.size.toLong())
        val activity = Robolectric.buildActivity(HexViewerActivity::class.java, intent).setup().get()
        val rv = activity.findViewById<RecyclerView>(R.id.list)
        if (layoutFirst) layout(rv)
        idle()
        if (!layoutFirst) {
            layout(rv)
            idle()
        }
        return rv
    }

    private fun layout(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1080, 1920)
    }

    /** The file-reading stage runs on Dispatchers.IO, so a single idle round on the main thread might not catch up to it yet — give it several chances. */
    private fun idle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class ByteFs(val data: ByteArray) : FileSystem {
        override val scheme = "bytes"
        override val displayName = "bytes"
        override fun root() = XFile(scheme, "/", isDir = true)
        override fun resolve(path: String) = XFile(scheme, path, isDir = false, size = data.size.toLong())
        override fun list(dir: XFile): List<XFile> = emptyList()
        override fun openInput(file: XFile): InputStream = ByteArrayInputStream(data)
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("ro")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("ro")
        override fun delete(file: XFile) = throw FsException("ro")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("ro")
        override fun exists(file: XFile) = true
        override fun randomAccessEfficient() = true
        override fun openRandom(file: XFile) = object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= data.size) return -1
                val n = minOf(length.toLong(), data.size - position).toInt()
                System.arraycopy(data, position.toInt(), buffer, offset, n)
                return n
            }

            override fun length() = data.size.toLong()
            override fun close() = Unit
        }
    }
}
