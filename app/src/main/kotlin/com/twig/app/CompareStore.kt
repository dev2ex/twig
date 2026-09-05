package com.twig.app

import android.content.Context
import com.twig.app.ui.CompareOptions
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved comparison (left/right sides + judgement options + exclusion rules).
 *
 * Both sides reuse the [Favorite] scheme of "store how to reach it, not the dynamic
 * scheme" — same reasoning: network sources get their scheme assigned per session, so
 * storing the scheme directly across launches would never match.
 *
 * **Not merged with [FavoritesStore]**: regular favorites are single directories with
 * a "Favorites" top-level node in the tree, whereas a comparison is a pair and only
 * makes sense inside the comparison page; merging them would only pollute that tree.
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
            put("inc", o.incrementalSync)
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
                incrementalSync = o.optBoolean("inc", d.incrementalSync),
                excludes = if (ex == null) emptyList() else (0 until ex.length()).map { ex.getString(it) },
            )
        }
    }
}

/** Persistence for saved comparisons (SharedPreferences + JSON), same pattern as [FavoritesStore]. */
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

    /** Rename (the saved comparison's label is itself the user-visible display name, so just overwrite). */
    fun rename(ctx: Context, s: CompareSession, newLabel: String) =
        persist(ctx, all(ctx).map { if (it.id == s.id) it.copy(label = newLabel) else it })

    /** Only change judgement / sync options (used when toggling the incremental switch in the sync confirmation dialog); leave label and side positions alone. */
    fun updateOptions(ctx: Context, s: CompareSession, o: CompareOptions) =
        persist(ctx, all(ctx).map { if (it.id == s.id) it.copy(options = o) else it })

    private fun persist(ctx: Context, list: List<CompareSession>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}

/**
 * Restore one side's "how to reach it" into a real [XFile]: local paths are assembled
 * directly, network sources go through [Connections.ensure] first to establish the
 * connection (passwords are all in [ConnectionStore], no prompt needed).
 *
 * restic is not supported — it also needs a repository password, which is not stored,
 * so saving the session already blocks that path. Same for archives (mount points are
 * session-temporary).
 * **Makes network calls**, the caller must run it on a background thread.
 */
fun resolveCompareSide(ctx: Context, fav: Favorite): XFile? = when (fav.kind) {
    "local" -> XFile("file", fav.path, isDir = true)
    // SAF: the grant is persistent and the document URI is valid across launches
    // (same as the saf branch for regular favorites). ★ Must bring back the name —
    // it's not in path — the path bar (Format.pathLabel) shows saf entries' name.
    // Don't go through FsRegistry.of().resolve(): SafFileSystem's implementation drops displayName.
    "saf" -> XFile(
        SafFileSystem.SCHEME, fav.path, isDir = true,
        displayName = fav.pathName.ifEmpty { null },
    )
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
 * Reverse-derive "how to reach it" from the current [XFile]. Same intent as
 * `PaneViewModel.favoriteFrom`, but here we don't depend on the VM's in-session map;
 * we look up via [Connections.ofScheme] — the comparison page is a separate Activity
 * and cannot access that map.
 */
fun compareSideOf(file: XFile): Favorite? = when {
    file.scheme == "file" -> Favorite(label = file.name, kind = "local", path = file.path)
    file.scheme == SafFileSystem.SCHEME ->
        Favorite(label = file.name, kind = "saf", path = file.path, pathName = file.name)
    else -> Connections.ofScheme(file.scheme)?.let {
        Favorite(label = "${it.displayLabel()}:${file.name}", kind = "conn", path = file.path, connLabel = it.label())
    }
}
