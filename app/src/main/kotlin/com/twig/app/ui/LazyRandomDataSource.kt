package com.twig.app.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.twig.core.RandomSource

/**
 * Lazy-open variant of [RandomSourceDataSource]: the underlying [RandomSource] is only created by [supplier] when
 * [open] is called, and closed on [close]. Used by the music queue — loading the entire queue doesn't open every
 * network connection immediately; only the one ExoPlayer actually loads (on the loader thread, where blocking IO is
 * allowed) gets a connection.
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
