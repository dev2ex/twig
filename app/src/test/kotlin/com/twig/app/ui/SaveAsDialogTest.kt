package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveAsDialogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private fun destination() = XFile("file", directory.path, true)

    @Before fun setup() {
        FsRegistry.register(LocalFileSystem())
        directory = File(context.cacheDir, "blob-export-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { directory.deleteRecursively(); FsRegistry.unregister("blob-export-test") }

    @Test fun `save writes the entire raw BLOB including empty values in bounded chunks`() = runTest {
        val bytes = ByteArray(200123) { (it % 251).toByte() }
        val ranges = mutableListOf<Pair<Long, Int>>()
        val progress = mutableListOf<Long>()
        SaveAsDialog.write(context, destination(), "payload.bin", bytes.size.toLong(), { offset, count ->
            ranges.add(offset to count)
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }, progress::add)
        assertArrayEquals(bytes, File(directory, "payload.bin").readBytes())
        assertEquals(4, ranges.size)
        assertTrue(ranges.all { it.second <= HexByteSource.CHUNK })
        assertEquals(bytes.size.toLong(), progress.last())
        var emptyRead = false
        SaveAsDialog.write(context, destination(), "empty.bin", 0, { _, _ -> emptyRead = true; ByteArray(0) })
        assertFalse(emptyRead)
        assertEquals(0, File(directory, "empty.bin").length())
    }

    @Test fun `existing files and failed listings never permit an overwrite`() = runTest {
        val existing = File(directory, "keep.bin").apply { writeText("keep") }
        var read = false
        assertTrue(runCatching {
            SaveAsDialog.write(context, destination(), "KEEP.bin", 1, { _, _ -> read = true; byteArrayOf(1) })
        }.isFailure)
        assertFalse(read)
        assertEquals("keep", existing.readText())
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = "blob-export-test"
            override fun list(dir: XFile): List<XFile> = error("Listing unavailable")
            override fun createFile(parent: XFile, name: String): XFile = error("Must not create after a failed listing")
        })
        assertTrue(runCatching {
            SaveAsDialog.write(context, destination().copy(scheme = "blob-export-test"), "new.bin", 1,
                { _, _ -> read = true; byteArrayOf(1) })
        }.isFailure)
        assertFalse(read)
        assertEquals(listOf("keep.bin"), directory.list()!!.toList())
    }

    @Test fun `cancelled and incomplete exports remove partial output`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            SaveAsDialog.write(context, destination(), "cancel.bin", HexByteSource.CHUNK * 2L, { offset, count ->
                if (offset == 0L) ByteArray(count) else { waiting.complete(Unit); awaitCancellation() }
            })
        }
        waiting.await()
        job.cancelAndJoin()
        assertFalse(File(directory, "cancel.bin").exists())
        assertTrue(runCatching {
            SaveAsDialog.write(context, destination(), "short.bin", 100, { _, _ -> ByteArray(50) })
        }.isFailure)
        assertFalse(File(directory, "short.bin").exists())
        assertTrue(runCatching {
            SaveAsDialog.write(context, destination(), "closed.bin", 100, { _, _ -> throw CancellationException("Database closed") })
        }.isFailure)
        assertFalse(File(directory, "closed.bin").exists())
    }

    @Test fun `suggested names are safe and invalid names cannot escape the destination`() = runTest {
        assertEquals("path_col_", SaveAsDialog.suggestedName("path/col\u0000"))
        assertEquals("file", SaveAsDialog.suggestedName(".."))
        for (name in listOf("../other.bin", "..", ".", "", "a\\b", "a\nb")) {
            assertFalse(SaveAsDialog.validName(name))
            assertTrue(runCatching { SaveAsDialog.write(context, destination(), name, 0, { _, _ -> ByteArray(0) }) }.isFailure)
        }
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun `save button keeps the viewer open and exports through Twig's directory picker`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_Twig) }
        controller.setup()
        val fragment = Fragment(android.R.layout.simple_list_item_1)
        var saved = false
        val exporter = SaveAsDialog(fragment)
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val bytes = ByteArray(65543) { it.toByte() }
        val reader: suspend (Long, Int) -> ByteArray = { offset, count -> bytes.copyOfRange(offset.toInt(), offset.toInt() + count) }
        val viewer = SqliteBlobDialog.show(activity, "payload", bytes.size.toLong(), fragment.viewLifecycleOwner.lifecycleScope,
            onSave = { exporter.offer("payload.bin", bytes.size.toLong(), reader) { saved = true } }, read = reader)
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        try {
            viewer.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertTrue(viewer.isShowing)
            val name = ShadowDialog.getLatestDialog() as AlertDialog
            val input = views(name.window!!.decorView).filterIsInstance<EditText>().single()
            assertEquals("payload.bin", input.text.toString())
            input.setText("saved.bin")
            name.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val started = shadowOf(activity).nextStartedActivityForResult
            assertEquals(PickerActivity::class.java.name, started.intent.component!!.className)
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent()
                .putExtra(PickerActivity.EXTRA_PICKED_SCHEME, "file")
                .putExtra(PickerActivity.EXTRA_PICKED_PATH, directory.path))
            for (attempt in 0 until 300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (saved) break
                Thread.sleep(5)
            }
            assertTrue(saved)
            assertArrayEquals(bytes, File(directory, "saved.bin").readBytes())
            assertTrue(viewer.isShowing)
        } finally {
            exporter.destroy(); viewer.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }
}
