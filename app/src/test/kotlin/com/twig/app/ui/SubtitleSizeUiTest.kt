package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleSizeUiTest {
    private fun context(language: String, scale: Float): Context {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val configuration = Configuration(app.resources.configuration).apply {
            fontScale = scale
            setLocale(Locale.forLanguageTag(language))
        }
        return ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_Twig)
    }

    private fun measure(view: View, widthDp: Int) {
        val width = (widthDp * view.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    @Test fun subtitleColorKeepsItsLabelOnOneLineAndSizeUsesTheRemainingWidth() {
        for (language in listOf("en", "de", "ru")) for (scale in listOf(1f, 1.5f)) {
            val controls = SubtitleSizeUi.create(context(language, scale)) {}
            measure(controls, 300)
            val color = children(controls).filterIsInstance<Button>().single()
            val slider = children(controls).filterIsInstance<SeekBar>().single()
            assertEquals("$language $scale", 1, color.lineCount)
            val textWidth = color.paint.measureText(color.text.toString())
            assertTrue("$language $scale color label clipped", color.width >= textWidth + color.compoundPaddingLeft + color.compoundPaddingRight)
            assertTrue("$language $scale slider squeezed out", slider.width > 48 * controls.resources.displayMetrics.density)
        }
    }
}
