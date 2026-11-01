package com.twig.app

import android.app.Application
import android.os.Build
import android.system.Os
import android.system.StructStat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.file.Files
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, shadows = [UnixOsShadow::class])
class UnixFilesTest {
    private lateinit var root: File
    @Before fun setup() {
        root = Files.createTempDirectory("twig-unix-").toFile()
        UnixOsShadow.xattrReads = 0
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun `Android metadata keeps a broken links own mode and never invents missing entries`() {
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), File(root, "missing").toPath())
        val attributes = UnixFiles.stat(link.path)!!
        assertTrue(attributes.isSymbolicLink)
        assertFalse(attributes.isDirectory)
        assertNull(UnixFiles.stat(File(root, "missing").path))
    }

    @Test fun `getxattr is API gated and never follows a symlink for its context`() {
        val file = File(root, "target").apply { writeText("x") }
        val context = UnixFiles.context(file.path)
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals("u:object_r:app_data_file:s0", context)
            assertEquals(1, UnixOsShadow.xattrReads)
        } else {
            assertNull(context) // /system/bin/ls is absent on the host JVM.
            assertEquals(0, UnixOsShadow.xattrReads)
        }
        UnixOsShadow.xattrReads = 0
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        UnixFiles.context(link.path)
        assertEquals("The target's context must never be read as the link's", 0, UnixOsShadow.xattrReads)
    }
}

@Implements(Os::class)
class UnixOsShadow {
    companion object {
        var xattrReads = 0
        @Implementation @JvmStatic fun lstat(path: String): StructStat = TrashOsShadow.lstat(path)
        @Implementation @JvmStatic fun getxattr(path: String, name: String): ByteArray {
            assertEquals("security.selinux", name)
            assertTrue(File(path).exists())
            xattrReads++
            return "u:object_r:app_data_file:s0\u0000".toByteArray()
        }
    }
}
