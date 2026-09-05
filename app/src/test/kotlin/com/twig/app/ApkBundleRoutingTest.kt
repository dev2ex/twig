package com.twig.app

import com.twig.fs.archive.Archives
import com.twig.fs.archive.ZipFileSystem
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A multi-apk bundle (.xapk / .apks / .apkm) has to be routed three ways at once, and the
 * three rules live in three different modules — easy to "tidy up" one of them into
 * breaking the others:
 *
 * - it is **installable** ([OpenFiles.isInstallable]), so a tap goes to
 *   `ApkBundleInstall` instead of the system installer, which only takes one apk;
 * - it is still a **mountable zip** ([Archives.schemeFor]), so "Open as archive" in
 *   the long-press menu has something to mount — while `PaneViewModel.expandableArchive`
 *   keeps it collapsed until the user asks;
 * - it is **not** one of [OpenFiles.isArchive]'s generic archives, or the file list
 *   would draw it with the folder-ish archive icon instead of `ic_file_apk_bundle`.
 */
class ApkBundleRoutingTest {

    private fun file(name: String) = XFile("file", "/sdcard/$name", isDir = false)

    /** All three bundle formats, and plain apk, take the install path. */
    @Test
    fun `every bundle format is installable, like apk`() {
        for (name in listOf("Twig 1.6.0.xapk", "Twig 1.6.0.apks", "Twig 1.6.0.apkm")) {
            assertTrue(name, OpenFiles.isApkBundle(file(name)))
            assertFalse(name, OpenFiles.isApk(file(name)))
            assertTrue(name, OpenFiles.isInstallable(file(name)))
        }
        assertTrue(OpenFiles.isInstallable(file("Twig 1.6.0.apk")))
        assertFalse(OpenFiles.isApkBundle(file("Twig 1.6.0.apk")))
        assertFalse(OpenFiles.isInstallable(file("photos.zip")))
    }

    @Test
    fun `bundles mount as zips so Open as archive works`() {
        for (name in listOf("a.xapk", "a.apks", "a.apkm", "a.apk")) {
            assertEquals(name, ZipFileSystem.SCHEME, Archives.schemeFor(file(name)))
        }
    }

    @Test
    fun `bundles do not count as generic archives in the UI`() {
        for (name in listOf("a.xapk", "a.apks", "a.apkm", "a.apk")) {
            assertFalse(name, OpenFiles.isArchive(file(name)))
        }
    }

    /** A directory named `x.xapk` is still a directory — every predicate checks isDir first. */
    @Test
    fun `a directory is never installable`() {
        val dir = XFile("file", "/sdcard/weird.xapk", isDir = true)
        assertFalse(OpenFiles.isApkBundle(dir))
        assertFalse(OpenFiles.isInstallable(dir))
        assertEquals(null, Archives.schemeFor(dir))
    }
}
