package com.twig.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.R
import java.util.concurrent.Executors

/**
 * 前台音乐服务:framework [MediaSession] + [Notification.MediaStyle] 通知(锁屏/耳机/媒体键)。
 * 不引 media3-session。实际播放器在 [MusicEngine];本服务只做通知与系统媒体会话映射。
 * 通知按钮经自身 service intent 触发;硬件/蓝牙媒体键由激活的 MediaSession 自动路由到回调。
 */
@UnstableApi
class MusicService : Service() {

    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-music-notif").apply { isDaemon = true } }
    private var cover: Bitmap? = null
    private var coverForId: String? = null

    private val listener = object : MusicEngine.Listener {
        override fun onTrackChanged(index: Int, track: PlaylistTrack?) { updateCoverThenNotify() }
        override fun onPlayStateChanged(playing: Boolean) { pushNotification() }
        override fun onModeChanged() { pushNotification() }
        override fun onFavChanged() { pushNotification() } // 播放页/列表页改了最爱,心形跟着换实心
        override fun onEnded() {
            // 非循环播完:退出前台(通知转可清除),停止服务
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.music_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        session = MediaSession(this, "twig-music").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { MusicEngine.togglePlay(); pushNotification() }
                override fun onPause() { MusicEngine.pause(); pushNotification() }
                override fun onSkipToNext() { MusicEngine.next() }
                override fun onSkipToPrevious() { MusicEngine.prev() }
                override fun onSeekTo(pos: Long) { MusicEngine.seekTo(pos); pushNotification() }
                override fun onStop() { MusicEngine.stopPlayback(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == ACTION_FAV) toggleFav()
                }
            })
            isActive = true
        }
        MusicEngine.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> MusicEngine.togglePlay()
            ACTION_NEXT -> MusicEngine.next()
            ACTION_PREV -> MusicEngine.prev()
            ACTION_FAV -> toggleFav()
            ACTION_STOP -> { MusicEngine.stopPlayback(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        }
        // 必须在 5s 内 startForeground
        startForeground(NOTIF_ID, buildNotification())
        updateCoverThenNotify()
        return START_STICKY
    }

    override fun onDestroy() {
        MusicEngine.removeListener(listener)
        session.isActive = false
        session.release()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 通知 / 会话 ----

    private fun updateCoverThenNotify() {
        val track = MusicEngine.currentTrack()
        val file = MusicEngine.currentFile()
        if (track == null || file == null) { pushNotification(); return }
        if (coverForId == track.id) { pushNotification(); return }
        pushNotification() // 先无封面刷一版
        io.execute {
            val bmp = runCatching { Thumbs.audioCover(file, 512) }.getOrNull()
            cover = bmp
            coverForId = track.id
            Handler(mainLooper).post { pushNotification() }
        }
    }

    private fun pushNotification() {
        updateSession()
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    /** 通知栏心形:当前曲加入/移出「我的最爱」,并广播出去让播放页的心形同步。 */
    private fun toggleFav() {
        val track = MusicEngine.currentTrack() ?: return
        PlaylistStore.toggleFav(this, track)
        MusicEngine.notifyFavChanged()
    }

    private fun isFav(): Boolean =
        MusicEngine.currentTrack()?.let { PlaylistStore.isFav(this, it.id) } == true

    private fun updateSession() {
        val track = MusicEngine.currentTrack()
        val title = track?.title?.ifEmpty { null } ?: track?.name ?: getString(R.string.music_title)
        val artist = track?.artist ?: ""
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, MusicEngine.durationMs())
                .also { b -> cover?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        val playing = MusicEngine.isPlaying()
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val fav = isFav()
        session.setPlaybackState(
            PlaybackState.Builder()
                // ★ Android 13 起,通知里的媒体控件按钮是系统从 PlaybackState 的标准动作 +
                // 自定义动作里取的,Notification.Action 只对更老的系统有效——心形两边都要给。
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_FAV,
                        getString(if (fav) R.string.music_remove_fav else R.string.music_add_fav),
                        if (fav) R.drawable.ic_heart_filled_notif else R.drawable.ic_heart_notif,
                    ).build(),
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP,
                )
                .setState(state, MusicEngine.positionMs(), if (playing) 1f else 0f)
                .build(),
        )
    }

    private fun buildNotification(): Notification {
        val track = MusicEngine.currentTrack()
        val playing = MusicEngine.isPlaying()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MusicPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track?.title?.ifEmpty { null } ?: track?.name ?: getString(R.string.music_title))
            .setContentText(track?.artist ?: "")
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
        cover?.let { builder.setLargeIcon(it) }
        builder.addAction(action(R.drawable.ic_skip_prev, "prev", ACTION_PREV, 1))
        builder.addAction(
            if (playing) action(R.drawable.ic_pause, "pause", ACTION_PLAY_PAUSE, 2)
            else action(R.drawable.ic_play, "play", ACTION_PLAY_PAUSE, 2),
        )
        builder.addAction(action(R.drawable.ic_skip_next, "next", ACTION_NEXT, 3))
        val fav = isFav()
        builder.addAction(
            action(
                if (fav) R.drawable.ic_heart_filled_notif else R.drawable.ic_heart_notif,
                getString(if (fav) R.string.music_remove_fav else R.string.music_add_fav),
                ACTION_FAV,
                4,
            ),
        )
        builder.style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2) // 收起时只留 上一首/播放/下一首,心形在展开态
        return builder.build()
    }

    private fun action(icon: Int, title: String, action: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    companion object {
        private const val CHANNEL = "music"
        private const val NOTIF_ID = 0x7107
        private const val ACTION_PLAY_PAUSE = "com.twig.app.music.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.twig.app.music.NEXT"
        private const val ACTION_PREV = "com.twig.app.music.PREV"
        private const val ACTION_STOP = "com.twig.app.music.STOP"
        private const val ACTION_FAV = "com.twig.app.music.FAV"

        @UnstableApi
        fun start(ctx: Context) {
            val i = Intent(ctx, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
