package com.twig.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 播放列表里的一首曲目。为跨会话可恢复,不存动态 scheme,而存"如何到达"(仿 [Favorite]):
 * - local: path = 本地绝对路径
 * - conn:  connLabel = 对应 SavedConnection.label(),path = 该连接内路径
 * 缓存元数据(标题/艺术家/时长)用于列表页免整读展示;0/空 = 未知,后台补全后写回 store。
 */
data class PlaylistTrack(
    val kind: String,            // "local" | "conn" | "share"(其他 App 传进来的 content://)
    val path: String,
    val connLabel: String = "",
    val size: Long = 0L,         // 文件字节数——网络来源必需:SMB/WebDAV 等的 openRandom().length() 直接取自
                                 // XFile.size,缺了(=0)会让数据源一读就 EOF、extractor 判空流,导致网络音频无法播放
    val lastModified: Long = 0L, // 导入时顺手存下,配合 size 让 MusicEngine.resolve() 重建的 XFile
                                 // 缓存 key(Thumbs.keyOf = md5(name:size:mtime))在跨会话间保持稳定,
                                 // 不必每次进播放列表都靠 statByListing 现列目录取值(m3u8/旧列表尤其明显)
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,     // Hz;0=未知(列表副标题显示用)
    val bitrate: Int = 0,        // bps;0=未知
    /** 显示名。content:// 这类 path 里没有文件名的来源必需(名字与扩展名都只能从这拿)。 */
    val displayName: String = "",
) {
    /** 稳定唯一 id。 */
    val id: String get() = "$kind|$connLabel|$path"

    /** 文件名:优先 [displayName],否则取路径末段。 */
    val name: String
        get() = displayName.ifEmpty { path.trimEnd('/').substringAfterLast('/').ifEmpty { path } }

    val ext: String
        get() {
            val n = name
            val d = n.lastIndexOf('.')
            return if (d <= 0) "" else n.substring(d + 1).lowercase()
        }

    fun withMeta(t: String, a: String, al: String, d: Long, sr: Int = sampleRate, br: Int = bitrate) =
        copy(title = t, artist = a, album = al, durationMs = d, sampleRate = sr, bitrate = br)

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind); put("path", path); put("connLabel", connLabel); put("size", size)
        put("lastModified", lastModified)
        put("title", title); put("artist", artist); put("album", album); put("durationMs", durationMs)
        put("sampleRate", sampleRate); put("bitrate", bitrate)
        if (displayName.isNotEmpty()) put("displayName", displayName)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlaylistTrack(
            kind = o.getString("kind"),
            path = o.getString("path"),
            connLabel = o.optString("connLabel", ""),
            size = o.optLong("size", 0L),
            lastModified = o.optLong("lastModified", 0L),
            title = o.optString("title", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            durationMs = o.optLong("durationMs", 0L),
            sampleRate = o.optInt("sampleRate", 0),
            bitrate = o.optInt("bitrate", 0),
            displayName = o.optString("displayName", ""),
        )
    }
}

/**
 * 一个播放列表。特殊 id:
 * - [NOW] "当前播放":每次从文件管理器点音频时被"替换"为同目录音频;可重命名(变普通列表)。
 * - [FAV] "我的最爱":固定存在、不可删不可改名,心形按钮加/移。
 */
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<PlaylistTrack>,
    val lastIndex: Int = 0,
    val lastPosMs: Long = 0L,
) {
    val isNow: Boolean get() = id == NOW
    val isFav: Boolean get() = id == FAV
    val fixed: Boolean get() = isNow || isFav

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("lastIndex", lastIndex); put("lastPosMs", lastPosMs)
        val a = JSONArray(); tracks.forEach { a.put(it.toJson()) }; put("tracks", a)
    }

    companion object {
        const val NOW = "now"
        const val FAV = "fav"

        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): Playlist {
            val a = o.optJSONArray("tracks") ?: JSONArray()
            return Playlist(
                id = o.getString("id"),
                name = o.getString("name"),
                tracks = (0 until a.length()).map { PlaylistTrack.fromJson(a.getJSONObject(it)) },
                lastIndex = o.optInt("lastIndex", 0),
                lastPosMs = o.optLong("lastPosMs", 0L),
            )
        }
    }
}

/** 播放列表持久化(SharedPreferences + JSON,模式照 [FavoritesStore])。 */
object PlaylistStore {
    private const val FILE = "twig_playlists"
    private const val KEY = "list"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Playlist> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Playlist.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, id: String): Playlist? = all(ctx).firstOrNull { it.id == id }

    /** "我的最爱"固定存在(首次访问自动建)。 */
    fun fav(ctx: Context): Playlist =
        get(ctx, Playlist.FAV) ?: Playlist(Playlist.FAV, "我的最爱", emptyList()).also { save(ctx, it) }

    fun save(ctx: Context, pl: Playlist) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == pl.id }
        if (i >= 0) list[i] = pl else list.add(pl)
        persist(ctx, list)
    }

    fun create(ctx: Context, name: String, tracks: List<PlaylistTrack> = emptyList()): Playlist {
        val pl = Playlist(Playlist.newId(), name, tracks)
        save(ctx, pl)
        return pl
    }

    /**
     * 重命名。普通列表就地改名;重命名 [Playlist.NOW] 特殊:把它变成一个新 uuid 的普通
     * 用户列表(名字为用户输入),原 now 条目移除——下次打开音频会重建空 now。返回结果列表。
     */
    fun rename(ctx: Context, id: String, name: String): Playlist? {
        val pl = get(ctx, id) ?: return null
        if (pl.isFav) return pl // 我的最爱不可改名
        return if (pl.isNow) {
            val promoted = pl.copy(id = Playlist.newId(), name = name)
            val list = all(ctx).filter { it.id != Playlist.NOW } + promoted
            persist(ctx, list)
            promoted
        } else {
            pl.copy(name = name).also { save(ctx, it) }
        }
    }

    fun delete(ctx: Context, id: String) {
        if (id == Playlist.FAV || id == Playlist.NOW) return // 特殊列表不可删
        persist(ctx, all(ctx).filter { it.id != id })
    }

    /** 替换"当前播放"内容(点目录音频时调用)。 */
    fun setNow(ctx: Context, name: String, tracks: List<PlaylistTrack>): Playlist {
        val pl = Playlist(Playlist.NOW, name, tracks)
        save(ctx, pl)
        return pl
    }

    fun addTracks(ctx: Context, id: String, tracks: List<PlaylistTrack>): Playlist? {
        val pl = get(ctx, id) ?: return null
        val existing = pl.tracks.map { it.id }.toHashSet()
        val merged = pl.tracks + tracks.filter { it.id !in existing }
        return pl.copy(tracks = merged).also { save(ctx, it) }
    }

    fun removeTrack(ctx: Context, id: String, trackId: String): Playlist? {
        val pl = get(ctx, id) ?: return null
        return pl.copy(tracks = pl.tracks.filter { it.id != trackId }).also { save(ctx, it) }
    }

    /** 心形:在"我的最爱"里加/移某曲目,返回是否已在最爱(操作后的状态)。 */
    fun toggleFav(ctx: Context, track: PlaylistTrack): Boolean {
        val f = fav(ctx)
        return if (f.tracks.any { it.id == track.id }) {
            save(ctx, f.copy(tracks = f.tracks.filter { it.id != track.id })); false
        } else {
            save(ctx, f.copy(tracks = f.tracks + track)); true
        }
    }

    fun isFav(ctx: Context, trackId: String): Boolean =
        fav(ctx).tracks.any { it.id == trackId }

    /** 后台 MMR 补全元数据后写回(按 track.id 匹配,内容变了才存)。 */
    fun updateTrackMeta(ctx: Context, id: String, track: PlaylistTrack) {
        val pl = get(ctx, id) ?: return
        var changed = false
        val next = pl.tracks.map { if (it.id == track.id && it != track) { changed = true; track } else it }
        if (changed) save(ctx, pl.copy(tracks = next))
    }

    /** 按曲目 id 在 pl.tracks(原始未过滤列表)里换算出正确下标再存,供 [Playlist.lastIndex]。 */
    fun saveResume(ctx: Context, id: String, trackId: String, posMs: Long) {
        val pl = get(ctx, id) ?: return
        val idx = pl.tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        save(ctx, pl.copy(lastIndex = idx, lastPosMs = posMs))
    }

    private fun persist(ctx: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
