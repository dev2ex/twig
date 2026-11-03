package com.twig.app.ui

import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteBlobDialogTest {
    @Test fun `chunks support crossing boundaries and eviction without loading the entire BLOB`() = runTest {
        val chunk = HexByteSource.CHUNK
        val bytes = ByteArray(chunk * 10 + 17) { (it % 251).toByte() }
        val reads = mutableListOf<Pair<Long, Int>>()
        val source = ChunkedHexSource(bytes.size.toLong()) { offset, length ->
            reads.add(offset to length)
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        assertTrue(reads.isEmpty())
        assertNull(source.peek(chunk - 3L, 6))
        source.load(0)
        source.load(1)
        assertArrayEquals(bytes.copyOfRange(chunk - 3, chunk + 3), source.peek(chunk - 3L, 6))
        source.load(1)
        assertEquals(2, reads.size)
        for (ci in 2..8) source.load(ci)
        assertNull(source.peek(0, 1)) // The least-recently-used chunk was evicted.
        source.load(10)
        assertEquals(bytes.size - 17L to 17, reads.last())
        assertArrayEquals(bytes.takeLast(17).toByteArray(), source.peek(bytes.size - 17L, 100))
        assertTrue(reads.all { it.second <= chunk })
        source.close()
        assertNull(source.peek(bytes.size - 17L, 1))
    }

    @Test fun `cancelling a chunk reaches its reader and incomplete reads are never cached`() = runTest {
        var cancelled = false
        val source = ChunkedHexSource(10) { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        val job = launch { source.load(0) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertNull(source.peek(0, 1))
        source.close()
        val short = ChunkedHexSource(10) { _, _ -> ByteArray(5) }
        assertTrue(runCatching { short.load(0) }.isFailure)
        assertNull(short.peek(0, 1))
        short.close()
    }

    @Test fun `nonempty BLOB renders bytes through the shared hex row binding`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Twig)
        val job = SupervisorJob()
        val reads = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        val dialog = SqliteBlobDialog.show(context, "payload", 512, CoroutineScope(job + Dispatchers.Main.immediate)) { offset, length ->
            reads.add(offset to length)
            ByteArray(length) { ((offset + it) % 256).toByte() }
        }
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        val list = views(dialog.window!!.decorView).filterIsInstance<RecyclerView>().single()
        try {
            list.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 480, 300)
            fun text() = list.findViewHolderForAdapterPosition(0)?.itemView
                ?.findViewById<android.widget.TextView>(R.id.hex)?.text?.toString().orEmpty()
            for (attempt in 0 until 200) {
                shadowOf(Looper.getMainLooper()).idle()
                list.layout(0, 0, 480, 300)
                if (text().startsWith("00 01")) break
                Thread.sleep(5)
            }
            assertTrue(text(), text().startsWith("00 01"))
            assertEquals(listOf(0L to 512), reads.toList())
            assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick())
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("00 01"))
        } finally { dialog.dismiss(); job.cancel(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun `a cancelled database lease dismisses the BLOB dialog during a range read`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Twig)
        val job = SupervisorJob()
        val dialog = SqliteBlobDialog.show(context, "payload", 512, CoroutineScope(job + Dispatchers.Main.immediate)) { _, _ ->
            throw CancellationException("Database closed")
        }
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        val list = views(dialog.window!!.decorView).filterIsInstance<RecyclerView>().single()
        try {
            list.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 480, 300)
            for (attempt in 0 until 200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (!dialog.isShowing) break
                Thread.sleep(5)
            }
            assertFalse(dialog.isShowing)
        } finally { dialog.dismiss(); job.cancel(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun `BLOB dialog uses the hex recycler and closes when its screen scope ends`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Twig)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        var reads = 0
        val dialog = SqliteBlobDialog.show(context, "payload", 0, scope) { _, _ -> reads++; ByteArray(0) }
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        val list = views(dialog.window!!.decorView).filterIsInstance<RecyclerView>().single()
        assertTrue(dialog.isShowing)
        assertEquals(0, list.adapter!!.itemCount)
        assertEquals(0, reads)
        job.cancel()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }
}
