package com.twig.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.LinearLayout

/** File search can be disabled while its independent web-search shortcut remains available. */
class SearchStripItem @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private val disabledGestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onLongPress(event: MotionEvent) {
            if (!isEnabled && isLongClickable) performLongClick()
        }
    })

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled && isLongClickable) {
            // Disabled views skip native click handling. Observe only this independent gesture;
            // the enclosing scroll view still intercepts movement and sends ACTION_CANCEL.
            disabledGestures.onTouchEvent(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
