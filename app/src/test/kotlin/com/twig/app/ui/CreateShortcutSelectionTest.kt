package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.RemoteCmd
import com.twig.app.SavedConnection
import com.twig.app.Website
import com.twig.app.WebsiteStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CreateShortcutSelectionTest {
    @Before
    fun grantStorage() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `website selection waits for confirm and returns website action`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val site = Website(name = "Dashboard", url = "https://example.com")
        WebsiteStore.save(ctx, site)
        val controller = Robolectric.buildActivity(CreateShortcutActivity::class.java).setup()
        val act = controller.get()
        val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
        shadowOf(Looper.getMainLooper()).idle()
        // AndroidViewModelFactory can retain an Application from an earlier Robolectric
        // sandbox; seed the model's own store as well as the Activity's store.
        WebsiteStore.save(pane.viewModel.getApplication(), site)
        pane.viewModel.expandGroup("websites")
        val node = pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.WebsiteNode>().firstOrNull { it.site.id == site.id }
            ?: error(pane.viewModel.state.value.rows.toString())
        assertTrue(act.onPickNode(node))
        assertFalse(act.isFinishing)
        assertNull(shadowOf(act).nextStartedActivity)
        val confirm = act.findViewById<View>(R.id.btn_confirm)
        assertTrue(confirm.isEnabled)
        confirm.performClick()
        val relay = shadowOf(act).resultIntent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)!!
        assertEquals(WebsiteActivity.intent(ctx, site.id).toUri(0),
            LauncherShortcutActivity.load(ctx, relay)!!.toUri(0))
        controller.pause().stop().destroy()
    }

    @Test
    fun `command picker disables unsupported nodes and cancellation returns no action`() {
        val controller = Robolectric.buildActivity(CreateCommandShortcutActivity::class.java).setup()
        val act = controller.get()
        val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
        val site = Website(name = "Site", url = "https://example.com")
        WebsiteStore.save(pane.viewModel.getApplication(), site)
        pane.viewModel.expandGroup("websites")
        act.onPickNode(PaneViewModel.WebsiteNode(site))
        assertFalse(act.findViewById<View>(R.id.btn_confirm).isEnabled)
        assertTrue(act.findViewById<View>(R.id.btn_confirm).alpha < 0.5f)
        act.findViewById<View>(R.id.btn_cancel).performClick()
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(act).resultCode)
        assertNull(shadowOf(act).resultIntent)
        controller.pause().stop().destroy()
    }

    @Test
    fun `unsupported group is disabled and visibly dimmed after valid selection`() {
        val controller = Robolectric.buildActivity(CreateShortcutActivity::class.java).setup()
        val act = controller.get()
        val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
        val rows = pane.viewModel.state.value.rows
        act.onPickNode(rows.filterIsInstance<PaneViewModel.FileNode>().first { it.file.scheme == "file" })
        assertTrue(act.findViewById<View>(R.id.btn_confirm).isEnabled)
        act.onPickNode(rows.filterIsInstance<PaneViewModel.GroupNode>().first())
        assertFalse(act.findViewById<View>(R.id.btn_confirm).isEnabled)
        assertTrue(act.findViewById<View>(R.id.btn_confirm).alpha < 0.5f)
        assertFalse(act.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `root directory remains a valid local command location`() {
        val controller = Robolectric.buildActivity(CreateCommandShortcutActivity::class.java).setup()
        val act = controller.get()
        val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
        val root = pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.scheme == "file" && it.file.path == "/" }
        act.onPickNode(root)
        assertTrue(act.findViewById<View>(R.id.btn_confirm).isEnabled)
        assertEquals(1f, act.findViewById<View>(R.id.btn_confirm).alpha)
        controller.pause().stop().destroy()
    }

    @Test
    fun `local command dialog persists local identity and working directory`() {
        val controller = Robolectric.buildActivity(CreateCommandShortcutActivity::class.java).setup()
        val act = controller.get()
        val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
        val local = pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.scheme == "file" && it.file.path != "/" }
        act.onPickNode(local)
        assertTrue(act.findViewById<View>(R.id.btn_confirm).isEnabled)
        assertEquals(1f, act.findViewById<View>(R.id.btn_confirm).alpha)
        act.findViewById<View>(R.id.btn_confirm).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val fields = mutableListOf<EditText>()
        fun visit(view: View) {
            if (view is EditText) fields += view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(dialog.window!!.decorView)
        fields[1].setText("id; pwd")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        @Suppress("DEPRECATION")
        val relay = shadowOf(act).resultIntent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)!!
        val cmd = RemoteCmd.fromShortcut(act, LauncherShortcutActivity.load(act, relay)!!)!!
        assertEquals(RemoteCmd.TARGET_LOCAL, cmd.target)
        assertEquals(local.file.path, cmd.workdir)
        assertEquals("", cmd.connLabel)
        controller.pause().stop().destroy()
    }

    @Test
    fun `command picker reuses command dialog for SFTP`() {
        val controller = Robolectric.buildActivity(CreateCommandShortcutActivity::class.java).setup()
        val act = controller.get()
        val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
        val conn = SavedConnection(type = "sftp", host = "example.com", port = 22, user = "demo")
        val node = PaneViewModel.ServerNode(conn, false, false)
        // Server selection is valid before connecting; configuring must not execute anything.
        pane.createNodeShortcut(node, command = true)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility == View.VISIBLE)
        val fields = mutableListOf<EditText>()
        val checks = mutableListOf<CheckBox>()
        fun visit(view: View) {
            if (view is EditText) fields += view
            if (view is CheckBox) checks += view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(dialog.window!!.decorView)
        val terminal = checks.single { it.text == act.getString(R.string.cmd_in_terminal) }
        assertTrue(terminal.isChecked)
        assertFalse(terminal.isEnabled)
        fields[1].setText("pwd")
        assertTrue(terminal.isEnabled)
        terminal.performClick()
        assertFalse(terminal.isChecked)
        fields[1].setText("  ")
        assertTrue(terminal.isChecked)
        assertFalse(terminal.isEnabled)
        // An empty label must also get a usable launcher name for terminal-only shortcuts.
        fields[0].setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        @Suppress("DEPRECATION")
        val relay = shadowOf(act).resultIntent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)!!
        val command = RemoteCmd.fromShortcut(act, LauncherShortcutActivity.load(act, relay)!!)!!
        assertEquals(conn.label(), command.connLabel)
        assertEquals("", command.command)
        assertEquals(conn.shortLabel(), command.label)
        assertTrue(command.inTerminal)
        assertNull(shadowOf(act).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
