package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileInfoSymbolicLinkTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `initial properties do not fetch a link target and loaded properties preserve relative target`() {
        var reads = 0
        val previous = FsRegistry.all().firstOrNull { it.scheme == "file" }
        FsRegistry.register(LocalFileSystem(readLink = { reads++; "../target" }))
        try {
            val link = XFile("file", "/link.bin", false, canRead = false, isSymbolicLink = true)
            val initial = FileInfo.initial(app, link).sections.first().rows.toMap()
            assertEquals(0, reads)
            assertEquals(app.getString(R.string.info_symbolic_link), initial[app.getString(R.string.info_type)])
            val loaded = FileInfo.load(app, link).sections.first().rows.toMap()
            assertEquals(1, reads)
            assertEquals("../target", loaded[app.getString(R.string.info_link_target)])
        } finally {
            FsRegistry.unregister("file")
            previous?.let(FsRegistry::register)
        }
    }
}
