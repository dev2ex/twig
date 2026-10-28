package com.twig.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ViewPanesBinding

/** Shared two-pane navigation and action strip for file and destination pickers. */
internal class PickerBrowser(
    private val activity: AppCompatActivity,
    private val toolbar: Toolbar,
    private val browser: ViewPanesBinding,
    state: Bundle?,
    private val tag: (Int) -> String,
    private val createPane: (Int) -> PaneFragment = { PaneFragment.newInstance(it) },
    private val onPaneChanged: () -> Unit,
) {
    var activeIndex = state?.getInt(STATE_PANE) ?: 0
        private set
    val isLandscape: Boolean
        get() = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pane: PaneFragment? get() = paneAt(activeIndex)

    init {
        for (strip in listOf(browser.stripLeft, browser.stripMid, browser.stripRight)) {
            strip.sizeIconsLikeRows(activity)
            strip.asCompress.visibility = View.GONE
            strip.asShare.visibility = View.GONE
            strip.asCopy.visibility = View.GONE
            strip.asMove.visibility = View.GONE
            strip.asRename.visibility = View.GONE
            strip.asDelete.visibility = View.GONE
            strip.asCompare.visibility = View.GONE
            strip.asMap.visibility = View.GONE
            strip.asUp.setOnClickListener { pane?.actionUp() }
            strip.asRefresh.setOnClickListener { pane?.actionRefresh() }
            strip.asNewFolder.setOnClickListener { pane?.actionNewFolder() }
            strip.asSort.setOnClickListener {
                SortDialog.show(activity, pane?.viewModel?.currentDir,
                    parentOf = { pane?.viewModel?.directoryViewParent(it) }) {
                    for (i in 0..1) paneAt(i)?.refreshSortAndView()
                }
            }
            strip.asSearch.setOnClickListener { pane?.actionSearch() }
            strip.asHistory.setOnClickListener { pane?.actionHistory() }
        }
        PaneSwitch.add(toolbar.menu, toolbar, MENU_SWITCH, activeIndex,
            onSwitch = { switchPane(1 - activeIndex, animate = true) })
    }

    fun paneAt(index: Int): PaneFragment? =
        activity.supportFragmentManager.findFragmentByTag(tag(index)) as? PaneFragment

    fun initPanes() {
        val transaction = activity.supportFragmentManager.beginTransaction()
        for (i in 0..1) {
            if (paneAt(i) == null) transaction.add(if (i == 0) R.id.pane_a else R.id.pane_b, createPane(i), tag(i))
        }
        transaction.commitNow()
        switchPane(activeIndex)
    }

    fun saveState(state: Bundle) { state.putInt(STATE_PANE, activeIndex) }

    fun switchPane(index: Int, animate: Boolean = false) {
        activeIndex = index
        val land = isLandscape
        browser.paneA.visibility = View.VISIBLE
        browser.paneB.visibility = View.VISIBLE
        browser.stripLeft.root.visibility = View.GONE
        browser.stripMid.root.visibility = if (land) View.VISIBLE else View.GONE
        browser.stripRight.root.visibility = if (land) View.GONE else View.VISIBLE
        for (strip in listOf(browser.stripLeft, browser.stripMid, browser.stripRight)) {
            strip.asGrid.setColumns(if (land) 2 else 1)
            strip.asGrid.dividers = Prefs.rowDivider(activity)
            strip.root.layoutParams = strip.root.layoutParams.apply {
                width = ((if (land) 104 else 52) * activity.resources.displayMetrics.density).toInt()
            }
        }
        for (i in 0..1) paneAt(i)?.setActive(i == activeIndex)
        browser.root.showPane(activeIndex, animate && !land)
        PaneSwitch.update(toolbar.menu.findItem(MENU_SWITCH), toolbar, activeIndex)
        onPaneChanged()
    }

    fun onPaneTouched(pane: PaneFragment) {
        if (isLandscape && pane.paneIndex != activeIndex) switchPane(pane.paneIndex)
    }

    fun onPaneSwipe(velocityX: Float) {
        if (!isLandscape) switchPane(if (velocityX < 0) 1 else 0, animate = true)
    }

    fun refreshTrees() { for (i in 0..1) paneAt(i)?.viewModel?.refreshTree() }

    private companion object {
        const val MENU_SWITCH = 2
        const val STATE_PANE = "picker_browser_active_pane"
    }
}
