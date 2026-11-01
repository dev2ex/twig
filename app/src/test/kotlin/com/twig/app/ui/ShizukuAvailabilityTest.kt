package com.twig.app.ui

import android.app.Application
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Privileged
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuAvailabilityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `live compatible service does not require the official manager`() {
        assertFalse(Privileged.shizukuInstalled(app, running = false))
        assertTrue(Privileged.shizukuInstalled(app, running = true))
    }

    @Test fun `stopped Shevery is recognized as installed`() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.hamondev.shevery" })
        assertTrue(Privileged.shizukuInstalled(app, running = false))
    }

    @Test fun `stopped official manager is recognized as installed`() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = Privileged.SHIZUKU_PKG })
        assertTrue(Privileged.shizukuInstalled(app, running = false))
    }
}
