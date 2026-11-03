package com.twig.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Menu
import android.view.View
import android.widget.Toast
import com.twig.app.R

/** System text actions for Termux's custom view, which has no TextView selection menu. */
internal object TerminalSelectionMenu {
    fun populate(ctx: Context, menu: Menu, selectedText: () -> String, finish: () -> Unit) {
        // Pinned Termux terminal-view v0.118.0: 1=Copy, 2=Paste, 3=More (showContextMenu).
        menu.removeItem(3)
        WebSearch.addSelectionAction(ctx, menu, selectedText, finish)
        if (menu.findItem(R.id.terminal_share_text) == null) {
            menu.add(0, R.id.terminal_share_text, 20, R.string.action_share).setOnMenuItemClickListener {
                val text = selectedText()
                if (text.isNotBlank()) {
                    finish()
                    launch(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text), ctx.getString(R.string.action_share)))
                }
                true
            }
        }
        menu.removeGroup(R.id.terminal_process_text)
        val request = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val handlers = ctx.packageManager.queryIntentActivities(request, 0)
            .filter { info ->
                val activity = info.activityInfo
                activity != null && activity.exported &&
                    (activity.permission.isNullOrEmpty() || ctx.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
            }
            .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        handlers.forEachIndexed { index, info ->
            val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            var id = View.generateViewId()
            while (id <= 3 || menu.findItem(id) != null) id = View.generateViewId()
            menu.add(R.id.terminal_process_text, id, 30 + index, info.loadLabel(ctx.packageManager))
                .setOnMenuItemClickListener {
                    val text = selectedText()
                    if (text.isNotBlank()) {
                        finish()
                        // Read-only: processing results must never become terminal input or commands.
                        launch(ctx, Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                            .setComponent(component)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
                    }
                    true
                }
        }
    }

    private fun launch(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.err_failed, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(ctx, R.string.err_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
