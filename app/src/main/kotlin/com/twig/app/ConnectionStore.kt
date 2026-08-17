package com.twig.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条已保存的远程连接(FTP / SMB / SFTP / WebDAV / S3)。 */
data class SavedConnection(
    val type: String,            // "ftp" | "smb" | "sftp" | "webdav" | "s3"
    val host: String,            // webdav / s3 时存完整 URL(s3 是 endpoint)
    val port: Int = 21,
    /** SMB 的共享名;S3 复用它存桶名(留空 = 根目录列出所有桶)。 */
    val share: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "WORKGROUP",
    /** 用户自定义显示名(可空);填写后树上显示它。 */
    val name: String = "",
    /** SFTP 私钥文件路径(可空);非空时用私钥认证,password 兼作口令。 */
    val keyPath: String = "",
    /**
     * SFTP 已记住的主机密钥指纹(TOFU,见 `SftpFileSystem.hostKeyVerifier`)。
     * 空 = 还没连过;首次连上会自动填,之后变了就拒绝连接。
     * 用户确认服务器确实换了密钥时,从服务器长按菜单「忘记主机密钥」清空这里。
     */
    val hostKey: String = "",
    /** S3 的区域;签名要用,填错会被服务端拒(错误信息里会写正确的那个)。 */
    val region: String = "us-east-1",
    /**
     * S3 的寻址风格:true = `endpoint/bucket/key`(自建 MinIO 的常态),
     * false = `bucket.endpoint/key`(AWS 正统写法)。
     */
    val pathStyle: Boolean = true,
) {
    /** 唯一标识,用于去重与持久化键;显示用 [displayLabel]。 */
    fun label(): String = when (type) {
        "smb" -> "smb://$host/$share"
        "sftp" -> "sftp://${user.ifEmpty { "root" }}@$host:$port"
        "webdav" -> host
        // 同一个 endpoint 上的不同桶是两条独立连接,桶名必须进标识
        "s3" -> "s3://${host.substringAfter("://").trimEnd('/')}/$share"
        else -> "ftp://${user.ifEmpty { "anonymous" }}@$host:$port"
    }

    /** 树上显示的名称:自定义名优先,否则用 [label]。 */
    fun displayLabel(): String = name.ifEmpty { label() }

    /**
     * 路径栏/最近位置里的服务器短名:自定义名优先,否则只留地址本身——
     * 这些地方前面已经有类型前缀(`sftp:/…`),再带一遍 [label] 的协议头就重复了。
     */
    fun shortLabel(): String = name.ifEmpty { rawShortLabel() }

    /**
     * 同 [shortLabel] 但不管有没有自定义别名,永远给真实地址——用在"路径"而非"名称"的
     * 场合(如收藏行下方的完整路径、对比收藏的"显示两个目录的路径"):路径该是能直接
     * 定位的原始地址,别名放名称那边就够了,两处混着用会认不出到底是哪台服务器。
     */
    fun rawShortLabel(): String = when (type) {
        "smb" -> if (share.isEmpty()) host else "$host/$share"
        "webdav" -> host.substringAfter("://").trimEnd('/')
        "s3" -> host.substringAfter("://").trimEnd('/') + if (share.isEmpty()) "" else "/$share"
        "sftp" -> if (port == 22) host else "$host:$port"
        else -> if (port == 21) host else "$host:$port"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("host", host); put("port", port)
        put("share", share); put("user", user); put("password", password); put("domain", domain)
        put("name", name); put("keyPath", keyPath); put("hostKey", hostKey)
        put("region", region); put("pathStyle", pathStyle)
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
        )
    }
}

/** 已保存连接的持久化(SharedPreferences + JSON)。 */
object ConnectionStore {
    private const val FILE = "twig_connections"
    private const val KEY = "list"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<SavedConnection> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { SavedConnection.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** 保存(按 label 去重,后者覆盖)。 */
    fun save(ctx: Context, conn: SavedConnection) {
        val list = all(ctx).filter { it.label() != conn.label() } + conn
        persist(ctx, list)
    }

    fun remove(ctx: Context, conn: SavedConnection) {
        persist(ctx, all(ctx).filter { it.label() != conn.label() })
    }

    private fun persist(ctx: Context, list: List<SavedConnection>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
