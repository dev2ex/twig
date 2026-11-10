package com.twig.app.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import com.twig.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackageFileIconTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun awaitIcons() {
        val executor = FileIcons::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(FileIcons) as ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `APK replacement reloads its icon when either size or modification time changes`() {
        val path = temporaryFolder.newFile("download.apk").absolutePath
        val view = ImageView(app)
        val pm = shadowOf(app.packageManager)
        val pkg = "com.twig.icon.replacement"
        pm.setPackageArchiveInfo(path, PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; icon = 1 }
        })
        val versions = listOf(
            XFile("file", path, isDir = false, size = 10, lastModified = 100),
            XFile("file", path, isDir = false, size = 20, lastModified = 100),
            XFile("file", path, isDir = false, size = 20, lastModified = 200),
        )
        for ((index, file) in versions.withIndex()) {
            val icon = ColorDrawable(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
            pm.addDrawableResolution(pkg, 1, icon)
            FileIcons.bind(view, file)
            awaitIcons()
            assertSame("Replacement $index must load its own APK icon", icon, view.drawable)
        }
    }

    private fun writeBundle(file: File, color: Int) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("icon.png"))
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
            bitmap.recycle()
            zip.closeEntry()
        }
    }

    @Test fun `bundle replacement reloads its icon and rejects the previous queued callback`() {
        val bundle = temporaryFolder.newFile("download.xapk")
        val view = ImageView(app)
        val first = XFile("file", bundle.absolutePath, isDir = false, size = 10, lastModified = 100)
        val second = first.copy(lastModified = 200)
        writeBundle(bundle, Color.RED)
        FileIcons.bind(view, first)
        // Finish decoding without delivering the old callback to the main thread.
        val executor = FileIcons::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(FileIcons) as ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)

        writeBundle(bundle, Color.BLUE)
        val release = CountDownLatch(1)
        executor.submit { check(release.await(5, TimeUnit.SECONDS)) }
        try {
            FileIcons.bind(view, second)
            // Drain the old callback while the new icon task is held back.
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(R.drawable.ic_file_apk_bundle, shadowOf(view.drawable).createdFromResId)
        } finally {
            release.countDown()
        }
        awaitIcons()
        assertEquals(Color.BLUE, (view.drawable as BitmapDrawable).bitmap.getPixel(0, 0))

        // Binding unchanged metadata should reuse the existing icon.
        writeBundle(bundle, Color.GREEN)
        FileIcons.bind(view, second)
        awaitIcons()
        assertEquals(Color.BLUE, (view.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
    }
}
