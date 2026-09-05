package com.twig.app

import android.content.Context
import com.twig.core.FsRegistry
import com.twig.fs.network.DavConfig
import com.twig.fs.network.FtpConfig
import com.twig.fs.network.FtpFileSystem
import com.twig.fs.network.JellyfinConfig
import com.twig.fs.network.JellyfinFileSystem
import com.twig.fs.network.S3Config
import com.twig.fs.network.S3FileSystem
import com.twig.fs.network.SftpConfig
import com.twig.fs.network.SftpFileSystem
import com.twig.fs.network.WebDavFileSystem
import com.twig.fs.smb.SmbConfig
import com.twig.fs.smb.SmbFileSystem

/**
 * Centralised "connection → registered scheme" resolution, so places outside PaneViewModel
 * (e.g. the playback service) can reuse the connection-establishment logic. The scheme
 * name uses the same algorithm as [ui.PaneViewModel.schemeForConn] (deterministic hash),
 * so a server the user already expanded in the tree reuses the same registered connection
 * here, without reconnecting.
 */
object Connections {

    /** Deterministic scheme for a given connection. */
    fun schemeOf(conn: SavedConnection): String =
        conn.type + Integer.toHexString(conn.label().hashCode())

    private val byScheme = java.util.concurrent.ConcurrentHashMap<String, SavedConnection>()

    /**
     * **Process-wide** reverse lookup from scheme to connection (registered in [ensure],
     * so any server the user has browsed must already be registered).
     *
     * `PaneViewModel` keeps its own `schemeToConn`, but that only records servers
     * **this pane** has expanded: copy / archive confirmation dialogs show the **other**
     * pane's destination directory, so looking up our own map necessarily misses, and
     * the server name disappears — paths degrade to `sftp:/backup` instead of matching
     * the recent-location entry `sftp:/nas/backup`.
     */
    fun ofScheme(scheme: String): SavedConnection? = byScheme[scheme]

    /**
     * Fields used to decide "do we need to rebuild the connection".
     *
     * **Excludes token / userId / hostKey — these three are learned after connecting,
     * they are not input for establishing it** (Jellyfin writes back the token after
     * login, SFTP remembers the host key on first connect). If they took part in the
     * comparison, every writeback would make the next ensure think "config changed",
     * disconnect and reconnect, then write back again — a loop.
     * Backup export excludes the same set of fields for the same reason.
     */
    private fun connKey(c: SavedConnection) = c.copy(token = "", userId = "", hostKey = "")

    /**
     * Unregister and disconnect the connection for a scheme. If the socket is not closed
     * explicitly it leaks, which is especially visible with SMB (libsmb2 still has a
     * context attached).
     */
    fun drop(scheme: String) {
        when (val fs = FsRegistry.unregister(scheme)) {
            is SmbFileSystem -> runCatching { fs.disconnect() }
            is SftpFileSystem -> runCatching { fs.disconnect() }
            else -> Unit
        }
    }

    /**
     * Ensure the connection is registered in FsRegistry and return its scheme; if already
     * registered, reuse without reconnecting. Blocking I/O, must run on a worker thread.
     *
     * **The connection-establishment logic lives here only**. There used to be a near
     * duplicate in [ui.PaneViewModel.schemeForConn], and the two sides relied on comments
     * to keep the scheme algorithm in sync — they had already started to drift (the
     * other side also recorded the SMB dialect). Adding a new connection type means
     * changing only here.
     *
     * @param onInfo supplementary information obtained after connecting (currently only
     *   the SMB negotiated dialect, e.g. "SMB3"), shown on the server row in the tree;
     *   pass nothing if you don't care.
     */
    fun ensure(ctx: Context, conn: SavedConnection, onInfo: (String) -> Unit = {}): String {
        val s = schemeOf(conn)
        val prev = byScheme.put(s, conn) // reverse-lookup table: register before the "reuse if registered" check
        if (FsRegistry.all().any { it.scheme == s }) {
            // ★ Only reuse when the config has **not** changed. The scheme is derived
            // from the connection label, and changing the password doesn't change the
            // label, so the scheme is unchanged — an unconditional reuse would hand
            // back the instance built with the **old** config.
            //
            // With master password enabled this surfaces in its most inscrutable way:
            // if a pane has expanded the server before unlocking, the "password" read
            // back then was still ciphertext, the constructed instance keeps carrying
            // it, and the symptom is "login fails, but editing without changing anything
            // and saving fixes it" (the save goes through forgetServer, which unregisters
            // the bad instance as a side effect). The timing issue is fixed too, but
            // the criterion belongs here, not depending on the caller.
            if (prev == null || connKey(prev) == connKey(conn)) return s
            drop(s)
        }
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
                        knownHostKey = conn.hostKey, path = conn.share,
                        onLearnHostKey = { fp -> rememberHostKey(ctx, conn, fp) },
                    ),
                    s,
                ),
            )
            "webdav" -> FsRegistry.register(WebDavFileSystem(DavConfig(conn.host, conn.user, conn.password), s))
            "jellyfin", "emby" -> FsRegistry.register(
                JellyfinFileSystem(
                    JellyfinConfig(
                        baseUrl = conn.host,
                        user = conn.user, password = conn.password, apiKey = conn.apiKey,
                        emby = conn.type == "emby",
                        deviceId = Prefs.deviceId(ctx),
                        deviceName = android.os.Build.MODEL ?: "Android",
                        clientVersion = appVersion(ctx),
                        token = conn.token, userId = conn.userId,
                        onAuth = { t, u -> rememberAuth(ctx, conn, t, u) },
                        labels = MediaLabels.of(ctx),
                    ),
                    s,
                ),
            )
            "s3" -> FsRegistry.register(
                S3FileSystem(
                    S3Config(
                        endpoint = conn.host, accessKey = conn.user, secretKey = conn.password,
                        region = conn.region, bucket = conn.share, pathStyle = conn.pathStyle,
                    ),
                    s,
                ),
            )
            else -> FsRegistry.register(
                FtpFileSystem(
                    FtpConfig(conn.host, conn.port, conn.user, conn.password, path = conn.share),
                    s,
                ),
            )
        }
        return s
    }

    /**
     * Record the host key fingerprint on first successful SFTP connect (TOFU).
     * Re-read from the store and modify that, not the potentially stale snapshot in hand —
     * which could otherwise clobber other fields the user has since edited. Called from
     * the SSH handshake thread; SharedPreferences.apply() is safe from any thread.
     */
    private fun rememberHostKey(ctx: Context, conn: SavedConnection, fp: String) {
        val cur = ConnectionStore.all(ctx).firstOrNull { it.label() == conn.label() } ?: return
        if (cur.hostKey == fp) return
        ConnectionStore.save(ctx, cur.copy(hostKey = fp))
    }

    /**
     * This app's version, used in Jellyfin's auth header (it records Client/Device/Version
     * as session info and 400s outright when they're missing). Goes through PackageManager
     * instead of `BuildConfig` — the latter requires enabling `buildFeatures.buildConfig`
     * and generating an entire class, not worth it for one string.
     */
    private fun appVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")

    /**
     * After a successful Jellyfin / Emby login, record the token and userId to skip logging
     * in next time. Same as [rememberHostKey]: re-read from the store and modify that, not
     * the possibly stale snapshot in hand — which could otherwise clobber other fields the
     * user has since edited. Called from the network thread; `apply()` is safe from any thread.
     */
    private fun rememberAuth(ctx: Context, conn: SavedConnection, token: String, userId: String) {
        val cur = ConnectionStore.all(ctx).firstOrNull { it.label() == conn.label() } ?: return
        if (cur.token == token && cur.userId == userId) return
        ConnectionStore.save(ctx, cur.copy(token = token, userId = userId))
    }

    /**
     * The path to use when a path leaves the file tree and enters a **shell** on the same
     * server: `git -C <dir>`, the terminal's `cd`, a remote command's workdir.
     *
     * An SFTP connection can be rooted below the server root, and then the path the UI
     * shows is not the path a command needs — [com.twig.fs.network.SftpFileSystem.serverPath]
     * translates it back. For every other scheme the visible path is already the real one,
     * so this is the identity.
     */
    fun shellPath(scheme: String, path: String): String =
        (runCatching { FsRegistry.of(scheme) }.getOrNull() as? SftpFileSystem)?.serverPath(path) ?: path

    fun find(ctx: Context, label: String): SavedConnection? =
        ConnectionStore.all(ctx).firstOrNull { it.label() == label }
}
