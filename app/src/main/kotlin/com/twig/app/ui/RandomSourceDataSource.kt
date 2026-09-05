package com.twig.app.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.twig.core.RandomSource

/**
 * Adapts [RandomSource] into a media3 DataSource, shared by the player / off-screen frame grabbing.
 * ExoPlayer seek closes + opens at the new position — the underlying source is a shared pread
 * source, reopen costs zero; close does not close the underlying connection (caller closes it
 * centrally).
 */
@UnstableApi
class RandomSourceDataSource(private val src: RandomSource) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val total = src.length()
        pos = dataSpec.position
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
        else (total - pos).coerceAtLeast(0)
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0L) return C.RESULT_END_OF_INPUT
        val n = src.readAt(pos, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) { opened = false; transferEnded() }
    }
}
