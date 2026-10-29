package com.twig.app.ui

import android.app.Application
import android.content.DialogInterface
import android.view.View
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.StripItems
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StripItemsDialogTest {
    private fun layout(list: RecyclerView) {
        list.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 900, 1400)
    }

    @Test fun `drag keeps checkbox identity and only confirmation saves changes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("twig_prefs", 0)
        prefs.edit().clear().commit()
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_Twig)
        controller.setup()
        try {
            fun moveAndHide(dialog: AlertDialog) {
                val list = dialog.findViewById<RecyclerView>(R.id.items)!!
                layout(list)
                val source = list.findViewHolderForAdapterPosition(0)!!
                val target = list.findViewHolderForAdapterPosition(2)!!
                source.itemView.findViewById<CheckBox>(R.id.visible).performClick()
                val helper = (0 until list.itemDecorationCount).map { list.getItemDecorationAt(it) }
                    .filterIsInstance<ItemTouchHelper>().single()
                val callback = ItemTouchHelper::class.java.getDeclaredField("mCallback")
                    .apply { isAccessible = true }.get(helper) as ItemTouchHelper.Callback
                assertTrue(callback.onMove(list, source, target))
                layout(list)
                assertEquals("up", list.findViewHolderForAdapterPosition(2)!!.itemView.tag)
                assertFalse(list.findViewHolderForAdapterPosition(2)!!.itemView.findViewById<CheckBox>(R.id.visible).isChecked)
            }
            val canceled = StripItemsDialog.show(controller.get()) { fail("Cancel must not save") }
            moveAndHide(canceled)
            canceled.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(StripItems.all, Prefs.stripItemOrder(app))
            assertTrue(Prefs.stripItemVisible(app, "up"))
            var saved = false
            val confirmed = StripItemsDialog.show(controller.get()) { saved = true }
            moveAndHide(confirmed)
            confirmed.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(saved)
            assertEquals(listOf("sort", "refresh", "up"), Prefs.stripItemOrder(app).take(3).map { it.id })
            assertFalse(Prefs.stripItemVisible(app, "up"))
            val reopened = StripItemsDialog.show(controller.get()) { }
            val list = reopened.findViewById<RecyclerView>(R.id.items)!!
            layout(list)
            assertFalse(list.findViewHolderForAdapterPosition(2)!!.itemView.findViewById<CheckBox>(R.id.visible).isChecked)
            reopened.dismiss()
        } finally {
            controller.pause().stop().destroy()
            prefs.edit().clear().commit()
        }
    }
}
