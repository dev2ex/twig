package com.twig.app

import android.content.Context
import com.twig.app.secure.Secrets
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A bookmark is an action in the tree, rather than a filesystem directory. */
data class Website(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    /** Legacy field: now only controls the toolbar; old records hid all three bars. */
    val fullscreen: Boolean = false,
    val pullToRefresh: Boolean = false,
    val doubleTapToolbar: Boolean = true,
    val allowBackgroundRunning: Boolean = false,
    val hideStatusBar: Boolean = fullscreen,
    val hideNavigationBar: Boolean = fullscreen,
    val disableTextSelection: Boolean = false,
    val lockScrollAxis: Boolean = false,
    val desktopSite: Boolean = false,
    /** Open in immersive display without adding an application Back-navigation step. */
    val openFullscreen: Boolean = false,
    val orientation: WebsiteOrientation = WebsiteOrientation.SYSTEM,
    val script: String = "",
    val style: String = "",
    /** One CSS selector per line; applied independently from custom CSS. */
    val hiddenElements: String = "",
    /** One domain or HTTP(S) URL pattern per line. */
    val blockedRequests: String = "",
    val userAgent: String = "",
    /** One Name: value per line; kept decrypted only in memory. */
    val headers: String = "",
    val basicAuthUsername: String = "",
    val basicAuthPassword: String = "",
    val blockScreenshots: Boolean = false,
    val microphonePermission: WebsitePermission = WebsitePermission.ASK,
    val cameraPermission: WebsitePermission = WebsitePermission.ASK,
    val locationPermission: WebsitePermission = WebsitePermission.ASK,
)

enum class WebsitePermission(val key: String) {
    ASK("ask"), ALLOW("allow"), DENY("deny");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: ASK
    }
}

enum class WebsiteOrientation(val key: String) {
    SYSTEM("system"), FREE("free"), LOCKED("locked"), LANDSCAPE("landscape"), PORTRAIT("portrait");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

object WebsiteStore {
    fun preferences(ctx: Context) = ctx.getSharedPreferences("twig_websites", Context.MODE_PRIVATE)

    /** Shared advanced settings and initial display choices for newly created sites. */
    fun defaults(ctx: Context): Website {
        val data = runCatching {
            JSONObject(preferences(ctx).getString("defaults", "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        return Website(name = "", url = "", userAgent = data.optString("userAgent"),
            script = data.optString("script"), style = data.optString("style"),
            hiddenElements = data.optString("hiddenElements"), blockedRequests = data.optString("blockedRequests"),
            openFullscreen = data.optBoolean("openFullscreen"), fullscreen = data.optBoolean("fullscreen"),
            hideStatusBar = data.optBoolean("hideStatusBar"), hideNavigationBar = data.optBoolean("hideNavigationBar"),
            pullToRefresh = data.optBoolean("pullToRefresh"), disableTextSelection = data.optBoolean("disableTextSelection"),
            lockScrollAxis = data.optBoolean("lockScrollAxis"),
            blockScreenshots = data.optBoolean("blockScreenshots"),
            doubleTapToolbar = data.optBoolean("doubleTapToolbar", true))
    }

    /** Advanced fields are site additions/overrides, never copies of the global settings. */
    fun newSite(ctx: Context): Website = defaults(ctx).copy(
        userAgent = "", script = "", style = "", hiddenElements = "", blockedRequests = "")

    fun effective(ctx: Context, site: Website): Website {
        val global = defaults(ctx)
        fun blocks(first: String, second: String) = listOf(first, second)
            .filter { it.isNotBlank() }.distinct()
        fun lines(first: String, second: String) = (first.lineSequence() + second.lineSequence())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
        val scripts = blocks(global.script, site.script)
        // Separate scopes and error handling let both additions run independently.
        val script = if (scripts.size < 2) scripts.firstOrNull().orEmpty() else scripts.joinToString("\n") {
            "try { (function() {\n$it\n}).call(window); } catch (error) { console.error('Twig custom script:', error); }"
        }
        return site.copy(userAgent = site.userAgent.ifBlank { global.userAgent }, script = script,
            style = blocks(global.style, site.style).joinToString("\n"),
            hiddenElements = lines(global.hiddenElements, site.hiddenElements),
            blockedRequests = lines(global.blockedRequests, site.blockedRequests))
    }

    fun saveDefaults(ctx: Context, site: Website) {
        val data = JSONObject().put("userAgent", site.userAgent).put("script", site.script)
            .put("style", site.style).put("hiddenElements", site.hiddenElements)
            .put("blockedRequests", site.blockedRequests)
            .put("openFullscreen", site.openFullscreen).put("fullscreen", site.fullscreen)
            .put("hideStatusBar", site.hideStatusBar).put("hideNavigationBar", site.hideNavigationBar)
            .put("pullToRefresh", site.pullToRefresh).put("disableTextSelection", site.disableTextSelection)
            .put("lockScrollAxis", site.lockScrollAxis)
            .put("blockScreenshots", site.blockScreenshots).put("doubleTapToolbar", site.doubleTapToolbar)
        preferences(ctx).edit().putString("defaults", data.toString()).apply()
    }

    fun all(ctx: Context): List<Website> {
        val raw = preferences(ctx).getString("list", null) ?: return emptyList()
        return parse(raw, { value -> runCatching { Secrets.dec(ctx, value) }.getOrDefault(value) })
    }

    private fun parse(raw: String, openSecret: (String) -> String, strict: Boolean = false): List<Website> {
        val parsed = runCatching {
            val items = JSONArray(raw)
            (0 until items.length()).map { index ->
                val o = items.getJSONObject(index)
                val fullscreen = o.optBoolean("fullscreen")
                Website(
                    id = o.getString("id"), name = o.getString("name"), url = o.getString("url"),
                    fullscreen = fullscreen, pullToRefresh = o.optBoolean("pullToRefresh"),
                    doubleTapToolbar = o.optBoolean("doubleTapToolbar", true),
                    allowBackgroundRunning = o.optBoolean("allowBackgroundRunning"),
                    blockScreenshots = o.optBoolean("blockScreenshots", false),
                    microphonePermission = WebsitePermission.fromKey(o.optString("microphonePermission")),
                    cameraPermission = WebsitePermission.fromKey(o.optString("cameraPermission")),
                    locationPermission = WebsitePermission.fromKey(o.optString("locationPermission")),
                    hideStatusBar = o.optBoolean("hideStatusBar", fullscreen),
                    hideNavigationBar = o.optBoolean("hideNavigationBar", fullscreen),
                    disableTextSelection = o.optBoolean("disableTextSelection"),
                    lockScrollAxis = o.optBoolean("lockScrollAxis"), desktopSite = o.optBoolean("desktopSite"),
                    openFullscreen = o.optBoolean("openFullscreen"),
                    orientation = WebsiteOrientation.fromKey(o.optString("orientation")),
                    script = o.optString("script"), style = o.optString("style"),
                    hiddenElements = o.optString("hiddenElements"), blockedRequests = o.optString("blockedRequests"),
                    userAgent = o.optString("userAgent"), headers = openSecret(o.optString("headers")),
                    basicAuthUsername = openSecret(o.optString("basicAuthUsername")),
                    basicAuthPassword = openSecret(o.optString("basicAuthPassword")),
                )
            }
        }
        return if (strict) parsed.getOrThrow() else parsed.getOrDefault(emptyList())
    }

    fun save(ctx: Context, site: Website) {
        val items = all(ctx).toMutableList()
        val index = items.indexOfFirst { it.id == site.id }
        if (index < 0) items.add(site) else items[index] = site
        persist(ctx, items)
    }

    fun remove(ctx: Context, id: String) = persist(ctx, all(ctx).filter { it.id != id })

    /** Backups carry plaintext fields under their own optional password encryption. */
    fun exportJson(ctx: Context): String {
        val sites = all(ctx)
        if (sites.any { site ->
            listOf(site.headers, site.basicAuthUsername, site.basicAuthPassword).any { it.startsWith("enc1:") }
        }) throw Secrets.KeyUnavailable()
        return json(sites) { it }.toString()
    }

    fun importJson(ctx: Context, raw: String) = persist(ctx, parse(raw, { it }, strict = true))

    private fun persist(ctx: Context, sites: List<Website>) {
        val items = json(sites) {
            // A locked reader may carry existing ciphertext. Never encrypt it twice.
            if (it.startsWith("enc1:")) it else Secrets.enc(ctx, it)
        }
        preferences(ctx).edit().putString("list", items.toString()).apply()
    }

    private fun json(sites: List<Website>, sealSecret: (String) -> String): JSONArray {
        val items = JSONArray()
        sites.forEach {
            items.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("url", it.url).put("fullscreen", it.fullscreen)
                .put("allowBackgroundRunning", it.allowBackgroundRunning).put("doubleTapToolbar", it.doubleTapToolbar).put("pullToRefresh", it.pullToRefresh).put("hideStatusBar", it.hideStatusBar)
                .put("hideNavigationBar", it.hideNavigationBar).put("disableTextSelection", it.disableTextSelection)
                .put("lockScrollAxis", it.lockScrollAxis).put("desktopSite", it.desktopSite)
                .put("openFullscreen", it.openFullscreen).put("orientation", it.orientation.key)
                .put("script", it.script).put("style", it.style).put("hiddenElements", it.hiddenElements).put("userAgent", it.userAgent)
                .put("blockedRequests", it.blockedRequests)
                .put("blockScreenshots", it.blockScreenshots)
                .put("microphonePermission", it.microphonePermission.key)
                .put("cameraPermission", it.cameraPermission.key)
                .put("locationPermission", it.locationPermission.key)
                .put("headers", sealSecret(it.headers))
                .put("basicAuthUsername", sealSecret(it.basicAuthUsername))
                .put("basicAuthPassword", sealSecret(it.basicAuthPassword)))
        }
        return items
    }

    /** Bare hosts use HTTPS; never accept local-file or executable URLs. */
    fun normalizeUrl(input: String): String? = runCatching {
        val text = input.trim()
        if (text.isEmpty()) return null
        val candidate = if (text.contains("://")) text else "https://$text"
        val uri = URI(candidate)
        if (uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port !in 1..65535)) return null
        uri.toASCIIString()
    }.getOrNull()
}
