package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
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
import androidx.media3.exoplayer.DefaultLoadControl
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
import com.twig.core.PlayState
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Built-in audio/video player, based on media3 ExoPlayer:
 * - Self demuxer (MKV/MP4 etc.), not limited to system MediaPlayer's container support;
 * - ffmpeg audio software-decoding extension as fallback (AC3/EAC3/DTS/TrueHD and other decoders
 *   that devices don't have);
 * - Non-local sources go through [RandomSourceDataSource] for direct positioned reads from
 *   [RandomSource] (SMB native pread).
 */
@UnstableApi
class MediaPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var b: ActivityMediaPlayerBinding
    private lateinit var file: XFile
    private var isVideo = false

    private var player: ExoPlayer? = null
    private var fsSrc: RandomSource? = null // share a single SMB-dedicated connection, seek that reopens DataSource costs zero
    private var prepared = false
    // Two-stage fallback for runtime decode crashes: 0=system preferred, 1=already tried ffmpeg-first retry, 2=audio track turned off entirely (play video silently)
    private var fallbackStage = 0
    private var videoW = 0
    private var videoH = 0
    private var rootW = 0 // last computed available area, used to recognize size changes from rotation / split-screen
    private var rootH = 0
    private var scaleMode = SCALE_BEST_FIT
    // last position (looked up once in onCreate; the actual seek happens during player prepare)
    private var resumeFrom = 0L
    private var lastSaveAt = 0L

    /**
     * When progress is kept by the source itself (Jellyfin / Emby) take this path and don't write
     * to the local `PlaybackStore` — if both sides record it, the same movie gets one position
     * on the server and another on the device, and neither side can tell which is right.
     */
    private var remote: RemoteProgress? = null

    /** Series queue (episodes of the same show, in order); empty when not computable, in which case the two buttons are not shown. */
    private var queue: List<XFile> = emptyList()
    private var queueIndex = -1

    /**
     * Whether the resume position is ready. Remote progress requires a network round-trip to
     * know, while [surfaceCreated] usually arrives first — at that point **don't prepare yet**;
     * wait for the position to come back before starting. Going the other way — "start from the
     * beginning, then seek when the position arrives" — produces a visible jump on screen, while
     * the extra two or three hundred milliseconds of waiting are lost in buffering and invisible.
     */
    private var resumeReady = true
    private var pendingHolder: SurfaceHolder? = null
    private var startedReported = false

    // Gestures: horizontal swipe to seek / left-side vertical swipe for brightness / right-side vertical swipe for volume
    private enum class Gesture { NONE, SEEK, BRIGHT, VOL }
    private var gesture = Gesture.NONE
    private var gestureSeekTo = -1
    private var gestureStartPos = 0
    private var gestureStartBright = 0.5f
    private var gestureStartVol = 0
    private var orientationLocked = false
    private var speedBoosted = false // long-press to speed up; release to restore original speed
    private var speedBeforeBoost = 1f
    private lateinit var audio: AudioManager

    // Subtitles: external (self-parsed) preferred; embedded text tracks (SRT/ASS in MKV etc.) are decoded by ExoPlayer and shown via onCues
    private var cues: List<SubCue> = emptyList()
    private var subEnabled = true
    private var subFiles: List<XFile> = emptyList()
    private var textGroups: List<Tracks.Group> = emptyList()
    private var audioGroups: List<Tracks.Group> = emptyList()
    private var subChoice = 0 // 0=off; 1..subFiles.size=external; afterwards=embedded text track
    private var subUserChosen = false // once the user has manually picked subtitles, no longer auto-enable embedded tracks

    /**
     * Pause dimming: the window holds FLAG_KEEP_SCREEN_ON for the whole session, so a video left
     * paused used to sit at full brightness forever. Once playback is paused it goes in two steps:
     * after the system's own screen-off timeout the window drops to [DIM_BRIGHTNESS] (still awake,
     * just dark), and after [SCREEN_OFF_DELAY] FLAG_KEEP_SCREEN_ON is dropped as well, so a video
     * paused and forgotten lets the screen go off like any other app. Any touch or key press
     * restores both, along with [userBrightness] — BRIGHTNESS_OVERRIDE_NONE (follow the system)
     * until the brightness gesture sets a value.
     */
    private var dimmed = false
    private var keepOn = true
    private var userBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var swallowTouch = false // the touch that wakes the screen must not also toggle playback

    private val handler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable {
        dimmed = true
        setWindowBrightness(DIM_BRIGHTNESS)
    }
    private val screenOffRunnable = Runnable {
        keepOn = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private val ticker = object : Runnable {
        override fun run() {
            player?.let {
                if (prepared) {
                    val pos = it.currentPosition.toInt()
                    if (gesture != Gesture.SEEK) { b.seek.progress = pos; b.tvPos.text = fmt(pos) }
                    updateSubtitle(pos)
                    // Periodically persist progress: onPause covers normal exit, this covers "process killed directly by the system"
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
        // ★ displayName must be carried along: for sources like content:// where "the path has no
        // file name", the extension can only come from the display name. Lose it → isVideo is
        // judged false (no surface bound, only sound remains), and MediaSources's `twig:///media.<ext>`
        // also loses its extension (container recognition degrades to sniff order)
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
        if (isVideo) {
            setupGestures()
            b.tvSpeed.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, fastForwardIcon(), null)
        }
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

        b.btnPrev.setOnClickListener { playAt(queueIndex - 1) }
        b.btnNext.setOnClickListener { playAt(queueIndex + 1) }

        if (isVideo) {
            loadQueue()
            // Resume memory is for video only: audio file resume is the MusicEngine/PlaylistStore's job
            remote = RemoteProgress.of(this, file)
            if (remote != null) fetchRemoteResume() else resumeFrom = PlaybackStore.positionFor(this, file)
            b.surface.holder.addCallback(this)
            watchRootSize()
            scanSubtitles()
        } else {
            b.audioInfo.visibility = View.VISIBLE
            b.audioName.text = name
            preparePlayer(null)
        }
    }

    // ---- Player ----

    /**
     * Cap how much demuxed data the player is allowed to hold. **Without this the process
     * dies on high-bitrate 4K over the network**, and because the master password's DEK
     * only lives in memory, the crash shows up to the user as "the player quit and now it
     * asks for my password again".
     *
     * media3's own defaults are far too generous for a 256MB heap
     * (`dalvik.vm.heapgrowthlimit` on a normal phone): `DEFAULT_VIDEO_BUFFER_SIZE` is
     * 125MB and the muxed variant 137MB. media3 1.9 does have a much smaller local-playback
     * tier (`DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK`, 18.7MB), but it picks it by URI
     * scheme against `LOCAL_PLAYBACK_SCHEMES` = file/content/data/android.resource/
     * rawresource/asset — **our network playback URI is `twig:///media.$ext`, so it never
     * qualifies** and always lands in the largest tier. Add [BufferedRandomSource]'s own
     * 24MB block cache on top and a 60fps HDR10 remux fills the heap in under a minute;
     * the OOM then lands on whatever allocates next, typically inside MediaCodec.
     *
     * 48MB is still several seconds of headroom even at 80Mbps, and the duration bounds
     * below are what actually governs low-bitrate files (where the byte cap is never hit).
     */
    private fun loadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .build()

    private fun preparePlayer(
        holder: SurfaceHolder?,
        preferExtensionDecoders: Boolean = false,
        disableAudio: Boolean = false,
        resumePositionMs: Long = 0L,
    ) {
        if (player != null) return
        b.loading.visibility = View.VISIBLE
        // EXTENSION_RENDERER_MODE_ON: prefer the system MediaCodec (power saving); fall back to
        // ffmpeg software decoding when no matching decoder is available.
        // Some devices' system decoders crash directly on certain streams (instead of reporting
        // "unsupported") — for such runtime crashes there's no way to read "no decoder" as a hint;
        // we can only wait for onPlayerError and downgrade the whole thing to PREFER for a retry.
        // Custom AudioSink: downmix >2-channel PCM to stereo first (some devices' 6-channel
        // AudioTrack repeatedly dies → playback stalls)
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
        // Local files use setMediaItem, with container parsing handled by the player's default
        // MediaSource factory — attach [MediaSources.extractors] (which includes
        // [TwigSubtitleParserFactory]); otherwise PGS subtitles in local MKV still fall back to
        // the official PgsParser and only show one segment per screen.
        // Audio focus: without requesting it, music playing elsewhere (the built-in MusicEngine
        // / external music apps) won't be paused and the sounds just stack on top of each other.
        // becomingNoisy is also on — auto-pause without speaker output when headphones unplug
        // or Bluetooth disconnects.
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, MediaSources.extractors()))
            .setLoadControl(loadControl())
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
        // Metadata tracks (SCTE-35 ad insertion signaling, etc.) are never consumed by this player,
        // yet have crashed in practice: SCTE-35 data in some m2ts remuxes is non-conforming, and
        // media3's SpliceInfoDecoder throws IllegalStateException directly on out-of-bounds,
        // bringing down the whole player. Since we don't use it, disable it directly.
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
                        if (com.twig.app.Prefs.autoNextEpisode(this@MediaPlayerActivity) &&
                            queueIndex in 0 until queue.size - 1
                        ) {
                            playAt(queueIndex + 1)
                        }
                    }
                    else -> {}
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) wakeScreen() else scheduleDim()
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
                // ERROR_CODE_UNSPECIFIED counts too: in practice, jellyfin's ffmpeg extension NPEs
                // directly in the constructor for some DTS variants (not bad input feeding decode
                // failure, but the decoder itself failing to build); ExoPlayer reports all such
                // uncategorized runtime exceptions as UNSPECIFIED.
                val recoverable = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
                    error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED
                if (fallbackStage < 2 && recoverable) {
                    // Runtime decode crash (not "no decoder" but "claims support but explodes on
                    // first decode / bad input feeding in") — or no crash but every packet fails
                    // and playback stalls, triggering TIMEOUT.
                    // First level: switch the whole thing to ffmpeg-first and retry (some devices'
                    // system decoders are themselves buggy).
                    // Second level: ffmpeg can't handle it either (e.g. the source audio track is
                    // locally corrupted / padded with garbage bytes) — just turn off the audio
                    // track and play video silently, better than freezing outright. Swapping
                    // renderer in place won't work: the player is already in error state, all
                    // renderers are disabled; we have to rebuild the whole thing.
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
                // Bitmap subtitles (PGS, etc.) drawn onto the overlay layer; text subtitles go through TextView
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
                // Unsupported audio tracks (e.g. DTS-HD LBR extension sub-stream, no matching decoder
                // on the device) are also kept visible — previously they were filtered from the list
                // and the user would think "one audio track is missing" (when actually extraction
                // is fine, the device just can't decode it); trackName() marks them so the selection
                // blocks applying an unplayable track.
                audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (cues.isNotEmpty()) return // external subtitles take priority
                // ExoPlayer may auto-select a text track by its default/forced flag; sync that to subChoice (so the radio lines up)
                val sel = textGroups.indexOfFirst { it.isSelected }
                when {
                    sel >= 0 -> { subChoice = subFiles.size + 1 + sel; subEnabled = true }
                    // No auto-selection and the user hasn't manually chosen (after tapping "off" it must not auto-enable) → enable the first embedded track
                    !subUserChosen && subChoice == 0 && textGroups.isNotEmpty() -> {
                        selectTextTrack(0)
                        subChoice = subFiles.size + 1
                        subEnabled = true
                    }
                }
            }
        })

        // Non-local sources require networking (SFTP/SMB/WebDAV/FTP handshakes are blocking socket IO),
        // must be on a background thread — calling on the main thread triggers StrictMode's
        // NetworkOnMainThreadException (whose message is null, which on SFTP showed up as an
        // uninformative "SFTP connection failed: null"). lifecycleScope binds to the Activity
        // lifecycle; on onDestroy, in-flight connection attempts are automatically abandoned
        // and won't continue updating the UI.
        lifecycleScope.launch {
            try {
                if (file.extension == "m2ts") {
                    prepareM2ts(p, file)
                } else if (file.scheme == "file") {
                    p.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(file.path))))
                } else {
                    // The URI must carry a real extension (see [MediaSources]): DefaultExtractorsFactory
                    // cannot recognize a URI without an extension, and the degraded sniff order would
                    // let AVI be misidentified by Mp3Extractor. The shared helper reuses the same
                    // construction as the music engine; here we pre-open a shared random source and
                    // close them all together in onDestroy.
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
     * True M2TS (Blu-ray BDAV container) uses one packet every 192 bytes (4-byte timestamp prefix
     * + 188-byte standard TS packet); media3's `TsExtractor` hardcodes a 188-byte step when
     * searching for sync bytes, and a stream that doesn't match is reported as "unrecognized
     * container". But some tools also store ordinary 188-byte TS streams under the .m2ts
     * extension — first detect the real packet size, then decide whether to strip prefixes;
     * never assume from the extension alone. When stripping, also explicitly specify TsExtractor
     * rather than relying on DefaultExtractorsFactory's sniff order (a clean 188-byte stream
     * shouldn't be ambiguous in theory, but better safe).
     */
    private suspend fun prepareM2ts(p: ExoPlayer, file: XFile) {
        val local = file.scheme == "file"
        // Detecting the packet size needs to read the file header; non-local sources also need
        // to open a connection first (SFTP/SMB/... blocking socket IO) — move to a background
        // thread, same reason as networkEager in preparePlayer.
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

    // ---- Gestures ----

    /** The left and right edges are reserved for the system's edge-back gesture; touches inside
     *  this width aren't processed at all (not fed to the gesture detector) to avoid fighting
     *  the system gesture — not just fast-forward/rewind, but also single/double taps on these
     *  edges are let through. */
    private val edgeGuardPx by lazy { 24f * resources.displayMetrics.density }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }

            // Double-tap anywhere on the screen is play/pause: splitting left/right into three-step
            // seek is too easy to trigger by accident (horizontal swipe seek works better anyway)
            override fun onDoubleTap(e: MotionEvent): Boolean { toggle(); return true }

            /** Holding ≈0.5s: temporarily speed up playback; on release ([endGesture]) restore original speed. */
            override fun onLongPress(e: MotionEvent) {
                if (gesture != Gesture.NONE || speedBoosted) return // already seeking/adjusting brightness, don't steal
                val p = player?.takeIf { prepared && it.isPlaying } ?: return
                speedBoosted = true
                speedBeforeBoost = p.playbackParameters.speed
                val speed = com.twig.app.Prefs.longPressSpeed(this@MediaPlayerActivity)
                p.setPlaybackSpeed(speed)
                b.tvSpeed.text = getString(R.string.player_speed, fmtSpeed(speed))
                b.tvSpeed.visibility = View.VISIBLE
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (speedBoosted) return true // micro-movements while speeding up shouldn't turn into seek/brightness gestures
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
                        // full screen width = ±90 seconds
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
                        userBrightness = v
                        setWindowBrightness(v)
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

    /** 2.0f → "2", 1.5f → "1.5" (integers don't carry a useless .0). */
    private fun fmtSpeed(s: Float): String =
        if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

    /** The fast-forward glyph shown after "2x" in [tv_speed]. A compound drawable never picks up
     *  the host TextView's shadowColor/Dx/Dy/Radius — those only paint the text layout — so a
     *  static drawableEnd icon stayed flat while the digits got a shadow. This bakes the glyph
     *  into a bitmap using the exact same [Paint.setShadowLayer] call, with the exact same
     *  radius/dx/dy/color as tv_speed's own shadow* attrs (raw pixels, unscaled by density —
     *  matching them, not "close to" them, is the point), so the two shadows read as one style.
     *
     *  The vertical bias is measured from [tv_speed]'s own live [android.text.TextPaint], not a
     *  hand-tuned dp constant: TextView centers a compound drawable against the view's content
     *  box, but "×" — the character actually adjacent to the icon, not the variable digit before
     *  it — has its own ink sitting off that box's center. A constant calibrated by eye on one
     *  phone would drift on another device/OEM font with different metrics; deriving it from
     *  [getTextBounds] on the actual paint at the actual text size self-corrects instead.
     *  ★ This box is `fontMetrics.ascent..descent` ONLY because tv_speed has
     *  `includeFontPadding="false"` — the default (true) sizes wrap_content against the font's
     *  wider top/bottom metrics instead (extra headroom for accents/CJK that this run of ASCII
     *  digits never uses), which on a CJK-fallback font pushed the box's true center well above
     *  what this ascent/descent math accounted for — the actual cause of the icon reading
     *  "too high" through two rounds of tuning this value directly, both against the wrong box. */
    private fun fastForwardIcon(iconSizeDp: Float = 16f): BitmapDrawable {
        val density = resources.displayMetrics.density
        val iconPx = iconSizeDp * density
        val shadowRadius = 4f; val shadowDx = 1f; val shadowDy = 1f // matches tv_speed's shadow* attrs verbatim
        val bleed = shadowRadius + kotlin.math.max(shadowDx, shadowDy) + 1f

        val textPaint = b.tvSpeed.paint
        val fm = textPaint.fontMetrics
        val lineBoxCenter = (fm.ascent + fm.descent) / 2f
        val glyphBounds = Rect()
        textPaint.getTextBounds("×", 0, 1, glyphBounds)
        val glyphCenter = (glyphBounds.top + glyphBounds.bottom) / 2f
        val risePx = lineBoxCenter - glyphCenter // >0: box center sits below "×"'s ink center

        val topPad = (bleed - risePx).coerceAtLeast(0f)
        val bottomPad = (bleed + risePx).coerceAtLeast(0f)
        val w = (iconPx + bleed * 2).toInt()
        val h = (iconPx + topPad + bottomPad).toInt()
        val path = Path().apply {
            moveTo(2f, 6f); lineTo(2f, 18f); lineTo(10.5f, 12f); close()
            moveTo(12f, 6f); lineTo(12f, 18f); lineTo(20.5f, 12f); close()
        }
        path.transform(Matrix().apply {
            setScale(iconPx / 24f, iconPx / 24f)
            postTranslate(bleed, topPad)
        })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(shadowRadius, shadowDx, shadowDy, 0xCC000000.toInt())
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPath(path, paint)
        return BitmapDrawable(resources, bmp)
    }

    private fun showGestureHint(text: String, autoHide: Boolean = false) {
        b.tvGesture.text = text
        b.tvGesture.visibility = View.VISIBLE
        if (autoHide) handler.postDelayed({ b.tvGesture.visibility = View.GONE }, 600)
    }

    // ---- Subtitles ----

    /** Background scan the same directory for .srt/.ass/.ssa (same-name ones first); same-name files auto-load. */
    private fun scanSubtitles() {
        Thread {
            runCatching {
                val fs = FsRegistry.of(file)
                // External subtitles from the source itself (media servers expose them as subtitle
                // streams, with nothing in the same directory to scan); they come back as ordinary
                // XFile, and the read/parse/select path below is the same
                val remote = runCatching {
                    (fs as? com.twig.core.MediaInfoSource)?.subtitlesOf(file).orEmpty()
                }.getOrDefault(emptyList())
                val base = file.name.substringBeforeLast('.').lowercase()
                // Don't list the parent directory again when the source already provided some:
                // those sources don't have subtitle files in their directories, just a wasted
                // network round-trip
                val sidecars = if (remote.isNotEmpty()) {
                    emptyList()
                } else {
                    val parent = XFile(file.scheme, file.parentPath, isDir = true)
                    fs.list(parent)
                        .filter {
                            !it.isDir &&
                                it.name.substringAfterLast('.', "").lowercase() in SubtitleParser.EXTENSIONS
                        }
                        .sortedByDescending { it.name.substringBeforeLast('.').lowercase() == base }
                }
                val subs = remote + sidecars
                runOnUiThread { subFiles = subs }
                // Auto-attach: take the first one given by the source (usually the default track),
                // otherwise take the same-named file
                (remote.firstOrNull() ?: sidecars.firstOrNull { it.name.substringBeforeLast('.').lowercase() == base })
                    ?.let { loadSubtitleFile(it) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Background read and parse an external subtitle file (reads bytes via openInput from any source). */
    private fun loadSubtitleFile(f: XFile) {
        Thread {
            runCatching {
                if (f.size > 4L shl 20) return@runCatching // >4MB doesn't look like subtitles
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

    /** The text that should be shown at the current moment; ASS often has multiple overlapping
     *  lines (dialogue + commentary); scan backward and merge, up to 3. */
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

    // ---- Subtitle / audio track selection ----

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
        // Mark tracks the device can't decode (e.g. some DTS-HD extension sub-streams) — previously
        // such tracks were hidden from the list and the user thought extraction missed one; in fact
        // they're recognized, the device just can't decode them.
        val unsupported = if (!g.isTrackSupported(0)) getString(R.string.player_track_unsupported) else null
        return "$prefix ${i + 1}" + (format?.let { " · $it" } ?: "") + (label?.let { " · $it" } ?: "") +
            (lang?.let { " ($it)" } ?: "") + (unsupported?.let { " $it" } ?: "")
    }

    /**
     * Audio format short name + channel count, e.g. "DTS-HD 5.1", "AC-3 Stereo" — uses the same
     * mapping as the "Media" tab in the properties card ([FileInfo.codecName]/[FileInfo.channels]),
     * keeping the two displays consistent.
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

    // ---- Controls / video ----

    /**
     * Lock/unlock the screen orientation. `SCREEN_ORIENTATION_LOCKED` locks to the **current**
     * orientation, so if watching lying down, rotate to the desired direction first then press.
     * Only takes effect for this playback, doesn't go into Prefs — landscape/portrait preference
     * depends on the video and posture.
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

    // ---- Screen dimming while paused ----

    private fun setWindowBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    /** How long to stay bright after pausing: the system's own screen-off timeout, clamped so that
     *  "never sleep" (Int.MAX_VALUE) still dims eventually — and always well before
     *  [SCREEN_OFF_DELAY], so the two steps never collapse into one. */
    private fun dimDelay(): Long = runCatching {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toLong()
    }.getOrDefault(30_000L).coerceIn(15_000L, 5 * 60_000L)

    /** Arm both timers, but only while paused — playing keeps the screen bright and awake. */
    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(screenOffRunnable)
        if (player?.isPlaying != true) {
            handler.postDelayed(dimRunnable, dimDelay())
            handler.postDelayed(screenOffRunnable, SCREEN_OFF_DELAY)
        }
    }

    /** Restore brightness and the keep-awake flag, then start the wait over. */
    private fun wakeScreen() {
        if (dimmed) {
            dimmed = false
            setWindowBrightness(userBrightness)
        }
        if (!keepOn) {
            keepOn = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        scheduleDim()
    }

    /** Any interaction wakes a dimmed screen, and the waking touch itself is swallowed — the whole
     *  gesture, not just the DOWN, since a half-delivered gesture confuses the detector — so that a
     *  tap meant only to see the picture doesn't also toggle playback. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowTouch = dimmed
            wakeScreen()
        }
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) wakeScreen()
        return super.dispatchKeyEvent(event)
    }

    private fun toggleControls() {
        val show = b.toolbar.visibility != View.VISIBLE
        val v = if (show) View.VISIBLE else View.GONE
        b.toolbar.visibility = v
        b.controls.visibility = v
    }

    /**
     * Recompute the picture size whenever the available area changes (rotation, split-screen drag, multi-window).
     *
     * ★ Can't rely on `surfaceChanged`: [resizeSurface] writes the SurfaceView's layoutParams as
     * **fixed pixel** width/height; after rotation this value doesn't change → the SurfaceView's
     * own size doesn't change → no callback arrives, and the picture stays laid out at the old
     * direction's aspect ratio. (Previously a screen tap would "fix" it because showing/hiding
     * the controls triggered a full-tree relayout that incidentally made the surface redo its
     * callback — pure coincidence.) configChanges includes orientation|screenSize so the Activity
     * doesn't get recreated, and there's no onCreate fallback.
     */
    private fun watchRootSize() {
        b.root.addOnLayoutChangeListener { _, l, t, r, bo, _, _, _, _ ->
            val w = r - l
            val h = bo - t
            if (w == rootW && h == rootH) return@addOnLayoutChangeListener
            rootW = w; rootH = h
            // Changing layoutParams inside the layout callback gets pushed to the next frame; just post once ourselves
            b.root.post { resizeSurface() }
        }
    }

    private fun resizeSurface() {
        if (videoW == 0 || videoH == 0) return
        val vw = b.root.width.toFloat(); val vh = b.root.height.toFloat()
        if (vw == 0f) return
        val (w, h) = when (scaleMode) {
            SCALE_CROP -> { // preserve aspect ratio to fill the screen, crop the excess (centered, FrameLayout clipping)
                val s = maxOf(vw / videoW, vh / videoH)
                (videoW * s).toInt() to (videoH * s).toInt()
            }
            SCALE_FILL -> vw.toInt() to vh.toInt() // stretch to fill, doesn't preserve aspect
            else -> { // best fit: show fully
                val s = minOf(vw / videoW, vh / videoH)
                (videoW * s).toInt() to (videoH * s).toInt()
            }
        }
        b.surface.layoutParams = b.surface.layoutParams.apply { width = w; height = h }
        b.surface.requestLayout()
        // Bitmap subtitles are mapped to the video display rectangle (centered)
        b.pgsView.setVideoRect((vw - w) / 2f, (vh - h) / 2f, w.toFloat(), h.toFloat())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        immersive()
        // The remote resume position is still in flight: remember the holder, wait for it (or
        // timeout) before starting; see [resumeReady]
        if (!resumeReady) { pendingHolder = holder; return }
        startPlayback(holder)
    }

    private fun startPlayback(holder: SurfaceHolder) {
        if (isFinishing || isDestroyed) return
        preparePlayer(holder, resumePositionMs = resumeFrom)
        player?.setVideoSurfaceHolder(holder)
    }

    // ---- Series queue ----

    /**
     * Compute this episode's series queue (background thread: both paths need network I/O).
     *
     * Two paths, **ask the source first, then guess from filenames**:
     * - The source knows its own series structure ([EpisodeSeries], currently Jellyfin/Emby):
     *   one request returns the whole show, ordered across seasons. ★ An episode in "Continue
     *   Watching" **only has this path** — its sibling on the tree is another show.
     * - Other sources: list video files in the same directory and group them by season/episode
     *   numbers in the filename (see [Episodes]).
     *
     * If it can't be computed, keep an empty queue and don't show the two buttons — better
     * no button than a button that does something random when tapped.
     */
    private fun loadQueue() {
        Thread {
            val q = runCatching { computeQueue() }.getOrElse { e ->
                android.util.Log.w("twig", "player: queue failed: ${e.message}")
                emptyList()
            }
            val idx = q.indexOfFirst { it.scheme == file.scheme && it.path == file.path }
            runOnUiThread {
                // The user may have manually skipped an episode before we finished computing;
                // in that case don't overwrite with this stale result
                if (isFinishing || isDestroyed || queue.isNotEmpty()) return@runOnUiThread
                if (idx < 0) return@runOnUiThread // current episode not in the queue = bad computation, treat as none
                queue = q
                queueIndex = idx
                syncQueueButtons()
            }
        }.apply { isDaemon = true; name = "twig-queue" }.start()
    }

    private fun computeQueue(): List<XFile> {
        val fs = runCatching { FsRegistry.of(file) }.getOrNull() ?: return emptyList()
        (fs as? com.twig.core.EpisodeSeries)?.episodesOf(file)?.let { return it }
        val parent = runCatching { fs.resolve(file.parentPath) }.getOrNull() ?: return emptyList()
        val sibs = runCatching { fs.list(parent) }.getOrNull()
            ?.filter { !it.isDir && OpenFiles.isVideo(it) } ?: return emptyList()
        return com.twig.app.Episodes.queueOf(file, sibs)
    }

    /** When there's no previous/next episode in the queue, hide the corresponding button to avoid dead taps. */
    private fun syncQueueButtons() {
        val has = queueIndex >= 0 && queue.size > 1
        b.btnPrev.visibility = if (has && queueIndex > 0) View.VISIBLE else View.GONE
        b.btnNext.visibility = if (has && queueIndex < queue.size - 1) View.VISIBLE else View.GONE
    }

    /**
     * Play a different episode. **Do not recreate the Activity** — the surface is still there,
     * and recreation would cause a black flash, plus we'd have to re-run the immersive / gesture /
     * orientation lock setup.
     *
     * Ending the current episode must report [PlayState.STOP]: the server uses this to end the
     * "now playing" session; without it, the session hangs, and the next episode's START collides
     * with it.
     */
    private fun playAt(index: Int) {
        val next = queue.getOrNull(index) ?: return
        saveProgress(PlayState.STOP)
        remote?.shutdown()
        remote = null
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        prepared = false
        startedReported = false
        val old = fsSrc
        fsSrc = null
        if (old != null) Thread { runCatching { old.close() } }.apply { isDaemon = true }.start()

        queueIndex = index
        file = next
        resumeFrom = 0
        b.toolbar.title = file.name
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(file.name))
        syncQueueButtons()
        scanSubtitles()

        // ★ The surface is already created, no surfaceCreated callback coming — manually hand
        // the holder to the resume gate so it follows the same path as the first play (wait for
        // the remote position to arrive before preparing)
        pendingHolder = b.surface.holder
        resumeReady = false
        remote = RemoteProgress.of(this, file)
        if (remote != null) {
            fetchRemoteResume()
        } else {
            resumeFrom = PlaybackStore.positionFor(this, file)
            releaseResumeGate()
        }
    }

    /**
     * Ask the server for the resume position. If the server has no record (or marks the video
     * as fully watched), fall back to the local copy — switching devices, or having previously
     * watched this from local, the local record still matters.
     *
     * A timeout fallback is required: when the network stalls, "wait for the position" must not
     * become "never plays", which on screen just looks like a stuck loading spinner with no clue.
     */
    private fun fetchRemoteResume() {
        val r = remote ?: return
        resumeReady = false
        Thread {
            val pos = runCatching { r.position() }.getOrElse { e ->
                android.util.Log.w("twig", "playback: resume position fetch failed: ${e.message}")
                -1L
            }
            runOnUiThread {
                if (resumeReady) return@runOnUiThread // the timeout path already let it through, don't change the position
                resumeFrom = if (pos > 0) pos else PlaybackStore.positionFor(this, file)
                releaseResumeGate()
            }
        }.apply { isDaemon = true; name = "twig-resume" }.start()
        handler.postDelayed({
            if (!resumeReady) {
                android.util.Log.w("twig", "playback: resume position timed out, starting from 0")
                releaseResumeGate()
            }
        }, RESUME_TIMEOUT_MS)
    }

    private fun releaseResumeGate() {
        resumeReady = true
        val h = pendingHolder ?: return
        pendingHolder = null
        startPlayback(h)
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

    override fun onResume() {
        super.onResume()
        wakeScreen()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.takeIf { prepared && it.isPlaying }?.pause()
        // In the background the window isn't visible, so the timers mean nothing; onResume decides again
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(screenOffRunnable)
    }

    /**
     * Persist playback progress once. SharedPreferences reads are in-memory and writes go through
     * apply() (asynchronous to disk), so calling on the main thread is fine; the "should we save
     * / delete" decisions are all in [PlaybackStore.save].
     */
    private fun saveProgress(state: PlayState = PlayState.PROGRESS) {
        if (!isVideo || !prepared) return
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        lastSaveAt = SystemClock.elapsedRealtime()
        val r = remote
        if (r != null) {
            // Progress is the server's job: reporting goes on a background thread (see RemoteProgress),
            // the local copy is no longer written
            if (!startedReported) { startedReported = true; r.report(p.currentPosition, dur, PlayState.START) }
            r.report(p.currentPosition, dur, state)
        } else {
            PlaybackStore.save(this, file, p.currentPosition, dur)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Report STOP on this exit: the server uses this to end the "now playing" session,
        // otherwise the session hangs
        saveProgress(PlayState.STOP)
        remote?.shutdown()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(screenOffRunnable)
        player?.release() // asynchronous teardown, doesn't block the main thread
        player = null
        val src = fsSrc; fsSrc = null
        // SMB-dedicated connection cleanup goes on the smb-io thread, in the background to avoid waiting
        if (src != null) Thread { runCatching { src.close() } }.apply { isDaemon = true }.start()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val SCALE_BEST_FIT = 0 // original aspect ratio, shown in full
        const val SCALE_CROP = 1     // preserve aspect, crop to fill
        const val SCALE_FILL = 2     // stretch to fill
        /** Upper bound for waiting on the remote resume position: beyond this, start from the beginning — don't let one network hiccup become "stuck on the loading spinner forever". */
        private const val RESUME_TIMEOUT_MS = 2500L
        /** Brightness a paused screen falls back to: dark enough to save power and to stop being a
         *  lamp in a dark room, bright enough that the paused frame is still recognisable. */
        private const val DIM_BRIGHTNESS = 0.05f
        /** After this long paused, stop holding the screen awake at all and let the system turn it
         *  off — dimmed-but-on is for "I'll be right back", not for a video paused and forgotten. */
        private const val SCREEN_OFF_DELAY = 10 * 60_000L
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
