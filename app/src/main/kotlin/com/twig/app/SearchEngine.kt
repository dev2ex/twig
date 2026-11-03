package com.twig.app

import android.content.Context
import java.net.URLEncoder

/** One search provider shared by browser entry points and selected-text actions. */
object SearchEngine {
    const val QUERY = "{query}"
    data class Provider(val name: String, val home: String, val template: String)

    val presets = listOf(
        Provider("DuckDuckGo", "https://start.duckduckgo.com/", "https://duckduckgo.com/?q={query}"),
        Provider("Google", "https://www.google.com/", "https://www.google.com/search?q={query}"),
        Provider("Bing", "https://www.bing.com/", "https://www.bing.com/search?q={query}"),
        Provider("Baidu", "https://www.baidu.com/", "https://www.baidu.com/s?wd={query}"),
    )
    val default get() = presets.first()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("twig_prefs", Context.MODE_PRIVATE)

    fun home(ctx: Context): String = WebsiteStore.normalizeUrl(prefs(ctx).getString("search_home", null).orEmpty())
        ?: default.home

    fun template(ctx: Context): String = prefs(ctx).getString("search_template", null)
        ?.takeIf(::validTemplate) ?: default.template

    fun validTemplate(value: String): Boolean = QUERY in value &&
        WebsiteStore.normalizeUrl(value.replace(QUERY, "twig")) != null

    fun save(ctx: Context, home: String, template: String) {
        val address = requireNotNull(WebsiteStore.normalizeUrl(home))
        val pattern = template.trim()
        require(validTemplate(pattern))
        prefs(ctx).edit().putString("search_home", address).putString("search_template", pattern).apply()
    }

    fun url(ctx: Context, text: String): String? {
        val query = text.trim().takeIf { it.isNotEmpty() } ?: return null
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return WebsiteStore.normalizeUrl(template(ctx).replace(QUERY, encoded))
    }
}
