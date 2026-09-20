package com.termux.terminal

/**
 * Same-package bridge: ByteQueue (TerminalSession's keyboard input queue) is package-private,
 * so we use the com.termux.terminal package name to expose a read entry point to Twig's SSH terminal.
 */
object TermBridge {

    private fun inputQueue(session: TerminalSession): ByteQueue {
        val f = TerminalSession::class.java.getDeclaredField("mTerminalToProcessIOQueue")
        f.isAccessible = true
        return f.get(session) as ByteQueue
    }

    /** Returns the blocking reader for this session's "terminal→process" queue; returns <=0 when the queue is closed. */
    fun inputReader(session: TerminalSession): (ByteArray) -> Int {
        val q = inputQueue(session)
        return { buf -> q.read(buf, true) }
    }

    /**
     * Close the "terminal→process" queue.
     *
     * ★ The queue holds 4096 bytes and `ByteQueue.write` **waits forever** once it is full — and the caller
     * writing into it is the **UI thread** (key strokes, and under a mouse-reporting program like htop every
     * scroll gesture). Closing it makes those writes return instead of hanging, and makes the bridge thread's
     * blocking read return -1 so it can end. Without this, a session whose bridge thread is gone freezes the
     * whole app as soon as 4096 bytes of input pile up.
     */
    fun closeInput(session: TerminalSession) {
        inputQueue(session).close()
    }
}
