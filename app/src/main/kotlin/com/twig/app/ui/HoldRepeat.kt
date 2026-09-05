package com.twig.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.view.MotionEvent
import android.view.View

/**
 * Hold-to-repeat for an accessory key: a physical keyboard keeps sending while a key is
 * held down, and the terminal's arrow keys are where that matters (moving the caret or
 * scrolling a pager one tap at a time is the complaint).
 *
 * ★ Everything runs off the touch listener and the view gets **no click listener**: with
 * both, the click that lands on ACTION_UP would add one extra keypress after every long
 * press. The listener therefore consumes the gesture (returns true) and drives the
 * pressed state itself, and calls `performClick()` on release purely so accessibility
 * services still hear a click.
 */
object HoldRepeat {

    /** How long the key must be held before it starts repeating (Android's own delay). */
    const val DELAY_MS = 400L

    /** Interval between repeats once it has started. */
    const val INTERVAL_MS = 60L

    @SuppressLint("ClickableViewAccessibility")
    fun install(view: View, handler: Handler, fire: () -> Unit) {
        var tick: Runnable? = null
        fun stop() {
            tick?.let { handler.removeCallbacks(it) }
            tick = null
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stop()
                    v.isPressed = true
                    fire() // the first one is the tap; repeats only start after DELAY_MS
                    val repeat = object : Runnable {
                        override fun run() {
                            fire()
                            handler.postDelayed(this, INTERVAL_MS)
                        }
                    }
                    tick = repeat
                    handler.postDelayed(repeat, DELAY_MS)
                }
                // Stop when the finger slides off the button: otherwise we'd keep firing a key the user is no longer pressing until release
                MotionEvent.ACTION_MOVE ->
                    if (e.x < 0f || e.y < 0f || e.x > v.width || e.y > v.height) {
                        v.isPressed = false
                        stop()
                    }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    stop()
                    v.performClick() // no click listener is set; this is for a11y only
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stop()
                }
            }
            true
        }
    }
}
