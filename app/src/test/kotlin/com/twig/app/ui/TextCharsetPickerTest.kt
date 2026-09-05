package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.TextCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The "text encoding" picker: candidates **actually render**, and checking / reordering
 * **actually persists**.
 *
 * The first half follows the same pitfall as "Privileged Access" (see
 * [PrivilegedDialogTest]: an AlertDialog's content panel only has room for one thing, and a
 * careless setup leaves the whole list unattached to the view tree while the code reads
 * flawlessly); the second half pins down the entire point of this feature -- order is
 * priority, and getting the stored order wrong means "set but useless".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextCharsetPickerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        Prefs.setTextCharsets(app, listOf("GB18030"))
    }

    private fun openSettings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun latestDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as? AlertDialog

    /**
     * ★ A dialog button's callback is dispatched **via a Handler message**
     * (AlertController's mButtonHandler), and Robolectric does not auto-run the main looper
     * by default -- calling only `performClick` is as good as not clicking at all, and
     * assertions see nothing but initial values, looking like "the click had no effect".
     */
    private fun click(v: View) {
        v.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun descendants(v: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) {
            out += x
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    private fun openPicker(): Pair<SettingsActivity, AlertDialog> {
        val activity = openSettings()
        val title = activity.getString(com.twig.app.R.string.settings_text_charsets)
        var hit: View? = null
        for (v in descendants(activity.findViewById(android.R.id.content))) {
            if (hit == null && v is TextView && v.text?.toString() == title) hit = v
        }
        assertNotNull("could not find the \"$title\" row on the settings page", hit)
        var row: View? = hit
        while (row != null && !row.hasOnClickListeners()) row = row.parent as? View
        row!!.performClick()
        val dialog = latestDialog()
        assertNotNull("no dialog appeared after clicking", dialog)
        return activity to dialog!!
    }

    /**
     * Each candidate is one row in the dialog, row = checkbox + name; finds a row's checkbox
     * by name. ★ Matching includes the "\u00b7" that separates name from language label:
     * plain `startsWith("Big5")` also hits the "Big5-HKSCS" row.
     */
    private fun labelOf(dialog: AlertDialog, name: String): TextView =
        descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text?.toString()?.startsWith("$name \u00b7") == true }

    private fun boxOf(dialog: AlertDialog, name: String): CheckBox =
        descendants(labelOf(dialog, name).parent as ViewGroup).filterIsInstance<CheckBox>().first()

    @Test
    fun `every candidate renders in the dialog`() {
        val (_, dialog) = openPicker()
        val texts = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            .map { it.text?.toString().orEmpty() }
        for (name in TextCodec.CANDIDATES) {
            assertTrue("the \"$name\" row did not appear in the dialog", texts.any { it.startsWith("$name \u00b7") })
        }
    }

    @Test
    fun `checked encodings are saved in order`() {
        val (_, dialog) = openPicker()
        boxOf(dialog, "Big5").performClick() // GB18030 is already checked, Big5 is appended after it
        click(dialog.getButton(AlertDialog.BUTTON_POSITIVE))

        assertEquals(listOf("GB18030", "Big5"), Prefs.textCharsets(app))
        assertEquals(listOf("GB18030", "Big5"), TextCodec.preferred.map { it.name() })
    }

    /** Unchecking means "don't try this encoding", not "fall back to the default" -- unchecking everything must really try none at all. */
    @Test
    fun `with nothing checked the stored list is empty, not a fallback to the default GB18030`() {
        val (_, dialog) = openPicker()
        boxOf(dialog, "GB18030").performClick()
        click(dialog.getButton(AlertDialog.BUTTON_POSITIVE))

        assertEquals(emptyList<String>(), Prefs.textCharsets(app))
        assertEquals(emptyList<String>(), TextCodec.preferred)
    }

    /** Cancel must leave no trace behind. */
    @Test
    fun `cancel does not change the preference`() {
        val (_, dialog) = openPicker()
        boxOf(dialog, "Big5").performClick()
        click(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))

        assertEquals(listOf("GB18030"), Prefs.textCharsets(app))
    }

    /** The up arrow: only meaningful if it moves earlier in the order, so the stored order must change along with it. */
    @Test
    fun `moving up changes priority`() {
        Prefs.setTextCharsets(app, listOf("GB18030", "Big5"))
        val (activity, dialog) = openPicker()
        val up = activity.getString(com.twig.app.R.string.charset_move_up)
        val big5Row = labelOf(dialog, "Big5").parent as ViewGroup
        descendants(big5Row).first { it.contentDescription == up }.performClick()
        click(dialog.getButton(AlertDialog.BUTTON_POSITIVE))

        assertEquals(listOf("Big5", "GB18030"), Prefs.textCharsets(app))
    }

    /**
     * Upgrading from a build that still offered GBK: the picker keeps only names that are
     * still candidates, so a stored "GBK" that is merely filtered out would leave the user
     * with an empty list -- no Chinese decoding at all, silently. It is rewritten instead.
     */
    @Test
    fun `a stored GBK is migrated to GB18030 rather than dropped`() {
        Prefs.setTextCharsets(app, listOf("GBK", "Big5"))
        assertEquals(listOf("GB18030", "Big5"), Prefs.textCharsets(app))

        val (_, dialog) = openPicker()
        assertTrue(boxOf(dialog, "GB18030").isChecked)
    }
}
