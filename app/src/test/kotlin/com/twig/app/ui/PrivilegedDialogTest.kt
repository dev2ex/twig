package com.twig.app.ui

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * ★ Regression: the options in the "Privileged Access" dialog **must actually be on
 * screen**.
 *
 * The first version called both `setMessage()` (the Shizuku explanation) and
 * `setSingleChoiceItems()`. An AlertDialog's content panel only has room for one of them;
 * when both are set, message wins and **the list simply does not render** -- the user sees
 * an explanation and a "Cancel" button, with no option clickable at all. The code reads
 * flawlessly, yet the consequence is that the whole feature has no entry point: nothing can
 * be selected -> Shizuku authorization is never requested -> the app never shows up in
 * Shizuku's app list either -- three symptoms that look like three separate bugs.
 *
 * So this test does not check "did the builder get given options", but **how many rows the
 * ListView actually has once the dialog comes up**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedDialogTest {

    private fun openSettings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    /**
     * Finds the row on the settings page by its title text (a hand-written layout, no id
     * available). After finding the title TextView, **walk up to the first ancestor that
     * actually has a click listener** -- not hardcoded as "two levels up", since that kind
     * of code silently clicks the wrong View the moment the layout nesting changes, turning
     * the test into a false positive.
     */
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

    /** AppCompat's dialog is not managed by ShadowAlertDialog; it can only be obtained from the most recently shown Dialog. */
    private fun latestDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as? AlertDialog

    /**
     * ★ The assertion must be "**is it attached in the view tree**", not `listView != null`.
     *
     * AlertController **still constructs the ListView** even when there's a message, it just
     * does not add it to the content panel -- so `dialog.listView` is non-null with all three
     * rows in its adapter, while zero options are on screen. Using non-null as the check
     * would leave this test green on the buggy version too.
     */
    private fun isShown(dialog: AlertDialog, target: android.view.View?): Boolean {
        if (target == null) return false
        var found = false
        fun walk(v: android.view.View) {
            if (v === target) found = true
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        dialog.window?.decorView?.let { walk(it) }
        return found
    }

    @Test
    fun `the privileged access dialog actually renders all three options`() {
        val activity = openSettings()
        val title = activity.getString(R.string.settings_privileged)
        val row = rowWithTitle(activity, title)
        assertNotNull("could not find the \"$title\" row on the settings page", row)

        row!!.performClick()

        val dialog = latestDialog()
        assertNotNull("no dialog appeared after clicking", dialog)
        val listView = dialog!!.listView
        assertTrue(
            "the option list did not appear in the dialog -- likely a message was set on it again, occupying the content panel",
            isShown(dialog, listView),
        )
        assertEquals("should be a choice of three: Off / Root / Shizuku", 3, listView.adapter.count)
        assertEquals(activity.getString(R.string.priv_mode_off), listView.adapter.getItem(0).toString())
        assertEquals(activity.getString(R.string.priv_mode_shizuku), listView.adapter.getItem(2).toString())
    }

    /**
     * The explanatory text must not get lost just because it was moved -- it explains that
     * "Shizuku is not root", and without it a user might think choosing Shizuku grants
     * access to any app's private data.
     */
    @Test
    fun `the Shizuku note still appears in the dialog`() {
        val activity = openSettings()
        rowWithTitle(activity, activity.getString(R.string.settings_privileged))!!.performClick()

        val dialog = latestDialog()!!
        val note = activity.getString(R.string.priv_note_shizuku)
        val texts = ArrayList<String>()
        fun walk(v: android.view.View) {
            if (v is TextView) texts += v.text?.toString().orEmpty()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        dialog.window?.decorView?.let { walk(it) }
        assertTrue("the explanatory text is gone", texts.any { it == note })
    }

    /**
     * Pins down exactly what happens when `setMessage` and a list are used together -- so
     * nobody later "conveniently moves the note back into setMessage", which would look like
     * a harmless simplification.
     */
    @Test
    fun `setting both message and options together makes the list disappear`() {
        val activity = openSettings()
        AlertDialog.Builder(activity)
            .setTitle("t")
            .setMessage("some note")
            .setSingleChoiceItems(arrayOf("a", "b", "c"), 0, null)
            .show()

        val dialog = latestDialog()!!
        // note it is **not null** -- the list was constructed, just never attached. This is exactly why this pitfall is hard to find.
        assertNotNull("the list object itself is still there", dialog.listView)
        assertTrue(
            "the AlertDialog somehow showed both the message and the list -- then this pitfall no longer exists and the main test's assertion should be tightened",
            !isShown(dialog, dialog.listView),
        )
    }
}
