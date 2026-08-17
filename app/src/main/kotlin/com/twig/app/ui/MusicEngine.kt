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
 * 全局音乐播放引擎(单例)。持有一条常驻 [ExoPlayer](后台播放不随 Activity 销毁),
 * 用 ExoPlayer 原生队列(shuffle/repeat 三态);音频焦点与拔耳机暂停交给 ExoPlayer 内建
 * (setAudioAttributes handleAudioFocus + setHandleAudioBecomingNoisy)。
 *
 * 网络来源 DataSource 懒开(见 [MediaSources.lazy]);所有 FileSystem/MMR 调用走后台线程。
 */
@UnstableApi
object MusicEngine {

    interface Listener {
        fun onTrackChanged(index: Int, track: PlaylistTrack?) {}
        fun onPlayStateChanged(playing: Boolean) {}
        fun onModeChanged() {}
        fun onQueueChanged() {}
        /** 当前曲的「我的最爱」状态被改了(播放页心形、列表页菜单、通知栏心形任一处)。 */
        fun onFavChanged() {}
        /** 播放完最后一首(非循环)。 */
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
    private var errorSkips = 0 // 连续播放失败计数;绕队列一圈都失败则停止

    /** 谁改了「我的最爱」谁调一下,好让别处(播放页心形、通知栏心形)跟着变。 */
    fun notifyFavChanged() { listeners.forEach { it.onFavChanged() } }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // ---- 播放器构建 ----

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
                if (state == Player.STATE_READY) errorSkips = 0 // 成功加载一首,清零失败计数
                if (state == Player.STATE_ENDED) listeners.forEach { it.onEnded() }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("twig", "music: player error ${error.errorCodeName}", error)
                // 某首放不了(损坏/格式不支持)→ 跳下一首继续;绕队列一整圈都失败则停止
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

    // ---- 队列加载 ----

    /** 载入播放列表并从 [startIndex] 播放;conn 曲目在后台建连接,连接缺失的跳过。 */
    fun play(ctx: Context, playlist: Playlist, startIndex: Int, startPosMs: Long = 0L, autoPlay: Boolean = true) {
        val appCtx = ctx.applicationContext
        io.execute {
            val resolved = ArrayList<Pair<PlaylistTrack, XFile>>()
            val origIdx = ArrayList<Int>() // 每个可播放曲在原列表里的下标
            playlist.tracks.forEachIndexed { i, t ->
                val f = resolve(appCtx, t) ?: return@forEachIndexed
                resolved.add(t to f); origIdx.add(i)
            }
            if (resolved.isEmpty()) {
                // 一首都解析不出来(典型:曲目在 SMB/SFTP 上而服务器连不上)——以前这里静默返回,
                // 播放器压根没建出来,于是播放/上一首/下一首这些 `player?.` 按钮点了全无反应,
                // 只有「播放列表」「抽屉」这类纯 UI 按钮还能动,看着像"按钮坏了"。给个提示。
                main.post {
                    android.widget.Toast.makeText(appCtx, com.twig.app.R.string.music_load_failed, android.widget.Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            // 起播位置:第一个"原下标 >= startIndex"的可播放曲(点到无效曲就顺延到下一个可播放的);
            // 点到末尾无效曲、后面没有可播放的则回卷到第一个可播放曲。
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
     * 若给定列表就是当前队列 → 跳到该曲;否则整列载入后从该曲播。
     * 按曲目 id 匹配(而非下标):m3u8/旧列表里无法解析的曲目会在 [play] 里被跳过,
     * 队列下标与列表下标会错位,只有按 id 对齐才不会跳错曲。
     */
    /** 播放列表改名(尤其 NOW 被提升成新 uuid)后,把当前队列 id 同步到新 id,别让它指向已删除的旧列表。 */
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

    /** 后台把一首曲目解析成可读的 XFile(conn 曲目建连接);连接缺失返回 null。阻塞 IO。 */
    fun resolveFile(ctx: Context, t: PlaylistTrack): XFile? = resolve(ctx, t)

    // size 缺失时列父目录取真实大小;m3u8 同目录多曲共用一次列表结果(LRU 8 个目录)
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
        return list.firstOrNull { !it.isDir && it.name == name }
    }

    // size/lastModified 都是导入时(trackFrom/M3uPlaylist.parse)顺手存下的——一起用才能让
    // 重建出的 XFile 与 Thumbs 缓存 key(md5(name:size:mtime))跨会话保持稳定,否则哪怕 size
    // 存了、mtime 每次都补 0 也会跟真实文件(mtime≠0)对不上,封面照样每次判 miss 重新生成。
    private fun resolve(ctx: Context, t: PlaylistTrack): XFile? = runCatching {
        when (t.kind) {
            // 本地:openRandom 用真实文件长度,size 缺失无妨;这里顺手 stat 一下,好让列表副标题显示大小
            "local" -> if (t.size > 0) {
                XFile("file", t.path, isDir = false, size = t.size, lastModified = t.lastModified)
            } else {
                val f = java.io.File(t.path)
                XFile("file", t.path, isDir = false, size = f.length(), lastModified = f.lastModified())
            }
            "conn" -> {
                val conn = Connections.find(ctx, t.connLabel) ?: return null
                val scheme = Connections.ensure(ctx, conn)
                // 网络来源(SMB/WebDAV/…)的 openRandom().length() 直接取 XFile.size,size 必须正确,
                // 否则数据源一读就 EOF、extractor 判空流,网络音频无法播放。size 缺失(m3u8/旧列表)时
                // 补真实大小——注意不能用 FileSystem.resolve()(SmbFileSystem.resolve 是不 stat 的桩,
                // 返回 isDir=true/size=0),得列父目录按文件名匹配拿到带真实 size 的条目。
                if (t.size > 0) {
                    XFile(scheme, t.path, isDir = false, size = t.size, lastModified = t.lastModified)
                } else {
                    statByListing(scheme, t.path) // 找不到(路径不存在)返回 null → 跳过该曲
                }
            }
            // 其他 App「用 Twig 打开」传进来的 content://:URI 存在 path 里,名字/扩展名
            // 只能靠 displayName。临时读权限随调用方任务栈存活,重启后多半读不到了——
            // 那时 openInput 抛异常,这里 runCatching 兜住返回 null,该曲被跳过
            "share" -> XFile(
                com.twig.app.ShareSourceFileSystem.SCHEME, t.path, isDir = false,
                size = t.size, lastModified = t.lastModified, displayName = t.displayName,
            )
            else -> null
        }
    }.getOrNull()

    // ---- 传输控制(主线程)----

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

    /** 从当前队列移除某曲目(列表页移出时同步)。 */
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
     * 存"记忆播放位置",按曲目 id 而非下标——currentIndex() 是过滤后(m3u8/旧列表里失效
     * 曲目已被 [play] 跳过)队列的下标,而 [Playlist.lastIndex] 是按原始未过滤的
     * playlist.tracks 存的、供 [play] 的 startIndex 用;两个下标域不一致,直接存
     * currentIndex() 会导致每次恢复播放定位到错误曲目(尤其 m3u8 部分路径无效时)。
     * PlaylistStore.saveResume 按 id 在原始列表里换算出正确下标再存。
     */
    fun saveResume() {
        val ctx = app ?: return
        val id = queueId.ifEmpty { return }
        val trackId = currentTrack()?.id ?: return
        val pos = positionMs()
        com.twig.app.Prefs.setLastQueueId(ctx, id) // 冷启动恢复要知道上次到底在放哪个队列(NOW 还是某个命名列表)
        io.execute { runCatching { PlaylistStore.saveResume(ctx, id, trackId, pos) } }
    }

    fun stopPlayback() {
        player?.pause()
        saveResume()
    }

    /** 彻底退出:保存进度、释放播放器、清空队列(供"退出播放器"用;通知由 [MusicService] 停前台移除)。 */
    @Synchronized
    fun shutdown() {
        saveResume()
        player?.release()
        player = null
        AudioCache.releaseAll() // 播放器停读后再释放缓存连接/文件
        tracks = emptyList()
        files = emptyList()
        queueId = ""
        queueName = ""
        errorSkips = 0
        listeners.forEach { it.onQueueChanged() }
    }

    // ---- 预取下一首(字节 + 波形 + 封面)----

    private val prefetchNextRunnable = Runnable { prefetchNext() }

    /** 延后几秒再预取下一首:先让当前曲把缓冲喂饱,避免开播/切歌瞬间就跟预取抢同一条
     *  (SMB 串行化的)网络连接、拖慢当前曲起播。多次触发只保留最后一次。 */
    private fun schedulePrefetchNext() {
        main.removeCallbacks(prefetchNextRunnable)
        main.postDelayed(prefetchNextRunnable, 4000)
    }

    /** 播放器实际的"下一首"(随机播放时非 index+1;单曲循环时即自己,跳过)在后台预取:
     *  整曲字节进 [AudioCache]、算好波形、封面——切歌即时出声/出图,且不重复下载。
     *  仅网络来源需要;本地文件无预取意义。 */
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
        AudioCache.prefetch(f)          // 整曲字节
        // 波形(读也走 AudioCache,顺带把字节落进共享缓存)。★ 必须带 prefetch=true:
        // 否则它会顶掉播放页正在算的当前曲,当前曲的波形永远算不出来。
        Waveform.request(ctx, f, prefetch = true) {}
        tracks.getOrNull(ni)?.let { MusicArt.prefetch(ctx, it.id, f, dark) } // 封面/毛玻璃/主色
    }

    // ---- 元数据补全 ----

    private fun prefetchMeta(index: Int) {
        val ctx = app ?: return
        val id = queueId
        // 当前曲优先,再补前后各一首
        listOf(index, index + 1, index - 1).forEach { i ->
            val t = tracks.getOrNull(i) ?: return@forEach
            val f = files.getOrNull(i) ?: return@forEach
            // 采样率键需 API31+,老系统取不到就别拿它当"未补全"反复重读
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

    /** 公开:后台读某曲目元数据(列表页补全用)。阻塞 IO。 */
    fun fetchMeta(ctx: Context, file: XFile, track: PlaylistTrack): PlaylistTrack? = readMeta(ctx, file, track)

    /** MMR 读标题/艺术家/时长 + 比特率/采样率(15s 超时,独立线程)。采样率键需 API 31+,
     *  老系统取不到(返回 null)时留 0,副标题里自动省略。 */
    private fun readMeta(ctx: Context, file: XFile, track: PlaylistTrack): PlaylistTrack? = runCatching {
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
