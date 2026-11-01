package com.twig.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.twig.app.TwigActivity
import java.util.UUID

/** Only app-created actions may cross this exported boundary; never accept raw targets. */
class LauncherShortcutActivity : TwigActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityUi.gate(this) {
            val target = load(this, intent)
            if (target != null) startActivity(target)
            finish()
        }
    }

    companion object {
        private const val FILE = "twig_launcher_shortcuts"
        private const val EXTRA_ID = "shortcut_id"

        fun save(ctx: Context, target: Intent): Intent {
            val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val uri = target.toUri(Intent.URI_INTENT_SCHEME)
            val id = prefs.all.entries.firstOrNull { it.value == uri }?.key ?: UUID.randomUUID().toString()
            prefs.edit().putString(id, uri).apply()
            return Intent(ctx, LauncherShortcutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ID, id)
        }

        internal fun load(ctx: Context, source: Intent): Intent? {
            val id = source.getStringExtra(EXTRA_ID) ?: return null
            val uri = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(id, null) ?: return null
            return runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
        }
    }
}
