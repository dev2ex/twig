package com.twig.fs.archive

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Reading encrypted archives. **The sample packages were really produced by the `7z` /
 * `zip` CLI tools** (contents are the base64 below), not written by this project and
 * read back by itself — the read path for encrypted zip is entirely hand-written, and a
 * round-trip-only test would miss the one thing that actually matters: "can a package
 * written by another tool be read".
 *
 * The sample content is uniformly: `hello.txt` = "hello encrypted world\n",
 * `dir/b.txt` = "nested content here\n", password `secret`.
 */
class ArchivePasswordTest {

    private lateinit var tmp: File
    private val zfs = ZipFileSystem()
    private val szfs = SevenZFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zfs)
        FsRegistry.register(szfs)
        tmp = File.createTempFile("twigpw", "").let { it.delete(); it.mkdirs(); it }
    }

    private fun sample(name: String, b64: String): XFile {
        val f = File(tmp, name)
        f.writeBytes(Base64.getDecoder().decode(b64))
        return XFile(scheme = "file", path = f.absolutePath, isDir = false, size = f.length())
    }

    private fun ArchiveFileSystem.readText(root: XFile, name: String): String {
        val f = list(root).first { it.name == name }
        return openInput(f).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // ---- zip: WinZip AES-256 (7z a -tzip -mem=AES256) ----

    @Test
    fun aesZipNeedsPasswordForContentButNotForListing() {
        val x = sample("aes.zip", AES_ZIP)
        assertTrue(zfs.needsPassword(x.path))
        // File names are not encrypted, so listing works without a password
        val root = zfs.rootOf(x)
        assertEquals(listOf("dir", "hello.txt"), zfs.list(root).map { it.name })

        assertFalse(zfs.checkPassword(x.path, "wrong"))
        assertTrue(zfs.checkPassword(x.path, "secret"))

        zfs.setPassword(x.path, "secret")
        assertEquals("hello encrypted world\n", zfs.readText(root, "hello.txt"))
        val dir = zfs.list(root).first { it.isDir }
        assertEquals("nested content here\n", zfs.readText(dir, "b.txt"))
    }

    @Test
    fun aesZipThrowsRecognizableExceptionWithoutPassword() {
        val x = sample("aes.zip", AES_ZIP)
        val root = zfs.rootOf(x)
        val f = zfs.list(root).first { it.name == "hello.txt" }
        val t = runCatching { zfs.openInput(f).use { it.readBytes() } }.exceptionOrNull()
        assertTrue("got $t", t is ArchivePasswordException && !t.wrong)
    }

    @Test
    fun aesZipWithWrongPasswordThrowsWrongPasswordNotExtractionFailure() {
        val x = sample("aes.zip", AES_ZIP)
        zfs.setPassword(x.path, "nope")
        val root = zfs.rootOf(x)
        val f = zfs.list(root).first { it.name == "hello.txt" }
        val t = runCatching { zfs.openInput(f).use { it.readBytes() } }.exceptionOrNull()
        assertTrue("got $t", t is ArchivePasswordException && t.wrong)
    }

    @Test
    fun encryptedZipIsAlwaysReadOnly() {
        val x = sample("aes.zip", AES_ZIP)
        val root = zfs.rootOf(x)
        assertFalse(root.canWrite)
        assertFalse(zfs.list(root).first { it.name == "hello.txt" }.canWrite)
    }

    // ---- zip: legacy ZipCrypto (zip -e) ----

    @Test
    fun legacyZipCryptoPackageCanAlsoBeDecrypted() {
        val x = sample("legacy.zip", LEGACY_ZIP)
        assertTrue(zfs.needsPassword(x.path))
        assertFalse(zfs.checkPassword(x.path, "wrong"))
        assertTrue(zfs.checkPassword(x.path, "secret"))

        zfs.setPassword(x.path, "secret")
        val root = zfs.rootOf(x)
        assertEquals("hello encrypted world\n", zfs.readText(root, "hello.txt"))
    }

    // ---- 7z ----

    @Test
    fun sevenZContentEncryptedListingFreeContentNeedsPassword() {
        val x = sample("enc.7z", ENC_7Z)
        assertTrue(szfs.needsPassword(x.path))
        val root = szfs.rootOf(x)
        assertEquals(listOf("dir", "hello.txt"), szfs.list(root).map { it.name })

        assertFalse(szfs.checkPassword(x.path, "wrong"))
        assertTrue(szfs.checkPassword(x.path, "secret"))

        szfs.setPassword(x.path, "secret")
        assertEquals("hello encrypted world\n", szfs.readText(root, "hello.txt"))
    }

    @Test
    fun sevenZHeaderEncryptedCannotListWithoutPasswordButCanListAndReadWithIt() {
        val x = sample("hdr.7z", HDR_7Z)
        assertTrue(szfs.needsPassword(x.path))
        val t = runCatching { szfs.list(szfs.rootOf(x)) }.exceptionOrNull()
        assertTrue("got $t", t is ArchivePasswordException)

        szfs.setPassword(x.path, "secret")
        val root = szfs.rootOf(x)
        assertEquals(listOf("dir", "hello.txt"), szfs.list(root).map { it.name })
        assertEquals("hello encrypted world\n", szfs.readText(root, "hello.txt"))
    }

    // ---- Unencrypted packages are unaffected ----

    @Test
    fun ordinaryPackageDoesNotAskForAPassword() {
        val f = File(tmp, "plain.zip")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("a.txt"))
            z.write("plain".toByteArray())
            z.closeEntry()
        }
        val x = XFile(scheme = "file", path = f.absolutePath, isDir = false, size = f.length())
        assertFalse(zfs.needsPassword(x.path))
        val root = zfs.rootOf(x)
        assertTrue(root.canWrite)
        assertEquals("plain", zfs.readText(root, "a.txt"))
    }

    private companion object {
        // 7z a -tzip -mem=AES256 -psecret aes.zip hello.txt dir
        const val AES_ZIP =
            "UEsDBBQAAAAAAHaBC10AAAAAAAAAAAAAAAAEAAAAZGlyL1BLAwQzAAEAYwB2gQtdAAAAADAAAAAUAAAACQAL" +
                "AGRpci9iLnR4dAGZBwACAEFFAwAAx/r0oOoxEzakbe6+l7Ldn1ip4zAIjqZQiyH+3ipYY8GupeGk72p1" +
                "X8Mfin3mCZCCUEsDBDMAAQBjAHaBC10AAAAAMgAAABYAAAAJAAsAaGVsbG8udHh0AZkHAAIAQUUDAADJ" +
                "Xe0OcLIRBLGrkZ17LVMPi2wbO/p/YK4kS1tz9zlcrJ+TLp7L0q/E6z20H0qL+3cub1BLAQI/AxQAAAAA" +
                "AHaBC10AAAAAAAAAAAAAAAAEACQAAAAAAAAAEIDtQQAAAABkaXIvCgAgAAAAAAABABgAoRFuC2kp3QEA" +
                "AAAAAAAAAAAAAAAAAAAAUEsBAj8DMwABAGMAdoELXQAAAAAwAAAAFAAAAAkALwAAAAAAAAAggKSBIgAA" +
                "AGRpci9iLnR4dAoAIAAAAAAAAQAYAKERbgtpKd0BAAAAAAAAAAAAAAAAAAAAAAGZBwACAEFFAwAAUEsB" +
                "Aj8DMwABAGMAdoELXQAAAAAyAAAAFgAAAAkALwAAAAAAAAAggKSBhAAAAGhlbGxvLnR4dAoAIAAAAAAA" +
                "AQAYAKERbgtpKd0BAAAAAAAAAAAAAAAAAAAAAAGZBwACAEFFAwAAUEsFBgAAAAADAAMAIgEAAOgAAAAAAA=="

        // zip -e -P secret legacy.zip hello.txt dir/b.txt
        const val LEGACY_ZIP =
            "UEsDBAoACQAAAHaBC1304eFqIgAAABYAAAAJABwAaGVsbG8udHh0VVQJAAM/2XpqP9l6anV4CwABBOgDAAAE" +
                "6AMAALjMouEMHazh/mqDslIjo2D3pnSPwz1HZb7MjuO2wPS1iNhQSwcI9OHhaiIAAAAWAAAAUEsDBAoA" +
                "CQAAAHaBC10INDLMIAAAABQAAAAJABwAZGlyL2IudHh0VVQJAAM/2XpqP9l6anV4CwABBOgDAAAE6AMA" +
                "AKqp+UrwtbFk4y701SWbZXc0jn/ld05ogI9wHXbZTycoUEsHCAg0MswgAAAAFAAAAFBLAQIeAwoACQAA" +
                "AHaBC1304eFqIgAAABYAAAAJABgAAAAAAAEAAACkgQAAAABoZWxsby50eHRVVAUAAz/Zemp1eAsAAQTo" +
                "AwAABOgDAABQSwECHgMKAAkAAAB2gQtdCDQyzCAAAAAUAAAACQAYAAAAAAABAAAApIF1AAAAZGlyL2Iu" +
                "dHh0VVQFAAM/2XpqdXgLAAEE6AMAAAToAwAAUEsFBgAAAAACAAIAngAAAOgAAAAAAA=="

        // 7z a -psecret -mhe=off enc.7z hello.txt dir
        const val ENC_7Z =
            "N3q8ryccAAQS727+vQAAAAAAAAAiAAAAAAAAAOI8kMN4+u84fw410L6iyicsLCaTwnEo1U89rTxkXlRgwcYt" +
                "G/EkiQaAT1uKBm8p37mlFwIAAIEzB64P0QDUPKCKabDjxAD69FhUvhrrMUrivVX30ES8ysawRvYf9Tqr" +
                "KBSo5KMrIUPNjdLRyQJul7Fte3A5IG0GWDk1NotIQO3Im8kN5yawjrwGHXBg0NVF1A+V+hw+ZU/axBAY" +
                "tDB9FwcedMNBX2LUuysh+OKi9Yx+p89soS0HdtjiLvllFKkGAAAXBjABCYCNAAcLAQABIwMBAQVdABAA" +
                "AAyAsgoBt/t1ewAA"

        // 7z a -psecret -mhe=on hdr.7z hello.txt dir
        const val HDR_7Z =
            "N3q8ryccAAT+/3tZwAAAAAAAAAA+AAAAAAAAANWZwG9tUwM2JFHRqsWgqbLDcuwNMzFPnvMlxN/kwoUveRZU" +
                "TviCMIEWgfqSEdpgISUkTi28+s6IvR4Fjz6nmXG8Aiqa3VHiRsaLzmfIUaRpM0mOp5uo865W6oN/7Zog" +
                "lDAotG6kxiTQQLPRg5+Mi1zwlFLj1KlXiGcc8TM+qUYOcFFRg3rKXvkL7grkJ3Gea3Dw8zssWOySkgpu" +
                "3DHYlD3CyIyjAkTdpEaARy5bFo3Vw0bsSNUNnVnNowIXUKFY2/rDmwYXBjABCYCQAAcLAQACJAbxBwES" +
                "Uw+T6Tij0mHhXoBVvJ3KlyvLIwMBAQVdABAAAAEADICNgLIKAay0NqcAAA=="
    }
}
