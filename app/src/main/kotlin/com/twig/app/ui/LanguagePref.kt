package com.twig.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.twig.app.R
import java.util.Locale

/**
 * App language. Language pickers share this single
 * candidate list and read/write logic — duplicating them in two places inevitably diverges (a one-off ordering
 * difference makes the radio button select a different language).
 *
 * Uses AppCompatDelegate's per-app language API (1.6+), rather than wrapping Locale / Configuration in every
 * Activity's attachBaseContext: it recreates all live Activities in the new language, and (via the
 * AppLocalesMetadataHolderService declared in the manifest) also persists + applies early on cold start below
 * API 33, without flashing the system language.
 */
object LanguagePref {

    private var cachedContext: Context? = null
    private var cachedConfiguration: Configuration? = null
    private var cachedTags: String? = null

    /** AppCompat localizes Activities below API 33, but leaves Application resources unchanged. */
    @Synchronized
    fun appContext(ctx: Context): Context {
        val app = ctx.applicationContext
        val locales = AppCompatDelegate.getApplicationLocales()
        if (Build.VERSION.SDK_INT >= 33 || locales.isEmpty) return app
        val configuration = app.resources.configuration
        val tags = locales.toLanguageTags()
        if (cachedContext?.applicationContext !== app || cachedConfiguration != configuration || cachedTags != tags) {
            cachedConfiguration = Configuration(configuration)
            cachedTags = tags
            val localized = Configuration(configuration).apply {
                // Preserve system fallback languages after the explicitly selected app languages.
                setLocales(android.os.LocaleList.forLanguageTags(
                    (tags.split(',') + configuration.locales.toLanguageTags().split(','))
                        .filter { it.isNotEmpty() }.distinct().joinToString(","),
                ))
            }
            cachedContext = app.createConfigurationContext(localized)
        }
        return requireNotNull(cachedContext)
    }

    /** Native names with English translations live only in default resources, keeping every picker readable. */
    private val choices = listOf(
        "" to R.string.language_system,
        "ar" to R.string.language_ar,
        "de" to R.string.language_de,
        "en" to R.string.language_en,
        "es" to R.string.language_es,
        "fr" to R.string.language_fr,
        "hi" to R.string.language_hi,
        "id" to R.string.language_id,
        "it" to R.string.language_it,
        "ja" to R.string.language_ja,
        "ko" to R.string.language_ko,
        "pt-BR" to R.string.language_pt_br,
        "ru" to R.string.language_ru,
        "tr" to R.string.language_tr,
        "vi" to R.string.language_vi,
        "zh" to R.string.language_zh,
        "zh-Hant" to R.string.language_zh_hant,
    ).sortedBy { it.first } // Stable tag order across UI languages; the empty system tag stays first.

    fun labels(ctx: Context): Array<String> = choices.map { ctx.getString(it.second) }.toTypedArray()

    /** Keep English available as a quieter annotation in the language dialog. */
    fun displayLabels(ctx: Context): Array<CharSequence> = labels(ctx).map { label ->
        val start = label.lastIndexOf(" (")
        if (start < 0) label else SpannableString(label).apply {
            setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_secondary)),
                start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }.toTypedArray()

    fun current(): Int = indexOf(AppCompatDelegate.getApplicationLocales()[0])

    /** Preserve script and region before falling back to a supported language. */
    internal fun indexOf(locale: Locale?): Int {
        if (locale == null) return 0
        val exact = choices.indexOfFirst { it.first.equals(locale.toLanguageTag(), ignoreCase = true) }
        if (exact >= 0) return exact
        val tag = when (locale.language) {
            "zh" -> if (locale.script == "Hant" ||
                (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))) "zh-Hant" else "zh"
            // Older Android releases expose Indonesian as the legacy ISO code "in".
            "in" -> "id"
            "pt" -> if (locale.country == "BR") "pt-BR" else "pt"
            else -> locale.language
        }
        return choices.indexOfFirst { it.first == tag }.coerceAtLeast(0)
    }

    fun apply(index: Int) {
        val tag = choices[index].first
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
