package com.twig.app

import android.content.Context
import com.twig.core.XFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条视频播放进度。
 *
 * key **与路径无关**——用「文件名 + 字节大小」,和缩略图缓存同一思路:同一部片子换个
 * 来源打开(先在 SMB 上看、后来复制到本地)也能接着上次的位置;反过来文件被替换成
 * 同名的另一个版本(大小必变)记录自动失效,不会跳到一个莫名其妙的位置。
 * 大小未知(size <= 0,比如某些 content:// 来源)才退回 `scheme|path`。
 */
data class PlaybackMark(
    val key: String,
    val name: String,
    val posMs: Long,
    val durMs: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("name", name); put("pos", posMs); put("dur", durMs); put("at", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlaybackMark(
            key = o.getString("key"),
            name = o.optString("name", ""),
            posMs = o.optLong("pos", 0L),
            durMs = o.optLong("dur", 0L),
            updatedAt = o.optLong("at", 0L),
        )
    }
}

/**
 * 视频播放进度的持久化(SharedPreferences + JSON),最近看的在前,只留 [MAX] 条。
 *
 * 保留策略的取舍:条数上限只是兜底,真正让列表不膨胀的是**看完即删**——看到片尾的
 * 记录留着没有意义,下次本来就该从头开始。加上「太短的片子不记」「刚开头不记」两条,
 * 日常能攒下的基本只有「看了一半的长片」,100 条远够用(每条约 100 字节,满打满算 10KB)。
 */
object PlaybackStore {
    private const val FILE = "twig_playback"
    private const val KEY = "list"
    const val MAX = 100

    /** 比这短的(短视频/表情包)不值得记:跳回去省的那几秒还不如重看。 */
    private const val MIN_DURATION_MS = 90_000L

    /** 开头这一点位置等于没看,记了下次反而多一次莫名其妙的跳转。 */
    private const val MIN_POS_MS = 15_000L

    /** 离结尾多近算「看完」:片长的 5%,限制在 10s~60s(别让长片的片尾曲整段都算看完)。 */
    private fun endSlack(durMs: Long) = (durMs / 20).coerceIn(10_000L, 60_000L)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun keyOf(f: XFile): String =
        if (f.size > 0) "${f.name}|${f.size}" else "${f.scheme}|${f.path}"

    fun all(ctx: Context): List<PlaybackMark> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PlaybackMark.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** 上次看到哪儿;没有记录、或功能关掉了则 0(从头播)。 */
    fun positionFor(ctx: Context, f: XFile): Long {
        if (!Prefs.resumePlayback(ctx)) return 0L
        val key = keyOf(f)
        return all(ctx).firstOrNull { it.key == key }?.posMs ?: 0L
    }

    /**
     * 记一次进度。「太短 / 刚开头 / 已看完」三种情况都当作**不需要记**——已有记录一并删掉,
     * 免得看完一遍后下次打开又跳到片尾。
     */
    fun save(ctx: Context, f: XFile, posMs: Long, durMs: Long) {
        if (!Prefs.resumePlayback(ctx)) return
        val key = keyOf(f)
        val worth = durMs >= MIN_DURATION_MS &&
            posMs >= MIN_POS_MS &&
            posMs < durMs - endSlack(durMs)
        val list = all(ctx)
        val rest = list.filter { it.key != key }
        if (!worth) {
            if (rest.size != list.size) persist(ctx, rest) // 原来有记录才需要写盘
            return
        }
        val mark = PlaybackMark(key, f.name, posMs, durMs, System.currentTimeMillis())
        persist(ctx, (listOf(mark) + rest).take(MAX))
    }

    fun clear(ctx: Context) = sp(ctx).edit().remove(KEY).apply()

    private fun persist(ctx: Context, list: List<PlaybackMark>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
