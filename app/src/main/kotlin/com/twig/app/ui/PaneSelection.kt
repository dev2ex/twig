package com.twig.app.ui

import com.twig.core.XFile

/** Pane-owned checks survive view recreation; folding removes checks for rows that disappear. */
class PaneSelection {
    internal val keys = LinkedHashSet<String>()
    internal val files = LinkedHashMap<String, XFile>()
}
