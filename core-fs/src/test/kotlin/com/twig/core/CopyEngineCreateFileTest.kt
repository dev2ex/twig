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
 * 验证 [CopyEngine] 通过 [FileSystem.createFile] 决定目标条目——这是 SAF / 云盘这类
 * "必须先创建对象才能得到不透明标识(URI/object id)"的文件系统能被正确写入的前提。
 *
 * 用一个内存文件系统模拟该语义:path 是不透明 id,真实文件名放在 displayName。
 */
class CopyEngineCreateFileTest {

    /** 源:常规路径式只读内存 fs。 */
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

    /** 目标:不透明 id 内存 fs。createFile 生成 id 并把名字放进 displayName。 */
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

        // 关键:目标条目用新建的不透明 id,真实名字进 displayName(path 不可拼接得名字)
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

        // 目标里有一个不透明 id 的条目,其 displayName 是原文件名,内容正确
        assertEquals(1, dest.store.size)
        val id = dest.store.keys.first()
        assertTrue(id.startsWith("id:"))
        assertEquals("a.txt", dest.names[id])
        assertEquals("hello", dest.store[id]!!.toByteArray().decodeToString())
    }

    @Test
    fun displayNameOverridesPathDerivedName() {
        val x = XFile("mem", "id:xyz%2Fopaque", isDir = false, displayName = "报告.pdf")
        assertEquals("报告.pdf", x.name)
        // 不设 displayName 时回退到 path 末段
        assertEquals("c.txt", XFile("file", "/a/b/c.txt", false).name)
    }
}
