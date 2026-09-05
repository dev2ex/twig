package com.twig.fs.archive

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Password-protected packing. The zip path is a **hand-written writer** ([ZipWriter]),
 * so besides round-tripping we also have to keep an eye on "will other tools accept it" —
 * pointing `TWIG_DUMP_DIR` at a directory while running this class dumps the packages
 * out, which can then be verified with `7z t -psecret xxx.zip` / `unzip -P secret -t
 * xxx.zip`.
 */
class ArchiveEncryptWriteTest {

    private lateinit var tmp: File
    private lateinit var src: File
    private lateinit var out: File
    private val zipFs = ZipFileSystem()
    private val sevenZFs = SevenZFileSystem()

    /** A file large enough to cross deflate buffer and AES counter-block boundaries; single-block data would not exercise counter increments. */
    private val big = ByteArray(3 * 1024 * 1024) { ((it * 31) xor (it shr 7)).toByte() }

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zipFs)
        FsRegistry.register(sevenZFs)

        tmp = File.createTempFile("twigenc", "").let { it.delete(); it.mkdirs(); it }
        src = File(tmp, "src").apply { mkdirs() }
        File(src, "hello.txt").writeText("hello encrypted world")
        File(src, "中文 目录").mkdirs()
        File(src, "中文 目录/说明.txt").writeText("名字带空格和中文")
        File(src, "big.bin").writeBytes(big)
        out = File(tmp, "out").apply { mkdirs() }
    }

    private fun local(f: File) = XFile("file", f.absolutePath, isDir = f.isDirectory, size = f.length())

    private fun ArchiveFileSystem.read(archive: File, inner: String): ByteArray {
        val root = rootOf(XFile("file", archive.absolutePath, isDir = false, size = archive.length()))
        var cur = root
        val parts = inner.split('/')
        for (p in parts.dropLast(1)) cur = list(cur).first { it.name == p && it.isDir }
        val f = list(cur).first { it.name == parts.last() }
        return openInput(f).use { it.readBytes() }
    }

    private fun dump(f: File) {
        val dir = System.getenv("TWIG_DUMP_DIR")?.let { File(it) } ?: return
        dir.mkdirs()
        f.copyTo(File(dir, f.name), overwrite = true)
    }

    @Test
    fun encryptedZipCanBeReadBackByItselfByteForByte() {
        val archive = File(out, "enc.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.ZIP, password = "secret",
        )
        dump(archive)

        assertTrue(zipFs.needsPassword(archive.path))
        zipFs.setPassword(archive.path, "secret")
        assertEquals(
            "hello encrypted world",
            zipFs.read(archive, "src/hello.txt").toString(Charsets.UTF_8),
        )
        assertEquals(
            "名字带空格和中文",
            zipFs.read(archive, "src/中文 目录/说明.txt").toString(Charsets.UTF_8),
        )
        assertArrayEquals(big, zipFs.read(archive, "src/big.bin"))
    }

    @Test
    fun encryptedZipWithWrongPasswordReportsWrongPasswordNotFailure() {
        val archive = File(out, "enc2.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.ZIP, password = "secret",
        )
        assertFalse(zipFs.checkPassword(archive.path, "wrong"))
        assertTrue(zipFs.checkPassword(archive.path, "secret"))

        zipFs.setPassword(archive.path, "wrong")
        val t = runCatching { zipFs.read(archive, "src/hello.txt") }.exceptionOrNull()
        assertTrue("got $t", t is ArchivePasswordException && t.wrong)
    }

    @Test
    fun packingWithoutAPasswordProducesAnOrdinaryZip() {
        val archive = File(out, "plain.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.ZIP, password = null,
        )
        assertFalse(zipFs.needsPassword(archive.path))
        // An empty password is equivalent to no encryption (this is what an empty dialog field gives)
        val archive2 = File(out, "plain2.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive2),
            ArchiveWriter.Format.ZIP, password = "",
        )
        assertFalse(zipFs.needsPassword(archive2.path))
    }

    /**
     * The zip64 branch: whether the local header reserves room for a 64-bit size is
     * decided **before writing**, from sizeHint (see [ZipWriter]), so a fake large hint
     * exercises that path without actually creating a 4GB file.
     */
    @Test
    fun sizeHintOver4GbTakesTheZip64PathAndTheArchiveStaysValid() {
        val archive = File(out, "zip64.zip")
        archive.outputStream().use { os ->
            ZipWriter(os, "secret").use { zw ->
                zw.putDir("d", System.currentTimeMillis())
                zw.putNextEntry("d/a.txt", System.currentTimeMillis(), sizeHint = 5L shl 30)
                zw.write("zip64 entry".toByteArray())
                zw.closeEntry()
            }
        }
        dump(archive)
        zipFs.setPassword(archive.path, "secret")
        assertEquals("zip64 entry", zipFs.read(archive, "d/a.txt").toString(Charsets.UTF_8))
    }

    @Test
    fun zipWriterWithoutAPasswordProducesAFullyReadableOrdinaryZip() {
        val archive = File(out, "writer-plain.zip")
        archive.outputStream().use { os ->
            ZipWriter(os).use { zw ->
                zw.putNextEntry("a.txt", System.currentTimeMillis(), sizeHint = 5)
                zw.write("plain".toByteArray())
                zw.closeEntry()
            }
        }
        dump(archive)
        assertFalse(zipFs.needsPassword(archive.path))
        assertEquals("plain", zipFs.read(archive, "a.txt").toString(Charsets.UTF_8))
    }

    @Test
    fun encryptedSevenZCanBeReadBackByItself() {
        val archive = File(out, "enc.7z")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.SEVEN_Z, password = "secret",
        )
        dump(archive)

        assertTrue(sevenZFs.needsPassword(archive.path))
        assertFalse(sevenZFs.checkPassword(archive.path, "wrong"))
        sevenZFs.setPassword(archive.path, "secret")
        assertEquals(
            "hello encrypted world",
            sevenZFs.read(archive, "src/hello.txt").toString(Charsets.UTF_8),
        )
        assertArrayEquals(big, sevenZFs.read(archive, "src/big.bin"))
    }
}
