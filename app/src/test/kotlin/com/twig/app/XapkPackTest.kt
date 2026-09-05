package com.twig.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * [XapkPack] hand-writes the raw bytes of a zip (local header / central directory / EOCD
 * offsets and little-endian fields); getting a single byte wrong would only surface once
 * installed on a phone. Here the JVM's own [ZipFile] parses it back the other way —
 * it genuinely locates the central directory via the EOCD, locates each local header via
 * the offsets in the central directory, and checks the CRC along the way, which amounts
 * to validating the whole structure at once.
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
    fun `the packed archive parses with a standard zip reader, content and entry names match`() {
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

    /** The size must be computed exactly without reading the file content — the copy progress bar and space checks rely entirely on it. */
    @Test
    fun `totalSize exactly equals the actual packed byte count`() {
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
    fun `entries are stored as STORED, CRC matches the original file`() {
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

    /** Edge cases like empty files and overlong file names must not throw the offsets off either. */
    @Test
    fun `empty entries and long file names`() {
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

    /** A pre-1980 timestamp (some system-partition apks have mtime 0) must not write out an illegal DOS date. */
    @Test
    fun `an ancient modification time is clamped to 1980`() {
        val old = tmpFile("old", "x".toByteArray()).apply { setLastModified(0L) }
        val p = XapkPack(listOf(XapkPack.Entry("com.foo.apk", null, old)))
        ZipFile(pack(p)).use { zip ->
            val t = zip.getEntry("com.foo.apk").time
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
            assertEquals(1980, cal.get(java.util.Calendar.YEAR))
        }
    }
}
