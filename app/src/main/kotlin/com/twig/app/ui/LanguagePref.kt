package com.twig.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.twig.app.R

/**
 * App language (follow system / Chinese / English). The top-bar menu and the settings page share this single
 * candidate list and read/write logic — duplicating them in two places inevitably diverges (a one-off ordering
 * difference makes the radio button select a different language).
 *
 * Uses AppCompatDelegate's per-app language API (1.6+), rather than wrapping Locale / Configuration in every
 * Activity's attachBaseContext: it recreates all live Activities in the new language, and (via the
 * AppLocalesMetadataHolderService declared in the manifest) also persists + applies early on cold start below
 * API 33, without flashing the system language.
 */
object LanguagePref {

    /** One-to-one with [labels]; empty string = follow system. */
    private val TAGS = arrayOf("", "zh", "en")

    fun labels(ctx: Context): Array<String> = arrayOf(
        ctx.getString(R.string.language_system),
        ctx.getString(R.string.language_zh),
        ctx.getString(R.string.language_en),
    )

    /** Index of the current selection; if a different language is stored (or none was ever selected), it all counts as "follow system". */
    fun current(): Int =
        TAGS.indexOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'))
            .coerceAtLeast(0)

    fun apply(index: Int) {
        val tag = TAGS[index]
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
