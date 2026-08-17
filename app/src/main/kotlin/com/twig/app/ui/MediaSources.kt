package com.twig.app.ui

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.File

/**
 * 从 [XFile] 构建 media3 [MediaSource] 的共享 helper。
 *
 * - 本地(scheme=="file"):[FileDataSource]。
 * - 其它来源:URI 必须带真实扩展名(`twig:///media.<ext>`,三斜杠让扩展名落在 path 里),
 *   否则 DefaultExtractorsFactory 认不出容器、退化到固定 sniff 顺序(MP3 排 AVI 前会误判)。
 *
 * [networkEager] 供视频播放器复用:预开一条共享 [RandomSource](seek 重开 DataSource 零成本),
 * 返回给调用方在 onDestroy 统一关闭——行为与原 MediaPlayerActivity 一致。
 *
 * [lazy] 供音乐队列:网络来源在 DataSource.open() 时才建连接、close() 时释放,避免载入
 * 整个队列就把所有网络连接一次开满。
 */
@UnstableApi
object MediaSources {

    /**
     * 默认容器工厂 + 自己的字幕解析工厂([TwigSubtitleParserFactory]):PGS 要用自己的
     * 解析器,官方那个一组 Display Set 只出得来一个 Cue(一屏多块字幕只显示一块)。
     * `DefaultExtractorsFactory` 有内部可变状态,每条源建一个新的,别共享。
     */
    fun extractors(): ExtractorsFactory =
        DefaultExtractorsFactory().setSubtitleParserFactory(TwigSubtitleParserFactory())

    /** 预开共享随机源(视频播放器用)。返回 (源, 待关闭的 RandomSource)。 */
    fun networkEager(file: XFile): Pair<MediaSource, RandomSource> {
        val shared = BufferedRandomSource(FsRegistry.of(file).openRandom(file))
        val factory = DataSource.Factory { RandomSourceDataSource(shared) }
        val src = ProgressiveMediaSource.Factory(factory, extractors())
            .createMediaSource(MediaItem.fromUri("twig:///media.${file.extension}"))
        return src to shared
    }

    /**
     * 懒开(音乐队列用):网络来源走 [AudioCache] 的每曲共享磁盘缓存——播放/波形/seek 共用
     * 同一份已下载字节,seek 命中不重新下载,连接与缓存生命周期由 [AudioCache] 统管
     * (LazyRandomDataSource.close → 共享源 close 为空操作,不会误关正在复用的连接)。
     */
    fun lazy(file: XFile): MediaSource {
        if (file.scheme == "file") {
            return ProgressiveMediaSource.Factory(FileDataSource.Factory())
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(File(file.path))))
        }
        val factory = DataSource.Factory { LazyRandomDataSource { AudioCache.source(file) } }
        return ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri("twig:///media.${file.extension}"))
    }
}
