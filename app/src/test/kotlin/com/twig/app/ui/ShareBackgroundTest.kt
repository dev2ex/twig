package com.twig.app.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.share.ShareConfig
import com.twig.app.share.ShareScope
import com.twig.app.share.ShareStore
import com.twig.app.share.WebShare
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareBackgroundTest {
    @Test fun `background shortcut starts stops and restarts sharing with saved options and toasts`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val host = Robolectric.buildActivity(TrashUiTest.Host::class.java).setup()
        val directory = File(app.cacheDir, "share-background").apply { mkdirs() }
        val port = ServerSocket(0).use { it.localPort }
        FsRegistry.register(LocalFileSystem())
        ShareStore.save(app, ShareConfig(ShareScope.AllSources, port = port, readOnly = false, deviceName = "My Twig"))
        val previous = ShadowDialog.getLatestDialog()
        ShadowToast.reset()
        try {
            ShareDialogs.toggleBackground(host.get(), XFile("file", directory.path, true))
            val deadline = System.nanoTime() + 5_000_000_000
            while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val session = requireNotNull(WebShare.session)
            assertEquals(directory.path, (session.cfg.scope as ShareScope.Dir).path)
            assertEquals(port, session.cfg.port)
            assertFalse(session.cfg.readOnly)
            assertEquals("My Twig", session.cfg.deviceName)
            assertEquals(app.getString(R.string.share_notif_title, session.primaryUrl()), ShadowToast.getTextOfLatestToast())
            assertSame(previous, ShadowDialog.getLatestDialog())
            val saved = ShareStore.load(app)
            ShareDialogs.toggleBackground(host.get(), null)
            assertNull(WebShare.session)
            assertEquals(app.getString(R.string.share_stopped), ShadowToast.getTextOfLatestToast())
            assertEquals(saved, ShareStore.load(app))
            assertSame(previous, ShadowDialog.getLatestDialog())
            // The stop path must close the listener, not just change the visible state.
            ServerSocket(port).use { assertTrue(it.isBound) }
            ShadowToast.reset()
            ShareDialogs.toggleBackground(host.get(), null)
            val restartDeadline = System.nanoTime() + 5_000_000_000
            while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < restartDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val restarted = requireNotNull(WebShare.session)
            assertNotSame(session, restarted)
            assertEquals(saved, restarted.cfg)
            assertEquals(app.getString(R.string.share_notif_title, restarted.primaryUrl()), ShadowToast.getTextOfLatestToast())
            assertSame(previous, ShadowDialog.getLatestDialog())
        } finally {
            WebShare.stop(app)
            app.getSharedPreferences(ShareStore.FILE, 0).edit().clear().commit()
            directory.deleteRecursively()
            host.pause().stop().destroy()
        }
    }
}
