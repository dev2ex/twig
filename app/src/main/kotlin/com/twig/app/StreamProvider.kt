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
import java.io.File

/**
 * 流式内容提供者:把任意来源(本地/SMB/压缩包/FTP 等)的 [XFile] 以
 * content:// URI 暴露给外部应用("用其他应用打开"),无需先整文件缓存。
 *
 * - 本地文件直接返回真实 fd;
 * - 虚拟来源在 API 26+ 用 openProxyFileDescriptor(基于 FUSE 的可 seek 代理 fd,
 *   外部播放器拖进度条会转成 openRandom 的定位读);
 * - 更老系统退化为单向管道(不可 seek)。
 *
 * URI 形如 content://<pkg>.stream/<base64(scheme,size,name,path)>/<文件名>,
 * 自包含全部信息,进程被杀后外部应用仍可凭 URI 重新打开。
 */
class StreamProvider : ContentProvider() {

    private var ioThread: HandlerThread? = null

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String =
        OpenFiles.mimeOf(uri.lastPathSegment ?: "")

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val f = decode(uri)
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
        if ("w" in mode) throw SecurityException("只读")
        val f = decode(uri)
        if (f.scheme == "file") {
            return ParcelFileDescriptor.open(File(f.path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val fs = FsRegistry.of(f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = requireNotNull(context).getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val src = fs.openRandom(f)
            val size = if (f.size > 0) f.size else src.length()
            return sm.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                RandomCallback(src, size),
                Handler(ioLooper()),
            )
        }
        // API < 26:单向管道流,外部应用不可 seek
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

    /** 代理 fd 回调:外部应用的 read(offset) 转成 RandomSource 定位读。 */
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

        fun uriFor(context: Context, f: XFile): Uri {
            val token = Base64.encodeToString(
                "${f.scheme}\n${f.size}\n${f.name}\n${f.path}".toByteArray(), B64,
            )
            return Uri.parse(
                "content://${context.packageName}.stream/$token/${Uri.encode(f.name)}",
            )
        }

        private fun decode(uri: Uri): XFile {
            val token = uri.pathSegments.firstOrNull() ?: throw SecurityException("bad uri")
            val parts = String(Base64.decode(token, B64)).split("\n", limit = 4)
            require(parts.size == 4) { "bad token" }
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
