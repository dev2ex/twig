package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Row rendering for the long-press menu.
 *
 * There is only one point worth unit-testing, but it is fatal: the row layout uses
 * `?attr/dialogPreferredPadding`, and if the theme is missing that attribute, inflate
 * **throws right there** — at the moment the user has just long-pressed, which shows up
 * as "long-press does nothing / crashes outright". While we're at it, also pin down that
 * the icon really gets swapped to this item's own icon (`ArrayAdapter` reuses the
 * convertView, and forgetting to call setImageResource every time leaks the previous
 * row's icon into this one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionMenuTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ctx = ContextThemeWrapper(app, R.style.Theme_Twig)

    @Test
    fun rendersLabelAndIcon() {
        val actions = listOf(
            MenuAct("Copy", R.drawable.ic_copy) {},
            MenuAct("Delete", R.drawable.ic_delete, DANGER) {},
        )
        val adapter = menuAdapter(ctx, actions)
        val parent = FrameLayout(ctx)

        val first = adapter.getView(0, null, parent)
        assertEquals("Copy", first.findViewById<TextView>(R.id.label).text.toString())
        assertNotNull(first.findViewById<ImageView>(R.id.icon).drawable)

        // Reuse the first row's view to render the second row: both the text and the icon must change
        val second = adapter.getView(1, first, parent)
        assertEquals("Delete", second.findViewById<TextView>(R.id.label).text.toString())
        assertEquals(
            app.getColor(R.color.menu_icon_danger),
            second.findViewById<ImageView>(R.id.icon).imageTintList?.defaultColor,
        )
    }
}
