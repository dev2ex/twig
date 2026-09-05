package com.twig.app.ui

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A pane must not crash when the host asks it something before its view has been built.
 *
 * The two panes' `onViewCreated` run **one at a time**, and the one that gets built first
 * fires its first frame straight into `render -> MainActivity.onClipTargetChanged ->
 * syncStripEnabled`, which in passing asks **the other side** about its capabilities --
 * at that moment its `adapter` (lateinit) has not been assigned yet.
 *
 * This never used to be hit because both panes were committed inside `Activity.onCreate`,
 * so by the time there was any state to render both sides were already in place. The
 * master-password unlock dialog pushed pane initialization to after RESUMED, and that is
 * what exposed this ordering: **it crashed the instant the master password was entered**,
 * with `lateinit property adapter has not been initialized`.
 *
 * The full ordering cannot be reproduced directly (`setMaxLifecycle(CREATED)` still
 * creates the view), so what this test pins down is the defense itself -- remove either
 * of the two `::adapter.isInitialized` checks and this test fails immediately.
 */
@RunWith(RobolectricTestRunner::class)
class PaneReadyTest {

    @Test
    fun `asking for checked items before the view is built returns empty, does not throw`() {
        val f = PaneFragment.newInstance(0)
        assertFalse("not even attached yet, let alone having a view", f.isReady())
        assertEquals(
            "the host may ask this in passing while refreshing the other side; throwing at that moment is a crash",
            emptyList<XFile>(),
            f.checkedFiles(),
        )
    }
}
