package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedImageTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun gifDecodesAsAnimationRatherThanFirstFrame() {
        val local = File(app.cacheDir, "animated.gif")
        javaClass.getResourceAsStream("/images/animated.gif")!!.use { input ->
            local.outputStream().use { input.copyTo(it) }
        }
        val image = requireNotNull(decodeAnimation(app, local))
        assertTrue(image.drawable is AnimatedImageDrawable)
        assertEquals(8, image.drawable.intrinsicWidth)
        assertEquals(4, image.drawable.intrinsicHeight)
        assertEquals(1f, image.actual, 0f)
    }

    @Test @Config(sdk = [24, 27]) fun olderAndroidKeepsStaticDecodePath() {
        assertFalse(animationCandidate(XFile("file", "/test.gif", isDir = false)))
        assertFalse(animationCandidate(XFile("file", "/test.webp", isDir = false)))
    }

    @Test fun rotationPreservesPixelsAndForwardsFrameInvalidation() {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.setPixel(0, 0, Color.RED)
        val source = BitmapDrawable(app.resources, bitmap)
        val rotated = RotatedImageDrawable(source)
        assertEquals(4, rotated.intrinsicWidth)
        assertEquals(8, rotated.intrinsicHeight)
        rotated.setBounds(0, 0, 4, 8)
        val target = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
        rotated.draw(Canvas(target))
        assertEquals(Color.RED, target.getPixel(3, 0))
        assertEquals(Color.TRANSPARENT, target.getPixel(0, 7))
        var invalidated = false
        rotated.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { invalidated = true }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        source.invalidateSelf()
        assertTrue(invalidated)
    }

    @Test fun animatedZoomNeverRequestsStaticRegionsAndBitmapRestoresThem() {
        app.setTheme(R.style.Theme_Twig)
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val image = ZoomableImageView(controller.get())
        controller.get().setContentView(image)
        image.layout(0, 0, 400, 200)
        image.initialMode = ZoomableImageView.MODE_ACTUAL
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        image.setAnimatedImage(BitmapDrawable(app.resources, bitmap), 4f)
        // The animation's actual-size viewport still uses original-pixel dimensions.
        assertEquals(.5f, requireNotNull(image.viewport())[2], .001f)
        var requested = false
        image.onNeedHiRes = { _, _ -> requested = true }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertFalse(requested)
        image.setImage(bitmap, 4f)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertTrue(requested)
        controller.pause().stop().destroy()
    }
}
