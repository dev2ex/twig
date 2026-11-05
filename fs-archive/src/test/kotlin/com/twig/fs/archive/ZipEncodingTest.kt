package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipFile

class ZipEncodingTest {
    @get:Rule val temp = TemporaryFolder()
    private var preferred = listOf(Charset.forName("GB18030"))
    private val fs = ZipFileSystem { preferred }
    private data class Entry(val name: ByteArray, val text: String, val utf8: Boolean = false)

    @Before fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(fs)
    }

    /** Raw central/local records: standard writers refuse malformed names or normalize flags. */
    private fun archive(vararg entries: Entry): XFile {
        val file = temp.newFile("${System.nanoTime()}.zip")
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun ByteArrayOutputStream.le(value: Long, bytes: Int) {
            repeat(bytes) { write((value shr (8 * it)).toInt() and 255) }
        }
        for (e in entries) {
            val offset = out.size()
            val data = e.text.toByteArray()
            val crc = CRC32().apply { update(data) }.value
            out.le(0x04034b50, 4); out.le(20, 2); out.le(if (e.utf8) 2048 else 0, 2)
            out.le(0, 2); out.le(0, 4); out.le(crc, 4)
            out.le(data.size.toLong(), 4); out.le(data.size.toLong(), 4)
            out.le(e.name.size.toLong(), 2); out.le(0, 2); out.write(e.name); out.write(data)
            central.le(0x02014b50, 4); central.le(20, 2); central.le(20, 2)
            central.le(if (e.utf8) 2048 else 0, 2); central.le(0, 2); central.le(0, 4)
            central.le(crc, 4); central.le(data.size.toLong(), 4); central.le(data.size.toLong(), 4)
            central.le(e.name.size.toLong(), 2); central.le(0, 2); central.le(0, 2)
            central.le(0, 2); central.le(0, 2); central.le(0, 4); central.le(offset.toLong(), 4)
            central.write(e.name)
        }
        val offset = out.size()
        out.write(central.toByteArray())
        out.le(0x06054b50, 4); out.le(0, 2); out.le(0, 2)
        out.le(entries.size.toLong(), 2); out.le(entries.size.toLong(), 2)
        out.le(central.size().toLong(), 4); out.le(offset.toLong(), 4); out.le(0, 2)
        file.writeBytes(out.toByteArray())
        return XFile("file", file.absolutePath, false, size = file.length())
    }

    private fun text(file: XFile) = fs.openInput(file).bufferedReader().use { it.readText() }

    @Test fun `settings order chooses ambiguous legacy names`() {
        val bytes = "中文测试.txt".toByteArray(Charset.forName("GBK"))
        val host = archive(Entry(bytes, "payload"))
        val gbk = Charset.forName("GBK")
        val big5 = Charset.forName("Big5")
        preferred = listOf(gbk, big5)
        assertEquals("中文测试.txt", fs.list(fs.rootOf(host)).single().name)
        preferred = listOf(big5, gbk)
        val entry = fs.list(fs.rootOf(host)).single()
        assertEquals(String(bytes, big5), entry.name)
        assertEquals("payload", text(entry))
    }

    @Test fun `GB18030 is always tried even when absent from settings`() {
        // Includes a four-byte GB18030 character, beyond the old GBK fallback.
        val name = "报告𠀀.txt"
        val host = archive(Entry(name.toByteArray(Charset.forName("GB18030")), "payload"))
        for (settings in listOf(emptyList(), listOf(Charsets.US_ASCII))) {
            preferred = settings
            val entry = fs.list(fs.rootOf(host)).single()
            assertEquals(name, entry.name)
            assertEquals("payload", text(entry))
        }
    }

    @Test fun `each name can use a different encoding and later candidates are tried`() {
        preferred = listOf(Charsets.US_ASCII, Charset.forName("GBK"), Charset.forName("Shift_JIS"))
        val host = archive(
            Entry("报告.txt".toByteArray(Charset.forName("GBK")), "chinese"),
            Entry("日本語.txt".toByteArray(), "unicode"),
            Entry("ｦ.txt".toByteArray(Charset.forName("Shift_JIS")), "japanese"),
        )
        val files = fs.list(fs.rootOf(host))
        assertEquals(setOf("报告.txt", "日本語.txt", "ｦ.txt"), files.map { it.name }.toSet())
        assertEquals(setOf("chinese", "unicode", "japanese"), files.map(::text).toSet())
    }

    @Test fun `Shift JIS trail backslash is part of a name and legacy separators still work`() {
        preferred = listOf(Charset.forName("Shift_JIS"))
        val host = archive(
            Entry("dir/表.txt".toByteArray(preferred.single()), "forward"),
            Entry("dir\\ソ.txt".toByteArray(preferred.single()), "backslash"),
        )
        val dir = fs.list(fs.rootOf(host)).single()
        assertEquals("dir", dir.name)
        val children = fs.list(dir)
        assertEquals(setOf("表.txt", "ソ.txt"), children.map { it.name }.toSet())
        assertEquals(setOf("forward", "backslash"), children.map(::text).toSet())
    }

    @Test fun `valid Unicode path extra overrides legacy bytes`() {
        preferred = emptyList()
        val file = temp.newFile("unicode-extra.zip")
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(file).use { zip ->
            zip.setEncoding("CP437")
            zip.setCreateUnicodeExtraFields(
                org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS,
            )
            val entry = org.apache.commons.compress.archivers.zip.ZipArchiveEntry("café.txt")
            zip.putArchiveEntry(entry)
            zip.write("payload".toByteArray())
            zip.closeArchiveEntry()
        }
        val entry = fs.list(fs.rootOf(XFile("file", file.absolutePath, false))).single()
        assertEquals("café.txt", entry.name)
        assertEquals("payload", text(entry))
    }

    @Test fun `wrong UTF-8 flag still allows a preferred encoding`() {
        val host = archive(Entry("报告.txt".toByteArray(Charset.forName("GBK")), "payload", utf8 = true))
        val entry = fs.list(fs.rootOf(host)).single()
        assertEquals("报告.txt", entry.name)
        assertEquals("payload", text(entry))
    }

    @Test fun `fallback collisions preserve files directories and extraction`() {
        preferred = emptyList()
        val host = archive(
            Entry(byteArrayOf(0x81.toByte()) + ".txt".toByteArray(), "first"),
            Entry(byteArrayOf(0x82.toByte()) + ".txt".toByteArray(), "second"),
            Entry("� (2).txt".toByteArray(), "literal"),
            Entry(byteArrayOf(0x81.toByte()) + "/a.txt".toByteArray(), "dir-one"),
            Entry(byteArrayOf(0x82.toByte()) + "/a.txt".toByteArray(), "dir-two"),
            Entry("ok.txt".toByteArray(), "normal"),
        )
        val root = fs.rootOf(host)
        val children = fs.list(root)
        assertEquals(6, children.size)
        assertEquals(6, children.map { it.name }.toSet().size)
        val contents = children.flatMap { if (it.isDir) fs.list(it) else listOf(it) }.map(::text).toSet()
        assertEquals(setOf("first", "second", "literal", "dir-one", "dir-two", "normal"), contents)
        val dest = temp.newFolder("out")
        CopyEngine.transfer(children, XFile("file", dest.absolutePath, true), move = false)
        assertEquals(contents, dest.walkTopDown().filter { it.isFile }.map { it.readText() }.toSet())
        for (file in children.filter { !it.isDir }) {
            fs.openRandom(file).use { source ->
                val buffer = ByteArray(file.size.toInt())
                assertEquals(buffer.size, source.readAt(0, buffer, 0, buffer.size))
                assertEquals(text(file), String(buffer))
            }
        }
    }

    @Test fun `remote listing changes when encoding preferences change`() {
        val bytes = "中文测试.txt".toByteArray(Charset.forName("GBK"))
        val local = archive(Entry(bytes, "remote payload"))
        val remoteFs = MountFs(temp.root)
        FsRegistry.register(remoteFs)
        val host = remoteFs.resolve("/" + File(local.path).name)
        val root = fs.rootOf(host)
        preferred = listOf(Charset.forName("GBK"))
        assertEquals("中文测试.txt", fs.list(root).single().name)
        preferred = listOf(Charset.forName("Big5"))
        val entry = fs.list(root).single()
        assertEquals(String(bytes, preferred.single()), entry.name)
        assertEquals("remote payload", text(entry))
    }

    @Test fun `encrypted malformed names still prompt verify and decrypt`() {
        preferred = emptyList()
        val file = temp.newFile("encrypted.zip")
        ZipWriter(file.outputStream(), "secret").use { zip ->
            zip.putNextEntry("xx.txt", System.currentTimeMillis(), 7)
            zip.write("payload".toByteArray())
            zip.closeEntry()
        }
        val bytes = file.readBytes()
        // Only replace name bytes in the local and central headers, retaining the UTF-8 flag.
        for (i in 0 until bytes.size - 6) {
            if (String(bytes, i, 6, Charsets.US_ASCII) == "xx.txt") bytes[i] = 0x81.toByte()
        }
        file.writeBytes(bytes)
        val host = XFile("file", file.absolutePath, false)
        val root = fs.rootOf(host)
        assertTrue(fs.needsPassword(host.path))
        assertFalse(fs.checkPassword(host.path, "wrong"))
        assertTrue(fs.checkPassword(host.path, "secret"))
        fs.setPassword(host.path, "secret")
        assertEquals("payload", text(fs.list(root).single()))
    }

    @Test fun `rewriting malformed archive preserves all remaining payloads`() {
        preferred = emptyList()
        val host = archive(
            Entry(byteArrayOf(0x81.toByte()) + ".txt".toByteArray(), "first"),
            Entry(byteArrayOf(0x82.toByte()) + ".txt".toByteArray(), "second"),
            Entry("delete.txt".toByteArray(), "remove"),
        )
        val root = fs.rootOf(host)
        fs.delete(fs.list(root).first { it.name == "delete.txt" })
        ZipFile(File(host.path)).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(2, entries.size)
            assertEquals(setOf("first", "second"), entries.map { zip.getInputStream(it).reader().readText() }.toSet())
        }
        assertEquals(setOf("first", "second"), fs.list(root).map(::text).toSet())
    }
}
