package com.twig.app

/** Opening end is absolute; ending length follows episodes with different runtimes. */
data class EpisodeSkip(val headMs: Long = 0, val tailMs: Long = 0) {
    fun headEnd(durationMs: Long): Long = headMs.takeIf {
        durationMs > 0 && it > 0 && it < durationMs && it < durationMs - tailMs
    } ?: 0

    fun tailStart(durationMs: Long): Long? = (durationMs - tailMs).takeIf {
        durationMs > 0 && tailMs > 0 && it > headMs && it > 0
    }
}
