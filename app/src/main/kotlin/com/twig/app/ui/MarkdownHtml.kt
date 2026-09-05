package com.twig.app.ui

/**
 * Hand-written Markdown → self-contained HTML string conversion (used by the WebView in
 * [TextViewerActivity] preview mode), no third-party library. Only covers a common GFM subset:
 * headings / bold-italic / inline code / links / images / blockquotes / lists / horizontal rules /
 * fenced code blocks / tables; not pursuing the full CommonMark spec (nested lists, footnotes,
 * etc. are not supported).
 */
object MarkdownHtml {

    /** Class on the `<div>` wrapping every table; see the rule in [CSS]. */
    private const val TABLE_WRAP_CLASS = "table-scroll"

    private val FENCE = Regex("^(`{3,}|~{3,})")
    private val HEADER = Regex("^(#{1,6})\\s+(.*)$")
    private val HR = Regex("^([-*_])\\1{2,}$")
    private val UL = Regex("^[-*+]\\s+(.*)$")
    private val OL = Regex("^\\d+\\.\\s+(.*)$")
    private val TABLE_SEP = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")

    fun render(text: String): String {
        val lines = text.split("\n")
        val body = StringBuilder()
        var i = 0
        var inUl = false
        var inOl = false
        fun closeLists() {
            if (inUl) { body.append("</ul>\n"); inUl = false }
            if (inOl) { body.append("</ol>\n"); inOl = false }
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            val fence = FENCE.find(trimmed)
            if (fence != null) {
                closeLists()
                val marker = if (fence.value[0] == '`') "```" else "~~~"
                val lang = trimmed.substring(fence.value.length).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // skip the closing fence line
                val langClass = if (lang.isNotEmpty()) " class=\"language-${escape(lang)}\"" else ""
                body.append("<pre><code$langClass>").append(escape(code.toString())).append("</code></pre>\n")
                continue
            }

            if (trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1].trim())) {
                closeLists()
                // Wrapped in a scroller: a wide table has to scroll on its own rather than either
                // squeeze its columns to shreds or push the whole page sideways. See TABLE_WRAP_CLASS.
                body.append("<div class=\"$TABLE_WRAP_CLASS\"><table><thead><tr>")
                for (h in splitRow(trimmed)) body.append("<th>").append(inline(h)).append("</th>")
                body.append("</tr></thead><tbody>\n")
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    body.append("<tr>")
                    for (c in splitRow(lines[i].trim())) body.append("<td>").append(inline(c)).append("</td>")
                    body.append("</tr>\n")
                    i++
                }
                body.append("</tbody></table></div>\n")
                continue
            }

            val header = HEADER.find(trimmed)
            if (header != null) {
                closeLists()
                val level = header.groupValues[1].length
                body.append("<h$level>").append(inline(header.groupValues[2])).append("</h$level>\n")
                i++; continue
            }

            if (trimmed.startsWith(">")) {
                closeLists()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote.append(lines[i].trimStart().removePrefix(">").trim()).append(' ')
                    i++
                }
                body.append("<blockquote><p>").append(inline(quote.toString().trim())).append("</p></blockquote>\n")
                continue
            }

            if (HR.matches(trimmed)) {
                closeLists()
                body.append("<hr>\n")
                i++; continue
            }

            val ul = UL.find(trimmed)
            if (ul != null) {
                if (inOl) { body.append("</ol>\n"); inOl = false }
                if (!inUl) { body.append("<ul>\n"); inUl = true }
                body.append("<li>").append(inline(ul.groupValues[1])).append("</li>\n")
                i++; continue
            }
            val ol = OL.find(trimmed)
            if (ol != null) {
                if (inUl) { body.append("</ul>\n"); inUl = false }
                if (!inOl) { body.append("<ol>\n"); inOl = true }
                body.append("<li>").append(inline(ol.groupValues[1])).append("</li>\n")
                i++; continue
            }

            closeLists()
            if (trimmed.isBlank()) { i++; continue }
            // Paragraph: merge consecutive non-blank, non-block-start lines (markdown soft line breaks = space)
            val para = StringBuilder(trimmed)
            i++
            while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i].trimStart())) {
                para.append(' ').append(lines[i].trim())
                i++
            }
            body.append("<p>").append(inline(para.toString())).append("</p>\n")
        }
        closeLists()
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>$CSS</style></head><body>$body</body></html>"
    }

    private fun isBlockStart(t: String): Boolean =
        t.isEmpty() || HEADER.containsMatchIn(t) || UL.containsMatchIn(t) || OL.containsMatchIn(t) ||
            HR.matches(t) || t.startsWith(">") || FENCE.containsMatchIn(t) || t.startsWith("|")

    private fun splitRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Inline grammar: backtick code, bold/italic, ![alt](src) images, [text](url) links.
     * Escape first, then scan — markdown marker characters (`*_[]()`) are not affected by HTML
     * escaping, so the order is safe. */
    private fun inline(raw: String): String {
        val text = escape(raw)
        val n = text.length
        val out = StringBuilder()
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i) {
                        out.append("<code>").append(text, i + 1, close).append("</code>"); i = close + 1
                    } else { out.append(c); i++ }
                }
                (c == '*' || c == '_') && i + 1 < n && text[i + 1] == c -> {
                    val close = text.indexOf("$c$c", i + 2)
                    if (close > i) {
                        out.append("<strong>").append(text, i + 2, close).append("</strong>"); i = close + 2
                    } else { out.append(c); i++ }
                }
                c == '*' || c == '_' -> {
                    val close = text.indexOf(c, i + 1)
                    if (close > i) {
                        out.append("<em>").append(text, i + 1, close).append("</em>"); i = close + 1
                    } else { out.append(c); i++ }
                }
                c == '!' && i + 1 < n && text[i + 1] == '[' -> {
                    val link = parseLink(text, i + 1, n)
                    if (link != null) {
                        out.append("<img alt=\"").append(link.first).append("\" src=\"").append(link.second).append("\">")
                        i = link.third
                    } else { out.append(c); i++ }
                }
                c == '[' -> {
                    val link = parseLink(text, i, n)
                    if (link != null) {
                        out.append("<a href=\"").append(link.second).append("\">").append(link.first).append("</a>")
                        i = link.third
                    } else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Parses `[text](url)` starting from `[`; returns (text, url, endIndex) or null (incomplete format). */
    private fun parseLink(text: String, bracketStart: Int, end: Int): Triple<String, String, Int>? {
        val labelEnd = text.indexOf(']', bracketStart + 1)
        if (labelEnd < 0 || labelEnd + 1 >= end || text[labelEnd + 1] != '(') return null
        val urlEnd = text.indexOf(')', labelEnd + 2)
        if (urlEnd < 0) return null
        return Triple(text.substring(bracketStart + 1, labelEnd), text.substring(labelEnd + 2, urlEnd), urlEnd + 1)
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /**
     * The actual background color of the preview page, one-to-one with `body`'s `background` in
     * [CSS] below — putting them in the same file is so that any change is visible in both places
     * at once (callers use this to tint the navigation bar). [dark] is provided by the caller
     * based on whether the current mode is dark: this is exactly what the WebView's
     * `prefers-color-scheme` tracks.
     */
    fun pageBg(dark: Boolean): Int = if (dark) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt()

    private const val CSS = """
        :root { color-scheme: light dark; }
        body { font-family: -apple-system, Roboto, sans-serif; line-height: 1.6; padding: 16px;
            margin: 0; color: #24292e; background: #ffffff; word-wrap: break-word; }
        h1, h2, h3, h4, h5, h6 { font-weight: 600; margin: 1.2em 0 .5em; }
        h1, h2 { border-bottom: 1px solid rgba(128,128,128,.3); padding-bottom: .3em; }
        h1 { font-size: 1.8em; } h2 { font-size: 1.5em; } h3 { font-size: 1.25em; }
        p { margin: .6em 0; }
        a { color: #0969da; }
        code { font-family: monospace; background: rgba(128,128,128,.15); padding: .15em .35em;
            border-radius: 4px; font-size: .9em; }
        pre { background: rgba(128,128,128,.12); padding: 12px; border-radius: 6px; overflow-x: auto; }
        pre code { background: none; padding: 0; }
        /* Table scroller: the div is what is clipped to the page width and scrolls, the table
           itself is left at its natural width (max-content) so columns keep their content on one
           line. Putting overflow on the table instead does nothing — a table is not a block box
           for overflow purposes, and max-width alone just crushes the columns. */
        .$TABLE_WRAP_CLASS { overflow-x: auto; max-width: 100%; margin: .6em 0; }
        blockquote { margin: .6em 0; padding: 0 1em; color: #656d76; border-left: 4px solid #d0d7de; }
        table { border-collapse: collapse; margin: 0; width: max-content; }
        th, td { border: 1px solid #d0d7de; padding: 6px 12px; }
        th { font-weight: 600; }
        ul, ol { padding-left: 1.6em; margin: .6em 0; }
        img { max-width: 100%; }
        hr { border: none; height: 1px; background: rgba(128,128,128,.3); margin: 1.2em 0; }
        @media (prefers-color-scheme: dark) {
            body { color: #e6edf3; background: #0d1117; }
            a { color: #58a6ff; }
            blockquote { color: #8b949e; border-left-color: #3b434b; }
            th, td { border-color: #30363d; }
            hr { background: #30363d; }
        }
    """
}
