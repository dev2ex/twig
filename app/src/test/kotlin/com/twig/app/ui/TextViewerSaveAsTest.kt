package com.twig.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.TextCodec
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextViewerSaveAsTest {
    private lateinit var directory: File
    private lateinit var source: File
    private lateinit var controller: ActivityController<TextViewerActivity>
    private val activity get() = controller.get()
    private val content get() = activity.findViewById<EditText>(R.id.content)
    private val menu get() = activity.findViewById<Toolbar>(R.id.toolbar).menu as MenuBuilder

    @Before fun setup() {
        FsRegistry.register(LocalFileSystem())
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        directory = File(context.cacheDir, "text-save-as-${UUID.randomUUID()}").apply { mkdirs() }
        source = File(directory, "original.txt")
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        directory.deleteRecursively()
        FsRegistry.unregister("text-opaque")
    }

    private fun await(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("The asynchronous save/load did not finish", condition())
    }

    private fun open(bytes: ByteArray) {
        source.writeBytes(bytes)
        controller = Robolectric.buildActivity(TextViewerActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), TextViewerActivity::class.java)
                .putExtra("scheme", "file").putExtra("path", source.path).putExtra("name", source.name)
                .putExtra("edit", true).putExtra("preview", false)).setup().visible()
        await { ReflectionHelpers.getField<Boolean>(activity, "editMode") }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun latest() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun chooseName(name: String) {
        val dialog = latest()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(name)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun chooseDirectory(destination: File) {
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(PickerActivity::class.java.name, started.intent.component!!.className)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent()
            .putExtra(PickerActivity.EXTRA_PICKED_SCHEME, "file")
            .putExtra(PickerActivity.EXTRA_PICKED_PATH, destination.path))
    }

    @Test fun `save as preserves UTF8 BOM adopts the new file and later Save only changes that file`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val original = TextCodec.encode("original", Charsets.UTF_8, bom)!!
        open(original)
        val text = "edited\r\n第二行"
        content.setText(text)
        content.setSelection(2, 4)
        assertTrue(menu.findItem(R.id.viewer_save_as).isVisible)
        assertTrue(menu.performIdentifierAction(R.id.viewer_save_as, 0))
        chooseName("copy.md")
        val destination = File(directory, "destination").apply { mkdirs() }
        chooseDirectory(destination)
        val copy = File(destination, "copy.md")
        await { activity.intent.getStringExtra("path") == copy.path }
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(TextCodec.encode(text, Charsets.UTF_8, bom), copy.readBytes())
        assertEquals(destination.path, activity.intent.getStringExtra("save_parent"))
        assertEquals("copy.md", activity.findViewById<Toolbar>(R.id.toolbar).title)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "isMarkdown"))
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "dirty"))
        assertEquals(2, content.selectionStart)
        assertEquals(4, content.selectionEnd)
        content.append("!")
        assertTrue(menu.performIdentifierAction(R.id.viewer_save, 0))
        val expected = TextCodec.encode("$text!", Charsets.UTF_8, bom)!!
        await { !ReflectionHelpers.getField<Boolean>(activity, "saving") && copy.exists() && copy.readBytes().contentEquals(expected) }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun `legacy encoding is preserved and conversion consent applies only after successful save as`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val previous = Prefs.textCharsets(context)
        val charset = Charset.forName("windows-1252")
        Prefs.setTextCharsets(context, listOf(charset.name()))
        TextCodec.load(context)
        try {
            val original = TextCodec.encode("café", charset, null)!!
            open(original)
            assertEquals(charset, ReflectionHelpers.getField<Charset>(activity, "fileCharset"))
            content.setText("café edited")
            assertTrue(menu.performIdentifierAction(R.id.viewer_save_as, 0))
            chooseName("legacy.txt")
            chooseDirectory(directory)
            val legacy = File(directory, "legacy.txt")
            await { activity.intent.getStringExtra("path") == legacy.path }
            assertArrayEquals(TextCodec.encode("café edited", charset, null), legacy.readBytes())
            assertEquals(charset, ReflectionHelpers.getField<Charset>(activity, "fileCharset"))
            content.setText("café😀")
            assertTrue(menu.performIdentifierAction(R.id.viewer_save_as, 0))
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(charset, ReflectionHelpers.getField<Charset>(activity, "fileCharset"))
            assertTrue(ReflectionHelpers.getField<Boolean>(activity, "dirty"))
            assertEquals(legacy.path, activity.intent.getStringExtra("path"))
            assertTrue(menu.performIdentifierAction(R.id.viewer_save_as, 0))
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            chooseName("utf8.txt")
            chooseDirectory(directory)
            val copy = File(directory, "utf8.txt")
            await { activity.intent.getStringExtra("path") == copy.path }
            assertArrayEquals("café😀".toByteArray(Charsets.UTF_8), copy.readBytes())
            assertEquals(Charsets.UTF_8, ReflectionHelpers.getField<Charset>(activity, "fileCharset"))
            assertArrayEquals(original, source.readBytes())
        } finally { Prefs.setTextCharsets(context, previous); TextCodec.load(context) }
    }

    @Test fun `later saves retain opaque parent and adopt replacement document identities`() {
        open("original".toByteArray())
        val parent = com.twig.core.XFile("text-opaque", "opaque:directory", true)
        val files = java.util.concurrent.ConcurrentHashMap<String, com.twig.core.XFile>()
        val data = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        var nextId = 0
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = parent.scheme
            override fun resolve(path: String): com.twig.core.XFile = error("Opaque parents cannot be derived from paths")
            override fun list(dir: com.twig.core.XFile): List<com.twig.core.XFile> {
                check(dir.path == parent.path)
                return files.values.toList()
            }
            override fun exists(file: com.twig.core.XFile) = files.containsKey(file.path)
            override fun createFile(dir: com.twig.core.XFile, name: String): com.twig.core.XFile {
                check(dir.path == parent.path)
                return com.twig.core.XFile(scheme, "opaque:document-${++nextId}", false, displayName = name)
                    .also { files[it.path] = it }
            }
            override fun openOutput(file: com.twig.core.XFile, append: Boolean) = object : java.io.ByteArrayOutputStream() {
                override fun close() { data[file.path] = toByteArray(); super.close() }
            }
            override fun delete(file: com.twig.core.XFile) { files.remove(file.path); data.remove(file.path) }
            override fun rename(file: com.twig.core.XFile, newName: String): com.twig.core.XFile {
                val bytes = data.remove(file.path)!!
                files.remove(file.path)
                return file.copy(path = "opaque:renamed-${++nextId}", displayName = newName).also {
                    files[it.path] = it; data[it.path] = bytes
                }
            }
        })
        content.setText("copy")
        assertTrue(menu.performIdentifierAction(R.id.viewer_save_as, 0))
        chooseName("opaque.txt")
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent()
            .putExtra(PickerActivity.EXTRA_PICKED_SCHEME, parent.scheme)
            .putExtra(PickerActivity.EXTRA_PICKED_PATH, parent.path))
        await { activity.intent.getStringExtra("scheme") == parent.scheme }
        for (suffix in listOf("1", "2")) {
            val previous = activity.intent.getStringExtra("path")!!
            content.append(suffix)
            val expected = content.text.toString().toByteArray()
            assertTrue(menu.performIdentifierAction(R.id.viewer_save, 0))
            await { !ReflectionHelpers.getField<Boolean>(activity, "saving") && activity.intent.getStringExtra("path") != previous }
            assertArrayEquals(expected, data[activity.intent.getStringExtra("path")!!])
        }
        assertEquals(1, files.size)
        assertEquals("original", source.readText())
    }

    @Test fun `cancelling directory selection keeps the current file and unsaved edits`() {
        val original = "original".toByteArray()
        open(original)
        content.setText("unsaved")
        assertTrue(menu.performIdentifierAction(R.id.viewer_save_as, 0))
        chooseName("cancel.txt")
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(source.path, activity.intent.getStringExtra("path"))
        assertEquals("unsaved", content.text.toString())
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "dirty"))
        assertArrayEquals(original, source.readBytes())
        assertFalse(File(directory, "cancel.txt").exists())
    }
}
