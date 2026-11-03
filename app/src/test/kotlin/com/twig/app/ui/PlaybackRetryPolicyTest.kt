package com.twig.app.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackRetryPolicyTest {
    @Test fun `five actual attempts use increasing delays and then stop`() {
        val policy = PlaybackRetryPolicy()
        for (delay in listOf(1000L, 2000L, 4000L, 8000L, 15000L)) {
            assertEquals(delay, policy.nextDelayMs)
            assertTrue(policy.beginAttempt())
        }
        assertNull(policy.nextDelayMs)
        assertFalse(policy.beginAttempt())
        assertEquals(5, policy.attempts)
    }

    @Test fun `waiting offline never consumes the retry budget`() {
        val policy = PlaybackRetryPolicy()
        repeat(100) { policy.update(it * 100000L); assertEquals(1000L, policy.nextDelayMs) }
        assertEquals(0, policy.attempts)
    }

    @Test fun `brief playback buffering and pauses do not reset exhausted budget`() {
        val policy = PlaybackRetryPolicy()
        repeat(5) { policy.beginAttempt() }
        repeat(10) {
            val now = it * 40000L
            policy.onPlayingChanged(true, now)
            policy.onPlayingChanged(false, now + 29000)
            assertNull(policy.nextDelayMs)
        }
        policy.onPlayingChanged(true, 500000)
        policy.update(529999)
        assertNull(policy.nextDelayMs)
        policy.update(530000)
        assertEquals(1000L, policy.nextDelayMs)
    }

    @Test fun `manual restart grants a fresh budget without counting paused time`() {
        val policy = PlaybackRetryPolicy()
        repeat(5) { policy.beginAttempt() }
        policy.onPlayingChanged(true, 0)
        policy.reset()
        policy.beginAttempt()
        policy.update(100000)
        assertEquals(1, policy.attempts)
        assertEquals(2000L, policy.nextDelayMs)
    }
}
