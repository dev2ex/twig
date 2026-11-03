package com.twig.app.ui

import com.twig.app.Website
import com.twig.app.WebsiteStore
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/** Navigation headers and page customizations stay scoped to the bookmark's origin. */
internal object WebsiteCustomization {
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val managedHeaders = setOf("host", "content-length", "transfer-encoding", "connection",
        "upgrade", "proxy-connection", "proxy-authorization", "trailer", "te", "user-agent")

    fun parseHeaders(text: String): LinkedHashMap<String, String>? {
        if (text.startsWith("enc1:")) return null // A locked store must not send ciphertext as a header.
        val normalized = text.replace("\r\n", "\n")
        if ('\r' in normalized) return null
        val result = linkedMapOf<String, String>()
        val names = hashSetOf<String>()
        for (line in normalized.lineSequence()) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim(' ', '\t')
            val lower = name.lowercase(Locale.ROOT)
            if (!headerName.matches(name) || lower in managedHeaders || !names.add(lower) ||
                value.any { it != '\t' && (it.code < 32 || it.code == 127) }) return null
            result[name] = value
        }
        return result
    }

    fun validUserAgent(value: String) = value.none { it.code < 32 || it.code == 127 }

    fun sameOrigin(first: String, second: String?) = WebsiteStore.sameOrigin(first, second)

    fun headersFor(site: Website, url: String): Map<String, String> =
        if (sameOrigin(site.url, url)) parseHeaders(site.headers).orEmpty() else emptyMap()

    fun validBasicAuth(username: String, password: String) =
        ':' !in username && listOf(username, password).all { value ->
            !value.startsWith("enc1:") && value.none { it.code < 32 || it.code == 127 }
        }

    /** WebView owns challenge negotiation and retry tracking; do not persist its auth cache. */
    fun basicAuthFor(site: Website, pageUrl: String?, host: String): Pair<String, String>? {
        if (site.basicAuthUsername.isEmpty() || !validBasicAuth(site.basicAuthUsername, site.basicAuthPassword) ||
            !sameOrigin(site.url, pageUrl)) return null
        val bookmarkHost = runCatching { URI(site.url).host }.getOrNull()
        if (!host.equals(bookmarkHost, ignoreCase = true)) return null
        return site.basicAuthUsername to site.basicAuthPassword
    }

    private fun quote(value: String) = JSONObject.quote(value)
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun guard(site: Website) = "if(location.origin!==new URL(${quote(site.url)}).origin)return;"

    fun requestKey(site: Website): String = digest(org.json.JSONArray()
        .put(site.desktopSite).put(site.userAgent).put(site.headers).put(site.script)
        .put(site.basicAuthUsername).put(site.basicAuthPassword).toString())

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun hiddenSelectors(text: String): List<String> = text.lineSequence().map { it.trimStart() }.filter { it.isNotBlank() }.distinct().toList()

    fun hasStyles(site: Website) = site.style.isNotEmpty() || site.hiddenElements.isNotBlank()

    fun styleScript(site: Website): String = """
        (() => {
            ${guard(site)}
            const key = ${quote("__twig_user_style_" + site.id)};
            let style = document.getElementById(key);
            const css = ${quote(site.style)};
            function makeStyle(id) {
                const value = document.createElement('style');
                value.id = id;
                const source = document.querySelector('style[nonce],script[nonce]');
                if (source && source.nonce) value.nonce = source.nonce;
                (document.head || document.documentElement).appendChild(value);
                return value;
            }
            if (!css) { if (style) style.remove(); }
            else {
                if (!style || style.tagName !== 'STYLE') {
                    if (style) style.remove();
                    style = makeStyle(key);
                }
                style.textContent = css;
            }
            const hiddenKey = ${quote("__twig_hidden_elements_" + site.id)};
            let hidden = document.getElementById(hiddenKey);
            const selectors = ${hiddenSelectors(site.hiddenElements).joinToString(",", "[", "]") { quote(it) }};
            if (!selectors.length) { if (hidden) hidden.remove(); return; }
            if (!hidden || hidden.tagName !== 'STYLE') {
                if (hidden) hidden.remove();
                hidden = makeStyle(hiddenKey);
            }
            hidden.textContent = '';
            for (const selector of selectors) {
                try {
                    // Validate as a selector before building a rule. Invalid rows cannot inject CSS rules.
                    document.querySelector(selector);
                    hidden.sheet.insertRule(selector + ' { display: none !important; }', hidden.sheet.cssRules.length);
                } catch (error) { console.warn('Twig hidden selector:', selector, error); }
            }
        })();
    """.trimIndent()

    /** Executed after page load, once per document, with ordinary page privileges. */
    fun pageScript(site: Website): String? {
        if (site.script.isBlank()) return null
        val revision = digest(site.script)
        return """
            (() => {
                ${guard(site)}
                const key = ${quote("__twig_user_script_" + site.id)};
                if (window[key] === ${quote(revision)}) return;
                window[key] = ${quote(revision)};
                try {
                    (function() {
                        ${site.script}
                    }).call(window);
                } catch (error) { console.error('Twig custom script:', error); }
            })();
        """.trimIndent()
    }
}
