package com.twig.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import com.twig.app.R
import com.twig.app.databinding.ItemInfoCardBinding
import com.twig.app.sqlite.SqliteCell

/** The table's property card stays in the tree, independently of its data expansion. */
class SqliteInfoView(context: Context) : LinearLayout(context) {
    private val card = ItemInfoCardBinding.inflate(LayoutInflater.from(context), this, true)
    private val grid = SqliteGridView(context)
    private val dp = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        // Keep the existing card shell; a grid owns its horizontal gestures rather
        // than using the ordinary text card's swipe-to-change-page behavior.
        card.root.swipeEnabled = false
        card.infoContent.removeAllViews()
        card.infoContent.addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (224 * dp).toInt()).apply {
            setMargins(dp.toInt(), dp.toInt(), dp.toInt(), dp.toInt())
        })
    }

    fun bind(node: SqliteInfoNode, tabTextSp: Float = 12f, command: (SqliteCommand) -> Unit) {
        val state = node.state
        val headings = listOf(R.string.sqlite_name, R.string.sqlite_type, R.string.sqlite_not_null,
            R.string.sqlite_default, R.string.sqlite_primary_key, R.string.sqlite_hidden).map(context::getString)
        val rows = state.table.columns.map { c ->
            listOf(c.name, c.type, if (c.notNull) "1" else "0", c.defaultValue ?: "—",
                c.primaryKey.toString(), c.hidden.toString()).map(SqliteCell::text)
        }
        InfoCardTabs.bind(card.tabBar, listOf(context.getString(R.string.sqlite_columns), "DDL"),
            state.tab, tabTextSp,
            onTab = { command(if (it == 0) SqliteCommand.COLUMNS else SqliteCommand.DDL) },
            onClose = { command(SqliteCommand.CLOSE) })
        card.root.showPage(node.key, state.tab.toString(), state.tab, card.infoContent) {
            if (state.tab == 0) {
                grid.bind(node.key + "/columns", headings, rows.size, state.columnsViewport,
                    rowAt = { rows.getOrNull(it) }, cell = { row, _ -> SqliteCellDialog.show(context,
                        state.table.columns[row].name, rows[row].joinToString("\n") { it.text }) })
            } else {
                val lines = state.table.ddl.lines()
                grid.bind(node.key + "/ddl", listOf("DDL"), lines.size, state.ddlViewport,
                    rowAt = { index -> lines.getOrNull(index)?.let { listOf(SqliteCell.text(it)) } },
                    cell = { _, _ -> SqliteCellDialog.show(context, state.table.name, state.table.ddl) }, contentLines = lines)
            }
        }
    }

    fun release() {
        grid.release()
        card.root.resetPage()
        card.tabBar.removeAllViews()
    }

    fun setGridHeight(height: Int) {
        grid.layoutParams = grid.layoutParams.apply { this.height = height }
    }
}
