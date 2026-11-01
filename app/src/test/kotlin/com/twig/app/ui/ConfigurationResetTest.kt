package com.twig.app.ui

import android.app.Application
import android.content.DialogInterface
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.RootItems
import com.twig.app.StripItems
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConfigurationResetTest {
    @Test fun `reset previews defaults while Cancel preserves saved configuration and OK applies defaults`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("twig_prefs", 0)
        prefs.edit().clear().commit()
        Prefs.setRootItemOrder(app, RootItems.all.reversed().map { it.id })
        Prefs.setRootItemVisible(app, 0, "private", false)
        Prefs.setRootItemVisible(app, 1, "root", false)
        Prefs.setStripItems(app, StripItems.all.reversed().map { it.id }, setOf("info", "exit"))
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.Theme_Twig)
        host.setup()
        try {
            for (roots in listOf(true, false)) {
                var saves = 0
                fun show() = if (roots) RootItemsDialog.show(host.get()) { saves++ }
                    else StripItemsDialog.show(host.get()) { saves++ }
                fun reset(dialog: androidx.appcompat.app.AlertDialog) {
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
                    assertTrue(dialog.isShowing)
                    assertEquals(0, saves)
                    val list = dialog.findViewById<RecyclerView>(R.id.items)!!
                    list.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY))
                    list.layout(0, 0, 900, 1400)
                    assertEquals(if (roots) "fav" else "up", list.findViewHolderForAdapterPosition(0)!!.itemView.tag)
                }
                val canceled = show()
                reset(canceled)
                canceled.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(RootItems.all.reversed(), Prefs.rootItemOrder(app))
                assertEquals(StripItems.all.reversed(), Prefs.stripItemOrder(app))
                assertFalse(Prefs.rootItemVisible(app, 0, "private"))
                val confirmed = show()
                reset(confirmed)
                confirmed.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(1, saves)
                if (roots) {
                    assertEquals(RootItems.all, Prefs.rootItemOrder(app))
                    RootItems.all.forEach { item ->
                        for (pane in 0..1) assertEquals(item.visibleByDefault, Prefs.rootItemVisible(app, pane, item.id))
                    }
                    // Prepare the next dialog's independent cancellation checks.
                    Prefs.setRootItemOrder(app, RootItems.all.reversed().map { it.id })
                    Prefs.setRootItemVisible(app, 0, "private", false)
                } else {
                    assertEquals(StripItems.all, Prefs.stripItemOrder(app))
                    StripItems.all.forEach { assertEquals(it.visibleByDefault, Prefs.stripItemVisible(app, it.id)) }
                }
            }
        } finally {
            host.pause().stop().destroy()
            prefs.edit().clear().commit()
        }
    }
}
