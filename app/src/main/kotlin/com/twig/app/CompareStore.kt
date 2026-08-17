package com.twig.app

import android.content.Context
import com.twig.app.ui.CompareOptions
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条保存下来的对比(左右两侧 + 判定选项 + 排除规则)。
 *
 * 两侧沿用 [Favorite] 那套"存如何到达、不存动态 scheme"的编码——同一个道理:
 * 网络来源的 scheme 是每次会话现分配的,跨启动直接存 scheme 必然对不上。
 *
 * **不与 [FavoritesStore] 合流**:普通收藏是单个目录、在树上还有个「收藏」顶级节点,
 * 对比是二元组且只在对比页里有意义,混进去只会污染那棵树。
 */
data class CompareSession(
    val label: String,
    val left: Favorite,
    val right: Favorite,
    val options: CompareOptions,
) {
    val id: String get() = "${left.id}>>${right.id}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("left", left.toJson())
        put("right", right.toJson())
        put("opt", optionsToJson(options))
    }

    companion object {
        fun fromJson(o: JSONObject) = CompareSession(
            label = o.getString("label"),
            left = Favorite.fromJson(o.getJSONObject("left")),
            right = Favorite.fromJson(o.getJSONObject("right")),
            options = optionsFromJson(o.optJSONObject("opt")),
        )

        fun optionsToJson(o: CompareOptions): JSONObject = JSONObject().apply {
            put("tol", o.timeToleranceMs)
            put("hour", o.allowHourShift)
            put("icase", o.ignoreCase)
            put("cLocal", o.contentLimitLocal)
            put("cNet", o.contentLimitNetwork)
            put("cTime", o.contentOnlyIfTimeDiffers)
            put("ex", JSONArray().apply { o.excludes.forEach { put(it) } })
        }

        fun optionsFromJson(o: JSONObject?): CompareOptions {
            if (o == null) return CompareOptions()
            val d = CompareOptions()
            val ex = o.optJSONArray("ex")
            return CompareOptions(
                timeToleranceMs = o.optLong("tol", d.timeToleranceMs),
                allowHourShift = o.optBoolean("hour", d.allowHourShift),
                ignoreCase = o.optBoolean("icase", d.ignoreCase),
                contentLimitLocal = o.optLong("cLocal", d.contentLimitLocal),
                contentLimitNetwork = o.optLong("cNet", d.contentLimitNetwork),
                contentOnlyIfTimeDiffers = o.optBoolean("cTime", d.contentOnlyIfTimeDiffers),
                excludes = if (ex == null) emptyList() else (0 until ex.length()).map { ex.getString(it) },
            )
        }
    }
}

/** 对比收藏的持久化(SharedPreferences + JSON),与 [FavoritesStore] 同一套路。 */
object CompareStore {
    private const val FILE = "twig_compares"
    private const val KEY = "list"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<CompareSession> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CompareSession.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, s: CompareSession) = persist(ctx, all(ctx).filter { it.id != s.id } + s)

    fun remove(ctx: Context, s: CompareSession) = persist(ctx, all(ctx).filter { it.id != s.id })

    /** 重命名(对比收藏的 label 本身就是用户看到的显示名,直接覆盖)。 */
    fun rename(ctx: Context, s: CompareSession, newLabel: String) =
        persist(ctx, all(ctx).map { if (it.id == s.id) it.copy(label = newLabel) else it })

    private fun persist(ctx: Context, list: List<CompareSession>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}

/**
 * 把一侧的"如何到达"还原成真实 [XFile]:本地直接拼,网络来源先 [Connections.ensure]
 * 建连(密码等都在 [ConnectionStore] 里,不用再问用户)。
 *
 * restic 不支持——它还要仓库密码,而密码不落盘,保存会话时就已经挡在外面了。
 * **会连网**,调用方必须放后台线程。
 */
fun resolveCompareSide(ctx: Context, fav: Favorite): XFile? = when (fav.kind) {
    "local" -> XFile("file", fav.path, isDir = true)
    "conn" -> {
        val conn = Connections.find(ctx, fav.connLabel)
        if (conn == null) null else runCatching {
            val scheme = Connections.ensure(ctx, conn)
            FsRegistry.of(scheme).resolve(fav.path)
        }.getOrNull()
    }
    else -> null
}

/**
 * 由当前 [XFile] 反推"如何到达"。与 `PaneViewModel.favoriteFrom` 同一意图,但这里不依赖
 * VM 的会话内映射表,靠 [Connections.ofScheme] 反查——对比页是独立 Activity,拿不到那张表。
 */
fun compareSideOf(file: XFile): Favorite? = when {
    file.scheme == "file" -> Favorite(label = file.name, kind = "local", path = file.path)
    else -> Connections.ofScheme(file.scheme)?.let {
        Favorite(label = "${it.displayLabel()}:${file.name}", kind = "conn", path = file.path, connLabel = it.label())
    }
}
