package com.twig.app.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.TwigApp
import com.twig.app.Website
import com.twig.core.CopyEngine
import com.twig.core.XFile

/** Website download confirmation and Twig's own destination picker. */
internal class WebsiteDownloads(private val activity: AppCompatActivity) {
    private class Draft(val site: Website, val url: String, val agent: String, var name: String, val size: Long) {
        var destination = WebsiteDownloadTask.defaultDirectory()
    }
    private var draft: Draft? = null
    private var confirmation: AlertDialog? = null
    private var transfer: TransferBox? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val current = draft ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val scheme = data?.getStringExtra(PickerActivity.EXTRA_PICKED_SCHEME)
            val path = data?.getStringExtra(PickerActivity.EXTRA_PICKED_PATH)
            if (scheme != null && path != null) current.destination = XFile(scheme, path, true,
                displayName = data.getStringExtra(PickerActivity.EXTRA_PICKED_NAME))
        }
        showConfirmation(current)
    }

    fun offer(site: Website, url: String, agent: String, disposition: String?, mime: String?, size: Long) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (com.twig.app.WebsiteStore.normalizeUrl(url) != url) {
            Toast.makeText(activity, R.string.website_download_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        if (Transfers.active != null) {
            Toast.makeText(activity, R.string.transfer_busy, Toast.LENGTH_LONG).show()
            return
        }
        val current = Draft(site, url, agent, WebsiteDownloadTask.suggestedName(url, disposition, mime), size)
        draft = current
        showConfirmation(current)
    }

    private fun showConfirmation(current: Draft) {
        if (activity.isFinishing || activity.isDestroyed) return
        confirmation?.dismiss()
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val name = EditText(activity).apply {
            tag = R.string.website_download_name
            hint = activity.getString(R.string.website_download_name)
            setSingleLine()
            setText(current.name)
        }
        form.addView(name)
        if (current.size > 0) form.addView(TextView(activity).apply { text = Format.size(current.size) })
        form.addView(TextView(activity).apply {
            tag = R.string.compress_save_to
            text = activity.getString(R.string.compress_save_to) + "\n" + Format.pathLabel(current.destination)
            setPadding(0, padding / 2, 0, padding / 2)
        })
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.share_web_download)
            .setView(form)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> draft = null }
            .setNeutralButton(R.string.website_download_location, null)
            .setPositiveButton(R.string.share_web_download, null)
            .create()
        dialog.setOnCancelListener { draft = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                current.name = name.text.toString()
                dialog.dismiss()
                picker.launch(PickerActivity.dirIntent(activity, activity.getString(R.string.website_download_location)))
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val filename = name.text.toString().trim()
                if (!WebsiteDownloadTask.validName(filename)) {
                    name.error = activity.getString(R.string.website_download_invalid_name)
                    return@setOnClickListener
                }
                current.name = filename
                if (start(current)) {
                    draft = null
                    dialog.dismiss()
                }
            }
        }
        confirmation = dialog
        dialog.show()
    }

    private fun start(current: Draft): Boolean {
        TwigApp.registerBaseFs(activity)
        val task = WebsiteDownloadTask(current.site, current.url, current.agent, current.name, current.size)
        val session = Transfers.Session(Transfers.Work.Download(task, current.destination),
            R.string.share_web_download, current.destination.path, FileIcons.sourceIconRes(current.destination.scheme),
            CopyEngine.Plan(current.size.coerceAtLeast(0), 1, 0))
        val started = runCatching { Transfers.start(activity.applicationContext, session) }.getOrElse {
            Toast.makeText(activity, it.message ?: activity.getString(R.string.err_failed), Toast.LENGTH_LONG).show()
            return false
        }
        if (!started) {
            Toast.makeText(activity, R.string.transfer_busy, Toast.LENGTH_LONG).show()
            return false
        }
        transfer = TransferBox(activity, activity.layoutInflater, session,
            alive = { !activity.isFinishing && !activity.isDestroyed },
            onBackground = { requestNotifications() },
            onDetach = { transfer = null },
            onFinished = { finished ->
                if (!finished.cancelled.get()) Toast.makeText(activity,
                    if (finished.finished?.isSuccess == true) activity.getString(R.string.transfer_done)
                    else finished.finished?.exceptionOrNull()?.message ?: activity.getString(R.string.transfer_failed),
                    Toast.LENGTH_LONG).show()
            })
        return true
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    fun stop() { transfer?.detach() }
    fun destroy() {
        stop()
        confirmation?.dismiss()
        confirmation = null
        draft = null
    }
}
