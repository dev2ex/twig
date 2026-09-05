package com.twig.app.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A frame that can **freeze the size of the terminal view inside it** while the window
 * is relaid out.
 *
 * termux resizes the emulator straight from `TerminalView.onSizeChanged`, and resizing
 * the alternate screen buffer (where tmux, htop and vim live) destroys what is on it —
 * the peer is expected to repaint on SIGWINCH. That contract breaks when the window is
 * laid out **twice in quick succession and ends up back where it started**, which is
 * exactly what a screen off/on cycle does: the IME is dismissed and restored, and the
 * status bar comes back for the keyguard and is hidden again by `applyFullscreen`. The
 * local buffer is wrecked by the round trip while the peer either never hears about it
 * (the SSH resize is debounced, and an A→B→A round trip de-duplicates to nothing) or
 * sees no net change and so repaints nothing. The screen then stays garbled until
 * *some* later size change makes the peer redraw — hence "hide and show the keyboard
 * and it comes back".
 *
 * `TerminalView` is `final`, so the size cannot be gated inside it; instead this parent
 * keeps handing the child the size it already has, so `onSizeChanged` never fires at
 * all. [TerminalActivity] lifts the freeze once the layout has settled, and the final
 * size is then applied in one step — a no-op when it is the size we started from.
 */
class TermSizeFreezeLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    /** While true, the child keeps its current width/height whatever the window does. */
    var frozen = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) requestLayout() // apply whatever the window settled on, once
        }

    private fun frozenChild() =
        getChildAt(0)?.takeIf { frozen && it.width > 0 && it.height > 0 }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val child = frozenChild()
        if (child == null) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthSpec),
            getDefaultSize(suggestedMinimumHeight, heightSpec),
        )
        child.measure(
            MeasureSpec.makeMeasureSpec(child.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(child.height, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = frozenChild()
        if (child == null) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }
}
