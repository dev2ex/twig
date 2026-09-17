package com.twig.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import java.io.File

/**
 * Streaming ContentProvider: exposes an [XFile] from any source (local /
 * SMB / archive / FTP / ...) as a `content://` URI to other apps ("Open
 * with..."), without caching the whole file first — *when* the source can
 * actually do positional reads cheaply.
 *
 * - Local files return a real fd directly;
 * - Virtual sources on API 26+ use openProxyFileDescriptor (a FUSE-backed
 *   seekable proxy fd: the external player's seek bar becomes a positional
 *   read via openRandom) **when the source reports true random access**
 *   (`randomAccessEfficient()` / an archive entry's `fastRandom()`); a
 *   compressed archive entry doesn't, so it's materialized to the cache once
 *   instead — see the comment in [openFile] for why proxying it anyway is a
 *   correctness bug, not just a slow path;
 * - On older systems this degrades to a one-way pipe (not seekable).
 *
 * The URI has the form
 * `content://<pkg>.stream/<base64(scheme,size,name,path)>/<signature>/<file name>`, and
 * is self-contained — after the process is killed, the external app can still reopen the
 * file using the URI alone.
 *
 * ★ **The signature is what makes "self-contained" safe** (2026-09-17 review). Without it
 * anyone could write such a URI for any path on any connected server — and although the
 * provider is not exported, Twig reads its own provider freely, so handing a forged URI to
 * the exported `ShareTargetActivity` / `ViewIntentActivity` made Twig fetch the file and copy
 * it wherever the user tapped. Only URIs this install minted ([uriFor]) carry a valid
 * HMAC; the key is random per install and never leaves the device's app data.
 */
class StreamProvider : ContentProvider() {

    private var ioThread: HandlerThread? = null

    override fun onCreate(): Boolean = true

    /**
     * ★ Never call `requireContext()` here: `ContentProvider.requireContext()` only exists from
     * **API 30**, and neither the Kotlin compiler nor a plain build complains — minSdk is 24, so
     * on Android 9 every provider entry point dies with
     * `NoSuchMethodError: No virtual method requireContext()` the first time another component
     * opens a `content://` URI. The crash lands on whatever thread called
     * `openFileDescriptor()` (media3's MetadataRetriever / ExoPlayer loader), which is outside
     * any of our try/catch, so it kills the process — the symptom is "tapping track info closes
     * the music page" on an API < 30 device (the 1.9.0 / LM-G710 incident).
     */
    private fun ctx(): Context = requireNotNull(context)

    override fun getType(uri: Uri): String =
        OpenFiles.mimeOf(uri.lastPathSegment ?: "")

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val f = decode(ctx(), uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.size
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = ctx()
        if ("w" in mode) throw SecurityException(ctx.getString(R.string.stream_read_only))
        val f = decode(ctx, uri)
        if (f.scheme == "file") {
            return ParcelFileDescriptor.open(File(f.path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val fs = FsRegistry.of(f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ★ A compressed archive entry (fastRandom() false — DEFLATE, not STORED) has no
            // true positional read: FileSystem.openRandom's default "reopen and skip" redoes
            // the whole decompression from byte 0 on every out-of-order seek. That is fine for
            // a player that mostly reads forward, but a random-access reader (PdfRenderer
            // parsing the trailer/xref at the file's tail, then jumping around the object
            // table) turns into a blocking read that can take minutes and pins whatever thread
            // called openFileDescriptor() for all of it — looking like "won't open" to the
            // caller, and, with enough concurrent attempts, starving Dispatchers.IO for
            // everything else that shares it (see docs/lessons/ for the incident this fixed).
            // Materializing once first is O(size) and bounded, same trade [OpenFiles.materialize]
            // already makes for RAR/nested archives in the tree.
            val efficient = (fs as? ArchiveFileSystem)?.fastRandom(f) ?: fs.randomAccessEfficient()
            if (!efficient) {
                val local = OpenFiles.materialize(ctx, f)
                return ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val src = fs.openRandom(f)
            val size = if (f.size > 0) f.size else src.length()
            return sm.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                RandomCallback(src, size),
                Handler(ioLooper()),
            )
        }
        // API < 26: one-way pipe stream; the external app cannot seek
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    fs.openInput(f).use { it.copyTo(out, 1 shl 20) }
                }
            }
        }, "twig-stream").start()
        return pipe[0]
    }

    @Synchronized
    private fun ioLooper() = (ioThread ?: HandlerThread("twig-stream-io").also {
        it.start(); ioThread = it
    }).looper

    /** Proxy fd callback: the external app's read(offset) is turned into a RandomSource positional read. */
    private class RandomCallback(
        private val src: RandomSource,
        private val size: Long,
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                var read = 0
                while (read < size) {
                    val n = src.readAt(offset + read, data, read, size - read)
                    if (n <= 0) break
                    read += n
                }
                return read
            } catch (e: Exception) {
                throw ErrnoException("read", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            runCatching { src.close() }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0

    companion object {
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        private const val KEY_FILE = "twig_stream"
        private const val KEY_NAME = "hmac"

        @Volatile private var key: ByteArray? = null

        /** Per-install HMAC key, created on first use. Not in any backup: a restored install mints its own. */
        private fun key(ctx: Context): ByteArray {
            key?.let { return it }
            synchronized(this) {
                key?.let { return it }
                val sp = ctx.applicationContext.getSharedPreferences(KEY_FILE, Context.MODE_PRIVATE)
                val stored = sp.getString(KEY_NAME, null)?.let { runCatching { Base64.decode(it, B64) }.getOrNull() }
                val k = stored?.takeIf { it.size == 32 } ?: ByteArray(32).also {
                    java.security.SecureRandom().nextBytes(it)
                    // commit, not apply: a URI handed out right now must still verify after a crash
                    sp.edit().putString(KEY_NAME, Base64.encodeToString(it, B64)).commit()
                }
                key = k
                return k
            }
        }

        private fun sign(ctx: Context, token: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key(ctx), "HmacSHA256"))
            return Base64.encodeToString(mac.doFinal(token.toByteArray()).copyOf(16), B64)
        }

        fun uriFor(context: Context, f: XFile): Uri {
            val token = Base64.encodeToString(
                "${f.scheme}\n${f.size}\n${f.name}\n${f.path}".toByteArray(), B64,
            )
            val sig = sign(context, token)
            return Uri.parse(
                "content://${context.packageName}.stream/$token/$sig/${Uri.encode(f.name)}",
            )
        }

        private fun decode(ctx: Context, uri: Uri): XFile {
            val segs = uri.pathSegments
            if (segs.size < 3) throw SecurityException(ctx.getString(R.string.stream_bad_uri))
            val token = segs[0]
            // Constant-time: the signature is the only thing standing between a caller and
            // any file on any connected server.
            val expected = sign(ctx, token).toByteArray()
            if (!java.security.MessageDigest.isEqual(expected, segs[1].toByteArray())) {
                throw SecurityException(ctx.getString(R.string.stream_bad_token))
            }
            val parts = String(Base64.decode(token, B64)).split("\n", limit = 4)
            require(parts.size == 4) { ctx.getString(R.string.stream_bad_token) }
            return XFile(
                scheme = parts[0],
                path = parts[3],
                isDir = false,
                size = parts[1].toLongOrNull() ?: 0L,
                displayName = parts[2],
            )
        }
    }
}
