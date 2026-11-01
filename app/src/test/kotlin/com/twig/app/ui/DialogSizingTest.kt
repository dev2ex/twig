package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
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
class DialogSizingTest {
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

    @Test fun directoryScopeLabelsShowEveryLineAtNarrowWidthsAndLargerFonts() {
        for (language in listOf("en", "ru")) for (scale in listOf(1f, 1.5f)) {
            val root = LayoutInflater.from(context(language, scale)).inflate(R.layout.dialog_sort, null)
            measure(root, 260)
            val label = root.findViewById<TextView>(R.id.scope_directory)
            assertTrue("fixture must wrap: $language $scale", label.lineCount > 1)
            assertTrue("$language $scale last line clipped", label.height >=
                label.layout.getLineBottom(label.lineCount - 1) + label.compoundPaddingTop + label.compoundPaddingBottom)
            val group = label.parent as View
            assertTrue("$language $scale row clips its label: ${group.height} < ${label.bottom}",
                group.height >= label.bottom)
        }
    }

    @Test fun destinationPathKeepsUsableWidthAndAnAccessibleTouchTarget() {
        for (language in listOf("en", "de", "ru", "ar")) {
            val root = LayoutInflater.from(context(language, 1.5f)).inflate(R.layout.view_destination_picker, null)
            val path = root.findViewById<TextView>(R.id.tv_dest)
            path.text = "/storage/emulated/0/Documents/a-very-long-destination-folder"
            measure(root, 260)
            assertTrue(root.isClickable)
            assertTrue(root.isFocusable)
            assertTrue(root.height >= 48 * root.resources.displayMetrics.density)
            assertTrue("$language path squeezed out", path.width > 80 * root.resources.displayMetrics.density)
        }
    }
}
