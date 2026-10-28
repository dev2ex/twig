package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.twig.app.*
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [TrashOsShadow::class, TrashMovesShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashThumbnailTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var store: TrashStore
    private lateinit var fs: TrashFileSystem

    @Before fun setup() {
        root = Files.createTempDirectory("twig-trash-thumbnail-").toFile().canonicalFile
        store = TrashStore(app, { listOf(root) })
        fs = TrashFileSystem(app, store)
        FsRegistry.register(fs)
        FsRegistry.register(LocalFileSystem())
        Prefs.setThumbsNetwork(app, false)
        Prefs.setThumbsEmbedded(app, false)
        Thumbs.clearCache(app)
    }

    @After fun cleanup() {
        Thumbs.clearCache(app)
        FsRegistry.unregister("trash")
        FsRegistry.unregister("file")
        root.deleteRecursively()
    }

    private fun image(file: File, color: Int) {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun xf(file: File) = XFile("file", file.path, file.isDirectory,
        size = if (file.isDirectory) 0 else file.length(), lastModified = file.lastModified())

    private fun generate(file: XFile): Bitmap? = Thumbs::class.java
        .getDeclaredMethod("generate", Context::class.java, XFile::class.java)
        .apply { isAccessible = true }.invoke(Thumbs, app, file) as Bitmap?

    private fun key(file: XFile) = Thumbs::class.java.getDeclaredMethod("keyOf", XFile::class.java)
        .apply { isAccessible = true }.invoke(Thumbs, file)

    @Test fun `uncached deleted image uses payload and shares original cache identity`() {
        val source = File(root, "photo.png")
        image(source, Color.BLUE)
        source.setLastModified(1_600_000_000_000L)
        val original = xf(source)
        store.move(store.plan(listOf(original)).single())
        val deleted = fs.list(fs.root()).single()
        assertEquals(key(original), key(deleted))
        assertNull(Thumbs.cached(deleted))
        image(source, Color.RED) // A new file at the original path must never supply the image.
        val result = generate(deleted)!!
        assertEquals(Color.BLUE, result.getPixel(20, 20))
        assertEquals("photo.png", fs.thumbnailSource(deleted).name)
    }

    @Test fun `nested deleted images generate without network thumbnails`() {
        val source = File(root, "album/sub/photo.png")
        image(source, Color.GREEN)
        store.move(store.plan(listOf(xf(File(root, "album")))).single())
        val album = fs.list(fs.root()).single()
        val nested = fs.list(fs.list(album).single()).single()
        assertEquals(Color.GREEN, generate(nested)!!.getPixel(20, 20))
        for (name in listOf("video.mp4", "song.mp3", "document.pdf")) {
            val candidate = nested.copy(displayName = name)
            val eligible = Thumbs::class.java.getDeclaredMethod("eligible", Context::class.java, XFile::class.java)
                .apply { isAccessible = true }.invoke(Thumbs, app, candidate) as Boolean
            assertTrue(name, eligible)
        }
    }

    @Test fun `thumbnail source refuses symlink payloads`() {
        val source = File(root, "photo.png")
        image(source, Color.BLUE)
        val entry = store.move(store.plan(listOf(xf(source))).single())
        val deleted = fs.list(fs.root()).single()
        val payload = store.payload(entry)
        assertTrue(payload.delete())
        image(source, Color.RED)
        Files.createSymbolicLink(payload.toPath(), source.toPath())
        assertThrows(java.io.IOException::class.java) { fs.thumbnailSource(deleted) }
    }
}
