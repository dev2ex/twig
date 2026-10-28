package com.twig.app.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.DialogRootItemsBinding
import com.twig.app.databinding.ItemRootVisibilityBinding

object RootItemsDialog {
    fun show(activity: AppCompatActivity, onSaved: () -> Unit): AlertDialog {
        val items = Prefs.rootItemOrder(activity).toMutableList()
        // Key choices by identity: dragging and recycling must never swap checkboxes.
        val checked = Array(2) { pane ->
            items.associate { it.id to Prefs.rootItemVisible(activity, pane, it.id) }.toMutableMap()
        }
        val inflater = LayoutInflater.from(activity)
        val content = DialogRootItemsBinding.inflate(inflater)
        val metrics = activity.resources.displayMetrics
        val availableHeight = (activity.resources.configuration.screenHeightDp - 230).coerceAtLeast(96) * metrics.density
        content.items.layoutParams.height = minOf(metrics.heightPixels * 0.55f, availableHeight).toInt()
        content.items.layoutManager = LinearLayoutManager(activity)
        class Holder(val row: ItemRootVisibilityBinding) : RecyclerView.ViewHolder(row.root)
        lateinit var drag: ItemTouchHelper
        val adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                Holder(ItemRootVisibilityBinding.inflate(inflater, parent, false))

            @SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val item = items[position]
                val row = holder.row
                row.root.tag = item.id
                row.icon.setImageResource(item.icon)
                row.label.setText(item.label)
                row.dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) drag.startDrag(holder)
                    false
                }
                listOf(row.left, row.right).forEachIndexed { pane, checkbox ->
                    checkbox.setOnCheckedChangeListener(null)
                    checkbox.isChecked = checked[pane][item.id] == true
                    checkbox.contentDescription = activity.getString(item.label) + " · " +
                        activity.getString(if (pane == 0) R.string.compare_side_left else R.string.compare_side_right)
                    checkbox.setOnCheckedChangeListener { _, on -> checked[pane][item.id] = on }
                    (if (pane == 0) row.leftCell else row.rightCell).apply {
                        setOnClickListener { checkbox.performClick() }
                    }
                }
            }
        }
        content.items.adapter = adapter
        drag = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                items.add(to, items.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        drag.attachToRecyclerView(content.items)
        return AlertDialog.Builder(activity)
            .setTitle(R.string.action_root_items)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Prefs.setRootItemOrder(activity, items.map { it.id })
                for (pane in 0..1) items.forEach {
                    Prefs.setRootItemVisible(activity, pane, it.id, checked[pane].getValue(it.id))
                }
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
