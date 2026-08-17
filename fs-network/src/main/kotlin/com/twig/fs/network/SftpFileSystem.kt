package com.twig.fs.network

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * 一个 SFTP 连接的配置;[keyPath] 非空时用私钥认证([password] 兼作私钥口令)。
 *
 * [knownHostKey] / [onLearnHostKey] 是主机密钥 TOFU(见 [SftpFileSystem.hostKeyVerifier]):
 * 空表示还没记住过,首次连上就把指纹交给 [onLearnHostKey] 让上层存起来;
 * 非空则必须匹配,不匹配拒绝连接。
 */
data class SftpConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String = "",
    val keyPath: String = "",
    val knownHostKey: String = "",
    val onLearnHostKey: (String) -> Unit = {},
)

/**
 * SFTP 文件系统,基于 SSHJ。
 *
 * 连接模型:SSH 握手开销大,保持单个持久连接、首次操作时懒连接;
 * SFTP 协议在单通道上按请求 ID 多路复用,流式读写与离散操作可并存。
 * 主机密钥走 TOFU(首次信任并记住,之后变了就拒绝),见 [hostKeyVerifier]。
 */
class SftpFileSystem(
    private val config: SftpConfig,
    override val scheme: String = SCHEME,
) : FileSystem {

    override val displayName: String = "SFTP (${config.host})"

    @Volatile private var client: SFTPClient? = null
    @Volatile private var ssh: SSHClient? = null

    /**
     * 主机密钥校验(TOFU:trust on first use)。
     *
     * 原来用的是 SSHJ 的 `PromiscuousVerifier` —— 任何主机密钥都接受。注释写的理由是
     * "文件管理器场景以可用性优先",但代价是局域网里的中间人可以无声接管每一条 SFTP
     * 连接,而这条连接上跑着用户的密码认证、文件传输和远程命令执行。
     *
     * 现在:第一次连上就记住指纹(交给 [SftpConfig.onLearnHostKey] 持久化),之后每次
     * 必须对得上;对不上直接拒绝并抛 [HostKeyChanged],由上层把两个指纹都显示出来让
     * 用户判断——这正是主机密钥变更时**应该**打断用户的场合(要么服务器重装了,要么
     * 正在被中间人劫持,两种情况用户都需要知道)。
     *
     * 没有做"要不要信任"的交互式弹框:那需要把一次阻塞 IO 拆成两段等 UI 回答,而
     * :fs-network 是纯 JVM 模块、够不到 UI。用户确认服务器确实换了密钥时,走服务器
     * 长按菜单里的"忘记主机密钥"重置即可。
     */
    class HostKeyChanged(val expected: String, val actual: String) :
        RuntimeException("host key changed: expected $expected, got $actual")

    // 不能写成 SAM lambda:HostKeyVerifier 有两个方法(verify + findExistingAlgorithms)
    private fun hostKeyVerifier() = object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
        override fun verify(hostname: String?, port: Int, key: java.security.PublicKey): Boolean {
            val fp = fingerprintOf(key)
            val known = config.knownHostKey
            return when {
                known.isEmpty() -> { config.onLearnHostKey(fp); true } // 首次见到:记住
                known == fp -> true
                else -> throw HostKeyChanged(known, fp)
            }
        }

        /** 不限定算法(返回空表 = 没有已知偏好),交给 SSHJ 自己协商。 */
        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
    }

    /**
     * 与 `ssh-keygen -lf` 一致的 SHA256 指纹(SSH 线格式取 SHA-256、base64、去掉补位
     * 的 '='),这样用户能拿它跟服务器上打印出来的那串直接对。
     * 取不到 SSH 线格式时退回 X.509 编码的十六进制——只跟自己存的值比,够用。
     */
    private fun fingerprintOf(key: java.security.PublicKey): String = runCatching {
        val wire = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(key).compactData
        "SHA256:" + b64(java.security.MessageDigest.getInstance("SHA-256").digest(wire))
    }.getOrElse {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(key.encoded)
        "X509:" + d.joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * 标准 base64 编码,不补 '='(ssh-keygen 的指纹就是这个形态)。
     * 自己写:这是纯 JVM 模块,没有 `android.util.Base64`;而 `java.util.Base64`
     * 要 API 26,minSdk 是 24 —— 与 `ResticCrypto.base64` 避开的是同一个坑。
     */
    private fun b64(data: ByteArray): String {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(alpha[b0 shr 2])
            sb.append(alpha[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) sb.append(alpha[((b1 and 0x0f) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            if (b2 >= 0) sb.append(alpha[b2 and 0x3f])
            i += 3
        }
        return sb.toString()
    }

    /** 新建并认证一条 SSH 连接(不复用),供 SFTP 与终端各自独立使用。 */
    private fun newAuthedClient(): SSHClient {
        val s = SSHClient()
        s.addHostKeyVerifier(hostKeyVerifier())
        try {
            s.connect(config.host, config.port)
            if (config.keyPath.isNotEmpty()) {
                val keys = if (config.password.isNotEmpty()) {
                    s.loadKeys(config.keyPath, config.password.toCharArray())
                } else {
                    s.loadKeys(config.keyPath)
                }
                s.authPublickey(config.user, keys)
            } else {
                s.authPassword(config.user, config.password)
            }
        } catch (e: Exception) {
            runCatching { s.disconnect() }
            // 主机密钥变了要单独报:这不是"连不上",是安全事件,得让用户看见两个指纹
            generateSequence(e as Throwable) { it.cause }.filterIsInstance<HostKeyChanged>()
                .firstOrNull()?.let {
                    throw FsException(
                        "Host key differs from the one remembered; connection refused.\n" +
                            "The server may have been reinstalled, or someone may be " +
                            "intercepting the connection. If you are sure it is fine, " +
                            "choose \"Forget host key\" in the server long-press menu.\n" +
                            "Remembered: ${it.expected}\nActual: ${it.actual}",
                        it,
                    )
                }
            e.printStackTrace() // logcat W/System.err:twig 排查 e.message 为空的连接失败原因
            throw FsException("SFTP connection failed: ${e::class.simpleName}: ${e.message}", e)
        }
        // 心跳:移动网络/NAT 对空闲连接通常几分钟就静默掐断,应用切后台时尤其明显;
        // 定时发心跳包续活连接,并能更快探测到真断线。
        s.connection.keepAlive.keepAliveInterval = 15
        return s
    }

    @Synchronized
    private fun cli(): SFTPClient {
        client?.let { if (ssh?.isConnected == true) return it }
        disconnect()
        val s = newAuthedClient()
        ssh = s
        return s.newSFTPClient().also { client = it }
    }

    /** 掉线后自动重连重试一次(息屏/网络休眠会断开持久连接)。 */
    private fun <T> retry(op: (SFTPClient) -> T): T = try {
        op(cli())
    } catch (e: Exception) {
        disconnect()
        op(cli())
    }

    /**
     * 在服务器上执行命令,返回 stdout;非零退出/失败返回 null。
     * 复用 SFTP 的持久 SSH 连接(每条命令一个 exec 会话),掉线自动重连一次。
     * 供上层跑远程 git 等服务端本地操作。
     */
    fun exec(cmd: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                synchronized(this) { cli() } // 确保已连接
                val session = ssh?.startSession() ?: return null
                try {
                    val c = session.exec(cmd)
                    val out = c.inputStream.readBytes()
                    c.join(30, java.util.concurrent.TimeUnit.SECONDS)
                    return if (c.exitStatus == 0) out else null
                } finally {
                    runCatching { session.close() }
                }
            } catch (e: Exception) {
                if (attempt == 1) return null
                synchronized(this) { disconnect() } // 重连后再试一次
            }
        }
        return null
    }

    /** 一次远程命令的完整结果(退出码 + 两路输出)。 */
    class ExecResult(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
    }

    /**
     * 执行一条命令,把退出码与 stdout/stderr 都带回来——[exec] 只在成功时给 stdout,
     * 用于能力探测够用,但给用户跑脚本必须能看到失败原因。
     *
     * 两点与 [exec] 不同:
     * - **用独立连接**(同 [openShell]):用户脚本可能跑很久,不该占着浏览文件那条
     *   共享连接,更不该在传大文件时互相拖累。
     * - **stdout/stderr 各起一条线程读**:单线程先读完 stdout 再读 stderr,对面
     *   写满 stderr 缓冲区就会双方僵住。
     */
    fun execFull(cmd: String, timeoutSec: Long = 900): ExecResult {
        val c = newAuthedClient()
        try {
            val session = c.startSession()
            try {
                val cmdCh = session.exec(cmd)
                val out = java.io.ByteArrayOutputStream()
                val err = java.io.ByteArrayOutputStream()
                val t1 = Thread({ runCatching { cmdCh.inputStream.copyTo(out) } }, "twig-exec-out")
                val t2 = Thread({ runCatching { cmdCh.errorStream.copyTo(err) } }, "twig-exec-err")
                t1.start(); t2.start()
                runCatching { cmdCh.join(timeoutSec, java.util.concurrent.TimeUnit.SECONDS) }
                t1.join(3000); t2.join(3000)
                return ExecResult(
                    cmdCh.exitStatus ?: -1,
                    out.toString(Charsets.UTF_8.name()),
                    err.toString(Charsets.UTF_8.name()),
                )
            } finally {
                runCatching { session.close() }
            }
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /**
     * 一条交互式 shell 会话(带 PTY,独立 SSH 连接),供 SSH 终端使用。
     * 所有出站操作(stdin 数据 / 窗口尺寸变更)经同一把锁串行化:
     * 并发写会搅乱加密流计数器,服务器 MAC 校验失败直接断 TCP(裸 EOF)。
     */
    class ShellSession internal constructor(
        private val ownClient: SSHClient,
        private val session: net.schmizz.sshj.connection.channel.direct.Session,
        private val shell: net.schmizz.sshj.connection.channel.direct.Session.Shell,
    ) : java.io.Closeable {
        private val writeLock = Any()
        private var lastCols = -1
        private var lastRows = -1

        val stdout: InputStream get() = shell.inputStream

        /** 写入远端 stdin(串行化)。 */
        fun write(buf: ByteArray, off: Int, len: Int) {
            synchronized(writeLock) {
                shell.outputStream.write(buf, off, len)
                shell.outputStream.flush()
            }
        }

        /**
         * 同步远端 PTY 尺寸(串行化;尺寸没变不发)。返回是否真的发出了 window-change。
         * ★ 不得在 Android 主线程调用:SSHJ 写 socket 前已推进包序号/加密流状态,
         * NetworkOnMainThreadException 抛出后连接即「中毒」,下一个包必断。
         * 失败不静默——历史上吞掉该异常导致根因排查绕了两大圈。
         */
        fun resize(cols: Int, rows: Int): Boolean {
            synchronized(writeLock) {
                if (cols == lastCols && rows == lastRows) return false
                lastCols = cols; lastRows = rows
                return try {
                    shell.changeWindowDimensions(cols, rows, 0, 0)
                    true
                } catch (e: Exception) {
                    System.err.println("twig: window-change failed: $e") // logcat W/System.err
                    false
                }
            }
        }

        val isOpen: Boolean get() = shell.isOpen

        /**
         * 远端 shell 的退出码;null = 没收到 exit-status。
         * `getExitStatus()` 声明在 `Session.Command` 上而不是 `Session`,不过
         * 实现类 `SessionChannel` 同时实现了 Session/Command/Shell,cast 即可取到。
         */
        val exitStatus: Int? get() = runCatching {
            (session as? net.schmizz.sshj.connection.channel.direct.Session.Command)?.exitStatus
        }.getOrNull()

        /**
         * 远端 shell 是不是**自己正常退出**的(`exit` / Ctrl+D),而不是连接断了——
         * 两者在 stdout 上都只表现为 EOF,得靠 SSH 协议层区分,否则正常退出也会
         * 被当掉线自动重连。
         *
         * 判据两条,满足其一即算正常:
         * - 收到了 `exit-status`(channel 正常关闭时服务端会发这条请求);
         * - 传输层还连着——channel 没了但 TCP/SSH transport 活着,只可能是远端把
         *   这条 shell 关了;真掉线的话 transport 一定也一起没了。
         *
         * stdout 的 EOF 可能早于 `exit-status` 到达,所以先等 channel 真正关闭
         * (最多 [waitMs]),别在消息还在路上时就下结论。
         */
        fun exitedCleanly(waitMs: Long = 2000): Boolean {
            runCatching { session.join(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            return exitStatus != null || ownClient.isConnected
        }

        override fun close() {
            runCatching { shell.close() }
            runCatching { session.close() }
            runCatching { ownClient.disconnect() }
        }
    }

    /**
     * 开一条交互式 shell(xterm-256color PTY)。用**独立的 SSH 连接**,
     * 与 SFTP 文件传输隔离——共用一条 transport 时终端输入会触发
     * "Broken transport EOF"(两个 channel 并发读写被服务器踢),
     * 且传大文件不会卡住终端。
     */
    fun openShell(cols: Int, rows: Int): ShellSession {
        val c = newAuthedClient()
        return try {
            val s = c.startSession()
            s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
            ShellSession(c, s, s.startShell())
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            throw FsException("Could not open terminal: ${e.message}", e)
        }
    }

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> =
        retry { it.ls(dir.path) }
            .map { info ->
                XFile(
                    scheme = scheme,
                    path = join(dir.path, info.name),
                    isDir = info.isDirectory,
                    size = if (info.isDirectory) 0L else info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                )
            }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })

    override fun openInput(file: XFile): InputStream {
        val rf = retry { it.open(file.path) }
        return object : FilterInputStream(rf.RemoteFileInputStream()) {
            override fun close() {
                try { super.close() } finally { runCatching { rf.close() } }
            }
        }
    }

    // SFTP 支持按偏移定位读(RemoteFile.read(offset,...)),不是 O(位置) 的重开跳过,
    // 所以缩略图对 MKV/mp4 可走"精确解析容器 + 只下必要片段"的路径(否则退化到只取
    // 时间 0 = 黑图)。
    override fun randomAccessEfficient(): Boolean = true

    override fun openRandom(file: XFile): RandomSource {
        val rf = retry { it.open(file.path) }
        return object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                rf.read(position, buffer, offset, length) // SSHJ:返回读到字节数,EOF 返回 -1
            override fun length(): Long = file.size
            override fun close() { runCatching { rf.close() } }
        }
    }

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        val modes = if (append) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        var chunk = FALLBACK_WRITE_CHUNK
        val rf = retry { c -> c.open(file.path, modes).also { chunk = writeChunk(c, it) } }
        val limit = chunk
        return object : FilterOutputStream(rf.RemoteFileOutputStream()) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                var pos = off
                var left = len
                while (left > 0) {
                    val n = minOf(left, limit)
                    out.write(b, pos, n)
                    pos += n
                    left -= n
                }
            }
            override fun close() {
                try { super.close() } finally { runCatching { rf.close() } }
            }
        }
    }

    /**
     * 一次 SFTP WRITE 能带多少数据。
     *
     * ★ 必须自己切块:SSHJ 的 `RemoteFileOutputStream.write(buf,off,len)` 把整个 len
     * 原样塞进**一个** SFTP WRITE 包(不像读那边会自然短读),而 OpenSSH `sftp-server`
     * 的 `SFTP_MAX_MSG_LENGTH` 是 256KB,超了不是回错误码而是
     * `error("bad message") + exit(11)` —— 子系统进程直接没,客户端下一次读包报
     * `EOF while reading packet`,看着像掉线。`CopyEngine.pipe` 的缓冲 0.21.1 从 64KB
     * 提到 1MB(为 SMB 提速)后,复制到 SFTP 的文件只要超过 1MB 就必挂,小文件反而正常。
     *
     * 取值同 SSHJ 官方上传器(`SFTPFileTransfer.Uploader`):通道协商的远端最大包
     * 减去 SFTP 请求头开销;OpenSSH 通常是 32KB。上下界兜底防服务器报离谱值。
     */
    private fun writeChunk(c: SFTPClient, rf: net.schmizz.sshj.sftp.RemoteFile): Int = runCatching {
        (c.sftpEngine.subsystem.remoteMaxPacketSize - rf.outgoingPacketOverhead)
            .coerceIn(8 * 1024, 128 * 1024)
    }.getOrDefault(FALLBACK_WRITE_CHUNK)

    override fun mkdir(parent: XFile, name: String): XFile {
        val path = join(parent.path, name)
        retry { it.mkdir(path) }
        return XFile(scheme, path, isDir = true)
    }

    override fun delete(file: XFile) {
        if (file.isDir) {
            for (child in list(file)) delete(child)
            retry { it.rmdir(file.path) }
        } else {
            retry { it.rm(file.path) }
        }
    }

    override fun rename(file: XFile, newName: String): XFile {
        val to = join(file.parentPath, newName)
        retry { it.rename(file.path, to) }
        return file.copy(path = to)
    }

    override fun exists(file: XFile): Boolean =
        runCatching { cli().statExistence(file.path) != null }.getOrDefault(false)

    override fun setModifiedTime(file: XFile, time: Long): Boolean = runCatching {
        val sec = time / 1000
        // SFTPv3 的 atime/mtime 是一对,协议里没有"只改 mtime"这回事;
        // 没有更好的 atime 来源,就让它跟着 mtime 一起走
        retry { it.setattr(file.path, net.schmizz.sshj.sftp.FileAttributes.Builder().withAtimeMtime(sec, sec).build()) }
        true
    }.getOrDefault(false)

    fun disconnect() {
        runCatching { client?.close() }
        runCatching { ssh?.disconnect() }
        client = null
        ssh = null
    }

    private fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    companion object {
        const val SCHEME = "sftp"

        /** 问不到协商值时的保守块大小(OpenSSH 的通道包上限就是 32KB)。 */
        private const val FALLBACK_WRITE_CHUNK = 32 * 1024 - 1024

        init {
            ensureFullBouncyCastle()
        }

        /**
         * Android 系统自带的 "BC" 是阉割版(缺 X25519/EdDSA 等),SSHJ 探测到它就不再
         * 注册依赖里带的完整版 BouncyCastle,导致 curve25519-sha256 握手报
         * "no such algorithm x25519"。这里把系统假 BC 顶掉换成完整版(追加注册,
         * 不抢 TLS 等其他算法的首选 Provider)。
         */
        private fun ensureFullBouncyCastle() {
            runCatching {
                val full = org.bouncycastle.jce.provider.BouncyCastleProvider()
                val cur = java.security.Security.getProvider("BC")
                if (cur == null || cur.javaClass !== full.javaClass) {
                    java.security.Security.removeProvider("BC")
                    java.security.Security.addProvider(full)
                }
            }
        }
    }
}
