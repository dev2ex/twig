package com.twig.core

/** Optional count-only enumeration; ordinary [FileSystem.list] must always remain complete. */
interface DirectoryCountSource {
    /**
     * Counts immediate children, with and without dotfiles. Values saturate at [limit].
     * May stop once both visible counts reach the limit; smaller values must be exact.
     * Returns null when [keepGoing] becomes false. Never closes a shared connection to cancel.
     */
    fun countChildren(dir: XFile, limit: Int, keepGoing: () -> Boolean): ChildCounts?
}

data class ChildCounts(
    val directories: Int, val files: Int,
    val visibleDirectories: Int, val visibleFiles: Int,
)
