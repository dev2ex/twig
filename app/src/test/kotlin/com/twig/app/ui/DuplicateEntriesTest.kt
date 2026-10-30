package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DuplicateEntriesTest {
    @Test fun `numbering preserves file extensions and dotted folder names and avoids case collisions`() {
        val fs = FakeFileSystem("duplicate-names", dirs = mapOf("/" to listOf("album.old", "photo.jpg", "PHOTO_1.jpg", ".hidden"), "/album.old" to emptyList()), files = mapOf(
            "/photo.jpg" to "a", "/PHOTO_1.jpg" to "b", "/.hidden" to "c",
        ), writable = true)
        FsRegistry.register(fs)
        val files = fs.list(fs.root())
        val names = DuplicateEntries.names(files.map { it to fs.root() })
        assertEquals("photo_2.jpg", names["duplicate-names:///photo.jpg"])
        assertEquals("album.old_1", names["duplicate-names:///album.old"])
        assertEquals(".hidden_1", names["duplicate-names:///.hidden"])
    }

    @Test fun `numbered files and folders advance their suffix instead of stacking it`() {
        val fs = FakeFileSystem("duplicate-sequence", dirs = mapOf(
            "/" to listOf("file_1.txt", "file_2.txt", "folder_9"), "/folder_9" to emptyList(),
        ), files = mapOf("/file_1.txt" to "a", "/file_2.txt" to "b"), writable = true)
        FsRegistry.register(fs)
        val names = DuplicateEntries.names(fs.list(fs.root()).map { it to fs.root() })
        assertEquals("file_3.txt", names["duplicate-sequence:///file_1.txt"])
        assertEquals("file_4.txt", names["duplicate-sequence:///file_2.txt"])
        assertEquals("folder_10", names["duplicate-sequence:///folder_9"])
    }

    @Test fun `opaque entries use their tree parent without parsing the document URI`() {
        val parent = XFile("duplicate-opaque", "content://docs/tree/root/document/root%3AFolder", true)
        val file = XFile(parent.scheme, parent.path + "%2Ffile.txt", false, displayName = "file.txt")
        val fs = object : FileSystem by FakeFileSystem(parent.scheme, emptyMap(), writable = true) {
            override fun parentOf(file: XFile): XFile? = null
            override fun list(dir: XFile): List<XFile> {
                assertEquals(parent, dir)
                return listOf(file)
            }
        }
        FsRegistry.register(fs)
        val pairs = DuplicateEntries.parents(ApplicationProvider.getApplicationContext<Application>(), listOf(file), mapOf(file to parent))
        assertEquals(listOf(file to parent), pairs)
        assertEquals("file_1.txt", DuplicateEntries.names(pairs)[file.toUri()])
    }

    @Test fun `failed listings never produce a free name and cancellation stops enumeration`() {
        val fs = object : FileSystem by FakeFileSystem("duplicate-error", emptyMap(), writable = true) {
            override fun list(dir: XFile): List<XFile> = error("offline")
        }
        FsRegistry.register(fs)
        val pairs = listOf(XFile(fs.scheme, "/file.txt", false) to fs.root())
        assertThrows(IllegalStateException::class.java) { DuplicateEntries.names(pairs) }
        assertThrows(java.util.concurrent.CancellationException::class.java) { DuplicateEntries.names(pairs) { true } }
    }

    @Test fun `selected ancestors and descendants are rejected before mutation`() {
        val fs = FakeFileSystem("duplicate-nested", mapOf("/" to listOf("folder"), "/folder" to listOf("file.txt")), mapOf("/folder/file.txt" to "data"), writable = true)
        FsRegistry.register(fs)
        val folder = fs.resolve("/folder")
        val file = fs.resolve("/folder/file.txt")
        assertThrows(IllegalStateException::class.java) {
            DuplicateEntries.parents(ApplicationProvider.getApplicationContext<Application>(), listOf(folder, file), emptyMap())
        }
    }
}
