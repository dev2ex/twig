package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.twig.app.R
import com.twig.app.Format
import com.twig.app.sqlite.SqliteCell
import com.twig.app.sqlite.SqliteSort
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Virtual rows, fixed header, bounded column widths and one locked axis per gesture. */
class SqliteGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val rowHeight = 32 * dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private val foreground = ContextCompat.getColor(context, R.color.text_primary)
    private val secondary = ContextCompat.getColor(context, R.color.text_secondary)
    private val background = ContextCompat.getColor(context, R.color.surface)
    private val alternateBackground = ColorUtils.blendARGB(background, foreground, 0.04f)
    private val matchedBackground = ContextCompat.getColor(context, R.color.selected)
    private val rowBackground = ColorUtils.blendARGB(background, matchedBackground, 0.5f)
    private val line = (secondary and 0x00ffffff) or 0x30000000
    private var headers = emptyList<String>()
    private var widths = emptyList<Float>()
    private var rows = 0
    private var rowAt: (Int) -> List<SqliteCell>? = { null }
    private var errorAt: (Int) -> String? = { null }
    private var requestRow: (Int) -> Unit = {}
    private var onCell: (Int, Int) -> Unit = { _, _ -> }
    private var onHeader: ((Int) -> Unit)? = null
    private var onHeaderLongClick: ((Int) -> Unit)? = null
    private var filterable = emptySet<Int>()
    private var filtered = emptySet<Int>()
    private var selectRows = false
    private var sort: SqliteSort? = null
    private var position = SqliteViewport()
    private var owner: String? = null
    private var pendingRequests = false
    private var axis = SwipeGesture.UNDECIDED
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var verticalToParent = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val scroller = OverScroller(context)
    private var flingX = 0
    private var flingY = 0
    private data class AccessibleCell(val row: Int, val column: Int, val bounds: Rect)
    private fun accessibleCells(): Map<Int, AccessibleCell> = buildMap {
        var left = -position.x
        widths.forEachIndexed { column, w ->
            if (left + w > 0 && left < width) {
                val l = max(0f, left).toInt(); val r = min(width.toFloat(), left + w).toInt()
                put(-2 - column, AccessibleCell(-1, column, Rect(l, 0, r, rowHeight.toInt())))
                val first = (position.y / rowHeight).toInt()
                val last = min(rows.toLong(), first.toLong() + (height / rowHeight).toInt() + 2).toInt()
                for (row in first until last) {
                    val top = rowHeight + row.toFloat() * rowHeight - position.y
                    if (top < height && top + rowHeight > rowHeight && rowAt(row) != null) {
                        val id = ((row.toLong() * headers.size + column) % Int.MAX_VALUE).toInt()
                        put(id, AccessibleCell(row, column, Rect(l, max(rowHeight, top).toInt(),
                            r, min(height.toFloat(), top + rowHeight).toInt())))
                    }
                }
            }
            left += w
        }
    }
    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = accessibleCells().entries
            .firstOrNull { it.value.bounds.contains(x.toInt(), y.toInt()) }?.key ?: INVALID_ID
        override fun getVisibleVirtualViews(ids: MutableList<Int>) { ids.addAll(accessibleCells().keys) }
        override fun onPopulateNodeForVirtualView(id: Int, info: AccessibilityNodeInfoCompat) {
            val cell = accessibleCells()[id]
            info.setBoundsInParent(cell?.bounds ?: Rect(0, 0, 1, 1))
            info.contentDescription = if (cell == null) "" else headers[cell.column] +
                if (cell.row < 0) "" else ": " + display(rowAt(cell.row)?.getOrNull(cell.column))
            if (cell != null) {
                info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                    cell.row + 1, 1, cell.column, 1, cell.row < 0,
                    selectRows && cell.row >= 0 && cell.row == position.selectedRow))
                info.isSelected = selectRows && cell.row >= 0 && cell.row == position.selectedRow
                if (cell.row >= 0 || onHeader != null) {
                    info.isClickable = true; info.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                }
                if (cell.row >= 0 && selectRows || cell.row < 0 && cell.column in filterable) {
                    info.isLongClickable = true; info.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                }
                if (cell.row < 0 && cell.column in filtered) {
                    info.contentDescription = "${headers[cell.column]}, ${context.getString(R.string.sqlite_filter_active)}"
                }
                if (cell.row < 0 && sort?.column == headers[cell.column]) {
                    info.stateDescription = context.getString(if (sort?.descending == true)
                        R.string.sqlite_sort_descending else R.string.sqlite_sort_ascending)
                }
            }
        }
        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            val cell = accessibleCells()[id] ?: return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK ->
                    if (cell.row < 0) (onHeader ?: return false)(cell.column) else activate(cell.row, cell.column, false)
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> {
                    if (cell.row < 0) {
                        if (cell.column !in filterable) return false
                        filterHeader(cell.column)
                    } else {
                        if (!selectRows) return false
                        activate(cell.row, cell.column, true)
                    }
                }
                else -> return false
            }
            return true
        }
        override fun onPopulateNodeForHost(info: AccessibilityNodeInfoCompat) {
            super.onPopulateNodeForHost(info)
            info.className = "android.widget.GridView"
            info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                rows.toLong().plus(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), headers.size, false,
                if (selectRows) AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_SINGLE
                else AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_NONE))
            info.isScrollable = maxY > 0 || maxX > 0
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
            if (position.x > 0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT)
            if (position.x < maxX) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT)
            if (position.y > 0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_UP)
            if (position.y < maxY) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN)
        }
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { click(e); performClick(); return true }
        override fun onLongPress(e: MotionEvent) { click(e, longPress = true) }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            flingX = 0; flingY = 0
            scroller.fling(0, 0, if (axis == SwipeGesture.HORIZONTAL) -velocityX.toInt() else 0,
                if (axis == SwipeGesture.VERTICAL) -velocityY.toInt() else 0,
                -1000000, 1000000, -1000000, 1000000)
            postInvalidateOnAnimation()
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(background)
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        minimumHeight = (rowHeight * 3).toInt()
    }

    fun bind(node: SqliteDataNode, load: (Int) -> Unit, cell: (Int, Int) -> Unit, header: (Int) -> Unit = {}, filter: (Int) -> Unit = {}) {
        val state = node.state
        bind(node.key, state.table.visibleColumns.map { it.name }, state.rowCount, state.viewport,
            rowAt = { row -> state.pages[row / state.pageSize * state.pageSize]
                ?.rows?.getOrNull(row % state.pageSize) },
            errorAt = { row -> state.errors[row / state.pageSize * state.pageSize] },
            request = { row ->
                val offset = row / state.pageSize * state.pageSize
                if (offset !in state.pending) load(offset)
            }, cell = cell, header = header.takeIf { state.execution == null }, sort = state.sort, selectRows = true,
            headerLongClick = filter, filterableColumns = state.table.visibleColumns.mapIndexedNotNull { i, col ->
                i.takeUnless { col.type.contains("BLOB", ignoreCase = true) }
            }.toSet(), filteredColumns = state.table.visibleColumns.mapIndexedNotNull { i, col ->
                i.takeIf { state.filters.any { it.column == col.name } }
            }.toSet())
    }

    fun bind(key: String, headings: List<String>, count: Int, viewport: SqliteViewport,
        rowAt: (Int) -> List<SqliteCell>?, errorAt: (Int) -> String? = { null },
        request: (Int) -> Unit = {}, cell: (Int, Int) -> Unit = { _, _ -> },
        header: ((Int) -> Unit)? = null, sort: SqliteSort? = null, selectRows: Boolean = false,
        headerLongClick: ((Int) -> Unit)? = null, filterableColumns: Set<Int> = emptySet(),
        filteredColumns: Set<Int> = emptySet()) {
        if (owner != key || this.sort != sort || position !== viewport) scroller.abortAnimation()
        owner = key
        position = viewport
        headers = headings
        rows = count
        this.rowAt = rowAt
        this.errorAt = errorAt
        requestRow = request
        onCell = cell
        onHeader = header
        onHeaderLongClick = headerLongClick
        filterable = filterableColumns
        filtered = filteredColumns
        this.selectRows = selectRows
        if (position.selectedRow !in 0 until rows) position.selectedRow = -1
        this.sort = sort
        // Stable widths: incoming pages cannot shift columns underneath a finger.
        widths = headings.map { max(112 * dp, min(240 * dp, paint.measureText(it) + 24 * dp)) }
        contentDescription = context.getString(R.string.sqlite_grid_description, count, headings.size)
        clamp()
        accessibility.invalidateRoot()
        invalidate()
    }

    private val contentWidth get() = widths.sum()
    fun release() {
        scroller.abortAnimation()
        owner = null
        headers = emptyList(); widths = emptyList(); rows = 0
        rowAt = { null }; errorAt = { null }; requestRow = {}; onCell = { _, _ -> }
        onHeader = null; onHeaderLongClick = null; filterable = emptySet(); filtered = emptySet()
        sort = null; selectRows = false
        position = SqliteViewport()
    }
    private val maxX get() = max(0f, contentWidth - width)
    private val maxY get() = max(0f, rows.toFloat() * rowHeight - max(0f, height - rowHeight))
    private fun clamp() {
        position.x = position.x.coerceIn(0f, maxX)
        position.y = position.y.coerceIn(0f, maxY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { clamp() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        clamp()
        if (headers.isEmpty()) return
        val start = (position.y / rowHeight).toInt().coerceAtLeast(0)
        val end = min(rows.toLong(), start.toLong() + (height / rowHeight).toInt() + 2).toInt()
        val missing = LinkedHashSet<Int>()
        canvas.save()
        canvas.clipRect(0f, rowHeight, width.toFloat(), height.toFloat())
        for (row in start until end) {
            val cells = rowAt(row)
            val top = rowHeight + row.toFloat() * rowHeight - position.y
            if (selectRows && (row == position.selectedRow || row % 2 == 1)) {
                paint.color = if (row == position.selectedRow) rowBackground else alternateBackground
                canvas.drawRect(0f, top, width.toFloat(), top + rowHeight, paint)
            }
            if (cells == null) {
                val error = errorAt(row)
                if (error == null) missing.add(row)
                drawCell(canvas, error ?: context.getString(R.string.sqlite_loading),
                    0f, top, width.toFloat(), secondary)
            } else {
                var x = -position.x
                widths.forEachIndexed { column, w ->
                    val value = cells.getOrNull(column)
                    if (x + w > 0 && x < width) {
                        if (value?.matched == true) {
                            paint.color = matchedBackground
                            canvas.drawRect(x, top, x + w, top + rowHeight, paint)
                        }
                        drawCell(canvas, display(value), x, top, w,
                            if (value?.kind == 'n' || value?.kind == 'b') secondary else foreground)
                    }
                    x += w
                    if (x in 0f..width.toFloat()) {
                        paint.color = line; canvas.drawLine(x, top, x, top + rowHeight, paint)
                    }
                }
            }
            paint.color = line
            canvas.drawLine(0f, top + rowHeight, width.toFloat(), top + rowHeight, paint)
        }
        canvas.restore()
        paint.color = background
        canvas.drawRect(0f, 0f, width.toFloat(), rowHeight, paint)
        var x = -position.x
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        headers.forEachIndexed { index, text ->
            val sorted = sort?.column == text
            val arrow = if (sort?.descending == true) "↓" else "↑"
            val arrowWidth = if (sorted) paint.measureText(arrow) + 16 * dp else 0f
            if (index in filtered) {
                paint.color = rowBackground
                canvas.drawRect(x, 0f, x + widths[index], rowHeight, paint)
            }
            drawCell(canvas, if (index in filtered) "• $text" else text, x, 0f, widths[index] - arrowWidth, foreground)
            if (sorted) drawCell(canvas, arrow, x + widths[index] - arrowWidth, 0f, arrowWidth, foreground)
            x += widths[index]
        }
        paint.typeface = Typeface.MONOSPACE
        paint.color = line
        canvas.drawLine(0f, rowHeight, width.toFloat(), rowHeight, paint)
        if (rows == 0) drawCell(canvas, context.getString(R.string.sqlite_no_rows), 0f, rowHeight, width.toFloat(), secondary)
        paint.color = line
        if (maxX > 0 && width > 0) {
            val thumb = max(24 * dp, width.toFloat() * width / contentWidth)
            val left = (width - thumb) * position.x / maxX
            canvas.drawRoundRect(left, height - 4 * dp, left + thumb, height.toFloat(), 2 * dp, 2 * dp, paint)
        }
        if (maxY > 0 && height > rowHeight) {
            val track = height - rowHeight
            val thumb = max(24 * dp, track * track / (rows.toFloat() * rowHeight))
            val top = rowHeight + (track - thumb) * position.y / maxY
            canvas.drawRoundRect(width - 4 * dp, top, width.toFloat(), top + thumb, 2 * dp, 2 * dp, paint)
        }
        if (missing.isNotEmpty() && !pendingRequests) {
            pendingRequests = true
            val key = owner
            post {
                pendingRequests = false
                if (isAttachedToWindow && owner == key) missing.forEach(requestRow)
            }
        }
    }

    private fun display(cell: SqliteCell?): String = when (cell?.kind) {
        'n' -> "NULL"
        'b' -> context.getString(R.string.sqlite_blob, Format.size(cell.length))
        else -> cell?.text.orEmpty().replace('\n', ' ').replace('\r', ' ').let { if (cell?.truncated == true) "$it…" else it }
    }

    private fun drawCell(canvas: Canvas, text: String, x: Float, top: Float, width: Float, color: Int) {
        if (x >= this.width || x + width <= 0) return
        paint.color = color
        val available = max(0f, width - 16 * dp)
        val clean = text.replace('\n', ' ').replace('\r', ' ')
        val count = paint.breakText(clean, true, available, null)
        val shown = if (count < clean.length && count > 1) clean.take(count - 1) + "…" else clean.take(count)
        canvas.save()
        canvas.clipRect(x, top, x + width, top + rowHeight)
        canvas.drawText(shown, x + 8 * dp, top + (rowHeight - paint.fontMetrics.bottom - paint.fontMetrics.top) / 2, paint)
        canvas.restore()
    }

    private fun selectRow(row: Int) {
        position.selectedRow = row
        invalidate()
        accessibility.invalidateRoot()
    }

    private fun activate(row: Int, column: Int, longPress: Boolean) {
        if (selectRows) selectRow(row)
        if (longPress) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (longPress || !selectRows) onCell(row, column)
    }

    private fun filterHeader(column: Int) {
        if (column !in filterable) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHeaderLongClick?.invoke(column)
    }

    private fun click(event: MotionEvent, longPress: Boolean = false) {
        if (event.x < 0 || event.y < 0) return
        val header = event.y < rowHeight
        val row = if (header) -1 else ((event.y - rowHeight + position.y) / rowHeight).toInt()
        if (!header && row !in 0 until rows) return
        if (!header && rowAt(row) == null) { requestRow(row); return }
        var x = event.x + position.x
        widths.forEachIndexed { column, w ->
            if (x < w) {
                if (header) {
                    if (longPress) filterHeader(column) else onHeader?.invoke(column)
                } else activate(row, column, longPress)
                return
            }
            x -= w
        }
        if (!header && !longPress && selectRows) selectRow(row)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                axis = SwipeGesture.UNDECIDED
                verticalToParent = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (axis == SwipeGesture.UNDECIDED) {
                    axis = SwipeGesture.axis(event.x - downX, event.y - downY, slop)
                    if (axis == SwipeGesture.VERTICAL && maxY <= 0f) {
                        verticalToParent = true
                        SwipeGesture.cancel(event) { gestures.onTouchEvent(it) }
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                if (!verticalToParent) {
                    if (axis == SwipeGesture.HORIZONTAL) position.x += lastX - event.x
                    if (axis == SwipeGesture.VERTICAL) position.y += lastY - event.y
                    clamp(); invalidate()
                }
                lastX = event.x; lastY = event.y
            }
        }
        if (!verticalToParent) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) scroller.abortAnimation()
        }
        return true
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val x = position.x; val y = position.y
        val pageHeight = max(rowHeight, height - rowHeight)
        when (action) {
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT.id -> position.x -= width
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT.id -> position.x += width
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_UP.id -> position.y -= pageHeight
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN.id -> position.y += pageHeight
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD, AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> {
                val direction = if (action == AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD) 1 else -1
                if (maxY > 0) position.y += direction * pageHeight else position.x += direction * width
            }
            else -> return super.performAccessibilityAction(action, args)
        }
        scroller.abortAnimation()
        clamp()
        if (position.x == x && position.y == y) return false
        invalidate(); accessibility.invalidateRoot()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gainFocus, direction, previous)
        accessibility.onFocusChanged(gainFocus, direction, previous)
    }
    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val x = position.x; val y = position.y
        position.x += scroller.currX - flingX; position.y += scroller.currY - flingY
        flingX = scroller.currX; flingY = scroller.currY
        clamp()
        if (abs(position.x - x) < 0.1f && abs(position.y - y) < 0.1f) scroller.abortAnimation()
        postInvalidateOnAnimation()
    }
    override fun onDetachedFromWindow() { scroller.abortAnimation(); super.onDetachedFromWindow() }
}
