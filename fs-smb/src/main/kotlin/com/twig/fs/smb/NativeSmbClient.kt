package com.twig.fs.smb

import com.twig.core.FsException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * libsmb2 的 JNI 绑定。一个实例 = 一个 smb2_context(到某共享的一条连接)。
 *
 * smb2_context 非线程安全且要求单线程访问——**所有原生调用都在一个专用单线程 executor 上执行**,
 * 从而彻底避免多线程(浏览 / 媒体预读 / FUSE 回调)进入 libsmb2 导致的堆损坏。
 * 息屏 POLLHUP 掉线时,操作失败会用记住的配置自动重连重试。
 *
 * **★ 最根本的实测坑(2026-07-21,缩略图上线后"经常崩溃"的真正元凶)**:分发到
 * 专用线程的成员函数原名叫 `run`,与 Kotlin 标准库的 `run { }` 同名同形状——在
 * openInput/openOutput 里的匿名 InputStream/OutputStream 内部类中,`run { }` 会被
 * 解析成标准库版本(直接在调用线程同步跑),而不是这个成员函数(已用独立单测验证:
 * 同结构的匿名内部类里,同名成员完全没被调用)。结果是 read()/write() 从这个类
 * 写下的第一天起就没被真正序列化到 smb-io 线程——缩略图并发起 2 个后台线程读不同
 * 网络文件、或缩略图读与媒体随机读/拷贝同时发生时,都是货真价实的多线程同时进
 * libsmb2,直接违反上面那条"单线程访问"的前提,堆损坏崩溃。**现已把成员函数改名
 * 为 [exec] 彻底消除同名歧义**——起名时避开 run/let/also/apply/with/use 这几个
 * stdlib 作用域函数名,防止重蹈覆辙。
 *
 * openInput/openOutput 的流对象会快照打开时的 handle(owner):缩略图等长耗时读取
 * 期间,若另一次并发调用因掉线触发了 reconnect()(销毁旧 context、换新 handle),
 * 流内持有的 fh 只在旧 context 里有效——继续拿新 handle 配旧 fh 调原生层是悬空指针,
 * 是缩略图/大文件传输偶发原生崩溃的根因之一。owner 与当前 handle 不一致时直接判失败
 * /跳过原生 close,不再跨 context 使用旧 fh。
 *
 * **实测坑(2026-07-21)**:libsmb2 对 handle=0(未连接/已断开)不做保护,直接传 0
 * 进原生层会在 nativeOpenFile 等函数里空指针解引用崩溃(tombstone 显示
 * `Java_com_twig_fs_smb_NativeSmbClient_nativeOpenFile`,fault addr 0x14,即对
 * NULL context 取偏移字段)。真实触发路径:一次 [reconnect] 因故失败(弱网/服务器
 * 短暂不可达)后 handle 停留在 0——但对应的 scheme 仍在 `FsRegistry` 里"注册着",
 * UI/缩略图后续每次操作都会再次落到这同一个 client 上,原来的代码不会在调用前
 * 检查 handle 就直接传给原生函数,必崩;崩溃后重启又立刻复现同一崩溃(handle 依旧
 * 是 0)。现在所有原生调用入口先经 [ensureConnected]——handle=0 时先按记住的配置
 * 补一次 [reconnect],还是失败才抛 [FsException],绝不把 0 传进原生层。
 */
class NativeSmbClient : AutoCloseable {

    @Volatile private var handle: Long = 0L
    @Volatile private var config: SmbConfig? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "smb-io").apply { isDaemon = true }
    }

    val isConnected: Boolean get() = handle != 0L

    data class Entry(val name: String, val isDir: Boolean, val size: Long, val mtimeSec: Long)

    /** 在专用线程上执行原生调用并等待结果(异常透传)。**命名故意避开 `run`**:
     * 这个函数原名就叫 run,openInput/openOutput 匿名 InputStream/OutputStream 内部类
     * 里的 `run { }` 会被 Kotlin 解析成标准库的 `kotlin.run`(直接在调用线程同步执行),
     * 而不是这个成员函数——已用单测验证(同结构的匿名内部类里,同名成员函数完全没被
     * 调到,始终落在调用线程而非 io 线程)。后果是读写完全绕开了单线程序列化,对
     * 非线程安全的 smb2_context 造成真·并发访问,这才是 SMB 缩略图/大文件传输
     * 偶发原生崩溃的**真正**根因,比 handle=0 那处更根本。改名彻底消除同名歧义。 */
    private fun <T> exec(block: () -> T): T =
        try {
            io.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: FsException("SMB operation failed", e)
        }

    /** 已断开(handle=0)时先用记住的配置试一次重连,仍失败才抛异常——绝不把 0
     * 传进原生层(见类注释"实测坑")。handle 本来就有效时零开销直接放行。 */
    private fun ensureConnected() {
        if (handle == 0L && !reconnect()) throw FsException("SMB connection is down")
    }

    private fun errText(): String = if (handle == 0L) "SMB connection is down" else nativeGetLastError(handle)

    /**
     * 上一个原生错误是不是"服务器答复了、但拒绝了"(权限/占用一类),区别于掉线。
     * libsmb2 只把 NT 状态拼进错误串,没有独立错误码可取,只能认串。认不出就当
     * 掉线处理(退回原来的重连重试行为),多重连一次无害。
     */
    private fun deniedByServer(): Boolean {
        if (handle == 0L) return false
        val e = errText()
        return e.contains("ACCESS_DENIED") || e.contains("SHARING_VIOLATION") ||
            e.contains("MEDIA_WRITE_PROTECTED")
    }

    /** 断掉死连接并用配置重连(仅在 io 线程内调用);返回是否成功。 */
    private fun reconnect(): Boolean {
        val c = config ?: return false
        val old = handle; handle = 0L
        if (old != 0L) runCatching { nativeDisconnect(old) }
        val h = nativeConnect(c.host, c.share, c.user, c.password, c.domain)
        handle = h
        return h != 0L
    }

    fun connect(c: SmbConfig) = exec {
        config = c
        val h = nativeConnect(c.host, c.share, c.user, c.password, c.domain)
        if (h == 0L) throw FsException("SMB connection failed: ${c.host}/${c.share}")
        handle = h
    }

    /** 协商的 SMB 方言代码(如 0x0311 = 3.1.1);未连接返回 0。 */
    fun dialect(): Int = exec { if (handle == 0L) 0 else nativeDialect(handle) }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { io.submit { nativeDisconnect(h) }.get() }
        io.shutdownNow()
    }

    /**
     * 硬关闭:只销毁 context(不 logoff、不逐个 smb2_close)。
     * smb2_close / smb2_disconnect_share 会在收尾时跑 wait_for_reply 读 socket,
     * 曾在此处释放陈旧 reply pdu 触发堆崩溃;媒体播放的专用连接用此收尾以彻底绕开。
     */
    fun closeHard() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { io.submit { nativeDestroy(h) }.get() }
        io.shutdownNow()
    }

    fun list(path: String): List<Entry> = exec {
        ensureConnected()
        var raw = nativeListDirectory(handle, path)
        if (raw == null && reconnect()) raw = nativeListDirectory(handle, path)
        val lines = raw ?: throw FsException("List failed: ${errText()}")
        lines.map { line ->
            val p = line.split('\t')
            Entry(
                name = p[0],
                isDir = p.getOrNull(1) == "1",
                size = p.getOrNull(2)?.toLongOrNull() ?: 0L,
                mtimeSec = p.getOrNull(3)?.toLongOrNull() ?: 0L,
            )
        }
    }

    fun openInput(path: String): InputStream {
        val fh = openReadHandle(path)
        if (fh == 0L) throw FsException("Open failed ($path): ${errText()}")
        // fh 只在打开时的 context(owner)里有效。缩略图等长耗时读取跨越较长时间,
        // 期间若因掉线(POLLHUP)由另一次并发调用触发了 reconnect(),owner 对应的
        // 原生 context 已被销毁——继续拿新 handle 配旧 fh 调原生层会用到悬空指针,
        // 是 SMB 缩略图/大文件读取偶发原生崩溃的根因之一。这里用 owner 快照发现
        // 错位就直接判读取失败(交给上层重试整次操作),不越权调用。
        val owner = handle
        return object : InputStream() {
            private var closed = false
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int = exec {
                if (handle != owner) throw FsException("Connection was re-established; read aborted")
                val n = nativeReadFile(owner, fh, b, off, len)
                if (n < 0) throw FsException("Read failed: ${errText()}")
                if (n == 0) -1 else n
            }
            override fun close() {
                if (!closed) {
                    closed = true
                    if (handle == owner) closeHandle(fh) // context 已换过,fh 早随旧 context 失效,不再调原生 close
                }
            }
        }
    }

    fun openOutput(path: String): OutputStream {
        val fh = exec {
            ensureConnected()
            var f = nativeOpenWrite(handle, path)
            // 服务器明确拒绝(权限不足)时重连也不会变好,反而白断一次连接、
            // 让后续操作都得重新握手;只有疑似掉线才值得重连重试。
            if (f == 0L && !deniedByServer() && reconnect()) f = nativeOpenWrite(handle, path)
            f
        }
        if (fh == 0L) throw FsException("Open for write failed ($path): ${errText()}")
        val owner = handle // 同 openInput:防止跨 reconnect 用旧 fh 配新 context
        return object : OutputStream() {
            private var closed = false
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var w = 0
                while (w < len) {
                    if (handle != owner) throw FsException("Connection was re-established; write aborted")
                    val n = exec { nativeWriteFile(owner, fh, b, off + w, len - w) }
                    if (n <= 0) throw FsException("Write failed: ${errText()}")
                    w += n
                }
            }
            override fun close() {
                if (!closed) {
                    closed = true
                    if (handle == owner) closeHandle(fh)
                }
            }
        }
    }

    // 定位读(供媒体播放器随机 seek):
    fun openReadHandle(path: String): Long = exec {
        ensureConnected()
        var fh = nativeOpenFile(handle, path)
        if (fh == 0L && reconnect()) fh = nativeOpenFile(handle, path)
        fh
    }

    /**
     * 定位读。原生层约定:n<0 = 出错,n==0 = 真 EOF,n>0 = 读到的字节数。
     *
     * **失败必须抛异常,不能返回 -1**:调用方([com.twig.core.RandomSource])的契约里
     * -1 就是 EOF,原来"掉线也返回 -1"会让播放器/缩略图/压缩包解析把「连接断了」
     * 理解成「文件到头了」——视频提前结束、缩略图黑图、压缩包报"条目损坏",全都不
     * 指向真实原因。与 [openInput] 里 `handle != owner` 直接判失败的处理保持一致。
     *
     * ★ 这里**故意不调 [ensureConnected]**(其它入口都调):[fh] 是在当前 context 上
     * 开的,重连会销毁旧 context、换来新 handle,旧 fh 随之失效——再把它传进原生层
     * 就是悬空指针(`nativePread` 只判 `!handle || !fhHandle`,不校验二者是否配对),
     * 正是 [openInput]/[openOutput] 用 owner 快照要防的那件事。断了就报错,让上层
     * 重开整个 [openRandom]。
     */
    fun pread(fh: Long, offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int = exec {
        if (handle == 0L) throw FsException("SMB connection is down")
        val n = nativePread(handle, fh, offset, buf, bufOffset, length)
        if (n < 0) throw FsException("SMB random read failed: ${errText()}")
        n
    }

    fun closeHandle(fh: Long) = exec { if (fh != 0L && handle != 0L) nativeCloseFile(handle, fh) }

    fun mkdir(path: String) = call { nativeMkdir(handle, path) }
    fun delete(path: String, isDir: Boolean) = call { nativeDelete(handle, path, isDir) }
    fun rename(from: String, to: String) = call { nativeRename(handle, from, to) }

    private inline fun call(crossinline op: () -> Int) = exec {
        ensureConnected()
        if (op() < 0) {
            if (!reconnect() || op() < 0) throw FsException(errText())
        }
    }

    // ── JNI ──
    private external fun nativeConnect(host: String, share: String, user: String, password: String, domain: String): Long
    private external fun nativeDialect(handle: Long): Int
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeListDirectory(handle: Long, path: String): Array<String>?
    private external fun nativeOpenFile(handle: Long, path: String): Long
    private external fun nativeGetFileSize(handle: Long, fh: Long): Long
    private external fun nativeReadFile(handle: Long, fh: Long, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeOpenWrite(handle: Long, path: String): Long
    private external fun nativeWriteFile(handle: Long, fh: Long, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeCloseFile(handle: Long, fh: Long)
    private external fun nativePread(handle: Long, fh: Long, offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int
    private external fun nativeMkdir(handle: Long, path: String): Int
    private external fun nativeDelete(handle: Long, path: String, isDir: Boolean): Int
    private external fun nativeRename(handle: Long, from: String, to: String): Int
    private external fun nativeGetLastError(handle: Long): String

    companion object {
        init { System.loadLibrary("samba_jni") }
    }
}
