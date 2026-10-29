package com.twig.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaDataSource
import android.os.Build
import com.twig.core.FsRegistry
import com.twig.core.MediaDetails
import com.twig.core.MediaInfoSource
import com.twig.core.MediaStream
import com.twig.core.XFile
import com.twig.core.UnixMetadata
import com.twig.core.UnixMetadataSource
import com.twig.core.UnixPermissions
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

    data class Section(
        val title: String,
        val rows: List<Pair<String, String>>,
        val appGroups: List<AppDetails.Group> = emptyList(),
        val loading: Boolean = false,
    )
    data class Details(val sections: List<Section>, val error: String? = null)

    /** Visible page order; null section denotes the lazily computed Hash page. */
    data class Tab(val title: String, val section: Int?)

    fun tabs(ctx: Context, file: XFile, details: Details?, selection: Boolean = false): List<Tab> {
        val sections = details?.sections.orEmpty()
        if (sections.isEmpty()) return emptyList()
        val pages = sections.mapIndexed { index, section -> Tab(section.title, index) }
        val unixTitle = s(ctx, R.string.info_section_unix)
        return buildList {
            addAll(pages.filter { it.title != unixTitle })
            if (!selection && !file.isDir && file.canRead) add(Tab(s(ctx, R.string.info_tab_hash), null))
            addAll(pages.filter { it.title == unixTitle })
        }
    }

    /** Aggregate fields use the same Basic tab and row renderer as single-item properties. */
    fun selection(ctx: Context, files: List<XFile>, stat: com.twig.app.ui.DirStat): Details {
        val dirs = files.count { it.isDir }
        val rows = buildList {
            add(s(ctx, R.string.info_selected) to files.size.toString())
            add(s(ctx, R.string.info_contains) to s(ctx, R.string.info_contains_value, dirs, files.size - dirs))
            if (dirs > 0) add(s(ctx, R.string.info_contains_all) to s(ctx, R.string.info_contains_value, stat.dirs, stat.files))
            add(s(ctx, R.string.info_total_size) to s(ctx, R.string.info_size_value, Format.size(stat.bytes), "%,d".format(stat.bytes)))
        }
        return Details(listOf(Section(s(ctx, R.string.info_section_basic), rows)))
    }

    /** Listing metadata first; no file reads or directory listings before the card appears. */
    fun initial(ctx: Context, file: XFile): Details = Details(buildList {
        add(basic(ctx, file, includeContents = false))
        if (!file.isDir && file.canRead && (OpenFiles.isVideo(file) || OpenFiles.isAudio(file)))
            add(Section(s(ctx, R.string.info_section_media), emptyList(), loading = true))
        if (supportsUnix(file)) add(unixSection(ctx, file, null).copy(loading = true))
    })

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
        val inspected = if (file.isSymbolicLink && file.scheme == "file") {
            file.copy(linkTarget = (FsRegistry.of(file) as? com.twig.fs.local.LocalFileSystem)?.linkTarget(file))
        } else file
        val unix = runCatching {
            if (file.scheme == "trash" && file.path != "/") {
                val fs = FsRegistry.of(file) as TrashFileSystem
                val item = fs.item(file)
                val path = fs.store.payload(item.entry, item.relative).path
                UnixFiles.stat(path)?.let { it.copy(selinuxContext = UnixFiles.context(path)) }
            } else (FsRegistry.of(file) as? UnixMetadataSource)?.unixMetadata(file)
        }.getOrNull()
        sections += basic(ctx, inspected, remote, unix = unix)
        if (!file.isDir && file.canRead) {
            when {
                remote != null -> remoteMedia(ctx, remote)?.let { sections += it }
                OpenFiles.isImage(file) -> exif(ctx, file)?.let { sections += it }
                OpenFiles.isVideo(file) || OpenFiles.isAudio(file) ->
                    sections += media(ctx, file) ?: Section(s(ctx, R.string.info_section_media), emptyList())
                // Entries in the "Apps" tree: ask PackageManager directly, no need to touch files
                file.scheme == AppsFileSystem.SCHEME ->
                    installedApp(ctx, file)?.let { sections += it }
                // Remote apks need to be downloaded entirely to parse, skip (skip any property requiring a full read)
                OpenFiles.isApk(file) && file.scheme == "file" ->
                    apk(ctx, file)?.let { sections += it }
            }
        }
        if (supportsUnix(file)) sections += unixSection(ctx, file, unix)
        return Details(sections)
    }

    // ---- Basic ----

    private fun basic(ctx: Context, file: XFile, remote: MediaDetails? = null, includeContents: Boolean = true, unix: UnixMetadata? = null): Section {
        val rows = ArrayList<Pair<String, String>>()
        rows += s(ctx, R.string.info_name) to file.name
        val trash = if (file.scheme == "trash" && file.path != "/") (FsRegistry.of(file) as TrashFileSystem).let { fs ->
            val item = fs.item(file)
            val payload = fs.store.payload(item.entry, item.relative)
            val st = android.system.Os.lstat(payload.path)
            rows += s(ctx, R.string.info_source) to s(ctx, R.string.trash_title)
            rows += s(ctx, R.string.trash_original_path) to (item.entry.original?.let {
                if (item.relative.isEmpty()) it else java.io.File(it, item.relative).path
            } ?: s(ctx, R.string.trash_invalid, item.entry.directory.path))
            rows += s(ctx, R.string.info_path) to payload.path
            rows += s(ctx, R.string.trash_storage) to item.entry.volume.path
            if (item.entry.deletedAt > 0)
                rows += s(ctx, R.string.trash_deleted_at) to Format.time(item.entry.deletedAt, ctx)
            st
        } else null
        if (trash == null) {
            // Media servers may provide a real path instead of their opaque id.
            rows += s(ctx, R.string.info_path) to remote?.realPath?.ifEmpty { null }.orEmpty().ifEmpty { file.path }
            if (file.scheme != "file") rows += s(ctx, R.string.info_source) to Format.schemeLabel(file.scheme)
        }
        val symbolicLink = file.isSymbolicLink || (trash != null && android.system.OsConstants.S_ISLNK(trash.st_mode))
        val linkTarget = file.linkTarget ?: if (symbolicLink && trash != null && includeContents)
            runCatching { android.system.Os.readlink((FsRegistry.of(file) as TrashFileSystem).let { fs ->
                val item = fs.item(file)
                fs.store.payload(item.entry, item.relative).path
            }) }.getOrNull() else null
        if (symbolicLink) {
            rows += s(ctx, R.string.info_type) to s(ctx, R.string.info_symbolic_link)
            rows += s(ctx, R.string.info_link_target) to (linkTarget ?: s(ctx, R.string.info_unavailable))
        }
        if (file.isDir) {
            if (!symbolicLink) rows += s(ctx, R.string.info_type) to s(ctx, R.string.info_folder)
            if (includeContents) runCatching { FsRegistry.of(file).list(file) }.getOrNull()?.let { kids ->
                val dirs = kids.count { it.isDir }
                rows += s(ctx, R.string.info_contains) to
                    s(ctx, R.string.info_contains_value, dirs, kids.size - dirs)
            }
        } else {
            if (!symbolicLink) rows += s(ctx, R.string.info_type) to OpenFiles.mimeOf(file.name)
            // XFile.size in the listing may be 0 (media servers don't expose photo byte counts),
            // but the properties card only deals with one entry and the source will fetch it
            // specifically — use the real value when available
            val bytes = remote?.size?.takeIf { it > 0 } ?: file.size
            if (bytes > 0 || Format.sizeOrNull(file) != null) {
                rows += s(ctx, R.string.info_size) to
                    s(ctx, R.string.info_size_value, Format.size(bytes), "%,d".format(bytes))
            }
        }
        val modified = unix?.modified ?: trash?.st_mtime?.times(1000) ?: file.lastModified
        if (modified > 0) rows += s(ctx, R.string.info_modified) to Format.time(modified, ctx)
        if (trash == null) rows += s(ctx, R.string.info_writable) to
            s(ctx, if (file.canWrite) R.string.info_yes else R.string.info_no)
        if (!file.isDir && file.canRead) rows += apps(ctx, file) // open-with merged into basic info
        return Section(s(ctx, R.string.info_section_basic), rows)
    }

    private fun supportsUnix(file: XFile): Boolean = file.unixMode != null || file.scheme == "file" ||
        (file.scheme == "trash" && file.path != "/") ||
        runCatching { FsRegistry.of(file) is UnixMetadataSource }.getOrDefault(false)

    private fun unixSection(ctx: Context, file: XFile, attributes: UnixMetadata?): Section {
        val rows = buildList {
            val mode = attributes?.mode ?: file.unixMode
            add(s(ctx, R.string.info_permissions) to if (mode != null)
                "${UnixPermissions.symbolic(mode)} (${UnixPermissions.octal(mode)})"
                else s(ctx, R.string.info_unavailable))
            if (attributes != null) {
                add(s(ctx, R.string.info_uid) to attributes.uid.toString())
                add(s(ctx, R.string.info_gid) to attributes.gid.toString())
                if (attributes.accessed > 0) add(s(ctx, R.string.info_accessed) to Format.time(attributes.accessed, ctx))
                if (attributes.changed > 0) add(s(ctx, R.string.info_changed) to Format.time(attributes.changed, ctx))
                attributes.selinuxContext?.let { add(s(ctx, R.string.info_selinux_context) to it) }
            }
        }
        return Section(s(ctx, R.string.info_section_unix), rows)
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
        val rows = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val probe = MediaProbe()
        probe.run { readMedia(ctx, file, probe, rows) }
        return if (rows.isEmpty()) null else Section(s(ctx, R.string.info_section_media), rows.toList())
    }

    private fun readMedia(ctx: Context, file: XFile, probe: MediaProbe, rows: MutableList<Pair<String, String>>) {
        // Track details (codec / audio track / subtitle) enumerated via the ExoPlayer parser
        // already in the app — much fuller than MediaMetadataRetriever (MKV subtitle tracks,
        // DTS/TrueHD and other system-unknown tracks are also listed)
        val tracks = mediaTracks(ctx, file, probe)
        rows += tracks
        var summaryEnd = tracks.count { it.first == s(ctx, R.string.info_duration) }
        fun summary(id: Int, value: String) {
            val key = s(ctx, id)
            if (rows.none { it.first == key }) rows.add(summaryEnd++, key to value)
        }
        val mmr = MediaMetadataRetriever()
        try {
            // Avoid StreamProvider: its proxy FD has no per-probe read limit and may materialize a whole archive entry.
            probe.open(file).use { source ->
                val data = object : MediaDataSource() {
                    override fun getSize() = source.length()
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) =
                        source.readAt(position, buffer, offset, size)
                    override fun close() = Unit // owned by use{}
                }
                mmr.setDataSource(data)
                fun k(key: Int) = mmr.extractMetadata(key)?.takeIf { it.isNotBlank() }
                k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.let { summary(R.string.info_duration, duration(it)) }
                k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { bps ->
                    summary(R.string.info_bitrate, if (bps >= 1_000_000) {
                        "%.1f Mbps".format(bps / 1_000_000.0)
                    } else "${bps / 1000} kbps")
                }
                k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { summary(R.string.info_container, it) }
                if (tracks.none { it.first != s(ctx, R.string.info_duration) }) { // fall back when track enumeration fails
                    val w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null) summary(R.string.info_resolution, "$w × $h")
                    if (Build.VERSION.SDK_INT >= 31) {
                        k(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                            ?.let { summary(R.string.info_samplerate, "$it Hz") }
                    }
                }
                k(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.takeIf { it != "0" }?.let { summary(R.string.info_rotation, "$it°") }
                k(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { rows += s(ctx, R.string.info_title) to it }
                k(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { rows += s(ctx, R.string.info_artist) to it }
                k(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { rows += s(ctx, R.string.info_album) to it }
                trackNumber(
                    com.twig.app.ui.MusicTrackSort.tagNumber(k(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                    com.twig.app.ui.MusicTrackSort.tagNumber(k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                )?.let { rows += s(ctx, R.string.info_track_number) to it }
            }
        } catch (e: Exception) {
            // Keep tracks for containers the system can't parse (e.g. some MKV).
        } finally {
            runCatching { mmr.release() }
        }
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
        trackNumber(d.discNumber, d.trackNumber)?.let { rows += s(ctx, R.string.info_track_number) to it }
        return if (rows.isEmpty()) null else Section(s(ctx, R.string.info_section_media), rows)
    }

    private fun trackNumber(disc: Int, track: Int): String? = when {
        track <= 0 -> null
        disc > 0 -> "$disc - $track"
        else -> track.toString()
    }

    private fun bitrate(bps: Long): String =
        if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1_000_000.0) else "${bps / 1000} kbps"

    /** Enumerate video/audio/subtitle tracks using ExoPlayer's MetadataRetriever (zero new dependencies). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mediaTracks(ctx: Context, file: XFile, probe: MediaProbe): List<Pair<String, String>> = runCatching {
        val factory = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
            androidx.media3.datasource.DataSource.Factory { com.twig.app.ui.LazyRandomDataSource { probe.open(file) } },
            // Properties only need headers, not the seek index (which may be missing or damaged).
            androidx.media3.extractor.DefaultExtractorsFactory().setMatroskaExtractorFlags(
                androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES),
        )
        val retriever = androidx.media3.exoplayer.MetadataRetriever.Builder(ctx,
            androidx.media3.common.MediaItem.fromUri("twig:///media.${OpenFiles.typeExt(file)}"))
            .setMediaSourceFactory(factory).build()
        try {
            val tracksFuture = retriever.retrieveTrackGroups()
            val durationFuture = retriever.retrieveDurationUs()
            val tga = tracksFuture.get(probe.remainingMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
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
            runCatching { durationFuture.get(probe.remainingMs(), java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrNull()?.takeIf { it > 0 }?.let { rows += s(ctx, R.string.info_duration) to duration(it / 1000) }
            fun put(name: String, list: List<String>) = list.forEachIndexed { i, v ->
                rows += (if (list.size > 1) "$name ${i + 1}" else name) to v
            }
            put(s(ctx, R.string.info_track_video), video)
            put(s(ctx, R.string.info_track_audio), audio)
            put(s(ctx, R.string.info_track_subtitle), subs)
            rows
        } finally {
            retriever.close() // also cancels preparation on failure/timeout
        }
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
        val pi = pm.getPackageArchiveInfo(path, AppDetails.packageFlags()) ?: return null
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
        Section(s(ctx, R.string.info_section_app), rows, appGroups(ctx, pi, installed = false))
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
        val pi = pm.getPackageInfo(pkg, AppDetails.packageFlags())
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
        rows += s(ctx, R.string.info_install_time) to Format.time(pi.firstInstallTime, ctx)
        if (pi.lastUpdateTime != pi.firstInstallTime) {
            rows += s(ctx, R.string.info_update_time) to Format.time(pi.lastUpdateTime, ctx)
        }
        val splits = ai.splitSourceDirs?.size ?: 0
        if (splits > 0) rows += s(ctx, R.string.info_splits) to s(ctx, R.string.info_splits_value, splits)
        rows += s(ctx, R.string.info_apk_path) to (ai.sourceDir ?: "?")
        rows += s(ctx, R.string.info_data_dir) to (ai.dataDir ?: "?")
        Section(s(ctx, R.string.info_section_app), rows, appGroups(ctx, pi, installed = true))
    }.getOrNull()

    private fun appGroups(ctx: Context, info: android.content.pm.PackageInfo, installed: Boolean) =
        runCatching { AppDetails.load(ctx, info, installed) }.getOrElse {
            listOf(AppDetails.Group(AppDetails.Kind.SIGNATURE, emptyList(), s(ctx, R.string.info_app_details_unavailable)))
        }

}
