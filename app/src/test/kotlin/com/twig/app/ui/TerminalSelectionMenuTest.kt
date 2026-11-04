package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TerminalSelectionMenuTest {
    @Test fun `native copy and paste remain while More is replaced by safe system text handlers`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val request = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            val pm = shadowOf(activity.packageManager)
            fun handler(name: String, exported: Boolean = true, permission: String? = null) = ResolveInfo().apply {
                nonLocalizedLabel = name
                activityInfo = ActivityInfo().apply {
                    packageName = "test.processors"
                    this.name = "test.processors.$name"
                    this.exported = exported
                    this.permission = permission
                    applicationInfo = ApplicationInfo().apply { packageName = "test.processors" }
                }
            }
            pm.addResolveInfoForIntent(request, handler("Translate"))
            pm.addResolveInfoForIntent(request, handler("Private", exported = false))
            pm.addResolveInfoForIntent(request, handler("Restricted", permission = "test.permission.UNGRANTED"))
            val menu = MenuBuilder(activity)
            menu.add(0, 1, 0, "Copy")
            menu.add(0, 2, 0, "Paste")
            menu.add(0, 3, 0, "More")
            var selected = "first"
            var finished = false
            val finish = { finished = true; selected = "" }
            repeat(2) { TerminalSelectionMenu.populate(activity, menu, { selected }, finish) }
            assertNotNull(menu.findItem(1))
            assertNotNull(menu.findItem(2))
            assertNull(menu.findItem(3))
            assertNotNull(menu.findItem(R.id.web_search))
            assertNotNull(menu.findItem(R.id.terminal_share_text))
            val handlers = (0 until menu.size()).map { menu.getItem(it) }
                .filter { it.groupId == R.id.terminal_process_text }
            assertEquals(listOf("Translate"), handlers.map { it.title.toString() })
            selected = "中文 & second\nline"
            assertTrue(menu.performIdentifierAction(handlers.single().itemId, 0))
            assertTrue(finished)
            val sent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_PROCESS_TEXT, sent.action)
            assertEquals("text/plain", sent.type)
            assertEquals("test.processors.Translate", sent.component!!.className)
            assertEquals("中文 & second\nline", sent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
            assertTrue(sent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `share sends selected text through a chooser and empty selections launch nothing`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val menu = MenuBuilder(activity)
            var selected = ""
            var finishes = 0
            TerminalSelectionMenu.populate(activity, menu, { selected }, { finishes++ })
            menu.performIdentifierAction(R.id.terminal_share_text, 0)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(0, finishes)
            selected = "hello\nworld"
            menu.performIdentifierAction(R.id.terminal_share_text, 0)
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION") val sent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, sent.action)
            assertEquals("text/plain", sent.type)
            assertEquals("hello\nworld", sent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(1, finishes)
        } finally { controller.pause().stop().destroy() }
    }
}
