package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.app.EpisodeSkip
import com.twig.app.R
import com.twig.app.databinding.ActivityMediaPlayerBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpisodeSeekBarTest {
    private val orange = Color.rgb(255, 183, 77)
    private fun bar(): EpisodeSeekBar {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        return ActivityMediaPlayerBinding.inflate(LayoutInflater.from(ContextThemeWrapper(ctx, R.style.Theme_Twig))).seek
            .apply { max = 1000; progress = 500 }
    }
    private fun draw(bar: EpisodeSeekBar): Bitmap {
        bar.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(36, View.MeasureSpec.EXACTLY))
        bar.layout(0, 0, 300, 36)
        return Bitmap.createBitmap(300, 36, Bitmap.Config.ARGB_8888).also { bar.draw(Canvas(it)) }
    }

    @Test fun `marked opening and ending cover both track endpoints and clear completely`() {
        val bar = bar()
        bar.marks = EpisodeSkip(200, 200)
        val bitmap = draw(bar)
        assertEquals(orange, bitmap.getPixel(bar.paddingLeft + 1, 18))
        assertEquals(orange, bitmap.getPixel(300 - bar.paddingRight - 2, 18))
        assertNotEquals(orange, bitmap.getPixel(100, 18))
        bar.marks = EpisodeSkip()
        val cleared = draw(bar)
        assertNotEquals(orange, cleared.getPixel(bar.paddingLeft + 1, 18))
        assertNotEquals(orange, cleared.getPixel(300 - bar.paddingRight - 2, 18))
    }

    @Test fun `opening marker follows right to left track direction`() {
        val bar = bar()
        bar.layoutDirection = View.LAYOUT_DIRECTION_RTL
        bar.marks = EpisodeSkip(headMs = 200)
        val bitmap = draw(bar)
        assertEquals(orange, bitmap.getPixel(300 - bar.paddingRight - 2, 18))
        assertNotEquals(orange, bitmap.getPixel(bar.paddingLeft + 1, 18))
    }
}
