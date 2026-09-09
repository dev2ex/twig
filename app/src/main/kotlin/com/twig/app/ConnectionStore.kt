package com.twig.app

import android.content.Context
import com.twig.app.secure.Secrets
import org.json.JSONArray
import org.json.JSONObject

/** A saved remote connection (FTP / SMB / SFTP / WebDAV / S3 / Jellyfin / Emby). */
data class SavedConnection(
    val type: String,            // "ftp" | "smb" | "sftp" | "webdav" | "s3" | "jellyfin" | "emby"
    val host: String,            // full URL for webdav / s3 / jellyfin / emby (s3 is endpoint)
    val port: Int = 21,
    /**
     * **Where a connection is rooted** — one field for every type, because "which share /
     * bucket" and "which directory inside it" are one question:
     * - SMB: `Public`, `Public/Photos`, or empty = list every share on the server;
     * - S3: `bucket`, `bucket/prefix`, or empty = list every bucket;
     * - FTP / SFTP: a directory (`pub/photos`), empty = the server root;
     * - WebDAV needs nothing here — its [host] is a full URL, so a path written into
     *   that URL already roots the connection.
     *
     * It is part of [label], so the same server saved at two directories is two
     * connections (they must not share a scheme, or one would reuse the other's root).
     * The field keeps the name `share` for the sake of already-stored configs.
     */
    val share: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "WORKGROUP",
    /** User-defined display name (nullable); when set, shown in the tree. */
    val name: String = "",
    /** SFTP private key file path (nullable); non-empty means key auth, password acts as passphrase. */
    val keyPath: String = "",
    /**
     * SFTP remembered host key fingerprint (TOFU, see `SftpFileSystem.hostKeyVerifier`).
     * Empty = never connected; filled on first successful connect, after which a change is rejected.
     * When the user confirms the server really did rotate its key, clear this via the server's
     * long-press menu's "Forget host key".
     */
    val hostKey: String = "",
    /** S3 region; needed for signing, a wrong value gets rejected by the server (the error message names the correct one). */
    val region: String = "us-east-1",
    /**
     * S3 addressing style: true = `endpoint/bucket/key` (typical for self-hosted MinIO),
     * false = `bucket.endpoint/key` (AWS canonical).
     */
    val pathStyle: Boolean = true,
    /**
     * Jellyfin / Emby API key (the kind generated in the server's admin backend). Non-empty
     * authenticates with it instead of user/password; in that case [user] is only used to
     * locate an identity in the server's user list — "Continue watching" and playback
     * progress are per-user, and the API key itself does not carry one.
     */
    val apiKey: String = "",
    /**
     * Jellyfin / Emby access token obtained from the last login, to skip logging in again
     * every connection. When it expires (server restart / session revoke), `JellyfinFileSystem`
     * automatically re-authenticates on receiving a 401 and writes it back.
     */
    val token: String = "",
    /** Jellyfin / Emby userId resolved on the last login, to skip a `/Users` round-trip. */
    val userId: String = "",
) {
    /** Whether it is a media server (Jellyfin / Emby): these two types behave identically everywhere, judgement centralised here. */
    fun isMediaServer(): Boolean = type == "jellyfin" || type == "emby"

    /** Unique identifier for deduplication and persistence keys; for display use [displayLabel]. */
    fun label(): String = when (type) {
        "smb" -> "smb://$host/$share"
        "sftp" -> "sftp://${user.ifEmpty { "root" }}@$host:$port" + rootSuffix()
        "webdav" -> host
        // Different buckets on the same endpoint are independent connections, the bucket name must be in the identifier
        "s3" -> "s3://${host.substringAfter("://").trimEnd('/')}/$share"
        // Different users on the same server see entirely different "Continue watching", so they are independent connections
        "jellyfin", "emby" -> "$type://${user.ifEmpty { "-" }}@${host.substringAfter("://").trimEnd('/')}"
        else -> "ftp://${user.ifEmpty { "anonymous" }}@$host:$port" + rootSuffix()
    }

    /** `/sub/dir` when the connection is rooted below the top, otherwise nothing. */
    private fun rootSuffix(): String = if (share.isEmpty()) "" else "/${share.trim('/')}"

    /** Name shown in the tree: custom name preferred, otherwise [label]. */
    fun displayLabel(): String = name.ifEmpty { label() }

    /**
     * Short server name used in the path bar / recent locations: custom name preferred,
     * otherwise only the address itself — these places already have a type prefix in
     * front (`sftp:/…`), so repeating [label]'s scheme header would be redundant.
     */
    fun shortLabel(): String = name.ifEmpty { rawShortLabel() }

    /**
     * Same as [shortLabel] but always returns the real address regardless of custom alias —
     * used in "path" rather than "name" contexts (e.g. the full path under a favourite row,
     * "show the two directories' paths" in a saved comparison): a path should be a directly
     * locatable raw address, and the alias belongs on the name side; mixing the two means
     * you can't tell which server a given line points at.
     */
    fun rawShortLabel(): String = when (type) {
        "smb" -> if (share.isEmpty()) host else "$host/$share"
        "webdav" -> host.substringAfter("://").trimEnd('/')
        "s3" -> host.substringAfter("://").trimEnd('/') + if (share.isEmpty()) "" else "/$share"
        "jellyfin", "emby" -> host.substringAfter("://").trimEnd('/')
        "sftp" -> (if (port == 22) host else "$host:$port") + rootSuffix()
        else -> (if (port == 21) host else "$host:$port") + rootSuffix()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("host", host); put("port", port)
        put("share", share); put("user", user); put("password", password); put("domain", domain)
        put("name", name); put("keyPath", keyPath); put("hostKey", hostKey)
        put("region", region); put("pathStyle", pathStyle)
        put("apiKey", apiKey); put("token", token); put("userId", userId)
    }

    companion object {
        fun fromJson(o: JSONObject) = SavedConnection(
            type = o.getString("type"),
            host = o.getString("host"),
            port = o.optInt("port", 21),
            share = o.optString("share", ""),
            user = o.optString("user", ""),
            password = o.optString("password", ""),
            domain = o.optString("domain", "WORKGROUP"),
            name = o.optString("name", ""),
            keyPath = o.optString("keyPath", ""),
            hostKey = o.optString("hostKey", ""),
            region = o.optString("region", "us-east-1"),
            pathStyle = o.optBoolean("pathStyle", true),
            apiKey = o.optString("apiKey", ""),
            token = o.optString("token", ""),
            userId = o.optString("userId", ""),
        )
    }
}

/**
 * Persistence for saved connections (SharedPreferences + JSON).
 *
 * **Password / apiKey / token are encrypted via [Secrets] before going to disk**;
 * [SavedConnection] in memory is always plaintext. The encryption hooks sit on the single
 * [all] / [persist] read-write pair, so callers (the connection dialog, Jellyfin writing
 * back a token, SFTP remembering a host key) don't change at all.
 *
 * [SavedConnection.toJson] deliberately stays plaintext — it is also the serialisation
 * path for exported backups, and those need plaintext (the local ciphertext was encrypted
 * with a Keystore key that simply cannot be decrypted on another device).
 */
object ConnectionStore {
    const val FILE = "twig_connections"
    private const val KEY = "list"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** For persisting: replace sensitive fields with ciphertext. */
    private fun SavedConnection.sealed(ctx: Context) = copy(
        password = Secrets.enc(ctx, password),
        apiKey = Secrets.enc(ctx, apiKey),
        token = Secrets.enc(ctx, token),
    )

    /** For reading back: restore sensitive fields to plaintext (old plaintext without the `enc1:` prefix passes through unchanged). */
    private fun SavedConnection.opened(ctx: Context) = copy(
        password = Secrets.dec(ctx, password),
        apiKey = Secrets.dec(ctx, apiKey),
        token = Secrets.dec(ctx, token),
    )

    fun all(ctx: Context): List<SavedConnection> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { SavedConnection.fromJson(arr.getJSONObject(it)).opened(ctx) }
        }.getOrDefault(emptyList())
    }

    /**
     * Save (dedup by [SavedConnection.label]; the latter overwrites).
     *
     * ★ **An existing entry keeps its slot in the list** — the stored order is the order
     * the sidebar shows servers in, and this method is also called from background writes
     * the user never asked for (SFTP recording a host key, Jellyfin writing back a token
     * on first connect). Appending instead of replacing in place made simply expanding a
     * server jump it to the bottom of its group, which was most visible right after
     * importing a backup — the backup strips `hostKey` / `token`, so the first expansion
     * of every server rewrote it and reshuffled the whole list.
     */
    fun save(ctx: Context, conn: SavedConnection) {
        val list = all(ctx).toMutableList()
        val at = list.indexOfFirst { it.label() == conn.label() }
        if (at >= 0) list[at] = conn else list += conn
        persist(ctx, list)
    }

    /**
     * Replace [old] with [conn] **in place** (used when editing a server): editing may
     * change the label — host, port, root directory are all part of it — so this cannot go
     * through [save], which matches on the new label and would append. Any other entry
     * that collides with the new label is dropped, and when [old] is gone the connection
     * is simply appended.
     */
    fun replace(ctx: Context, old: SavedConnection, conn: SavedConnection) {
        val list = all(ctx).toMutableList()
        val at = list.indexOfFirst { it.label() == old.label() }
        if (at < 0) {
            save(ctx, conn)
            return
        }
        list[at] = conn
        // A collision can only be with a *different* slot: the edited one now holds conn itself.
        list.removeAll { it !== conn && it.label() == conn.label() }
        persist(ctx, list)
    }

    fun remove(ctx: Context, conn: SavedConnection) {
        persist(ctx, all(ctx).filter { it.label() != conn.label() })
    }

    /** Replace the whole table (used by backup import): the caller passes plaintext, which is uniformly encrypted before persisting. */
    fun replaceAll(ctx: Context, list: List<SavedConnection>) = persist(ctx, list)

    private fun persist(ctx: Context, list: List<SavedConnection>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.sealed(ctx).toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
