package com.twig.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.ByteArrayInputStream

/**
 * 属性卡片的数据源:基本信息 + 打开方式(默认应用/可打开应用)+
 * 按类型追加 EXIF(图片)/ 媒体信息(音视频)/ 应用信息(apk)。
 * 全部走系统 API(ExifInterface / MediaMetadataRetriever / PackageManager),零额外依赖;
 * 非本地来源经 openInput 流或 StreamProvider 的 content URI 读取,apk 需物化到缓存。
 * 阻塞 IO,须在工作线程调用。
 */
object FileInfo {

    /** 取字符串资源(随 AppCompatDelegate 当前 locale);这一层的文案全部走资源。 */
    private fun s(ctx: Context, id: Int, vararg args: Any): String = ctx.getString(id, *args)

    data class Section(val title: String, val rows: List<Pair<String, String>>)
    data class Details(val sections: List<Section>, val error: String? = null)

    fun load(ctx: Context, file: XFile): Details {
        val sections = ArrayList<Section>()
        sections += basic(ctx, file)
        if (!file.isDir) {
            when {
                OpenFiles.isImage(file) -> exif(ctx, file)?.let { sections += it }
                OpenFiles.isVideo(file) || OpenFiles.isAudio(file) ->
                    media(ctx, file)?.let { sections += it }
                // 「应用」树里的条目:直接问 PackageManager,连文件都不用碰
                file.scheme == AppsFileSystem.SCHEME ->
                    installedApp(ctx, file)?.let { sections += it }
                // 远程 apk 要整包下载才能解析,不显示(需完整读取的属性一律略过)
                OpenFiles.isApk(file) && file.scheme == "file" ->
                    apk(ctx, file)?.let { sections += it }
            }
        }
        return Details(sections)
    }

    // ---- 基本 ----

    private fun basic(ctx: Context, file: XFile): Section {
        val rows = ArrayList<Pair<String, String>>()
        rows += s(ctx, R.string.info_name) to file.name
        rows += s(ctx, R.string.info_path) to file.path
        if (file.scheme != "file") rows += s(ctx, R.string.info_source) to Format.schemeLabel(file.scheme)
        if (file.isDir) {
            rows += s(ctx, R.string.info_type) to s(ctx, R.string.info_folder)
            runCatching { FsRegistry.of(file).list(file) }.getOrNull()?.let { kids ->
                val dirs = kids.count { it.isDir }
                rows += s(ctx, R.string.info_contains) to
                    s(ctx, R.string.info_contains_value, dirs, kids.size - dirs)
            }
        } else {
            rows += s(ctx, R.string.info_type) to OpenFiles.mimeOf(file.name)
            rows += s(ctx, R.string.info_size) to
                s(ctx, R.string.info_size_value, Format.size(file.size), "%,d".format(file.size))
        }
        if (file.lastModified > 0) rows += s(ctx, R.string.info_modified) to Format.time(file.lastModified)
        rows += s(ctx, R.string.info_writable) to
            s(ctx, if (file.canWrite) R.string.info_yes else R.string.info_no)
        if (!file.isDir) rows += apps(ctx, file) // 打开方式并入基本信息
        return Section(s(ctx, R.string.info_section_basic), rows)
    }

    /**
     * 目录的递归统计行(追加在"基本"分组末尾):全部文件/目录数 + 总大小。
     * 数据由 `PaneViewModel` 的后台扫描边扫边给([com.twig.app.ui.DirStat]),
     * 所以不在 [load] 里算——那是一次性的,而这两行要随扫描进度实时变。
     */
    fun dirStatRows(ctx: Context, stat: com.twig.app.ui.DirStat): List<Pair<String, String>> = listOf(
        s(ctx, R.string.info_contains_all) to
            s(ctx, R.string.info_contains_value, stat.dirs, stat.files),
        s(ctx, R.string.info_total_size) to
            s(ctx, R.string.info_size_value, Format.size(stat.bytes), "%,d".format(stat.bytes)),
    )

    // ---- 打开方式 ----

    private fun apps(ctx: Context, file: XFile): List<Pair<String, String>> = runCatching {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(StreamProvider.uriFor(ctx, file), OpenFiles.mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val rows = ArrayList<Pair<String, String>>()
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?.takeIf { it.packageName != "android" } // 无默认时系统返回解析器自身
            ?.let { rows += s(ctx, R.string.info_default_app) to it.loadLabel(pm).toString() }
        rows
    }.getOrDefault(emptyList())

    // ---- 图片 EXIF ----

    /**
     * 读文件头(最多 [IMAGE_HEAD_CAP];文件比它小就是整个文件)。
     *
     * ★ EXIF **必须**从内存字节解析,不能把网络流直接交给 [ExifInterface]:
     * 它解 APP1 段时是 `if (in.read(bytes) != length) throw IOException("Invalid exif")`
     * ——单次 read。而 `BufferedInputStream.read(b,off,len)` 的填充循环遇到
     * `in.available() <= 0` 就提前返回:本地 `FileInputStream` 一次给得满,
     * SMB/SFTP/WebDAV 这类 socket 流给的是部分字节 → 长度对不上 → 异常被
     * `loadAttributes` 内部吞掉 → 属性全空。表现就是"服务器上的图片没有 EXIF"。
     * `ByteArrayInputStream.available()` 恒等于剩余字节,永远读得满。
     * 宽高解码顺带复用这段字节:头部就够出 SOF,不必再开一次流。
     */
    private fun imageHead(file: XFile): ByteArray {
        val cap = if (file.size in 1 until IMAGE_HEAD_CAP.toLong()) file.size.toInt() else IMAGE_HEAD_CAP
        return FsRegistry.of(file).openInput(file).use { ins ->
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val n = ins.read(buf, off, cap - off)
                if (n < 0) break
                off += n
            }
            if (off == cap) buf else buf.copyOf(off)
        }
    }

    /** EXIF(APP1 单段上限 64KB)+ JPEG SOF 都在这以内;整文件更小时读的就是整个文件。 */
    private const val IMAGE_HEAD_CAP = 256 * 1024

    private fun exif(ctx: Context, file: XFile): Section? {
        val rows = ArrayList<Pair<String, String>>()
        val head = runCatching { imageHead(file) }.getOrNull() ?: return null
        runCatching {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(head, 0, head.size, opt)
            if (opt.outWidth > 0) rows += s(ctx, R.string.info_dimensions) to "${opt.outWidth} × ${opt.outHeight}"
        }
        runCatching {
            val ex = ExifInterface(ByteArrayInputStream(head))
            fun tag(t: String) = ex.getAttribute(t)?.takeIf { it.isNotBlank() }
            (tag(ExifInterface.TAG_DATETIME_ORIGINAL) ?: tag(ExifInterface.TAG_DATETIME))
                ?.let { rows += s(ctx, R.string.info_taken_at) to it }
            listOfNotNull(tag(ExifInterface.TAG_MAKE), tag(ExifInterface.TAG_MODEL))
                .joinToString(" ").takeIf { it.isNotBlank() }?.let { rows += s(ctx, R.string.info_camera) to it }
            tag(ExifInterface.TAG_F_NUMBER)?.let { rows += s(ctx, R.string.info_aperture) to "f/$it" }
            tag(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { t ->
                rows += s(ctx, R.string.info_shutter) to if (t >= 1) "${t}s" else "1/${Math.round(1 / t)}s"
            }
            tag(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { rows += "ISO" to it }
            tag(ExifInterface.TAG_FOCAL_LENGTH)?.let { r ->
                val mm = r.split('/').let {
                    if (it.size == 2) it[0].toDouble() / it[1].toDouble() else r.toDoubleOrNull()
                }
                if (mm != null) rows += s(ctx, R.string.info_focal) to "%.1f mm".format(mm)
            }
            when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> "90°"
                ExifInterface.ORIENTATION_ROTATE_180 -> "180°"
                ExifInterface.ORIENTATION_ROTATE_270 -> "270°"
                else -> null
            }?.let { rows += s(ctx, R.string.info_rotation) to it }
            val ll = FloatArray(2)
            if (ex.getLatLong(ll)) rows += "GPS" to "%.6f, %.6f".format(ll[0], ll[1])
        }
        return if (rows.isEmpty()) null else Section(s(ctx, R.string.info_section_image), rows)
    }

    // ---- 音视频 ----

    private fun media(ctx: Context, file: XFile): Section? {
        val rows = ArrayList<Pair<String, String>>()
        // 轨道明细(编码/音轨/字幕)用 app 里已有的 ExoPlayer 解析器枚举,
        // 比 MediaMetadataRetriever 全得多(MKV 字幕轨、DTS/TrueHD 等系统不认的轨也能列)
        val tracks = mediaTracks(ctx, file)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, StreamProvider.uriFor(ctx, file))
            fun k(key: Int) = mmr.extractMetadata(key)?.takeIf { it.isNotBlank() }
            k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.let { rows += s(ctx, R.string.info_duration) to duration(it) }
            k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { bps ->
                rows += s(ctx, R.string.info_bitrate) to if (bps >= 1_000_000) {
                    "%.1f Mbps".format(bps / 1_000_000.0)
                } else "${bps / 1000} kbps"
            }
            k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { rows += s(ctx, R.string.info_container) to it }
            if (tracks.isEmpty()) { // 轨道枚举失败才退回系统摘要字段
                val w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (w != null && h != null) rows += s(ctx, R.string.info_resolution) to "$w × $h"
                if (Build.VERSION.SDK_INT >= 31) {
                    k(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.let { rows += s(ctx, R.string.info_samplerate) to "$it Hz" }
                }
            }
            k(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.takeIf { it != "0" }?.let { rows += s(ctx, R.string.info_rotation) to "$it°" }
            rows += tracks
            k(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { rows += s(ctx, R.string.info_title) to it }
            k(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { rows += s(ctx, R.string.info_artist) to it }
            k(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { rows += s(ctx, R.string.info_album) to it }
        } catch (e: Exception) {
            rows += tracks // 系统解析不了的容器(如部分 MKV)仍给轨道信息
        } finally {
            runCatching { mmr.release() }
        }
        return if (rows.isEmpty()) null else Section(s(ctx, R.string.info_section_media), rows)
    }

    /** 用 ExoPlayer 的 MetadataRetriever 枚举视频/音频/字幕轨(零新依赖)。 */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mediaTracks(ctx: Context, file: XFile): List<Pair<String, String>> = runCatching {
        val tga = androidx.media3.exoplayer.MetadataRetriever.retrieveMetadata(
            ctx, androidx.media3.common.MediaItem.fromUri(StreamProvider.uriFor(ctx, file)),
        ).get(20, java.util.concurrent.TimeUnit.SECONDS)
        val video = ArrayList<String>()
        val audio = ArrayList<String>()
        val subs = ArrayList<String>()
        for (i in 0 until tga.length) {
            val f = tga.get(i).getFormat(0)
            val mime = f.sampleMimeType ?: continue
            val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { "[$it]" }
            val label = f.label?.takeIf { it.isNotBlank() }
            when {
                mime.startsWith("video/") -> video += buildList {
                    add(codecName(mime))
                    f.codecs?.let { add("($it)") }
                    if (f.width > 0 && f.height > 0) add("${f.width}×${f.height}")
                    if (f.frameRate > 0) add("%.4g fps".format(f.frameRate))
                }.joinToString(" ")
                mime.startsWith("audio/") -> audio += buildList {
                    add(codecName(mime))
                    if (f.channelCount > 0) add(channels(ctx, f.channelCount))
                    if (f.sampleRate > 0) add("${f.sampleRate} Hz")
                    lang?.let { add(it) }
                    label?.let { add(it) }
                }.joinToString(" ")
                SUB_MIME.containsKey(mime) || mime.startsWith("text/") -> subs += buildList {
                    add(codecName(mime))
                    lang?.let { add(it) }
                    label?.let { add(it) }
                }.joinToString(" ")
                // 其余 application/*(id3/emsg 等容器元数据轨)不展示
            }
        }
        val rows = ArrayList<Pair<String, String>>()
        fun put(name: String, list: List<String>) = list.forEachIndexed { i, v ->
            rows += (if (list.size > 1) "$name ${i + 1}" else name) to v
        }
        put(s(ctx, R.string.info_track_video), video)
        put(s(ctx, R.string.info_track_audio), audio)
        put(s(ctx, R.string.info_track_subtitle), subs)
        rows
    }.getOrDefault(emptyList())

    private val SUB_MIME = mapOf(
        "application/x-subrip" to "SRT",
        "text/x-ssa" to "ASS/SSA",
        "text/vtt" to "WebVTT",
        "application/ttml+xml" to "TTML",
        "application/pgs" to "PGS",
        "application/vobsub" to "VobSub",
        "application/dvbsubs" to "DVB",
        "application/cea-608" to "CEA-608",
        "application/cea-708" to "CEA-708",
    )

    private val CODEC_NAME = mapOf(
        "video/avc" to "H.264",
        "video/hevc" to "H.265",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/av01" to "AV1",
        "video/mpeg2" to "MPEG-2",
        "video/mp4v-es" to "MPEG-4",
        "video/3gpp" to "H.263",
        "video/dolby-vision" to "Dolby Vision",
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/mpeg-L2" to "MP2",
        "audio/ac3" to "AC-3",
        "audio/eac3" to "E-AC-3",
        "audio/eac3-joc" to "E-AC-3 JOC",
        "audio/ac4" to "AC-4",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/vnd.dts.hd;profile=lbr" to "DTS Express",
        "audio/true-hd" to "TrueHD",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/raw" to "PCM",
        "audio/g711-alaw" to "G.711 A-law",
        "audio/g711-mlaw" to "G.711 μ-law",
        "audio/3gpp" to "AMR-NB",
        "audio/amr-wb" to "AMR-WB",
    )

    /** 编码简称,比如 "audio/vnd.dts" → "DTS"——播放器音轨菜单也用这份映射,保持一致。 */
    fun codecName(mime: String): String =
        CODEC_NAME[mime] ?: SUB_MIME[mime] ?: mime.substringAfter('/').uppercase()

    /** 声道数简称,比如 6 → "5.1"。 */
    fun channels(ctx: Context, n: Int): String = when (n) {
        1 -> s(ctx, R.string.info_channel_mono)
        2 -> s(ctx, R.string.info_channel_stereo)
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> s(ctx, R.string.info_channel_n, n)
    }

    private fun duration(ms: Long): String {
        val t = ms / 1000
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---- 哈希(整文件读取,按需计算)----

    fun hashes(file: XFile): List<Pair<String, String>> {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val crc = java.util.zip.CRC32()
        FsRegistry.of(file).openInput(file).use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n)
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                crc.update(buf, 0, n)
            }
        }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        return listOf(
            "CRC32" to "%08x".format(crc.value),
            "MD5" to hex(md5.digest()),
            "SHA-1" to hex(sha1.digest()),
            "SHA-256" to hex(sha256.digest()),
        )
    }

    // ---- APK(仅本地:直接读路径,不用整包物化)----

    private fun apk(ctx: Context, file: XFile): Section? = runCatching {
        val path = file.path
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(path, 0) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path
        val rows = ArrayList<Pair<String, String>>()
        rows += s(ctx, R.string.info_app_name) to pm.getApplicationLabel(ai).toString()
        rows += s(ctx, R.string.info_package) to pi.packageName
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        rows += s(ctx, R.string.info_version) to "${pi.versionName ?: "?"}($vc)"
        rows += "SDK" to "min ${ai.minSdkVersion} / target ${ai.targetSdkVersion}"
        rows += s(ctx, R.string.info_installed) to (
            runCatching { pm.getPackageInfo(pi.packageName, 0) }.getOrNull()
                ?.let { s(ctx, R.string.info_installed_yes, it.versionName ?: "?") }
                ?: s(ctx, R.string.info_no)
            )
        Section(s(ctx, R.string.info_section_app), rows)
    }.getOrNull()

    /**
     * 已安装应用的信息(「应用」树条目专用)。与 [apk] 不同,这里的应用**已经装在机器上**,
     * 全部字段都来自 PackageManager 的元数据,不读 apk 一个字节 —— 分包应用也就顺带能显示
     * split 数量与总占用(它们正是复制出去时打进 XAPK 的那几个文件)。
     */
    private fun installedApp(ctx: Context, file: XFile): Section? = runCatching {
        val fs = runCatching { FsRegistry.of(file) }.getOrNull() as? AppsFileSystem ?: return null
        val pkg = fs.packageOf(file) ?: return null
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(pkg, 0)
        val ai = pi.applicationInfo ?: return null
        val rows = ArrayList<Pair<String, String>>()
        rows += s(ctx, R.string.info_app_name) to pm.getApplicationLabel(ai).toString()
        rows += s(ctx, R.string.info_package) to pkg
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        rows += s(ctx, R.string.info_version) to "${pi.versionName ?: "?"}($vc)"
        rows += "SDK" to "min ${ai.minSdkVersion} / target ${ai.targetSdkVersion}"
        rows += s(ctx, R.string.info_type) to s(
            ctx,
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) R.string.info_app_system
            else R.string.info_app_user,
        )
        rows += s(ctx, R.string.info_install_time) to Format.time(pi.firstInstallTime)
        if (pi.lastUpdateTime != pi.firstInstallTime) {
            rows += s(ctx, R.string.info_update_time) to Format.time(pi.lastUpdateTime)
        }
        val splits = ai.splitSourceDirs?.size ?: 0
        if (splits > 0) rows += s(ctx, R.string.info_splits) to s(ctx, R.string.info_splits_value, splits)
        rows += s(ctx, R.string.info_apk_path) to (ai.sourceDir ?: "?")
        rows += s(ctx, R.string.info_data_dir) to (ai.dataDir ?: "?")
        Section(s(ctx, R.string.info_section_app), rows)
    }.getOrNull()
}
