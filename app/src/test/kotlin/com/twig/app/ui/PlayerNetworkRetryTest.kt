package com.twig.app.ui

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerNetworkRetryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val conn = SavedConnection("webdav", "https://retry.invalid")
    private val scheme = Connections.schemeOf(conn)
    private val video = XFile(scheme, "/retry.mp4", false, size = 1024)
    private val cm get() = app.getSystemService(ConnectivityManager::class.java)
    private lateinit var controller: ActivityController<MediaPlayerActivity>
    private val activity get() = controller.get()
    private val opened = AtomicInteger()
    private var prepares = 0
    private var seekedTo = 0L
    private var reportedPositionMs = 42000L
    private val p = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
        when (method.name) {
            "getCurrentPosition" -> reportedPositionMs
            "getDuration" -> 180000L
            "getPlaybackState" -> Player.STATE_IDLE
            "getPlayWhenReady" -> false
            "getIsPlaying", "isPlaying" -> false
            "prepare" -> { prepares++; null }
            "seekTo" -> { seekedTo = args!!.last() as Long; null }
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer

    @Before fun setup() {
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = this@PlayerNetworkRetryTest.scheme
            override fun list(dir: XFile) = emptyList<XFile>()
            override fun openRandom(file: XFile): RandomSource {
                opened.incrementAndGet()
                return object : RandomSource {
                    override fun length() = 1024L
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        if (position >= 1024) return -1
                        return minOf(length, (1024 - position).toInt())
                    }
                    override fun close() { }
                }
            }
        })
        Connections.ensure(app, conn)
        val intent = Intent(app, MediaPlayerActivity::class.java)
            .putExtra("scheme", scheme)
            .putExtra("path", video.path)
            .putExtra("name", video.name)
        controller = Robolectric.buildActivity(MediaPlayerActivity::class.java, intent).create()
        ReflectionHelpers.setField(activity, "player", p)
        ReflectionHelpers.setField(activity, "prepared", true)
        ReflectionHelpers.setField(activity, "sourceReady", true)
        shadowOf(cm).clearAllNetworks()
    }

    @After fun cleanup() {
        controller.destroy()
        Connections.drop(scheme)
    }

    private fun fail() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "sourceFailure",
        from(ExoPlayer::class.java, p), from(Exception::class.java,
            PlaybackException("network lost", IOException("socket closed"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
    private fun policy() = ReflectionHelpers.getField<PlaybackRetryPolicy>(activity, "retryPolicy")
    private fun online(): Network {
        val network = org.robolectric.shadows.ShadowNetwork.newInstance(42)
        shadowOf(cm).addNetwork(network, ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(cm).setNetworkCapabilities(network, org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
                shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        return network
    }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun awaitOpen() {
        repeat(200) {
            idle()
            if (prepares > 0) return
            Thread.sleep(5)
        }
        fail("Source was not prepared")
    }

    @Test fun `offline wait costs nothing and network recovery immediately reopens at saved position`() {
        fail()
        idle(120000)
        assertEquals(0, policy().attempts)
        assertEquals(0, opened.get())
        val network = online()
        val callback = ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(activity, "networkCallback")
        callback.onAvailable(network)
        awaitOpen()
        assertEquals(1, policy().attempts)
        assertEquals(1, opened.get())
        assertEquals(42000L, seekedTo)
    }

    @Test fun `initial online callback does not bypass one second backoff`() {
        val network = online()
        fail()
        ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(activity, "networkCallback").onAvailable(network)
        idle(999)
        assertEquals(0, policy().attempts)
        idle(1)
        awaitOpen()
        assertEquals(1, policy().attempts)
    }

    @Test fun `play button pauses offline retries and a later network recovery stays paused`() {
        fail()
        activity.findViewById<android.view.View>(R.id.btn_play).performClick()
        val network = online()
        ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(activity, "networkCallback").onAvailable(network)
        idle(120000)
        assertEquals(0, opened.get())
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "playbackWanted"))
    }

    @Test fun `manual play after exhaustion grants new retries and reopens even though player is idle`() {
        repeat(5) { policy().beginAttempt() }
        online()
        fail()
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "retryPending"))
        activity.findViewById<android.view.View>(R.id.btn_play).performClick()
        awaitOpen()
        assertEquals(0, policy().attempts)
        assertEquals(42000L, seekedTo)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "playbackWanted"))
    }

    @Test fun `leaving the player cancels delayed reconnects`() {
        online()
        fail()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onPause")
        idle(60000)
        assertEquals(0, opened.get())
        assertEquals(0, policy().attempts)
    }

    @Test fun `connection timeout advances backoff and keeps original resume position before ready`() {
        online()
        fail()
        idle(1000)
        awaitOpen()
        reportedPositionMs = 0L
        idle(9999)
        assertEquals(1, policy().attempts)
        idle(1)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "retryPending"))
        idle(1999)
        assertEquals(1, policy().attempts)
        idle(1)
        assertEquals(2, policy().attempts)
        assertEquals(42000L, ReflectionHelpers.getField<Long>(activity, "retryPositionMs").toLong())
    }

    @Test fun `successful first read ends connection timeout while long container probing continues`() {
        online()
        fail()
        idle(1000)
        awaitOpen()
        val source = ReflectionHelpers.getField<RandomSource>(activity, "fsSrc")
        assertEquals(1, source.readAt(0, ByteArray(1), 0, 1))
        idle(20000)
        assertEquals(1, policy().attempts)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "retryPending"))
    }

    @Test fun `unexpected early EOF is an error rather than successful video completion`() {
        val source = RandomSourceDataSource(object : RandomSource {
            override fun length() = 1024L
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() { }
        })
        source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("twig:///media.mp4")))
        assertThrows(java.io.EOFException::class.java) { source.read(ByteArray(16), 0, 16) }
        source.close()
    }

    @Test fun `network classification excludes corrupt media missing files and permission errors`() {
        assertTrue(MediaPlayerActivity.isRetryableNetworkError(PlaybackException("io",
            IOException("connection reset"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertTrue(MediaPlayerActivity.isRetryableConnectionError(FsException("SMB connection is down")))
        assertFalse(MediaPlayerActivity.isRetryableConnectionError(FsException("SMB read failed: access denied")))
        assertFalse(MediaPlayerActivity.isRetryableConnectionError(FsException("GET failed: HTTP 404")))
        assertFalse(MediaPlayerActivity.isRetryableConnectionError(FsException("GET failed: HTTP 401")))
        assertFalse(MediaPlayerActivity.isRetryableNetworkError(PlaybackException("parser",
            IOException(IllegalStateException("invalid packet")), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertFalse(MediaPlayerActivity.isRetryableNetworkError(PlaybackException("missing",
            IOException(), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)))
        assertFalse(MediaPlayerActivity.isRetryableNetworkError(PlaybackException("codec", null,
            PlaybackException.ERROR_CODE_DECODING_FAILED)))
    }
}
