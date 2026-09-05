package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Format
import com.twig.app.SavedConnection
import com.twig.core.XFile
import com.twig.fs.archive.SingleFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Size unknown" and "size is 0" are two different things.
 *
 * A media server gives no byte count at all for entries like photos (`Photo` has no
 * `MediaSources`, and `ItemFields` has no `Size` either); Twig can only probe via `Range`,
 * a step gated by count and time, leaving 0 when it cannot probe. Displaying that 0 as
 * "0 B" is wrong -- it is not an empty file, we simply never asked.
 *
 * ★ But **local/SMB files can genuinely be 0 bytes**, and hiding those the same way would
 * be a different kind of mistake. This file pins down exactly that dividing line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnknownSizeTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
    }

    /** Builds a registered connection so `Connections.ofScheme` can look it up. */
    private fun scheme(type: String, host: String): String {
        val conn = SavedConnection(type = type, host = host, user = "u")
        ConnectionStore.save(app, conn)
        return Connections.ensure(app, conn)
    }

    @Test
    fun `0 bytes on a media server means unknown, not displayed`() {
        val s = scheme("jellyfin", "http://jf-size.test")
        assertNull(Format.sizeOrNull(XFile(s, "/photos/a/p1", isDir = false, size = 0)))
    }

    @Test
    fun `0 bytes locally is a genuinely empty file, displayed as usual`() {
        assertEquals("0 B", Format.sizeOrNull(XFile("file", "/sdcard/empty.txt", isDir = false, size = 0)))
    }

    @Test
    fun `0 bytes on other remote sources is also displayed as usual`() {
        // the check only targets media servers: an empty file on FTP/SMB is still genuinely 0 bytes
        val s = scheme("ftp", "ftp-size.test")
        assertEquals("0 B", Format.sizeOrNull(XFile(s, "/empty.bin", isDir = false, size = 0)))
    }

    /**
     * Single-file compression, same distinction. `.tar.zst` is normally made by piping
     * tar into zstd, and zstd's content-size field sits in the frame header — nothing to
     * write there yet — so the entry inside reports 0 without being empty.
     */
    @Test
    fun `zstd with no content size in the header shows nothing`() {
        val f = XFile(SingleFileSystem.ZSTD_SCHEME, "/x/bundle.tar.zst!/bundle.tar", isDir = false, size = 0)
        assertNull(Format.sizeOrNull(f))
    }

    /** bz2 never carries the original size — the format has no field for it at all. */
    @Test
    fun `bzip2 entries never show a size`() {
        val f = XFile(SingleFileSystem.BZIP2_SCHEME, "/x/notes.txt.bz2!/notes.txt", isDir = false, size = 0)
        assertNull(Format.sizeOrNull(f))
    }

    /** `zstd somefile` does know the length and writes it, so that one shows normally. */
    @Test
    fun `zstd with a content size formats normally`() {
        val f = XFile(SingleFileSystem.ZSTD_SCHEME, "/x/a.tar.zst!/a.tar", isDir = false, size = 819200)
        assertEquals("800 KB", Format.sizeOrNull(f))
    }

    /**
     * ★ The dividing line: gz and xz keep their length at the *end* of the stream, so
     * even piped output carries it. A 0 from those is a genuinely empty file and must
     * still print as `0 B`.
     */
    @Test
    fun `gzip and xz zero really means empty`() {
        assertEquals("0 B", Format.sizeOrNull(
            XFile(SingleFileSystem.GZIP_SCHEME, "/x/empty.txt.gz!/empty.txt", isDir = false, size = 0),
        ))
        assertEquals("0 B", Format.sizeOrNull(
            XFile(SingleFileSystem.XZ_SCHEME, "/x/empty.txt.xz!/empty.txt", isDir = false, size = 0),
        ))
    }

    @Test
    fun `a file with a size formats as usual`() {
        val s = scheme("emby", "http://emby-size.test")
        assertEquals("27.2 KB", Format.sizeOrNull(XFile(s, "/photos/a/p2", isDir = false, size = 27836)))
    }
}
