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
import com.twig.app.StripItems
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.DialogStripItemsBinding
import com.twig.app.databinding.ItemStripVisibilityBinding

object StripItemsDialog {
    fun show(activity: AppCompatActivity, onSaved: () -> Unit): AlertDialog {
        val items = Prefs.stripItemOrder(activity).toMutableList()
        // Key choices by identity: dragging and recycling must never swap checkboxes.
        val checked = items.associate { it.id to Prefs.stripItemVisible(activity, it.id) }.toMutableMap()
        val inflater = LayoutInflater.from(activity)
        val content = DialogStripItemsBinding.inflate(inflater)
        val metrics = activity.resources.displayMetrics
        val availableHeight = (activity.resources.configuration.screenHeightDp - 230).coerceAtLeast(96) * metrics.density
        content.items.layoutParams.height = minOf(metrics.heightPixels * 0.55f, availableHeight).toInt()
        content.items.layoutManager = LinearLayoutManager(activity)
        class Holder(val row: ItemStripVisibilityBinding) : RecyclerView.ViewHolder(row.root)
        lateinit var drag: ItemTouchHelper
        val adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                Holder(ItemStripVisibilityBinding.inflate(inflater, parent, false))

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
                row.visible.setOnCheckedChangeListener(null)
                row.visible.isChecked = checked[item.id] == true
                row.visible.contentDescription = activity.getString(item.label)
                row.visible.setOnCheckedChangeListener { _, on -> checked[item.id] = on }
                row.root.setOnClickListener { row.visible.performClick() }
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
            .setTitle(R.string.settings_action_strip)
            .setView(content.root)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                Prefs.setStripItems(activity, items.map { it.id }, checked.filterValues { it }.keys)
                onSaved()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.dialog_reset, null)
            .show().apply {
                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    items.clear()
                    items.addAll(StripItems.all)
                    checked.clear()
                    items.forEach { checked[it.id] = it.visibleByDefault }
                    adapter.notifyDataSetChanged()
                }
                getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                    androidx.core.content.ContextCompat.getColor(activity, R.color.action_text))
            }
    }
}
