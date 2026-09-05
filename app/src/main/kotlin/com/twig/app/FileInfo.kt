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
import com.twig.core.MediaDetails
import com.twig.core.MediaInfoSource
import com.twig.core.MediaStream
import com.twig.core.XFile
import java.io.ByteArrayInputStream

/**
 * Data source for the properties card: basic info + open-with (default app / available apps) +
 * EXIF (images) / media info (audio & video) / app info (apk) appended by type.
 * All via system APIs (ExifInterface / MediaMetadataRetriever / PackageManager), zero extra
 * dependencies; non-local sources are read via openInput stream or StreamProvider's content
 * URI, apk needs to be materialised into cache. Blocking I/O, must be called on a worker thread.
 */
object FileInfo {

    /** Fetch the string resource (following AppCompatDelegate's current locale); all copy at this layer goes through resources. */
    private fun s(ctx: Context, id: Int, vararg args: Any): String = ctx.getString(id, *args)

    data class Section(val title: String, val rows: List<Pair<String, String>>)
    data class Details(val sections: List<Section>, val error: String? = null)

    fun load(ctx: Context, file: XFile): Details {
        val sections = ArrayList<Section>()
        // Prefer the source's own media details (Jellyfin/Emby) when available: those data
        // already come in the listing response, whereas the default path requires
        // MediaMetadataRetriever to **actually read the file** — on remote sources that takes
        // seconds to tens of seconds, and the properties card would stay blank and spinning.
        val remote = if (file.isDir) {
            null
        } else {
            runCatching { (FsRegistry.of(file) as? MediaInfoSource)?.detailsOf(file) }.getOrNull()
        }
        sections += basic(ctx, file, remote)
        if (!file.isDir) {
            when {
                remote != null -> remoteMedia(ctx, remote)?.let { sections += it }
                OpenFiles.isImage(file) -> exif(ctx, file)?.let { sections += it }
                OpenFiles.isVideo(file) || OpenFiles.isAudio(file) ->
                    media(ctx, file)?.let { sections += it }
                // Entries in the "Apps" tree: ask PackageManager directly, no need to touch files
                file.scheme == AppsFileSystem.SCHEME ->
                    installedApp(ctx, file)?.let { sections += it }
                // Remote apks need to be downloaded entirely to parse, skip (skip any property requiring a full read)
                OpenFiles.isApk(file) && file.scheme == "file" ->
                    apk(ctx, file)?.let { sections += it }
            }
        }
        return Details(sections)
    }

    // ---- Basic ----

    private fun basic(ctx: Context, file: XFile, remote: MediaDetails? = null): Section {
        val rows = ArrayList<Pair<String, String>>()
        rows += s(ctx, R.string.info_name) to file.name
        // Media server paths are GUIDs, useless to show — when the server has a real path, show that
        rows += s(ctx, R.string.info_path) to remote?.realPath?.ifEmpty { null }.orEmpty().ifEmpty { file.path }
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
            // XFile.size in the listing may be 0 (media servers don't expose photo byte counts),
            // but the properties card only deals with one entry and the source will fetch it
            // specifically — use the real value when available
            val bytes = remote?.size?.takeIf { it > 0 } ?: file.size
            if (bytes > 0 || Format.sizeOrNull(file) != null) {
                rows += s(ctx, R.string.info_size) to
                    s(ctx, R.string.info_size_value, Format.size(bytes), "%,d".format(bytes))
            }
        }
        if (file.lastModified > 0) rows += s(ctx, R.string.info_modified) to Format.time(file.lastModified)
        rows += s(ctx, R.string.info_writable) to
            s(ctx, if (file.canWrite) R.string.info_yes else R.string.info_no)
        if (!file.isDir) rows += apps(ctx, file) // open-with merged into basic info
        return Section(s(ctx, R.string.info_section_basic), rows)
    }

    /**
     * Recursive stats rows for a directory (appended at the end of the "Basic" section):
     * total file / directory count + total size.
     * Data is fed incrementally by `PaneViewModel`'s background scan
     * ([com.twig.app.ui.DirStat]), so we don't compute it here in [load] — that runs once,
     * whereas these two rows must update live with scan progress.
     */
    fun dirStatRows(ctx: Context, stat: com.twig.app.ui.DirStat): List<Pair<String, String>> = listOf(
        s(ctx, R.string.info_contains_all) to
            s(ctx, R.string.info_contains_value, stat.dirs, stat.files),
        s(ctx, R.string.info_total_size) to
            s(ctx, R.string.info_size_value, Format.size(stat.bytes), "%,d".format(stat.bytes)),
    )

    // ---- Open with ----

    private fun apps(ctx: Context, file: XFile): List<Pair<String, String>> = runCatching {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(StreamProvider.uriFor(ctx, file), OpenFiles.mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val rows = ArrayList<Pair<String, String>>()
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?.takeIf { it.packageName != "android" } // system returns the resolver itself when there's no default
            ?.let { rows += s(ctx, R.string.info_default_app) to it.loadLabel(pm).toString() }
        rows
    }.getOrDefault(emptyList())

    // ---- Image EXIF ----

    /**
     * Read the file head (at most [IMAGE_HEAD_CAP]; for files smaller than that, the whole file).
     *
     * ★ EXIF **must** be parsed from in-memory bytes; the network stream cannot be passed
     * straight to [ExifInterface]: when it decodes the APP1 segment, it does
     * `if (in.read(bytes) != length) throw IOException("Invalid exif")` — a single read.
     * And `BufferedInputStream.read(b,off,len)`'s fill loop returns early when
     * `in.available() <= 0`: a local `FileInputStream` fills in one go, but SMB/SFTP/WebDAV
     * socket streams return a partial read → length mismatches → the exception is swallowed
     * inside `loadAttributes` → properties are empty. The symptom is "no EXIF on server-side
     * images". `ByteArrayInputStream.available()` always equals the remaining bytes, so it
     * always fills.
     * Width / height decoding reuses those same bytes: the head is enough for the SOF, no
     * need to open another stream.
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

    /** EXIF (APP1 single-segment limit 64KB) + JPEG SOF all fit within this; for files smaller than the cap, the read returns the whole file. */
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

    // ---- Audio & Video ----

    private fun media(ctx: Context, file: XFile): Section? {
        val rows = ArrayList<Pair<String, String>>()
        // Track details (codec / audio track / subtitle) enumerated via the ExoPlayer parser
        // already in the app — much fuller than MediaMetadataRetriever (MKV subtitle tracks,
        // DTS/TrueHD and other system-unknown tracks are also listed)
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
            if (tracks.isEmpty()) { // fall back to system summary fields only when track enumeration fails
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
            rows += tracks // containers the system can't parse (e.g. some MKV) still get track info
        } finally {
            runCatching { mmr.release() }
        }
        return if (rows.isEmpty()) null else Section(s(ctx, R.string.info_section_media), rows)
    }

    /**
     * Media details returned directly by the source ([MediaInfoSource]); the row layout
     * matches [media] — the user shouldn't be able to infer "this used a different path"
     * just from the properties card looking different.
     *
     * No network request is made (the data is already in the listing response), so the
     * properties card for remote files opens **instantly**; the `MediaMetadataRetriever`
     * path actually has to read the file bytes.
     */
    private fun remoteMedia(ctx: Context, d: MediaDetails): Section? {
        val rows = ArrayList<Pair<String, String>>()
        if (d.durationMs > 0) rows += s(ctx, R.string.info_duration) to duration(d.durationMs)
        if (d.bitrate > 0) rows += s(ctx, R.string.info_bitrate) to bitrate(d.bitrate)
        if (d.container.isNotEmpty()) rows += s(ctx, R.string.info_container) to d.container
        // Photos have no video stream, dimensions are at the top level; MediaDetails provides both uniformly
        if (d.width > 0 && d.height > 0) {
            rows += s(ctx, R.string.info_resolution) to "${d.width} × ${d.height}"
        }
        fun describe(st: MediaStream): String = buildList {
            // The server-assembled phrase (e.g. "1080p H264", "English DTS-HD MA 5.1") is most
            // complete; prefer it
            st.title.takeIf { it.isNotBlank() }?.let { add(it) }
                ?: st.codec.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            st.language.takeIf { it.isNotBlank() && it != "und" && st.title.isBlank() }?.let { add("[$it]") }
            if (st.kind == MediaStream.Kind.VIDEO && st.frameRate > 0) add("%.3g fps".format(st.frameRate))
            if (st.kind == MediaStream.Kind.AUDIO && st.channels > 0) add(channels(ctx, st.channels))
            if (st.bitrate > 0) add(bitrate(st.bitrate))
        }.joinToString(" ")
        fun put(nameRes: Int, kind: MediaStream.Kind) {
            val list = d.streams.filter { it.kind == kind }
            list.forEachIndexed { i, st ->
                val name = s(ctx, nameRes).let { if (list.size > 1) "$it ${i + 1}" else it }
                rows += name to describe(st)
            }
        }
        put(R.string.info_track_video, MediaStream.Kind.VIDEO)
        put(R.string.info_track_audio, MediaStream.Kind.AUDIO)
        put(R.string.info_track_subtitle, MediaStream.Kind.SUBTITLE)
        return if (rows.isEmpty()) null else Section(s(ctx, R.string.info_section_media), rows)
    }

    private fun bitrate(bps: Long): String =
        if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1_000_000.0) else "${bps / 1000} kbps"

    /** Enumerate video/audio/subtitle tracks using ExoPlayer's MetadataRetriever (zero new dependencies). */
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
                // Other application/* (id3/emsg etc. container metadata tracks) are not displayed
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

    /** Codec short name, e.g. "audio/vnd.dts" → "DTS" — the player's audio track menu uses the same map to stay consistent. */
    fun codecName(mime: String): String =
        CODEC_NAME[mime] ?: SUB_MIME[mime] ?: mime.substringAfter('/').uppercase()

    /** Channel count short form, e.g. 6 → "5.1". */
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

    // ---- Hashes (full file read, computed on demand) ----

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

    // ---- APK (local only: read path directly, no need to materialise the whole archive) ----

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
     * Info for an installed app (used only by "Apps" tree entries). Unlike [apk], the app here
     * **is already installed**, so all fields come from PackageManager's metadata without
     * reading a single byte of the apk — split apps also get the split count and total size
     * for free (they are exactly the files that get bundled into XAPK when copying out).
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
