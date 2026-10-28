package com.twig.app.ui

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/** A tap outside a handle ends sorting; scrolling and an active drag leave it alone. */
internal class FavoriteSortTouch {
    private var candidates = emptyList<PaneFragment>()
    private var downX = 0f
    private var downY = 0f

    fun dispatch(activity: AppCompatActivity, event: MotionEvent, dispatch: () -> Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                candidates = activity.supportFragmentManager.fragments.filterIsInstance<PaneFragment>()
                    .filter { it.viewModel.sortingFavorites && !it.isFavoriteDragHandleAt(downX, downY) }
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(activity).scaledTouchSlop
                if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) candidates = emptyList()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> candidates = emptyList()
        }
        val finish = if (event.actionMasked == MotionEvent.ACTION_UP) candidates.also { candidates = emptyList() }
            else emptyList()
        // Finish after the existing click dispatch so a disabled sorting row cannot
        // unexpectedly expand under the finger as its chevron comes back.
        val handled = dispatch()
        finish.forEach { it.finishFavoriteSorting() }
        return handled || finish.isNotEmpty()
    }
}
