package com.twig.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchRenameLayoutTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun withDialog(fileCount: Int = 2, selectExtension: Boolean = false, block: (AlertDialog, BatchRenameDialog.DialogContent) -> Unit) {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        try {
            BatchRenameDialog.show(activity, activity, listOf(
                XFile("batch", "/old.txt", false), XFile("batch", "/safe.txt", false),
            ) + (2 until fileCount).map { XFile("batch", "/file$it.txt", false) }, { null }, selectExtension) {}
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            try {
                val content = descendants(dialog.window!!.decorView).filterIsInstance<BatchRenameDialog.DialogContent>().single()
                layout(content, 480)
                block(dialog, content)
            } finally { dialog.dismiss() }
        } finally { controller.pause().stop().destroy() }
    }

    private fun layout(content: View, height: Int) {
        val dp = content.resources.displayMetrics.density
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        content.measure(View.MeasureSpec.makeMeasureSpec((320 * dp).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * dp).toInt(), View.MeasureSpec.AT_MOST))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
    }

    @Test fun `extension shortcut starts batch rename in extension mode`() = withDialog(selectExtension = true) { dialog, content ->
        val scope = descendants(content).filterIsInstance<Spinner>()
            .single { it.adapter.getItem(0) == dialog.context.getString(R.string.batch_name_only) }
        assertEquals(dialog.context.getString(R.string.batch_ext_only), scope.selectedItem)
    }

    @Test fun `only preview rows scroll while every rule form stays fixed`() = withDialog(80) { dialog, content ->
        val views = descendants(content)
        val method = views.filterIsInstance<Spinner>().single { it.adapter.getItem(0) == dialog.context.getString(R.string.batch_replace) }
        val regex = views.filterIsInstance<CheckBox>().single { it.text.toString() == dialog.context.getString(R.string.batch_regex) }
        val matchCase = views.filterIsInstance<CheckBox>().single { it.text.toString() == dialog.context.getString(R.string.batch_match_case) }
        assertSame(regex.parent, matchCase.parent)
        assertEquals(regex.top, matchCase.top)
        assertTrue(regex.left < matchCase.left)
        val form = content.getChildAt(0)
        val list = content.list
        assertEquals(80, list.adapter!!.itemCount)
        assertFalse(descendants(form).contains(list))
        for (mode in 0..4) {
            method.setSelection(mode)
            for (height in listOf(600, 480)) {
                layout(content, height)
                assertEquals(1, descendants(content).filterIsInstance<RecyclerView>().size)
                assertTrue("Preview must have room: mode=$mode height=$height form=${form.height}", list.height > 0)
                val formTop = form.top
                val formBottom = form.bottom
                val methodTop = method.top
                list.scrollBy(0, 100000)
                val last = list.findViewHolderForAdapterPosition(79)
                assertNotNull("The final preview row must be reachable", last)
                assertTrue(list.canScrollVertically(-1))
                assertEquals(formTop, form.top)
                assertEquals(formBottom, form.bottom)
                assertEquals(methodTop, method.top)
                assertEquals(0, form.scrollY)
            }
        }
    }

    @Test fun `preview count and changed-name emphasis follow the current rule`() = withDialog { dialog, content ->
        val fields = descendants(content).filterIsInstance<EditText>()
        fun field(id: Int) = fields.single { it.hint?.toString() == dialog.context.getString(id) }
        val summary = descendants(content).filterIsInstance<TextView>().single {
            it.text.toString() == dialog.context.getString(R.string.batch_preview_count, 0, 2)
        }
        field(R.string.batch_find).setText("old")
        field(R.string.batch_replacement).setText("new")
        layout(content, 480)
        assertEquals(dialog.context.getString(R.string.batch_preview_count, 1, 2), summary.text.toString())
        layout(content, 480)
        content.list.scrollBy(0, 100000)
        val row = content.list.findViewHolderForAdapterPosition(0)!!.itemView as ViewGroup
        val after = row.getChildAt(1) as TextView
        assertEquals("new.txt", after.text.toString())
        assertEquals(ContextCompat.getColor(dialog.context, R.color.action_text), after.currentTextColor)
        field(R.string.batch_find).setText("")
        layout(content, 480)
        content.list.scrollBy(0, 100000)
        val unchanged = (content.list.findViewHolderForAdapterPosition(0)!!.itemView as ViewGroup).getChildAt(1) as TextView
        assertEquals("old.txt", unchanged.text.toString())
        assertEquals(ContextCompat.getColor(dialog.context, R.color.text_primary), unchanged.currentTextColor)
        assertEquals(dialog.context.getString(R.string.batch_preview_count, 0, 2), summary.text.toString())
    }

    @Test @Config(qualifiers = "night")
    fun `dark preview names stay bright and inherit the dialog background`() = withDialog { dialog, content ->
        val title = descendants(content).filterIsInstance<TextView>().single {
            it.text.toString() == dialog.context.getString(R.string.batch_preview)
        }
        assertNull("Preview should not introduce a darker background band", (title.parent as View).background)
        layout(content, 480)
        content.list.scrollBy(0, 100000)
        val row = content.list.findViewHolderForAdapterPosition(0)!!.itemView as ViewGroup
        for (index in 0..1) {
            val text = row.getChildAt(index) as TextView
            assertTrue("Both original and unchanged names need bright text in dark mode",
                androidx.core.graphics.ColorUtils.calculateLuminance(text.currentTextColor) > 0.5)
        }
    }

    @Test @Config(fontScale = 1.5f)
    fun `standard radio choices have equal touch targets and keep checked state visible with larger fonts`() = withDialog { _, content ->
        val choices = descendants(content).filterIsInstance<BatchRenameDialog.Choices>()
        val operation = choices.single { (it.getChildAt(0) as RadioButton).text.toString() == content.context.getString(R.string.batch_insert) }
        val method = descendants(content).filterIsInstance<Spinner>().single { it.adapter.getItem(0) == content.context.getString(R.string.batch_replace) }
        method.setSelection(2)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        layout(content, 680)
        val direction = choices.single { (it.getChildAt(0) as RadioButton).text.toString() == content.context.getString(R.string.batch_insert_begin) }
        for (group in listOf(operation, direction)) {
            val left = group.getChildAt(0) as RadioButton
            val right = group.getChildAt(1) as RadioButton
            assertNotNull("The native radio indicator must remain visible", left.buttonDrawable)
            assertNotNull("The native radio indicator must remain visible", right.buttonDrawable)
            assertTrue(left.isChecked)
            assertEquals(left.height, right.height)
            assertTrue(left.height >= (48 * content.resources.displayMetrics.density).toInt())
            right.performClick()
            assertEquals(1, group.selectedIndex)
            assertFalse(left.isChecked)
            assertTrue(right.isChecked)
        }
    }
}
