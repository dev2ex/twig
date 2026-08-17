package com.twig.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * [XapkPack] 手写的是 zip 的原始字节(本地头/中央目录/EOCD 的偏移与小端字段),
 * 错一个字节就要装到手机上才发现。这里用 JVM 自带的 [ZipFile] 反过来解一遍 ——
 * 它会真的按 EOCD 找中央目录、按中央目录里的偏移定位每个本地头,顺带校验 CRC,
 * 等于把整套结构验了一遍。
 */
class XapkPackTest {

    private fun tmpFile(name: String, content: ByteArray): File =
        File.createTempFile(name, ".apk").apply { writeBytes(content); deleteOnExit() }

    private fun pack(pack: XapkPack): File {
        val out = File.createTempFile("out", ".xapk").apply { deleteOnExit() }
        pack.open().use { ins -> out.outputStream().use { ins.copyTo(it) } }
        return out
    }

    @Test
    fun `打出的包能被标准 zip 解析,内容与条目名一致`() {
        val base = tmpFile("base", ByteArray(5000) { (it % 251).toByte() })
        val split = tmpFile("split", ByteArray(1234) { (it % 97).toByte() })
        val manifest = """{"xapk_version":2,"package_name":"com.foo"}""".toByteArray()

        val p = XapkPack(
            listOf(
                XapkPack.Entry("manifest.json", manifest, null),
                XapkPack.Entry("com.foo.apk", null, base),
                XapkPack.Entry("split_config.arm64_v8a.apk", null, split),
            ),
        )
        val out = pack(p)

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf("manifest.json", "com.foo.apk", "split_config.arm64_v8a.apk"), names)
            assertArrayEquals(manifest, zip.getInputStream(zip.getEntry("manifest.json")).readBytes())
            assertArrayEquals(base.readBytes(), zip.getInputStream(zip.getEntry("com.foo.apk")).readBytes())
            assertArrayEquals(
                split.readBytes(),
                zip.getInputStream(zip.getEntry("split_config.arm64_v8a.apk")).readBytes(),
            )
        }
    }

    /** size 要在不读文件内容的前提下算准 —— 复制进度条/空间校验全靠它。 */
    @Test
    fun `totalSize 与实际打包字节数完全相等`() {
        val base = tmpFile("base", ByteArray(40_000) { it.toByte() })
        val split = tmpFile("split", ByteArray(7) { it.toByte() })
        val p = XapkPack(
            listOf(
                XapkPack.Entry("manifest.json", "{}".toByteArray(), null),
                XapkPack.Entry("com.foo.apk", null, base),
                XapkPack.Entry("split_config.xxhdpi.apk", null, split),
            ),
        )
        assertEquals(p.totalSize(), pack(p).length())
    }

    @Test
    fun `条目按 STORED 存储,CRC 与原文件一致`() {
        val base = tmpFile("base", "hello xapk".toByteArray())
        val p = XapkPack(listOf(XapkPack.Entry("com.foo.apk", null, base)))
        ZipFile(pack(p)).use { zip ->
            val e = zip.getEntry("com.foo.apk")
            assertEquals(java.util.zip.ZipEntry.STORED.toLong(), e.method.toLong())
            assertEquals(base.length(), e.size)
            assertEquals(base.length(), e.compressedSize)
            val crc = java.util.zip.CRC32().apply { update(base.readBytes()) }.value
            assertEquals(crc, e.crc)
        }
    }

    /** 空文件、超长文件名这类边角也不能把偏移算歪。 */
    @Test
    fun `空条目与长文件名`() {
        val empty = tmpFile("empty", ByteArray(0))
        val longName = "split_config." + "a".repeat(200) + ".apk"
        val p = XapkPack(
            listOf(
                XapkPack.Entry("manifest.json", "{}".toByteArray(), null),
                XapkPack.Entry(longName, null, empty),
            ),
        )
        val out = pack(p)
        assertEquals(p.totalSize(), out.length())
        ZipFile(out).use { zip ->
            assertTrue(zip.getEntry(longName) != null)
            assertEquals(0L, zip.getEntry(longName).size)
        }
    }

    /** 1980 年以前的时间戳(某些系统分区 apk 的 mtime 为 0)不能写出非法 DOS 日期。 */
    @Test
    fun `远古修改时间被钳到 1980`() {
        val old = tmpFile("old", "x".toByteArray()).apply { setLastModified(0L) }
        val p = XapkPack(listOf(XapkPack.Entry("com.foo.apk", null, old)))
        ZipFile(pack(p)).use { zip ->
            val t = zip.getEntry("com.foo.apk").time
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
            assertEquals(1980, cal.get(java.util.Calendar.YEAR))
        }
    }
}
