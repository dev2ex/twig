package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, qualifiers = "ar-rEG-ldrtl-mdpi")
class ArabicPaneLayoutTest {
    @Test fun `Arabic browser keeps icons and indentation left and selection right`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = host.get()
            activity.setTheme(R.style.Theme_Twig)
            val parent = FrameLayout(activity).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            activity.setContentView(parent)
            val pane = LayoutInflater.from(activity).inflate(R.layout.fragment_pane, parent, false)
            parent.addView(pane)
            val list = pane.findViewById<RecyclerView>(R.id.list)
            val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
            list.layoutManager = LinearLayoutManager(activity)
            list.itemAnimator = null
            list.adapter = adapter
            val arabicName = "ملخص كتاب التحليل الفني"
            adapter.submitList(listOf(0, 2).map { depth ->
                PaneViewModel.FileNode(XFile("file", "/$depth/$arabicName", true, canRead = false),
                    depth, false, false)
            } + listOf(
                PaneViewModel.GroupNode("fav", "المفضلة", false),
                PaneViewModel.FileNode(XFile("file", "/storage/emulated/0", true, canRead = false),
                    0, true, false, label = "التخزين الداخلي"),
                PaneViewModel.ActionNode("add_smb", activity.getString(R.string.action_add_smb)),
                PaneViewModel.ActionNode("add_saf", activity.getString(R.string.action_add_saf)),
            ))
            parent.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 480, 640)

            assertEquals(View.LAYOUT_DIRECTION_RTL, parent.layoutDirection)
            val first = list.findViewHolderForAdapterPosition(0)!!.itemView
            val nested = list.findViewHolderForAdapterPosition(1)!!.itemView
            val icon = first.findViewById<View>(R.id.icon)
            val indicator = first.findViewById<View>(R.id.indicator)
            val check = first.findViewById<View>(R.id.check)
            assertTrue(indicator.right <= icon.left)
            assertTrue(icon.right < check.left)
            assertEquals(first.width, check.right)
            val content = first.findViewById<View>(R.id.row_content)
            val nestedContent = nested.findViewById<View>(R.id.row_content)
            assertTrue(nestedContent.paddingLeft > content.paddingLeft)
            assertEquals(0, nestedContent.paddingRight)
            val name = first.findViewById<TextView>(R.id.name)
            assertEquals(arabicName, name.text.toString())
            assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, name.textDirection)
            // API 24's Robolectric text shadow does not resolve bidirectional paragraphs.
            if (Build.VERSION.SDK_INT >= 28) {
                assertEquals(-1, name.layout.getParagraphDirection(0))
            }
            for (position in 0 until adapter.itemCount) {
                val title = list.findViewHolderForAdapterPosition(position)!!.itemView
                    .findViewById<TextView>(R.id.name)
                assertEquals("Title at position $position", View.TEXT_ALIGNMENT_VIEW_START, title.textAlignment)
                if (Build.VERSION.SDK_INT >= 28) {
                    assertEquals("Arabic title stays left at position $position", 0f,
                        title.layout.getLineLeft(0), 0.5f)
                    assertEquals(-1, title.layout.getParagraphDirection(0))
                }
            }
        } finally {
            host.pause().stop().destroy()
        }
    }
}
