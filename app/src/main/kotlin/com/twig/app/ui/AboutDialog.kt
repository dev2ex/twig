package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.twig.app.R

object AboutDialog {
    fun show(activity: AppCompatActivity) {
        val url = SettingsActivity.PROJECT_URL
        fun copyUrl() {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("twig", url))
            Toast.makeText(activity, R.string.info_copied, Toast.LENGTH_SHORT).show()
        }
        val version = runCatching {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            activity.getString(R.string.about_version, info.versionName ?: "", PackageInfoCompat.getLongVersionCode(info))
        }.getOrDefault("")
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_about)
            .setMessage(activity.getString(R.string.about_body, version, url))
            .setPositiveButton(R.string.about_open) { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure {
                        copyUrl()
                        Toast.makeText(activity, R.string.about_no_browser, Toast.LENGTH_LONG).show()
                    }
            }
            .setNeutralButton(R.string.about_copy) { _, _ -> copyUrl() }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }
}
