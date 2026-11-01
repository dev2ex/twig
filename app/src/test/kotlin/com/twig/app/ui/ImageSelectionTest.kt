package com.twig.app.ui

import android.app.Activity
import android.widget.CheckBox
import android.widget.TextView
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageSelectionTest {
    private val image = XFile("file", "/pictures/a.jpg", isDir = false)

    private fun withViewer(selection: List<XFile>, images: List<XFile> = listOf(image), block: (ImageViewerActivity, CheckBox) -> Unit) {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        ImageViewerActivity.start(host.get(), images, 0, selection = selection)
        val controller = Robolectric.buildActivity(
            ImageViewerActivity::class.java, shadowOf(host.get()).nextStartedActivity,
        ).setup()
        try {
            val activity = controller.get()
            block(activity, activity.findViewById(R.id.btn_select))
        } finally {
            controller.pause().stop().destroy()
            ImageViewerActivity.takeResult()
            host.pause().stop().destroy()
        }
    }

    @Test fun `pane checks initialize viewer and unchecking the last image returns empty selection`() {
        withViewer(listOf(image)) { activity, checkbox ->
            val count = activity.findViewById<TextView>(R.id.selection_count)
            assertTrue(checkbox.isChecked)
            assertEquals("1", count.text.toString())
            ShadowToast.reset()
            checkbox.performClick()
            assertFalse(checkbox.isChecked)
            assertEquals("", count.text.toString())
            assertNull(count.contentDescription)
            assertNull(ShadowToast.getTextOfLatestToast())
            val result = ImageViewerActivity.takeResult()!!
            assertEquals(emptyList<XFile>(), result.selection)
            assertFalse(result.changed)
        }
    }

    @Test fun `editing image checks preserves other directories and files with the same path on another backend`() {
        val document = XFile("file", "/documents/notes.txt", isDir = false)
        val remoteImage = image.copy(scheme = "sftp-test")
        withViewer(listOf(document, remoteImage)) { _, checkbox ->
            assertFalse(checkbox.isChecked)
            checkbox.performClick()
            assertEquals(listOf(document, remoteImage, image), ImageViewerActivity.takeResult()!!.selection)
            checkbox.performClick()
            assertEquals(listOf(document, remoteImage), ImageViewerActivity.takeResult()!!.selection)
        }
    }

    @Test fun `opening and closing without editing does not replace pane checks`() {
        withViewer(listOf(image)) { _, checkbox ->
            assertTrue(checkbox.isChecked)
            assertNull(ImageViewerActivity.takeResult())
        }
    }

    @Test fun `return reports the last viewed image even without editing checks`() {
        val next = image.copy(path = "/pictures/b.jpg")
        withViewer(emptyList(), listOf(image, next)) { activity, _ ->
            activity.findViewById<android.view.View>(R.id.btn_next).performClick()
            activity.finish()
            val result = ImageViewerActivity.takeResult()!!
            assertEquals(next, result.currentImage)
            assertEquals(emptyList<XFile>(), result.selection)
            assertFalse(result.changed)
        }
    }
}
