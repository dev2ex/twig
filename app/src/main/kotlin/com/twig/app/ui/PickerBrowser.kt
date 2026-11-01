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
    private var appliedDualPane: Boolean? = null
    private var appliedLandscape: Boolean? = null
    val pane: PaneFragment? get() = paneAt(activeIndex)

    init {
        for (strip in listOf(browser.stripLeft, browser.stripMid, browser.stripRight)) {
            strip.sizeIconsLikeRows(activity)
            strip.asCompress.visibility = View.GONE
            strip.asShare.visibility = View.GONE
            strip.asCopy.visibility = View.GONE
            strip.asDuplicate.visibility = View.GONE
            strip.asMove.visibility = View.GONE
            strip.asRename.visibility = View.GONE
            strip.asDelete.visibility = View.GONE
            strip.asCompare.visibility = View.GONE
            strip.asMap.visibility = View.GONE
            strip.asInfo.setOnClickListener { pane?.actionInfo() }
            strip.asInfo.setOnLongClickListener { pane?.actionInfo(secondTab = true); true }
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
        refreshStripItems()
        val land = isLandscape
        val dual = Prefs.dualPane(activity)
        appliedDualPane = dual
        appliedLandscape = land
        browser.paneA.visibility = View.VISIBLE
        browser.paneB.visibility = View.VISIBLE
        browser.stripLeft.root.visibility = View.GONE
        browser.stripMid.root.visibility = if (dual) View.VISIBLE else View.GONE
        browser.stripRight.root.visibility = if (dual) View.GONE else View.VISIBLE
        for (strip in listOf(browser.stripLeft, browser.stripMid, browser.stripRight)) {
            strip.asGrid.setColumns(if (land) 2 else 1)
            strip.asGrid.dividers = Prefs.rowDivider(activity)
            strip.root.layoutParams = strip.root.layoutParams.apply {
                width = ((if (land) 104 else 52) * activity.resources.displayMetrics.density).toInt()
            }
        }
        for ((i, container) in listOf(browser.paneA, browser.paneB).withIndex()) {
            paneAt(i)?.setActive(i == activeIndex)
            val hidden = !dual && i != activeIndex
            container.descendantFocusability = if (hidden) android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                else android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            if (hidden) container.clearFocus()
            container.importantForAccessibility = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        browser.root.showPane(activeIndex, animate && !dual)
        browser.root.requestLayout()
        PaneSwitch.update(toolbar.menu.findItem(MENU_SWITCH), toolbar, activeIndex)
        onPaneChanged()
    }

    fun refreshLayout() {
        if (appliedDualPane != Prefs.dualPane(activity) || appliedLandscape != isLandscape) switchPane(activeIndex)
        else refreshStripItems()
    }

    fun refreshStripItems() {
        val available = setOf("up", "sort", "refresh", "new_folder", "info", "search", "history")
        for (strip in listOf(browser.stripLeft, browser.stripMid, browser.stripRight)) strip.asGrid.applyItems(available)
    }

    fun onPaneTouched(pane: PaneFragment) {
        if (Prefs.dualPane(activity) && pane.paneIndex != activeIndex) switchPane(pane.paneIndex)
    }

    fun onPaneSwipe(velocityX: Float) {
        if (!Prefs.dualPane(activity)) switchPane(if (velocityX < 0) 1 else 0, animate = true)
    }

    fun refreshTrees() { for (i in 0..1) paneAt(i)?.viewModel?.refreshTree() }

    private companion object {
        const val MENU_SWITCH = 2
        const val STATE_PANE = "picker_browser_active_pane"
    }
}
