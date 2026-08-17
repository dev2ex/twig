package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.twig.app.FileInfo
import com.twig.app.OpenFiles
import com.twig.app.PlaybackStore
import com.twig.app.R
import com.twig.app.databinding.ActivityMediaPlayerBinding
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置音视频播放器,基于 media3 ExoPlayer:
 * - 自带解封装(MKV/MP4 等),不受系统 MediaPlayer 容器限制;
 * - ffmpeg 音频软解扩展兜底(AC3/EAC3/DTS/TrueHD 等设备没有的解码器);
 * - 非本地来源经 [RandomSourceDataSource] 直接从 [RandomSource] 定位读(SMB 原生 pread)。
 */
@UnstableApi
class MediaPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var b: ActivityMediaPlayerBinding
    private lateinit var file: XFile
    private var isVideo = false

    private var player: ExoPlayer? = null
    private var fsSrc: RandomSource? = null // 共享一条 SMB 专用连接,seek 重开 DataSource 零成本
    private var prepared = false
    // 解码运行时崩溃的两级降级:0=系统优先 1=已切 ffmpeg 优先重试 2=已彻底关掉音轨(哑巴放视频)
    private var fallbackStage = 0
    private var videoW = 0
    private var videoH = 0
    private var rootW = 0 // 上一次算过的可用区域,用来认出旋转/分屏引起的尺寸变化
    private var rootH = 0
    private var scaleMode = SCALE_BEST_FIT
    // 上次看到哪儿(onCreate 时查一次;真正 seek 过去是在播放器 prepare 那步)
    private var resumeFrom = 0L
    private var lastSaveAt = 0L

    // 手势:横滑定位 / 左侧竖滑亮度 / 右侧竖滑音量
    private enum class Gesture { NONE, SEEK, BRIGHT, VOL }
    private var gesture = Gesture.NONE
    private var gestureSeekTo = -1
    private var gestureStartPos = 0
    private var gestureStartBright = 0.5f
    private var gestureStartVol = 0
    private var orientationLocked = false
    private var speedBoosted = false // 长按加速中(抬手恢复原速)
    private var speedBeforeBoost = 1f
    private lateinit var audio: AudioManager

    // 字幕:外挂(自解析)优先;内嵌文本轨(SRT/ASS in MKV 等)由 ExoPlayer 解出经 onCues 显示
    private var cues: List<SubCue> = emptyList()
    private var subEnabled = true
    private var subFiles: List<XFile> = emptyList()
    private var textGroups: List<Tracks.Group> = emptyList()
    private var audioGroups: List<Tracks.Group> = emptyList()
    private var subChoice = 0 // 0=关闭;1..subFiles.size=外挂;之后=内嵌文本轨
    private var subUserChosen = false // 用户手动选过字幕后,不再自动启用内嵌轨

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            player?.let {
                if (prepared) {
                    val pos = it.currentPosition.toInt()
                    if (gesture != Gesture.SEEK) { b.seek.progress = pos; b.tvPos.text = fmt(pos) }
                    updateSubtitle(pos)
                    // 定期落一次进度:onPause 覆盖正常退出,这条覆盖「进程被系统直接杀掉」
                    if (it.isPlaying && SystemClock.elapsedRealtime() - lastSaveAt > 10_000) saveProgress()
                }
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMediaPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: path
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)
        // ★ displayName 必须带上:content:// 这类"路径里没有文件名"的来源,扩展名只能从
        // 显示名取。丢了它 → isVideo 判成 false(不绑 surface,只剩声音)、
        // MediaSources 的 `twig:///media.<ext>` 也没了扩展名(容器识别退化到 sniff 顺序)
        file = XFile(scheme, path, isDir = false, size = size, displayName = name)
        isVideo = OpenFiles.isVideo(file)

        b.toolbar.title = name
        b.toolbar.setNavigationOnClickListener { finish() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(name))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        scaleMode = com.twig.app.Prefs.videoScaleMode(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        b.btnPlay.setOnClickListener { toggle() }
        b.btnSubtitle.setOnClickListener { showSubtitleDialog() }
        b.btnAudioTrack.setOnClickListener { showAudioTrackDialog() }
        b.btnScale.setOnClickListener { showScaleDialog() }
        b.btnOrientation.setOnClickListener { toggleOrientationLock() }
        if (!isVideo) {
            b.btnSubtitle.visibility = View.GONE
            b.btnScale.visibility = View.GONE
            b.btnOrientation.visibility = View.GONE
        }
        if (isVideo) setupGestures()
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) b.tvPos.text = fmt(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                android.util.Log.d("twig", "player: seekTo requested ${s.progress}")
                player?.seekTo(s.progress.toLong())
            }
        })

        if (isVideo) {
            // 进度记忆只给视频:音频文件的续播归 MusicEngine/PlaylistStore 那套管
            resumeFrom = PlaybackStore.positionFor(this, file)
            b.surface.holder.addCallback(this)
            watchRootSize()
            scanSubtitles()
        } else {
            b.audioInfo.visibility = View.VISIBLE
            b.audioName.text = name
            preparePlayer(null)
        }
    }

    // ---- 播放器 ----

    private fun preparePlayer(
        holder: SurfaceHolder?,
        preferExtensionDecoders: Boolean = false,
        disableAudio: Boolean = false,
        resumePositionMs: Long = 0L,
    ) {
        if (player != null) return
        b.loading.visibility = View.VISIBLE
        // EXTENSION_RENDERER_MODE_ON:优先系统 MediaCodec(省电),没有对应解码器时落到 ffmpeg 软解。
        // 部分设备的系统解码器对特定流会直接崩溃(而非声明不支持)——这种运行时崩溃
        // 拿不到"没有解码器"的判断依据,只能等 onPlayerError 后整体降级成 PREFER 重试一次。
        // 自定义 AudioSink:>2 声道 PCM 先降混立体声(部分设备 6ch AudioTrack 反复 dead → 播放卡顿)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(StereoDownmixProcessor()))
                    .build()
        }.setExtensionRendererMode(
            if (preferExtensionDecoders) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            },
        )
        // 本地文件走 setMediaItem,容器解析交给播放器默认的 MediaSource 工厂——挂上
        // [MediaSources.extractors](内含 [TwigSubtitleParserFactory]),不然本地 MKV 的
        // PGS 字幕仍会落到官方 PgsParser,一屏多块只显示一块。
        // 音频焦点:不请求的话别处(内置 MusicEngine / 外部音乐 App)正在放的音乐不会被暂停,
        // 声音直接叠在一起。becomingNoisy 一并开,拔耳机/断蓝牙时自动暂停不外放。
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, MediaSources.extractors()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        // 元数据轨(SCTE-35 广告插播信令等)这个播放器从不消费,却有实测崩过:
        // 部分 m2ts remux 里的 SCTE-35 数据不太规范,media3 的 SpliceInfoDecoder
        // 对越界直接抛 IllegalStateException 崩整个播放器。反正用不上,直接关掉。
        p.trackSelectionParameters =
            p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true).build()

        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoW = size.width; videoH = size.height; resizeSurface()
            }
            override fun onRenderedFirstFrame() {
                android.util.Log.d("twig", "player: onRenderedFirstFrame pos=${p.currentPosition}")
            }
            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.d(
                    "twig",
                    "player: state=$state pos=${p.currentPosition} bufferedPos=${p.bufferedPosition}",
                )
                when (state) {
                    Player.STATE_BUFFERING -> b.loading.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        b.loading.visibility = View.GONE
                        if (!prepared) {
                            prepared = true
                            val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                            b.seek.max = dur.toInt().coerceAtLeast(0)
                            b.tvDur.text = fmt(dur.toInt())
                            handler.post(ticker)
                        }
                    }
                    Player.STATE_ENDED -> {
                        android.util.Log.d("twig", "player: ended pos=${p.currentPosition} dur=${p.duration}")
                        b.btnPlay.setImageResource(R.drawable.ic_play)
                    }
                    else -> {}
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                android.util.Log.d(
                    "twig",
                    "player: onPositionDiscontinuity reason=$reason old=${oldPosition.positionMs} " +
                        "new=${newPosition.positionMs}",
                )
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("twig", "player: error ${error.errorCodeName}", error)
                // ERROR_CODE_UNSPECIFIED 也算:实测 jellyfin 那个 ffmpeg 扩展对某些
                // DTS 变体解码器构造函数直接 NPE(不是喂坏数据解码失败,是解码器本身
                // 建不起来),ExoPlayer 把这类没归类的运行时异常都报成 UNSPECIFIED。
                val recoverable = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
                    error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED
                if (fallbackStage < 2 && recoverable) {
                    // 解码运行时崩溃(非"没有解码器",是声明支持但一解就炸/喂到坏数据)——
                    // 或者没崩但每包都解码失败、playback 卡住不动触发的 TIMEOUT。
                    // 第一级:整体切到 ffmpeg 优先重来一次(部分设备系统解码器本身有 bug)。
                    // 第二级:ffmpeg 也吃不动(比如源文件音轨本身局部损坏/填充垃圾字节)——
                    // 干脆整段关掉音轨、哑巴放视频,总比直接卡死强。就地换 renderer 不行,
                    // 播放器已进入 error 状态、renderer 都已 disable,只能整个重建。
                    val resumeAt = p.currentPosition
                    fallbackStage++
                    if (fallbackStage >= 2) {
                        b.toolbar.subtitle = getString(R.string.player_audio_disabled)
                    }
                    p.release()
                    player = null
                    prepared = false
                    preparePlayer(
                        if (isVideo) b.surface.holder else null,
                        preferExtensionDecoders = true,
                        disableAudio = fallbackStage >= 2,
                        resumePositionMs = resumeAt,
                    )
                    return
                }
                b.loading.visibility = View.GONE
                b.toolbar.subtitle = getString(R.string.viewer_load_failed, error.errorCodeName)
            }
            override fun onCues(cueGroup: CueGroup) {
                if (cues.isNotEmpty() || !subEnabled) {
                    b.pgsView.setCues(emptyList())
                    return
                }
                // 位图字幕(PGS 等)画到叠加层;文本字幕走 TextView
                b.pgsView.setCues(cueGroup.cues.filter { it.bitmap != null })
                showSubtitleText(cueGroup.cues.mapNotNull { it.text }.joinToString("\n").trim())
            }
            override fun onTracksChanged(tracks: Tracks) {
                for (g in tracks.groups) for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    android.util.Log.d(
                        "twig",
                        "player: track type=${g.type} mime=${f.sampleMimeType} " +
                            "codecs=${f.codecs} w=${f.width} h=${f.height} " +
                            "sr=${f.sampleRate} ch=${f.channelCount} supported=${g.isTrackSupported(i)}",
                    )
                }
                // 不支持的音轨(比如 DTS-HD LBR 扩展子流,设备没有对应解码器)也留着
                // 显示——之前直接从列表里过滤掉,用户会觉得"音轨少了一条"(其实提取
                // 没问题,是真解不了);trackName() 会标出来,选择时挡掉不真的应用。
                audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (cues.isNotEmpty()) return // 外挂字幕优先
                // ExoPlayer 可能按 default/forced 标志自行选中文本轨,同步到 subChoice(radio 才对得上)
                val sel = textGroups.indexOfFirst { it.isSelected }
                when {
                    sel >= 0 -> { subChoice = subFiles.size + 1 + sel; subEnabled = true }
                    // 无自动选中且用户没手动选过(点过"关闭"后不能再自动开)→ 启用第一条内嵌轨
                    !subUserChosen && subChoice == 0 && textGroups.isNotEmpty() -> {
                        selectTextTrack(0)
                        subChoice = subFiles.size + 1
                        subEnabled = true
                    }
                }
            }
        })

        // 非本地来源要连网(SFTP/SMB/WebDAV/FTP 握手是阻塞 socket IO),必须放后台线程——
        // 主线程直接调会被 StrictMode 判 NetworkOnMainThreadException(该异常 message 为
        // null,曾在 SFTP 上表现成一句没有信息量的"SFTP 连接失败: null")。lifecycleScope
        // 绑定 Activity 生命周期,onDestroy 时未完成的连接尝试自动放弃续做后续 UI 更新。
        lifecycleScope.launch {
            try {
                if (file.extension == "m2ts") {
                    prepareM2ts(p, file)
                } else if (file.scheme == "file") {
                    p.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(file.path))))
                } else {
                    // URI 必须带真实扩展名(见 [MediaSources]):DefaultExtractorsFactory 认不出
                    // 无扩展名 URI,退化 sniff 顺序会让 AVI 被 Mp3Extractor 误判。共享 helper 与
                    // 音乐引擎复用同一构建逻辑;这里预开一条共享随机源、onDestroy 统一关。
                    val (src, shared) = withContext(Dispatchers.IO) { MediaSources.networkEager(file) }
                    fsSrc = shared
                    p.setMediaSource(src)
                }
                holder?.let { p.setVideoSurfaceHolder(it) }
                if (disableAudio) {
                    p.trackSelectionParameters =
                        p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                }
                if (resumePositionMs > 0L) p.seekTo(resumePositionMs)
                p.playWhenReady = true
                p.prepare()
            } catch (e: Exception) {
                b.loading.visibility = View.GONE
                b.toolbar.subtitle = getString(R.string.viewer_load_failed, e.message ?: "")
            }
        }
    }

    /**
     * 真正的 M2TS(蓝光 BDAV 封装)每 192 字节一包(4 字节时间戳前缀 + 188 字节标准
     * TS 包),media3 `TsExtractor` 硬编码按 188 步进找同步字节,对不上直接判"无法
     * 识别容器"。但也有工具把普通 188 字节 TS 流存成 .m2ts 后缀——先探测真实包大小
     * 再决定要不要剥前缀,不能看后缀就假设。剥的话顺带显式指定 TsExtractor,不吃
     * DefaultExtractorsFactory 的 sniff 顺序(剥干净的 188 流本该没有歧义,但求稳)。
     */
    private suspend fun prepareM2ts(p: ExoPlayer, file: XFile) {
        val local = file.scheme == "file"
        // 探测包大小要读文件头,非本地来源还要先建连接(SFTP/SMB/... 阻塞 socket IO)——挪
        // 后台线程,原因同 preparePlayer 里 networkEager 那处。
        val (shared, rawPacketSize) = withContext(Dispatchers.IO) {
            val s = if (local) null else BufferedRandomSource(FsRegistry.of(file).openRandom(file))
            s to detectM2tsPacketSize(file, s)
        }
        if (shared != null) fsSrc = shared

        val baseFactory: DataSource.Factory = if (shared != null) {
            DataSource.Factory { RandomSourceDataSource(shared) }
        } else {
            FileDataSource.Factory()
        }
        val mediaUri = if (local) Uri.fromFile(java.io.File(file.path)).toString() else "twig:///media.ts"

        val sourceFactory = if (rawPacketSize == RAW_M2TS_PACKET_SIZE) {
            val totalRawLength = if (local) java.io.File(file.path).length() else file.size
            val strippingFactory =
                DataSource.Factory { M2tsStrippingDataSource(baseFactory.createDataSource(), totalRawLength) }
            ProgressiveMediaSource.Factory(strippingFactory, newM2tsExtractorsFactory())
        } else {
            ProgressiveMediaSource.Factory(baseFactory, MediaSources.extractors())
        }
        p.setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(mediaUri)))
    }

    private fun toggle() {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) p.pause() else p.play()
    }

    // ---- 手势 ----

    /** 左右边缘留给系统边缘返回手势,这个宽度内按下的触摸整个不处理(不喂手势识别器),
     *  避免和系统的返回手势抢——不止快进快退,连同这条边上的单击/双击一起让开。 */
    private val edgeGuardPx by lazy { 24f * resources.displayMetrics.density }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }

            // 双击整屏都是播放/暂停:分左右三档快进快退太容易误触(横滑定位本来就更好用)
            override fun onDoubleTap(e: MotionEvent): Boolean { toggle(); return true }

            /** 按住不动 ≈0.5s:临时加速播放,抬手([endGesture])恢复原速。 */
            override fun onLongPress(e: MotionEvent) {
                if (gesture != Gesture.NONE || speedBoosted) return // 已在滑动定位/调亮度,不抢
                val p = player?.takeIf { prepared && it.isPlaying } ?: return
                speedBoosted = true
                speedBeforeBoost = p.playbackParameters.speed
                val speed = com.twig.app.Prefs.longPressSpeed(this@MediaPlayerActivity)
                p.setPlaybackSpeed(speed)
                b.tvSpeed.text = getString(R.string.player_speed, fmtSpeed(speed))
                b.tvSpeed.visibility = View.VISIBLE
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (speedBoosted) return true // 加速中手指的微动不该变成定位/亮度手势
                val start = e1 ?: return false
                val totalDx = e2.x - start.x
                val totalDy = e2.y - start.y
                if (gesture == Gesture.NONE) {
                    if (kotlin.math.abs(totalDx) < 24 && kotlin.math.abs(totalDy) < 24) return false
                    gesture = when {
                        kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) -> Gesture.SEEK
                        start.x < b.root.width / 2f -> Gesture.BRIGHT
                        else -> Gesture.VOL
                    }
                    gestureStartPos = player?.takeIf { prepared }?.currentPosition?.toInt() ?: 0
                    gestureStartBright = window.attributes.screenBrightness.takeIf { it >= 0f }
                        ?: runCatching {
                            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                        }.getOrDefault(0.5f)
                    gestureStartVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                when (gesture) {
                    Gesture.SEEK -> {
                        val dur = player?.takeIf { prepared }?.duration?.toInt() ?: return true
                        // 整屏宽 = ±90 秒
                        val target = (gestureStartPos + (totalDx / b.root.width * 90_000).toInt())
                            .coerceIn(0, dur)
                        gestureSeekTo = target
                        val delta = (target - gestureStartPos) / 1000
                        showGestureHint("${fmt(target)}  [${if (delta >= 0) "+" else ""}${delta}s]")
                        b.seek.progress = target
                        b.tvPos.text = fmt(target)
                    }
                    Gesture.BRIGHT -> {
                        val v = (gestureStartBright - totalDy / b.root.height).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = v }
                        showGestureHint(getString(R.string.player_brightness, (v * 100).toInt()))
                    }
                    Gesture.VOL -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val v = (gestureStartVol + (-totalDy / b.root.height * max * 1.2f).toInt())
                            .coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        showGestureHint(getString(R.string.player_volume, v * 100 / max))
                    }
                    else -> {}
                }
                return true
            }
        })
        b.root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                (ev.x < edgeGuardPx || ev.x > b.root.width - edgeGuardPx)
            ) {
                return@setOnTouchListener false
            }
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) endGesture()
            true
        }
    }

    private fun endGesture() {
        if (gesture == Gesture.SEEK && gestureSeekTo >= 0) player?.takeIf { prepared }?.seekTo(gestureSeekTo.toLong())
        if (speedBoosted) {
            speedBoosted = false
            player?.setPlaybackSpeed(speedBeforeBoost)
            b.tvSpeed.visibility = View.GONE
        }
        gesture = Gesture.NONE
        gestureSeekTo = -1
        handler.postDelayed({ b.tvGesture.visibility = View.GONE }, 300)
    }

    /** 2.0f → "2"、1.5f → "1.5"(整数不拖个没用的 .0)。 */
    private fun fmtSpeed(s: Float): String =
        if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

    private fun showGestureHint(text: String, autoHide: Boolean = false) {
        b.tvGesture.text = text
        b.tvGesture.visibility = View.VISIBLE
        if (autoHide) handler.postDelayed({ b.tvGesture.visibility = View.GONE }, 600)
    }

    // ---- 字幕 ----

    /** 后台扫描同目录的 .srt/.ass/.ssa(同名靠前),同名的自动加载。 */
    private fun scanSubtitles() {
        Thread {
            runCatching {
                val fs = FsRegistry.of(file)
                val parent = XFile(file.scheme, file.parentPath, isDir = true)
                val base = file.name.substringBeforeLast('.').lowercase()
                val subs = fs.list(parent)
                    .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in SubtitleParser.EXTENSIONS }
                    .sortedByDescending { it.name.substringBeforeLast('.').lowercase() == base }
                runOnUiThread { subFiles = subs }
                subs.firstOrNull { it.name.substringBeforeLast('.').lowercase() == base }
                    ?.let { loadSubtitleFile(it) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 后台读取并解析一个外挂字幕文件(任何来源经 openInput 读字节)。 */
    private fun loadSubtitleFile(f: XFile) {
        Thread {
            runCatching {
                if (f.size > 4L shl 20) return@runCatching // >4MB 不像字幕
                val parsed = SubtitleParser.parse(FsRegistry.of(file).openInput(f).use { OpenFiles.readAllBytes(it) })
                if (parsed.isNotEmpty()) runOnUiThread {
                    cues = parsed
                    subChoice = subFiles.indexOf(f) + 1
                    subEnabled = true
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun updateSubtitle(pos: Int) {
        if (cues.isEmpty()) return
        showSubtitleText(if (subEnabled) activeCueText(pos) else null)
    }

    /** 当前时刻应显示的文本;ASS 常有多条重叠(对话+注释),向前回扫合并,最多 3 条。 */
    private fun activeCueText(pos: Int): String? {
        var lo = 0; var hi = cues.size - 1; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        val parts = ArrayList<String>(2)
        var i = idx
        while (i >= 0 && idx - i < 30 && parts.size < 3) {
            val c = cues[i]
            if (pos <= c.end && c.text !in parts) parts.add(c.text)
            i--
        }
        return if (parts.isEmpty()) null else parts.asReversed().joinToString("\n")
    }

    private fun showSubtitleText(text: String?) {
        if (text.isNullOrEmpty()) {
            b.tvSubtitle.visibility = View.GONE
        } else {
            if (b.tvSubtitle.text?.toString() != text) b.tvSubtitle.text = text
            b.tvSubtitle.visibility = View.VISIBLE
        }
    }

    // ---- 字幕 / 音轨选择 ----

    private fun showScaleDialog() {
        val names = arrayOf(
            getString(R.string.scale_best_fit),
            getString(R.string.scale_crop),
            getString(R.string.scale_fill),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.player_scale_mode)
            .setSingleChoiceItems(names, scaleMode.coerceIn(0, 2)) { d, which ->
                scaleMode = which
                com.twig.app.Prefs.setVideoScaleMode(this, which)
                resizeSurface()
                b.pgsView.invalidate()
                d.dismiss()
            }
            .show()
    }

    private fun trackName(g: Tracks.Group, i: Int, prefix: String): String {
        val f = g.getTrackFormat(0)
        val lang = f.language?.takeIf { it.isNotEmpty() && it != "und" }
        val label = f.label?.takeIf { it.isNotEmpty() }
        val format = if (g.type == C.TRACK_TYPE_AUDIO) audioFormatLabel(f).takeIf { it.isNotEmpty() } else null
        // 设备没有对应解码器时标出来(比如某些 DTS-HD 扩展子流)——之前直接把这种轨道
        // 从列表里隐藏,用户会以为提取漏了一条,其实是识别出来了但真解不了。
        val unsupported = if (!g.isTrackSupported(0)) getString(R.string.player_track_unsupported) else null
        return "$prefix ${i + 1}" + (format?.let { " · $it" } ?: "") + (label?.let { " · $it" } ?: "") +
            (lang?.let { " ($it)" } ?: "") + (unsupported?.let { " $it" } ?: "")
    }

    /**
     * 音轨格式简称 + 声道数,比如 "DTS-HD 5.1"、"AC-3 立体声"——和属性卡片"媒体" tab
     * 用的是同一份映射([FileInfo.codecName]/[FileInfo.channels]),两处显示保持一致。
     */
    private fun audioFormatLabel(f: Format): String {
        val mime = f.sampleMimeType ?: return ""
        val codec = FileInfo.codecName(mime)
        val ch = if (f.channelCount > 0) FileInfo.channels(this, f.channelCount) else null
        return listOfNotNull(codec.takeIf { it.isNotEmpty() }, ch).joinToString(" ")
    }

    private fun showSubtitleDialog() {
        val names = ArrayList<String>()
        names.add(getString(R.string.player_subtitle_close))
        subFiles.forEach { names.add(it.name) }
        textGroups.forEachIndexed { i, g -> names.add(trackName(g, i, getString(R.string.player_subtitle))) }
        AlertDialog.Builder(this)
            .setTitle(R.string.player_subtitle)
            .setSingleChoiceItems(names.toTypedArray(), subChoice.coerceIn(0, names.size - 1)) { d, which ->
                applySubChoice(which)
                d.dismiss()
            }
            .show()
    }

    private fun applySubChoice(which: Int) {
        subUserChosen = true
        subChoice = which
        cues = emptyList()
        b.tvSubtitle.visibility = View.GONE
        b.pgsView.setCues(emptyList())
        val p = player ?: return
        when {
            which == 0 -> {
                subEnabled = false
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            which <= subFiles.size -> {
                subEnabled = true
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                loadSubtitleFile(subFiles[which - 1])
            }
            else -> {
                subEnabled = true
                selectTextTrack(which - subFiles.size - 1)
            }
        }
    }

    private fun selectTextTrack(i: Int) {
        val p = player ?: return
        val g = textGroups.getOrNull(i) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    private fun showAudioTrackDialog() {
        val p = player?.takeIf { prepared } ?: return
        if (audioGroups.isEmpty()) {
            showGestureHint(getString(R.string.player_no_tracks), autoHide = true)
            return
        }
        val names = audioGroups.mapIndexed { i, g -> trackName(g, i, getString(R.string.player_audio_track)) }
        val cur = audioGroups.indexOfFirst { it.isSelected }
        AlertDialog.Builder(this)
            .setTitle(R.string.player_audio_track)
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                audioGroups.getOrNull(which)?.let { g ->
                    if (!g.isTrackSupported(0)) {
                        showGestureHint(getString(R.string.player_track_unsupported_hint), autoHide = true)
                        return@setSingleChoiceItems
                    }
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
                        .build()
                }
                d.dismiss()
            }
            .show()
    }

    // ---- 控制栏 / 画面 ----

    /**
     * 锁定/解锁屏幕方向。`SCREEN_ORIENTATION_LOCKED` 锁的是**当前**方向,所以躺着看时先转到
     * 想要的方向再按。只在本次播放有效,不进 Prefs——横竖屏偏好是跟着片子和姿势走的。
     */
    private fun toggleOrientationLock() {
        orientationLocked = !orientationLocked
        requestedOrientation = if (orientationLocked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        b.btnOrientation.setImageResource(
            if (orientationLocked) R.drawable.ic_rotate_lock else R.drawable.ic_rotate_unlock,
        )
        b.btnOrientation.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (orientationLocked) R.color.accent else R.color.white),
        )
        showGestureHint(
            getString(
                if (orientationLocked) R.string.player_orientation_locked else R.string.player_orientation_auto,
            ),
            autoHide = true,
        )
    }

    private fun toggleControls() {
        val show = b.toolbar.visibility != View.VISIBLE
        val v = if (show) View.VISIBLE else View.GONE
        b.toolbar.visibility = v
        b.controls.visibility = v
    }

    /**
     * 可用区域一变(旋转、分屏拖拽、多窗口)就重算一次画面尺寸。
     *
     * ★ 不能指望 `surfaceChanged`:[resizeSurface] 把 SurfaceView 的 layoutParams 写成
     * **固定像素**宽高,旋转后这个值原样不动 → SurfaceView 自身尺寸没变化 → 回调不来,
     * 画面就一直按旧方向的比例摆着。(以前点一下屏幕能"修好",是显隐控制栏触发的整树
     * relayout 顺带让 surface 重走一遍回调,纯属巧合。)configChanges 里带了
     * orientation|screenSize,Activity 不重建,所以也没有 onCreate 兜底。
     */
    private fun watchRootSize() {
        b.root.addOnLayoutChangeListener { _, l, t, r, bo, _, _, _, _ ->
            val w = r - l
            val h = bo - t
            if (w == rootW && h == rootH) return@addOnLayoutChangeListener
            rootW = w; rootH = h
            // 布局回调里改 layoutParams 会被推到下一帧,干脆自己 post 一次
            b.root.post { resizeSurface() }
        }
    }

    private fun resizeSurface() {
        if (videoW == 0 || videoH == 0) return
        val vw = b.root.width.toFloat(); val vh = b.root.height.toFloat()
        if (vw == 0f) return
        val (w, h) = when (scaleMode) {
            SCALE_CROP -> { // 保持比例填满屏幕,多出的裁掉(画面居中,FrameLayout 裁剪)
                val s = maxOf(vw / videoW, vh / videoH)
                (videoW * s).toInt() to (videoH * s).toInt()
            }
            SCALE_FILL -> vw.toInt() to vh.toInt() // 拉伸填充,不保比例
            else -> { // 最佳适配:完整显示
                val s = minOf(vw / videoW, vh / videoH)
                (videoW * s).toInt() to (videoH * s).toInt()
            }
        }
        b.surface.layoutParams = b.surface.layoutParams.apply { width = w; height = h }
        b.surface.requestLayout()
        // 位图字幕按视频显示矩形映射坐标(画面居中)
        b.pgsView.setVideoRect((vw - w) / 2f, (vh - h) / 2f, w.toFloat(), h.toFloat())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        immersive()
        preparePlayer(holder, resumePositionMs = resumeFrom)
        player?.setVideoSurfaceHolder(holder)
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { resizeSurface() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isVideo) immersive()
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.takeIf { prepared && it.isPlaying }?.pause()
    }

    /**
     * 落一次播放进度。SharedPreferences 的读是内存里的、写走 apply() 异步落盘,主线程调
     * 没问题;「该不该记 / 该不该删」的判断全在 [PlaybackStore.save] 里。
     */
    private fun saveProgress() {
        if (!isVideo || !prepared) return
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        lastSaveAt = SystemClock.elapsedRealtime()
        PlaybackStore.save(this, file, p.currentPosition, dur)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        handler.removeCallbacks(ticker)
        player?.release() // 异步收尾,不阻塞主线程
        player = null
        val src = fsSrc; fsSrc = null
        // SMB 专用连接的销毁走 smb-io 线程,放后台避免等待
        if (src != null) Thread { runCatching { src.close() } }.apply { isDaemon = true }.start()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val SCALE_BEST_FIT = 0 // 原始比例完整显示
        const val SCALE_CROP = 1     // 保比例裁切填满
        const val SCALE_FILL = 2     // 拉伸填充
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"

        fun start(context: Context, file: XFile) {
            context.startActivity(
                Intent(context, MediaPlayerActivity::class.java).apply {
                    putExtra(EXTRA_SCHEME, file.scheme)
                    putExtra(EXTRA_PATH, file.path)
                    putExtra(EXTRA_NAME, file.name)
                    putExtra(EXTRA_SIZE, file.size)
                },
            )
        }
    }
}
