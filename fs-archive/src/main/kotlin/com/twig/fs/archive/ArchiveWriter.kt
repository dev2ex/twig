package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
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
 * The two formats take different write paths:
 *  - **zip** (java.util.zip): strictly sequential write, piped straight onto the target's
 *    openOutput, so remote targets need no local landing;
 *  - **7z** (commons-compress + xz): the header must be patched in place, so positional writes
 *    are required and the archive must be written to a local file first — if the target is local,
 *    write it directly there; otherwise write to [tmpDir] then ship the whole archive over.
 *
 * With a password: zip swaps to our handwritten [ZipWriter] (WinZip AES-256; java.util.zip does
 * not support encryption at all); 7z uses commons-compress's built-in AES-256. Both leave file
 * names **unencrypted** — encrypting names would prevent other tools from even listing the archive,
 * and the common need here is only "don't let the content be glanced at".
 */
object ArchiveWriter {

    enum class Format(val ext: String) { ZIP("zip"), SEVEN_Z("7z") }

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
    ) {
        if (items.isEmpty()) throw FsException("Nothing to compress")
        val pw = password?.takeIf { it.isNotEmpty() }
        val destFs = FsRegistry.of(target)
        val name = target.name
        val part = destFs.createFile(destDir, "$name$PART_SUFFIX")
        val st = State(
            listener, cancelled,
            if (plannedBytes >= 0) plannedBytes else CopyEngine.totalSize(items),
            excluded = listOf(part, target),
        )
        try {
            when (format) {
                Format.ZIP -> destFs.openOutput(part, append = false).use { out ->
                    // Without encryption still goes through java.util.zip: it is battle-tested, and there is no reason
                    // to replace the old path just for the sake of consistency.
                    if (pw == null) {
                        ZipOutputStream(out).use { zos -> writeAll(items, ZipSink(zos), st) }
                    } else {
                        ZipWriter(out, pw).use { zw -> writeAll(items, EncryptedZipSink(zw), st) }
                    }
                }
                Format.SEVEN_Z -> writeSevenZ(items, part, st, tmpDir, pw)
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
    private fun writeSevenZ(items: List<XFile>, target: XFile, st: State, tmpDir: File?, password: String?) {
        val local = target.scheme == LOCAL_SCHEME
        val file = if (local) File(target.path) else File.createTempFile("twig7z", ".7z", tmpDir)
        try {
            if (local) file.delete() // RandomAccessFile does not truncate, leftover content would produce a corrupt archive
            // Do not use SevenZOutputFile(File): it opens its channel via java.nio.file.Files (API 26+);
            // provide the FileChannel ourselves so minSdk 24 works too
            RandomAccessFile(file, "rw").channel.use { ch ->
                SevenZOutputFile(ch, password?.toCharArray()).use { szo ->
                    szo.setContentMethods(listOf(LZMA2))
                    writeAll(items, SevenZSink(szo), st)
                }
            }
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
        for (item in items) walk(item, "", sink, st)
    }

    private fun walk(item: XFile, prefix: String, sink: Sink, st: State) {
        if (st.cancelled.isCancelled()) throw Cancelled()
        // Compressing into ourselves (possible when both panes are parked in the same directory): the growing
        // .twigpart and the same-name old archive it is about to replace are both off-limits as sources
        if (st.excluded.any { it.scheme == item.scheme && it.path == item.path }) return
        val name = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
        if (item.isDir) {
            sink.dir(name, item.lastModified)
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
        fun dir(name: String, time: Long)
        fun file(name: String, src: XFile, st: State)
    }

    private class ZipSink(private val zos: ZipOutputStream) : Sink {
        override fun dir(name: String, time: Long) {
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
        override fun dir(name: String, time: Long) = zw.putDir(name, time)

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

        override fun dir(name: String, time: Long) {
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
    ) {
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

    /**
     * 7z compression method: LZMA2 with a 4 MB dictionary. **Do not use the default 8 MB** — the
     * LZMA2 encoder needs roughly 11× the dictionary in memory, and the default immediately burns
     * ~90 MB of Java heap; compressing big files on a phone OOMs easily. 4 MB is ~46 MB and the
     * compression ratio barely moves.
     */
    private val LZMA2 = org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(
        org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2,
        org.tukaani.xz.LZMA2Options().apply { dictSize = 1 shl 22 },
    )
}
