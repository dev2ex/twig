package com.twig.app

import android.app.Application
import android.media.MediaMetadataRetriever
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ui.MusicDialogs
import com.twig.app.ui.SettingsActivity
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.MediaDetails
import com.twig.core.MediaInfoSource
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowMediaMetadataRetriever

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34], shadows = [FileInfoTrackNumberTest.Tags::class])
class FileInfoTrackNumberTest {
    @Implements(MediaMetadataRetriever::class)
    class Tags : ShadowMediaMetadataRetriever() {
        companion object { var values = emptyMap<Int, String>() }
        @Implementation public override fun extractMetadata(key: Int): String? = values[key]
    }
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val scheme = "track-number-test"
    private val file = XFile(scheme, "/song.flac", false, size = 123)
    @After fun cleanup() { FsRegistry.unregister(scheme); Tags.values = emptyMap() }
    private fun rows() = FileInfo.load(app, file).sections.flatMap { it.rows }
    private fun remote(details: MediaDetails) {
        FsRegistry.register(object : FileSystem by LocalFileSystem(), MediaInfoSource {
            override val scheme = this@FileInfoTrackNumberTest.scheme
            override fun detailsOf(file: XFile) = details
            override fun openInput(file: XFile): java.io.InputStream = error("Server tags must not read audio")
            override fun openRandom(file: XFile): RandomSource = error("Server tags must not read audio")
        })
    }

    @Test fun `properties media includes server disc and track without opening audio`() {
        remote(MediaDetails(discNumber = 2, trackNumber = 3))
        val media = FileInfo.load(app, file).sections.single { it.title == app.getString(R.string.info_section_media) }
        assertEquals("2 - 3", media.rows.single { it.first == app.getString(R.string.info_track_number) }.second)
    }

    @Test fun `unknown disc displays only the known track and unknown track omits the row`() {
        for (disc in listOf(0, -1)) {
            remote(MediaDetails(discNumber = disc, trackNumber = 7))
            assertEquals("7", rows().single { it.first == app.getString(R.string.info_track_number) }.second)
        }
        for (track in listOf(0, -1)) {
            remote(MediaDetails(discNumber = 2, trackNumber = track))
            assertFalse(rows().any { it.first == app.getString(R.string.info_track_number) })
        }
    }

    @Test fun `system media tags accept leading zeros and number slash total syntax`() {
        val bytes = javaClass.getResourceAsStream("/media-probe.mkv")!!.use { it.readBytes() }
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override val scheme = this@FileInfoTrackNumberTest.scheme
            override fun randomAccessEfficient() = true
            override fun openRandom(file: XFile): RandomSource = object : RandomSource {
                override fun length() = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(length, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                    return n
                }
                override fun close() = Unit
            }
        })
        Tags.values = mapOf(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to "02/03",
            MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "03/12")
        val details = FileInfo.load(app, file.copy(path = "/clip.mkv", size = bytes.size.toLong()))
        assertEquals("2 - 3", details.sections.flatMap { it.rows }
            .single { it.first == app.getString(R.string.info_track_number) }.second)
    }

    @Test fun `music info renders the same disc track row as properties`() {
        val entered = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        FsRegistry.register(object : FileSystem by LocalFileSystem(), MediaInfoSource {
            override val scheme = this@FileInfoTrackNumberTest.scheme
            override fun detailsOf(file: XFile): MediaDetails {
                worker.set(Thread.currentThread())
                entered.countDown()
                return MediaDetails(discNumber = 2, trackNumber = 3)
            }
        })
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            MusicDialogs.showInfo(activity, file)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.get().join(5000)
            assertFalse(worker.get().isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
            assertTrue(message.contains(activity.getString(R.string.info_track_number)))
            assertTrue(message.contains("2 - 3"))
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }
}
