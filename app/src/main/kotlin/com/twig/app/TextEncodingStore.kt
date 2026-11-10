package com.twig.app

import android.content.Context
import com.twig.core.XFile
import java.nio.charset.Charset

/** Only deliberate choices are remembered; inferred encodings must follow Settings on every open. */
object TextEncodingStore {
    private const val MAX = 200
    private val temporary = linkedMapOf<String, Charset>()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("twig_text_encodings", Context.MODE_PRIVATE)

    private fun key(file: XFile): String? = when (file.scheme) {
        "file", "saf" -> "${file.scheme}|${file.path}"
        else -> Connections.ofScheme(file.scheme)?.let { "conn|${it.label()}|${file.path}" }
    }

    @Synchronized fun get(ctx: Context, file: XFile): Charset? {
        val key = key(file) ?: return temporary["${file.scheme}|${file.path}"]
        return prefs(ctx).getString(key, null)?.substringAfter('\n')
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
    }

    @Synchronized fun put(ctx: Context, file: XFile, charset: Charset?) {
        val key = key(file)
        if (key == null) {
            val sessionKey = "${file.scheme}|${file.path}"
            temporary.remove(sessionKey)
            if (charset != null) temporary[sessionKey] = charset
            while (temporary.size > MAX) temporary.remove(temporary.keys.first())
            return
        }
        val sp = prefs(ctx)
        val edit = sp.edit().remove(key)
        if (charset != null) edit.putString(key, "${System.currentTimeMillis()}\n${charset.name()}")
        val others = sp.all.filterKeys { it != key }.entries.sortedByDescending {
            (it.value as? String)?.substringBefore('\n')?.toLongOrNull() ?: 0L
        }
        others.drop(MAX - if (charset == null) 0 else 1).forEach { edit.remove(it.key) }
        edit.apply()
    }
}
