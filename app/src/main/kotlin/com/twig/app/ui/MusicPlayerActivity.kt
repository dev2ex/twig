package com.twig.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityMusicPlayerBinding
import com.twig.app.databinding.ItemPlaylistRowBinding
import com.twig.core.XFile
import java.util.concurrent.Executors

/** Main music player UI (UI shell; state all in [MusicEngine]/[MusicService]; onDestroy does not release the player). */
@UnstableApi
class MusicPlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityMusicPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-music-ui").apply { isDaemon = true } }
    private var showingLyrics = false
    private var artForId: String? = null
    private var lyricsForId: String? = null
    private var waveForId: String? = null
    private var seekPreview: Long? = null
    private var accentColor = 0 // accent color extracted from the cover; 0 = use theme default accent
    private var bgTone = 0 // average color of the blurred background (the color text actually sits on); 0 = cover not yet available, use fallbackBg()
    private var tint = MusicTint.of(Color.parseColor("#1A1A1A")) // foreground layer color, derived from bgTone
    private lateinit var plDrawer: PlaylistDrawer
    private val finisher: () -> Unit = { finish() }

    private val ticker = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                val pos = MusicEngine.positionMs()
                val dur = MusicEngine.durationMs()
                if (seekPreview == null) {
                    b.tvPos.text = fmt(pos)
                    b.wave.setProgress(pos, dur)
                }
                b.tvDur.text = fmt(dur)
                b.lyrics.setPositionMs(pos)
                handler.postDelayed(this, 300)
            }
        }
    }

    private val listener = object : MusicEngine.Listener {
        override fun onTrackChanged(index: Int, track: PlaylistTrack?) { bindTrack() }
        override fun onPlayStateChanged(playing: Boolean) { updatePlayButton() }
        override fun onModeChanged() { updateModeButtons() }
        override fun onFavChanged() { updateFavButton(); plDrawer.reload() } // Heart icon tapped from the notification.
        override fun onQueueChanged() { bindTrack() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ The view and the lateinit fields are **all built right here**, not deferred to
        // after unlock: while the unlock dialog is up, the Activity still goes through onResume;
        // at that point plDrawer hasn't been assigned and it crashes on the spot (always when
        // entering from the notification). Same pitfall as the PaneFragment.adapter entry —
        // **any time initialization is moved after an async callback, lifecycle callbacks fire first**.
        b = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupImmersive()
        b.bgBlur.setBackgroundColor(fallbackBg()) // paint a solid color first to avoid a black flash before the blur appears
        requestNotifPermission()
        wireViews()

        plDrawer = PlaylistDrawer(this, b.drawer, b.drawerList, b.drawerExit)
        MusicUi.register(finisher)

        // While locked, music **keeps playing normally** (that's what distinguishes "lock" from
        // "exit"); the buttons on the notification also keep working — but **entering this screen
        // requires the master password**: playlists, which server each track came from, and
        // "locate in file manager" all live behind it.
        SecurityUi.gate(this) { restoreLastQueue() }
    }

    /**
     * Loads "the queue that was actually being played last time" (no auto-play): could be NOW,
     * could be some named playlist. Locates by [Prefs.lastQueueId], not by blindly taking NOW —
     * otherwise a cold-start while a named list was being played jumps to the wrong list.
     */
    private fun restoreLastQueue() {
        if (MusicEngine.hasQueue()) return
        val lastId = Prefs.lastQueueId(this)
        val pl = lastId?.let { PlaylistStore.get(this, it) }
            ?: PlaylistStore.get(this, Playlist.NOW)
            ?: PlaylistStore.all(this).firstOrNull { it.tracks.isNotEmpty() }
        if (pl != null && pl.tracks.isNotEmpty()) {
            MusicEngine.play(this, pl, pl.lastIndex, pl.lastPosMs, autoPlay = false)
        }
    }

    /** Portrait ↔ landscape switch: the Activity declares configChanges in the manifest, so the
     *  system does not recreate it — must manually re-inflate the layout for the new orientation
     *  (landscape layout-land has two panes) and rebind everything. MusicEngine/playback state both
     *  live in the singleton, so re-inflating views doesn't disturb playback. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        b = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupImmersive()
        b.bgBlur.setBackgroundColor(fallbackBg())
        wireViews()
        plDrawer = PlaylistDrawer(this, b.drawer, b.drawerList, b.drawerExit)
        plDrawer.reload()
        if (!isLandscape()) {
            b.cover.visibility = if (showingLyrics) View.GONE else View.VISIBLE
            b.lyrics.visibility = if (showingLyrics) View.VISIBLE else View.GONE
        }
        // The new View instances have drawn nothing yet; force-reapply cover/lyrics/waveform
        // (a cache hit renders immediately, no recompute)
        artForId = null; lyricsForId = null; waveForId = null
        bindTrack()
        updatePlayButton()
        updateModeButtons()
    }

    /** Button/gesture listeners — must be wired both in onCreate and after every re-inflate on orientation switch. */
    private fun wireViews() {
        b.btnDrawer.setOnClickListener { b.drawer.openDrawer(GravityCompat.START) }
        b.btnMore.setOnClickListener { overflowMenu() }
        b.btnPlay.setOnClickListener { MusicEngine.togglePlay() }
        b.btnNext.setOnClickListener { MusicEngine.next() }
        b.btnPrev.setOnClickListener { MusicEngine.prev() }
        b.btnShuffle.setOnClickListener { MusicEngine.toggleShuffle() }
        b.btnRepeat.setOnClickListener { MusicEngine.cycleRepeat() }
        b.btnQueue.setOnClickListener { MusicEngine.queueId.takeIf { it.isNotEmpty() }?.let { PlaylistActivity.start(this, it) } }
        b.btnFav.setOnClickListener { toggleFav() }
        b.lyrics.onTap = { toggleView() }
        b.lyrics.onSeekTo = { MusicEngine.seekTo(it) }
        b.lyrics.onSwipe = { next -> if (next) MusicEngine.next() else MusicEngine.prev() }
        // Tapping the track title/artist/album row also toggles cover ↔ lyrics (expands the tap
        // target; in landscape the two panes are always visible so toggleView itself no-ops)
        val toggle = View.OnClickListener { toggleView() }
        b.trackTitle.setOnClickListener(toggle)
        b.trackArtist.setOnClickListener(toggle)
        b.trackAlbum.setOnClickListener(toggle)
        b.artistRow.setOnClickListener(toggle)
        setupCenterGestures()

        b.wave.onSeek = { MusicEngine.seekTo(it) }
        b.wave.onPreview = { p -> seekPreview = p; if (p != null) b.tvPos.text = fmt(p) }

        applyColors() // newly inflated views carry the pure-white placeholder color from XML; immediately swap to the bg-derived set
    }

    /** Immersive: content extends below the status bar (blurred background fills it), status bar is
     *  transparent with light icons; top bar / controls / drawer inset around system bars so they
     *  aren't covered by the status/navigation bar. */
    private fun setupImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        applySystemBarIcons()
        applyFullscreen()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            b.topBar.setPadding(b.topBar.paddingLeft, bars.top, b.topBar.paddingRight, 0)
            b.controls.setPadding(
                b.controls.paddingLeft, b.controls.paddingTop, b.controls.paddingRight,
                bars.bottom + dp(16),
            )
            insets
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.drawerContent) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
    }

    /** Light/dark of status/nav bar icons follows the current background: now that the blur is no
     *  longer darkened, white icons disappear on light covers. */
    private fun applySystemBarIcons() {
        val light = MusicTint.isLight(if (bgTone != 0) bgTone else fallbackBg())
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** Hides/shows the status bar following the main "fullscreen" preference (matches MainActivity.applyFullscreen). */
    private fun applyFullscreen() {
        val controller =
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (Prefs.fullscreen(this)) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen() // the system may restore the status bar after a switch; re-apply
    }

    /** Swipe left/right on the cover to skip tracks; single-tap toggles cover ↔ lyrics. */
    private fun setupCenterGestures() {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true // must consume DOWN for subsequent MOVE/UP/fling to arrive
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { toggleView(); return true }
            override fun onFling(
                e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float,
            ): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (kotlin.math.abs(dx) > kotlin.math.abs(e2.y - e1.y) && kotlin.math.abs(dx) > dp(48)) {
                    if (dx < 0) MusicEngine.next() else MusicEngine.prev()
                    return true
                }
                return false
            }
        })
        // When lyrics are showing, hand them to LyricsView (scroll/tap); when cover is showing, gestures apply
        b.centerArea.setOnTouchListener { _, ev -> if (showingLyrics) false else detector.onTouchEvent(ev) }
    }

    override fun onResume() {
        super.onResume()
        MusicEngine.addListener(listener)
        plDrawer.reload()
        bindTrack()
        updatePlayButton()
        updateModeButtons()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        MusicEngine.removeListener(listener)
        handler.removeCallbacks(ticker)
        MusicEngine.saveResume()
    }

    // ---- Bind the current track ----

    private fun bindTrack() {
        val track = MusicEngine.currentTrack()
        val file = MusicEngine.currentFile()
        b.trackTitle.text = track?.title?.ifEmpty { null } ?: track?.name ?: ""
        b.trackArtist.text = track?.artist ?: ""
        b.trackAlbum.text = track?.album ?: ""
        // Album name is backfilled from metadata on a background thread, arriving after the UI.
        // Previously an empty album was GONE, so at the moment of backfill the whole title block
        // suddenly grew one line and the waveform/controls below jumped down; INVISIBLE keeps the
        // slot reserved and just hides the text.
        b.trackAlbum.visibility = if (track?.album.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
        val count = MusicEngine.trackCount()
        b.tvIndex.text = if (count > 0) "${MusicEngine.currentIndex() + 1} / $count" else ""
        updateFavButton()
        if (track == null || file == null) {
            showCoverPlaceholder()
            b.bgBlur.setImageDrawable(null); b.bgBlur.setBackgroundColor(fallbackBg())
            accentColor = 0; bgTone = 0; applyColors()
            b.wave.setWaveform(null)
            b.lyrics.setLyrics(null)
            return
        }
        loadArt(track.id, file)
        loadLyrics(track.id, file)
        loadWaveform(track.id, file)
    }

    /** Cover + blur + accent all go through the [MusicArt] cache: a hit renders the image
     *  instantly (no more "black flash"), otherwise it's computed once in the background and
     *  reused between the player page and the list page. */
    private fun loadArt(id: String, file: XFile) {
        if (artForId == id) return
        artForId = id
        val dark = isDark()
        val snap = MusicArt.snapshot(id, dark)
        if (snap != null) {
            applyArt(snap) // cache hit: apply immediately
        } else {
            // Switched to an uncached new track: clear the previous cover first so the old one
            // doesn't linger while the new image loads
            showCoverPlaceholder()
        }
        MusicArt.load(this, id, file, dark) { s -> if (artForId == id && !isFinishing) applyArt(s) }
    }

    private fun applyArt(snap: MusicArt.Snapshot) {
        if (snap.cover != null) setRoundedCover(snap.cover) else showCoverPlaceholder()
        if (snap.blur != null) { b.bgBlur.setBackgroundColor(0); b.bgBlur.setImageBitmap(snap.blur) }
        else { b.bgBlur.setImageDrawable(null); b.bgBlur.setBackgroundColor(fallbackBg()) }
        accentColor = snap.accent
        bgTone = if (snap.blur != null) snap.bgTone else 0
        applyColors()
    }

    private fun showCoverPlaceholder() {
        b.cover.setImageResource(R.drawable.ic_audio)
    }

    /** fitCenter leaves a transparent letterbox in a non-matching-aspect container, and clipToOutline's
     *  rounded corner only clips to the View's full rectangle — clipping on transparent padding
     *  is invisible to the eye. Here the rounded corners are drawn only on the rectangle the image
     *  actually lands in (the centered position after fitCenter scaling), not on the whole View
     *  bounds — the letterbox stays transparent, the image's own four corners actually show as
     *  rounded, and no stretching/cropping of the original image's aspect ratio is needed. */
    private fun setRoundedCover(src: Bitmap) {
        val view = b.cover
        if (view.width <= 0 || view.height <= 0) {
            view.setImageBitmap(src) // display as-is before onMeasure; redraw the rounded version after layout
            view.post { if (!isFinishing) setRoundedCover(src) }
            return
        }
        val outW = view.width; val outH = view.height
        val scale = minOf(outW / src.width.toFloat(), outH / src.height.toFloat())
        val drawW = src.width * scale; val drawH = src.height * scale
        val left = (outW - drawW) / 2f; val top = (outH - drawH) / 2f
        val dest = RectF(left, top, left + drawW, top + drawH)
        val radius = resources.displayMetrics.density * 5f

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path().apply { addRoundRect(dest, radius, radius, Path.Direction.CW) }
        canvas.clipPath(path)
        val matrix = Matrix().apply {
            setRectToRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), dest, Matrix.ScaleToFit.FILL)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        view.setImageBitmap(out)
    }

    /**
     * Foreground colors follow the cover: text/icons are no longer hard-coded pure white but are
     * derived by [MusicTint] from the blurred background's average color — same color family,
     * contrast decreasing by layer (track title 7:1 / artist 4.6:1 / album·index 3.1:1), avoiding
     * the harsh "two layers stuck together" feel of pure white on a dark background at 13:1.
     * Accent color (play button, played waveform, current lyrics, active shuffle/repeat/heart)
     * still comes from the cover's accent color.
     */
    private fun applyColors() {
        tint = MusicTint.of(if (bgTone != 0) bgTone else fallbackBg())
        b.trackTitle.setTextColor(tint.primary)
        b.trackArtist.setTextColor(tint.secondary)
        b.trackAlbum.setTextColor(tint.tertiary)
        b.tvPos.setTextColor(tint.secondary)
        b.tvDur.setTextColor(tint.secondary)
        b.tvIndex.setTextColor(tint.tertiary)
        b.btnPrev.setColorFilter(tint.primary)
        b.btnNext.setColorFilter(tint.primary)
        b.btnDrawer.setColorFilter(tint.secondary)
        b.btnMore.setColorFilter(tint.secondary)
        b.btnQueue.setColorFilter(tint.secondary)
        b.wave.setAccent(effAccent())
        b.wave.setUnplayedColor(tint.tertiary)
        b.lyrics.setAccent(effAccent())
        b.lyrics.setTint(tint.secondary, tint.faint)
        applySystemBarIcons()
        updatePlayButton()
        updateModeButtons()
        updateFavButton()
    }

    /** Cover accent color (or theme default); also ensures it stays visible on the current
     *  background — the background is itself a blur of the same cover, so the accent may share
     *  hue and brightness with the background, and without correction the play button / played
     *  waveform would blend into the background. */
    private fun effAccent(): Int {
        val raw = if (accentColor != 0) accentColor
        else androidx.core.content.ContextCompat.getColor(this, R.color.accent)
        return MusicTint.readable(raw, if (bgTone != 0) bgTone else fallbackBg())
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun fallbackBg(): Int = if (isDark()) Color.parseColor("#1A1A1A") else Color.parseColor("#37474F")

    private fun loadLyrics(id: String, file: XFile) {
        if (lyricsForId == id) return
        lyricsForId = id
        b.lyrics.setLyrics(null)
        io.execute {
            val lrc = runCatching { LyricsLoader.load(file) }.getOrNull()
            runOnUiThread { if (lyricsForId == id && !isFinishing) b.lyrics.setLyrics(lrc) }
        }
    }

    private fun loadWaveform(id: String, file: XFile) {
        if (waveForId == id) return
        waveForId = id
        b.wave.setWaveform(null)
        Waveform.request(this, file) { if (waveForId == id && !isFinishing) b.wave.setWaveform(it) }
    }

    private fun toggleView() {
        if (isLandscape()) return // landscape cover+lyrics two panes always visible, no "toggle" to speak of
        showingLyrics = !showingLyrics
        b.cover.visibility = if (showingLyrics) View.GONE else View.VISIBLE
        b.lyrics.visibility = if (showingLyrics) View.VISIBLE else View.GONE
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun updatePlayButton() {
        b.btnPlay.setImageResource(if (MusicEngine.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnPlay.setColorFilter(effAccent())
    }

    private fun updateModeButtons() {
        val accent = effAccent()
        b.btnShuffle.setColorFilter(if (MusicEngine.shuffleEnabled()) accent else tint.secondary)
        when (MusicEngine.repeatMode()) {
            Player.REPEAT_MODE_ONE -> { b.btnRepeat.setImageResource(R.drawable.ic_repeat_one); b.btnRepeat.setColorFilter(accent) }
            Player.REPEAT_MODE_ALL -> { b.btnRepeat.setImageResource(R.drawable.ic_repeat); b.btnRepeat.setColorFilter(accent) }
            else -> { b.btnRepeat.setImageResource(R.drawable.ic_repeat); b.btnRepeat.setColorFilter(tint.secondary) }
        }
    }

    private fun updateFavButton() {
        val track = MusicEngine.currentTrack()
        val fav = track != null && PlaylistStore.isFav(this, track.id)
        b.btnFav.setImageResource(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart)
        b.btnFav.setColorFilter(if (fav) effAccent() else tint.secondary)
    }

    private fun toggleFav() {
        val track = MusicEngine.currentTrack() ?: return
        PlaylistStore.toggleFav(this, track)
        updateFavButton()
        plDrawer.reload()
        MusicEngine.notifyFavChanged() // notification heart switches to filled too
    }

    // ---- overflow menu ----

    private fun overflowMenu() {
        val track = MusicEngine.currentTrack() ?: return
        val file = MusicEngine.currentFile() ?: return
        // Pair labels with actions; do not use fixed indices — "go to containing folder" is omitted
        // for sources like document trees (see MusicDialogs.canLocate); once any item can be
        // missing, a hardcoded when(which) goes out of alignment
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += getString(R.string.music_info) to { MusicDialogs.showInfo(this, file) }
        items += getString(R.string.music_send_to) to { MusicDialogs.sendToPlaylist(this, listOf(track)) }
        items += getString(R.string.music_share) to { MusicDialogs.share(this, file); Unit }
        if (MusicDialogs.canLocate(file)) {
            items += getString(R.string.music_locate) to { MusicDialogs.locateInFileManager(this, file) }
        }
        val favLabel = if (PlaylistStore.isFav(this, track.id)) {
            getString(R.string.music_remove_fav)
        } else {
            getString(R.string.music_add_fav)
        }
        items += favLabel to { toggleFav() }
        items += getString(R.string.music_remove_from) to {
            val id = MusicEngine.queueId
            PlaylistStore.removeTrack(this, id, track.id)
            MusicEngine.removeFromQueue(track.id)
        }
        AlertDialog.Builder(this)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        MusicUi.unregister(finisher)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
