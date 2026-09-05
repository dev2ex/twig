package com.termux.terminal

/**
 * Same-package bridge: ByteQueue (TerminalSession's keyboard input queue) is package-private,
 * so we use the com.termux.terminal package name to expose a read entry point to Twig's SSH terminal.
 */
object TermBridge {

    /** Returns the blocking reader for this session's "terminal→process" queue; returns <=0 when the queue is closed. */
    fun inputReader(session: TerminalSession): (ByteArray) -> Int {
        val f = TerminalSession::class.java.getDeclaredField("mTerminalToProcessIOQueue")
        f.isAccessible = true
        val q = f.get(session) as ByteQueue
        return { buf -> q.read(buf, true) }
    }
}
