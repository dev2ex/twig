package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.ContextCompat
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageBarViewTest {
    @Test fun `bar fills its fraction and strengthens over parent selection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(context)
        val bar = UsageBarView(context).apply {
            isDuplicateParentStateEnabled = true
            fraction = 0.25f
        }
        parent.addView(bar, FrameLayout.LayoutParams(100, 20))
        parent.measure(android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(20, android.view.View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 100, 20)
        fun pixel(x: Int): Int {
            val bitmap = Bitmap.createBitmap(100, 20, Bitmap.Config.ARGB_8888)
            bar.draw(Canvas(bitmap))
            return bitmap.getPixel(x, 10).also { bitmap.recycle() }
        }
        val normal = pixel(10)
        assertNotEquals(0, normal)
        assertEquals(ContextCompat.getColor(context, R.color.selected), normal)
        assertEquals(0, pixel(30))
        parent.isActivated = true
        assertEquals(76, Color.alpha(pixel(10)))
        assertNotEquals(normal, pixel(10))
        assertEquals(0, pixel(30))
        parent.isActivated = false
        assertNotEquals(0, pixel(10))
        bar.fraction = 0f // Recycled rows cannot retain a previous scan's bar.
        assertEquals(0, pixel(10))
    }
}
