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

/** Playlist page: lists tracks (number / cover / title / artist / duration); tap a row to play; overflow menu for management. */
@UnstableApi
class PlaylistActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlaylistBinding
    private lateinit var playlistId: String
    private var playlist: Playlist? = null
    private val files = HashMap<String, XFile>() // trackId -> resolved XFile
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-pl").apply { isDaemon = true } }
    private lateinit var adapter: TrackAdapter
    private lateinit var plDrawer: PlaylistDrawer
    private val finisher: () -> Unit = { finish() }
    private var blurForId: String? = null
    private var rowAccent = 0 // accent color extracted from the cover, used to color the currently playing row (0 = use default accent)
    private var bgTone = 0 // the background color text actually sits on (blurred average color + scrim); 0 = cover not yet ready
    private var tint = MusicTint.of(android.graphics.Color.parseColor("#1A1A1A")) // foreground layer color

    /** When the list page is open, the queue auto-advances: highlight / blur / scroll position all
     *  follow along (previously only refreshed once in onResume, so if you stayed on the page and
     *  finished a track, ▶ stayed on the previous one). Only registered while visible. */
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
        b.bgBlur.setBackgroundColor(fallbackBg()) // paint a solid color first to avoid a black flash on entry
        b.toolbar.setNavigationOnClickListener { b.drawer.openDrawer(androidx.core.view.GravityCompat.START) }
        plDrawer = PlaylistDrawer(this, b.drawer, b.drawerList, b.drawerExit)
        MusicUi.register(finisher)
        adapter = TrackAdapter()
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        // Metadata / cover / accent come back from the background one by one, triggering notifyItemChanged
        // along the way. The default ItemAnimator does a fade-in/out change animation for each
        // notifyItemChanged — that's the real source of the "list updates flash" glitch. Previously
        // setting supportsChangeAnimations=false alone didn't fix it (the animator still drives
        // re-layout); remove the animator entirely so all item changes rebind instantly without
        // flashing. This list doesn't need add/remove animations either.
        b.list.itemAnimator = null
        applyTint() // cover not out yet: derive a palette from fallbackBg first, so the pure-white placeholder in XML doesn't show through
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        MusicUi.unregister(finisher)
    }

    /** Immersive: content extends below the status bar (blur fills it); toolbar / list inset around system bars. */
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

    /** Use the currently playing track's cover (if this is the current queue) or the first
     *  resolvable track's cover as the blur background. When this list is currently playing,
     *  reuse the cover / blur / accent already computed by [MusicArt] — instant display, no more "black flash". */
    private fun loadBlurBg() {
        val isCurrent = MusicEngine.queueId == playlistId && MusicEngine.hasQueue()
        val track = if (isCurrent) MusicEngine.currentTrack() else playlist?.tracks?.firstOrNull()
        val id = track ?: return
        if (blurForId == id.id) return
        blurForId = id.id
        val dark = isDark()
        if (isCurrent) {
            MusicArt.snapshot(id.id, dark)?.let { applyBg(id.id, it.blur, it.accent) } // cache hit: apply immediately
            io.execute {
                val f = synchronized(files) { files[id.id] } ?: MusicEngine.resolveFile(applicationContext, id) ?: return@execute
                MusicArt.load(this, id.id, f, dark) { if (!isFinishing) applyBg(id.id, it.blur, it.accent) }
            }
            return
        }
        // Not the current queue: compute locally, don't pollute MusicArt's "now playing" cache slot
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
            // The blur may also have a scrim drawn on top in the layout; what text actually sits on
            // is the "average color + this overlay" result
            bgTone = MusicTint.blend(MusicTint.averageColor(bg), SCRIM)
        } else {
            bgTone = 0
        }
        applyTint()
    }

    /**
     * Foreground colors follow the cover (same [MusicTint] as the player page): title / track name /
     * subtitle / duration / number are no longer hard-coded pure white, but same-color-family colors
     * with stepped contrast derived from the background.
     *
     * Refresh can only go through **payload-based partial rebind**: previously (for a single color
     * change) this used notifyDataSetChanged() to rebind the whole list, which was often triggered
     * from inside the MusicArt.load async callback, right in the window when scrollToCurrent()'s
     * positioning hadn't settled yet; the whole-table relayout would re-anchor the list once more,
     * which is the root of "opening the list while it's still updating flashes when locating the
     * current track"; and a full rebind would reset the cover to the placeholder image then
     * asynchronously fill it back in, flashing a second time.
     */
    private fun applyTint() {
        tint = MusicTint.of(if (bgTone != 0) bgTone else fallbackBg())
        b.plTitle.setTextColor(tint.primary)
        b.plCount.setTextColor(tint.secondary)
        b.empty.setTextColor(tint.secondary)
        b.toolbar.navigationIcon = b.toolbar.navigationIcon?.mutate()?.apply { setTint(tint.secondary) }
        // Light/dark of status/nav bar icons follows the background: now that the blur is no longer
        // darkened, white icons disappear on light covers
        val light = MusicTint.isLight(if (bgTone != 0) bgTone else fallbackBg())
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_COLORS)
    }

    /** The accent color of the currently playing row; also ensures it's still visible on the current
     *  background (the background is itself a blur of this cover). */
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
        adapter.notifyDataSetChanged() // refresh the now-playing highlight
        adapter.syncPlaying()          // sync the highlight row index (for later incremental refreshes)
        plDrawer.reload()
        scrollToCurrent()
        MusicEngine.addListener(musicListener)
    }

    override fun onPause() {
        super.onPause()
        MusicEngine.removeListener(musicListener)
    }

    /** When entering / returning to the list page, auto-scroll to the currently playing track (only when this list is the current queue). */
    private fun scrollToCurrent() {
        val pos = currentPos() ?: return
        b.list.post {
            (b.list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pos, b.list.height / 3)
        }
    }

    /** Follow along when the queue advances to the next track: don't move if the new row is already
     *  on screen (don't drag the user's current view away); only smoothly scroll if it's scrolled
     *  out of view. */
    private fun followCurrent() {
        val pos = currentPos() ?: return
        val lm = b.list.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && pos in first..last) return
        b.list.smoothScrollToPosition(pos)
    }

    /** The index of the currently playing track in this list; null if this list isn't the current queue. */
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

    /** Resolve each track's XFile in the background (conn tracks build connections); used for cover thumbnails and metadata backfill. */
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
        // Same as the player page: pair labels with actions, don't use a hardcoded index — "go to
        // containing folder" is missing for sources like document trees (see MusicDialogs.canLocate);
        // an index off-by-one ends up triggering the wrong action
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += getString(R.string.music_play) to {
            playlist?.tracks?.indexOfFirst { it.id == track.id }?.takeIf { it >= 0 }?.let { play(it) }
            Unit
        }
        items += getString(R.string.music_info) to { showInfo(track) }
        items += getString(R.string.music_send_to) to { MusicDialogs.sendToPlaylist(this, listOf(track)) }
        items += getString(R.string.music_share) to { share(track) }
        val file = synchronized(files) { files[track.id] }
        if (file != null && MusicDialogs.canLocate(file)) {
            items += getString(R.string.music_locate) to { locate(track) }
        }
        val favLabel = if (PlaylistStore.isFav(this, track.id)) {
            getString(R.string.music_remove_fav)
        } else {
            getString(R.string.music_add_fav)
        }
        items += favLabel to { PlaylistStore.toggleFav(this, track); MusicEngine.notifyFavChanged() }
        items += getString(R.string.music_remove_from) to { removeTrack(track) }
        AlertDialog.Builder(this)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
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

    /** Subtitle: format · frequency · bitrate · file size (unknown items auto-omitted). */
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
        private var playingPos = -1 // index of the currently highlighted row (-1 = this list isn't playing), for incremental refresh on track change

        fun submit(list: List<PlaylistTrack>) {
            items = list
            playingPos = currentPos() ?: -1
            notifyDataSetChanged()
        }

        fun updateTrack(t: PlaylistTrack) {
            val i = items.indexOfFirst { it.id == t.id }
            if (i >= 0) { items = items.toMutableList().also { it[i] = t }; notifyItemChanged(i) }
        }

        /** Track change: only refresh the old row and the new row. Whole-table notifyDataSetChanged
         *  re-anchors the list and makes rows whose cover/metadata are being backfilled flash (see
         *  the [applyBg] comment) — never use that here. */
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

        /** Background/accent changed: only update this row's colors, not a full rebind (see [applyTint]). */
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
            // Prefer size from the resolved XFile (network sources backfill the real size); if not resolved, fall back to the stored value
            val sub = techLine(t, maxOf(t.size, f?.size ?: 0L))
            h.b.artist.text = sub
            h.b.artist.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            h.b.duration.text = fmt(t.durationMs)
            // Only reset the cover to the placeholder icon when this ViewHolder switches to a
            // different track — otherwise the notifyItemChanged/notifyDataSetChanged triggered by
            // metadata backfill from the background makes rows that already display a cover flash
            // back to the placeholder and then back to the cover on each rebind (the audio cover is
            // generated asynchronously the first time, so the placeholder actually shows for one
            // frame), and that's the root cause of "list updates flash". Rebinding the same track
            // leaves the existing cover alone; only a different track triggers a reset (uses a keyed
            // tag so it doesn't collide with the ordinary tag Thumbs.bind uses internally).
            if (h.b.cover.getTag(R.id.cover) != t.id) {
                h.b.cover.setImageResource(R.drawable.ic_audio)
                h.b.cover.setTag(R.id.cover, t.id)
            }
            if (f != null) Thumbs.bind(h.b.cover, f)
            h.b.root.setOnClickListener { play(position) }
            h.b.root.setOnLongClickListener { trackMenu(t); true }
            h.b.menu.setOnClickListener { trackMenu(t) }
        }

        /** Match by id (not by index): skipped tracks in m3u8 / old lists cause queue and list indices to drift. */
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
         * The extra overlay on top of the blur (@id/scrim). alpha=0 = no overlay: text colors are
         * now derived by MusicTint from the actual background color, not by darkening the background
         * to guarantee readability — darkening only washes out the cover colors. To restore: change
         * to 0x66000000 (the original @id/scrim color in the layout), and the view and bgTone will
         * automatically account for it together.
         */
        private const val SCRIM = 0x00000000
        fun start(ctx: Context, playlistId: String) {
            ctx.startActivity(Intent(ctx, PlaylistActivity::class.java).putExtra(EXTRA_ID, playlistId))
        }
    }
}
