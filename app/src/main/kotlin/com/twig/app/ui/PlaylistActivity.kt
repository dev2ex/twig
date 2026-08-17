package com.twig.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.Format
import com.twig.app.PlaylistTrack
import com.twig.app.R
import com.twig.app.StreamProvider
import com.twig.app.databinding.ActivityPlaylistBinding
import com.twig.app.databinding.ItemTrackBinding
import com.twig.core.XFile
import java.util.concurrent.Executors

/** 播放列表页:列出曲目(序号/封面/标题/艺术家/时长),点行播放,三竖点菜单管理。 */
@UnstableApi
class PlaylistActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlaylistBinding
    private lateinit var playlistId: String
    private var playlist: Playlist? = null
    private val files = HashMap<String, XFile>() // trackId -> 已解析 XFile
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-pl").apply { isDaemon = true } }
    private lateinit var adapter: TrackAdapter
    private lateinit var plDrawer: PlaylistDrawer
    private val finisher: () -> Unit = { finish() }
    private var blurForId: String? = null
    private var rowAccent = 0 // 从封面提取的主色调,给正在播放的行上色(0 = 用默认 accent)
    private var bgTone = 0 // 文字实际压在上面的背景色(毛玻璃平均色 + scrim);0 = 还没出封面
    private var tint = MusicTint.of(android.graphics.Color.parseColor("#1A1A1A")) // 前景层级色

    /** 列表页开着时队列自动跳下一首:高亮/毛玻璃/滚动位置都要跟着走(以前只在 onResume 刷一次,
     *  停在页面上听完一首,▶ 还留在上一曲)。只在可见期间注册。 */
    private val musicListener = object : MusicEngine.Listener {
        override fun onTrackChanged(index: Int, track: PlaylistTrack?) {
            adapter.syncPlaying()
            loadBlurBg()
            followCurrent()
        }
        override fun onQueueChanged() { adapter.syncPlaying() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(b.root)
        playlistId = intent.getStringExtra(EXTRA_ID) ?: return finish()
        setupImmersive()
        b.bgBlur.setBackgroundColor(fallbackBg()) // 先铺纯色,避免进页面黑一下
        b.toolbar.setNavigationOnClickListener { b.drawer.openDrawer(androidx.core.view.GravityCompat.START) }
        plDrawer = PlaylistDrawer(this, b.drawer, b.drawerList, b.drawerExit)
        MusicUi.register(finisher)
        adapter = TrackAdapter()
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        // 元数据/封面/强调色是后台陆续回填的,会一路触发 notifyItemChanged。默认 ItemAnimator
        // 对每次 notifyItemChanged 都做淡出淡入的 change 动画——这才是"列表更新时跳闪一下"的
        // 真正根源。之前只置 supportsChangeAnimations=false 没根治(仍会走动画器的 re-layout),
        // 直接把动画器整个去掉,所有 item 变更瞬时重绑、不闪;本列表也不需要增删动画。
        b.list.itemAnimator = null
        applyTint() // 封面还没出来:先按 fallbackBg 反推一套,别让 XML 里的纯白占位色露出来
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        MusicUi.unregister(finisher)
    }

    /** 沉浸式:内容延伸到状态栏下,毛玻璃背景铺满;工具栏/列表按系统栏 inset 让开。 */
    private fun setupImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.content) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            b.content.setPadding(0, bars.top, 0, 0)
            b.list.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.drawerContent) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    /** 用当前播放曲(若正是本列表)或首个可解析曲目的封面做毛玻璃背景。
     *  正在播放本列表时,直接复用 [MusicArt] 已算好的封面/毛玻璃/主色——秒出,不再"黑一下"。 */
    private fun loadBlurBg() {
        val isCurrent = MusicEngine.queueId == playlistId && MusicEngine.hasQueue()
        val track = if (isCurrent) MusicEngine.currentTrack() else playlist?.tracks?.firstOrNull()
        val id = track ?: return
        if (blurForId == id.id) return
        blurForId = id.id
        val dark = isDark()
        if (isCurrent) {
            MusicArt.snapshot(id.id, dark)?.let { applyBg(id.id, it.blur, it.accent) } // 命中:立即
            io.execute {
                val f = synchronized(files) { files[id.id] } ?: MusicEngine.resolveFile(applicationContext, id) ?: return@execute
                MusicArt.load(this, id.id, f, dark) { if (!isFinishing) applyBg(id.id, it.blur, it.accent) }
            }
            return
        }
        // 非当前队列:本地算一次,不污染 MusicArt 的"正在播放"缓存槽
        io.execute {
            val f = synchronized(files) { files[id.id] } ?: MusicEngine.resolveFile(applicationContext, id) ?: return@execute
            val cover = runCatching { Thumbs.audioCover(f, 512) }.getOrNull() ?: return@execute
            val bg = runCatching { Blur.background(cover, 256, dark) }.getOrNull()
            val accent = runCatching { MusicArt.extractAccent(cover) }.getOrDefault(0)
            runOnUiThread { applyBg(id.id, bg, accent) }
        }
    }

    private fun applyBg(id: String, bg: android.graphics.Bitmap?, accent: Int) {
        if (blurForId != id || isFinishing) return
        rowAccent = accent
        if (bg != null) {
            b.bgBlur.setImageBitmap(bg)
            b.scrim.visibility = if (android.graphics.Color.alpha(SCRIM) > 0) View.VISIBLE else View.GONE
            // 毛玻璃上还可能压着布局里的 scrim,文字真正压在的是"平均色 + 这层遮罩"的结果
            bgTone = MusicTint.blend(MusicTint.averageColor(bg), SCRIM)
        } else {
            bgTone = 0
        }
        applyTint()
    }

    /**
     * 前景配色随封面变(与播放页同一套 [MusicTint]):标题/曲名/副标题/时长/序号不再是
     * 硬编码纯白,而是由背景色反推的同色系、对比度分级色。
     *
     * 刷新只能走 **payload 局部重绑**:以前这里(为一处颜色变化)用 notifyDataSetChanged()
     * 整表重绑,它常在 MusicArt.load 异步回调里触发,正好落在 scrollToCurrent() 定位还没
     * settle 的窗口,整表重新布局会把列表重新锚定一次,这就是"打开正在更新的列表定位当前
     * 曲目时跳闪一下"的根源;而且完整 rebind 会把封面复位成占位图再异步填回,又闪一次。
     */
    private fun applyTint() {
        tint = MusicTint.of(if (bgTone != 0) bgTone else fallbackBg())
        b.plTitle.setTextColor(tint.primary)
        b.plCount.setTextColor(tint.secondary)
        b.empty.setTextColor(tint.secondary)
        b.toolbar.navigationIcon = b.toolbar.navigationIcon?.mutate()?.apply { setTint(tint.secondary) }
        // 状态栏/导航栏图标明暗跟着背景走:毛玻璃不再压暗后,亮色封面上白图标会看不见
        val light = MusicTint.isLight(if (bgTone != 0) bgTone else fallbackBg())
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_COLORS)
    }

    /** 正在播放那一行的强调色;顺带保证它压在当前背景上还看得见(背景就是这张封面糊出来的)。 */
    private fun effAccent(): Int {
        val raw = if (rowAccent != 0) rowAccent else ContextCompat.getColor(this, R.color.accent)
        return MusicTint.readable(raw, if (bgTone != 0) bgTone else fallbackBg())
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun fallbackBg(): Int =
        if (isDark()) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.parseColor("#37474F")

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged() // 刷新当前播放高亮
        adapter.syncPlaying()          // 同步高亮行下标(供后续增量刷新)
        plDrawer.reload()
        scrollToCurrent()
        MusicEngine.addListener(musicListener)
    }

    override fun onPause() {
        super.onPause()
        MusicEngine.removeListener(musicListener)
    }

    /** 进入/返回列表页时,自动滚动到正在播放的那一曲(仅当本列表就是当前队列)。 */
    private fun scrollToCurrent() {
        val pos = currentPos() ?: return
        b.list.post {
            (b.list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pos, b.list.height / 3)
        }
    }

    /** 队列跳到下一首时跟随:新行已经在屏幕上就不动(别把用户正在看的位置拽走),
     *  滚出可视范围了才平滑滚过去。 */
    private fun followCurrent() {
        val pos = currentPos() ?: return
        val lm = b.list.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && pos in first..last) return
        b.list.smoothScrollToPosition(pos)
    }

    /** 正在播放的那一曲在本列表中的下标;本列表不是当前队列则 null。 */
    private fun currentPos(): Int? {
        if (MusicEngine.queueId != playlistId || !MusicEngine.hasQueue()) return null
        val id = MusicEngine.currentTrack()?.id ?: return null
        return playlist?.tracks?.indexOfFirst { it.id == id }?.takeIf { it >= 0 }
    }

    private fun reload() {
        val pl = PlaylistStore.get(this, playlistId) ?: return finish()
        playlist = pl
        b.plTitle.text = pl.name
        b.plCount.text = getString(R.string.music_track_count, pl.tracks.size)
        b.empty.visibility = if (pl.tracks.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(pl.tracks)
        resolveFiles(pl.tracks)
        loadBlurBg()
    }

    /** 后台解析各曲目的 XFile(conn 曲目建连接),用于封面缩略图与元数据补全。 */
    private fun resolveFiles(tracks: List<PlaylistTrack>) {
        io.execute {
            for (t in tracks) {
                if (files.containsKey(t.id)) continue
                val f = MusicEngine.resolveFile(applicationContext, t) ?: continue
                synchronized(files) { files[t.id] = f }
                runOnUiThread { adapter.notifyItemChangedById(t.id) }
                val needMeta = t.title.isEmpty() || t.durationMs <= 0L ||
                    (android.os.Build.VERSION.SDK_INT >= 31 && t.sampleRate <= 0)
                if (needMeta) {
                    val filled = MusicEngine.fetchMeta(applicationContext, f, t) ?: continue
                    PlaylistStore.updateTrackMeta(applicationContext, playlistId, filled)
                    runOnUiThread {
                        playlist = PlaylistStore.get(this, playlistId)
                        adapter.updateTrack(filled)
                    }
                }
            }
        }
    }

    private fun play(index: Int) {
        val pl = playlist ?: return
        MusicEngine.playFrom(this, pl, index)
        startActivity(Intent(this, MusicPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun trackMenu(track: PlaylistTrack) {
        val items = arrayOf(
            getString(R.string.music_play),
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
                    0 -> playlist?.tracks?.indexOfFirst { it.id == track.id }?.takeIf { it >= 0 }?.let { play(it) }
                    1 -> showInfo(track)
                    2 -> MusicDialogs.sendToPlaylist(this, listOf(track))
                    3 -> share(track)
                    4 -> locate(track)
                    5 -> { PlaylistStore.toggleFav(this, track); MusicEngine.notifyFavChanged() }
                    6 -> removeTrack(track)
                }
            }
            .show()
    }

    private fun removeTrack(track: PlaylistTrack) {
        PlaylistStore.removeTrack(this, playlistId, track.id)
        if (MusicEngine.queueId == playlistId) MusicEngine.removeFromQueue(track.id)
        reload()
    }

    private fun showInfo(track: PlaylistTrack) {
        val f = synchronized(files) { files[track.id] } ?: return
        MusicDialogs.showInfo(this, f)
    }

    private fun share(track: PlaylistTrack) {
        val f = synchronized(files) { files[track.id] } ?: return
        MusicDialogs.share(this, f)
    }

    private fun locate(track: PlaylistTrack) {
        val f = synchronized(files) { files[track.id] } ?: return
        MusicDialogs.locateInFileManager(this, f)
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** 副标题:格式 · 频率 · 比特率 · 文件大小(未知项自动省略)。 */
    private fun techLine(t: PlaylistTrack, sizeBytes: Long): String {
        val parts = ArrayList<String>(4)
        t.ext.takeIf { it.isNotEmpty() }?.let { parts += it.uppercase() }
        if (t.sampleRate > 0) {
            val khz = t.sampleRate / 1000f
            parts += (if (khz % 1f == 0f) "%.0fkHz".format(khz) else "%.1fkHz".format(khz))
        }
        if (t.bitrate > 0) parts += "${(t.bitrate + 500) / 1000}kbps"
        if (sizeBytes > 0) parts += Format.size(sizeBytes)
        return parts.joinToString(" · ")
    }

    // ---- Adapter ----

    private inner class TrackAdapter : RecyclerView.Adapter<TrackVH>() {
        private var items: List<PlaylistTrack> = emptyList()
        private var playingPos = -1 // 当前高亮那一行的下标(-1 = 本列表不在播),供切歌时增量刷新

        fun submit(list: List<PlaylistTrack>) {
            items = list
            playingPos = currentPos() ?: -1
            notifyDataSetChanged()
        }

        fun updateTrack(t: PlaylistTrack) {
            val i = items.indexOfFirst { it.id == t.id }
            if (i >= 0) { items = items.toMutableList().also { it[i] = t }; notifyItemChanged(i) }
        }

        /** 切歌:只把旧行与新行各刷一条。整表 notifyDataSetChanged 会重新锚定列表、
         *  让封面/元数据回填中的行闪一下(见 [applyBg] 那段注释),这里绝不能用。 */
        fun syncPlaying() {
            val pos = currentPos() ?: -1
            if (pos == playingPos) return
            val old = playingPos
            playingPos = pos
            if (old in items.indices) notifyItemChanged(old)
            if (pos in items.indices) notifyItemChanged(pos)
        }

        fun notifyItemChangedById(id: String) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) notifyItemChanged(i)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackVH =
            TrackVH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        /** 背景/强调色变了:只改这一行的配色,不走完整 rebind(见 [applyTint])。 */
        override fun onBindViewHolder(h: TrackVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_COLORS }) {
                bindColors(h, items[position])
            } else {
                super.onBindViewHolder(h, position, payloads)
            }
        }

        override fun onBindViewHolder(h: TrackVH, position: Int) {
            val t = items[position]
            h.b.num.text = if (isPlaying(t)) "▶" else "${position + 1}"
            bindColors(h, t)
            val f = synchronized(files) { files[t.id] }
            val title = t.title.ifEmpty { t.name }
            h.b.title.text = if (t.artist.isNotEmpty()) "${t.artist} - $title" else title
            // 大小优先取已解析 XFile(网络来源会补真实 size);未解析出来时退回存储值
            val sub = techLine(t, maxOf(t.size, f?.size ?: 0L))
            h.b.artist.text = sub
            h.b.artist.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            h.b.duration.text = fmt(t.durationMs)
            // 只在这个 ViewHolder 换了曲目时才把封面复位成占位图标——否则后台陆续回填元数据触发的
            // notifyItemChanged/notifyDataSetChanged 会让本来已显示封面的行每次 rebind 都先闪一下
            // 占位图再填回封面(音频封面首次是异步生成的,占位图这一帧会真的露出来),这才是"列表
            // 更新时跳闪一下"的根源。同曲目 rebind 保持现有封面不动;换曲目才复位(用带 key 的 tag,
            // 不和 Thumbs.bind 内部用的普通 tag 冲突)。
            if (h.b.cover.getTag(R.id.cover) != t.id) {
                h.b.cover.setImageResource(R.drawable.ic_audio)
                h.b.cover.setTag(R.id.cover, t.id)
            }
            if (f != null) Thumbs.bind(h.b.cover, f)
            h.b.root.setOnClickListener { play(position) }
            h.b.root.setOnLongClickListener { trackMenu(t); true }
            h.b.menu.setOnClickListener { trackMenu(t) }
        }

        /** 按 id 匹配(而非下标):m3u8/旧列表里跳过的曲目会让队列下标与列表下标错位。 */
        private fun isPlaying(t: PlaylistTrack): Boolean =
            MusicEngine.queueId == playlistId && MusicEngine.hasQueue() &&
                MusicEngine.currentTrack()?.id == t.id

        private fun bindColors(h: TrackVH, t: PlaylistTrack) {
            val playing = isPlaying(t)
            val accent = effAccent()
            h.b.num.setTextColor(if (playing) accent else tint.tertiary)
            h.b.title.setTextColor(if (playing) accent else tint.primary)
            h.b.artist.setTextColor(tint.tertiary)
            h.b.duration.setTextColor(tint.tertiary)
            h.b.menu.setColorFilter(tint.secondary)
        }
    }

    private inner class TrackVH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private const val EXTRA_ID = "playlist_id"
        private const val PAYLOAD_COLORS = "colors"
        /**
         * 毛玻璃上那层额外遮罩(@id/scrim)。alpha=0 = 不叠:文字颜色现在由 MusicTint 按
         * 实际背景色反推,不靠压暗背景保证可读性,压暗只会把封面颜色洗掉。想找回:改成
         * 0x66000000(布局里 @id/scrim 的原色),视图与 bgTone 会自动跟着一起算。
         */
        private const val SCRIM = 0x00000000
        fun start(ctx: Context, playlistId: String) {
            ctx.startActivity(Intent(ctx, PlaylistActivity::class.java).putExtra(EXTRA_ID, playlistId))
        }
    }
}
