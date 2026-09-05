package com.twig.fs.network

import com.twig.core.CoverSource
import com.twig.core.EpisodeSeries
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.MediaDetails
import com.twig.core.LyricsSource
import com.twig.core.MediaInfoSource
import com.twig.core.MediaStream
import com.twig.core.PlayState
import com.twig.core.PlaybackProgress
import com.twig.core.RandomSource
import com.twig.core.SearchSource
import com.twig.core.TextDecoding
import com.twig.core.XFile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Configuration for a Jellyfin / Emby server.
 *
 * Authentication has two paths, picked based on whether [apiKey] is empty:
 *  - **Username + password**: `POST /Users/AuthenticateByName` to exchange for a
 *    token, and the userId comes back with it.
 *  - **API key** (the kind generated in the server's admin UI): it can be used
 *    as a token directly, but **carries no user identity** — "Continue Watching"
 *    and playback progress are per-user, so we still need a `GET /Users` to pick
 *    one: when [user] is non-empty we look up by name, otherwise we take the first.
 */
data class JellyfinConfig(
    /** For example, `http://192.168.1.9:8096`; write the full path when reverse-proxied under a sub-path (`https://h/jellyfin`). */
    val baseUrl: String,
    val user: String = "",
    val password: String = "",
    /** Non-empty = use API key authentication, skip AuthenticateByName. */
    val apiKey: String = "",
    /** true = Emby; only affects the display name (endpoints are the same source, see the class doc). */
    val emby: Boolean = false,
    /** Stable device identifier; Jellyfin uses it to distinguish sessions, must persist across restarts (generated and persisted by the :app layer). */
    val deviceId: String = "twig",
    val deviceName: String = "Android",
    val clientVersion: String = "1.0",
    /** Token from the previous sign-in; when non-empty, try it first, re-authenticate on 401. */
    val token: String = "",
    /** Last resolved userId, saves one `/Users` round trip. */
    val userId: String = "",
    /**
     * Callback after a successful sign-in / user resolution, so the :app layer can
     * persist token and userId back into the connection config.
     * Called on any thread.
     */
    val onAuth: (token: String, userId: String) -> Unit = { _, _ -> },
    /**
     * Display names for virtual directories (id → name), populated by the :app
     * layer from `strings.xml`.
     *
     * ★ This module is pure JVM (no Context, no R), so per project convention
     * everything is written **in English**; "Continue Watching", "Movies" and
     * the like are user-facing, so we go via a "lookup table in the UI layer"
     * instead of turning the whole module into an Android library to access
     * resources (which would cost us millisecond-fast pure-JVM unit tests).
     * Unknown ids fall back to the built-in English name.
     */
    val labels: Map<String, String> = emptyMap(),
)

/**
 * Jellyfin / Emby media server, **read-only**.
 *
 * Emby is an upstream fork of Jellyfin, and these endpoints
 * (`/Users/{uid}/Views`, `/Items`, `Items/Resume`, `Items/Latest`,
 * `/Items/{id}/Download`, `/Sessions/Playing/…`) are the same name on both
 * sides, so **one implementation covers both**; [JellyfinConfig.emby] only
 * affects the display name.
 *
 * ## Tree shape
 *
 * Server entries are GUIDs rather than paths, so this is a **virtual tree**
 * (same pattern as `AppsFileSystem` using the package name as the path):
 * `path` keeps the full ancestor chain with the entry id as its last segment,
 * `displayName` carries the human-readable name.
 * Ten fixed entries at the root (see [VIRTUAL]): Continue Watching / Movies /
 * TV / Photos / Music, the four matching "Latest", and "Folders" for browsing
 * by the libraries' physical hierarchy.
 *
 * ★ **path must include the virtual-directory prefix, not only the id**.
 * The same movie appears both under "Continue Watching" and under "Movies",
 * and the row key on the tree is `f:<scheme>:<path>` — if two rows collide
 * on key, DiffUtil picks the wrong row, with the symptom "the same entry
 * expands correctly in one place, empty in another" (see the `FileNode.keyPrefix`
 * note in `CLAUDE.md`). Adding the prefix makes collision impossible.
 *
 * ## Playback
 *
 * Uses only **raw stream direct passthrough** (`/Items/{id}/Download`,
 * falling back to `/Videos|Audio/{id}/stream` when blocked), without any
 * server-side transcoding negotiation: the direct stream is one byte stream
 * that accepts HTTP Range, so [openRandom] reuses [HttpRangeSource] and the
 * whole player-seek + network-video-thumbnail stack (MP4 moov / MKV Cues
 * positioning) benefits with zero changes. The cost is that codecs the
 * device cannot decode natively (10-bit H.264 etc.) still cannot be decoded.
 *
 * File names carry the real extension (taken from the server's `Path` or
 * `Container`) — the player uses it to identify the container, and a fake
 * extension makes media3 fall back to sniffing files one by one (see the AVI
 * note in `CLAUDE.md`).
 *
 * ## Progress sync
 *
 * Implements [PlaybackProgress]: the resume position is read from the server's
 * `UserData.PlaybackPositionTicks`, and during playback we report through the
 * `/Sessions/Playing/…` trio, so watching halfway on the phone lets the web
 * client pick up where you left off. A tick is 100 ns, so ms × 10000.
 */
class JellyfinFileSystem(
    private val config: JellyfinConfig,
    override val scheme: String = SCHEME,
) : FileSystem, PlaybackProgress, CoverSource, MediaInfoSource, LyricsSource, SearchSource,
    EpisodeSeries {

    override val displayName: String =
        (if (config.emby) "Emby" else "Jellyfin") + " (${config.baseUrl})"

    private val base: HttpUrl = config.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw FsException("Bad server URL: ${config.baseUrl}")

    /** Timeout choices match WebDAV: short connect (fail fast when unreachable), generous read/write (playback is a long-lived connection). */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    // ---- authentication ----

    private val authLock = Any()

    @Volatile private var token: String = config.apiKey.ifEmpty { config.token }

    @Volatile private var uid: String = config.userId

    /**
     * Ensures we have a usable token + userId.
     *
     * If we already have them, we just use them (we do not probe for validity on
     * every list — that would burn a round trip for nothing); real expiry is
     * handled by [call] clearing them and retrying on a 401.
     */
    private fun ensureAuth(): String {
        if (token.isNotEmpty() && uid.isNotEmpty()) return uid
        synchronized(authLock) {
            if (token.isNotEmpty() && uid.isNotEmpty()) return uid
            if (config.apiKey.isNotEmpty()) {
                token = config.apiKey
                uid = pickUser()
            } else {
                login()
            }
            config.onAuth(token, uid)
            return uid
        }
    }

    private fun login() {
        if (config.user.isEmpty()) throw FsException("A username is required to sign in")
        val body = JSONObject()
            .put("Username", config.user)
            .put("Pw", config.password)
            .toString()
        val req = Request.Builder()
            .url(base.newBuilder().addPathSegments("Users/AuthenticateByName").build())
            .post(body.toRequestBody(JSON))
            .apply { authHeaders(this, withToken = false) }
            .build()
        val json = http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw FsException("Sign-in failed: wrong username or password")
            if (!resp.isSuccessful) throw FsException("Sign-in failed: HTTP ${resp.code} $text")
            runCatching { JSONObject(text) }
                .getOrElse { throw FsException("Sign-in returned no JSON: $text") }
        }
        val t = json.optString("AccessToken")
        if (t.isEmpty()) throw FsException("Sign-in returned no AccessToken")
        val u = json.optJSONObject("User")?.optString("Id").orEmpty()
        if (u.isEmpty()) throw FsException("Sign-in returned no user id")
        token = t
        uid = u
    }

    /**
     * Picks a user in API-key mode: when [JellyfinConfig.user] is non-empty,
     * look it up by name; otherwise take the first one.
     * The API key itself carries no user identity, but "Continue Watching" /
     * playback progress are per-user.
     */
    private fun pickUser(): String {
        val arr = getArray(base.newBuilder().addPathSegment("Users").build())
        if (arr.length() == 0) throw FsException("The server reports no users")
        val want = config.user.trim()
        // Username left blank + the server has multiple users = we probably
        // picked someone other than the actual user, in which case
        // "Continue Watching" and progress point at their records — symptom:
        // "Continue Watching is always empty, everything else works", which
        // looks identical to a missing Recursive parameter. One log line here
        // makes the two cases distinguishable.
        if (want.isEmpty() && arr.length() > 1) {
            val names = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("Name") }
            System.err.println(
                "twig: jellyfin no username given, using the first of ${arr.length()} users " +
                    "(${names.joinToString(", ")}) — fill in the username if that is not you",
            )
        }
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            if (!want.isEmpty() && !u.optString("Name").equals(want, ignoreCase = true)) continue
            val id = u.optString("Id")
            if (id.isNotEmpty()) return id
        }
        throw FsException("No such user on the server: $want")
    }

    /**
     * Authentication headers for Jellyfin and Emby. Sending all three together is
     * intentional:
     * `Authorization` is Jellyfin 10.8+'s preferred header, `X-Emby-Authorization`
     * is its still-supported legacy name and Emby's canonical form, and
     * `X-Emby-Token` is where Emby looks for the token alone.
     * A few dozen extra bytes buys us "the same code works against both servers".
     *
     * Client / Device / DeviceId / Version are **required** by Jellyfin — missing
     * any of them returns 400 directly.
     */
    private fun authHeaders(b: Request.Builder, withToken: Boolean = true) {
        val t = if (withToken) token else ""
        val sb = StringBuilder("MediaBrowser Client=\"Twig\"")
        sb.append(", Device=\"").append(sanitize(config.deviceName)).append('"')
        sb.append(", DeviceId=\"").append(sanitize(config.deviceId)).append('"')
        sb.append(", Version=\"").append(sanitize(config.clientVersion)).append('"')
        if (t.isNotEmpty()) sb.append(", Token=\"").append(t).append('"')
        val v = sb.toString()
        b.header("Authorization", v)
        b.header("X-Emby-Authorization", v)
        if (t.isNotEmpty()) b.header("X-Emby-Token", t)
    }

    /** Header values must be ASCII-visible characters: the device name can contain non-ASCII / quotes, so sanitize before placing it in a header. */
    private fun sanitize(s: String): String =
        s.filter { it.code in 0x20..0x7e && it != '"' && it != '\\' }.ifEmpty { "Twig" }

    // ---- HTTP ----

    /**
     * Sends a request and requires success; on 401, clears the token, re-authenticates
     * once and retries (server restart / session invalidation invalidates the
     * token, and that should not look like "the whole connection is broken").
     * API keys do not expire, so a 401 from one really means no permission and
     * is not retried.
     */
    private fun call(build: () -> Request.Builder): Response {
        var resp = exec(build())
        if (resp.code == 401 && config.apiKey.isEmpty()) {
            resp.close()
            synchronized(authLock) { token = ""; uid = "" }
            ensureAuth()
            resp = exec(build())
        }
        if (!resp.isSuccessful) {
            val msg = resp.use { it.body?.string().orEmpty().take(200) }
            throw FsException("HTTP ${resp.code} ${resp.message} @ ${resp.request.url.encodedPath}: $msg")
        }
        return resp
    }

    private fun exec(b: Request.Builder): Response {
        authHeaders(b)
        val req = b.build()
        return try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw FsException("Request failed @ ${req.url.encodedPath}: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private fun getText(url: HttpUrl): String =
        call { Request.Builder().url(url).get() }.use { it.body?.string().orEmpty() }

    private fun getObject(url: HttpUrl): JSONObject {
        val text = getText(url)
        return runCatching { JSONObject(text) }
            .getOrElse { throw FsException("Expected a JSON object @ ${url.encodedPath}: ${text.take(200)}") }
    }

    private fun getArray(url: HttpUrl): JSONArray {
        val text = getText(url)
        return runCatching { JSONArray(text) }
            .getOrElse { throw FsException("Expected a JSON array @ ${url.encodedPath}: ${text.take(200)}") }
    }

    /**
     * Extracts the items table from a response that may be either
     * `{Items:[…]}` or a bare array.
     *
     * ★ These two shapes are decided by the endpoint and cannot be guessed:
     * `/Items`, `Items/Resume`, `/Views` reply with `{Items,TotalRecordCount}`,
     * while **`Items/Latest` replies with a bare array**. Hard-coding one
     * silently turns the other into an empty list — the directory opens empty
     * without any error.
     */
    private fun itemsOf(text: String, url: HttpUrl): JSONArray {
        val t = text.trimStart()
        if (t.startsWith("[")) {
            return runCatching { JSONArray(t) }
                .getOrElse { throw FsException("Bad JSON array @ ${url.encodedPath}: ${t.take(200)}") }
        }
        val o = runCatching { JSONObject(t) }
            .getOrElse { throw FsException("Bad JSON @ ${url.encodedPath}: ${t.take(200)}") }
        return o.optJSONArray("Items") ?: JSONArray()
    }

    // ---- virtual tree ----

    override fun root(): XFile = XFile(scheme, "/", isDir = true, canWrite = false)

    override fun list(dir: XFile): List<XFile> {
        val uid = ensureAuth()
        val segs = segsOf(dir.path)
        if (segs.isEmpty()) return rootEntries(uid)
        val top = segs[0]
        if (top !in VIRTUAL_IDS) throw FsException("Unknown Jellyfin directory: ${dir.path}")

        // The first level under "Latest" and "Folders" is the library itself (named by the user on the server)
        if (segs.size == 1 && (top == ID_LATEST || top == ID_FOLDERS)) {
            return libraries(uid).map { lib -> libEntry(lib, "/$top") }
        }
        // Clicking into a music library yields four fixed sections (matching the official client's tabs), not a pile of physical folders
        if (top == ID_LIB && segs.size == 2 && libraryType(uid, segs[1]) == "music") {
            return MUSIC_SECTIONS.map { seg ->
                XFile(
                    scheme, "${dir.path.trimEnd('/')}/$seg", isDir = true, canWrite = false,
                    displayName = config.labels[seg] ?: FALLBACK_LABELS[seg] ?: seg,
                )
            }
        }

        val items = when {
            segs.size == 1 -> topLevelItems(uid, top)
            top == ID_LATEST && segs.size == 2 -> latestOfLibrary(uid, segs[1])
            top == ID_LIB && segs.size == 2 -> libraryItems(uid, segs[1])
            top == ID_LIB && segs.size == 3 && segs[2] in MUSIC_SECTIONS ->
                musicSection(uid, segs[1], segs[2])
            // When a series has only one season, skip the season layer and go straight to episodes — one extra level adds no information
            else -> childrenOrSingleSeason(uid, segs.last(), keepsServerOrder(dir.path))
        }
        // "This directory was always empty" and "wrong query, server returned 200 with an empty Items" look identical in the UI
        // (Emby's Resume missing Recursive does exactly this); one log line makes the two distinguishable
        if (items.length() == 0) {
            System.err.println("twig: jellyfin empty listing @ ${dir.path}")
        }
        val out = ArrayList<XFile>(items.length())
        val used = HashMap<String, Int>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val id = it.optString("Id")
            if (id.isEmpty()) continue
            cache.put(id, it)
            val path = dir.path.trimEnd('/') + "/" + id
            rememberCover(it, id, path)
            out += toXFile(it, path, used)
        }
        return withSizes(out)
    }

    /**
     * Probed byte sizes (entry id → size). File sizes do not change, so once
     * queried they stay valid; this is not TTL-cached like [cache].
     */
    private val probedSizes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val probePool by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(PROBE_CONCURRENCY) { r ->
            Thread(r, "twig-jf-size").apply { isDaemon = true }
        }
    }

    /**
     * Fills in the byte size the server omits — **photos fall into this case**
     * (2026-08-18):
     * `Photo` entries **have no `MediaSources`**, and the `ItemFields` enum
     * **has no `Size`** either (confirmed by checking `/api-docs/openapi.json`;
     * `Fields=Size` is silently ignored by the server). Both servers behave the
     * same way, so every photo in a library shows as 0 B.
     *
     * The only workaround is to ask once: `Range: bytes=0-0` returns a
     * `Content-Range: bytes 0-0/27836` header, where the divisor is the total
     * length — only one byte is transferred. (`HEAD` does not work; both
     * servers reply 405 in practice.)
     *
     * A few limits, so listing one directory does not balloon into hundreds of
     * requests: if there are more than [PROBE_MAX] entries without size we skip
     * the whole batch (a library of thousands of photos is not worth that);
     * [PROBE_CONCURRENCY] in parallel; the whole batch waits at most
     * [PROBE_BUDGET_MS] — those that time out stay at 0 and do not stall the
     * directory from opening.
     */
    private fun withSizes(files: List<XFile>): List<XFile> {
        val missing = files.filter { !it.isDir && it.size <= 0L }
        if (missing.isEmpty() || missing.size > PROBE_MAX) return files
        val found = HashMap<String, Long>()
        val pending = ArrayList<Pair<String, java.util.concurrent.Future<*>>>()
        for (f in missing) {
            val id = f.path.substringAfterLast('/')
            val known = probedSizes[id]
            if (known != null) {
                found[id] = known
                continue
            }
            pending += id to probePool.submit {
                val n = runCatching { probeSize(id) }.getOrDefault(0L)
                if (n > 0) probedSizes[id] = n
            }
        }
        val deadline = System.currentTimeMillis() + PROBE_BUDGET_MS
        for ((id, task) in pending) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break // budget exhausted: leave the rest at 0 rather than freezing the directory
            runCatching { task.get(left, TimeUnit.MILLISECONDS) }
            probedSizes[id]?.let { found[id] = it }
        }
        if (found.isEmpty()) return files
        return files.map { f -> found[f.path.substringAfterLast('/')]?.let { f.copy(size = it) } ?: f }
    }

    /** @return the entry's total byte count; 0 when it cannot be determined. */
    private fun probeSize(id: String): Long {
        // If downloads are forbidden this will fail, and we keep "size unknown"
        // — re-querying MediaType to switch to the stream endpoint is not worth
        // it; this is a nice-to-have step to begin with
        val resp = exec(Request.Builder().url(downloadUrl(id)).header("Range", "bytes=0-0").get())
        resp.use {
            if (!it.isSuccessful) return 0L
            val range = it.header("Content-Range") ?: return 0L
            return range.substringAfterLast('/').trim().toLongOrNull() ?: 0L
        }
    }

    /**
     * Name clashes within one layer: two same-named movies (different-year
     * remasters) are legal on the server, but if `displayName` collides the
     * UI cannot tell them apart at all, and copying them into the same
     * directory would overwrite each other.
     * The `path` carries the id so the row key does not collide; here we only
     * disambiguate the **display** with a numeric suffix.
     */
    private fun uniqueName(name: String, used: HashMap<String, Int>): String {
        val n = used.merge(name, 1, Int::plus) ?: 1
        if (n == 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }

    private fun virtualDir(id: String): XFile = XFile(
        scheme, "/$id", isDir = true, canWrite = false,
        displayName = config.labels[id] ?: FALLBACK_LABELS[id] ?: id,
    )

    /**
     * Which entries appear at the root, **depending on what libraries actually
     * exist on the server** (revised on 2026-08-19 based on user feedback).
     *
     * Previously this was a fixed twelve: with no music library there would still
     * be a "Music" and a "Latest Music" entry, both leading to empties; when a
     * type had two libraries they were hard-merged into one and the library
     * names ("Japanese Drama", "Documentary") were all lost.
     * Now only real entries are listed, and the library uses the name the user
     * gave it.
     */
    private fun rootEntries(uid: String): List<XFile> {
        val out = ArrayList<XFile>()
        out += virtualDir(ID_RESUME)
        val views = views(uid)
        fun hasType(t: String) = (0 until views.length()).any {
            views.optJSONObject(it)?.optString("CollectionType") == t
        }
        if (hasType("playlists")) out += virtualDir(ID_PLAYLISTS)
        if (hasType("boxsets")) out += virtualDir(ID_COLLECTIONS)
        val libs = libraries(uid)
        if (libs.isNotEmpty()) {
            out += virtualDir(ID_LATEST)
            // ★ Libraries **go straight on the root** (using the name the user
            // set on the server); clicking into them yields that library's most
            // natural organisation (see [libraryItems]). "Folders" stays for the
            // "browse by physical hierarchy" flow.
            libs.forEach { out += libEntry(it, "/$ID_LIB") }
            out += virtualDir(ID_FOLDERS)
        }
        return out
    }

    private fun libEntry(lib: JSONObject, parentPath: String): XFile = XFile(
        scheme, "$parentPath/${lib.optString("Id")}", isDir = true, canWrite = false,
        displayName = lib.optString("Name"),
    )

    /** A library's `CollectionType` (movies / tvshows / music / homevideos ...); empty string when unrecognised. */
    private fun libraryType(uid: String, libId: String): String =
        libraries(uid).firstOrNull { it.optString("Id") == libId }?.optString("CollectionType").orEmpty()

    /**
     * What you see when you open a library — **always recurse for entries of
     * the matching type, never show physical folders** (2026-08-19).
     *
     * ★ Previously this used the "library root" (`ParentId=<lib>` without a
     * type), which seemed right on my test library but **only holds when the
     * library has no subfolders**: the moment the library is split by year/region,
     * the library root returns the **folders themselves** like `Folder/2020`,
     * `Folder/Japanese Drama` and you have to drill in level by level to reach
     * the items. The recursive query flattens them: movies get `Movie`, series
     * get `Series`, photo libraries get `PhotoAlbum` (every "click to see
     * images" directory), music sees [musicSection].
     * Mixed-type libraries whose type we cannot recognise still fall back to
     * the library root — such a library has no "natural first layer" anyway.
     */
    private fun libraryItems(uid: String, libId: String): JSONArray {
        val types = when (libraryType(uid, libId)) {
            "movies" -> "Movie"
            "tvshows" -> "Series"
            "homevideos", "photos" -> "PhotoAlbum"
            "books" -> "Book"
            else -> return fetchUrl(childrenUrl(uid, libId))
        }
        return fetchUrl(queryUrl(uid, types, "SortName", false, null, parentId = libId))
    }

    /**
     * The four sections of a music library. The first two go through the server's
     * existing artist indexes.
     *
     * ★ `@folder` wants **every directory that directly contains music files**
     * (same reasoning as the photo case), not "the library root with one extra
     * level to drill into" — the server's notion of "a directory that contains
     * music" is exactly `MusicAlbum` (even an empty tag still gets bucketed as
     * an album), so this reuses the same query as [SEG_ALBUMS]. The entry stays
     * because the two have different **semantics** (one is "album", the other
     * "directory"); if we ever need to separate them by physical structure, the
     * single change is here.
     */
    private fun musicSection(uid: String, libId: String, section: String): JSONArray = when (section) {
        SEG_ALBUM_ARTISTS -> fetchUrl(artistsUrl(uid, libId, albumArtists = true))
        SEG_ARTISTS -> fetchUrl(artistsUrl(uid, libId, albumArtists = false))
        else -> fetchUrl(queryUrl(uid, "MusicAlbum", "SortName", false, null, parentId = libId))
    }

    private fun artistsUrl(uid: String, libId: String, albumArtists: Boolean): HttpUrl = base.newBuilder()
        .addPathSegments(if (albumArtists) "Artists/AlbumArtists" else "Artists")
        .addQueryParameter("ParentId", libId)
        .addQueryParameter("UserId", uid)
        .addQueryParameter("Fields", FIELDS)
        .build()

    /**
     * Lists children; **when a series has only one season, skip the season layer**
     * and go straight to that season's episodes — one extra level adds no
     * information (multi-season series still break down by season, where it
     * does add information). The cost is one extra request for single-season
     * series, only at expansion time.
     */
    private fun childrenOrSingleSeason(uid: String, parentId: String, keepOrder: Boolean): JSONArray {
        val url = childrenUrl(uid, parentId, keepOrder = keepOrder)
        val items = itemsOf(getText(url), url)
        val only = items.takeIf { it.length() == 1 }?.optJSONObject(0)
        if (only != null && only.optString("Type") == "Season") {
            val seasonId = only.optString("Id")
            if (seasonId.isNotEmpty()) {
                val sub = childrenUrl(uid, seasonId)
                return itemsOf(getText(sub), sub)
            }
        }
        return items
    }

    /** Browsable libraries: exclude playlists/collections (already have dedicated root entries) and the nested "Folders" view. */
    private fun libraries(uid: String): List<JSONObject> {
        val arr = views(uid)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("CollectionType") !in HIDDEN_VIEWS }
            .filter { it.optString("Id").isNotEmpty() }
    }

    /** The non-library entries at the root, at their own level. */
    private fun topLevelItems(uid: String, top: String): JSONArray = when (top) {
        ID_RESUME -> fetchUrl(resumeUrl(uid))
        ID_PLAYLISTS -> fetchUrl(recursiveUrl(uid, "Playlist"))
        ID_COLLECTIONS -> fetchUrl(recursiveUrl(uid, "BoxSet"))
        // Container for search results; this is not an actual entry on the tree; if it is listed somehow it is empty, which is not an error
        ID_SEARCH -> JSONArray()
        else -> throw FsException("Unknown Jellyfin directory: /$top")
    }

    private fun toXFile(item: JSONObject, path: String, used: HashMap<String, Int>): XFile {
        // ★ Do not trust IsFolder alone: dedicated endpoints like `/Artists`
        // may not even return it, in which case artists and albums become
        // "files" — they cannot be opened and we try to probe their size.
        // The entry's Type is a more reliable signal.
        val folder = item.optBoolean("IsFolder", false) || item.optString("Type") in CONTAINER_TYPES
        val src = item.optJSONArray("MediaSources")?.optJSONObject(0)
        return XFile(
            scheme = scheme,
            path = path,
            isDir = folder,
            size = if (folder) 0L else src?.optLong("Size", 0L) ?: 0L,
            lastModified = parseTime(item.optString("DateCreated")),
            canWrite = false,
            displayName = uniqueName(displayNameOf(item, folder, src), used),
        )
    }

    /**
     * Display name = human-readable name + **real extension** (no extension on
     * directories).
     *
     * Extension sources by reliability: the server's `Path` (original filename,
     * most faithful) → `MediaSources`' `Container` (may be a comma list like
     * `mkv,webm`, take the first) → guessed from `MediaType`.
     * It must be present: the player and viewer dispatch depends on it; a fake
     * one makes media3 fall back to sniffing files one by one.
     *
     * Episodes additionally get `SxxEyy` — on the server an episode's `Name` is
     * just the episode title, so a whole season laid out flat cannot be sorted
     * by name alone.
     */
    private fun displayNameOf(item: JSONObject, folder: Boolean, src: JSONObject?): String {
        val raw = item.optString("Name").ifEmpty { item.optString("Id") }
        val season = item.optInt("ParentIndexNumber", -1)
        val ep = item.optInt("IndexNumber", -1)
        val prefix = if (item.optString("Type") == "Episode") {
            buildString {
                // Series name first: in flat lists like "Continue Watching" /
                // "Latest Episodes" you cannot recognise which series you are
                // looking at from an SxxExx and a single-episode title; when
                // copying to a local file it is also a more useful filename.
                // ★ When metadata was not scraped, `Name` is the filename and
                // may already start with the series name (Emby does this in
                // practice), so do not prepend it again in that case.
                val series = item.optString("SeriesName")
                if (series.isNotEmpty() && !raw.startsWith(series)) append(series).append(" - ")
                if (ep >= 0) {
                    val tag = if (season >= 0) "S%02dE%02d".format(season, ep) else "E%02d".format(ep)
                    // Same idea: filenames often already contain S01E01, and
                    // appending it again yields "S01E01 - show name - S01E01"
                    // (seen in practice on un-scraped libraries)
                    if (!raw.contains(tag, ignoreCase = true)) append(tag).append(" - ")
                }
            }
        } else {
            ""
        }
        // '/' is treated as a path separator (WiFi sharing uses the name to locate files level by level, and URL segments are encoded that way)
        val name = (prefix + raw).replace('/', '_').replace('\\', '_').trim()
        if (folder) return name
        val ext = extOf(item, src)
        return if (ext.isEmpty() || name.endsWith(".$ext", ignoreCase = true)) name else "$name.$ext"
    }

    private fun extOf(item: JSONObject, src: JSONObject?): String {
        val path = item.optString("Path")
        if (path.isNotEmpty()) {
            val tail = path.substringAfterLast('/').substringAfterLast('\\')
            val dot = tail.lastIndexOf('.')
            if (dot > 0 && dot < tail.length - 1) return tail.substring(dot + 1).lowercase()
        }
        val container = (src?.optString("Container") ?: item.optString("Container"))
            .substringBefore(',').trim()
        if (container.isNotEmpty()) return container.lowercase()
        return when (item.optString("MediaType")) {
            "Video" -> "mp4"
            "Audio" -> "mp3"
            "Photo" -> "jpg"
            else -> ""
        }
    }

    private fun parseTime(iso: String): Long {
        if (iso.isEmpty()) return 0L
        // Jellyfin gives ISO 8601 with a variable number of fractional-second digits (sometimes none) — truncate to whole seconds for uniform handling
        val t = iso.substringBefore('.').trimEnd('Z')
        return runCatching { ISO.get()!!.parse(t)?.time ?: 0L }.getOrDefault(0L)
    }

    // ---- Single entry ----

    /**
     * Both [resolve] and [openInput] need "given a path, get the entry itself";
     * this is the shared place.
     *
     * ★ Cannot just do `XFile(scheme, path, isDir = true)` the way
     * `SftpFileSystem.resolve` does — that "everything is a directory"
     * implementation caused a real-device incident once (see the git worktree
     * note in `CLAUDE.md`). Here we actually ask the server.
     */
    private fun itemOf(id: String): JSONObject {
        cache.get(id)?.let { return it }
        val uid = ensureAuth()
        val url = base.newBuilder()
            .addPathSegments("Users/$uid/Items/$id")
            .addQueryParameter("Fields", FIELDS)
            .build()
        return getObject(url).also { cache.put(id, it) }
    }

    override fun resolve(path: String): XFile {
        val segs = segsOf(path)
        if (segs.isEmpty()) return root()
        if (segs[0] !in VIRTUAL_IDS) throw FsException("Unknown Jellyfin directory: $path")
        if (segs.size == 1) return virtualDir(segs[0])
        // The first level under "Latest" / "Folders" is the library, which is also a regular entry
        return toXFile(itemOf(segs.last()), path, HashMap())
    }

    override fun exists(file: XFile): Boolean {
        val segs = segsOf(file.path)
        if (segs.isEmpty()) return true
        if (segs[0] !in VIRTUAL_IDS) return false
        if (segs.size == 1) return true
        return runCatching { itemOf(segs.last()) }.isSuccess
    }

    // ---- Reads ----

    /**
     * Raw file passthrough. `/Items/{id}/Download` is the most faithful one
     * (bytes identical to the file on the server), but it requires the account
     * to have download permission; when denied we fall back to the playback
     * endpoint `/Videos|Audio/{id}/stream` — which is also raw passthrough
     * (`static=true`), just not served for non-media files.
     *
     * Once denied, we remember it ([downloadBlocked]) and go straight to the
     * fallback: every file hitting 403 first is a wasted round trip, and during
     * a bulk copy that cost is multiplied by the file count.
     */
    @Volatile private var downloadBlocked = false

    private fun downloadUrl(id: String): HttpUrl =
        base.newBuilder().addPathSegments("Items/$id/Download").build()

    /**
     * Fallback endpoint. **Only this one needs to know whether the entry is a
     * video or audio**, so only this one asks for metadata — see the performance
     * note in [Media].
     */
    private fun streamUrl(id: String): HttpUrl {
        val seg = when (itemOf(id).optString("MediaType")) {
            "Audio" -> "Audio"
            "Video" -> "Videos"
            else -> throw FsException("The server refused to let this item be downloaded")
        }
        return base.newBuilder()
            .addPathSegments("$seg/$id/stream")
            .addQueryParameter("static", "true")
            .build()
    }

    /**
     * The media URL and byte-fetching actions for one "open".
     *
     * ★ **The URL is computed exactly once here**, after which every Range
     * request simply GETs it (settled on 2026-08-17): the old implementation
     * called `itemOf()` for every new stream, and its only purpose was
     * "distinguish Video/Audio when falling back". The cost was one extra
     * **serial round trip per seek**, and the response carried `MediaSources`
     * (all audio/subtitle stream details, a few KB to tens of KB), while the
     * entry cache was only 30 s so it expired frequently.
     * Symptom: "4K playback stutters, seeking is even worse; the same file over
     * SMB/WebDAV plays smoothly" — those two paths make one pread / one Range
     * GET per seek with no extra round trips.
     * The normal path (server allows downloads) now sends zero metadata requests.
     */
    private inner class Media(private val file: XFile) {

        private val id = idOf(file)

        /** Shared across multiple threads (player main read + prefetch thread); overwritten when we fall back. */
        @Volatile private var url: HttpUrl = if (downloadBlocked) streamUrl(id) else downloadUrl(id)

        fun open(position: Long): Response {
            var resp = exec(request(position))
            // Server forbids download: switch to the playback endpoint. The whole connection only reaches here once (downloadBlocked remembers it)
            if ((resp.code == 403 || resp.code == 404) && !downloadBlocked) {
                resp.close()
                downloadBlocked = true
                url = streamUrl(id)
                resp = exec(request(position))
            }
            // Token expires mid-playback (server restart) must not look like "this file is broken": re-authenticate and try again
            if (resp.code == 401 && config.apiKey.isEmpty()) {
                resp.close()
                synchronized(authLock) { token = ""; uid = "" }
                ensureAuth()
                resp = exec(request(position))
            }
            // 416 means "start is out of range"; HttpRangeSource itself recognises this code (normal termination at end of file)
            if (!resp.isSuccessful && resp.code != 416) {
                val msg = resp.use { it.body?.string().orEmpty().take(200) }
                throw FsException("Download failed: HTTP ${resp.code} for ${file.name}: $msg")
            }
            return resp
        }

        private fun request(position: Long) = Request.Builder()
            .url(url)
            .apply { if (position > 0) header("Range", "bytes=$position-") }
            .get()
    }

    override fun openInput(file: XFile): InputStream {
        // Pseudo subtitle path (made up by [subtitlesOf]): goes through the subtitle endpoint, not the media bytes
        subRefOf(file.path)?.let { (itemId, index) -> return openSubtitle(itemId, index) }
        val resp = Media(file).open(0)
        val body = resp.body ?: throw FsException("Empty response for ${file.name}")
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                runCatching { super.close() }
                resp.close()
            }
        }
    }

    override fun openRandom(file: XFile): RandomSource {
        // If we already know the size, don't ask again (player/copy pass XFile with size attached); only look it up once otherwise
        val len = if (file.size > 0) file.size else runCatching {
            itemOf(idOf(file)).optJSONArray("MediaSources")?.optJSONObject(0)?.optLong("Size", 0L) ?: 0L
        }.getOrDefault(0L)
        val media = Media(file)
        return HttpRangeSource(len) { position -> media.open(position) }
    }

    /** HTTP Range — positioned reads are O(1) regardless of position; network video thumbnails and player seek benefit equally. */
    override fun randomAccessEfficient(): Boolean = true

    // ---- Covers ----

    /**
     * Pre-made artwork on the server. Two wins over "download the video bytes
     * and extract a frame ourselves": that frame is human-picked, and the
     * **folder entries** (photo albums / albums / series) have no bytes to
     * extract from at all.
     *
     * Have the server downscale to [maxPx] before sending: a 96 dp tile on the
     * phone does not need a 2000 px source image.
     */
    /**
     * "Continue Watching" uses **landscape backdrops**, not portrait posters:
     * that row is the "where do I pick up next" entry, and a backdrop is
     * instantly recognisable as a particular moment; lined up with other rows,
     * a portrait poster would also make the row much taller.
     *
     * Priority `Thumb → Backdrop → Primary`. **Episodes always take the image
     * from the series level**, not their own `ImageTags.Primary` — that is a
     * screenshot of this episode, and every episode of the same series looks
     * different, so the list cannot recognise which series it is.
     *
     * ★ The Resume response **already includes all these fields by default**
     * (verified against real servers of both projects on 2026-08-19, field
     * names match exactly), so no extra requests are needed:
     *
     * | | Movies | Episodes |
     * |---|---|---|
     * | Thumb | `ImageTags.Thumb` → itself | `ParentThumbImageTag` + `ParentThumbItemId` |
     * | Backdrop | `BackdropImageTags[]` → itself | `ParentBackdropImageTags[]` + `ParentBackdropItemId` |
     * | Primary | `ImageTags.Primary` → itself | `SeriesPrimaryImageTag` + `SeriesId` |
     *
     * When none of the three is present, returns null and the caller falls
     * back to [primaryImage] (the entry's own Primary) — "no image" is worse
     * than "image less appropriate".
     */
    private fun resumeImage(item: JSONObject, id: String): Pair<String, String>? {
        if (item.optString("Type") == "Episode") {
            val series = item.optString("SeriesId")
            if (item.optString("ParentThumbImageTag").isNotEmpty()) {
                val t = item.optString("ParentThumbItemId").ifEmpty { series }
                if (t.isNotEmpty()) return t to "Thumb"
            }
            if ((item.optJSONArray("ParentBackdropImageTags")?.length() ?: 0) > 0) {
                val t = item.optString("ParentBackdropItemId").ifEmpty { series }
                if (t.isNotEmpty()) return t to "Backdrop"
            }
            if (series.isNotEmpty() && item.optString("SeriesPrimaryImageTag").isNotEmpty()) {
                return series to "Primary"
            }
            return null
        }
        val tags = item.optJSONObject("ImageTags")
        if (tags?.optString("Thumb")?.isNotEmpty() == true) return id to "Thumb"
        if ((item.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0) return id to "Backdrop"
        if (tags?.optString("Primary")?.isNotEmpty() == true) return id to "Primary"
        return null
    }

    /** Which image to pull for this entry: "Continue Watching" uses landscape backdrops, everywhere else uses the portrait poster. */
    private fun imageRefOf(item: JSONObject, id: String, resume: Boolean): Pair<String, String>? =
        (if (resume) resumeImage(item, id) else null) ?: primaryImage(item, id)

    /** Side-effect during listing/search: record how to fetch the image for each entry, see [coverRefs]. */
    private fun rememberCover(item: JSONObject, id: String, path: String) {
        val ref = imageRefOf(item, id, segsOf(path).firstOrNull() == ID_RESUME) ?: return
        synchronized(coverRefs) { coverRefs[path] = ref }
    }

    /** Elsewhere (movie library / series / album / photo album) always uses the entry's own Primary; episodes fall back to the series'. */
    private fun primaryImage(item: JSONObject, id: String): Pair<String, String>? {
        val tags = item.optJSONObject("ImageTags")
        val hasPrimary = tags?.has("Primary") == true || item.optString("PrimaryImageTag").isNotEmpty()
        if (hasPrimary) return id to "Primary"
        if (item.optString("SeriesPrimaryImageTag").isEmpty()) return null
        return item.optString("SeriesId").ifEmpty { return null } to "Primary"
    }

    override fun openCover(file: XFile, maxPx: Int): InputStream? {
        val segs = segsOf(file.path)
        if (segs.size < 2) return null
        val id = segs.last()
        // Use what was recorded during listing — skip one serial item query (the main reason covers are slow, see coverRefs)
        val known = synchronized(coverRefs) { coverRefs[file.path] }
        val item = if (known != null) null else {
            // Swallowing the exception here means "this entry has no cover", which is indistinguishable from "no image at all" in the UI — leave one log line, otherwise debugging devolves into guessing (this happened on 2026-08-18)
            runCatching { itemOf(id) }.getOrElse {
                System.err.println("twig: jellyfin cover: cannot load item $id: ${it.message}")
                return null
            }
        }
        // Do not fetch from an entry with no image: the server replies 404, which looks identical to a real error in the logs
        val ref = known ?: imageRefOf(item!!, id, segs[0] == ID_RESUME) ?: return null
        val url = base.newBuilder()
            .addPathSegments("Items/${ref.first}/Images/${ref.second}")
            .addQueryParameter("maxHeight", maxPx.toString())
            .addQueryParameter("maxWidth", maxPx.toString())
            .build()
        val resp = exec(Request.Builder().url(url).get())
        if (!resp.isSuccessful) {
            // "No image" and "could not load" both look like "no thumbnail at the row end"; with no way to tell them apart we would just guess
            System.err.println("twig: jellyfin cover HTTP ${resp.code} @ ${url.encodedPath}")
            resp.close()
            return null
        }
        val body = resp.body ?: run { resp.close(); return null }
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                runCatching { super.close() }
                resp.close()
            }
        }
    }

    // ---- Media details ----

    /**
     * Data source for the properties card. **Usually no requests at all** —
     * the listing already carries `Fields=…MediaSources`, which contains the
     * full stream info, and that JSON is still in [cache] (opening the
     * properties card always follows listing the parent directory).
     *
     * This is another reason not to do "list without MediaSources, fetch when
     * needed" (see [FIELDS]): every properties card open would then need an
     * extra round trip.
     */
    override fun detailsOf(file: XFile): MediaDetails? {
        if (file.isDir) return null
        val id = idOf(file)
        val item = runCatching { itemOf(id) }.getOrNull() ?: return null
        val src = item.optJSONArray("MediaSources")?.optJSONObject(0)
        val streams = src?.optJSONArray("MediaStreams") ?: item.optJSONArray("MediaStreams")
        val out = ArrayList<MediaStream>()
        if (streams != null) {
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                val kind = when (s.optString("Type")) {
                    "Video" -> MediaStream.Kind.VIDEO
                    "Audio" -> MediaStream.Kind.AUDIO
                    "Subtitle" -> MediaStream.Kind.SUBTITLE
                    else -> continue // Data/EmbeddedImage — not exposed in the properties card
                }
                out += MediaStream(
                    kind = kind,
                    codec = s.optString("Codec"),
                    language = s.optString("Language"),
                    title = s.optString("DisplayTitle").ifEmpty { s.optString("Title") },
                    width = s.optInt("Width"),
                    height = s.optInt("Height"),
                    // Either of the two frame-rate fields may be missing; the average frame rate is closer to what you actually see
                    frameRate = s.optDouble("AverageFrameRate", 0.0)
                        .takeIf { !it.isNaN() && it > 0 } ?: s.optDouble("RealFrameRate", 0.0)
                            .takeIf { !it.isNaN() } ?: 0.0,
                    channels = s.optInt("Channels"),
                    sampleRate = s.optInt("SampleRate"),
                    bitrate = s.optLong("BitRate"),
                    isDefault = s.optBoolean("IsDefault", false),
                )
            }
        }
        val ticks = src?.optLong("RunTimeTicks", 0L)?.takeIf { it > 0 } ?: item.optLong("RunTimeTicks", 0L)
        val video = out.firstOrNull { it.kind == MediaStream.Kind.VIDEO }
        // During listing we deliberately skip sending dozens of requests, so an unknown size stays at 0 (see withSizes); the properties card only involves one entry, so it is worth a dedicated query
        val bytes = (src?.optLong("Size", 0L) ?: 0L).takeIf { it > 0 }
            ?: probedSizes[id] ?: runCatching { probeSize(id) }.getOrDefault(0L).also {
                if (it > 0) probedSizes[id] = it
            }
        return MediaDetails(
            // Real path on the server: our own path is a string of GUIDs, meaningless to show to the user
            realPath = src?.optString("Path").orEmpty().ifEmpty { item.optString("Path") },
            container = src?.optString("Container").orEmpty().ifEmpty { item.optString("Container") },
            durationMs = ticks / TICKS_PER_MS,
            bitrate = src?.optLong("Bitrate", 0L) ?: 0L,
            size = bytes,
            // Photo dimensions are at the entry top level (need Fields=Width,Height to be sent); for video they live in the stream
            width = item.optInt("Width").takeIf { it > 0 } ?: video?.width ?: 0,
            height = item.optInt("Height").takeIf { it > 0 } ?: video?.height ?: 0,
            // Music tags: the track title is the entry name; the artist prefers the first entry in the Artists array, falling back to AlbumArtist (both fields are present in practice; the single-track artist and the album artist may differ)
            title = item.optString("Name"),
            artist = item.optJSONArray("Artists")?.optString(0).orEmpty()
                .ifEmpty { item.optString("AlbumArtist") },
            album = item.optString("Album"),
            streams = out,
        )
    }

    // ---- Search ----

    /**
     * Uses the server-side search interface. The default BFS-recursive list is a
     * disaster on this kind of source: every level of the virtual tree is one
     * HTTP round trip, so one search would drag the entire library down.
     *
     * Scope is decided by which layer [root] lands at:
     *  - **Server root** → the whole server (no `ParentId`);
     *  - **Some library** (`/lib/<lib>`, `/latest/<lib>`, `/folders/<lib>`) →
     *    scoped to that library;
     *  - **An entry deeper than that** (an album, a series) → scoped to that
     *    entry.
     * Aggregate entries like "Continue Watching" cannot give a clear scope,
     * so we return null and let the caller fall back to traversal (those
     * directories only have dozens of entries anyway, so traversal is cheap).
     */
    override fun search(root: XFile, query: String, limit: Int): List<XFile>? {
        val term = query.trim()
        if (term.isEmpty()) return null
        val segs = segsOf(root.path)
        val parentId = when {
            segs.isEmpty() -> null // whole server
            segs.size >= 2 && segs[0] in setOf(ID_LIB, ID_LATEST, ID_FOLDERS) -> segs[1]
            else -> return null // "Continue Watching" / playlists / collections: scope is unclear, hand back to traversal
        }
        val uid = ensureAuth()
        val url = base.newBuilder()
            .addPathSegments("Users/$uid/Items")
            .addQueryParameter("SearchTerm", term)
            .addQueryParameter("Recursive", "true")
            .apply { if (parentId != null) addQueryParameter("ParentId", parentId) }
            .addQueryParameter("Fields", FIELDS)
            .addQueryParameter("Limit", limit.coerceAtMost(SEARCH_LIMIT).toString())
            .build()
        val items = itemsOf(getText(url), url)
        val used = HashMap<String, Int>()
        val out = ArrayList<XFile>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val id = it.optString("Id")
            if (id.isEmpty()) continue
            cache.put(id, it)
            // Results hang off the directory that was searched: the last segment is
            // the entry id, so opening/expanding works as usual.
            // ★ The server root's path is "/", so just concatenating yields
            // only one segment; [list] cannot recognise it, and [openCover]
            // also rejects it — prepend ID_SEARCH so the shape matches what
            // we get when searching inside a library.
            val prefix = root.path.trimEnd('/').ifEmpty { "/$ID_SEARCH" }
            val path = "$prefix/$id"
            rememberCover(it, id, path)
            out += toXFile(it, path, used)
        }
        return withSizes(out)
    }

    // ---- Episode queue ----

    /**
     * Every episode of the series this episode belongs to, sorted by season
     * and episode.
     *
     * `/Shows/{seriesId}/Episodes` **returns everything in one call** (across
     * seasons, already sorted); both servers behave identically (2026-08-19,
     * HTTP 200 + the same shape). Much more reliable than grouping by filename,
     * and **works for an episode reached through "Continue Watching" too** —
     * its siblings on the tree are other series, so we have to look back via
     * `SeriesId` to assemble a queue at all.
     *
     * The path reuses the parent path of the tapped episode, so queue entries
     * match the shape they have on the tree (`openCover`/`isLiveDir` dispatch
     * by path).
     */
    override fun episodesOf(file: XFile): List<XFile>? {
        if (file.isDir) return null
        val id = runCatching { idOf(file) }.getOrNull() ?: return null
        val item = runCatching { itemOf(id) }.getOrNull() ?: return null
        if (item.optString("Type") != "Episode") return null
        val series = item.optString("SeriesId").ifEmpty { return null }
        val uid = ensureAuth()
        val url = base.newBuilder()
            .addPathSegments("Shows/$series/Episodes")
            .addQueryParameter("userId", uid)
            .addQueryParameter("Fields", FIELDS)
            .build()
        val items = runCatching { itemsOf(getText(url), url) }.getOrNull() ?: return null
        if (items.length() == 0) return null
        val parent = file.path.substringBeforeLast('/', "")
        val used = HashMap<String, Int>()
        val out = ArrayList<XFile>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val eid = it.optString("Id")
            if (eid.isEmpty()) continue
            cache.put(eid, it)
            val path = "$parent/$eid"
            rememberCover(it, eid, path)
            out += toXFile(it, path, used)
        }
        return out.takeIf { it.size > 1 }
    }

    // ---- External subtitles ----

    /**
     * External subtitles for a video. The server exposes them as **subtitle
     * streams** rather than files in the directory, so the player's
     * "list siblings for `.srt`" path would not find any — here we wrap each
     * stream as an [XFile] that [openInput] can read, so the player reads,
     * parses and selects tracks through the same path as always.
     *
     * Text subtitles only: graphical ones like PGS/DVBSUB cannot produce text,
     * and the player already handles embedded tracks itself (see the PGS
     * note in `CLAUDE.md`). `.lrc` is excluded too — that is lyrics.
     */
    override fun subtitlesOf(file: XFile): List<XFile> {
        if (file.isDir) return emptyList()
        val id = idOf(file)
        val src = runCatching { itemOf(id) }.getOrNull()
            ?.optJSONArray("MediaSources")?.optJSONObject(0) ?: return emptyList()
        val streams = src.optJSONArray("MediaStreams") ?: return emptyList()
        val out = ArrayList<XFile>()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (s.optString("Type") != "Subtitle") continue
            if (!s.optBoolean("IsTextSubtitleStream", false)) continue
            val codec = s.optString("Codec").lowercase()
            if (codec in LYRIC_CODECS) continue
            val index = s.optInt("Index")
            val label = s.optString("DisplayTitle")
                .ifEmpty { s.optString("Title") }
                .ifEmpty { s.optString("Language") }
                .ifEmpty { "Subtitle $index" }
                .replace('/', '_')
            out += XFile(
                scheme = scheme,
                path = "${file.path}$SUB_MARK$index",
                isDir = false,
                canWrite = false,
                // Extension uses the **real format**: ASS must be recognisable as ASS (and the
                // content endpoint must be asked in its native format too, since
                // converting effect tags or multi-line dialogue to SRT loses them)
                displayName = "$label.${subExt(codec)}",
            )
        }
        return out
    }

    /**
     * Subtitle stream codec → extension to use in requests.
     *
     * ★ **Jellyfin reports the codec as `subrip`, not `srt`** (verified), so
     * assembling `Stream.subrip` is a guaranteed round trip to nowhere; `ass`/
     * `ssa` must be kept as-is — converting to SRT flattens effect tags and
     * multi-line dialogue.
     */
    private fun subExt(codec: String): String = when (codec.lowercase()) {
        "ass" -> "ass"
        "ssa" -> "ssa"
        "webvtt", "vtt" -> "vtt"
        else -> "srt" // subrip / srt / unrecognised
    }

    /** Subtitle pseudo-path → (entry id, stream index); returns null when the path is not a subtitle. */
    private fun subRefOf(path: String): Pair<String, Int>? {
        val at = path.lastIndexOf(SUB_MARK)
        if (at < 0) return null
        val index = path.substring(at + SUB_MARK.length).toIntOrNull() ?: return null
        val itemId = path.substring(0, at).substringAfterLast('/')
        return itemId.takeIf { it.isNotEmpty() }?.let { it to index }
    }

    private fun openSubtitle(itemId: String, index: Int): InputStream {
        val src = itemOf(itemId).optJSONArray("MediaSources")?.optJSONObject(0)
        val msId = src?.optString("Id").orEmpty().ifEmpty { itemId }
        val codec = (0 until (src?.optJSONArray("MediaStreams")?.length() ?: 0))
            .mapNotNull { src?.optJSONArray("MediaStreams")?.optJSONObject(it) }
            .firstOrNull { it.optInt("Index") == index }
            ?.optString("Codec")?.lowercase().orEmpty()
        // First ask for the original format (ASS style tags only exist in the original format);
        // same lesson as for lyrics: not every version will serve the original
        // (it may reply 200 + zero bytes), so fall back to srt
        val want = subExt(codec)
        val text = fetchSubtitle(itemId, msId, index, want)
            ?: (if (want != "srt") fetchSubtitle(itemId, msId, index, "srt") else null)
            ?: throw FsException("Subtitle stream $index is empty for item $itemId")
        return text.byteInputStream()
    }

    // ---- Lyrics ----

    /**
     * Lyrics. **The two projects' mechanisms are entirely different** (verified
     * on 2026-08-18), so we try both:
     *
     *  - **Jellyfin** (10.9+) has a dedicated `GET /Audio/{id}/Lyrics` endpoint
     *    that returns structured JSON (`Lyrics[].Text` + `Start`, in ticks);
     *    we stitch it back into LRC text here.
     *  - **Emby does not have that endpoint** (asking for that URL is treated
     *    as a transcode task and gets 500); it associates `.lrc` files as
     *    **external subtitle streams** with `Codec=lrc`, which must be fetched
     *    through the subtitle download URL
     *    `Videos/{id}/{mediaSourceId}/Subtitles/{index}/Stream.lrc` — that one
     *    returns the raw LRC text directly.
     *
     * Always returns LRC text, so the caller reuses the existing LRC parser.
     */
    override fun lyricsOf(file: XFile): String? {
        if (file.isDir) return null
        val id = idOf(file)
        return runCatching { structuredLyrics(id) }.getOrNull()
            ?: runCatching { subtitleLyrics(id) }.getOrNull()
    }

    /** Jellyfin's lyrics endpoint; no lyrics returns 404, which here becomes null. */
    private fun structuredLyrics(id: String): String? {
        val url = base.newBuilder().addPathSegments("Audio/$id/Lyrics").build()
        val text = exec(Request.Builder().url(url).get()).use {
            if (!it.isSuccessful) return null
            it.body?.string().orEmpty()
        }
        val arr = runCatching { JSONObject(text).optJSONArray("Lyrics") }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val line = arr.optJSONObject(i) ?: continue
            val words = line.optString("Text")
            // Start is in ticks (100 ns); lyrics without timing have it at 0 for the whole song, in which case we output the line as plain text
            val ms = line.optLong("Start", -1L).takeIf { it >= 0 }?.div(TICKS_PER_MS)
            if (ms != null && (ms > 0 || i == 0)) {
                val cs = (ms % 1000) / 10
                sb.append("[%02d:%02d.%02d]".format(ms / 60_000, (ms / 1000) % 60, cs))
            }
            sb.append(words).append('\n')
        }
        return sb.toString().ifBlank { null }
    }

    /**
     * Emby: `.lrc` is an external subtitle stream; fetch through the subtitle
     * download URL.
     *
     * ★ **Try both formats** (got bitten on real Emby 4.9.3 on 2026-08-18): that
     * endpoint "converts" the subtitle to the format named in the URL and gives
     * it to you, and **`.lrc` is not supported as an output format by every
     * version** — 4.9.3 replies **HTTP 200 + zero bytes** (not 404, not an
     * error — the worst kind), while 4.9.5 returns the raw text. `.srt` works
     * on both versions, so use it as the fallback and convert back to LRC.
     * Try `.lrc` first because that is the raw form, and even `[ti:]`/`[ar:]`
     * metadata survive.
     */
    private fun subtitleLyrics(id: String): String? {
        val src = itemOf(id).optJSONArray("MediaSources")?.optJSONObject(0) ?: return null
        val streams = src.optJSONArray("MediaStreams") ?: return null
        val msId = src.optString("Id").ifEmpty { id }
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (s.optString("Type") != "Subtitle") continue
            val codec = s.optString("Codec").lowercase()
            if (codec !in LYRIC_CODECS) continue
            val index = s.optInt("Index")
            fetchSubtitle(id, msId, index, codec)?.let { return it }
            // When this version does not recognise the output format (200 + empty), fall back to SRT and convert back to LRC
            fetchSubtitle(id, msId, index, "srt")?.let { srt -> srtToLrc(srt)?.let { return it } }
        }
        return null
    }

    /**
     * ★ Do not use `body.string()`: it decodes with the response header's charset,
     * **defaults to UTF-8 when none is set**, while this endpoint may return
     * the raw file bytes (the user's `.lrc` is often GBK) — that decodes into
     * a wall of replacement characters, looking like "the server's lyrics are
     * broken". Go through [TextDecoding] so the layer above can apply the
     * user's encoding priority; when the response header **explicitly** declares
     * a different encoding, that wins and we use it directly.
     */
    private fun fetchSubtitle(id: String, msId: String, index: Int, format: String): String? {
        val url = base.newBuilder()
            .addPathSegments("Videos/$id/$msId/Subtitles/$index/Stream.$format")
            .build()
        return exec(Request.Builder().url(url).get()).use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val declared = body.contentType()?.charset()
            val bytes = body.bytes()
            val text = if (declared != null && declared != Charsets.UTF_8) {
                String(bytes, declared)
            } else {
                TextDecoding.decode(bytes)
            }
            text.takeIf { it.isNotBlank() }
        }
    }

    /**
     * SRT → LRC. Discarding the end time is harmless: lyrics only scroll by
     * start time. The multiple text lines in one subtitle are merged onto one
     * line (lyrics rarely look like that, but when they do, merging is better
     * than dropping).
     */
    private fun srtToLrc(srt: String): String? {
        val out = StringBuilder()
        for (block in srt.split(BLANK_LINE)) {
            val lines = block.trim().lines().map { it.trim().removePrefix("﻿") }
            val at = lines.indexOfFirst { it.contains("-->") }
            if (at < 0) continue
            val m = SRT_TIME.find(lines[at]) ?: continue
            val (h, mi, sec, frac) = m.destructured
            val ms = h.toLong() * 3_600_000 + mi.toLong() * 60_000 + sec.toLong() * 1000 +
                frac.padEnd(3, '0').take(3).toLong()
            val text = lines.drop(at + 1).filter { it.isNotEmpty() }.joinToString(" ")
            if (text.isEmpty()) continue
            out.append("[%02d:%02d.%02d]".format(ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10))
                .append(text).append('\n')
        }
        return out.toString().ifBlank { null }
    }

    // ---- Playback progress ----

    /**
     * Server-side resume position. **Does not use [cache]** — the cache exists
     * to save round trips during listing, while this value specifically needs
     * the freshest copy (the user may have scrubbed forward on the web client
     * just now).
     */
    override fun positionOf(file: XFile): Long {
        val uid = ensureAuth()
        val id = idOf(file)
        val url = base.newBuilder().addPathSegments("Users/$uid/Items/$id").build()
        val data = getObject(url).optJSONObject("UserData") ?: return 0L
        // Already marked as played: start from the beginning, do not jump to the end
        if (data.optBoolean("Played", false)) return 0L
        return data.optLong("PlaybackPositionTicks", 0L) / TICKS_PER_MS
    }

    /**
     * The current playback's `PlaySessionId` for each entry (see [report]).
     * Generated on START, dropped on STOP; at most one or two are in flight at
     * any time, so they never pile up.
     */
    private val playSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Reports playback state.
     *
     * ★ **`PlaySessionId` is required, and must be non-empty** (settled on real
     * Emby 4.9.5 on 2026-08-17): Emby uses this field directly as the key of
     * a `ConcurrentDictionary` in `SessionInfo.GetOrAddPlaySessionInfo`, so
     * null produces an `ArgumentNullException: Value cannot be null. (Parameter
     * 'key')` — externally that surfaces as **HTTP 400**, and all three endpoints
     * die. Jellyfin tolerates a missing value, so this only blows up on Emby.
     *
     * The fallout is much worse than "one missing field": progress has **never**
     * been synced, so the server has no resume record and "Continue Watching"
     * is always empty — and that looks like a listing bug, sending the
     * investigation in the wrong direction.
     * (This is also why `RemoteProgress`'s "do not silently swallow failures"
     * matters: 400 has been happening all along, just invisible.)
     */
    override fun report(file: XFile, posMs: Long, durMs: Long, state: PlayState) {
        ensureAuth()
        val id = idOf(file)
        // One playback = one id, START begins it, STOP ends it; if we join mid-stream (e.g. the app resumed after a restart), generate one now
        val session = if (state == PlayState.START) {
            java.util.UUID.randomUUID().toString().also { playSessions[id] = it }
        } else {
            playSessions.getOrPut(id) { java.util.UUID.randomUUID().toString() }
        }
        val path = when (state) {
            PlayState.START -> "Sessions/Playing"
            PlayState.PROGRESS -> "Sessions/Playing/Progress"
            PlayState.STOP -> "Sessions/Playing/Stopped"
        }
        val body = JSONObject()
            .put("ItemId", id)
            .put("MediaSourceId", id)
            .put("PlaySessionId", session)
            .put("PositionTicks", posMs.coerceAtLeast(0L) * TICKS_PER_MS)
            .put("IsPaused", false)
            .put("CanSeek", true)
            .put("PlayMethod", "DirectStream")
            .toString()
        try {
            call {
                Request.Builder()
                    .url(base.newBuilder().addPathSegments(path).build())
                    .post(body.toRequestBody(JSON))
            }.close()
        } finally {
            // Drop on failure too: otherwise the next playback of this entry would reuse a session id the server has already ended
            if (state == PlayState.STOP) playSessions.remove(id)
        }
    }

    // ---- Read-only ----

    override fun writable(): Boolean = false

    private fun readOnly(): Nothing =
        throw FsException("${if (config.emby) "Emby" else "Jellyfin"} libraries are read-only in Twig")

    override fun openOutput(file: XFile, append: Boolean): OutputStream = readOnly()

    override fun mkdir(parent: XFile, name: String): XFile = readOnly()

    override fun delete(file: XFile): Unit = readOnly()

    override fun rename(file: XFile, newName: String): XFile = readOnly()

    // ---- Misc ----

    private fun idOf(file: XFile): String = segsOf(file.path).lastOrNull()
        ?: throw FsException("Not a Jellyfin item: ${file.path}")

    /**
     * Children of an entry.
     *
     * ★ When [keepOrder] is true, **`SortBy` must not be added**: the playlist's
     * order is the user-curated playback order, and adding `SortBy=IsFolder,
     * SortName` would immediately re-sort by name (verified: a playlist ordered
     * 8, 6 comes back as 6, 8 with SortBy). The UI layer has a second guard,
     * see [keepsServerOrder].
     */
    private fun childrenUrl(uid: String, parentId: String, keepOrder: Boolean = false): HttpUrl =
        base.newBuilder()
            .addPathSegments("Users/$uid/Items")
            .addQueryParameter("ParentId", parentId)
            .addQueryParameter("Fields", FIELDS)
            .apply {
                if (!keepOrder) {
                    addQueryParameter("SortBy", "IsFolder,SortName")
                    addQueryParameter("SortOrder", "Ascending")
                }
            }
            .build()

    /** Aggregate view: recursively fetch a type across the whole library, sorted by name. */
    private fun recursiveUrl(uid: String, types: String): HttpUrl =
        queryUrl(uid, types, sortBy = "SortName", descending = false, limit = null)

    private fun fetchUrl(url: HttpUrl): JSONArray = itemsOf(getText(url), url)

    /**
     * A library's "Latest".
     *
     * ★ Two pitfalls (2026-08-19, decided by inspecting the actual requests
     * the official web clients of both projects send):
     *  - **`Latest` does not scope to the library unless `ParentId` is sent**,
     *    so it dumps every type together — the previous version blamed this
     *    on "Emby ignores `IncludeItemTypes`", which was **an incomplete
     *    diagnosis**: once the parameter is sent, both servers are clean.
     *  - **`GroupItems=false`** is what returns single tracks / single
     *    episodes / single photos; the default (`true`) returns the
     *    **containers** (albums, series, etc), and "Latest Music" then lists a
     *    bunch of folders that need another click to open.
     */
    private fun latestOfLibrary(uid: String, libraryId: String): JSONArray = fetchUrl(
        base.newBuilder()
            .addPathSegments("Users/$uid/Items/Latest")
            .addQueryParameter("ParentId", libraryId)
            .addQueryParameter("GroupItems", "false")
            .addQueryParameter("Fields", FIELDS)
            .addQueryParameter("Limit", RECENT_LIMIT.toString())
            .build(),
    )

    /**
     * Library views. Each "Latest" entry has to look libraries up by type on
     * every visit, so caching saves one round trip per visit; libraries come
     * and go rarely, so a short TTL is enough.
     */
    private fun views(uid: String): JSONArray {
        viewsCache?.let { (at, arr) -> if (System.currentTimeMillis() - at < VIEWS_TTL_MS) return arr }
        val arr = fetchUrl(viewsUrl(uid))
        viewsCache = System.currentTimeMillis() to arr
        return arr
    }

    @Volatile private var viewsCache: Pair<Long, JSONArray>? = null

    private fun queryUrl(
        uid: String,
        types: String,
        sortBy: String,
        descending: Boolean,
        limit: Int?,
        parentId: String? = null,
    ): HttpUrl = base.newBuilder()
        .addPathSegments("Users/$uid/Items")
        .addQueryParameter("IncludeItemTypes", types)
        .addQueryParameter("Recursive", "true")
        .apply { if (parentId != null) addQueryParameter("ParentId", parentId) }
        .addQueryParameter("Fields", FIELDS)
        .addQueryParameter("SortBy", sortBy)
        .addQueryParameter("SortOrder", if (descending) "Descending" else "Ascending")
        .apply { if (limit != null) addQueryParameter("Limit", limit.toString()) }
        .build()

    /**
     * "Continue Watching".
     *
     * ★ **`MediaTypes` cannot be omitted** (verified truth-table on real Emby
     * 4.9.5 on 2026-08-17): without it Emby always replies **HTTP 200 + an empty
     * list**; with it things work normally. Jellyfin tolerates omitting it, so
     * this pitfall only blows up on Emby, and it looks like "this server has no
     * resume records".
     *
     * Three **unrelated** variables were ruled out in the same round (do not
     * investigate along these axes again): the value of `Fields`, the `/emby`
     * path prefix (routes without it still work), and `Limit` — whether or not
     * it is sent there is exactly one result either way.
     *
     * `Recursive=true` is also **not** the cause: the truth-table row "with
     * MediaTypes but without Recursive" still returns the same content.
     * It is kept only for consistency with the official clients (Emby Web
     * itself sends it) and with Jellyfin's semantics.
     * — A previous version treated this as the root cause and changed things
     * in a whole round, without solving it; the lesson is recorded in `CLAUDE.md`.
     */
    private fun resumeUrl(uid: String): HttpUrl = base.newBuilder()
        .addPathSegments("Users/$uid/Items/Resume")
        .addQueryParameter("Recursive", "true")
        // Video only: music/audiobook "half-listened" entries do not belong here and would push the actual films the user wants to continue further down (this directory only fetches the most recent N entries)
        .addQueryParameter("MediaTypes", "Video")
        .addQueryParameter("Fields", FIELDS)
        .addQueryParameter("Limit", RECENT_LIMIT.toString())
        .build()

    /** Library list (the first level under the "Folders" entry). */
    private fun viewsUrl(uid: String): HttpUrl =
        base.newBuilder().addPathSegments("Users/$uid/Views").build()

    private fun segsOf(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /**
     * Short-TTL cache of entry JSON. Browsing is naturally "list one level →
     * tap into one of them", so we remember every child during listing, and the
     * very next resolve / openInput / openCover gets a free round trip.
     *
     * The TTL is short (30 s) because the cached JSON contains things that can
     * change (watched state); [positionOf] needs the freshest copy and so
     * intentionally bypasses it entirely.
     */
    private val cache = TtlCache<JSONObject>(256, 30_000L)

    /**
     * Path → (which entry's image, which image type). Recorded as a side effect
     * of listing / searching; on hit, [openCover] sends **zero metadata requests**.
     *
     * ★ Why not rely on [cache]: that TTL is only 30 s, while thumbnails are
     * drained slowly by a 2-thread queue — one search can return hundreds of
     * items, and by the time they reach the end of the queue the cache has
     * already expired, so every thumbnail first fetches the entry and then the
     * image — **two serial round trips × hundreds of items**. That is why
     * "covers are slow even though there is an API".
     *
     * The key is the **path**, not the entry id: the same movie needs a
     * landscape backdrop under "Continue Watching" but a portrait poster in
     * the movie library (see [resumeImage]); storing by id would overwrite each
     * other.
     *
     * Image tags change rarely (only when the user replaces the cover), so
     * we evict purely by capacity, with no TTL.
     */
    private val coverRefs = object : LinkedHashMap<String, Pair<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) =
            size > 512
    }

    /** Definition of virtual entries: id, built-in English name, and "how to query its own level". */
    companion object {
        const val SCHEME = "jellyfin"

        /** 1 tick = 100 ns. */
        private const val TICKS_PER_MS = 10_000L

        /** "Continue Watching" / "Latest" are semantically "most recent N", not the entire library. */
        private const val RECENT_LIMIT = 60

        /** Cache duration for the library list: libraries are added/removed rarely, and "Latest" needs to query it every time it expands. */
        private const val VIEWS_TTL_MS = 60_000L

        /** Maximum number of results a server search can return — anything more cannot be browsed on the tree anyway. */
        private const val SEARCH_LIMIT = 300

        /** If more than this many entries have unknown sizes, skip the batch (a library of thousands of photos is not worth thousands of requests). */
        private const val PROBE_MAX = 80
        private const val PROBE_CONCURRENCY = 6

        /** Time budget for the whole batch of probes; entries that time out stay "size unknown" and do not stall the directory from opening. */
        private const val PROBE_BUDGET_MS = 2500L

        /**
         * Fields the server must fill in during listing.
         *
         * `Path` gives the real extension, `MediaSources` gives the byte size and
         * container format.
         * ★ **MediaSources is not cheap**: each entry carries the full audio/
         * subtitle stream details (a few KB per movie), so listing a movie
         * library recursively produces a response body in the megabytes. We
         * still ask for it, because the fallback "list without size, fetch on
         * demand" would make every row show 0 bytes in the file manager —
         * which to the user looks like "broken", and `CopyEngine`'s progress
         * bar would also be completely off.
         */
        private const val FIELDS = "Path,MediaSources,DateCreated,Width,Height"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Library views not shown under "Folders".
         *
         *  - `playlists` / `boxsets`: dedicated entries already exist at the
         *    root, so showing them again duplicates the same content in two
         *    places.
         *  - `folders`: this view (`Type=UserView`) appears when the Jellyfin
         *    server has "Display media folders" enabled, and clicking into it
         *    shows the same set of libraries again — since this layer already
         *    browses by library, wrapping another "Folders" layer on top just
         *    adds a click.
         */
        private val HIDDEN_VIEWS = setOf("playlists", "boxsets", "folders")

        /**
         * These types are **containers by nature**, treated as directories
         * regardless of whether the server sends `IsFolder`.
         * (Dedicated endpoints like `/Artists` sometimes omit that field.)
         */
        private val CONTAINER_TYPES = setOf(
            "MusicArtist", "MusicAlbum", "Series", "Season", "PhotoAlbum", "BoxSet",
            "Playlist", "CollectionFolder", "UserView", "Folder", "MusicGenre", "Genre",
        )

        /** External subtitle formats treated as lyrics (Emby associates `.lrc` as a subtitle stream). */
        private val LYRIC_CODECS = setOf("lrc", "txt", "elrc")

        /** Separator for subtitle pseudo-paths: `<entry path>!sub<stream index>`; only this implementation interprets it. */
        private const val SUB_MARK = "!sub"

        private val BLANK_LINE = Regex("\r?\n\r?\n")
        private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->""")

        private val ISO = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        /** Virtual directory ids — the :app layer provides localised names keyed by these ids (see [JellyfinConfig.labels]). */
        const val ID_RESUME = "resume"

        /** Path prefix for libraries themselves: `/lib/<library id>`. */
        const val ID_LIB = "lib"

        /** The four fixed sections under a music library (matching the official client's tabs). Prefixed with `@` so they cannot collide with entry ids. */
        const val SEG_ALBUMS = "@albums"
        const val SEG_ALBUM_ARTISTS = "@albumartists"
        const val SEG_ARTISTS = "@artists"
        const val SEG_MUSIC_FOLDER = "@folder"
        const val ID_PLAYLISTS = "playlists"
        const val ID_COLLECTIONS = "collections"
        const val ID_LATEST = "latest"
        const val ID_FOLDERS = "folders"

        /**
         * Search results at the server root hang off this segment.
         *
         * ★ **Not** meant to add an extra entry on the tree (it is not in
         * `rootEntries`); it exists purely because [list] requires the first
         * segment of a path to be a known virtual directory: results from a
         * root-level search are at `/<entry id>` — **only one segment** —
         * opening them produces `Unknown Jellyfin directory: /2121089`;
         * [openCover] returns null for `segs.size < 2` either (manifesting as
         * "search results have no covers, but the same entries in a library
         * do"). Library-scoped search yields `/lib/<lib>/<id>`, three segments,
         * which always worked — so the bug only shows up when **searching at
         * the root**.
         */
        const val ID_SEARCH = "search"

        /**
         * The ids that may appear at the root. **Which actually appear is
         * decided by what libraries exist on the server** (see `rootEntries`) —
         * a server without a music library should not show a "Music" entry
         * that leads to nothing.
         */
        val VIRTUAL_IDS =
            listOf(ID_RESUME, ID_PLAYLISTS, ID_COLLECTIONS, ID_LATEST, ID_LIB, ID_FOLDERS, ID_SEARCH)

        internal val MUSIC_SECTIONS = listOf(SEG_ALBUMS, SEG_ALBUM_ARTISTS, SEG_ARTISTS, SEG_MUSIC_FOLDER)

        /** Fallback when :app does not supply a localised name (pure JVM module does not write user-facing strings, see [JellyfinConfig.labels]). */
        private val FALLBACK_LABELS = mapOf(
            ID_RESUME to "Continue Watching",
            ID_PLAYLISTS to "Playlists",
            ID_COLLECTIONS to "Collections",
            ID_LATEST to "Latest",
            ID_FOLDERS to "Folders",
            SEG_ALBUMS to "Albums",
            SEG_ALBUM_ARTISTS to "Album Artists",
            SEG_ARTISTS to "Artists",
            SEG_MUSIC_FOLDER to "Folders",
        )

        /**
         * This directory's contents **change all the time** (just watched half
         * of something, just added to the library), so every expansion should
         * re-fetch and must not use the UI layer's children cache — otherwise
         * "watched an episode, came back, Continue Watching still looks the
         * same". The directory itself is what counts, not its children.
         */
        fun isLiveDir(path: String): Boolean {
            val segs = path.split('/').filter { it.isNotEmpty() }
            return when (segs.size) {
                1 -> segs[0] == ID_RESUME
                2 -> segs[0] == ID_LATEST // "Latest" inside some library
                else -> false
            }
        }

        /**
         * The **order of this directory is set by the server**, and the UI
         * layer must not re-sort it using the user's chosen sort.
         *
         * One more case beyond [isLiveDir]: **playlist contents**
         * (`/playlists/<id>`) — that order is the user-curated playback
         * sequence, so sorting by name destroys the playlist. Its contents do
         * not change minute-by-minute the way "Continue Watching" does, so it
         * is not in [isLiveDir] (no need to re-fetch on every expansion).
         */
        fun keepsServerOrder(path: String): Boolean {
            val segs = path.split('/').filter { it.isNotEmpty() }
            if (segs.size == 2 && segs[0] == ID_PLAYLISTS) return true
            return isLiveDir(path)
        }

        /** Whether this path is a "Latest" inside some library (its content is sorted by recency and must not be re-sorted). */
        fun isLatestOfLibrary(path: String): Boolean {
            val segs = path.split('/').filter { it.isNotEmpty() }
            return segs.size == 2 && segs[0] == ID_LATEST
        }
    }
}

/** Minimal capacity + TTL cache (LinkedHashMap's LRU mode); shared across threads with per-method locking. */
internal class TtlCache<T>(private val max: Int, private val ttlMs: Long) {

    /** Deliberately not called `Entry`: that name is a package-private nested class in `LinkedHashMap` and would collide. */
    private class Slot<T>(val value: T, val at: Long)

    private val map = object : LinkedHashMap<String, Slot<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot<T>>): Boolean = size > max
    }

    @Synchronized
    fun get(key: String): T? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > ttlMs) { map.remove(key); return null }
        return e.value
    }

    @Synchronized
    fun put(key: String, value: T) {
        map[key] = Slot(value, System.currentTimeMillis())
    }

    /** Drops the whole cache. Only tests use this to simulate "after TTL expiry". */
    @Synchronized
    fun clear() = map.clear()
}
