package com.twig.app.ui

import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.TransferCheckpoint
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferRetryTest {
    private fun session(): Transfers.Session {
        val fs = FakeFileSystem("retry-dialog", mapOf("/" to emptyList()))
        FsRegistry.register(fs)
        return Transfers.Session(Transfers.Work.Copy(emptyList(), fs.root(), false, false),
            R.string.progress_copy, "target", R.drawable.ic_copy, null).apply {
            finished = Result.failure(java.io.IOException("Connection reset"))
            checkpoint.complete("already-completed")
            checkpoint.partial("unfinished", TransferCheckpoint.Partial(XFile(fs.scheme, "/part", false), "file"))
        }
    }

    @Test fun failureOffersRetryWithTheSameCompletedItemsAndPartialState() {
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val failed = session()
        var started = 0
        try {
            assertTrue(showTransferRetry(host.get(), failed, onStarted = { started++ }))
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(host.get().getString(R.string.transfer_retry), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(1, started)
            assertFalse(dialog.isShowing)
            val next = Transfers.active!!
            assertNotSame(failed, next)
            assertSame(failed.checkpoint, next.checkpoint)
            assertTrue("already-completed" in next.checkpoint.completed)
            assertTrue("unfinished" in next.checkpoint.partials)
            assertNull(next.finished)
            assertFalse(next.cancelled.get())
            Transfers.execute(host.get(), next) {}
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(next.checkpoint.partials.isEmpty())
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun dismissingFailureReleasesPartialStateInsteadOfKeepingAHistoryEntry() {
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val failed = session()
        try {
            var closed = false
            assertTrue(showTransferRetry(host.get(), failed, onStarted = { fail("Unexpected retry") },
                onClosed = { closed = true }))
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val end = System.nanoTime() + 2_000_000_000
            while (failed.checkpoint.partials.isNotEmpty() && System.nanoTime() < end) Thread.sleep(5)
            assertTrue(closed)
            assertTrue(failed.checkpoint.partials.isEmpty())
            assertNull(Transfers.active)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun cancellationAndSuccessDoNotPromptToRetry() {
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val cancelled = session().apply { cancel() }
            assertFalse(showTransferRetry(host.get(), cancelled, onStarted = {}))
            val success = session().apply { finished = Result.success(Unit) }
            assertFalse(showTransferRetry(host.get(), success, onStarted = {}))
        } finally {
            host.pause().stop().destroy()
        }
    }
    @Test fun failureShowsItsFinalTargetAndSkipContinuesWithThatItemExcluded() {
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val failed = session()
        val source = XFile("retry-dialog", "/source/bad.txt", false, 123, 456)
        val directory = XFile("file", "/storage/destination/nested", true)
        val failure = TransferCheckpoint.Failure(source, directory, "bad (1).txt")
        failed.checkpoint.failure = failure
        var started = false
        try {
            assertTrue(showTransferRetry(host.get(), failed, onStarted = { started = true }))
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
            assertTrue(message, message.contains("/storage/destination/nested/bad (1).txt"))
            assertEquals(host.get().getString(R.string.transfer_skip_failed),
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertTrue(started)
            val next = Transfers.active!!
            assertSame(failure, next.skipOnStart)
            // The choice is applied by the worker after a successful start, never while the dialog is waiting.
            assertFalse(next.checkpoint.isSkipped(source, directory))
            Transfers.execute(host.get(), next) {}
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(next.checkpoint.isSkipped(source, directory))
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun touchingOutsideFailureDialogKeepsTheRetryAndPartialStateAvailable() {
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val failed = session()
        var closed = false
        try {
            assertTrue(showTransferRetry(host.get(), failed, onStarted = {}, onClosed = { closed = true }))
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val outside = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_OUTSIDE, -100f, -100f, 0)
            try { dialog.onTouchEvent(outside) } finally { outside.recycle() }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing)
            assertFalse(closed)
            assertTrue("unfinished" in failed.checkpoint.partials)
            // Explicit cancellation still closes the dialog and releases the task.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertTrue(closed)
        } finally {
            host.pause().stop().destroy()
        }
    }

}
