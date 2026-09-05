package com.twig.app.ui

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Centralized exit for the music UI. Each music Activity registers a finish callback; on "exit
 * player" they are unified: release the player + stop the foreground service (removes the
 * notification) + finish all open music Activities (back to the file manager).
 */
@UnstableApi
object MusicUi {

    private val finishers = CopyOnWriteArraySet<() -> Unit>()

    fun register(finish: () -> Unit) { finishers.add(finish) }
    fun unregister(finish: () -> Unit) { finishers.remove(finish) }

    fun exit(ctx: Context) {
        MusicEngine.shutdown()
        // Stop the service: when the foreground service is destroyed, its notification is removed along with it
        ctx.applicationContext.stopService(Intent(ctx.applicationContext, MusicService::class.java))
        finishers.toList().forEach { it() }
        finishers.clear()
    }
}
