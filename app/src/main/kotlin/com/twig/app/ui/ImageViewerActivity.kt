package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityImageViewerBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 图片查看器 = 幻灯片同屏(参照 SambaGallery):
 * 单个可缩放视图 + 手势翻页;切换/自动播放时保留当前图,右上角显示转圈直到新图就绪。
 * 全屏沉浸;单击切换悬浮栏;双击循环缩放模式;随机播放。
 *
 * 两种进入方式:
 * - [start]:固定列表(从文件列表点开单张图,兄弟图片已在内存里),index 从 0 开始不变。
 * - [startSlideshow]:只传目录,自己在本 Activity 内用 [scanImages] 后台递归扫描
 *   ——找到第一张就先显示、边播边继续扫描追加,不必等整棵树扫完(网络来源尤其明显);
 *   工具栏副标题显示扫描进度。两种入口都不把大列表塞进 Intent extras
 *   ([pendingImages] 进程内直传),避免上千张图片的路径/文件名数组撑爆 binder 单次事务
 *   1MB 上限(TransactionTooLargeException)。
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityImageViewerBinding

    private var images = mutableListOf<XFile>()  // 原始发现顺序,只追加、不重排——真实 index 的来源
    private var order = mutableListOf<Int>()     // 播放顺序(随机时是 images 下标的一个排列)
    private var pos = 0                          // 在 order 中的位置;current = order[pos] 是真实下标
    private var shuffle = false
    private var playing = false
    private var loadToken = 0                    // 防止过期加载覆盖
    private var autoFit = true                   // 按屏幕方向旋转图片适配
    private var currentLoading = false           // 当前图正在加载(下载)
    private var prefetching = false              // 正在预取下一张
    private var scanDone = true                  // 幻灯片递归扫描是否已完成(固定列表模式恒真)
    private var wantAutoPlay = false              // 幻灯片模式:图片就绪后自动开始播放
    private var canSelect = true                 // 「选择」入口(外部 content:// 打开时没有树可同步,隐藏)

    /** 在查看器里勾选的图(按 path 去重,保持勾选顺序);退出后同步成树上的多选,见 [pendingResult]。 */
    private val picked = LinkedHashMap<String, XFile>()
    private var changed = false                  // 删过图,文件面板需要刷新

    /** 按字节计容量:降采样上限放宽后单张位图可达数十 MB,再按"6 张"算会 OOM。 */
    private val cache = object : LruCache<String, Decoded>(
        ((Runtime.getRuntime().maxMemory() / 4) / 1024).toInt().coerceAtLeast(16 * 1024),
    ) {
        override fun sizeOf(key: String, value: Decoded) = value.bmp.byteCount / 1024
    }

    private var shown: Decoded? = null // 当前显示的解码结果
    private var shownRot = 0           // 显示位图相对原文件的总旋转角(EXIF + autoFit)
    private var hiResToken = 0
    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable {
        if (scanDone && pos == order.lastIndex && !Prefs.slideshowLoop(this)) stop() else go(1)
    }

    private val current get() = order[pos]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.menu.add(R.string.action_more).apply {
            setIcon(R.drawable.ic_more_vert)
            icon?.setTint(android.graphics.Color.WHITE) // 半透明黑底上默认色看不清
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { showActions(); true }
        }
        b.image.onTap = { toggleControls() }
        b.image.onLongPress = { showActions() }
        b.image.onPage = { dir -> scheduleHide(); go(dir) }
        b.image.onNeedHiRes = { rect, scale -> loadHiRes(rect, scale) }
        b.btnPrev.setOnClickListener { scheduleHide(); go(-1) }
        b.btnNext.setOnClickListener { scheduleHide(); go(1) }
        b.btnPlay.setOnClickListener { scheduleHide(); if (playing) stop() else start() }
        b.btnShuffle.setOnClickListener { scheduleHide(); toggleShuffle() }
        autoFit = Prefs.imageAutoFit(this)
        b.btnFit.alpha = if (autoFit) 1f else 0.5f
        b.btnFit.setOnClickListener { scheduleHide(); toggleAutoFit() }
        scheduleHide() // 悬浮栏默认可见,3 秒无操作后自动隐藏

        canSelect = intent.getBooleanExtra(EXTRA_ALLOW_SELECT, true)
        when (intent.getIntExtra(EXTRA_MODE, MODE_FIXED)) {
            MODE_SCAN -> {
                val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
                val rootPath = intent.getStringExtra(EXTRA_ROOT_PATH) ?: return finish()
                wantAutoPlay = true
                shuffle = Prefs.slideshowShuffle(this)
                b.btnShuffle.alpha = if (shuffle) 1f else 0.5f
                startScan(XFile(scheme, rootPath, isDir = true))
            }
            else -> {
                val list = pendingImages
                pendingImages = null
                if (list.isNullOrEmpty()) return finish()
                images = list.toMutableList()
                order = images.indices.toMutableList()
                pos = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, images.lastIndex)
                b.btnShuffle.alpha = 0.5f
                onImagesReady()
            }
        }
    }

    private fun toggleAutoFit() {
        autoFit = !autoFit
        Prefs.setImageAutoFit(this, autoFit)
        b.btnFit.alpha = if (autoFit) 1f else 0.5f
        if (order.isNotEmpty()) cache.get(images[current].path)?.let { show(it) } // 立即重画当前图
    }

    // ---- 全屏沉浸 ----

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 设备旋转不重建,但需按新屏幕方向重新旋转适配当前图
        if (order.isEmpty()) return
        cache.get(images[current].path)?.let { b.image.post { show(it) } }
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- 幻灯片递归扫描(边扫边播)----

    private fun startScan(root: XFile) {
        scanDone = false // ★ 必须先置 false,否则 scanTick 首帧就因 scanDone==true 不再续期,总数只刷一次
        b.loading.visibility = View.VISIBLE
        handler.postDelayed(scanTick, SCAN_TICK_MS) // 标题里的总数按固定节奏刷新,与扫描速度解耦,避免每张图都触发一次刷新
        lifecycleScope.launch {
            var started = false
            runCatching {
                scanImages(root, Prefs.slideshowMaxImages(this@ImageViewerActivity)).collect { f ->
                    val idx = images.size
                    images.add(f)
                    if (shuffle && started) {
                        order.add((pos + 1 + (0..(order.size - pos - 1)).random()), idx)
                    } else {
                        order.add(idx)
                    }
                    if (!started) {
                        started = true
                        pos = 0
                        onImagesReady()
                    }
                }
            }
            scanDone = true
            handler.removeCallbacks(scanTick)
            if (order.isNotEmpty()) updateTitle()
            if (!started) {
                if (images.isEmpty()) {
                    Toast.makeText(this@ImageViewerActivity, R.string.no_images, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    started = true
                    pos = 0
                    onImagesReady()
                }
            }
        }
    }

    /** 标题里的"当前/总数"按固定节奏刷新总数(不用等切图才看到最新已扫到的张数)。只更新
     * 文本、不干预悬浮栏显隐——扫描期间悬浮栏照常 3 秒无操作自动隐藏,下次唤出即显示最新总数。 */
    private val scanTick: Runnable = object : Runnable {
        override fun run() {
            if (order.isNotEmpty()) updateTitle()
            if (!scanDone) handler.postDelayed(this, SCAN_TICK_MS)
        }
    }

    /** 图片列表(至少有第一张)就绪:开始显示 + 幻灯片模式自动播放。 */
    private fun onImagesReady() {
        loadCurrent()
        if (wantAutoPlay) start()
    }

    // ---- 翻页 / 加载 ----

    private fun go(delta: Int) {
        if (order.isEmpty()) return
        pos = (pos + delta + order.size) % order.size
        loadCurrent() // 自动播放的下一次计时在当前图加载完成后(onCurrentReady)才安排
    }

    private fun loadCurrent() {
        val idx = current
        val path = images[idx].path
        updateTitle()
        val token = ++loadToken

        cache.get(path)?.let {
            currentLoading = false; updateSpinner()
            show(it)
            onCurrentReady()
            return
        }
        // 保留当前图(不清空),右上角转圈表示正在下载/加载
        currentLoading = true; updateSpinner()
        lifecycleScope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { decodeImage(this@ImageViewerActivity, images[idx]) } }.getOrNull()
            if (token != loadToken) return@launch // 已切到别的图
            currentLoading = false; updateSpinner()
            if (bmp != null) { cache.put(path, bmp); show(bmp) }
            onCurrentReady()
        }
    }

    /** 当前图加载完成:此时才安排下一次自动播放、并预取下一张(当前没加载完不往后下载)。 */
    private fun onCurrentReady() {
        if (playing) scheduleNext()
        prefetchNext()
    }

    /** 显示图片:按屏幕方向旋转适配(不改屏幕方向,只旋转图片内容);传入实际尺寸倍数。 */
    private fun show(d: Decoded) {
        val extra = if (autoFit) screenFitRotation(d.bmp) else 0
        shown = d
        shownRot = (d.exifRot + extra) % 360
        b.image.setImage(if (extra == 0) d.bmp else rotate(d.bmp, extra), d.actual)
    }

    /** 图片方向与屏幕方向不一致时旋转 90°,以在当前屏幕上"横着/竖着"占满。 */
    private fun screenFitRotation(bmp: Bitmap): Int {
        val screenLandscape = resources.displayMetrics.widthPixels >= resources.displayMetrics.heightPixels
        val imgLandscape = bmp.width > bmp.height
        return if (imgLandscape == screenLandscape || bmp.width == bmp.height) 0 else 90
    }

    /** 预取下一张到缓存(带转圈);当前图还在加载则不预取,避免"往后下载"。 */
    private fun prefetchNext() {
        if (currentLoading || order.isEmpty()) return
        val i = order[(pos + 1) % order.size]
        val p = images[i].path
        if (cache.get(p) != null) return
        prefetching = true; updateSpinner()
        lifecycleScope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { decodeImage(this@ImageViewerActivity, images[i]) } }.getOrNull()
            prefetching = false; updateSpinner()
            if (bmp != null && cache.get(p) == null) cache.put(p, bmp)
        }
    }

    private fun updateSpinner() {
        b.loading.visibility = if (currentLoading || prefetching) View.VISIBLE else View.GONE
    }

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, Prefs.slideshowIntervalMs(this))
    }

    // ---- 播放 / 随机 ----

    private fun start() {
        playing = true
        b.btnPlay.setImageResource(R.drawable.ic_pause)
        if (Prefs.slideshowKeepAwake(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!currentLoading) scheduleNext() // 当前图正在加载则等它就绪再计时
    }

    private fun stop() {
        playing = false
        b.btnPlay.setImageResource(R.drawable.ic_play)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(advance)
    }

    private fun toggleShuffle() {
        if (images.isEmpty()) return
        val cur = current
        shuffle = !shuffle
        order = images.indices.toMutableList()
        if (shuffle) { order.shuffle(); order.remove(cur); order.add(0, cur) }
        pos = order.indexOf(cur).coerceAtLeast(0)
        b.btnShuffle.alpha = if (shuffle) 1f else 0.5f
    }

    private fun toggleControls() {
        setControlsVisible(b.toolbar.visibility != View.VISIBLE)
    }

    private fun setControlsVisible(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        b.toolbar.visibility = v
        b.controls.visibility = v
        if (show) scheduleHide() else handler.removeCallbacks(hideControls)
    }

    private val hideControls = Runnable { setControlsVisible(false) }

    /** 悬浮栏/底栏 3 秒无操作后自动隐藏;每次交互(翻页/播放/随机/适配按钮、手动唤出)重新计时。 */
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, HIDE_CONTROLS_MS)
    }

    /** 标题显示"真实 index"(该图在扫描/浏览原始顺序中的位置),而非随机播放序号——
     * 随机播放时序号会跳来跳去,但反映的是这张图在目录里的真实位置。 */
    private fun updateTitle() {
        val f = images.getOrNull(current) ?: return
        b.toolbar.title = "${current + 1}/${images.size}  ${f.name}"
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(f.name))
    }

    // ---- 操作菜单(长按画面 / 标题栏「更多」) ----

    /**
     * 当前图的操作菜单。自动播放中弹菜单会先停播——否则对话框开着图还在往后翻,
     * 用户点「删除」删掉的就不是他看着的那张了。
     */
    private fun showActions() {
        val file = images.getOrNull(current) ?: return
        if (playing) stop()
        handler.removeCallbacks(hideControls) // 菜单期间别让悬浮栏自己缩回去
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (canSelect) {
            val on = picked.containsKey(file.path)
            labels += getString(if (on) R.string.action_unselect else R.string.action_select)
            acts += { togglePick(file) }
        }
        labels += getString(R.string.action_delete)
        acts += { confirmDelete(file) }
        labels += getString(R.string.action_share)
        acts += { runCatching { OpenFiles.share(this, file) } }
        labels += getString(R.string.image_info)
        acts += { MusicDialogs.showInfo(this, file, R.string.image_info) }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setOnDismissListener { scheduleHide() }
            .show()
    }

    /** 勾选/取消当前图;结果随时发布到 [pendingResult],由文件面板回到前台时取走。 */
    private fun togglePick(file: XFile) {
        if (picked.remove(file.path) == null) picked[file.path] = file
        toast(getString(R.string.title_selected_count, picked.size))
        publishResult()
    }

    private fun confirmDelete(file: XFile) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete, 1))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> delete(file) }
            .setOnDismissListener { scheduleHide() }
            .show()
    }

    /**
     * 删除当前图并从列表里摘掉。[images] 是"原始发现顺序"、[order] 存的是它的下标,
     * 删一项会让后面所有下标错位——必须整体重排 [order](>被删下标的减一),
     * 不能只 remove 一个元素。删完 [pos] 原地指向播放顺序里的下一张。
     */
    private fun delete(file: XFile) {
        val idx = images.indexOfFirst { it.path == file.path && it.scheme == file.scheme }
        if (idx < 0) return
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { FsRegistry.of(file).delete(file) }.exceptionOrNull()
            }
            if (err != null) { toast(err.message ?: getString(R.string.info_failed)); return@launch }
            cache.remove(file.path)
            picked.remove(file.path)
            images.removeAt(idx)
            order = order.filter { it != idx }.map { if (it > idx) it - 1 else it }.toMutableList()
            changed = true
            publishResult()
            if (order.isEmpty()) { finish(); return@launch }
            pos = pos.coerceAtMost(order.lastIndex)
            loadCurrent()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun publishResult() {
        pendingResult = Result(picked.values.toList(), changed)
    }

    // ---- 放大后的高清区块(BitmapRegionDecoder) ----

    /** 基础位图被放大到插值了,按可视区域从原文件重解一块高清的叠上去。 */
    private fun loadHiRes(rect: RectF, scale: Float) {
        val d = shown ?: return
        if (d.actual <= 1f) return // 原图就没有更多像素可挖
        val rot = shownRot
        val vw = b.image.width.coerceAtLeast(1) // View 尺寸在主线程取好再进 IO
        val vh = b.image.height.coerceAtLeast(1)
        val token = ++hiResToken
        lifecycleScope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { decodeRegion(d, rot, rect, scale, vw, vh) } }.getOrNull()
            if (token != hiResToken || bmp == null) return@launch
            if (shown === d && shownRot == rot) b.image.setHiRes(bmp, rect)
        }
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advance)
        handler.removeCallbacks(scanTick)
        handler.removeCallbacks(hideControls)
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_ROOT_PATH = "root_path"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_ALLOW_SELECT = "allow_select"
        private const val MODE_FIXED = 0
        private const val MODE_SCAN = 1
        private const val SCAN_TICK_MS = 400L
        private const val HIDE_CONTROLS_MS = 3000L

        /** 高清区块的像素上限:可视面积的倍数,再叠一个绝对上限(约 48MB@ARGB_8888)。 */
        /** 固定列表模式下的进程内一次性传递槽:避免通过 Intent extras 传大列表触发
         * TransactionTooLargeException(binder 单次事务上限 1MB,单目录几千张图片的路径 +
         * 文件名数组很容易超)。onCreate 里读取后立即清空。 */
        @Volatile private var pendingImages: List<XFile>? = null

        /**
         * 查看器里的操作结果:勾选的图([selection])、是否删过图([changed])。
         * 由 [PaneFragment] 回到前台时 [takeResult] 取走一次——查看器是普通
         * startActivity 起的(图片列表走 [pendingImages] 进程内直传,没走 Intent),
         * 这里同样用进程内槽位回传,不额外引一套 ActivityResult 契约。
         */
        class Result(val selection: List<XFile>, val changed: Boolean)

        @Volatile private var pendingResult: Result? = null

        fun takeResult(): Result? = pendingResult.also { pendingResult = null }

        /**
         * 固定图片列表(如同目录兄弟图片,已在内存里),从 index 开始,不自动播放。
         * [allowSelect]=false 用于外部 content:// 打开(没有文件树可同步,不给「选择」)。
         */
        fun start(context: Context, images: List<XFile>, startIndex: Int, allowSelect: Boolean = true) {
            if (images.isEmpty()) return
            pendingImages = images
            pendingResult = null // 上一轮没人来取的陈旧结果就此作废
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_FIXED)
                    putExtra(EXTRA_INDEX, startIndex)
                    putExtra(EXTRA_ALLOW_SELECT, allowSelect)
                },
            )
        }

        /** 幻灯片:只传目录,查看器自己后台递归扫描并边扫边播,自动播放。 */
        fun startSlideshow(context: Context, dir: XFile) {
            pendingResult = null
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_SCAN)
                    putExtra(EXTRA_SCHEME, dir.scheme)
                    putExtra(EXTRA_ROOT_PATH, dir.path)
                },
            )
        }
    }
}
