package com.twig.app.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.twig.core.RandomSource

/**
 * 懒开版的 [RandomSourceDataSource]:底层 [RandomSource] 在 [open] 时才由 [supplier] 建立,
 * [close] 时关闭。用于音乐队列——载入整个队列不会立即打开所有网络连接,只有真正被
 * ExoPlayer 加载(loader 线程,阻塞 IO 允许)的那首才建连接。
 */
@UnstableApi
class LazyRandomDataSource(
    private val supplier: () -> RandomSource,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var src: RandomSource? = null
    private var pos = 0L
    private var left = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val s = supplier()
        src = s
        val total = s.length()
        pos = dataSpec.position
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
        else (total - pos).coerceAtLeast(0)
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0L) return C.RESULT_END_OF_INPUT
        val s = src ?: return C.RESULT_END_OF_INPUT
        val n = s.readAt(pos, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val s = src
        src = null
        if (s != null) {
            transferEnded()
            runCatching { s.close() }
        }
    }
}
