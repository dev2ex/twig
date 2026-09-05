package com.twig.app.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The criterion for the "About" row is **what the user actually sees**: the row exists
 * on the settings page, the dialog it opens really shows the project URL, and the
 * "Project homepage" button fires an ACTION_VIEW pointing at that same address.
 *
 * The address is hard-coded in [SettingsActivity.PROJECT_URL] rather than strings.xml —
 * so this also pins its literal value: changing the domain/username should be a
 * deliberate edit alongside the README, not something that happens silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutRowTest {

    private fun openSettings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    /** The hand-written layout has no ids: find the TextView by its title text, then walk up to the ancestor that actually holds the click listener. */
    private fun rowWithTitle(activity: SettingsActivity, title: String): android.view.View? {
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        var hit: android.view.View? = null
        fun walk(v: android.view.View) {
            if (hit == null && v is TextView && v.text?.toString() == title) hit = v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        var cur: android.view.View? = hit
        while (cur != null && !cur.hasOnClickListeners()) cur = cur.parent as? android.view.View
        return cur
    }

    private fun textsIn(dialog: AlertDialog): List<String> {
        val out = ArrayList<String>()
        fun walk(v: android.view.View) {
            if (v is TextView) out += v.text?.toString().orEmpty()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        dialog.window?.decorView?.let { walk(it) }
        return out
    }

    @Test
    fun `settings page has an About Twig row, and its dialog shows the project URL`() {
        val activity = openSettings()
        val row = rowWithTitle(activity, activity.getString(R.string.settings_about))
        assertNotNull("settings page is missing the About Twig row", row)

        row!!.performClick()

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("clicking it did not pop up a dialog", dialog)
        assertTrue(
            "dialog does not show the project URL",
            textsIn(dialog!!).any { it.contains(SettingsActivity.PROJECT_URL) },
        )
    }

    @Test
    fun `Project homepage button fires an ACTION_VIEW pointing at the project URL`() {
        val activity = openSettings()
        rowWithTitle(activity, activity.getString(R.string.settings_about))!!.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // The button callback is posted to the main looper (AlertController forwards it via Message), so nothing happens without an idle()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("Project homepage did not start any Activity", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(SettingsActivity.PROJECT_URL, started.data?.toString())
    }

    @Test
    fun `Copy link puts the address on the clipboard`() {
        val activity = openSettings()
        rowWithTitle(activity, activity.getString(R.string.settings_about))!!.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(
            SettingsActivity.PROJECT_URL,
            cm.primaryClip?.getItemAt(0)?.text?.toString(),
        )
    }

    @Test
    fun `project URL matches the one in the README`() {
        assertEquals("https://github.com/dev2ex/twig", SettingsActivity.PROJECT_URL)
    }
}
