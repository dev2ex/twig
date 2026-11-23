package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.SourceLayout
import com.twig.core.UnixMetadata
import com.twig.core.UnixMetadataSource
import com.twig.core.XFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Archiver: compress a batch of [XFile]s from any source into one archive, written into any
 * target directory.
 *
 * Same pattern as [CopyEngine] — source side only uses openInput(), target side only uses
 * openOutput() — so combinations like "compress a directory on SMB to a local file" are
 * supported for free, with no N×N special-case code.
 * Blocking IO; the caller is responsible for putting it on a worker thread. Progress / cancel
 * reuse the CopyEngine interfaces, so the UI shares one progress dialog between copy and compress.
 *
 * Formats take different write paths:
 *  - **tar.gz**: tar headers/data through gzip directly into the target, without an intermediate tar;
 *  - **zip** (java.util.zip): strictly sequential write, piped straight onto the target's
 *    openOutput, so remote targets need no local landing;
 *  - **7z** (commons-compress + xz): the header must be patched in place, so positional writes
 *    are required and the archive must be written to a local file first — if the target is local,
 *    write it directly there; otherwise write to [tmpDir] then ship the whole archive over.
 *
 * With a password: zip swaps to our handwritten [ZipWriter] (WinZip AES-256; java.util.zip does
 * not support encryption at all); 7z uses commons-compress's built-in AES-256, optionally
 * followed by [SevenZHeaders] to encrypt names too. ZIP names remain visible.
 */
object ArchiveWriter {

    enum class Format(val ext: String) {
        ZIP("zip"), SEVEN_Z("7z"), TAR_GZ("tar.gz");

        val levels: List<Level> get() = if (this == TAR_GZ) listOf(Level.FAST, Level.STANDARD) else Level.entries
        fun normalizeLevel(level: Level): Level = level.takeIf { it in levels } ?: Level.STANDARD
    }
    enum class Level { FAST, STANDARD, STORE }

    /** TAR stores links themselves; planning must not traverse directory links or count their targets. */
    fun plan(items: List<XFile>, format: Format, cancelled: CopyEngine.Cancelled = CopyEngine.Cancelled { false }): CopyEngine.Plan {
        if (cancelled.isCancelled()) throw Cancelled()
        if (format != Format.TAR_GZ) return try { CopyEngine.plan(items, followLinks = true, cancelled = cancelled) }
            catch (_: java.util.concurrent.CancellationException) { throw Cancelled() }
        var bytes = 0L
        var files = 0
        var dirs = 0
        fun walk(file: XFile) {
            if (cancelled.isCancelled()) throw Cancelled()
            if (file.isSymbolicLink) { files++; return }
            if (file.isDir) {
                dirs++
                FsRegistry.of(file).list(file).forEach(::walk)
            } else { files++; bytes += file.size.coerceAtLeast(0) }
        }
        items.forEach { walk(FsRegistry.of(it).stat(it.path) ?: it) }
        return CopyEngine.Plan(bytes, files, dirs)
    }

    /** Cancelled (as opposed to a real failure): the caller uses this to say "cancelled" instead of erroring. */
    class Cancelled : RuntimeException("Cancelled")

    /**
     * Pack [items] into [target] (the archive file the caller has already produced via
     * destFs.createFile); [destDir] is the directory holding [target] — the temp archive has to
     * live in the same directory, so it is passed separately.
     *
     * **The whole write goes to a same-directory `<name>.twigpart`, and only after the archive
     * is complete does it take [target]'s place.** The old implementation wrote directly to
     * [target] and called `delete(target)` on failure: when the target was an **existing** archive
     * (the UI had asked "overwrite xxx?" and the user said yes), a cancel mid-write or OOM would
     * delete the user's original archive — new content not written, old content gone. Now failures
     * only delete their own `.twigpart`; the original archive is left exactly as it was.
     *
     * @param plannedBytes total source bytes the caller has planned; used for progress; < 0 means compute now.
     * @param tmpDir for 7z with a non-local target, the directory to land in (pass cacheDir).
     * @param password non-empty encrypts the whole archive (AES-256); empty string is treated as unencrypted.
     * @throws Cancelled user cancelled (the partial file has been cleaned up, and the same-named archive is untouched).
     */
    fun compress(
        items: List<XFile>,
        destDir: XFile,
        target: XFile,
        format: Format,
        listener: CopyEngine.ProgressListener? = null,
        cancelled: CopyEngine.Cancelled = CopyEngine.Cancelled { false },
        plannedBytes: Long = -1,
        tmpDir: File? = null,
        password: String? = null,
        level: Level = Level.STANDARD,
        hideNames: Boolean = false,
        targetPaths: Map<String, String> = emptyMap(),
    ) {
        if (items.isEmpty()) throw FsException("Nothing to compress")
        require(targetPaths.all { (uri, path) ->
            SourceLayout.isSafePath(path) || (path.isEmpty() && items.any { it.toUri() == uri && it.isDir })
        }) { "Invalid archive path" }
        if (items.any { !it.isDir && it.scheme == target.scheme && it.path == target.path }) {
            throw FsException("Choose a different archive name: the target is a selected source file")
        }
        val pw = password?.takeIf { it.isNotEmpty() }
        require(level in format.levels) { "Unsupported compression level for ${format.ext}: $level" }
        require(format != Format.TAR_GZ || pw == null) { "tar.gz does not support passwords" }
        val destFs = FsRegistry.of(target)
        val name = target.name
        if (items.any { !it.isDir && it.scheme == destDir.scheme &&
                it.parentPath == destDir.path && it.name == "$name$PART_SUFFIX" }) {
            throw FsException("Choose a different archive name: the temporary output is a selected source file")
        }
        val total = if (plannedBytes >= 0) plannedBytes else plan(items, format, cancelled).bytes
        val part = destFs.createFile(destDir, "$name$PART_SUFFIX")
        val st = State(
            listener, cancelled,
            total,
            excluded = listOf(part, target),
            targetPaths = targetPaths,
        )
        try {
            when (format) {
                Format.ZIP -> destFs.openOutput(part, append = false).use { out ->
                    // Without encryption still goes through java.util.zip: it is battle-tested, and there is no reason
                    // to replace the old path just for the sake of consistency.
                    if (pw == null && level != Level.STORE) {
                        ZipOutputStream(out).use { zos ->
                            zos.setLevel(if (level == Level.FAST) 1 else 6)
                            writeAll(items, ZipSink(zos), st)
                        }
                    } else {
                        ZipWriter(out, pw, compressionLevel = when (level) {
                            Level.FAST -> 1
                            Level.STANDARD -> 6
                            Level.STORE -> 0
                        }).use { zw -> writeAll(items, EncryptedZipSink(zw), st) }
                    }
                }
                Format.TAR_GZ -> destFs.openOutput(part, append = false).use { out ->
                    val gzip = object : GZIPOutputStream(out, 64 * 1024) {
                        init { def.setLevel(if (level == Level.FAST) 1 else 6) }
                    }
                    gzip.use { compressed ->
                        TarArchiveOutputStream(compressed, "UTF-8").use { tar ->
                            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                            tar.setAddPaxHeadersForNonAsciiNames(true)
                            writeAll(items, TarSink(tar), st)
                        }
                    }
                }
                Format.SEVEN_Z -> writeSevenZ(items, part, st, tmpDir, pw, level, hideNames)
            }
        } catch (t: Throwable) {
            // Only clean up our own partial file; the existing same-name archive is user data and not our business
            runCatching { destFs.delete(part) }
            throw t
        }
        // Content is fully on disk; only now touch the target: delete the old one (overwrite was already confirmed by the UI),
        // then rename the new one into place.
        // ★ This section does **no** cleanup on failure — the data is in `.twigpart`; leave it for the user to rename by hand.
        //   That is still better than "old deleted, new deleted, nothing left" (ZipFileSystem.rewrite hit the same trap).
        if (runCatching { destFs.exists(target) }.getOrDefault(false)) destFs.delete(target)
        destFs.rename(part, name)
        listener?.onDone()
    }

    /**
     * 7z needs positional writes: if the target is local write it directly, otherwise write a
     * temp file and ship the whole archive over to the target.
     * The [target] here is the `.twigpart` already created by [compress], not the user-visible final file.
     */
    private fun writeSevenZ(items: List<XFile>, target: XFile, st: State, tmpDir: File?, password: String?,
                            level: Level, hideNames: Boolean) {
        val local = target.scheme == LOCAL_SCHEME
        val file = if (local) File(target.path) else File.createTempFile("twig7z", ".7z", tmpDir)
        try {
            if (local) file.delete() // RandomAccessFile does not truncate, leftover content would produce a corrupt archive
            // Do not use SevenZOutputFile(File): it opens its channel via java.nio.file.Files (API 26+);
            // provide the FileChannel ourselves so minSdk 24 works too
            RandomAccessFile(file, "rw").channel.use { ch ->
                SevenZOutputFile(ch, password?.toCharArray()).use { szo ->
                    val method = if (level == Level.STORE) {
                        org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(
                            org.apache.commons.compress.archivers.sevenz.SevenZMethod.COPY)
                    } else {
                        org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(
                            org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2,
                            org.tukaani.xz.LZMA2Options(if (level == Level.FAST) 1 else 6).apply {
                                // Keep the mobile memory cap: the encoder needs roughly 11x the dictionary.
                                dictSize = if (level == Level.FAST) 1 shl 20 else 1 shl 22
                            })
                    }
                    szo.setContentMethods(listOf(method))
                    writeAll(items, SevenZSink(szo), st)
                }
            }
            if (password != null && hideNames) SevenZHeaders.encrypt(file, password, st.cancelled)
            if (!local) {
                val destFs = FsRegistry.of(target)
                file.inputStream().use { input ->
                    destFs.openOutput(target, append = false).use { out ->
                        CopyEngine.pipe(input, out, st.cancelled)
                    }
                }
            }
        } finally {
            if (!local) file.delete()
        }
    }

    private fun writeAll(items: List<XFile>, sink: Sink, st: State) {
        for (item in items) {
            val path = st.targetPaths[item.toUri()]
            walk(item, "", sink, st, path)
        }
    }

    private fun walk(original: XFile, prefix: String, sink: Sink, st: State, targetPath: String? = null) {
        if (st.cancelled.isCancelled()) throw Cancelled()
        // Compressing into ourselves (possible when both panes are parked in the same directory): the growing
        // .twigpart and the same-name old archive it is about to replace are both off-limits as sources
        if (st.excluded.any { it.scheme == original.scheme && it.path == original.path }) return
        val item = sink.prepare(original)
        val directory = item.isDir && !(sink.preservesLinks && item.isSymbolicLink)
        // A tar's synthetic '.' directory and filesystem roots represent their children.
        if (directory && (targetPath == "" || (targetPath == null && item.name in setOf(".", "/")))) {
            for (child in FsRegistry.of(item).list(item)) walk(child, prefix, sink, st)
            st.listener?.onItemDone(isDir = true)
            return
        }
        require(SourceLayout.isSafeSegment(item.name)) { "Invalid archive entry: ${item.name}" }
        val name = targetPath?.takeUnless { it.isEmpty() && !directory } ?: if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
        val parents = name.split('/').dropLast(1).runningReduce { path, segment -> "$path/$segment" }
        if (name in st.names || parents.any { st.names[it] == false } ||
            (!directory && name in st.requiredDirectories))
            throw FsException("Duplicate archive entry: $name")
        st.requiredDirectories.addAll(parents)
        st.names[name] = directory
        if (directory) {
            sink.dir(name, item)
            for (child in FsRegistry.of(item).list(item)) walk(child, name, sink, st)
            st.listener?.onItemDone(isDir = true)
        } else {
            st.listener?.onFile(item)
            sink.file(name, item, st)
            st.listener?.onItemDone(isDir = false)
        }
    }

    /** One entry's write into the archive; each format has its own implementation, walk logic is shared. */
    private interface Sink {
        val preservesLinks: Boolean get() = false
        fun prepare(file: XFile): XFile = file
        fun dir(name: String, src: XFile)
        fun file(name: String, src: XFile, st: State)
    }

    private class TarSink(private val tar: TarArchiveOutputStream) : Sink {
        override val preservesLinks = true
        private var metadata: UnixMetadata? = null

        override fun prepare(file: XFile): XFile {
            val fs = FsRegistry.of(file)
            metadata = (fs as? UnixMetadataSource)?.unixMetadata(file, includeContext = false)
            val info = metadata
            return if (info == null) file else file.copy(
                isSymbolicLink = info.isSymbolicLink, unixMode = info.mode,
                isDir = if (info.isSymbolicLink) file.isDir else info.isDirectory,
                size = if (info.isSymbolicLink || info.isDirectory) 0 else info.size,
                lastModified = info.modified)
        }

        private fun entry(name: String, src: XFile, type: Byte): TarArchiveEntry {
            val entry = TarArchiveEntry(name, type)
            (metadata?.mode ?: src.unixMode)?.let { entry.mode = it and 0xFFF }
            (metadata?.uid ?: src.unixUid)?.let { entry.setUserId(it) }
            (metadata?.gid ?: src.unixGid)?.let { entry.setGroupId(it) }
            entry.setModTime(src.lastModified)
            return entry
        }

        override fun dir(name: String, src: XFile) {
            tar.putArchiveEntry(entry("$name/", src, TarConstants.LF_DIR))
            tar.closeArchiveEntry()
        }

        override fun file(name: String, src: XFile, st: State) {
            if (src.isSymbolicLink) {
                val fs = FsRegistry.of(src)
                val target = (fs as? UnixMetadataSource)?.linkTarget(src) ?: src.linkTarget
                    ?: fs.stat(src.path)?.linkTarget ?: throw FsException("Cannot read symbolic link: $name")
                tar.putArchiveEntry(entry(name, src, TarConstants.LF_SYMLINK).apply { linkName = target })
            } else {
                require(src.size >= 0) { "Unknown tar entry size: $name" }
                val mode = src.unixMode?.and(0xF000)
                require(mode == null || mode == 0 || mode == 0x8000) { "Unsupported tar source type: $name" }
                tar.putArchiveEntry(entry(name, src, TarConstants.LF_NORMAL).apply { size = src.size })
                st.pump(src, tar)
            }
            tar.closeArchiveEntry()
        }
    }

    private class ZipSink(private val zos: ZipOutputStream) : Sink {
        override fun dir(name: String, src: XFile) {
            val time = src.lastModified
            zos.putNextEntry(ZipEntry("$name/").also { if (time > 0) it.time = time })
            zos.closeEntry()
        }

        override fun file(name: String, src: XFile, st: State) {
            zos.putNextEntry(ZipEntry(name).also { if (src.lastModified > 0) it.time = src.lastModified })
            st.pump(src, zos)
            zos.closeEntry()
        }
    }

    /** Encrypted zip: same walk, just routed through our handwritten [ZipWriter]. */
    private class EncryptedZipSink(private val zw: ZipWriter) : Sink {
        override fun dir(name: String, src: XFile) = zw.putDir(name, src.lastModified)

        override fun file(name: String, src: XFile, st: State) {
            // sizeHint decides whether to enable zip64 for this entry, must be set before writing (see ZipWriter comment)
            zw.putNextEntry(name, src.lastModified, src.size)
            st.pump(src, zw)
            zw.closeEntry()
        }
    }

    private class SevenZSink(private val szo: SevenZOutputFile) : Sink {
        /** SevenZOutputFile is not an OutputStream; wrap it for the pipeline (it computes size from bytes written itself). */
        private val out = object : OutputStream() {
            override fun write(b: Int) = szo.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = szo.write(b, off, len)
        }

        override fun dir(name: String, src: XFile) {
            val time = src.lastModified
            szo.putArchiveEntry(entry(name, isDir = true, time = time))
            szo.closeArchiveEntry()
        }

        override fun file(name: String, src: XFile, st: State) {
            szo.putArchiveEntry(entry(name, isDir = false, time = src.lastModified))
            st.pump(src, out)
            szo.closeArchiveEntry()
        }

        private fun entry(name: String, isDir: Boolean, time: Long) = SevenZArchiveEntry().apply {
            this.name = name
            this.isDirectory = isDir
            // File Roller's 7z backend determines directories from the Attributes field
            // in `7z l -slt`, rather than the format's separate empty-directory flag.
            setHasWindowsAttributes(true)
            windowsAttributes = if (isDir) 0x10 else 0x20 // DIRECTORY / ARCHIVE
            // 7z timestamps go through java.nio.file.attribute.FileTime (API 26+); on older devices it is not available, so skip it
            if (time > 0) runCatching { lastModifiedDate = java.util.Date(time) }
        }
    }

    private class State(
        val listener: CopyEngine.ProgressListener?,
        val cancelled: CopyEngine.Cancelled,
        val total: Long,
        /** Entries that cannot be used as compression sources (the .twigpart being written + the same-name old archive it will replace). */
        val excluded: List<XFile>,
        val targetPaths: Map<String, String>,
    ) {
        val names = HashMap<String, Boolean>()
        val requiredDirectories = HashSet<String>()
        var bytes = 0L

        /** Source -> archive stream; progress is aggregated across the whole task; cancel throws [Cancelled] to unwind the recursion. */
        fun pump(src: XFile, out: OutputStream) {
            var fileBytes = 0L
            val ok = FsRegistry.of(src).openInput(src).use { input ->
                CopyEngine.pipe(input, out, cancelled) { n ->
                    fileBytes += n
                    bytes += n
                    listener?.onFileBytes(fileBytes, src.size)
                    listener?.onBytes(bytes, total)
                }
            }
            if (!ok) throw Cancelled()
        }
    }

    private const val LOCAL_SCHEME = "file"

    /** Suffix for an in-progress archive; renamed to the user's filename only when fully written (see [compress]). */
    private const val PART_SUFFIX = ".twigpart"

}
