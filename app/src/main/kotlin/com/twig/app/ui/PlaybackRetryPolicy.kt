package com.twig.app.ui

/** Counts actual reconnects, never offline waits. Only sustained playback earns a new budget. */
internal class PlaybackRetryPolicy {
    var attempts = 0
        private set
    private var playingSince: Long? = null

    val nextDelayMs: Long? get() = DELAYS.getOrNull(attempts)

    fun beginAttempt(): Boolean {
        if (nextDelayMs == null) return false
        attempts++
        return true
    }

    fun onPlayingChanged(playing: Boolean, nowMs: Long) {
        update(nowMs)
        if (!playing) playingSince = null
        else if (playingSince == null) playingSince = nowMs
    }

    fun update(nowMs: Long) {
        if (playingSince?.let { nowMs - it >= STABLE_PLAYBACK_MS } == true) attempts = 0
    }

    fun reset() {
        attempts = 0
        playingSince = null
    }

    companion object {
        val DELAYS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val STABLE_PLAYBACK_MS = 30_000L
    }
}
