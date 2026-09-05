package com.twig.app.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.twig.app.Connections
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.StreamProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.util.concurrent.Executors

/**
 * Global music playback engine (singleton). Holds a resident [ExoPlayer] (background playback
 * survives Activity destruction); uses ExoPlayer's native queue (shuffle/repeat three states);
 * audio focus and unplug-headphone pause are delegated to ExoPlayer's built-ins
 * (setAudioAttributes handleAudioFocus + setHandleAudioBecomingNoisy).
 *
 * Network sources' DataSource is opened lazily (see [MediaSources.lazy]); all FileSystem/MMR
 * calls go through background threads.
 */
@UnstableApi
object MusicEngine {

    interface Listener {
        fun onTrackChanged(index: Int, track: PlaylistTrack?) {}
        fun onPlayStateChanged(playing: Boolean) {}
        fun onModeChanged() {}
        fun onQueueChanged() {}
        /** The current track's "favorite" state was changed (player page heart, list page menu, or notification heart — anywhere). */
        fun onFavChanged() {}
        /** Playback finished the last track (no repeat). */
        fun onEnded() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-music-io").apply { isDaemon = true } }
    private val meta = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-music-meta").apply { isDaemon = true } }

    private var app: Context? = null
    private var player: ExoPlayer? = null
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()

    var queueId: String = ""
        private set
    var queueName: String = ""
        private set
    private var tracks: List<PlaylistTrack> = emptyList()
    private var files: List<XFile> = emptyList()
    private var errorSkips = 0 // consecutive playback failure counter; stop if all tracks in the queue have been tried and failed

    /** Whoever changes "favorite" calls this so other places (player page heart, notification heart) update too. */
    fun notifyFavChanged() { listeners.forEach { it.onFavChanged() } }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // ---- Player construction ----

    @Synchronized
    fun ensurePlayer(ctx: Context): ExoPlayer {
        app = ctx.applicationContext
        AudioCache.init(ctx)
        player?.let { return it }
        val renderers = object : DefaultRenderersFactory(ctx.applicationContext) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(StereoDownmixProcessor()))
                .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val p = ExoPlayer.Builder(ctx.applicationContext, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listeners.forEach { it.onPlayStateChanged(isPlaying) }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = p.currentMediaItemIndex
                listeners.forEach { it.onTrackChanged(i, tracks.getOrNull(i)) }
                saveResume()
                prefetchMeta(i)
                schedulePrefetchNext()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) errorSkips = 0 // successfully loaded a track, reset failure counter
                if (state == Player.STATE_ENDED) listeners.forEach { it.onEnded() }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("twig", "music: player error ${error.errorCodeName}", error)
                // A track can't play (corrupt / unsupported format) → skip to the next one and continue;
                // stop if we've cycled through the whole queue and all failed
                val n = tracks.size
                if (n <= 0) return
                errorSkips++
                if (errorSkips >= n) { errorSkips = 0; p.playWhenReady = false; return }
                p.seekTo((p.currentMediaItemIndex + 1) % n, 0)
                p.prepare()
                p.playWhenReady = true
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { listeners.forEach { it.onModeChanged() }; schedulePrefetchNext() }
            override fun onRepeatModeChanged(repeatMode: Int) { listeners.forEach { it.onModeChanged() }; schedulePrefetchNext() }
        })
        player = p
        return p
    }

    // ---- Queue loading ----

    /** Loads the playlist and starts playback from [startIndex]; conn tracks build connections in the background, missing ones are skipped. */
    fun play(ctx: Context, playlist: Playlist, startIndex: Int, startPosMs: Long = 0L, autoPlay: Boolean = true) {
        val appCtx = ctx.applicationContext
        io.execute {
            val resolved = ArrayList<Pair<PlaylistTrack, XFile>>()
            val origIdx = ArrayList<Int>() // index of each playable track in the original list
            playlist.tracks.forEachIndexed { i, t ->
                val f = resolve(appCtx, t) ?: return@forEachIndexed
                resolved.add(t to f); origIdx.add(i)
            }
            if (resolved.isEmpty()) {
                // None could be resolved (typical: tracks on SMB/SFTP but the server can't be connected).
                // Previously this returned silently — the player was never even built, so tapping
                // play/prev/next (`player?.`) buttons did nothing, while pure UI buttons like
                // "Playlist" / "Drawer" still worked, looking like "buttons are broken". Show a hint.
                main.post {
                    android.widget.Toast.makeText(appCtx, com.twig.app.R.string.music_load_failed, android.widget.Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            // Start position: the first playable track whose original index is >= startIndex
            // (tapping an invalid track advances to the next playable one); tapping the last invalid
            // track with no playable after wraps back to the first playable one.
            val startPos = origIdx.indexOfFirst { it >= startIndex }.let { if (it < 0) 0 else it }
            main.post {
                val p = ensurePlayer(appCtx)
                tracks = resolved.map { it.first }
                files = resolved.map { it.second }
                queueId = playlist.id
                queueName = playlist.name
                errorSkips = 0
                p.setMediaSources(files.map { MediaSources.lazy(it) }, startPos, startPosMs)
                p.prepare()
                p.playWhenReady = autoPlay
                listeners.forEach { it.onQueueChanged(); it.onTrackChanged(startPos, tracks.getOrNull(startPos)) }
                if (autoPlay) MusicService.start(appCtx)
                prefetchMeta(startPos)
                schedulePrefetchNext()
            }
        }
    }

    /**
     * If the given list is the current queue → jump to that track; otherwise load the whole list
     * and start playing from that track. Matches by track id (not index): tracks that can't be
     * resolved in m3u8 / old lists are skipped by [play], so queue index and list index drift;
     * only matching by id avoids jumping to the wrong track.
     */
    /** After a playlist is renamed (especially when NOW gets promoted to a new uuid), sync the current queue id to the new id so it doesn't point at a deleted old list. */
    fun onQueueRenamed(from: String, to: String) {
        if (queueId == from) queueId = to
    }

    fun playFrom(ctx: Context, playlist: Playlist, index: Int) {
        val id = playlist.tracks.getOrNull(index)?.id
        if (playlist.id == queueId && id != null) {
            val qi = tracks.indexOfFirst { it.id == id }
            if (qi >= 0) {
                player?.let { p ->
                    p.seekTo(qi, 0); p.playWhenReady = true; MusicService.start(ctx.applicationContext); return
                }
            }
        }
        play(ctx, playlist, index)
    }

    /** Resolves a track to a readable XFile in the background (conn tracks build connections); returns null when connection is missing. Blocking IO. */
    fun resolveFile(ctx: Context, t: PlaylistTrack): XFile? = resolve(ctx, t)

    // When size is missing, list the parent directory to get the real size; m3u8 with multiple
    // tracks in the same directory share one listing result (LRU 8 directories)
    private val dirListCache = object : LinkedHashMap<String, List<XFile>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<XFile>>?) = size > 8
    }

    private fun statByListing(scheme: String, path: String): XFile? {
        val parentPath = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val key = "$scheme:$parentPath"
        val list = synchronized(dirListCache) { dirListCache[key] }
            ?: FsRegistry.of(scheme).list(XFile(scheme, parentPath, isDir = true))
                .also { synchronized(dirListCache) { dirListCache[key] = it } }
        // Look up by display name; for sources like media servers where "the last path segment is
        // an id and the name is something else", also try matching by the last segment
        return list.firstOrNull { !it.isDir && it.name == name }
            ?: list.firstOrNull { !it.isDir && it.path.substringAfterLast('/') == name }
    }

    // size/lastModified are stored at import time (trackFrom/M3uPlaylist.parse) — using both is
    // the only way to keep the rebuilt XFile and the Thumbs cache key (md5(name:size:mtime))
    // stable across sessions; otherwise even if size is stored, mtime being defaulted to 0 every
    // time will not match the real file (mtime≠0), and the cover will be a miss every time and
    // be regenerated.
    private fun resolve(ctx: Context, t: PlaylistTrack): XFile? = runCatching {
        when (t.kind) {
            // Local: openRandom uses the real file length, so missing size is fine; we stat here
            // as a bonus so the list subtitle can show size
            "local" -> if (t.size > 0) {
                XFile("file", t.path, isDir = false, size = t.size, lastModified = t.lastModified)
            } else {
                val f = java.io.File(t.path)
                XFile("file", t.path, isDir = false, size = f.length(), lastModified = f.lastModified())
            }
            "conn" -> {
                val conn = Connections.find(ctx, t.connLabel) ?: return null
                val scheme = Connections.ensure(ctx, conn)
                // For network sources (SMB/WebDAV/…) openRandom().length() takes XFile.size directly;
                // size must be correct, otherwise the data source EOFs on first read and the
                // extractor sees an empty stream — network audio won't play. When size is missing
                // (m3u8 / old lists) backfill the real size — note that FileSystem.resolve() can't
                // be used (SmbFileSystem.resolve is a non-statting stub returning isDir=true/size=0);
                // we must list the parent directory and match by file name to get the entry with
                // its real size.
                if (t.size > 0) {
                    // ★ displayName must come back too: for media servers the last path segment
                    // is the entry id (Emby uses pure numbers); losing it doesn't just make the
                    // list display numbers, **the extension is also gone** — which makes media3
                    // fail to recognize the container and fall back to per-byte sniffing (see the
                    // AVI entry in CLAUDE.md). Empty string must become null, otherwise XFile.name
                    // returns the empty string directly.
                    XFile(
                        scheme, t.path, isDir = false, size = t.size, lastModified = t.lastModified,
                        displayName = t.displayName.ifEmpty { null },
                    )
                } else {
                    statByListing(scheme, t.path) // returns null if not found (path doesn't exist) → skip this track
                }
            }
            // content:// passed in by another app via "Open with Twig": the URI lives in path,
            // name and extension can only come from displayName. The temporary read permission
            // survives only with the caller's task stack; after a restart it likely can't be read
            // anymore — openInput then throws, runCatching here catches it and returns null, and
            // the track is skipped
            "share" -> XFile(
                com.twig.app.ShareSourceFileSystem.SCHEME, t.path, isDir = false,
                size = t.size, lastModified = t.lastModified, displayName = t.displayName,
            )
            // SAF: authorization is persistent, so reads still work after a restart; when the
            // grant is revoked openInput throws, and like share this is caught by the outer
            // runCatching, and the track is skipped
            "saf" -> XFile(
                com.twig.app.SafFileSystem.SCHEME, t.path, isDir = false,
                size = t.size, lastModified = t.lastModified,
                displayName = t.displayName.ifEmpty { null },
            )
            else -> null
        }
    }.getOrNull()

    // ---- Transport control (main thread) ----

    fun currentIndex(): Int = player?.currentMediaItemIndex ?: 0
    fun currentTrack(): PlaylistTrack? = tracks.getOrNull(currentIndex())
    fun currentFile(): XFile? = files.getOrNull(currentIndex())
    fun isPlaying(): Boolean = player?.isPlaying == true
    fun hasQueue(): Boolean = tracks.isNotEmpty()
    fun positionMs(): Long = player?.currentPosition ?: 0L
    fun durationMs(): Long = player?.duration?.takeIf { it != C.TIME_UNSET } ?: (currentTrack()?.durationMs ?: 0L)
    fun trackCount(): Int = tracks.size
    fun trackAt(i: Int): PlaylistTrack? = tracks.getOrNull(i)

    fun togglePlay() { player?.let { if (it.isPlaying) it.pause() else { it.play(); app?.let { c -> MusicService.start(c) } } } }
    fun pause() { player?.pause() }
    fun next() { player?.seekToNextMediaItem() }
    fun prev() {
        val p = player ?: return
        if (p.currentPosition > 3000) p.seekTo(0) else p.seekToPreviousMediaItem()
    }
    fun seekTo(ms: Long) { player?.seekTo(ms) }

    fun shuffleEnabled(): Boolean = player?.shuffleModeEnabled == true
    fun toggleShuffle() { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun repeatMode(): Int = player?.repeatMode ?: Player.REPEAT_MODE_OFF
    fun cycleRepeat() {
        val p = player ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Removes a track from the current queue (synced when removed from the list page). */
    fun removeFromQueue(trackId: String) {
        val p = player ?: return
        val i = tracks.indexOfFirst { it.id == trackId }
        if (i < 0) return
        p.removeMediaItem(i)
        tracks = tracks.toMutableList().also { it.removeAt(i) }
        files = files.toMutableList().also { it.removeAt(i) }
        listeners.forEach { it.onQueueChanged() }
    }

    /**
     * Save "remember playback position" by track id rather than index — currentIndex() is the
     * post-filter (invalid tracks from m3u8 / old lists have been skipped by [play]) queue index,
     * while [Playlist.lastIndex] is stored from the original unfiltered playlist.tracks for [play]'s
     * startIndex; the two index domains don't match, so storing currentIndex() directly causes
     * resume to point at the wrong track (especially with partially-invalid m3u8). PlaylistStore.saveResume
     * resolves the correct index in the original list by id before storing.
     */
    fun saveResume() {
        val ctx = app ?: return
        val id = queueId.ifEmpty { return }
        val trackId = currentTrack()?.id ?: return
        val pos = positionMs()
        com.twig.app.Prefs.setLastQueueId(ctx, id) // on cold-start resume we need to know which queue was being played (NOW or some named list)
        io.execute { runCatching { PlaylistStore.saveResume(ctx, id, trackId, pos) } }
    }

    fun stopPlayback() {
        player?.pause()
        saveResume()
    }

    /** Full exit: save progress, release player, clear queue (used by "Exit player"; notification is removed by [MusicService] stopping the foreground). */
    @Synchronized
    fun shutdown() {
        saveResume()
        player?.release()
        player = null
        AudioCache.releaseAll() // release cache connections/files after the player stops reading them
        tracks = emptyList()
        files = emptyList()
        queueId = ""
        queueName = ""
        errorSkips = 0
        listeners.forEach { it.onQueueChanged() }
    }

    // ---- Prefetch next track (bytes + waveform + cover) ----

    private val prefetchNextRunnable = Runnable { prefetchNext() }

    /** Delay prefetching the next track by a few seconds: let the current track fill its buffer first,
     *  to avoid the start/skip moment contending with the prefetch over the same (SMB-serialized)
     *  network connection and slowing the current track's start. Multiple triggers keep only the last. */
    private fun schedulePrefetchNext() {
        main.removeCallbacks(prefetchNextRunnable)
        main.postDelayed(prefetchNextRunnable, 4000)
    }

    /** The player's actual "next track" (in shuffle mode not index+1; in repeat-one it's itself, so skip)
     *  is prefetched in the background: full track bytes into [AudioCache], waveform computed, cover
     *  ready — instant audio/cover on track change with no duplicate download. Only network sources
     *  need this; local files have nothing to prefetch. */
    private fun prefetchNext() {
        val p = player ?: return
        val ctx = app ?: return
        if (!p.hasNextMediaItem()) return
        val ni = p.nextMediaItemIndex
        if (ni < 0 || ni == p.currentMediaItemIndex) return
        val f = files.getOrNull(ni) ?: return
        if (f.scheme == "file") return
        val dark = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        AudioCache.prefetch(f)          // full track bytes
        // Waveform (reads also go through AudioCache, incidentally dropping bytes into the shared cache).
        // ★ Must pass prefetch=true: otherwise it would preempt the current track's waveform being
        // computed on the player page, and the current track's waveform would never finish.
        Waveform.request(ctx, f, prefetch = true) {}
        tracks.getOrNull(ni)?.let { MusicArt.prefetch(ctx, it.id, f, dark) } // cover / blur / accent
    }

    // ---- Metadata backfill ----

    private fun prefetchMeta(index: Int) {
        val ctx = app ?: return
        val id = queueId
        // Current track first, then one before and after
        listOf(index, index + 1, index - 1).forEach { i ->
            val t = tracks.getOrNull(i) ?: return@forEach
            val f = files.getOrNull(i) ?: return@forEach
            // Sample-rate key requires API 31+; on older systems we can't get it, so don't use it
            // as a "not backfilled" signal that triggers repeated rereads
            val complete = t.title.isNotEmpty() && t.durationMs > 0 &&
                (android.os.Build.VERSION.SDK_INT < 31 || t.sampleRate > 0)
            if (complete) return@forEach
            meta.execute {
                val filled = readMeta(ctx, f, t) ?: return@execute
                if (id == queueId) {
                    main.post {
                        val cur = tracks.indexOfFirst { it.id == filled.id }
                        if (cur >= 0) {
                            tracks = tracks.toMutableList().also { it[cur] = filled }
                            listeners.forEach { it.onTrackChanged(currentIndex(), currentTrack()); it.onQueueChanged() }
                        }
                    }
                }
                runCatching { PlaylistStore.updateTrackMeta(ctx, id, filled) }
            }
        }
    }

    /** Public: read a track's metadata on a background thread (used by the list page for backfill). Blocking IO. */
    fun fetchMeta(ctx: Context, file: XFile, track: PlaylistTrack): PlaylistTrack? = readMeta(ctx, file, track)

    /**
     * Reads a track's tags. **If the source knows directly, use that** (media servers put
     * title/artist/album/duration in their listing response); otherwise fall back to MMR and read
     * the file bytes — the latter costs several seconds per track for network audio, and a whole
     * album takes a long time even though the server has already parsed all that data.
     */
    private fun readMeta(ctx: Context, file: XFile, track: PlaylistTrack): PlaylistTrack? =
        remoteMeta(file, track) ?: mmrMeta(ctx, file, track)

    /** Tags given directly by the source ([com.twig.core.MediaInfoSource]); returns null if it can't provide any. */
    private fun remoteMeta(file: XFile, track: PlaylistTrack): PlaylistTrack? {
        val d = runCatching {
            (FsRegistry.of(file) as? com.twig.core.MediaInfoSource)?.detailsOf(file)
        }.getOrNull() ?: return null
        // No title and no duration: this source contributed nothing, don't block MMR
        if (d.title.isBlank() && d.durationMs <= 0L) return null
        val audio = d.streams.firstOrNull { it.kind == com.twig.core.MediaStream.Kind.AUDIO }
        return track.withMeta(
            d.title.ifBlank { track.name.substringBeforeLast('.') },
            d.artist,
            d.album,
            d.durationMs,
            audio?.sampleRate ?: 0,
            (audio?.bitrate?.takeIf { it > 0 } ?: d.bitrate).toInt(),
        )
    }

    /** MMR reads title/artist/duration + bitrate/sample rate (15s timeout, dedicated thread). The sample-rate key requires API 31+;
     *  on older systems it returns null — leave 0, the subtitle line auto-omits it. */
    private fun mmrMeta(ctx: Context, file: XFile, track: PlaylistTrack): PlaylistTrack? = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            if (file.scheme == "file") mmr.setDataSource(file.path)
            else mmr.setDataSource(ctx, StreamProvider.uriFor(ctx, file))
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: track.name.substringBeforeLast('.')
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: ""
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() } ?: ""
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val sampleRate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            track.withMeta(title, artist, album, dur, sampleRate, bitrate)
        } finally { runCatching { mmr.release() } }
    }.getOrNull()
}
