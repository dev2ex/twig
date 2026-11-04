package com.termux.view

/** Same-package access to Termux's selection controller; no clipboard round trip. */
object TermSelection {
    fun text(view: TerminalView): String {
        if (!view.isSelectingText) return ""
        val selectors = IntArray(4)
        view.getTextSelectionCursorController().getSelectors(selectors)
        // Termux returns y1, y2, x1, x2, whereas the emulator expects x1, y1, x2, y2.
        return view.mEmulator?.getSelectedText(selectors[2], selectors[0], selectors[3], selectors[1]).orEmpty()
    }
}
