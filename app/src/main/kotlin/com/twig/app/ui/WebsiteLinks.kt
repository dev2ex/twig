package com.twig.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.twig.app.R

internal object WebsiteLinks {
    fun browserIntent(context: Context, url: String): Intent? {
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        // A hostless URL finds browsers without selecting an app's verified domain handler.
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("${uri.scheme}://"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported &&
                it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
        val defaultPackage = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        fun target(info: android.content.pm.ResolveInfo) = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setComponent(ComponentName(info.activityInfo.packageName, info.activityInfo.name))
        browsers.firstOrNull { it.activityInfo.packageName == defaultPackage }?.let { return target(it) }
        val targets = browsers.map(::target)
        return when (targets.size) {
            0 -> null
            1 -> targets.first()
            else -> Intent.createChooser(targets.first(), context.getString(R.string.website_open_browser))
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        }
    }
}
