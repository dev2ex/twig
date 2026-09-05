package com.twig.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Regression: the two toggles on the sync confirmation dialog **must really be on screen**,
 * and only appear when they mean something.
 *
 * Each of them guards an irreversible decision (delete the extra items on the other side /
 * overwrite the newer items on the other side); failing to render one is equivalent to
 * pressing the default value on the user's behalf — the same class of incident as the
 * "privileged access" dialog (see [PrivilegedDialogTest]), so the criterion is likewise
 * "attached to the view tree and visible", not "set on the builder or not".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompareSyncDialogTest {

    /** The destination side must be a **writable** source, otherwise [confirmSync] blocks it right there (see the last test case). */
    private val dstFs = FakeFileSystem("cmpdst", dirs = mapOf("/dst" to emptyList()), writable = true)
    private val roFs = FakeFileSystem("cmpro", dirs = mapOf("/dst" to emptyList()))
    private val dst = XFile(dstFs.scheme, "/dst", isDir = true)

    @Before
    fun setUp() {
        FsRegistry.register(dstFs)
        FsRegistry.register(roFs)
    }

    @After
    fun tearDown() {
        FsRegistry.unregister(dstFs.scheme)
        FsRegistry.unregister(roFs.scheme)
    }

    private fun item(key: String, newer: Boolean = false) =
        SyncItem(key, XFile("l", "/src/$key", isDir = false), newer)

    private fun show(plan: SyncPlan, options: CompareOptions = CompareOptions()): AlertDialog {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        confirmSync(a, a.lifecycleScope, dst, to = 1, plan = plan, options = options)
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    /** All **visible** Views in the dialog's view tree. Invisible ones never count — if the user cannot see it, it does not exist. */
    private fun visible(dialog: AlertDialog): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(dialog.window!!.decorView)
        return out
    }

    private fun checkBoxes(dialog: AlertDialog) = visible(dialog).filterIsInstance<CheckBox>()

    private fun summaryOf(dialog: AlertDialog): TextView =
        visible(dialog).filterIsInstance<TextView>()
            .first { it !is CheckBox && it.text.toString().contains("item") }

    @Test
    fun `with both extra items and newer items present, both toggles render`() {
        val d = show(SyncPlan(listOf(item("a"), item("b", newer = true)), listOf(item("x"))))
        val texts = checkBoxes(d).map { it.text.toString() }
        assertEquals("both toggles should be there", 2, texts.size)
        assertTrue(texts.any { it.contains("Incremental") })
        assertTrue(texts.any { it.contains("Overwrite") })
    }

    @Test
    fun `with no extra items the incremental toggle is absent, with no newer items the overwrite toggle is absent`() {
        val onlyCopies = show(SyncPlan(listOf(item("a")), emptyList()))
        assertTrue("nothing to delete, so it should not even ask", checkBoxes(onlyCopies).isEmpty())

        val withDeletes = show(SyncPlan(listOf(item("a")), listOf(item("x"))))
        assertEquals(1, checkBoxes(withDeletes).size)
        assertTrue(checkBoxes(withDeletes).single().text.toString().contains("Incremental"))
    }

    @Test
    fun `the incremental toggle's initial value follows the options, defaulting to checked`() {
        val plan = SyncPlan(listOf(item("a")), listOf(item("x")))
        assertTrue(checkBoxes(show(plan)).single().isChecked)
        val mirror = show(plan, CompareOptions(incrementalSync = false))
        assertFalse("last time mirror was chosen, so it should still be mirror this time", checkBoxes(mirror).single().isChecked)
    }

    @Test
    fun `the item count follows whether Overwrite newer is checked`() {
        // two items to push, one of which is newer on the destination side; overwrite unchecked = only 1 item actually gets pushed
        val d = show(SyncPlan(listOf(item("a"), item("b", newer = true)), emptyList()))
        val summary = summaryOf(d)
        val ctx = d.context
        assertEquals(ctx.getString(R.string.compare_sync_confirm, 1, ctx.getString(R.string.compare_side_right)), summary.text.toString())
        val cbOverwrite = checkBoxes(d).single { it.text.toString().contains("Overwrite") }
        cbOverwrite.isChecked = true
        assertEquals(ctx.getString(R.string.compare_sync_confirm, 2, ctx.getString(R.string.compare_side_right)), summary.text.toString())
    }

    @Test
    fun `when the destination is a read-only source it is blocked outright, with no confirmation dialog`() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        ShadowDialog.reset()
        confirmSync(
            a, a.lifecycleScope, XFile(roFs.scheme, "/dst", isDir = true), to = 1,
            plan = SyncPlan(listOf(item("a")), emptyList()), options = CompareOptions(),
        )
        assertNull("a read-only source as the destination is pointless, so the dialog should not even pop up", ShadowDialog.getLatestDialog())
    }

    @Test
    fun `the title states which side it is syncing to`() {
        val d = show(SyncPlan(listOf(item("a")), emptyList()))
        val ctx = d.context
        val want = ctx.getString(R.string.compare_sync_to, ctx.getString(R.string.compare_side_right))
        assertNotNull(visible(d).filterIsInstance<TextView>().firstOrNull { it.text.toString() == want })
    }
}
