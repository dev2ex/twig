package com.twig.app.share

import android.content.Context
import android.os.Build
import com.twig.app.Connections
import com.twig.app.ConnectionStore
import com.twig.core.FsRegistry
import org.json.JSONObject

/**
 * 共享范围:要么整棵 [FsRegistry],要么某一个目录。
 */
sealed class ShareScope {

    /**
     * 所有来源:URL 顶层是各已注册 FileSystem,下面才是它们各自的目录树。
     * 这是这个功能最有意思的地方——电脑浏览器能直接下 SMB 上的、甚至压缩包里的文件,
     * 因为对服务端来说它们都只是 `openInput()`。
     */
    object AllSources : ShareScope()

    /**
     * 单个目录。
     *
     * [connLabel] 是该 scheme 对应的已保存连接标签(本地/压缩包这类没有连接的来源为空)。
     * scheme 由 [Connections.schemeOf] 从标签确定性算出、跨重启不变,但**注册**不会自动
     * 恢复——存下标签,服务冷启动时可以自己把连接重新建起来(见 [ShareRoot.ensureReady]),
     * 不必要求用户先回树上手动展开一次那台服务器。
     */
    data class Dir(
        val scheme: String,
        val path: String,
        val label: String,
        val connLabel: String = "",
    ) : ShareScope()
}

/**
 * 一次 WiFi 共享的配置。
 *
 * **默认只读**:把整台设备的文件摊到局域网上本来就该是保守的默认值,写入要用户明确打开。
 */
data class ShareConfig(
    val scope: ShareScope,
    val readOnly: Boolean = true,
    val port: Int = DEFAULT_PORT,
    val user: String = "",
    val password: String = "",
    /** 设备发现里报出去的名字;空则用机型名。 */
    val deviceName: String = "",
) {
    /** 空密码 = 不设防(局域网内谁都能访问),非空才要 Basic 认证。 */
    val needsAuth: Boolean get() = password.isNotEmpty()

    /** 用户名留空时允许随便填(只校验密码),但 WebDAV 客户端总要给个用户名。 */
    val authUser: String get() = user.ifEmpty { "twig" }

    fun nameOrModel(): String = deviceName.ifEmpty { Build.MODEL ?: "Android" }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

/**
 * 共享配置的持久化(SharedPreferences + JSON)。
 *
 * 单独一个 store 而不是往 [com.twig.app.Prefs] 里再摊七八个平铺的键:这组值只被共享功能
 * 整体读写,拆开只会多出一堆要同步维护的键名(与 `compare_options` 存整块 JSON 同一理由)。
 */
object ShareStore {
    private const val FILE = "twig_share"
    private const val KEY = "config"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): ShareConfig {
        val raw = sp(ctx).getString(KEY, null) ?: return ShareConfig(ShareScope.AllSources)
        return runCatching {
            val o = JSONObject(raw)
            val scope = if (o.optBoolean("all", true)) {
                ShareScope.AllSources
            } else {
                ShareScope.Dir(
                    scheme = o.optString("scheme"),
                    path = o.optString("path"),
                    label = o.optString("label"),
                    connLabel = o.optString("conn"),
                )
            }
            ShareConfig(
                scope = scope,
                readOnly = o.optBoolean("ro", true),
                port = o.optInt("port", ShareConfig.DEFAULT_PORT),
                user = o.optString("user"),
                password = o.optString("pass"),
                deviceName = o.optString("name"),
            )
        }.getOrDefault(ShareConfig(ShareScope.AllSources))
    }

    fun save(ctx: Context, cfg: ShareConfig) {
        val o = JSONObject()
        when (val s = cfg.scope) {
            is ShareScope.AllSources -> o.put("all", true)
            is ShareScope.Dir -> {
                o.put("all", false)
                o.put("scheme", s.scheme); o.put("path", s.path)
                o.put("label", s.label); o.put("conn", s.connLabel)
            }
        }
        o.put("ro", cfg.readOnly); o.put("port", cfg.port)
        o.put("user", cfg.user); o.put("pass", cfg.password)
        o.put("name", cfg.deviceName)
        sp(ctx).edit().putString(KEY, o.toString()).apply()
    }

    /**
     * 某个 scheme 属于哪条已保存连接(存 scope 时用)。本地/压缩包等没有连接的来源返回空串。
     */
    fun connLabelOf(ctx: Context, scheme: String): String =
        Connections.ofScheme(scheme)?.label()
            ?: ConnectionStore.all(ctx).firstOrNull { Connections.schemeOf(it) == scheme }?.label()
            ?: ""
}
