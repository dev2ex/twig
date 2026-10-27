package com.twig.app.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.twig.app.OpenFiles
import com.twig.app.R
import com.twig.app.secure.Secrets
import com.twig.core.XFile

/** Lifecycle bridge only: the existing transfer service owns the work and TransferBox owns the dialog. */
class ShareTransfers : Fragment() {
    private var box: TransferBox? = null
    private var background = false
    private var handedOff = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = present()
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state); background = state?.getBoolean("background") == true }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putBoolean("background", background) }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(requireContext(), receiver, IntentFilter(Transfers.ACTION_FINISHED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onResume() { super.onResume(); handedOff = false; present() }
    override fun onPause() { box?.detach(); super.onPause() }
    override fun onStop() { requireContext().unregisterReceiver(receiver); super.onStop() }

    private fun present() {
        val host = activity ?: return
        if (handedOff || !isResumed || host.isFinishing || Secrets.locked(host) ||
            !(host.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive ||
            (host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        var completed = Transfers.consumeShare()
        while (completed != null) {
            box?.detach()
            val prepared = completed.preparedShare
            try {
                if (!completed.cancelled.get() && completed.finished?.isSuccess == true && prepared != null) {
                    handedOff = true
                    OpenFiles.sharePrepared(host, prepared.files)
                }
            } catch (e: Exception) {
                Toast.makeText(host, getString(R.string.share_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
            } finally { prepared?.release(); completed.preparedShare = null }
            if (handedOff) break
            completed = Transfers.consumeShare()
        }
        val session = Transfers.active?.takeIf { it.isShare }
        if (session != null && !background && box == null && Transfers.ui == null) {
            box = TransferBox(host, layoutInflater, session, alive = { isResumed },
                onBackground = {
                    background = true
                    if (android.os.Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        host.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                }, onDetach = { box = null }, onFinished = {})
        } else if (session == null && !Transfers.hasPendingShare()) {
            parentFragmentManager.beginTransaction().remove(this).commit()
        }
    }

    companion object {
        private const val TAG = "share-transfer"
        private fun host(context: Context): FragmentActivity {
            var value = context
            while (value is ContextWrapper && value !is FragmentActivity) value = value.baseContext
            return value as FragmentActivity
        }
        fun start(context: Context, files: List<XFile>) {
            val activity = host(context)
            val session = Transfers.Session(Transfers.Work.Share(files), R.string.share_preparing,
                activity.getString(R.string.action_share), R.drawable.ic_share, null)
            if (!Transfers.start(activity.applicationContext, session)) {
                Toast.makeText(activity, R.string.transfer_busy, Toast.LENGTH_SHORT).show()
                return
            }
            attach(activity, reveal = true)
        }
        fun attach(context: Context, reveal: Boolean = false) {
            if (Transfers.active?.isShare != true && !Transfers.hasPendingShare()) return
            val manager = host(context).supportFragmentManager
            if (manager.isStateSaved || manager.isDestroyed) return
            val existing = manager.findFragmentByTag(TAG) as? ShareTransfers
            if (existing != null) {
                if (reveal) existing.background = false
                existing.present()
            } else manager.beginTransaction().add(ShareTransfers(), TAG).commitNow()
        }
    }
}
