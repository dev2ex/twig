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
 * Reading RAR archives. **The sample packages were really produced by the `rar` CLI
 * tool** (contents are the base64 below), covering the plain/encrypted forms of both
 * RAR5 and RAR4.
 *
 * ★ This test backfills the junrar 7.5.5 → 8.1.0 upgrade: RAR5 support only arrived in
 * junrar 8.0.0, and before that RAR5 packages **could not be opened at all** — the
 * symptom was a password dialog that rejected every input
 * (`UnsupportedRarV5Exception` got swallowed by the `getOrElse { true }` in
 * [RarFileSystem.needsPassword] into "this is a header-encrypted package") — so the
 * tests below pin both content and `needsPassword`, so downgrading back to 7.x turns
 * both red.
 *
 * The sample content is uniformly: `src/hello.txt` = "hello encrypted world\n",
 * `src/dir/b.txt` = "nested content here\n", password `secret`.
 * RAR4 entry names are separated with a **backslash** (`src\hello.txt`), which happens
 * to cover the substitution in [RarFileSystem].
 */
class RarFileSystemTest {

    private lateinit var tmp: File
    private val rfs = RarFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(rfs)
        tmp = File.createTempFile("twigrar", "").let { it.delete(); it.mkdirs(); it }
    }

    private fun sample(name: String, b64: String): XFile {
        val f = File(tmp, name)
        f.writeBytes(Base64.getDecoder().decode(b64))
        return XFile(scheme = "file", path = f.absolutePath, isDir = false, size = f.length())
    }

    private fun child(dir: XFile, name: String): XFile = rfs.list(dir).first { it.name == name }
    private fun readText(f: XFile): String = rfs.openInput(f).use { it.readBytes().toString(Charsets.UTF_8) }

    /** root → src → {dir, hello.txt}; asserted level by level, incidentally proving the directory tree is built from entry names. */
    private fun assertContent(root: XFile) {
        val src = child(root, "src")
        assertTrue(src.isDir)
        assertEquals(listOf("dir", "hello.txt"), rfs.list(src).map { it.name })
        assertEquals("hello encrypted world\n", readText(child(src, "hello.txt")))
        assertEquals("nested content here\n", readText(child(child(src, "dir"), "b.txt")))
    }

    // ---- RAR5 ----

    @Test
    fun rar5ListingAndContentBothReadable() {
        val x = sample("plain5.rar", PLAIN5)
        assertFalse(rfs.needsPassword(x.path))
        assertContent(rfs.rootOf(x))
    }

    @Test
    fun rar5EncryptedNeedsPasswordForContentButNotForListing() {
        val x = sample("enc5.rar", ENC5)
        assertTrue(rfs.needsPassword(x.path))
        val root = rfs.rootOf(x)
        // File names are not encrypted, so listing works without a password
        assertEquals(listOf("src"), rfs.list(root).map { it.name })

        assertFalse(rfs.checkPassword(x.path, "wrong"))
        assertTrue(rfs.checkPassword(x.path, "secret"))

        rfs.setPassword(x.path, "secret")
        assertContent(root)
    }

    @Test
    fun rar5EncryptedThrowsRecognizableExceptionWithoutPassword() {
        val x = sample("enc5.rar", ENC5)
        val root = rfs.rootOf(x)
        val f = child(child(root, "src"), "hello.txt")
        try {
            rfs.openInput(f).use { it.readBytes() }
            throw AssertionError("should have thrown ArchivePasswordException")
        } catch (e: ArchivePasswordException) {
            assertEquals(x.path, e.archivePath)
        }
    }

    @Test
    fun rar5HeaderEncryptedCannotEvenListWithoutPassword() {
        val x = sample("head5.rar", HEAD5)
        assertTrue(rfs.needsPassword(x.path))
        val root = rfs.rootOf(x)
        try {
            rfs.list(root)
            throw AssertionError("should have thrown ArchivePasswordException")
        } catch (e: ArchivePasswordException) {
            assertEquals(x.path, e.archivePath)
        }

        assertFalse(rfs.checkPassword(x.path, "wrong"))
        assertTrue(rfs.checkPassword(x.path, "secret"))

        rfs.setPassword(x.path, "secret")
        assertContent(root)
    }

    // ---- RAR4 (upgrade regression: 8.x must not break the old format) ----

    @Test
    fun rar4ListingAndContentBothReadable() {
        val x = sample("plain4.rar", PLAIN4)
        assertFalse(rfs.needsPassword(x.path))
        assertContent(rfs.rootOf(x))
    }

    @Test
    fun rar4EncryptedNeedsPasswordForContent() {
        val x = sample("enc4.rar", ENC4)
        assertTrue(rfs.needsPassword(x.path))
        assertFalse(rfs.checkPassword(x.path, "wrong"))
        assertTrue(rfs.checkPassword(x.path, "secret"))

        rfs.setPassword(x.path, "secret")
        assertContent(rfs.rootOf(x))
    }

    companion object {
        // rar a -r -ma5 plain5.rar src/hello.txt src/dir(RAR 7.23)
        const val PLAIN5 =
            "UmFyIRoHAQAzkrXlCgEFBgAFAQGAgAAh2umKKwIDC5YABJYApIMC9OHhaoAAAQ1zcmMvaGVsbG8udHh0CgMTTy6N" +
                "amLf+ThoZWxsbyBlbmNyeXB0ZWQgd29ybGQKcqN+gSsCAwuUAASUAKSDAgg0MsyAAAENc3JjL2Rpci9iLnR4dAoD" +
                "E08ujWpi3/k4bmVzdGVkIGNvbnRlbnQgaGVyZQqBYpSqHwIDCwABAO2DAYAAAQdzcmMvZGlyCgMTTy6NamLf+Tgd" +
                "d1ZRAwUEAA=="

        // rar a -r -ma5 -psecret enc5.rar … (content encrypted, listing not encrypted)
        const val ENC5 =
            "UmFyIRoHAQAzkrXlCgEFBgAFAQGAgAAZ+a3XXAIDPKAABJYApIMClH5crYADAQ1zcmMvaGVsbG8udHh0MAEAAw8A" +
                "lVOcGRFNmbSf7ZEtAlNmnkreKCKCeR35p4mcxIDdFM0RuAQwx/tKtMwFdQoDE08ujWpi3/k4qtZ5myvMabYiV4wy" +
                "9pU5eelCzNloRFC0FVyAe6Bt84j/BFCRXAIDPKAABJQApIMCT0zCe4ADAQ1zcmMvZGlyL2IudHh0MAEAAw8AlVOc" +
                "GRFNmbSf7ZEtAlNmJwjYDSs/A18CC72U/kv5sM0RuAQwx/tKtMwFdQoDE08ujWpi3/k4G0Yb0cRBsVANqrsBHy9b" +
                "2eA7opZVtabzf9u/19ic58KBYpSqHwIDCwABAO2DAYAAAQdzcmMvZGlyCgMTTy6NamLf+Tgdd1ZRAwUEAA=="

        // rar a -r -ma5 -hpsecret head5.rar … (listing encrypted too)
        const val HEAD5 =
            "UmFyIRoHAQC53JqNIQQAAAEPVvoWsLPSUEng8aM3jfNyI681E3T5AiTnSmzaY8sJ3o0Q53s7nDbg8lsoSGDBIWLg" +
                "+WvL/1PeQSfAmjz0SGbFnDIyc4LlfDpPxrVUpIpEME2UkCzQL3eJCXUR3AMFP+cv4YufltIF91sBuSF8LtcMwax+" +
                "FHei4RHcyJAI8gwJboG1TkpMWNocbE43mburI8hv6NWofZEpvLNKVOgZ65lOZ0sjlpr+Srb+4pf9goTlif/1EVZU" +
                "ZaNJnEGcYRgYgUJfcyJVmboO8Y0J1JnYGms18syMQkN7i1pPEESRNdY0Pw+y7PjpQRRp6Y+za5z8CfkFcfmNVStP" +
                "s0HkNGLO7rLC+uUxPapSLVcNxsTfitdk8OADTL+9IxtBbzrgF6h0UzUFaccqoDbU9SU29HJLA4EuOW7y0gMlRbZt" +
                "bRjbRn2l15VRpMOW1kpXaIpABtKp12PX0WNcHsB173012UxGwutu0mMsc48yYoYxzEkuITVNQTs6gDXDfUCMG9Ri" +
                "RygyJzuLZknEqf2z5Z33CKOMLVvoPlaVssTaz/tqO95vgXsLoA1WBW1A0xJbBHPaTTVA5Dg+wIJA3poWEvCiQC14" +
                "3TdCd8xVATa5xoqcqZ6Mm53FbT2hM0wg2YMQe/Iulk8="

        // rar a -r -ma4 plain4.rar … (RAR 6.24; 7.x can no longer write RAR4)
        const val PLAIN4 =
            "UmFyIRoHAM+QcwAADQAAAAAAAAAcXHQgkDIAFgAAABYAAAAD9OHhau1uGV0dMA0ApIEAAHNyY1xoZWxsby50eHQA" +
                "8NXbkWhlbGxvIGVuY3J5cHRlZCB3b3JsZAqSWXQgkDIAFAAAABQAAAADCDQyzO1uGV0dMA0ApIEAAHNyY1xkaXJc" +
                "Yi50eHQA8NXbkW5lc3RlZCBjb250ZW50IGhlcmUKvZJ04JAsAAAAAAAAAAAAAwAAAADtbhldFDAHAO1BAABzcmNc" +
                "ZGlyAPDV25HEPXsAQAcA"

        // rar a -r -ma4 -psecret enc4.rar …(RAR 6.24)
        const val ENC4 =
            "UmFyIRoHAM+QcwAADQAAAAAAAACoO3QklDoAIAAAABYAAAAD9OHhau1uGV0dMw0ApIEAAHNyY1xoZWxsby50eHSb" +
                "UnXxvh67FwDw1duR4qEur1r+En2HGQqc+jDBWut5zWexXN6gxpUbPGIKVVWh7HQklDoAIAAAABQAAAADCDQyzO1u" +
                "GV0dMw0ApIEAAHNyY1xkaXJcYi50eHSbUnXxvh67FwDw1duRc0FQuiZyERZuc1LWNVjk49AbVMXIbTE//v77cGib" +
                "tSRJf3TgkCwAAAAAAAAAAAADAAAAAO1uGV0dMAcA7UEAAHNyY1xkaXIA8NXbkcQ9ewBABwA="

    }
}
