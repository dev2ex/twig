package com.termux.terminal

/**
 * 同包桥接:ByteQueue(TerminalSession 的键盘输入队列)是包私有的,
 * 这里以 com.termux.terminal 包名提供一个读取入口给 Twig 的 SSH 终端。
 */
object TermBridge {

    /** 返回该会话"终端→进程"队列的阻塞读函数;队列关闭时返回 <=0。 */
    fun inputReader(session: TerminalSession): (ByteArray) -> Int {
        val f = TerminalSession::class.java.getDeclaredField("mTerminalToProcessIOQueue")
        f.isAccessible = true
        val q = f.get(session) as ByteQueue
        return { buf -> q.read(buf, true) }
    }
}
