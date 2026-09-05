package com.twig.app.secure

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ui.MusicPlayerActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Entering an Activity while locked, the Activity must still run through its full
 * lifecycle without trouble.
 *
 * ★ This pitfall has been hit twice, both the same pattern: **initialization is deferred
 * until after the unlock callback, but lifecycle callbacks run first**. While the unlock
 * dialog is showing, the Activity still goes through `onStart`/`onResume` as usual, and a
 * `lateinit` that has not been assigned yet crashes on the spot — the first time it was
 * `PaneFragment.adapter` (crash right after entering the master password), the second
 * time it was `MusicPlayerActivity.plDrawer` (reproducible every time from tapping the
 * notification).
 *
 * So the rule is: **views and lateinit fields are always built in `onCreate`; the
 * `SecurityUi.gate` callback holds only "read data"**. This test pins exactly that down —
 * moving initialization back into the gate callback immediately turns it red.
 */
@RunWith(RobolectricTestRunner::class)
class LockedEntryResumeTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    private object MemoryWrapper : Secrets.Wrapper {
        var key: ByteArray? = null
        override fun wrap(dek: ByteArray): String { key = dek.copyOf(); return "mem" }
        override fun unwrap(blob: String): ByteArray? = key?.copyOf()
        override fun available() = true
    }

    @Before
    fun setUp() {
        MemoryWrapper.key = null
        Secrets.wrapper = MemoryWrapper
        Secrets.reset(ctx)
        Secrets.enc(ctx, "x") // force the DEK to materialize
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        Secrets.lock()
    }

    @After
    fun tearDown() {
        Secrets.reset(ctx)
        Secrets.wrapper = Secrets.AndroidKeystore
    }

    /**
     * Music keeps playing as usual while locked and the notification buttons keep
     * working, but **entering this screen always requires the password** — the playlist,
     * which server a track comes from, and "locate in file manager" are all behind it.
     */
    @Test
    fun `opening the music player while locked always requires the master password`() {
        Robolectric.buildActivity(MusicPlayerActivity::class.java).setup().use {
            val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue("the unlock dialog should pop up: the playlist and track sources are behind it", dlg != null && dlg.isShowing)
        }
    }

    /**
     * ★ "there is already a dialog waiting" must be tracked **per screen**, not with a
     * single global boolean.
     *
     * If the dialog never reaches `dismiss` (the Activity is destroyed, the process
     * reclaims the screen), a global flag would stay stuck at true forever, and after
     * that **every entry point's gate returns immediately** — the master password quietly
     * stops working, with no sign of it on screen at all. This is the regression test for
     * that implementation: it was this very test going red that surfaced the bug.
     */
    @Test
    fun `an unlock dialog left open on one screen does not exempt another screen from the password`() {
        org.robolectric.shadows.ShadowDialog.reset()
        Robolectric.buildActivity(MusicPlayerActivity::class.java).setup() // pop one up, deliberately leave it open
        Robolectric.buildActivity(MusicPlayerActivity::class.java).setup()
        assertTrue(
            "the second screen must also pop up its own unlock dialog",
            org.robolectric.shadows.ShadowDialog.getShownDialogs().size >= 2,
        )
    }

    /**
     * ★ The password field must display dots.
     *
     * Just setting `inputType = TYPE_TEXT_VARIATION_PASSWORD` is not enough — calling
     * `setSingleLine()` afterward internally does
     * `setTransformationMethod(SingleLineTransformationMethod)`, which overwrites the dot
     * transformation entirely, **leaving the password sitting in plaintext on screen while
     * inputType still looks completely correct**. So the check is on
     * `transformationMethod`, not inputType.
     */
    @Test
    fun `the master password field shows dots, not plaintext`() {
        Robolectric.buildActivity(MusicPlayerActivity::class.java).setup().use {
            val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()!!
            val fields = ArrayList<android.widget.EditText>()
            collectEditTexts(dlg.window!!.decorView, fields)
            assertTrue("the unlock dialog should contain an input field", fields.isNotEmpty())
            fields.forEach {
                assertTrue(
                    "the password must not be shown in plaintext (setSingleLine would overwrite the dot transformation)",
                    it.transformationMethod is android.text.method.PasswordTransformationMethod,
                )
            }
        }
    }

    private fun collectEditTexts(v: android.view.View, out: MutableList<android.widget.EditText>) {
        if (v is android.widget.EditText) out += v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collectEditTexts(v.getChildAt(i), out)
    }

    @Test
    fun `opening the music player while locked does not crash on resume`() {
        assertTrue("precondition: it must really be locked", Secrets.locked(ctx))
        // setup() drives it all the way to RESUMED - the unlock dialog is still on screen, onResume still runs
        Robolectric.buildActivity(MusicPlayerActivity::class.java).setup().use {
            assertTrue(it.get() != null)
        }
    }
}
