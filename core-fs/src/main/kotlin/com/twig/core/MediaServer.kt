package com.twig.core

import java.io.InputStream

/**
 * Two **optional** capability interfaces, implemented on demand by [FileSystem]
 * implementations (media servers like Jellyfin / Emby).
 *
 * Why these don't live in [FileSystem] itself: that interface is the floor that
 * "every source must answer", whereas these two only make sense for media
 * servers — adding them would force 20 implementations to each write `= null`.
 * Callers always use `FsRegistry.of(file) as? PlaybackProgress` to fetch them,
 * and fall back to the existing local logic on null.
 */

/** When playback state is reported; media servers use this to maintain "now playing" sessions and watch history. */
enum class PlayState {
    /** Playback started (server lights up "now playing" based on this). */
    START,

    /** Periodic heartbeat while playing. */
    PROGRESS,

    /** Stopped / exited playback. */
    STOP,
}

/**
 * Playback progress is owned by the **source itself** (rather than this device's
 * `PlaybackStore`).
 *
 * The implementation is the carrier for "the server's watch history": resume on
 * another device, recognize "watched" status set from the server's web UI, etc.
 * **All blocking network IO** — callers must put it on a background thread. The
 * progress-reporting path inside the player used to run on the main thread
 * (SharedPreferences was all it wrote); copying that pattern verbatim triggers
 * NetworkOnMainThread.
 */
interface PlaybackProgress {
    /**
     * The server-recorded resume position (milliseconds); returns <= 0 when there
     * is no record / the item has already been finished.
     */
    fun positionOf(file: XFile): Long

    /**
     * Report a playback state. Throw [FsException] on failure — **don't swallow
     * it silently**: when progress sync fails, the user has a right to know
     * "this viewing wasn't counted", and swallowing makes "didn't sync" and
     * "synced" indistinguishable.
     *
     * @param posMs current position; @param durMs total duration (use <= 0 when unknown).
     */
    fun report(file: XFile, posMs: Long, durMs: Long, state: PlayState)
}

/** One media stream (video / audio / subtitle track). Fields default when unavailable; UI decides which to show. */
data class MediaStream(
    val kind: Kind,
    val codec: String = "",
    val language: String = "",
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Double = 0.0,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Long = 0,
    val isDefault: Boolean = false,
) {
    enum class Kind { VIDEO, AUDIO, SUBTITLE }
}

/**
 * Media properties delivered directly by the source.
 *
 * [realPath] is the entry's **real path on the source side** (the file path on
 * the media server) — our own `XFile.path` for such sources is a string of GUIDs,
 * which is meaningless to show a user.
 */
data class MediaDetails(
    val realPath: String = "",
    val container: String = "",
    val durationMs: Long = 0,
    val bitrate: Long = 0,
    /**
     * Size in bytes; 0 = source doesn't know.
     *
     * This gets its own field because **`XFile.size` in the listing may be 0
     * while this one isn't**: some sources (media server photos) don't include
     * size in the listing response — asking per entry when filling a screen is
     * far too expensive — but opening a properties card involves only one entry,
     * and it's worth one extra round trip for it.
     */
    val size: Long = 0,
    /** Pixel dimensions; 0 = N/A or unknown. For photos, this is more meaningful than byte size. */
    val width: Int = 0,
    val height: Int = 0,
    /**
     * Content tags (title / artist / album); empty = source did not provide them.
     *
     * Lives here rather than in a separate interface because for music these
     * are "media information the source already knows", just like duration and
     * bitrate, and the caller (the playlist) actually wants them all in one
     * go — splitting it into two interfaces would just make it ask twice.
     */
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val streams: List<MediaStream> = emptyList(),
)

/**
 * The source knows the entry's media properties (duration / resolution / codec /
 * audio track / subtitle) itself, no need to read the file's bytes.
 *
 * The properties card defaults to `MediaMetadataRetriever` from the system API —
 * which actually has to **read the file**, a seconds-to-tens-of-seconds operation
 * over a remote source — whereas media servers have already parsed all this and
 * cached it in the listing response. Returning null means "can't deliver"; the
 * caller falls back to the original read-based path.
 */
interface MediaInfoSource {
    /** Return null when unavailable. Blocking IO — implementations should hit an existing metadata cache instead of issuing another round trip. */
    fun detailsOf(file: XFile): MediaDetails?

    /**
     * External subtitles attached to this entry; each is an [XFile] that the
     * caller can directly `openInput()`.
     *
     * Media servers expose external subtitles as "streams" rather than files in
     * a directory, so the player's "scan sibling directory for `.srt`" approach
     * picks up none of them. Returning `XFile` rather than a custom structure
     * lets the caller **not need to know where the subtitles come from** — read,
     * parse, track menu all go through the original path.
     *
     * The implementation picks its own `path` representation (only it knows how
     * to interpret it), but `name` must carry an extension such as `.srt` /
     * `.ass`: the caller relies on that to decide whether something is a
     * subtitle.
     */
    fun subtitlesOf(file: XFile): List<XFile> = emptyList()
}

/**
 * The source can **search itself**.
 *
 * The default search is a BFS recursive directory listing (see `scanSearch`) —
 * for a media server's virtual tree that's equivalent to pulling the whole
 * library (one HTTP round trip per level), while the server already has an
 * indexed search endpoint. This interface isn't limited to media servers: any
 * source that "can search faster itself" is welcome to implement it.
 */
interface SearchSource {
    /**
     * Search [query] within [root] (**plain keywords, no wildcards** — wildcard
     * syntax belongs to the caller, which will re-filter the results with the
     * original pattern). Returns null = this scope doesn't support it; the
     * caller falls back to a recursive walk. Blocking IO.
     */
    fun search(root: XFile, query: String, limit: Int): List<XFile>?
}

/**
 * The source can supply lyrics itself (media servers).
 *
 * ★ Always return **LRC text** (with `[mm:ss.xx]` prefixes when there are
 * timestamps, plain text lines otherwise), not each source's own structure —
 * the caller reuses the existing LRC parser and doesn't need to write one per
 * server, nor move a "lyric line" structure into `core-fs`.
 */
interface LyricsSource {
    /** Return null when unavailable. Blocking IO. */
    fun lyricsOf(file: XFile): String?
}

/**
 * The source can supply cover / preview art itself (media server posters, album art).
 *
 * Preferred over "download the bytes and frame-grab yourself": posters are
 * human-curated images, whereas frame-grabbing a TV episode only gets you a
 * single frame from some arbitrary second, and for a photo or music directory
 * there is no frame to grab at all.
 */
interface CoverSource {
    /**
     * Open a byte stream for the cover image (JPEG / PNG, implementation's
     * choice); return null when this entry has no cover. Caller closes.
     * Blocking IO.
     *
     * @param maxPx the desired maximum side length; the implementation can use
     *   this to ask the server to scale directly — no point pulling a 2000px
     *   original down for a 96dp cell on a phone.
     */
    fun openCover(file: XFile, maxPx: Int): InputStream?
}

/**
 * The source knows "which series this episode belongs to, and what episodes the
 * whole series has", for the player's continuous-play queue.
 *
 * A source with this interface **doesn't have to guess from the file name** —
 * on a media server every episode carries its own series id and season /
 * episode number, the order is what the server hands back, and cross-season
 * ordering is correct by construction. The siblings of a "Continue Watching"
 * episode in the tree are actually **other series** — there's no way to assemble
 * a queue from the sibling directory alone; only the source can answer.
 *
 * Returns null when it can't recognise the entry (not an episode, or this
 * source has no notion of "series"); the caller falls back to file-name
 * grouping.
 */
interface EpisodeSeries {
    /**
     * All episodes of the series [file] belongs to, sorted by season and
     * episode; the returned list includes [file] itself. Blocking IO.
     */
    fun episodesOf(file: XFile): List<XFile>?
}
