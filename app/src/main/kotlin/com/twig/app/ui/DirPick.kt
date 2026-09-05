package com.twig.app.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.twig.core.isWritableDir
import kotlinx.coroutines.launch

/**
 * Common part of "embed a panel and let the user tap a directory before pressing OK".
 *
 * Two places do the same thing:
 * - [ShareTargetActivity] — other apps share in, "copy to here"
 * - [PickerActivity]'s directory-pick mode — backup "Save to"
 *
 * Extracted because **relying on convention lets them fork**: when these two were
 * originally each written separately, one disabled the button in real time while the
 * other "tapped and then toasted that it was not allowed". The former is clearly
 * better (the user knows before pressing), and the fork itself has no reason — it is
 * purely the result of separate implementations.
 *
 * The test is [isWritableDir] rather than `isDir`: directories on read-only sources
 * (media servers, restic, 7z/RAR, git viewer, "Apps") can be entered but you cannot
 * write into them.
 */
fun AppCompatActivity.enableOnWritableDir(pane: PaneFragment, button: View) {
    // Start by laying it out as disabled, until the panel reports its first writable directory
    setEnabledDim(button, false)
    val vm = pane.viewModel
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.state.collect { st -> setEnabledDim(button, st.currentDir?.isWritableDir() == true) }
        }
    }
}

/**
 * ★ Setting only `alpha` is not enough — it looks grayed out, but tapping still
 * triggers the action. `isEnabled = false` is what makes `View.onTouchEvent` not
 * dispatch the click. Both are needed.
 */
private fun setEnabledDim(v: View, enabled: Boolean) {
    v.isEnabled = enabled
    v.alpha = if (enabled) 1f else 0.4f
}
