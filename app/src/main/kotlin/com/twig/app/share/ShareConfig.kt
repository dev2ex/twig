package com.twig.app.share

import android.content.Context
import android.os.Build
import com.twig.app.Connections
import com.twig.app.ConnectionStore
import com.twig.core.FsRegistry
import org.json.JSONObject

/**
 * Sharing scope: either the whole [FsRegistry] or a single directory.
 */
sealed class ShareScope {

    /**
     * All sources: the URL top level is each registered FileSystem, and
     * their directory trees hang below. This is the interesting part of
     * the feature — a browser on the computer can download files straight
     * from SMB, or even from inside an archive, because to the server they
     * are all just `openInput()`.
     */
    object AllSources : ShareScope()

    /**
     * A single directory.
     *
     * [connLabel] is the label of the saved connection that corresponds to
     * this scheme (empty for sources that have no connection, such as
     * local / archives). The scheme is computed deterministically from the
     * label by [Connections.schemeOf] and stays the same across restarts,
     * but the **registration** does not auto-restore — storing the label
     * lets the service rebuild the connection itself on a cold start (see
     * [ShareRoot.ensureReady]), without making the user first expand the
     * server manually in the tree.
     */
    data class Dir(
        val scheme: String,
        val path: String,
        val label: String,
        val connLabel: String = "",
    ) : ShareScope()
}

/**
 * The configuration of one WiFi share session.
 *
 * **Read-only by default**: spreading the device's files across the LAN
 * deserves a conservative default; writes require the user to opt in
 * explicitly.
 */
data class ShareConfig(
    val scope: ShareScope,
    val readOnly: Boolean = true,
    val port: Int = DEFAULT_PORT,
    val user: String = "",
    val password: String = "",
    /** Name reported in device discovery; empty falls back to the model name. */
    val deviceName: String = "",
) {
    /** Empty password = no protection (anyone on the LAN can reach it); non-empty requires Basic auth. */
    val needsAuth: Boolean get() = password.isNotEmpty()

    /** Empty username is allowed (only the password is checked), but WebDAV clients always need to provide a username, so default to "twig". */
    val authUser: String get() = user.ifEmpty { "twig" }

    fun nameOrModel(): String = deviceName.ifEmpty { Build.MODEL ?: "Android" }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

/**
 * Persistence of share configuration (SharedPreferences + JSON).
 *
 * A separate store rather than seven or eight flat keys in
 * [com.twig.app.Prefs]: the share feature reads and writes this set as a
 * unit, and splitting it would just mean more key names to keep in sync
 * (same reasoning as `compare_options` storing its whole block as one
 * JSON blob).
 */
object ShareStore {
    const val FILE = "twig_share"
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
                password = com.twig.app.secure.Secrets.dec(ctx, o.optString("pass")),
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
        o.put("user", cfg.user); o.put("pass", com.twig.app.secure.Secrets.enc(ctx, cfg.password))
        o.put("name", cfg.deviceName)
        sp(ctx).edit().putString(KEY, o.toString()).apply()
    }

    /**
     * Which saved connection a given scheme belongs to (used when persisting
     * the scope). Sources with no underlying connection (local / archives)
     * return the empty string.
     */
    fun connLabelOf(ctx: Context, scheme: String): String =
        Connections.ofScheme(scheme)?.label()
            ?: ConnectionStore.all(ctx).firstOrNull { Connections.schemeOf(it) == scheme }?.label()
            ?: ""
}
