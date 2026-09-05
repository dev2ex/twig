package com.twig.app.ui

import android.app.Activity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ★ Regression: the launcher menu came out **empty** (2026-08-27).
 *
 * `setDynamicShortcuts` refuses a shortcut that does not name an owning activity —
 * `IllegalArgumentException: Cannot publish shortcut: target activity is not set` —
 * while `requestPinShortcut` fills it in for us, which is why the pinned shortcuts
 * had always worked and this looked like it should too. The failure was invisible
 * because the call sat inside a `runCatching`: on the desktop "publish threw" and
 * "never published" are the same empty menu.
 *
 * The criterion is the field on the shortcut we actually publish, not "did the call
 * throw" — Robolectric's shadow does not enforce the precondition, so a test that
 * only checked for an exception would be green on the broken version.
 *
 * ★ The ids are pinned here too: reusing the ids of the manifest shortcuts this
 * replaced is refused by the real system (they stay reserved while a launcher keeps
 * one pinned), and the refusal takes the whole batch with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutsTest {

    @Test
    fun `published shortcuts name their owning activity`() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        Shortcuts.publish(act)

        val published = ShortcutManagerCompat.getDynamicShortcuts(
            ApplicationProvider.getApplicationContext(),
        )
        assertEquals(listOf("open_music", "open_terminal"), published.map { it.id })
        for (s in published) {
            assertNotNull(
                "${s.id}: no activity — the system refuses to publish it",
                s.activity,
            )
            assertEquals(
                "${s.id}: the label must be resolved text, or the launcher shows " +
                    "it in the system language instead of the app's",
                s.shortLabel.toString(), s.longLabel.toString(),
            )
        }
    }
}
