package com.twig.app

import android.content.Context
import com.twig.core.FsRegistry
import com.twig.fs.network.DavConfig
import com.twig.fs.network.FtpConfig
import com.twig.fs.network.FtpFileSystem
import com.twig.fs.network.S3Config
import com.twig.fs.network.S3FileSystem
import com.twig.fs.network.SftpConfig
import com.twig.fs.network.SftpFileSystem
import com.twig.fs.network.WebDavFileSystem
import com.twig.fs.smb.SmbConfig
import com.twig.fs.smb.SmbFileSystem

/**
 * 集中的"连接 → 已注册 scheme"解析,供播放服务等 PaneViewModel 之外的场景复用连接建立
 * 逻辑。scheme 名与 [ui.PaneViewModel.schemeForConn] 同一算法(确定性 hash),因此用户已在
 * 树上展开过的服务器,这里直接复用同一条已注册连接、不再重连。
 */
object Connections {

    /** 某连接对应的确定性 scheme。 */
    fun schemeOf(conn: SavedConnection): String =
        conn.type + Integer.toHexString(conn.label().hashCode())

    private val byScheme = java.util.concurrent.ConcurrentHashMap<String, SavedConnection>()

    /**
     * scheme → 连接的**全进程**反查([ensure] 里登记,浏览过的服务器必然登记过)。
     *
     * `PaneViewModel` 自己也有一份 `schemeToConn`,但只记**本面板**展开过的服务器:
     * 复制/压缩确认框显示的是**对侧**面板的目标目录,查自己那份必然落空,服务器名
     * 就没了——路径退化成 `sftp:/backup`,与最近位置的 `sftp:/nas/backup` 对不上。
     */
    fun ofScheme(scheme: String): SavedConnection? = byScheme[scheme]

    /**
     * 确保该连接已注册进 FsRegistry 并返回其 scheme;已注册则复用不重连。
     * 阻塞 IO,须在工作线程。
     *
     * **建立连接的逻辑只有这一份**。以前 [ui.PaneViewModel.schemeForConn] 还有一份
     * 几乎一样的拷贝,两边靠注释约定 scheme 算法保持一致,而且已经开始分叉了
     * (那份多记一次 SMB 方言)。新增一种连接类型只该改这里。
     *
     * @param onInfo 连上后拿到的补充信息(目前只有 SMB 协商的方言,如 "SMB3.1.1"),
     *   供树上的服务器行显示;不关心就不传。
     */
    fun ensure(ctx: Context, conn: SavedConnection, onInfo: (String) -> Unit = {}): String {
        val s = schemeOf(conn)
        byScheme[s] = conn // 反查表:已注册的直接返回,登记要放在这之前
        if (FsRegistry.all().any { it.scheme == s }) return s
        when (conn.type) {
            "smb" -> {
                val fs = SmbFileSystem(
                    SmbConfig(conn.host, conn.share, conn.user, conn.password, conn.domain), s,
                )
                fs.connect()
                fs.dialectName()?.let(onInfo)
                FsRegistry.register(fs)
            }
            "sftp" -> FsRegistry.register(
                SftpFileSystem(
                    SftpConfig(
                        conn.host, conn.port, conn.user, conn.password, keyPath = conn.keyPath,
                        knownHostKey = conn.hostKey,
                        onLearnHostKey = { fp -> rememberHostKey(ctx, conn, fp) },
                    ),
                    s,
                ),
            )
            "webdav" -> FsRegistry.register(WebDavFileSystem(DavConfig(conn.host, conn.user, conn.password), s))
            "s3" -> FsRegistry.register(
                S3FileSystem(
                    S3Config(
                        endpoint = conn.host, accessKey = conn.user, secretKey = conn.password,
                        region = conn.region, bucket = conn.share, pathStyle = conn.pathStyle,
                    ),
                    s,
                ),
            )
            else -> FsRegistry.register(FtpFileSystem(FtpConfig(conn.host, conn.port, conn.user, conn.password), s))
        }
        return s
    }

    /**
     * 首次连上某台 SFTP 时把主机密钥指纹记下来(TOFU)。
     * 从 store 里重新读一份再改,别拿手上这份可能已经过期的快照去覆盖用户
     * 中途改过的其它字段。由 SSH 握手线程回调,SharedPreferences.apply() 任意线程安全。
     */
    private fun rememberHostKey(ctx: Context, conn: SavedConnection, fp: String) {
        val cur = ConnectionStore.all(ctx).firstOrNull { it.label() == conn.label() } ?: return
        if (cur.hostKey == fp) return
        ConnectionStore.save(ctx, cur.copy(hostKey = fp))
    }

    fun find(ctx: Context, label: String): SavedConnection? =
        ConnectionStore.all(ctx).firstOrNull { it.label() == label }
}
