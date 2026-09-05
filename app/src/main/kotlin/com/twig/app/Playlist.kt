package com.twig.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One track in a playlist. To be restorable across sessions, the dynamic scheme is
 * not stored; instead we store "how to reach it" (mirroring [Favorite]):
 * - local: path = absolute local path
 * - conn:  connLabel = SavedConnection.label(), path = path within that connection
 * Metadata (title/artist/duration) is cached so the playlist page renders without
 * reading the file; 0/empty = unknown — a background job fills it in and writes back.
 */
data class PlaylistTrack(
    val kind: String,            // "local" | "conn" | "share" (content:// from other apps)
    val path: String,
    val connLabel: String = "",
    val size: Long = 0L,         // File size in bytes — required for network sources:
                                 // openRandom().length() on SMB/WebDAV/etc. reads directly from
                                 // XFile.size; if it's missing (=0), the source EOFs on first read,
                                 // the extractor sees an empty stream, and the network audio never plays.
    val lastModified: Long = 0L, // Captured at import; together with size this keeps MusicEngine.resolve()'s
                                 // rebuilt XFile cache key (Thumbs.keyOf = md5(name:size:mtime)) stable
                                 // across sessions, so we don't have to re-list directories via
                                 // statByListing every time the playlist opens (m3u8 / old lists especially).
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,     // Hz; 0 = unknown (used by the list subtitle)
    val bitrate: Int = 0,        // bps; 0 = unknown
    /** Display name. Required for sources like content:// that don't carry a filename
     * in their path (both name and extension have to come from here). */
    val displayName: String = "",
) {
    /** Stable unique id. */
    val id: String get() = "$kind|$connLabel|$path"

    /** Filename: [displayName] takes priority, otherwise the last segment of the path. */
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
 * A playlist. Special ids:
 * - [NOW] "Now playing": gets "replaced" with the same-folder audio every time
 *   you tap an audio file in the file manager; can be renamed (becomes a regular list).
 * - [FAV] "Favorites": always present, cannot be deleted or renamed; the heart
 *   button adds/removes tracks.
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
        put("id", id)
        // The "Favorites" list's display name is owned by R.string.playlist_favorites
        // (translatable). Persisting a snapshot of it freezes the label in whichever
        // locale was active on first launch, so leave it out and reconstruct from
        // resources on read in [PlaylistStore.fav].
        if (id != FAV) put("name", name)
        put("lastIndex", lastIndex); put("lastPosMs", lastPosMs)
        val a = JSONArray(); tracks.forEach { a.put(it.toJson()) }; put("tracks", a)
    }

    companion object {
        const val NOW = "now"
        const val FAV = "fav"

        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): Playlist {
            val id = o.getString("id")
            val a = o.optJSONArray("tracks") ?: JSONArray()
            return Playlist(
                id = id,
                // FAV's name is always reconstructed by [PlaylistStore.fav]; older saves
                // (pre-fix) may still carry a localized snapshot under "name" but we
                // discard it on read so it stops shadowing the resource.
                name = if (id == FAV) "" else o.getString("name"),
                tracks = (0 until a.length()).map { PlaylistTrack.fromJson(a.getJSONObject(it)) },
                lastIndex = o.optInt("lastIndex", 0),
                lastPosMs = o.optLong("lastPosMs", 0L),
            )
        }
    }
}

/** Playlist persistence (SharedPreferences + JSON, same pattern as [FavoritesStore]). */
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

    /** "Favorites" is always present (created on first access). The display name is
     *  reconstructed from [R.string.playlist_favorites] on every read so a locale change
     *  takes effect immediately; the persisted record therefore carries no `name` field
     *  for this id (see [Playlist.toJson]). */
    fun fav(ctx: Context): Playlist {
        val name = ctx.getString(R.string.playlist_favorites)
        val existing = get(ctx, Playlist.FAV)
            ?: Playlist(Playlist.FAV, "", emptyList()).also { save(ctx, it) }
        return existing.copy(name = name)
    }

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
     * Rename. Regular lists are renamed in place; renaming [Playlist.NOW] is
     * special: it becomes a regular user list with a new uuid (name from the
     * user input) and the original `now` entry is removed — the next audio open
     * rebuilds an empty `now`. Returns the resulting list, or null if not found.
     */
    fun rename(ctx: Context, id: String, name: String): Playlist? {
        val pl = get(ctx, id) ?: return null
        if (pl.isFav) return pl // Favorites cannot be renamed
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
        if (id == Playlist.FAV || id == Playlist.NOW) return // special lists cannot be deleted
        persist(ctx, all(ctx).filter { it.id != id })
    }

    /** Replaces the "Now playing" contents (called when an audio file in a directory is tapped). */
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

    /** Heart button: adds/removes a track in "Favorites". Returns whether it's now a
     * favorite (state after the operation). */
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

    /** Called by the background MMR after metadata backfill: writes back (matched by
     * track.id; only persists if content changed). */
    fun updateTrackMeta(ctx: Context, id: String, track: PlaylistTrack) {
        val pl = get(ctx, id) ?: return
        var changed = false
        val next = pl.tracks.map { if (it.id == track.id && it != track) { changed = true; track } else it }
        if (changed) save(ctx, pl.copy(tracks = next))
    }

    /** Finds the correct index in pl.tracks (the original unfiltered list) by track
     * id, then persists it for [Playlist.lastIndex]. */
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
