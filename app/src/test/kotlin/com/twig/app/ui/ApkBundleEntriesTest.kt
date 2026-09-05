package com.twig.app.ui

import com.twig.core.XFile
import com.twig.fs.archive.ZipFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Which entries of a bundle get written into the install session, and — the part that
 * actually shipped broken — whether they can be read at all.
 *
 * ★ The bundles APKPure hands out store their apks **STORED with a data descriptor**:
 * the local header's crc and both size fields are zero, the real values trail the data.
 * A single-pass reader cannot find the end of an uncompressed entry that way, and the
 * JDK's `ZipInputStream` refuses the combination outright ("only DEFLATED entries can
 * have EXT descriptor") — the first version of the installer used it and failed on every
 * such file. The zips below are written in exactly that shape, so reading them proves
 * the directory-based path ([ZipFileSystem]) is still in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkBundleEntriesTest {

    @Test
    fun `apks are taken wherever the format puts them`() {
        assertTrue(ApkBundleInstall.installable("com.foo.apk")) // xapk: root
        assertTrue(ApkBundleInstall.installable("split_config.arm64_v8a.apk"))
        assertTrue(ApkBundleInstall.installable("splits/base-master.apk")) // apks: splits/
        assertTrue(ApkBundleInstall.installable("base.apk")) // apkm: root
        assertTrue(ApkBundleInstall.installable("universal.apk")) // bundletool --mode=universal
        assertTrue(ApkBundleInstall.installable("BASE.APK"))
    }

    @Test
    fun `metadata and payloads are skipped`() {
        assertFalse(ApkBundleInstall.installable("manifest.json"))
        assertFalse(ApkBundleInstall.installable("icon.png"))
        assertFalse(ApkBundleInstall.installable("info.json"))
        assertFalse(ApkBundleInstall.installable("toc.pb"))
        assertFalse(ApkBundleInstall.installable("Android/obb/com.foo/main.1.com.foo.obb"))
    }

    /** Pre-Android-5 standalone apks: taking them alongside splits/ gives the session two bases. */
    @Test
    fun `bundletool standalones are skipped`() {
        assertFalse(ApkBundleInstall.installable("standalones/standalone-arm64_v8a_hdpi.apk"))
        assertFalse(ApkBundleInstall.installable("nested/standalones/standalone-x86.apk"))
    }

    /** An APKPure-shaped .xapk: apks at the root, next to manifest.json and icon.png. */
    @Test
    fun `an APKPure bundle is listed and read back byte for byte`() {
        val base = ByteArray(9000) { (it % 251).toByte() }
        val split = ByteArray(777) { (it % 97).toByte() }
        val file = descriptorZip(
            "com.foo.apk" to base,
            "icon.png" to ByteArray(64),
            "config.en.apk" to split,
            "manifest.json" to """{"xapk_version":2}""".toByteArray(),
        )
        val zip = ZipFileSystem()
        val found = ApkBundleInstall.apkEntries(zip, zip.rootOf(local(file)))

        assertEquals(listOf("com.foo.apk", "config.en.apk"), found.map { it.name }.sorted())
        // The sizes go straight into session.openWrite, so a wrong one is a broken install.
        assertEquals(base.size.toLong(), found.first { it.name == "com.foo.apk" }.size)
        assertArrayEquals(base, zip.openInput(found.first { it.name == "com.foo.apk" }).readBytes())
        assertArrayEquals(split, zip.openInput(found.first { it.name == "config.en.apk" }).readBytes())
    }

    /** A bundletool .apks: apks one directory down, plus standalones that must not come along. */
    @Test
    fun `an apks bundle is walked into splits and skips standalones`() {
        val file = descriptorZip(
            "toc.pb" to ByteArray(16),
            "splits/base-master.apk" to ByteArray(100),
            "splits/base-en.apk" to ByteArray(50),
            "standalones/standalone-hdpi.apk" to ByteArray(400),
        )
        val zip = ZipFileSystem()
        val found = ApkBundleInstall.apkEntries(zip, zip.rootOf(local(file)))
        assertEquals(listOf("base-en.apk", "base-master.apk"), found.map { it.name }.sorted())
    }

    private fun local(f: File) = XFile(
        "file", f.absolutePath, isDir = false, size = f.length(), lastModified = f.lastModified(),
    )

    /**
     * Writes a zip the way APKPure does: every entry STORED, the general-purpose flag's
     * bit 3 set, the local header's crc/sizes left at zero and the real values written in
     * a trailing data descriptor. The central directory (which is what a correct reader
     * uses) carries the true values.
     */
    private fun descriptorZip(vararg entries: Pair<String, ByteArray>): File {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0
        for ((name, data) in entries) {
            val n = name.toByteArray()
            val crc = CRC32().apply { update(data) }.value
            val lfh = ByteArrayOutputStream()
            le32(lfh, 0x04034b50); le16(lfh, 20); le16(lfh, 0x8) // flag bit 3: data descriptor
            le16(lfh, 0) /* STORED */; le16(lfh, 0); le16(lfh, 33)
            le32(lfh, 0); le32(lfh, 0); le32(lfh, 0) // crc + sizes: only in the descriptor
            le16(lfh, n.size); le16(lfh, 0)
            lfh.write(n)
            out.write(lfh.toByteArray())
            out.write(data)
            val dd = ByteArrayOutputStream()
            le32(dd, 0x08074b50); le32(dd, crc); le32(dd, data.size); le32(dd, data.size)
            out.write(dd.toByteArray())

            le32(central, 0x02014b50); le16(central, 20); le16(central, 20); le16(central, 0x8)
            le16(central, 0); le16(central, 0); le16(central, 33)
            le32(central, crc); le32(central, data.size); le32(central, data.size)
            le16(central, n.size); le16(central, 0); le16(central, 0); le16(central, 0)
            le16(central, 0); le32(central, 0); le32(central, offset)
            central.write(n)
            offset += lfh.size() + data.size + dd.size()
        }
        val cd = central.toByteArray()
        out.write(cd)
        val eocd = ByteArrayOutputStream()
        le32(eocd, 0x06054b50); le16(eocd, 0); le16(eocd, 0)
        le16(eocd, entries.size); le16(eocd, entries.size)
        le32(eocd, cd.size); le32(eocd, offset); le16(eocd, 0)
        out.write(eocd.toByteArray())

        return File.createTempFile("bundle", ".xapk")
            .apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    private fun le16(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xff); o.write((v ushr 8) and 0xff)
    }

    private fun le32(o: ByteArrayOutputStream, v: Long) {
        le16(o, (v and 0xffff).toInt()); le16(o, ((v ushr 16) and 0xffff).toInt())
    }

    private fun le32(o: ByteArrayOutputStream, v: Int) = le32(o, v.toLong() and 0xffffffffL)
}
