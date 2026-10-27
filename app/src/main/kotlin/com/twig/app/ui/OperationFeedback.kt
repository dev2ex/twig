package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.annotation.MainThread
import com.twig.app.MainActivity
import com.twig.app.Prefs
import com.twig.app.R

/** Completion feedback is independent of whether a progress dialog is attached. */
object OperationFeedback : Application.ActivityLifecycleCallbacks {
    private const val CHANNEL = "twig_operation_results"
    private const val DONE_ID = 4302
    private val resumed = mutableSetOf<Activity>()

    @MainThread
    private fun foreground(ctx: Context): Boolean =
        resumed.any { !it.isFinishing && !it.isDestroyed } &&
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** A new operation replaces the previous operation's retained completion. */
    fun clear(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(DONE_ID)
    }

    @MainThread
    fun finished(
        ctx: Context, title: String, message: String, details: String = message,
        cancelled: Boolean = false, openIntent: Intent? = null, showToast: Boolean = true,
        allowVibration: Boolean = true,
    ) {
        val app = ctx.applicationContext
        val visible = foreground(app)
        if (showToast) Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        if (cancelled) return
        if (allowVibration && Prefs.vibrateOnCompletion(app)) vibrate(app)
        if (visible && !Prefs.foregroundCompletionNotifications(app)) return

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.settings_operation_notifications), NotificationManager.IMPORTANCE_LOW),
        )
        val intent = openIntent ?: Intent(app, MainActivity::class.java)
        val pending = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL) else Notification.Builder(app)
        val notification = builder.setContentTitle(title).setContentText(details)
            .setSmallIcon(R.drawable.ic_info).setAutoCancel(true).setContentIntent(pending).build()
        // Missing notification permission must not turn a successful file operation into a failure.
        runCatching { nm.notify(DONE_ID, notification) }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ctx: Context) {
        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        // An explicitly enabled completion alert must work in silent mode and in background.
        // Touch feedback is restricted to foreground processes; alarm usage supports both.
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE), audio)
            else vibrator.vibrate(80, audio)
        }
    }

    override fun onActivityResumed(activity: Activity) { resumed.add(activity) }
    override fun onActivityPaused(activity: Activity) { resumed.remove(activity) }
    override fun onActivityDestroyed(activity: Activity) { resumed.remove(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
