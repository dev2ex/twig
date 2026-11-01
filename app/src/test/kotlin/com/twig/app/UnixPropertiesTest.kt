package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.UnixMetadata
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class UnixPropertiesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `initial card uses listing mode and worker loads numeric ownership times and context`() {
        var stats = 0
        var contexts = 0
        val metadata = UnixMetadata("104755".toInt(8), 10001, 1023, 3,
            1600000000000L, 1600000100000L, 1600000200000L)
        val fs = LocalFileSystem(readUnix = { stats++; metadata },
            readContext = { contexts++; "u:object_r:app_data_file:s0:c1,c2" })
        val previous = FsRegistry.all().firstOrNull { it.scheme == "file" }
        FsRegistry.register(fs)
        try {
            val file = XFile("file", "/file.bin", false, canRead = false, unixMode = "100644".toInt(8))
            fun rows(details: FileInfo.Details) = details.sections.single { it.title == app.getString(R.string.info_section_unix) }.rows.toMap()
            val initialDetails = FileInfo.initial(app, file)
            assertEquals(listOf(app.getString(R.string.info_section_basic), app.getString(R.string.info_section_unix)),
                initialDetails.sections.map { it.title })
            assertTrue(initialDetails.sections[1].loading)
            assertFalse(initialDetails.sections.first().rows.any { it.first == app.getString(R.string.info_permissions) })
            val initial = rows(initialDetails)
            assertEquals("rw-r--r-- (0644)", initial[app.getString(R.string.info_permissions)])
            assertEquals(0, stats)
            assertEquals(0, contexts)
            val mediaInitial = FileInfo.initial(app, file.copy(path = "/video.mp4", canRead = true))
            assertEquals(listOf(app.getString(R.string.info_section_basic), app.getString(R.string.info_section_media),
                app.getString(R.string.info_section_unix)), mediaInitial.sections.map { it.title })
            assertEquals(0, stats)
            val details = FileInfo.load(app, file)
            val loaded = rows(details)
            assertFalse(details.sections[1].loading)
            val basic = details.sections.first().rows.toMap()
            assertFalse(basic.containsKey(app.getString(R.string.info_permissions)))
            assertFalse(basic.containsKey(app.getString(R.string.info_uid)))
            assertEquals("rwsr-xr-x (4755)", loaded[app.getString(R.string.info_permissions)])
            assertEquals("10001", loaded[app.getString(R.string.info_uid)])
            assertEquals("1023", loaded[app.getString(R.string.info_gid)])
            assertEquals(Format.time(metadata.modified, app), basic[app.getString(R.string.info_modified)])
            assertEquals(Format.time(metadata.accessed, app), loaded[app.getString(R.string.info_accessed)])
            assertEquals(Format.time(metadata.changed, app), loaded[app.getString(R.string.info_changed)])
            assertEquals("u:object_r:app_data_file:s0:c1,c2", loaded[app.getString(R.string.info_selinux_context)])
            assertEquals(1, stats)
            assertEquals(1, contexts)
        } finally {
            FsRegistry.unregister("file")
            previous?.let(FsRegistry::register)
        }
    }

    @Test fun `unsupported sources have no Unix tab`() {
        val file = XFile("no-unix-source", "/remote.bin", false, canRead = false)
        for (details in listOf(FileInfo.initial(app, file), FileInfo.load(app, file))) {
            assertEquals(listOf(app.getString(R.string.info_section_basic)), details.sections.map { it.title })
            assertFalse(details.sections.first().rows.any { it.first == app.getString(R.string.info_permissions) })
        }
    }

    @Test fun `failed metadata reads never become mode zero or root ownership`() {
        val previous = FsRegistry.all().firstOrNull { it.scheme == "file" }
        val elevation = LocalFileSystem.elevation
        LocalFileSystem.elevation = null
        FsRegistry.register(LocalFileSystem(readUnix = { null }, readContext = { null }))
        try {
            val file = XFile("file", "/unreadable.bin", false, canRead = false)
            val rows = FileInfo.load(app, file).sections.single { it.title == app.getString(R.string.info_section_unix) }.rows.toMap()
            assertEquals(app.getString(R.string.info_unavailable), rows[app.getString(R.string.info_permissions)])
            assertFalse(rows.containsKey(app.getString(R.string.info_uid)))
            assertFalse(rows.containsKey(app.getString(R.string.info_gid)))
            assertFalse(rows.containsKey(app.getString(R.string.info_accessed)))
            assertFalse(rows.containsKey(app.getString(R.string.info_changed)))
            assertFalse(rows.containsKey(app.getString(R.string.info_selinux_context)))
        } finally {
            LocalFileSystem.elevation = elevation
            FsRegistry.unregister("file")
            previous?.let(FsRegistry::register)
        }
    }
}
