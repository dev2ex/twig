package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Looper
import android.view.ViewGroup
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.OpenFiles
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SvgImageTest {
    private fun decode(attributes: String = "viewBox='0 0 100 50'", body: String = "<rect width='50' height='50' fill='red'/>") =
        ByteArrayInputStream("<svg xmlns='http://www.w3.org/2000/svg' $attributes>$body</svg>".toByteArray()).use {
            decodeSvg(it, "", 400, 160)
        }

    @Test fun viewBoxOnlyKeepsAspectRatioAndTransparency() {
        val d = decode()
        assertEquals(100, d.origW)
        assertEquals(50, d.origH)
        assertEquals(400, d.bmp.width)
        assertEquals(200, d.bmp.height)
        assertEquals(Color.RED, d.bmp.getPixel(80, 80))
        assertEquals(Color.TRANSPARENT, d.bmp.getPixel(320, 80))
    }

    @Test fun explicitSizeAndPercentSizeUseTheirViewport() {
        val fixed = decode("width='80' height='40'")
        assertEquals(80, fixed.origW)
        assertEquals(40, fixed.origH)
        assertEquals(Color.RED, fixed.bmp.getPixel(200, 100))
        assertEquals(Color.TRANSPARENT, fixed.bmp.getPixel(300, 100))
        val percent = decode("width='100%' height='100%' viewBox='0 0 100 50'")
        assertEquals(400, percent.bmp.width)
        assertEquals(200, percent.bmp.height)
        assertEquals(Color.RED, percent.bmp.getPixel(80, 80))
    }

    @Test fun highZoomRendersVectorDetailBeyondTheBaseBitmap() {
        // The original is only 100 units wide; the detail occupies less than one base pixel.
        val d = decode(body = "<rect x='50' y='10' width='.1' height='10' fill='blue'/>")
        assertTrue(d.actual < 1f)
        val region = requireNotNull(decodeRegion(d, 0, RectF(199f, 40f, 202f, 80f), 16f, 400, 400))
        assertEquals(48, region.width)
        assertEquals(Color.BLUE, region.getPixel(18, 100))
        assertEquals(Color.TRANSPARENT, region.getPixel(2, 100))
    }

    @Test fun regionUsesRotatedDisplayCoordinates() {
        val d = decode()
        for (rot in listOf(0, 90, 180, 270)) {
            val rect = if (rot % 180 == 0) RectF(0f, 0f, 400f, 200f) else RectF(0f, 0f, 200f, 400f)
            val region = requireNotNull(decodeRegion(d, rot, rect, 2f, 800, 800))
            val red = when (rot) {
                0 -> 100 to 100
                90 -> 100 to 100
                180 -> 600 to 100
                else -> 100 to 600
            }
            assertEquals("rotation $rot", Color.RED, region.getPixel(red.first, red.second))
        }
    }

    @Test fun zoomBitmapStaysWithinMemoryBudget() {
        val d = decode()
        val region = requireNotNull(decodeRegion(d, 0, RectF(0f, 0f, 400f, 200f), 100f, 100, 100))
        assertTrue(region.width.toLong() * region.height <= 20_000)
    }

    @Test fun highResolutionRegionDoesNotDoubleCompositeTransparency() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.setTheme(R.style.Theme_Twig)
        val d = decode(body = "<rect width='100' height='50' fill='red' fill-opacity='.5'/>")
        val image = ZoomableImageView(app)
        image.layout(0, 0, 400, 200)
        image.setImage(d.bmp, d.actual, vector = true)
        fun snapshot() = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also {
            image.draw(Canvas(it))
        }
        val before = snapshot()
        image.setHiRes(requireNotNull(decodeRegion(d, 0, RectF(0f, 0f, 200f, 200f), 2f, 400, 200)),
            RectF(0f, 0f, 200f, 200f))
        val after = snapshot()
        assertEquals(before.getPixel(100, 100), after.getPixel(100, 100))
        assertEquals(before.getPixel(300, 100), after.getPixel(300, 100))
    }

    @Test fun smallVectorCanZoomAndRequestDetailThenRasterRestoresItsLimit() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.setTheme(R.style.Theme_Twig)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val image = ZoomableImageView(controller.get())
        controller.get().setContentView(image, ViewGroup.LayoutParams(400, 200))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        image.layout(0, 0, 400, 200)
        val d = decode()
        image.setImage(d.bmp, d.actual, vector = true)
        var requests = 0
        image.onNeedHiRes = { _, _ -> requests++ }
        image.applyViewport(floatArrayOf(0f, 0f, .1f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        val matrix = FloatArray(9).also { image.imageMatrix.getValues(it) }
        assertEquals(10f, matrix[Matrix.MSCALE_X], .001f)
        assertTrue(requests > 0)
        image.setImage(d.bmp)
        image.applyViewport(floatArrayOf(0f, 0f, .1f))
        image.imageMatrix.getValues(matrix)
        assertEquals(2f, matrix[Matrix.MSCALE_X], .001f)
        controller.pause().stop().destroy()
    }

    @Test fun localSvgRoutesToImageDecoder() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val local = File(app.cacheDir, "viewer-test.svg").apply {
            writeText("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50'><rect width='100' height='50' fill='red'/></svg>")
        }
        try {
            val file = XFile("file", local.absolutePath, isDir = false)
            assertTrue(OpenFiles.isImage(file))
            val d = requireNotNull(decodeImage(app, file))
            assertNotNull(d.vector)
            assertEquals(Color.RED, d.bmp.getPixel(0, 0))
        } finally { local.delete() }
    }
}
