package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, qualifiers = "ar-rEG-ldrtl-mdpi")
class ArabicCompareLayoutTest {
    @Test fun `Arabic comparison preserves physical sides and tree geometry in both pane modes`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = host.get()
            activity.setTheme(R.style.Theme_Twig)
            val parent = FrameLayout(activity).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            activity.setContentView(parent)
            val screen = LayoutInflater.from(activity).inflate(R.layout.activity_compare, parent, false)
            parent.addView(screen)
            val left = screen.findViewById<View>(R.id.side_left)
            val right = screen.findViewById<View>(R.id.side_right)
            val state = screen.findViewById<View>(R.id.list_state).parent.parent as View
            val leftList = screen.findViewById<RecyclerView>(R.id.list_left)
            val rightList = screen.findViewById<RecyclerView>(R.id.list_right)
            for (list in listOf(leftList, rightList)) {
                list.layoutManager = LinearLayoutManager(activity)
                list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 2
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_compare_row, parent, false)) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        holder.itemView.findViewById<TextView>(R.id.name).text = "مجلد عربي"
                        holder.itemView.findViewById<View>(R.id.indent).layoutParams.width = position * 24
                    }
                }
            }
            for (mode in 0..2) {
                // Both panes, left only, right only: the same visibility policy as CompareActivity.
                left.visibility = if (mode == 2) View.GONE else View.VISIBLE
                right.visibility = if (mode == 1) View.GONE else View.VISIBLE
                parent.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, 800, 480)
                assertEquals(View.LAYOUT_DIRECTION_RTL, screen.findViewById<View>(R.id.toolbar).layoutDirection)
                if (mode != 2) assertEquals(left.right, state.left)
                if (mode != 1) assertEquals(state.right, right.left)
                for (list in listOf(leftList, rightList).filter { it.parent === left && mode != 2 || it.parent === right && mode != 1 }) {
                    val first = list.findViewHolderForAdapterPosition(0)!!.itemView
                    val nested = list.findViewHolderForAdapterPosition(1)!!.itemView
                    val icon = first.findViewById<View>(R.id.icon)
                    val indicator = first.findViewById<View>(R.id.indicator)
                    val check = first.findViewById<View>(R.id.check)
                    assertTrue(indicator.right <= icon.left)
                    assertTrue(icon.right < check.left)
                    assertEquals(first.width, check.right)
                    assertTrue(nested.findViewById<View>(R.id.icon).left > icon.left)
                    assertEquals(first.height, nested.height)
                    val title = first.findViewById<TextView>(R.id.name)
                    assertEquals(View.TEXT_ALIGNMENT_VIEW_START, title.textAlignment)
                    if (Build.VERSION.SDK_INT >= 28) {
                        assertEquals(0f, title.layout.getLineLeft(0), 0.5f)
                        assertEquals(-1, title.layout.getParagraphDirection(0))
                    }
                }
                for ((bar, icon, path) in listOf(
                    Triple(R.id.path_bar_left, R.id.iv_path_left, R.id.tv_path_left),
                    Triple(R.id.path_bar_right, R.id.iv_path_right, R.id.tv_path_right),
                )) {
                    if ((screen.findViewById<View>(bar).parent as View).visibility == View.VISIBLE) {
                        assertTrue(screen.findViewById<View>(icon).right <= screen.findViewById<View>(path).left)
                    }
                }
            }
        } finally {
            host.pause().stop().destroy()
        }
    }
}
