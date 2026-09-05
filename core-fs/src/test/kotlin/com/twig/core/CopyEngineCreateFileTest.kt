package com.twig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Verifies that [CopyEngine] lets [FileSystem.createFile] decide the destination entry —
 * this is the precondition for filesystems like SAF / cloud drives, where an object
 * "must be created first to get an opaque id (URI/object id)", to be written correctly.
 *
 * An in-memory filesystem simulates that semantics: path is an opaque id, and the real
 * file name lives in displayName.
 */
class CopyEngineCreateFileTest {

    /** Source: an ordinary path-style read-only in-memory fs. */
    private class SrcFs : FileSystem {
        override val scheme = "src"
        override val displayName = "src"
        val data = mapOf("/a.txt" to "hello".toByteArray())
        override fun root() = XFile("src", "/", true)
        override fun resolve(path: String) = XFile("src", path, false)
        override fun list(dir: XFile) = data.keys.map { XFile("src", it, false, data[it]!!.size.toLong()) }
        override fun openInput(file: XFile): InputStream = ByteArrayInputStream(data[file.path]!!)
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("ro")
        override fun mkdir(parent: XFile, name: String) = throw FsException("ro")
        override fun delete(file: XFile) = throw FsException("ro")
        override fun rename(file: XFile, newName: String) = throw FsException("ro")
        override fun exists(file: XFile) = data.containsKey(file.path)
    }

    /** Destination: an opaque-id in-memory fs. createFile generates the id and puts the name into displayName. */
    private class OpaqueFs : FileSystem {
        override val scheme = "mem"
        override val displayName = "mem"
        val store = LinkedHashMap<String, ByteArrayOutputStream>() // id -> bytes
        val names = LinkedHashMap<String, String>()                // id -> displayName
        private var seq = 0

        override fun root() = XFile("mem", "id:root", true)
        override fun resolve(path: String) = XFile("mem", path, true)
        override fun list(dir: XFile) = emptyList<XFile>()
        override fun openInput(file: XFile): InputStream = ByteArrayInputStream(store[file.path]!!.toByteArray())
        override fun openOutput(file: XFile, append: Boolean): OutputStream =
            store.getOrPut(file.path) { ByteArrayOutputStream() }
        override fun mkdir(parent: XFile, name: String) = XFile("mem", "id:${seq++}", true, displayName = name)
        override fun delete(file: XFile) {}
        override fun rename(file: XFile, newName: String) = file
        override fun exists(file: XFile) = store.containsKey(file.path)

        // Key point: the destination entry uses a freshly created opaque id, the real name
        // goes into displayName (the name cannot be derived from the path)
        override fun createFile(parent: XFile, name: String): XFile {
            val id = "id:${seq++}"
            names[id] = name
            return XFile("mem", id, false, displayName = name)
        }
    }

    private val src = SrcFs()
    private val dest = OpaqueFs()

    @Before
    fun setup() {
        FsRegistry.register(src)
        FsRegistry.register(dest)
    }

    @Test
    fun copyUsesCreateFileAndPreservesName() {
        val file = src.list(src.root()).first()
        CopyEngine.transfer(listOf(file), dest.root(), move = false)

        // The destination has one opaque-id entry whose displayName is the original file
        // name, and the content is correct
        assertEquals(1, dest.store.size)
        val id = dest.store.keys.first()
        assertTrue(id.startsWith("id:"))
        assertEquals("a.txt", dest.names[id])
        assertEquals("hello", dest.store[id]!!.toByteArray().decodeToString())
    }

    @Test
    fun displayNameOverridesPathDerivedName() {
        val x = XFile("mem", "id:xyz%2Fopaque", isDir = false, displayName = "report.pdf")
        assertEquals("report.pdf", x.name)
        // Falls back to the last path segment when displayName is not set
        assertEquals("c.txt", XFile("file", "/a/b/c.txt", false).name)
    }
}
