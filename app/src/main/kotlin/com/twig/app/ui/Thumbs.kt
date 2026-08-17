package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 缩略图引擎:内存 LruCache + 磁盘缓存(cacheDir/thumbs,LRU 上限 100MB)。
 *
 * 缓存 key = md5(文件名:大小:修改时间)——与路径无关,同一文件换挂载点/换路径仍
 * 命中同一份缓存,文件变更后自动失效。
 *
 * 生成规则(最大边长 [MAX_EDGE]):
 * - 图片(网络来源):**先无条件试 EXIF 内嵌缩略图**(只读文件头 256KB,流量可忽略,
 *   不受"优先使用内置缩略图"开关限制——那个开关只管本地文件的画质/速度取舍,本地
 *   全量解码本来就很便宜;网络场景先试内嵌图没有任何坏处),命中就直接用,不碰
 *   网络;没有内嵌图(非 jpg、或 jpg 没带)时才看"对网络文件生成缩略图"开关——关闭
 *   则放弃(这是设计使然,不是 bug),开启才整图下载解码。**这是网络缩略图快慢的
 *   关键**:多数相机/手机拍的 jpg 都带内嵌图,先试命中率很高,只看开关直接整图
 *   下载是不少人觉得"很慢"的根源;
 * - 图片(本地/SAF):"优先使用内置缩略图"开启时先试内嵌图,否则直接全量采样解码
 *   (本地开销小,不像网络要考虑流量/延迟);原图本身已在 256 以内则直接用解码
 *   结果,不再另存一份缩略图文件(省磁盘、避免二次有损压缩);
 * - 视频帧:取时长 1/[VIDEO_FRAME_DIVISOR] 处而不是开头([pickRepresentativeFrame])——
 *   开头常是黑场淡入/片头 Logo,同一部剧集集集都长一个样,不够有代表性。取不到才
 *   退回时间 0。本地/SAF 走 `MediaMetadataRetriever.setDataSource(String)`,
 *   retriever 能直接 seek 到任意时间点,不需要额外准备。网络来源同样受"对网络文件
 *   生成"开关控制([genVideoNetworkFrame]):先精确读文件头 [VIDEO_HEAD_CAP](含
 *   ftyp,时间 0 附近的关键帧数据,取不到 1/10 处时的最终兜底)+ moov 本体(容器
 *   元数据,常见在头部"faststart"或尾部"边录边写、最后补 moov"两种布局;精确偏移/
 *   大小由 [scanTopBoxes] 扫描 box 头——只读 8/16 字节,靠 box size 跳转不读内容——
 *   得到,不是固定猜一个尾部大小:长/高码率视频的 moov 可以到十几 MB,猜小了整段
 *   拿不到,扫描失败才退化成猜 `VIDEO_TAIL_FALLBACK_CAP` 兜底)+ 目标时间点的关键帧
 *   数据(由 [findKeyframeOffset] **精确解析 moov 采样表**算出真实字节偏移后精准
 *   下载一个小窗口——早先按 mdat 大小线性估算比例算"大致偏移",实测 VBR 视频
 *   (前后段码率不均匀)偏差可达 9MB 以上,固定窗口根本盖不住,这才是"取 1/10 处
 *   经常失败、退回黑色开头"的真正原因;用 ffprobe 抽真实关键帧位置核对过,采样表
 *   算出的偏移和真实值完全一致)。曾经加过"取到黑帧就换时间点重试"这层,但采样表
 *   精确定位后命中率已经很高,那层重试只剩开销没有收益,故去掉。
 *   MP4 上述精确路径的**前提是 ISO BMFF 的 moov/mdat**(ISO 14496-12);MKV/AVI 等
 *   其它容器解析不出 moov。对它们:若来源支持高效定位读([FileSystem.randomAccessEfficient],
 *   SMB pread / WebDAV Range),就把一个**真随机访问 + 块缓存**的数据源
 *   ([NetVideoDataSource])交给 MMR,让它自己解封装 + seek(内部 MediaExtractor
 *   原生支持 MKV,HEVC 走硬件 MediaCodec),只读它需要的字节;来源无高效定位读
 *   (FTP/SFTP)则只喂头部、基本只能取时间 0(可能黑),但不会把整个文件拖下来。
 *   **MP4 精确路径故意不用 [FileSystem.openRandom] 让 retriever 自己按需 seek**——
 *   试过,retriever 内部会散落地调用很多次 seek+read,moov 在尾部时若来源退化成
 *   "重开跳过"相当于要把整个文件传一遍;所以 MP4 我们自己精确取所需片段(次数
 *   固定)。MKV 走 [NetVideoDataSource] 时用块缓存 + 累计读取上限约束这个开销。
 *   另外发现无参 `frameAtTime`(等价 `getFrameAtTime(-1, OPTION_CLOSEST_SYNC)`)
 *   配合自定义 `MediaDataSource` 经常干净地返回 null(数据明明完整,不抛异常也不
 *   超时)——全程改用显式时间点 + 两种 OPTION 尝试,不用无参版本;
 * - 音频封面(mp3/flac/m4a 等):先取内嵌封面(ID3 APIC / FLAC PICTURE / m4a covr,
 *   MMR 解析,本地给路径、网络给随机访问数据源按需读),没有内嵌封面再退回同目录的
 *   cover/folder/front/albumart.jpg|png|webp(专辑目录常见约定;按目录缓存查找结果,
 *   一个专辑目录几十首歌只列目录/下载封面一次)。网络来源受"对网络文件生成"开关控制;
 * - PDF 首页:要求本地可随机访问的真文件(PdfRenderer 要 fd),网络来源在 [eligible]
 *   里同步判定必然失败,不进线程池排队;
 * - **应用图标(APK 文件 / 「应用」树条目)不在这里出缩略图**:图标由 [FileIcons] 直接问
 *   PackageManager 要(同样是异步 + 有缓存),不受缩略图开关影响、任何时候都显示。走缩略图
 *   管线只是把同一张图再生成一遍、再占一份磁盘缓存和生成队列名额,没有意义。
 *
 * 网络协议本身的开销也会拖慢速度,和这里的缩略图逻辑无关但值得知道:`FtpFileSystem`
 * 每次 openInput/list 都是即连即断(每个文件都要重新握手 + 登录),没有做连接池;
 * SFTP(持久 SSH 连接)/WebDAV(OkHttp 连接池)/SMB(持久 smb-io 连接)都复用连接,
 * 通常比 FTP 快不少。后台并发数固定 2([executor]),不是"一个一个生成"但也没有很高。
 *
 * 首次失败只带 [FAIL_COOLDOWN_MS] 冷却期,不立刻拉黑:网络失败常是一次性的(弱网超时、
 * 连接被重置——尤其 SMB 0.45.4 之前那次线程串行化 bug 修复前更容易撞上),冷却期一过
 * 下次绑定会重新尝试,不会因为一次网络抖动就再也生成不出来。但如果冷却期过后**再次**
 * 失败(连续 [BLACKLIST_THRESHOLD] 次),大概率是文件本身解不出来而非网络抖动——尤其
 * 视频一次失败就要卡满 [VIDEO_TIMEOUT_MS](15s),只按内存冷却的话每次重启/冷却期过后
 * 都会在同一个文件上再卡一次。这种情况写入持久化 [blacklist](`cacheDir/thumbs_blacklist`,
 * 存 key 而非路径,文件被替换/修复后 key 自然变化不会被冤枉),之后直接跳过不再尝试,
 * 直到用户手动清缓存([clearCache])。
 *
 * 目录折叠时 `PaneViewModel` 会调 [cancelPending] 把该目录(含仍展开的子目录/压缩包,
 * 递归)下还没开始跑的任务从线程池队列摘掉;已经在跑的不打断,跑完照常进缓存
 * (等于顺手预热,下次展开直接命中)。
 */
object Thumbs {

    private const val MAX_EDGE = 256
    private const val HEAD_BYTES = 256 * 1024 // EXIF 内嵌缩略图只读文件头
    private const val NET_DECODE_CAP = 64L * 1024 * 1024 // 网络整图解码的大小上限
    private const val DISK_CAP = 100L * 1024 * 1024 // 磁盘缓存上限(超限按 LRU 清到 80%)
    private const val FAIL_COOLDOWN_MS = 60_000L // 失败冷却期:期间不重试,过后允许再试一次

    /** 网络整块下载用的读取缓冲区。**实测这是 SMB(以及其他网络来源)缩略图慢的
     * 关键因素**:Kotlin `InputStream.readBytes()` 默认按 8KB 一块读,对网络流意味着
     * 一张 5MB 的照片要来回 600+ 次——每次都是一次 `NativeSmbClient.exec()` 线程调度
     * 往返 + 一次 SMB2 Read 协议请求/响应,时延一放大(哪怕局域网也有毫秒级)乘以
     * 几百次就是几百毫秒到几秒。调大到 256KB 能把往返次数砍到十几次。 */
    private const val NET_READ_CHUNK = 256 * 1024
    private val JPG = setOf("jpg", "jpeg")

    private val mem = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** key -> 上次失败时间戳;冷却期内不重试(避免滚动时反复打开坏文件/慢网络),
     * 过后允许重新尝试——网络失败常是一次性的,不该永久拉黑一个文件。 */
    private val failed = Collections.synchronizedMap(HashMap<String, Long>())

    /** key -> 连续失败次数(成功一次清零);用来判断是否该升级进 [blacklist]。 */
    private val failCount = Collections.synchronizedMap(HashMap<String, Int>())

    /** 连续失败达 [BLACKLIST_THRESHOLD] 次后写入的持久化黑名单——单次失败仍按
     * [FAIL_COOLDOWN_MS] 冷却重试(可能只是网络抖动),但冷却期一过重试**还是**失败,
     * 大概率是这个文件本身解不出来(如没有对应解码器的编码、损坏文件),而视频一次
     * 解码失败就要卡满 [VIDEO_TIMEOUT_MS](15s)——只按内存冷却的话,App 重启或冷却期
     * 一过就会在同一个文件上再卡一次 15s。写盘持久化后不再受重启/冷却期影响,直到
     * 用户手动清缓存([clearCache])才会再给它一次机会。key 含 size+mtime,文件被
     * 替换/修复后天然换新 key,不会被冤枉拉黑。 */
    private const val BLACKLIST_THRESHOLD = 2
    private const val BLACKLIST_FILE = "thumbs_blacklist"
    private var blacklist: MutableSet<String>? = null

    private fun blacklistFile(ctx: Context) = File(ctx.cacheDir, BLACKLIST_FILE)

    private fun loadBlacklist(ctx: Context): MutableSet<String> {
        blacklist?.let { return it }
        synchronized(this) {
            blacklist?.let { return it }
            val loaded = runCatching {
                blacklistFile(ctx).readLines().filter { it.isNotBlank() }
            }.getOrNull() ?: emptyList()
            val set = Collections.synchronizedSet(loaded.toMutableSet())
            blacklist = set
            return set
        }
    }

    private fun addToBlacklist(ctx: Context, key: String) {
        val set = loadBlacklist(ctx)
        if (!set.add(key)) return
        runCatching { blacklistFile(ctx).appendText("$key\n") }
    }

    private fun recentlyFailed(ctx: Context, key: String): Boolean {
        if (loadBlacklist(ctx).contains(key)) return true
        val at = failed[key] ?: return false
        return System.currentTimeMillis() - at < FAIL_COOLDOWN_MS
    }

    /** key -> 等待回填的视图(仅主线程访问);已有任务在跑时后续视图只挂队列。 */
    private val waiters = HashMap<String, MutableList<ImageView>>()

    /** key -> 已提交但可能还没开始跑的任务(仅主线程访问,与 [waiters] 同一约束)。
     * 目录折叠时用来把还没开始跑的任务从线程池队列摘掉([cancelPending]);已经在跑
     * 的任务不受影响——不强行中断,交给它自己跑完、正常收尾清理这里的条目。 */
    private val queued = HashMap<String, Runnable>()

    /** 2 线程并行,FIFO 队列(先提交先处理)。 */
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "twig-thumbs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /**
     * 上次裁剪磁盘缓存的时刻。原来是个一次性布尔:整个进程只裁一次,长时间开着连续
     * 浏览大量媒体目录时,100MB 上限在下次冷启动前形同虚设。改成按间隔重跑。
     */
    @Volatile private var lastTrimAt = 0L
    private const val TRIM_INTERVAL_MS = 10 * 60 * 1000L

    fun canThumb(f: XFile): Boolean = !f.isDir &&
        (
            OpenFiles.isImage(f) || OpenFiles.isVideo(f) || OpenFiles.isAudio(f) ||
                f.extension == "pdf"
            )

    /** 提前判断该文件是否有机会生成成功;网络来源的 PDF 必然失败(需要本地可随机
     * 访问的真文件),不值得排进后台线程池排队——否则会被同池里较慢的网络图片下载
     * 卡住,白白排队。视频受"对网络文件生成"开关控制(和图片一样,走网络就有真实
     * 流量),不像 PDF 那样天生做不到。 */
    private fun eligible(ctx: Context, file: XFile): Boolean {
        val localish = file.scheme == "file" || file.scheme == "saf"
        return when {
            OpenFiles.isImage(file) -> true
            OpenFiles.isVideo(file) -> localish || Prefs.thumbsNetwork(ctx)
            OpenFiles.isAudio(file) -> localish || Prefs.thumbsNetwork(ctx)
            file.extension == "pdf" -> localish
            else -> false
        }
    }

    /** 异步绑定缩略图;命中内存缓存立即回填,否则保留占位图标后台加载/生成。 */
    fun bind(view: ImageView, file: XFile) {
        if (!canThumb(file)) return
        val key = keyOf(file)
        mem.get(key)?.let { fill(view, it, key); return }
        val ctx = view.context.applicationContext
        if (recentlyFailed(ctx, key)) return
        if (!eligible(ctx, file)) { failed[key] = System.currentTimeMillis(); return }
        view.tag = key
        waiters[key]?.let { it.add(view); return }
        waiters[key] = arrayListOf(view)
        val job = Runnable {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTrimAt > TRIM_INTERVAL_MS) {
                lastTrimAt = now
                runCatching { trim(diskDir(ctx)) }
            }
            val bmp = runCatching { load(ctx, file, key) }.getOrNull()
            if (bmp == null) {
                failed[key] = System.currentTimeMillis()
                val count = (failCount[key] ?: 0) + 1
                failCount[key] = count
                if (count >= BLACKLIST_THRESHOLD) addToBlacklist(ctx, key)
            } else {
                failed.remove(key)
                failCount.remove(key)
                mem.put(key, bmp)
            }
            main.post {
                queued.remove(key)
                val views = waiters.remove(key) ?: return@post
                if (bmp != null) views.forEach { v -> if (v.tag == key) fill(v, bmp, key) }
            }
        }
        queued[key] = job
        executor.execute(job)
    }

    /** 目录折叠时调用:把 [files] 里还没开始跑的生成任务从线程池队列里摘掉,省得
     * 排队干等一堆已经不可见的行。已经在跑的任务不受影响(不强行中断)。 */
    fun cancelPending(files: Collection<XFile>) {
        for (f in files) {
            val key = keyOf(f)
            val job = queued[key] ?: continue
            if (executor.remove(job)) {
                queued.remove(key)
                waiters.remove(key)
            }
        }
    }

    /** 磁盘缓存占用(设置页展示)。 */
    fun cacheBytes(ctx: Context): Long = diskDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(ctx: Context) {
        mem.evictAll()
        failed.clear()
        failCount.clear()
        synchronized(this) { blacklist = mutableSetOf() }
        blacklistFile(ctx).delete()
        synchronized(dirCoverBytes) { dirCoverBytes.clear() }
        diskDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** 单文件"刷新缩略图":清掉内存/磁盘缓存及失败/黑名单标记,下次绑定会
     * 重新生成。用户可见的强制刷新入口(菜单项),与 [clearCache] 的全量清空不同,
     * 只影响这一个文件。 */
    fun invalidate(ctx: Context, file: XFile) {
        val key = keyOf(file)
        mem.remove(key)
        failed.remove(key)
        failCount.remove(key)
        val set = loadBlacklist(ctx)
        if (set.remove(key)) {
            runCatching {
                blacklistFile(ctx).writeText(set.joinToString("") { "$it\n" })
            }
        }
        runCatching { File(diskDir(ctx), key).delete() }
    }

    /** 目录"刷新缩略图":后台线程递归遍历,对每个文件调用
     * [invalidate];完成后回主线程执行 [onDone](通常用来刷新列表显示)。网络目录遍历
     * 可能较慢,不能在主线程做,复用 [executor]。 */
    fun invalidateDir(ctx: Context, dir: XFile, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { walkInvalidate(ctx, dir) }
            main.post(onDone)
        }
    }

    private fun walkInvalidate(ctx: Context, dir: XFile) {
        val list = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        for (f in list) {
            if (f.isDir) walkInvalidate(ctx, f) else invalidate(ctx, f)
        }
    }

    // ---- 内部 ----

    /** 树式行(有 `infoBox` 兄弟视图)按图片宽高比调整高度,不裁切/顶部裁切,见
     * [fillAspect];网格格子等其它场景维持原来的固定方形 + CENTER_CROP。 */
    private fun fill(view: ImageView, bmp: Bitmap, key: String) {
        view.setPadding(0, 0, 0, 0)
        val box = (view.parent as? ViewGroup)?.findViewById<View>(R.id.infoBox)
        if (box == null) {
            view.tag = null
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
        } else {
            fillAspect(view, bmp, box, key)
        }
    }


    /** 树式列表缩略图:宽度不变,高度按原图宽高比算——横图变矮完整显示(不裁切);
     * 竖图变高,上限是右侧信息区([box])当前高度,超过上限则顶部裁切填满(絶不会比
     * 现在的方形宽度更矮,因为竖图按比例算出的高度天然 ≥ 宽度)。等 [box] 完成本轮
     * 布局([View.post])才读它的实测高度,避免读到上一次绑定的旧值;用 [key] 做
     * tag 校验,若视图在 post 触发前已被回收绑到别的文件就放弃(防止串图)。 */
    private fun fillAspect(view: ImageView, bmp: Bitmap, box: View, key: String) {
        val lp = view.layoutParams
        val w = lp.width
        val bw = bmp.width
        val bh = bmp.height
        if (w <= 0 || bw <= 0 || bh <= 0) {
            view.tag = null
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
            return
        }
        val marker = "aspect:$key"
        view.tag = marker
        view.post {
            if (view.tag != marker) return@post
            view.tag = null
            val maxH = maxOf(box.height, w)
            val natural = (w.toLong() * bh / bw).toInt().coerceAtLeast(1)
            val h = minOf(natural, maxH)
            if (lp.height != h) { lp.height = h; view.layoutParams = lp }
            if (natural <= maxH) {
                view.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                val scale = w.toFloat() / bw
                view.scaleType = ImageView.ScaleType.MATRIX
                view.imageMatrix = Matrix().apply { setScale(scale, scale) }
            }
            view.setImageBitmap(bmp)
        }
    }

    private fun keyOf(f: XFile): String {
        val d = MessageDigest.getInstance("MD5")
            .digest("${f.name}:${f.size}:${f.lastModified}".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun diskDir(ctx: Context): File = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    private fun load(ctx: Context, file: XFile, key: String): Bitmap? {
        val disk = File(diskDir(ctx), key)
        if (disk.isFile) {
            BitmapFactory.decodeFile(disk.path)?.let {
                disk.setLastModified(System.currentTimeMillis()) // 磁盘 LRU 记一次使用
                return it
            }
        }
        val raw = generate(ctx, file) ?: return null
        // 图片本身已在 256 以内:无需另存一份缩略图文件(节省磁盘 + 避免二次 JPEG 有损压缩),
        // 直接把解码结果交给内存缓存即可。
        if (OpenFiles.isImage(file) && maxOf(raw.width, raw.height) <= MAX_EDGE) return raw
        val bmp = scaleTo(raw)
        val png = bmp.hasAlpha()
        val bytes = ByteArrayOutputStream().let { bos ->
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, bos)
            bos.toByteArray()
        }
        runCatching { disk.writeBytes(bytes) }
        return bmp
    }

    private fun generate(ctx: Context, file: XFile): Bitmap? = when {
        OpenFiles.isImage(file) -> genImage(ctx, file)
        OpenFiles.isVideo(file) -> genVideo(ctx, file)
        OpenFiles.isAudio(file) -> genAudio(file)
        file.extension == "pdf" -> genPdf(file)
        else -> null
    }

    private fun genImage(ctx: Context, file: XFile): Bitmap? {
        val localish = file.scheme == "file" || file.scheme == "saf"
        if (!localish) {
            // 网络来源:内置缩略图只读文件头几十 KB,几乎零成本,总是先试一把——
            // 不受"优先使用内置缩略图"开关限制(那个开关是本地文件的画质/速度取舍,
            // 本地全量解码本来就很便宜)。网络场景没有不试的理由:大多数相机/手机
            // 拍的 jpg 都带内置缩略图,先试命中就不用整图下载,这是网络缩略图快慢
            // 的关键——只看"对网络文件生成"开关整图下载,是不少人觉得"很慢"的根源。
            embedded(file)?.let { return it }
            if (!Prefs.thumbsNetwork(ctx)) return null // 开关关闭且没有内置图:不下载
            if (file.size > NET_DECODE_CAP) return null
            val bytes = FsRegistry.of(file).openInput(file).use { readCapped(it, NET_DECODE_CAP, file.size) }
            val bmp = decodeSampled { ByteArrayInputStream(bytes) } ?: return null
            return rotate(bmp, if (file.extension in JPG) orientationOf(bytes) else 0)
        }
        if (Prefs.thumbsEmbedded(ctx)) embedded(file)?.let { return it }
        val fs = FsRegistry.of(file)
        val bmp = decodeSampled { fs.openInput(file) } ?: return null
        return rotate(bmp, if (file.extension in JPG) orientationOf(head(file)) else 0)
    }

    /** jpg 的 EXIF 内嵌缩略图(只读文件头);没有则 null。 */
    private fun embedded(file: XFile): Bitmap? {
        if (file.extension !in JPG) return null
        val bytes = head(file) ?: return null
        return runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            exif.thumbnail?.let { t ->
                BitmapFactory.decodeByteArray(t, 0, t.size)
                    ?.let { rotate(it, orientationDegrees(exif)) }
            }
        }.getOrNull()
    }

    /** 网络视频精确读两段:文件头 [VIDEO_HEAD_CAP](含 ftyp,以及 moov 在尾部布局时
     * 紧随其后的关键帧数据)+ moov 本体——moov 的真实偏移/大小由 [scanMoovBox] 扫描
     * box 头精确得到,而不是猜一个固定尾部大小:长视频/高码率视频的 moov(采样表,
     * 大致跟帧数成正比)可以到十几 MB 甚至更大,固定猜 4MB 这类小文件够、大文件完全
     * 覆盖不到——这正是"多数 mp4 能生成、少数(尤其是长/大文件)生成不了"的真正原因。
     * 扫描失败(结构异常)才退化成猜 [VIDEO_TAIL_FALLBACK_CAP] 兜底。 */
    private const val VIDEO_HEAD_CAP = 8L * 1024 * 1024
    private const val VIDEO_TAIL_FALLBACK_CAP = 4L * 1024 * 1024
    private const val VIDEO_MOOV_CAP = 64L * 1024 * 1024 // moov 大小的合理上限,异常大就放弃,不无限下载

    /** MediaMetadataRetriever 超时上限:遇到截断/畸形的容器(网络视频只喂了前
     * [VIDEO_HEAD_CAP],moov 没读全时尤其容易撞上)有时会在原生层长时间探测格式甚至
     * 卡住不返回——它没有取消 API。之前整个 genVideo 直接跑在 [executor] 的 2 个
     * twig-thumbs 线程上,一个视频卡住就等于占掉一半线程池,连图片缩略图都会跟着
     * 停摆,表现为"等很久都不出来,不知道是不是还在生成"。现在把它丢到独立的
     * [videoExecutor] 上跑,主线程池只等 [VIDEO_TIMEOUT_MS] 就撤——等不到就判失败
     * (走 [FAIL_COOLDOWN_MS] 冷却重试),twig-thumbs 池不再被拖住;卡住的那次调用
     * 留在 twig-video 的独立线程上自生自灭(daemon 线程,不影响进程退出)。
     *
     * **实测坑**:加了按候选时间点现下载数据窗口的黑场重试后,6 秒经常不够用——
     * 每个候选都要现下载一个 [VIDEO_MID_WINDOW] 窗口,几个候选加起来的网络耗时容易
     * 超过 6 秒,导致大量视频直接超时判失败(比之前"退回黑色开头"还差,变成整个
     * 没有缩略图)。现在把上限放宽到 15 秒——这个等待只占用 [videoExecutor] 自己的
     * 线程,不占用 twig-thumbs 共享池的名额,加长不影响其它文件的缩略图并发。 */
    private const val VIDEO_TIMEOUT_MS = 15_000L
    private val videoExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "twig-video").apply { isDaemon = true }
    }

    private fun genVideo(ctx: Context, file: XFile): Bitmap? = try {
        videoExecutor.submit(Callable { genVideoBlocking(ctx, file) }).get(VIDEO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        Log.w("twig", "thumbs: video timeout (${VIDEO_TIMEOUT_MS}ms) ${file.name}")
        null
    } catch (e: Exception) {
        Log.w("twig", "thumbs: video failed ${file.name}: $e")
        null
    }

    /** 目标时间点:取时长的 1/[VIDEO_FRAME_DIVISOR] 处而不是开头——开头常是黑场淡入/
     * 片头 Logo。曾经加过"取到黑帧就换时间点重试"(isMostlyBlack)那层,但
     * [findKeyframeOffset] 精确解析出关键帧真实字节偏移后命中率已经很高,那层重试
     * 只剩下"每次都多下载几个窗口"的开销、没有实际收益,故去掉(实测反馈确认)。 */
    private const val VIDEO_FRAME_DIVISOR = 10L

    /** [findKeyframeOffset] 精确算出关键帧字节偏移后,前后各留的安全边界——只是防止
     * 解码器需要 SPS/PPS 或往前多探一点,不用像比例估算那样留大窗口。 */
    private const val VIDEO_KEYFRAME_MARGIN = 128L * 1024
    private const val VIDEO_KEYFRAME_WINDOW = 8L * 1024 * 1024 // 4K/60fps I 帧可达数 MB,窗口给大点少走回落读

    /** [findKeyframeOffset] 解析失败时的兜底:按 mdat 大小估算比例算出大致偏移,
     * 前后各留一半窗口兜住码率不均匀的误差(实测这个误差可能到 9MB 以上,兜底本身
     * 并不可靠,只是"没有更好办法时的最后手段")。 */
    private const val VIDEO_MID_WINDOW = 8L * 1024 * 1024

    private fun genVideoBlocking(ctx: Context, file: XFile): Bitmap? {
        val frame = try {
            if (file.extension == "avi") {
                // 系统 MediaMetadataRetriever 不支持 AVI 解封装(本地/网络都一样),
                // 只能绕开它走 GlFrameGrabber(见其注释)。
                genVideoAviFrame(ctx, file)
            } else if (file.extension == "m2ts") {
                // 真 BDAV M2TS 每包 192 字节,MMR 支不支持因设备而异不保底——统一走
                // GlFrameGrabber + M2tsStrippingDataSource,和播放器那条路径一致。
                genVideoM2tsFrame(ctx, file)
            } else if (file.scheme == "file") {
                withRetriever { r ->
                    r.setDataSource(file.path)
                    pickRepresentativeFrame(r, file, durationMsOf(r))
                }
            } else {
                genVideoNetworkFrame(file)
            }
        } catch (e: Exception) {
            Log.w("twig", "thumbs: video decode failed ${file.name}: $e")
            null
        }
        frame?.setHasAlpha(false)
        return frame
    }

    private fun genVideoAviFrame(ctx: Context, file: XFile): Bitmap? {
        if (file.scheme == "file") {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(file.path)))
            return GlFrameGrabber.grab(ctx, mediaItem, null, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS)
        }
        val mediaItem = MediaItem.fromUri("twig:///media.avi")
        return FsRegistry.of(file).openRandom(file).use { raw ->
            // idx1 常在文件尾部,seek 到目标时间前得先跳到尾部读它——裸 RandomSource
            // 一堆小读来回,网络上很容易把预算耗在这上面,套一层预读缓存(和播放器
            // 用的是同一个)。
            val src = BufferedRandomSource(raw)
            val factory = DataSource.Factory { RandomSourceDataSource(src) }
            GlFrameGrabber.grab(ctx, mediaItem, factory, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS)
        }
    }

    private fun genVideoM2tsFrame(ctx: Context, file: XFile): Bitmap? {
        val local = file.scheme == "file"
        val shared = if (local) null else BufferedRandomSource(FsRegistry.of(file).openRandom(file))
        try {
            val rawPacketSize = detectM2tsPacketSize(file, shared)
            val baseFactory: DataSource.Factory = if (shared != null) {
                DataSource.Factory { RandomSourceDataSource(shared) }
            } else {
                FileDataSource.Factory()
            }
            val mediaItem = if (local) {
                MediaItem.fromUri(Uri.fromFile(File(file.path)))
            } else {
                MediaItem.fromUri("twig:///media.ts")
            }
            return if (rawPacketSize != RAW_M2TS_PACKET_SIZE) {
                // 有些工具把普通 188 字节 TS 流也存成 .m2ts 后缀,不用剥,走默认 sniff。
                GlFrameGrabber.grab(ctx, mediaItem, baseFactory, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS)
            } else {
                val totalRawLength = if (local) File(file.path).length() else file.size
                val strippingFactory = DataSource.Factory {
                    M2tsStrippingDataSource(baseFactory.createDataSource(), totalRawLength)
                }
                GlFrameGrabber.grab(
                    ctx, mediaItem, strippingFactory, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS, newM2tsExtractorsFactory(),
                )
            }
        } finally {
            shared?.close()
        }
    }

    /** 每次取帧用一个独立的 MediaMetadataRetriever 实例(setDataSource 只能调一次,
     * 精确 MKV 失败要退回随机访问就得换新实例);用完即 release。 */
    private inline fun <T> withRetriever(block: (MediaMetadataRetriever) -> T): T {
        val r = MediaMetadataRetriever()
        try {
            return block(r)
        } finally {
            runCatching { r.release() }
        }
    }

    /** 尝试时长 1/[VIDEO_FRAME_DIVISOR] 处,取不到才退回时间 0;[prepareFor] 在尝试前
     * 调用(网络来源用来现下载目标时间点附近的数据窗口,本地文件不需要,传空实现
     * 即可——retriever 能直接 seek 到任意时间)。
     *
     * 无参 frameAtTime(即 getFrameAtTime(-1, OPTION_CLOSEST_SYNC))配合自定义
     * MediaDataSource 实测经常干净地直接返回 null(不抛异常也不超时,数据也确认
     * 完整)——所以这里全程用显式时间点 + 两种 OPTION 尝试,不用无参版本。 */
    private fun pickRepresentativeFrame(
        r: MediaMetadataRetriever,
        file: XFile,
        durationMs: Long?,
        prepareFor: (targetUs: Long) -> Unit = {},
    ): Bitmap? {
        val targetUs = if (durationMs != null && durationMs > 0) durationMs * 1000 / VIDEO_FRAME_DIVISOR else 0L
        prepareFor(targetUs)
        var frame = sequenceOf(
            targetUs to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            targetUs to MediaMetadataRetriever.OPTION_CLOSEST,
        ).firstNotNullOfOrNull { (t, opt) -> runCatching { r.getFrameAtTime(t, opt) }.getOrNull() }
        if (frame == null && targetUs != 0L) {
            frame = runCatching { r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
        }
        if (frame == null) Log.w("twig", "thumbs: video frameAtTime null (all attempts) ${file.name}")
        return frame
    }

    private fun genVideoNetworkFrame(file: XFile): Bitmap? {
        val fs = FsRegistry.of(file)
        val size = file.size
        return fs.openRandom(file).use { src ->
            // 先嗅一小段判容器:ISO BMFF(mp4/mov)第一个 box 就是 ftyp(第 4~8 字节);
            // Matroska/WebM(MKV)以 EBML magic 1A45DFA3 开头。
            val sniff = readAtCapped(src, 0, 64)
            if (sniff.isEmpty()) {
                Log.w("twig", "thumbs: video head empty ${file.name}")
                return@use null
            }
            val isoBmff = sniff.size >= 12 && String(sniff, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            val isMatroska = sniff.size >= 4 && sniff[0] == 0x1A.toByte() && sniff[1] == 0x45.toByte() &&
                sniff[2] == 0xDF.toByte() && sniff[3] == 0xA3.toByte()
            when {
                isoBmff -> withRetriever { r -> genFrameMp4(r, file, src, size) }
                isMatroska && fs.randomAccessEfficient() ->
                    // MKV 优先精确解析 EBML(SeekHead→Cues→目标 Cluster),只下 init+Cues+
                    // 目标簇三段(通常 ~2MB,固定几次定位读);解析不出(无 Cues 索引等)
                    // 才退回让 MMR 自己随机访问解封装。
                    withRetriever { r -> genFrameMkv(r, file, src, size) }
                        ?: withRetriever { r -> genFrameRandomAccess(r, file, src, size) }
                fs.randomAccessEfficient() ->
                    // 其它容器(AVI 等)没写离线解析:把带块缓存的真随机访问数据源交给
                    // MMR 自己解封装 + seek(FTP/SFTP 无高效定位读不走这条,免拖垮流量)。
                    withRetriever { r -> genFrameRandomAccess(r, file, src, size) }
                else ->
                    // 无高效定位读的非 MP4 来源:只能喂头部,基本只取到时间 0(可能黑)。
                    withRetriever { r ->
                        r.setDataSource(headOnlySource(readAtCapped(src, 0, minOf(VIDEO_HEAD_CAP, size)), size))
                        pickRepresentativeFrame(r, file, durationMsOf(r))
                    }
            }
        }
    }

    /** MKV(Matroska/WebM,EBML 容器)网络取帧:精确解析出目标关键帧所在 Cluster,
     * 只下载 init(EBML 头 + SeekHead + Info + Tracks)+ Cues 索引 + 目标 Cluster 三段
     * 喂给 MMR。和 MP4 的采样表路径同理:确定性、字节最少、定位读次数固定,不让 MMR
     * 自己在慢速网络上乱 seek(那是"MKV 很慢 / 大文件生成不了"的原因)。解析失败
     * (无 Cues 索引、结构异常)返回 null,由调用方退回随机访问兜底。 */
    private fun genFrameMkv(r: MediaMetadataRetriever, file: XFile, src: RandomSource, size: Long): Bitmap? {
        val plan = runCatching { planMkv(src, size) }.getOrNull()
        if (plan == null) {
            Log.w("twig", "thumbs: mkv plan failed ${file.name}")
            return null
        }
        val cluster = readAtCapped(src, plan.clusterStart, plan.clusterLen)
        if (cluster.size < 16) return null
        // 只取目标簇里的"那一个视频关键帧块",时间戳全部归零,合成成:簇头 +
        // Timestamp(0)+ 关键帧块。给 MMR 的合成文件里就只有一帧、且在 t=0——它没有
        // 别的帧可解、也不会往后 seek/前向解码,彻底避免"解到非关键帧/后续帧出绿屏"。
        // 关键帧块靠扫描簇内块头精确定位(看 track 是视频轨 + keyframe 标志),不依赖
        // CueRelativePosition(有些文件不写该字段,之前默认 0 会撞到 Timestamp 出绿屏)。
        val kfBlock = extractVideoKeyframeBlock(cluster, plan.videoTrack) ?: return null

        // 合成一个自足小 MKV:EBML 头 + Segment(未知大小)+ Info + Tracks + 只含关键帧块的
        // 小 Cluster。SeekHead 不放进去(它指向原文件绝对偏移,合成后是错的)。
        val bos = ByteArrayOutputStream(kfBlock.size + plan.tracksEnd + 64)
        bos.write(plan.head, 0, plan.ebmlEnd)
        bos.write(SEGMENT_UNKNOWN_HEADER)
        bos.write(plan.head, plan.infoStart, plan.infoEnd - plan.infoStart)
        bos.write(plan.head, plan.tracksStart, plan.tracksEnd - plan.tracksStart)
        bos.write(CLUSTER_UNKNOWN_HEADER)
        bos.write(TIMESTAMP_ZERO) // Timestamp = 0
        bos.write(kfBlock)
        val synth = bos.toByteArray()

        r.setDataSource(inMemorySource(synth))
        val t0 = SystemClock.elapsedRealtime()
        val frame = runCatching { r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            ?: runCatching { r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
        Log.d(
            "twig",
            "thumbs: mkv ${file.name} size=$size synth=${synth.size} kf=${kfBlock.size} " +
                "frame=${frame != null} ms=${SystemClock.elapsedRealtime() - t0}",
        )
        return frame
    }

    /** 在 Cluster 里扫出第一个"视频轨关键帧"块(SimpleBlock 有 keyframe 标志 / BlockGroup
     * 无 ReferenceBlock),把它的块内相对时间戳(track 号后 2 字节)清零后**整块复制**返回。
     * 只取这一块:合成文件里就只有一帧、且在 t=0,MMR 无从解到别的帧(绿屏根治)。找不到
     * 返回 null。 */
    private fun extractVideoKeyframeBlock(cluster: ByteArray, videoTrack: Long): ByteArray? {
        val ch = ebmlHeader(cluster, 0) ?: return null
        if (ch.id != CLUSTER_ID) return null
        val hdrLen = ch.bodyStart.toInt()
        val bodyEnd = if (ch.unknownSize) cluster.size else minOf((ch.bodyStart + ch.contentSize).toInt(), cluster.size)
        var p = hdrLen
        while (p < bodyEnd) {
            val e = ebmlHeader(cluster, p) ?: break
            val end = (e.bodyStart + e.contentSize).toInt()
            if (end > bodyEnd || end <= p) break
            when (e.id) {
                SIMPLEBLOCK_ID -> {
                    // [track vint][timecode 2B][flags 1B]...;keyframe = flags & 0x80
                    val tv = ebmlVint(cluster, e.bodyStart.toInt(), keepMarker = false)
                    if (tv != null) {
                        val tcPos = e.bodyStart.toInt() + tv.second
                        if (tcPos + 3 <= cluster.size) {
                            val track = tv.first
                            val flags = cluster[tcPos + 2].toInt() and 0xFF
                            if (track == videoTrack && (flags and 0x80) != 0) {
                                val block = cluster.copyOfRange(p, end)
                                zeroBlockTimecode(block, (e.bodyStart.toInt() - p) + tv.second)
                                return block
                            }
                        }
                    }
                }
                BLOCKGROUP_ID -> {
                    // 含 Block(0xA1)且无 ReferenceBlock(0xFB)= 关键帧
                    var q = e.bodyStart.toInt()
                    var blockPos = -1
                    var hasRef = false
                    while (q < end) {
                        val x = ebmlHeader(cluster, q) ?: break
                        if (x.id == BLOCK_ID) blockPos = q
                        if (x.id == REFERENCEBLOCK_ID) hasRef = true
                        q = (x.bodyStart + x.contentSize).toInt()
                        if (q <= x.start.toInt()) break
                    }
                    if (blockPos >= 0 && !hasRef) {
                        val bx = ebmlHeader(cluster, blockPos)!!
                        val tv = ebmlVint(cluster, bx.bodyStart.toInt(), keepMarker = false)
                        if (tv != null && tv.first == videoTrack) {
                            val block = cluster.copyOfRange(p, end)
                            zeroBlockTimecode(block, (bx.bodyStart.toInt() - p) + tv.second)
                            return block
                        }
                    }
                }
            }
            p = end
        }
        return null
    }

    /** 把块内(SimpleBlock 或 BlockGroup 的 Block)track 号之后的 2 字节相对时间戳清零。 */
    private fun zeroBlockTimecode(block: ByteArray, tcOffset: Int) {
        if (tcOffset + 1 < block.size) {
            block[tcOffset] = 0
            block[tcOffset + 1] = 0
        }
    }

    private val CLUSTER_UNKNOWN_HEADER = byteArrayOf(
        0x1F, 0x43, 0xB6.toByte(), 0x75, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )
    private val TIMESTAMP_ZERO = byteArrayOf(0xE7.toByte(), 0x81.toByte(), 0x00) // Timestamp 元素,值=0

    /** Segment 元素头 + "未知大小"(0x01 后跟 7 个 0xFF):合成流按数据源 EOF 结束。 */
    private val SEGMENT_UNKNOWN_HEADER = byteArrayOf(
        0x18, 0x53, 0x80.toByte(), 0x67, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )

    /** 纯内存数据源(合成 MKV 用),readAt 全命中内存。 */
    private fun inMemorySource(data: ByteArray) = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(len, (data.size - position).toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }

    // ---- MKV(EBML)精确定位:SeekHead → Cues → 目标 Cluster ----

    /** 为合成"自足小 MKV"备好的料:EBML 头 + Info + Tracks(都在 [head] 里,给出各自的
     * 起止)+ 目标 Cluster 的绝对区间。合成后只喂给 MMR 这一小段,它就无法去扫原文件
     * 的海量 Cluster(那是 REMUX 慢的根源)。 */
    private data class MkvPlan(
        val head: ByteArray,
        val ebmlEnd: Int,
        val infoStart: Int,
        val infoEnd: Int,
        val tracksStart: Int,
        val tracksEnd: Int,
        val clusterStart: Long,
        val clusterLen: Long,
        val videoTrack: Long,
        val targetUs: Long,
    )

    private const val EBML_ID = 0x1A45DFA3L
    private const val SEGMENT_ID = 0x18538067L
    private const val SEEKHEAD_ID = 0x114D9B74L
    private const val SEEK_ID = 0x4DBBL
    private const val SEEKID_ID = 0x53ABL
    private const val SEEKPOS_ID = 0x53ACL
    private const val INFO_ID = 0x1549A966L
    private const val TIMESTAMPSCALE_ID = 0x2AD7B1L
    private const val DURATION_ID = 0x4489L
    private const val TRACKS_ID = 0x1654AE6BL
    private const val CUES_ID = 0x1C53BB6BL
    private const val CUEPOINT_ID = 0xBBL
    private const val CUETIME_ID = 0xB3L
    private const val CUETRACKPOS_ID = 0xB7L
    private const val CUECLUSTERPOS_ID = 0xF1L
    private const val CUERELPOS_ID = 0xF0L
    private const val CUETRACK_ID = 0xF7L
    private const val CLUSTER_ID = 0x1F43B675L
    private const val TRACKENTRY_ID = 0xAEL
    private const val TRACKNUMBER_ID = 0xD7L
    private const val TRACKTYPE_ID = 0x83L
    private const val TIMESTAMP_ID = 0xE7L
    private const val SIMPLEBLOCK_ID = 0xA3L
    private const val BLOCKGROUP_ID = 0xA0L
    private const val BLOCK_ID = 0xA1L
    private const val REFERENCEBLOCK_ID = 0xFBL

    private const val MKV_HEAD_CAP = 64L * 1024 // EBML 头 + SeekHead + Info + Tracks 通常都在这以内
    private const val MKV_CUES_CAP = 16L * 1024 * 1024
    private const val MKV_CLUSTER_CAP = 24L * 1024 * 1024

    /** 解析出 init / Cues / 目标 Cluster 三段的绝对字节区间和目标时间(µs);
     * 无 SeekHead/Cues 或结构异常时返回 null(交给随机访问兜底)。 */
    private fun planMkv(src: RandomSource, size: Long): MkvPlan? {
        val head = readAtCapped(src, 0, minOf(MKV_HEAD_CAP, size))
        if (head.size < 8) return null

        // EBML 头 + Segment 头
        var p = 0
        var h = ebmlHeader(head, p) ?: return null
        if (h.id != EBML_ID) return null
        p = (h.bodyStart + h.contentSize).toInt() // 跳过 EBML 头体
        h = ebmlHeader(head, p) ?: return null
        if (h.id != SEGMENT_ID) return null
        val segBase = h.bodyStart // Segment 内偏移都相对这里

        // 扫描 Segment 顶层子元素(在 head 缓冲内),收集 SeekHead/Info/Tracks 与首个 Cluster
        var seekHead: EbmlBox? = null
        var info: EbmlBox? = null
        var tracks: EbmlBox? = null
        var q = segBase.toInt()
        while (q < head.size) {
            val e = ebmlHeader(head, q) ?: break
            if (e.id == CLUSTER_ID) break // 到第一个 Cluster 就停,前面的元数据已收齐
            when (e.id) {
                SEEKHEAD_ID -> seekHead = e
                INFO_ID -> info = e
                TRACKS_ID -> tracks = e
            }
            if (e.unknownSize) break
            q = (e.bodyStart + e.contentSize).toInt()
            if (q <= e.start.toInt()) break
        }
        val tk = tracks ?: return null
        val sh = seekHead ?: return null
        val inf = info ?: return null
        // Info/Tracks 必须完整落在 head 缓冲内(才能切出来合成),否则放弃走精确路径
        val infoEnd = (inf.bodyStart + inf.contentSize).toInt()
        val tracksEnd = (tk.bodyStart + tk.contentSize).toInt()
        if (infoEnd > head.size || tracksEnd > head.size) return null

        // SeekHead → Cues 的 Segment 相对偏移
        val cuesRel = parseSeekHead(head, sh, CUES_ID) ?: return null
        val cuesAbs = segBase + cuesRel
        if (cuesAbs < 0 || cuesAbs >= size) return null

        // Info → timestampScale(ns/tick,默认 1e6)+ duration(tick)
        var scale = 1_000_000L
        var durationTicks = 0.0
        if (info != null) {
            val parsed = parseInfo(head, info)
            scale = parsed.first
            durationTicks = parsed.second
        }

        // 读 Cues 元素(定位读)
        val cuesHdrBuf = readAtCapped(src, cuesAbs, 16)
        val ch = ebmlHeader(cuesHdrBuf, 0) ?: return null
        if (ch.id != CUES_ID || ch.unknownSize) return null
        val cuesBodyStart = cuesAbs + ch.bodyStart
        val cuesLen = ch.contentSize
        if (cuesLen <= 0 || cuesLen > MKV_CUES_CAP) return null
        // 视频轨号:Cues 会分别索引各条轨(含字幕轨稀疏索引);只挑视频轨的 CuePoint,
        // 否则可能选到字幕轨的 CueRelativePosition、指向字幕块而非关键帧(实测"绿屏/
        // 解不出"就是这原因)。
        val videoTrack = parseVideoTrackNumber(head, tk)
        val cuesData = readAtCapped(src, cuesBodyStart, cuesLen)
        val cuePoints = parseCues(cuesData, videoTrack) // (timeTicks, clusterSegRelPos, relPos),仅视频轨
        if (cuePoints.isEmpty()) return null

        val lastTime = cuePoints.last().time
        val targetTicks = (if (durationTicks > 0) durationTicks / VIDEO_FRAME_DIVISOR else lastTime.toDouble() / VIDEO_FRAME_DIVISOR).toLong()
        var chosen = cuePoints.first()
        for (cp in cuePoints) {
            if (cp.time <= targetTicks) chosen = cp else break
        }
        val clusterAbs = segBase + chosen.clusterPos
        if (clusterAbs < 0 || clusterAbs >= size) return null

        // 目标 Cluster 头 → 真实大小
        val clHdrBuf = readAtCapped(src, clusterAbs, 16)
        val cl = ebmlHeader(clHdrBuf, 0) ?: return null
        if (cl.id != CLUSTER_ID) return null
        val clusterLen = if (cl.unknownSize) MKV_CLUSTER_CAP else minOf(cl.bodyStart + cl.contentSize, MKV_CLUSTER_CAP)

        val ebmlEnd = (ebmlHeader(head, 0)!!.let { it.bodyStart + it.contentSize }).toInt()
        val targetUs = targetTicks * scale / 1000L
        return MkvPlan(
            head, ebmlEnd, inf.start.toInt(), infoEnd, tk.start.toInt(), tracksEnd,
            clusterAbs, clusterLen, videoTrack, targetUs,
        )
    }

    private data class MkvCue(val time: Long, val clusterPos: Long, val relPos: Long)

    /** Tracks 里 TrackType==1(视频)的 TrackNumber;找不到默认 1。 */
    private fun parseVideoTrackNumber(buf: ByteArray, tracks: EbmlBox): Long {
        var p = tracks.bodyStart.toInt()
        val end = (tracks.bodyStart + tracks.contentSize).toInt().coerceAtMost(buf.size)
        while (p < end) {
            val e = ebmlHeader(buf, p) ?: break
            if (e.id == TRACKENTRY_ID) {
                var r = e.bodyStart.toInt()
                val re = (e.bodyStart + e.contentSize).toInt().coerceAtMost(buf.size)
                var num = -1L
                var type = -1L
                while (r < re) {
                    val x = ebmlHeader(buf, r) ?: break
                    when (x.id) {
                        TRACKNUMBER_ID -> num = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                        TRACKTYPE_ID -> type = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                    }
                    r = (x.bodyStart + x.contentSize).toInt()
                }
                if (type == 1L && num >= 0) return num
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return 1L
    }

    /** EBML 元素头:id(保留长度标记位)+ 内容大小 + 头长;[start] 元素起始、[bodyStart] 内容起始。 */
    private data class EbmlBox(val id: Long, val start: Long, val bodyStart: Long, val contentSize: Long, val unknownSize: Boolean)

    private fun ebmlHeader(buf: ByteArray, p: Int): EbmlBox? {
        val idv = ebmlVint(buf, p, keepMarker = true) ?: return null
        val szPos = p + idv.second
        val szv = ebmlVint(buf, szPos, keepMarker = false) ?: return null
        val unknown = szv.third // all-ones = unknown size
        return EbmlBox(idv.first, p.toLong(), (szPos + szv.second).toLong(), szv.first, unknown)
    }

    /** 读一个 EBML vint。keepMarker=true 用于元素 ID(保留长度描述位),false 用于大小。
     * 返回 (值, 字节数, 是否全 1 即 unknown-size)。 */
    private fun ebmlVint(buf: ByteArray, p: Int, keepMarker: Boolean): Triple<Long, Int, Boolean>? {
        if (p < 0 || p >= buf.size) return null
        val b0 = buf[p].toInt() and 0xFF
        var mask = 0x80
        var length = 1
        while (length <= 8 && (b0 and mask) == 0) { mask = mask shr 1; length++ }
        if (length > 8 || p + length > buf.size) return null
        var v = (if (keepMarker) b0 else (b0 and (mask - 1))).toLong()
        for (i in 1 until length) v = (v shl 8) or (buf[p + i].toLong() and 0xFF)
        val allOnes = if (keepMarker) false else v == (1L shl (7 * length)) - 1
        return Triple(v, length, allOnes)
    }

    private fun ebmlUint(buf: ByteArray, p: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (buf[p + i].toLong() and 0xFF)
        return v
    }

    /** SeekHead 里找 [wantId] 的 Segment 相对偏移。 */
    private fun parseSeekHead(buf: ByteArray, sh: EbmlBox, wantId: Long): Long? {
        var p = sh.bodyStart.toInt()
        val end = (sh.bodyStart + sh.contentSize).toInt().coerceAtMost(buf.size)
        while (p < end) {
            val e = ebmlHeader(buf, p) ?: break
            if (e.id == SEEK_ID) {
                var r = e.bodyStart.toInt()
                val re = (e.bodyStart + e.contentSize).toInt().coerceAtMost(buf.size)
                var sid = -1L
                var spos = -1L
                while (r < re) {
                    val x = ebmlHeader(buf, r) ?: break
                    when (x.id) {
                        SEEKID_ID -> sid = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                        SEEKPOS_ID -> spos = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                    }
                    r = (x.bodyStart + x.contentSize).toInt()
                }
                if (sid == wantId && spos >= 0) return spos
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return null
    }

    private fun parseInfo(buf: ByteArray, info: EbmlBox): Pair<Long, Double> {
        var p = info.bodyStart.toInt()
        val end = (info.bodyStart + info.contentSize).toInt().coerceAtMost(buf.size)
        var scale = 1_000_000L
        var dur = 0.0
        while (p < end) {
            val e = ebmlHeader(buf, p) ?: break
            when (e.id) {
                TIMESTAMPSCALE_ID -> scale = ebmlUint(buf, e.bodyStart.toInt(), e.contentSize.toInt())
                DURATION_ID -> {
                    val n = e.contentSize.toInt()
                    val bits = ebmlUint(buf, e.bodyStart.toInt(), n)
                    dur = if (n == 4) java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(bits)
                }
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return scale to dur
    }

    /** 解析 Cues → **仅 [videoTrack] 轨**的 CuePoint(时间 tick、Cluster 的 Segment 相对
     * 偏移、关键帧块在 Cluster 内的相对偏移),按时间升序。一个 CuePoint 可能含多条
     * CueTrackPositions(视频/字幕各一),必须挑视频轨那条。 */
    private fun parseCues(buf: ByteArray, videoTrack: Long): List<MkvCue> {
        val out = ArrayList<MkvCue>()
        var p = 0
        while (p < buf.size) {
            val e = ebmlHeader(buf, p) ?: break
            if (e.id == CUEPOINT_ID) {
                var q = e.bodyStart.toInt()
                val qe = (e.bodyStart + e.contentSize).toInt().coerceAtMost(buf.size)
                var time = -1L
                var pos = -1L
                var rel = 0L
                while (q < qe) {
                    val x = ebmlHeader(buf, q) ?: break
                    when (x.id) {
                        CUETIME_ID -> time = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                        CUETRACKPOS_ID -> {
                            var s = x.bodyStart.toInt()
                            val se = (x.bodyStart + x.contentSize).toInt().coerceAtMost(buf.size)
                            var track = -1L
                            var cpos = -1L
                            var crel = 0L
                            while (s < se) {
                                val y = ebmlHeader(buf, s) ?: break
                                when (y.id) {
                                    CUETRACK_ID -> track = ebmlUint(buf, y.bodyStart.toInt(), y.contentSize.toInt())
                                    CUECLUSTERPOS_ID -> cpos = ebmlUint(buf, y.bodyStart.toInt(), y.contentSize.toInt())
                                    CUERELPOS_ID -> crel = ebmlUint(buf, y.bodyStart.toInt(), y.contentSize.toInt())
                                }
                                s = (y.bodyStart + y.contentSize).toInt()
                            }
                            if (track == videoTrack && cpos >= 0 && pos < 0) { pos = cpos; rel = crel }
                        }
                    }
                    q = (x.bodyStart + x.contentSize).toInt()
                }
                if (time >= 0 && pos >= 0) out.add(MkvCue(time, pos, rel))
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return out
    }

    /** MP4/MOV(ISO BMFF)网络取帧:解析 moov 采样表精确定位关键帧,只下载所需片段。 */
    private fun genFrameMp4(r: MediaMetadataRetriever, file: XFile, src: RandomSource, size: Long): Bitmap? {
        // moov(采样表等元数据)常见在文件头(faststart)或文件尾(边录边写、最后补
        // moov)。头部还承担第二个角色:不管 moov 在哪,时间 0 附近的关键帧数据都紧跟
        // 在文件开头 ftyp 之后,所以头部不能省(取不到 1/10 处时的兜底)。moov/mdat 的
        // 精确位置/大小用 [scanTopBoxes] 扫描 box 头拿到,再用采样表([findKeyframeOffset])
        // 精确定位目标时间点的关键帧字节偏移,只下载那一小段。
        var frame: Bitmap? = null
        run {
            val head = readAtCapped(src, 0, minOf(VIDEO_HEAD_CAP, size))
            if (head.isEmpty()) {
                Log.w("twig", "thumbs: video head empty ${file.name}")
                return null
            }
            val boxes = scanTopBoxes(src, size)
            val moov = boxes["moov"]
            val metaStart: Long
            val meta: ByteArray
            if (moov != null && moov.second in 1..VIDEO_MOOV_CAP) {
                metaStart = moov.first
                meta = readAtCapped(src, metaStart, moov.second)
            } else {
                metaStart = maxOf(0L, size - minOf(VIDEO_TAIL_FALLBACK_CAP, size))
                meta = readAtCapped(src, metaStart, size - metaStart)
                Log.w(
                    "twig",
                    "thumbs: video ${file.name} moov scan failed(size=${moov?.second}), " +
                        "fallback tail metaStart=$metaStart got=${meta.size}",
                )
            }

            // head + moov 预取进 NetVideoDataSource;目标关键帧窗口在拿到时长后再 pin。
            // 关键:未命中的位置回落定位读(不像之前 return -1 硬判 EOF)——4K/60fps 的
            // I 帧可能比预取窗口大(实测某 34GB DoVi mp4 关键帧超过 2MB 被截断就解不出),
            // 回落读能把超出窗口的关键帧字节补上;MP4 靠 moov 直接 seek(不像 MKV 顺序扫),
            // 回落读次数很少,仍然快。
            val ds = NetVideoDataSource(src, size, listOf(0L to head, metaStart to meta))
            r.setDataSource(ds)

            val durationMs = runCatching {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }.getOrNull()
            val mdat = boxes["mdat"]

            frame = pickRepresentativeFrame(r, file, durationMs) { targetUs ->
                val exact = findKeyframeOffset(meta, targetUs)
                val winStart: Long
                val winLen: Long
                if (exact != null) {
                    winStart = maxOf(0L, exact - VIDEO_KEYFRAME_MARGIN)
                    winLen = minOf(VIDEO_KEYFRAME_WINDOW, size - winStart)
                } else if (mdat != null) {
                    // 采样表解析失败:按 mdat 大小估算比例(兜底,不精确)
                    val targetByte = (mdat.first + mdat.second / VIDEO_FRAME_DIVISOR)
                        .coerceIn(mdat.first, mdat.first + mdat.second)
                    winStart = maxOf(mdat.first, targetByte - VIDEO_MID_WINDOW / 2)
                    winLen = minOf(VIDEO_MID_WINDOW, mdat.first + mdat.second - winStart)
                } else return@pickRepresentativeFrame
                ds.pin(winStart, readAtCapped(src, winStart, winLen))
            }
            Log.d(
                "twig",
                "thumbs: mp4 ${file.name} size=$size head=${head.size} moov=$moov " +
                    "durationMs=$durationMs frame=${frame != null} ${ds.stats()}",
            )
        }
        return frame
    }

    /** 非 MP4 容器(AVI 等,或 MKV 精确解析失败)网络取帧:纯随机访问,让 MMR 自己
     * 解封装 + seek(内部 MediaExtractor 原生支持 MKV)。 */
    private fun genFrameRandomAccess(r: MediaMetadataRetriever, file: XFile, src: RandomSource, size: Long): Bitmap? {
        val ds = NetVideoDataSource(src, size, emptyList())
        r.setDataSource(ds)
        val durationMs = durationMsOf(r)
        val t0 = SystemClock.elapsedRealtime()
        val frame = pickRepresentativeFrame(r, file, durationMs)
        Log.d(
            "twig",
            "thumbs: video ${file.name} random-access size=$size durationMs=$durationMs " +
                "frame=${frame != null} ms=${SystemClock.elapsedRealtime() - t0} ${ds.stats()}",
        )
        return frame
    }

    private fun durationMsOf(r: MediaMetadataRetriever): Long? = runCatching {
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }.getOrNull()

    /** 只服务文件头 [head] 的数据源(非 MP4 且来源无高效定位读时的兜底,基本只能取时间 0)。 */
    private fun headOnlySource(head: ByteArray, size: Long) = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            if (position >= head.size) return -1
            val n = minOf(len, (head.size - position).toInt())
            System.arraycopy(head, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = size
        override fun close() = Unit
    }

    /** 网络视频数据源:先服务预取的 [pinned] 内存段(MKV 精确路径的 init/Cues/目标
     * Cluster),未命中的位置回落到底层 [RandomSource] 定位读(256KB 块 LRU 缓存合并
     * 相邻/重复读)。这样即便 MMR 读到我们没预取的位置也不会因返回 -1(=EOF)而解码
     * 失败——之前 MKV 精确路径每次都 fallback 就是因为 MMR 会读三段之外的字节、被
     * 硬判 EOF。累计回落读取超过 [LIVE_CAP] 才中止(防无 Cues 的文件触发全文件扫描)。
     * [stats] 暴露未命中位置/回落字节,用于诊断"MMR 到底还需要哪些区间"。
     * close() 空实现——底层 src 由外层 use{} 关闭;readAt 加锁(MMR 回调不保证同线程)。 */
    private class NetVideoDataSource(
        private val src: RandomSource,
        private val totalSize: Long,
        pinned: List<Pair<Long, ByteArray>>,
    ) : MediaDataSource() {
        private val regions = ArrayList(pinned.filter { it.second.isNotEmpty() })
        /** 构造后追加一段预取(MP4 目标关键帧窗口在拿到时长/偏移后才下载)。 */
        fun pin(start: Long, data: ByteArray) = synchronized(lock) { if (data.isNotEmpty()) regions.add(start to data) }
        private val blockBits = 18
        private val blockSize = 1 shl blockBits
        private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
        }
        private var liveBytes = 0L
        private var missCount = 0
        private val missLog = StringBuilder()
        private val lock = Any()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = synchronized(lock) {
            if (position >= totalSize) return -1
            if (len <= 0) return 0
            for ((start, data) in regions) {
                if (position >= start && position < start + data.size) {
                    val off = (position - start).toInt()
                    val n = minOf(len, data.size - off)
                    System.arraycopy(data, off, buffer, offset, n)
                    return n
                }
            }
            // 未命中预取段:回落到定位读(块缓存)
            if (missCount < 20) missLog.append(position).append('+').append(len).append(' ')
            missCount++
            if (liveBytes > LIVE_CAP) return -1
            val blockIndex = position shr blockBits
            val block = cache[blockIndex] ?: run {
                val start = blockIndex shl blockBits
                val want = minOf(blockSize.toLong(), totalSize - start).toInt()
                val buf = ByteArray(want)
                var got = 0
                while (got < want) {
                    val n = src.readAt(start + got, buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                val b = if (got == want) buf else buf.copyOf(got)
                liveBytes += b.size
                cache[blockIndex] = b
                b
            }
            val within = (position - (blockIndex shl blockBits)).toInt()
            if (within >= block.size) return -1
            val n = minOf(len, block.size - within)
            System.arraycopy(block, within, buffer, offset, n)
            n
        }

        fun stats(): String = "miss=$missCount live=${liveBytes / 1024}KB firstMiss=[${missLog.trim()}]"

        override fun getSize(): Long = totalSize
        override fun close() = Unit

        companion object {
            private const val MAX_BLOCKS = 24 // 24 × 256KB = 6MB 滑动缓存窗口
            private const val LIVE_CAP = 160L * 1024 * 1024 // 回落读取上限,超了放弃
        }
    }

    // ---- MP4 box 树解析(仅用于从已下载的 moov 里精确定位关键帧字节偏移) ----

    private data class MBox(val type: String, val start: Int, val end: Int, val bodyStart: Int)

    /** 列出 [from, to) 范围内的直接子 box(只看头部,不递归)。 */
    private fun mBoxChildren(buf: ByteArray, from: Int, to: Int): List<MBox> {
        val list = ArrayList<MBox>()
        var pos = from
        while (pos + 8 <= to) {
            var size = readU32(buf, pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var bodyStart = pos + 8
            if (size == 1L) {
                if (pos + 16 > to) break
                size = readU64(buf, pos + 8)
                bodyStart = pos + 16
            } else if (size == 0L) {
                size = (to - pos).toLong()
            }
            if (size < 8) break
            val end = (pos + size).toInt().coerceAtMost(to)
            list.add(MBox(type, pos, end, bodyStart))
            pos += size.toInt()
        }
        return list
    }

    private fun readU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or (buf[off + 3].toLong() and 0xFF)

    private fun readU64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    /** [meta] 是完整的 moov box(含它自己的 8 字节头)。解析 trak/mdia/stbl 找到视频
     * 轨道的采样表(stts 时间->采样号、stss 同步采样表、stsc/stco(或 co64)采样->
     * chunk->文件偏移、stsz 采样大小),精确算出 [targetTimeUs] 最近的关键帧在文件
     * 里的真实字节偏移——用 ffprobe 抽真实关键帧位置核对过完全一致。结构不支持
     * (缺某个 box、多轨道选轨失败等)时返回 null,调用方退化成旧的比例估算兜底。 */
    private fun findKeyframeOffset(meta: ByteArray, targetTimeUs: Long): Long? = runCatching {
        val top = mBoxChildren(meta, 8, meta.size)
        val trak = top.filter { it.type == "trak" }.firstOrNull { isVideoTrak(meta, it) } ?: return null
        val mdia = mBoxChildren(meta, trak.bodyStart, trak.end).first { it.type == "mdia" }
        val mdiaChildren = mBoxChildren(meta, mdia.bodyStart, mdia.end)
        val mdhd = mdiaChildren.first { it.type == "mdhd" }
        val timescale = mdhdTimescale(meta, mdhd)
        val minf = mdiaChildren.first { it.type == "minf" }
        val stbl = mBoxChildren(meta, minf.bodyStart, minf.end).first { it.type == "stbl" }
        val stblChildren = mBoxChildren(meta, stbl.bodyStart, stbl.end)
        val stts = stblChildren.first { it.type == "stts" }
        val stsc = stblChildren.first { it.type == "stsc" }
        val stsz = stblChildren.firstOrNull { it.type == "stsz" }
        val stco = stblChildren.firstOrNull { it.type == "stco" }
        val co64 = stblChildren.firstOrNull { it.type == "co64" }
        val stss = stblChildren.firstOrNull { it.type == "stss" }
        if (stco == null && co64 == null) return null

        val targetSampleTime = targetTimeUs * timescale / 1_000_000L
        val sampleIndex1 = sampleIndexAtTime(meta, stts, targetSampleTime)
        val syncIndex1 = if (stss != null) nearestSyncSample(meta, stss, sampleIndex1) else sampleIndex1
        byteOffsetForSample(meta, stsc, stco, co64, stsz, syncIndex1)
    }.getOrNull()

    private fun isVideoTrak(meta: ByteArray, trak: MBox): Boolean = runCatching {
        val mdia = mBoxChildren(meta, trak.bodyStart, trak.end).first { it.type == "mdia" }
        val hdlr = mBoxChildren(meta, mdia.bodyStart, mdia.end).first { it.type == "hdlr" }
        String(meta, hdlr.bodyStart + 8, 4, Charsets.ISO_8859_1) == "vide"
    }.getOrDefault(false)

    /** mdhd(version 0 或 1)里的 timescale。 */
    private fun mdhdTimescale(meta: ByteArray, mdhd: MBox): Long {
        val version = meta[mdhd.bodyStart].toInt() and 0xFF
        val off = if (version == 1) mdhd.bodyStart + 20 else mdhd.bodyStart + 12
        return readU32(meta, off)
    }

    /** stts(time-to-sample):按 (sample_count, sample_delta) 累加,找到目标采样时间
     * 落在哪个采样上,返回 1-based 采样号。 */
    private fun sampleIndexAtTime(meta: ByteArray, stts: MBox, targetSampleTime: Long): Long {
        var p = stts.bodyStart + 4
        val count = readU32(meta, p).toInt()
        p += 4
        var idx = 1L
        var accum = 0L
        repeat(count) {
            val sampleCount = readU32(meta, p)
            val sampleDelta = readU32(meta, p + 4)
            p += 8
            if (sampleDelta == 0L) return idx
            val span = sampleCount * sampleDelta
            if (accum + span > targetSampleTime) {
                val extra = (targetSampleTime - accum) / sampleDelta
                return idx + minOf(extra, sampleCount - 1)
            }
            accum += span
            idx += sampleCount
        }
        return idx - 1
    }

    /** stss(sync sample table,1-based 升序采样号列表):找 <= 目标采样号里最大的一个
     * (即目标之前最近的关键帧);目标比第一个关键帧还早就用第一个。 */
    private fun nearestSyncSample(meta: ByteArray, stss: MBox, sampleIndex1: Long): Long {
        var p = stss.bodyStart + 4
        val count = readU32(meta, p).toInt()
        p += 4
        var best = -1L
        repeat(count) {
            val s = readU32(meta, p)
            p += 4
            if (s <= sampleIndex1) best = s else return if (best >= 0) best else s
        }
        return if (best >= 0) best else sampleIndex1
    }

    /** 按 stsc(采样->chunk 映射)+ stco/co64(chunk->文件偏移)+ stsz(采样大小)
     * 算出 [sampleIndex1](1-based)这个采样的绝对文件字节偏移。 */
    private fun byteOffsetForSample(
        meta: ByteArray,
        stsc: MBox,
        stco: MBox?,
        co64: MBox?,
        stsz: MBox?,
        sampleIndex1: Long,
    ): Long? {
        var p = stsc.bodyStart + 4
        val stscCount = readU32(meta, p).toInt()
        p += 4
        val firstChunks = LongArray(stscCount)
        val samplesPerChunks = LongArray(stscCount)
        for (i in 0 until stscCount) {
            firstChunks[i] = readU32(meta, p)
            samplesPerChunks[i] = readU32(meta, p + 4)
            p += 12
        }
        val totalChunks = if (stco != null) readU32(meta, stco.bodyStart + 4) else readU32(meta, co64!!.bodyStart + 4)

        var sampleCounter = 1L
        var chunkIndex1 = -1L
        var firstSampleInChunk = -1L
        loop@ for (i in 0 until stscCount) {
            val nextFirstChunk = if (i + 1 < stscCount) firstChunks[i + 1] else totalChunks + 1
            var c = firstChunks[i]
            while (c < nextFirstChunk) {
                if (sampleCounter + samplesPerChunks[i] > sampleIndex1) {
                    chunkIndex1 = c
                    firstSampleInChunk = sampleCounter
                    break@loop
                }
                sampleCounter += samplesPerChunks[i]
                c++
            }
        }
        if (chunkIndex1 < 0) return null

        val chunkOffset = if (stco != null) {
            readU32(meta, stco.bodyStart + 8 + ((chunkIndex1 - 1) * 4).toInt())
        } else {
            readU64(meta, co64!!.bodyStart + 8 + ((chunkIndex1 - 1) * 8).toInt())
        }

        var offsetInChunk = 0L
        if (stsz != null) {
            val sampleSize = readU32(meta, stsz.bodyStart + 4)
            offsetInChunk = if (sampleSize != 0L) {
                (sampleIndex1 - firstSampleInChunk) * sampleSize
            } else {
                val q = stsz.bodyStart + 12
                var acc = 0L
                for (s in firstSampleInChunk until sampleIndex1) acc += readU32(meta, q + ((s - 1) * 4).toInt())
                acc
            }
        }
        return chunkOffset + offsetInChunk
    }

    /** 从 [src] 的 [start] 起最多读 [cap] 字节(定位读版的 [readCapped])。 */
    private fun readAtCapped(src: RandomSource, start: Long, cap: Long): ByteArray {
        if (cap <= 0) return ByteArray(0)
        val bos = ByteArrayOutputStream(minOf(cap, NET_READ_CHUNK.toLong()).toInt())
        val buf = ByteArray(NET_READ_CHUNK)
        var total = 0L
        while (total < cap) {
            val n = src.readAt(start + total, buf, 0, minOf(buf.size.toLong(), cap - total).toInt())
            if (n <= 0) break
            bos.write(buf, 0, n)
            total += n
        }
        return bos.toByteArray()
    }

    /** 扫描顶层 box(ftyp/moov/mdat/free/…):只读 box 头(8 或 64 位大小扩展的 16 字节),
     * 用 box size 直接跳到下一个 box 头,不读 box 内容——代价是几次几十字节的定位读。
     * 返回 类型->(偏移,大小) 的映射(一次扫描顺带拿到 moov 和 mdat);结构异常时
     * 返回已扫到的部分(可能不含 moov/mdat)。 */
    private fun scanTopBoxes(src: RandomSource, fileSize: Long): Map<String, Pair<Long, Long>> {
        val result = HashMap<String, Pair<Long, Long>>()
        var pos = 0L
        var guard = 0
        while (pos + 8 <= fileSize && guard++ < 64) {
            val hdr = ByteArray(8)
            if (readAtExact(src, pos, hdr, 8) < 8) break
            var boxSize = ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or
                ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (boxSize == 1L) {
                val ext = ByteArray(8)
                if (readAtExact(src, pos + 8, ext, 8) < 8) break
                boxSize = ext.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                headerLen = 16L
            } else if (boxSize == 0L) {
                boxSize = fileSize - pos
            }
            if (boxSize < headerLen) break
            result[type] = pos to boxSize
            pos += boxSize
        }
        return result
    }

    private fun readAtExact(src: RandomSource, pos: Long, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = src.readAt(pos + total, buf, total, len - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    // ---- 音频封面 ----

    /** 同目录封面文件的候选主名(按优先级)与扩展名。 */
    private val COVER_STEMS = listOf("cover", "folder", "front", "albumart")
    private val COVER_EXTS = setOf("jpg", "jpeg", "png", "webp")
    private const val COVER_BYTES_CAP = 32L * 1024 * 1024 // 封面图大小上限,防止误配置指向巨大文件

    /** "scheme:目录路径" -> 同目录封面文件的原始字节(null = 确认没有,负结果也缓存);
     * 一个专辑目录几十首歌 / 播放器·通知·播放列表多个调用方只列目录 + 下载一次。缓存
     * 字节而非解码结果——文件管理器缩略图要 256px、播放器/通知/播放列表要 1024px
     * ([audioCover]),大小不同没法共用同一张位图,但都能从同一份字节各自解码,不用
     * 各自重新 list+下载。LRU 上限 32 个目录,只在会话内存有效。 */
    private val dirCoverBytes = object : LinkedHashMap<String, ByteArray?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray?>?) = size > 32
    }

    private fun resolveDirCoverBytes(file: XFile): ByteArray? {
        val dirKey = "${file.scheme}:${file.parentPath}"
        synchronized(dirCoverBytes) { if (dirCoverBytes.containsKey(dirKey)) return dirCoverBytes[dirKey] }
        val bytes = runCatching {
            val fs = FsRegistry.of(file)
            fs.list(XFile(file.scheme, file.parentPath, isDir = true))
                .filter {
                    !it.isDir && it.extension in COVER_EXTS &&
                        it.name.substringBeforeLast('.').lowercase() in COVER_STEMS
                }
                .sortedBy { COVER_STEMS.indexOf(it.name.substringBeforeLast('.').lowercase()) }
                .firstNotNullOfOrNull { c ->
                    runCatching { fs.openInput(c).use { readCapped(it, COVER_BYTES_CAP, c.size) } }.getOrNull()
                }
        }.getOrNull()
        synchronized(dirCoverBytes) { dirCoverBytes[dirKey] = bytes }
        return bytes
    }

    /** 音频封面:先取内嵌封面,没有再退回同目录封面图。MMR 对截断/慢速数据源可能
     * 卡住(无取消 API),和视频一样丢到 [videoExecutor] 上限时等待,不占 twig-thumbs 池。 */
    private fun genAudio(file: XFile): Bitmap? = try {
        videoExecutor.submit(Callable { genAudioBlocking(file) }).get(VIDEO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        Log.w("twig", "thumbs: audio timeout (${VIDEO_TIMEOUT_MS}ms) ${file.name}")
        null
    } catch (e: Exception) {
        Log.w("twig", "thumbs: audio failed ${file.name}: $e")
        null
    }

    private fun genAudioBlocking(file: XFile): Bitmap? {
        embeddedAudioCover(file)?.let { return it }
        return dirCover(file)
    }

    /** 全尺寸音频封面(内嵌 APIC/covr/PICTURE 优先,退同目录 cover 文件),供音乐播放器
     * 主界面/毛玻璃背景/通知([MusicService])/播放列表([PlaylistActivity])用。降采样到
     * [maxEdge] 而非缩略图的 256。三个调用方各自是**单线程**队列——MMR 卡住(无取消 API,
     * 和 [genVideo]/[genAudio] 同一风险,网络音频尤其容易撞上)会把那条队列永远堵死、
     * 后续所有曲目再也等不到封面(这是"加载封面很慢"背后真正的坑,不只是慢而是可能
     * 卡死),故和 [genAudio] 一样丢到 [videoExecutor] 限时等待,不再是裸阻塞调用。 */
    fun audioCover(file: XFile, maxEdge: Int = 1024): Bitmap? = try {
        videoExecutor.submit(Callable { audioCoverBlocking(file, maxEdge) }).get(VIDEO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        Log.w("twig", "thumbs: audioCover timeout (${VIDEO_TIMEOUT_MS}ms) ${file.name}")
        null
    } catch (e: Exception) {
        Log.w("twig", "thumbs: audioCover failed ${file.name}: $e")
        null
    }

    private fun audioCoverBlocking(file: XFile, maxEdge: Int): Bitmap? {
        val pic = runCatching {
            if (file.scheme == "file") {
                withRetriever { r -> r.setDataSource(file.path); r.embeddedPicture }
            } else {
                FsRegistry.of(file).openRandom(file).use { src ->
                    withRetriever { r ->
                        r.setDataSource(NetVideoDataSource(src, file.size, emptyList()))
                        r.embeddedPicture
                    }
                }
            }
        }.getOrNull()
        if (pic != null) decodeSampledTo({ ByteArrayInputStream(pic) }, maxEdge)?.let { return it }
        // 退回同目录 cover/folder/front/albumart——复用 [resolveDirCoverBytes] 的目录级缓存,
        // 同一目录内切歌不用重新 list+下载。
        return resolveDirCoverBytes(file)?.let { b -> decodeSampledTo({ ByteArrayInputStream(b) }, maxEdge) }
    }

    private fun decodeSampledTo(open: () -> InputStream, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { open().use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
    }

    /** MMR 取内嵌封面(mp3 ID3 APIC / FLAC PICTURE / m4a covr)。本地直接给路径;
     * 其它来源给随机访问数据源让 MMR 自己按需读——mp3/flac 的封面都在文件头,m4a 的
     * covr 在 moov(可能在尾部),[NetVideoDataSource] 的块缓存 + 读取上限约束流量。 */
    private fun embeddedAudioCover(file: XFile): Bitmap? {
        val pic = runCatching {
            if (file.scheme == "file") {
                withRetriever { r ->
                    r.setDataSource(file.path)
                    r.embeddedPicture
                }
            } else {
                FsRegistry.of(file).openRandom(file).use { src ->
                    withRetriever { r ->
                        r.setDataSource(NetVideoDataSource(src, file.size, emptyList()))
                        r.embeddedPicture
                    }
                }
            }
        }.getOrNull() ?: return null
        return decodeSampled { ByteArrayInputStream(pic) }
    }

    /** 同目录封面图(256px 缩略图用),字节来自 [resolveDirCoverBytes] 的目录级缓存。 */
    private fun dirCover(file: XFile): Bitmap? =
        resolveDirCoverBytes(file)?.let { b -> decodeSampled { ByteArrayInputStream(b) } }

    private fun genPdf(file: XFile): Bitmap? {
        if (file.scheme != "file") return null
        ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val scale = MAX_EDGE.toFloat() / maxOf(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.setHasAlpha(false)
                    return bmp
                }
            } finally {
                renderer.close()
            }
        }
    }

    // ---- 解码工具 ----

    private fun decodeSampled(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { open().use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
    }

    /** 从 [ins] 最多读 [cap] 字节,用 [NET_READ_CHUNK] 大缓冲区——网络流专用,减少往返。 */
    private fun readCapped(ins: InputStream, cap: Long, sizeHint: Long = 0L): ByteArray {
        val bos = ByteArrayOutputStream(if (sizeHint in 1..cap) sizeHint.toInt() else NET_READ_CHUNK)
        val buf = ByteArray(NET_READ_CHUNK)
        var total = 0L
        while (total < cap) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), cap - total).toInt())
            if (n < 0) break
            bos.write(buf, 0, n)
            total += n
        }
        return bos.toByteArray()
    }

    private fun head(file: XFile): ByteArray? = runCatching {
        FsRegistry.of(file).openInput(file).use { ins ->
            val cap = if (file.size in 1 until HEAD_BYTES.toLong()) file.size.toInt() else HEAD_BYTES
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val n = ins.read(buf, off, cap - off)
                if (n < 0) break
                off += n
            }
            if (off == cap) buf else buf.copyOf(off)
        }
    }.getOrNull()

    private fun orientationOf(headBytes: ByteArray?): Int {
        if (headBytes == null) return 0
        return runCatching {
            orientationDegrees(ExifInterface(ByteArrayInputStream(headBytes)))
        }.getOrDefault(0)
    }

    private fun orientationDegrees(exif: ExifInterface): Int = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun scaleTo(bmp: Bitmap): Bitmap {
        val edge = maxOf(bmp.width, bmp.height)
        if (edge <= MAX_EDGE) return bmp
        val s = MAX_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt().coerceAtLeast(1),
            (bmp.height * s).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_CAP * 8 / 10) break
        }
    }
}
