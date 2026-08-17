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

/** 音乐播放主界面(UI 壳,状态全在 [MusicEngine]/[MusicService];onDestroy 不 release 播放器)。 */
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
    private var accentColor = 0 // 从封面提取的主色调;0 = 用主题默认强调色
    private var bgTone = 0 // 毛玻璃背景的平均色(文字压在上面的那个颜色);0 = 还没出封面,用 fallbackBg()
    private var tint = MusicTint.of(Color.parseColor("#1A1A1A")) // 前景层级色,随 bgTone 反推
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
        override fun onFavChanged() { updateFavButton(); plDrawer.reload() } // 通知栏点了心形
        override fun onQueueChanged() { bindTrack() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupImmersive()
        b.bgBlur.setBackgroundColor(fallbackBg()) // 先铺纯色,避免进页面黑一下再出毛玻璃
        requestNotifPermission()
        wireViews()

        plDrawer = PlaylistDrawer(this, b.drawer, b.drawerList, b.drawerExit)
        MusicUi.register(finisher)

        // 无队列时载入"上次真正在放的那个队列"(不自动播):可能是 NOW,也可能是某个命名播放列表
        // ——按 Prefs.lastQueueId 定位,而不是无脑固定取 NOW,否则上次在播命名列表时冷启动会跳错列表。
        if (!MusicEngine.hasQueue()) {
            val lastId = Prefs.lastQueueId(this)
            val pl = lastId?.let { PlaylistStore.get(this, it) }
                ?: PlaylistStore.get(this, Playlist.NOW)
                ?: PlaylistStore.all(this).firstOrNull { it.tracks.isNotEmpty() }
            if (pl != null && pl.tracks.isNotEmpty()) {
                MusicEngine.play(this, pl, pl.lastIndex, pl.lastPosMs, autoPlay = false)
            }
        }
    }

    /** 竖屏↔横屏切换:Activity 在 manifest 里声明了 configChanges,系统不会重建它,
     *  要手动重新 inflate 对应方向的布局(横屏 layout-land 双栏)并重新走一遍绑定。
     *  MusicEngine/播放状态都在单例里,重新 inflate 视图不影响播放。 */
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
        // 新的 View 实例什么都还没画过,强制重新应用一遍封面/歌词/波形(缓存命中会立即出图,不会重新算)
        artForId = null; lyricsForId = null; waveForId = null
        bindTrack()
        updatePlayButton()
        updateModeButtons()
    }

    /** 按钮/手势监听——onCreate 与横竖屏切换重新 inflate 后都要走一遍。 */
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
        // 下方 曲名/艺术家/专辑 区域点击也切换封面↔歌词(扩大切换热区;横屏双栏常显,toggleView 本身会忽略)
        val toggle = View.OnClickListener { toggleView() }
        b.trackTitle.setOnClickListener(toggle)
        b.trackArtist.setOnClickListener(toggle)
        b.trackAlbum.setOnClickListener(toggle)
        b.artistRow.setOnClickListener(toggle)
        setupCenterGestures()

        b.wave.onSeek = { MusicEngine.seekTo(it) }
        b.wave.onPreview = { p -> seekPreview = p; if (p != null) b.tvPos.text = fmt(p) }

        applyColors() // 新 inflate 的视图带的是 XML 里的纯白占位色,立刻换成按背景反推的那套
    }

    /** 沉浸式:内容延伸到状态栏下(毛玻璃背景铺满),状态栏透明、图标用亮色;顶栏/控制区/抽屉
     *  按系统栏 inset 让开,避免被状态栏/导航栏遮挡。 */
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

    /** 状态栏/导航栏图标的明暗跟随当前背景:毛玻璃不再压暗后,亮色封面上白图标会看不见。 */
    private fun applySystemBarIcons() {
        val light = MusicTint.isLight(if (bgTone != 0) bgTone else fallbackBg())
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** 跟随主界面「全屏」偏好隐藏/显示状态栏(与 MainActivity.applyFullscreen 一致)。 */
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
        if (hasFocus) applyFullscreen() // 系统可能在切换后恢复状态栏,重新应用
    }

    /** 封面区左右滑切歌、单击封面↔歌词切换。 */
    private fun setupCenterGestures() {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true // 必须消费 DOWN,后续 MOVE/UP/fling 才会来
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
        // 歌词显示时交给 LyricsView 自己处理(滚动/点击);封面显示时走手势
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

    // ---- 绑定当前曲目 ----

    private fun bindTrack() {
        val track = MusicEngine.currentTrack()
        val file = MusicEngine.currentFile()
        b.trackTitle.text = track?.title?.ifEmpty { null } ?: track?.name ?: ""
        b.trackArtist.text = track?.artist ?: ""
        b.trackAlbum.text = track?.album ?: ""
        // 专辑名是元数据后台补全的,来得比界面晚。以前空专辑用 GONE,补全那一刻整块标题区
        // 突然长高一行、下面的波形/操作栏跟着往下跳;改 INVISIBLE 一直占位,只是不显示文字。
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

    /** 封面 + 毛玻璃 + 主色调统一走 [MusicArt] 缓存:缓存命中瞬间上图(不再"黑一下"),
     *  否则后台算一次,播放页/列表页共享复用。 */
    private fun loadArt(id: String, file: XFile) {
        if (artForId == id) return
        artForId = id
        val dark = isDark()
        val snap = MusicArt.snapshot(id, dark)
        if (snap != null) {
            applyArt(snap) // 命中缓存:立即
        } else {
            // 切到未缓存的新曲:先清掉上一首封面,避免加载新图前残留旧封面
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

    /** fitCenter 会在非同比例容器里留出透明 letterbox,clipToOutline 圆角只能裁到 View
     *  整个矩形边界,裁在透明留白上肉眼看不见。这里只把圆角画在图片实际落地的那个矩形
     *  (fitCenter 缩放后居中的位置)上,而不是整个 View 边界——letterbox 部分仍保持
     *  透明,图片本身的四角才会真正显示圆角,且不需要拉伸/裁切原图比例。 */
    private fun setRoundedCover(src: Bitmap) {
        val view = b.cover
        if (view.width <= 0 || view.height <= 0) {
            view.setImageBitmap(src) // onMeasure 前先原样显示,布局完成后重画一次圆角版本
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
     * 前景配色随封面变:文字/图标不再是硬编码纯白,而是由 [MusicTint] 从毛玻璃背景的
     * 平均色反推——同色系、对比度按层级递减(曲名 7:1 / 艺术家 4.6:1 / 专辑·序号 3.1:1),
     * 避免纯白压深色背景那种 13:1 的"两层贴在一起"的生硬感。
     * 强调色(播放键、已播波形、当前歌词、激活的随机/循环/心形)仍来自封面主色调。
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

    /** 封面主色调(无则主题默认);顺带保证它压在当前背景上还看得见——背景本来就是同一张
     *  封面糊出来的,主色调可能跟背景同色同亮度,不校正的话播放键/已播波形会糊进背景。 */
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
        if (isLandscape()) return // 横屏封面+歌词双栏常显,没有"切换"这一说
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
        MusicEngine.notifyFavChanged() // 通知栏心形跟着换实心
    }

    // ---- overflow 菜单 ----

    private fun overflowMenu() {
        val track = MusicEngine.currentTrack() ?: return
        val file = MusicEngine.currentFile() ?: return
        val items = arrayOf(
            getString(R.string.music_info),
            getString(R.string.music_send_to),
            getString(R.string.music_share),
            getString(R.string.music_locate),
            if (PlaylistStore.isFav(this, track.id)) getString(R.string.music_remove_fav) else getString(R.string.music_add_fav),
            getString(R.string.music_remove_from),
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> MusicDialogs.showInfo(this, file)
                    1 -> MusicDialogs.sendToPlaylist(this, listOf(track))
                    2 -> MusicDialogs.share(this, file)
                    3 -> MusicDialogs.locateInFileManager(this, file)
                    4 -> toggleFav()
                    5 -> {
                        val id = MusicEngine.queueId
                        PlaylistStore.removeTrack(this, id, track.id)
                        MusicEngine.removeFromQueue(track.id)
                    }
                }
            }
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
