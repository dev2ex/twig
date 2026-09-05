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
 * Shared helper that builds a media3 [MediaSource] from an [XFile].
 *
 * - Local (scheme=="file"): [FileDataSource].
 * - Other sources: the URI must carry a real extension (`twig:///media.<ext>`, the triple slash
 *   puts the extension in the path); otherwise DefaultExtractorsFactory cannot recognize the
 *   container and falls back to a fixed sniff order (MP3 before AVI causes misdetection).
 *
 * [networkEager] is for the video player: pre-open one shared [RandomSource] (seek that reopens
 * the DataSource costs zero), returned to the caller for unified close in onDestroy — behavior
 * matches the original MediaPlayerActivity.
 *
 * [lazy] is for the music queue: network sources open the connection on DataSource.open() and
 * release on close(), avoiding opening all network connections at once when loading the entire
 * queue.
 */
@UnstableApi
object MediaSources {

    /**
     * Default container factory + our own subtitle parser factory ([TwigSubtitleParserFactory]):
     * PGS needs our own parser; the official one only emits one Cue per Display Set (multi-segment
     * subtitles on the same screen only show one). `DefaultExtractorsFactory` has internal mutable
     * state; build a new one per source, do not share.
     *
     * Wrapped in [AviRepairExtractorsFactory] for the two things media3 gets wrong about AVI:
     * its MP3 audio is a byte stream that has to be cut back into frames, and its MPEG-4 video
     * carries no display timestamps, so B-frames need reordering. Every other container is
     * untouched.
     */
    fun extractors(): ExtractorsFactory =
        AviRepairExtractorsFactory(
            DefaultExtractorsFactory().setSubtitleParserFactory(TwigSubtitleParserFactory()),
        )

    /** Pre-open shared random source (for the video player). Returns (source, RandomSource to close). */
    fun networkEager(file: XFile): Pair<MediaSource, RandomSource> {
        val shared = BufferedRandomSource(FsRegistry.of(file).openRandom(file))
        val factory = DataSource.Factory { RandomSourceDataSource(shared) }
        val src = ProgressiveMediaSource.Factory(factory, extractors())
            .createMediaSource(MediaItem.fromUri("twig:///media.${file.extension}"))
        return src to shared
    }

    /**
     * Lazy open (for the music queue): network sources go through [AudioCache]'s per-track shared
     * disk cache — playback / waveform / seek share the same already-downloaded bytes; seek hits
     * don't re-download; connection and cache lifecycle are managed by [AudioCache]
     * (LazyRandomDataSource.close → shared source close is a no-op, won't accidentally close a
     * connection that's still being reused).
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
