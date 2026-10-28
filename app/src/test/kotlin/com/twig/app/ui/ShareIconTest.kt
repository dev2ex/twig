package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.share.ShareScope
import com.twig.app.share.ShareConfig
import com.twig.app.share.ShareStore
import com.twig.app.share.WebShare
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ShareIconTest {
    @Test fun `sharing updates every strip after another main activity pauses`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ReflectionHelpers.setStaticField(androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java,
            "_instance", null)
        Prefs.setRememberLocation(app, false)
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, "com.twig.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        val port = ServerSocket(0).use { it.localPort }
        ShareStore.save(app, ShareConfig(ShareScope.AllSources, port = port))
        try {
            first.pause()
            shadowOf(Looper.getMainLooper()).idle()
            fun assertIcons(resource: Int) {
                for (strip in listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)) {
                    val icon = second.get().findViewById<android.view.View>(strip)
                        .findViewById<ImageView>(R.id.as_share_icon)
                    assertEquals(resource, shadowOf(icon.drawable).createdFromResId)
                }
            }
            assertIcons(R.drawable.ic_share_wifi)
            ShadowToast.reset()
            ShareDialogs.toggleBackground(second.get(), null)
            val deadline = System.nanoTime() + 5_000_000_000
            while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertIcons(R.drawable.ic_share_wifi_on)
            ShareDialogs.toggleBackground(second.get(), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertIcons(R.drawable.ic_share_wifi)
        } finally {
            WebShare.stop(app)
            app.getSharedPreferences(ShareStore.FILE, 0).edit().clear().commit()
            second.pause().stop().destroy()
            first.stop().destroy()
        }
    }
}
