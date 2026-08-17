package com.twig.app.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * 手写词法着色器(体积优先,不引库):单遍扫描产出 token 区间,[render] 按主题上色。
 * 词法级高亮——关键字/字符串/注释/数字/注解/函数名/大写开头类型,不做语义分析,
 * 看源码与配置文件够用。加一种语言 = 一个关键字集合 + 注释/字符串风格配置。
 */
object CodeHighlighter {

    // token 类型(兼作主题色数组下标)
    private const val KEYWORD = 0
    private const val STRING = 1
    private const val COMMENT = 2
    private const val NUMBER = 3
    private const val ANNOTATION = 4
    private const val FUNC = 5
    private const val TYPE = 6

    // Markdown 专用 token 类型:不进 theme.colors 数组,render() 里单独 when 分支处理
    // (需要背景色/粗斜体/字号等颜色以外的效果,不适合复用"kind 即数组下标"这套映射)
    private const val MD_HEADER = 7
    private const val MD_BOLD = 8
    private const val MD_ITALIC = 9
    private const val MD_CODE_INLINE = 10
    private const val MD_CODE_BLOCK = 11
    private const val MD_LINK = 12
    private const val MD_QUOTE = 13
    private const val MD_LIST = 14
    private const val MD_HR = 15

    /** [level] 仅 MD_HEADER 用(1-6 级标题,决定字号)。 */
    class Token(val start: Int, val end: Int, val kind: Int, val level: Int = 0)

    /** 一套配色;色值与应用日夜主题无关,代码查看自成一体。[colors] 按 token 类型取色。 */
    class Theme(val name: String, val bg: Int, val fg: Int, val colors: IntArray)

    val THEMES = listOf(
        Theme(
            "Monokai", 0xFF1E1F1C.toInt(), 0xFFF8F8F2.toInt(),
            intArrayOf(
                0xFFF92672.toInt(), 0xFFE6DB74.toInt(), 0xFF75715E.toInt(),
                0xFFAE81FF.toInt(), 0xFFA6E22E.toInt(), 0xFFA6E22E.toInt(), 0xFF66D9EF.toInt(),
            ),
        ),
        Theme(
            "Dracula", 0xFF282A36.toInt(), 0xFFF8F8F2.toInt(),
            intArrayOf(
                0xFFFF79C6.toInt(), 0xFFF1FA8C.toInt(), 0xFF6272A4.toInt(),
                0xFFBD93F9.toInt(), 0xFF50FA7B.toInt(), 0xFF50FA7B.toInt(), 0xFF8BE9FD.toInt(),
            ),
        ),
        Theme(
            "GitHub Light", 0xFFFFFFFF.toInt(), 0xFF24292E.toInt(),
            intArrayOf(
                0xFFD73A49.toInt(), 0xFF032F62.toInt(), 0xFF6A737D.toInt(),
                0xFF005CC5.toInt(), 0xFF6F42C1.toInt(), 0xFF6F42C1.toInt(), 0xFF22863A.toInt(),
            ),
        ),
        Theme(
            "Solarized Light", 0xFFFDF6E3.toInt(), 0xFF657B83.toInt(),
            intArrayOf(
                0xFF859900.toInt(), 0xFF2AA198.toInt(), 0xFF93A1A1.toInt(),
                0xFFD33682.toInt(), 0xFF268BD2.toInt(), 0xFF268BD2.toInt(), 0xFFB58900.toInt(),
            ),
        ),
    )

    /** 一种语言的词法配置。 */
    class Lang(
        val keywords: Set<String>,
        val lineComments: Array<String> = arrayOf("//"),
        val blockStart: String? = "/*",
        val blockEnd: String = "*/",
        val quotes: String = "\"'",
        val triple: Boolean = false,     // """ / ''' 跨行字符串(py/kt)
        val backtick: Boolean = false,   // ` 跨行字符串(js 模板/go raw/sh 命令替换)
        val annotation: Char? = null,    // '@' 注解/装饰器,'#' C 预处理
        val caseInsensitive: Boolean = false,
        val typeHl: Boolean = true,      // 大写开头标识符按类型上色
        val xml: Boolean = false,        // 走 XML 专用扫描
        val markdown: Boolean = false,   // 走 Markdown 专用扫描
    )

    private val LANGS: Map<String, Lang> = buildMap {
        val kotlin = Lang(
            setOf(
                "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "class",
                "object", "interface", "data", "sealed", "enum", "import", "package", "is", "in",
                "as", "null", "true", "false", "this", "super", "try", "catch", "finally", "throw",
                "break", "continue", "init", "companion", "override", "private", "public",
                "protected", "internal", "open", "abstract", "final", "suspend", "inline",
                "noinline", "crossinline", "reified", "typealias", "out", "vararg", "by",
                "constructor", "where", "lateinit", "get", "set", "it",
            ),
            triple = true, annotation = '@',
        )
        put("kt", kotlin); put("kts", kotlin); put("gradle", kotlin)
        put(
            "java",
            Lang(
                setOf(
                    "public", "private", "protected", "static", "final", "void", "int", "long",
                    "short", "byte", "char", "float", "double", "boolean", "class", "interface",
                    "enum", "extends", "implements", "import", "package", "new", "return", "if",
                    "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
                    "try", "catch", "finally", "throw", "throws", "this", "super", "null", "true",
                    "false", "instanceof", "abstract", "synchronized", "volatile", "transient",
                    "native", "assert", "var", "record", "sealed", "permits", "yield",
                ),
                annotation = '@',
            ),
        )
        val c = Lang(
            setOf(
                "int", "long", "short", "char", "float", "double", "void", "unsigned", "signed",
                "struct", "union", "enum", "typedef", "static", "extern", "const", "volatile",
                "register", "auto", "if", "else", "for", "while", "do", "switch", "case",
                "default", "break", "continue", "return", "goto", "sizeof", "inline", "bool",
                "true", "false", "NULL", "nullptr", "class", "public", "private", "protected",
                "virtual", "template", "typename", "namespace", "using", "new", "delete", "this",
                "operator", "friend", "constexpr", "decltype", "noexcept", "override", "final",
                "try", "catch", "throw", "mutable", "explicit",
            ),
            annotation = '#',
        )
        put("c", c); put("h", c); put("cpp", c)
        put(
            "js",
            Lang(
                setOf(
                    "function", "var", "let", "const", "if", "else", "for", "while", "do",
                    "switch", "case", "default", "break", "continue", "return", "new", "delete",
                    "typeof", "instanceof", "in", "of", "this", "null", "undefined", "true",
                    "false", "class", "extends", "super", "import", "export", "from", "async",
                    "await", "yield", "try", "catch", "finally", "throw", "void", "get", "set",
                    "static",
                ),
                backtick = true, annotation = '@',
            ),
        )
        put(
            "py",
            Lang(
                setOf(
                    "def", "class", "if", "elif", "else", "for", "while", "break", "continue",
                    "return", "import", "from", "as", "pass", "None", "True", "False", "and",
                    "or", "not", "in", "is", "lambda", "try", "except", "finally", "raise",
                    "with", "yield", "global", "nonlocal", "del", "assert", "async", "await",
                    "self", "match", "case",
                ),
                lineComments = arrayOf("#"), blockStart = null, triple = true, annotation = '@',
            ),
        )
        put(
            "sh",
            Lang(
                setOf(
                    "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
                    "esac", "function", "return", "exit", "local", "export", "echo", "read",
                    "source", "break", "continue", "in", "until", "select", "shift", "eval",
                    "set", "unset", "trap", "declare", "readonly", "cd", "test",
                ),
                lineComments = arrayOf("#"), blockStart = null, backtick = true, typeHl = false,
            ),
        )
        put(
            "go",
            Lang(
                setOf(
                    "func", "var", "const", "type", "struct", "interface", "map", "chan", "if",
                    "else", "for", "range", "switch", "case", "default", "break", "continue",
                    "return", "go", "defer", "select", "package", "import", "goto", "fallthrough",
                    "nil", "true", "false", "make", "new", "len", "cap", "append", "copy",
                    "delete", "panic", "recover", "error", "string", "int", "int32", "int64",
                    "uint", "uint32", "uint64", "float32", "float64", "bool", "byte", "rune",
                    "iota",
                ),
                backtick = true,
            ),
        )
        put(
            "rs",
            Lang(
                setOf(
                    "fn", "let", "mut", "const", "static", "if", "else", "match", "for", "while",
                    "loop", "break", "continue", "return", "struct", "enum", "trait", "impl",
                    "pub", "use", "mod", "crate", "self", "super", "as", "in", "ref", "move",
                    "unsafe", "async", "await", "dyn", "where", "type", "true", "false", "Some",
                    "None", "Ok", "Err", "Box", "Vec", "String", "str", "i32", "i64", "u8",
                    "u32", "u64", "f32", "f64", "usize", "isize", "bool", "char",
                ),
            ),
        )
        put(
            "sql",
            Lang(
                setOf(
                    "select", "from", "where", "insert", "into", "values", "update", "set",
                    "delete", "create", "table", "drop", "alter", "index", "view", "join",
                    "left", "right", "inner", "outer", "full", "cross", "on", "group", "by",
                    "order", "having", "limit", "offset", "union", "all", "distinct", "as",
                    "and", "or", "not", "null", "is", "in", "like", "between", "exists",
                    "primary", "key", "foreign", "references", "default", "unique", "check",
                    "constraint", "begin", "commit", "rollback", "transaction", "if", "else",
                    "case", "when", "then", "end", "int", "integer", "varchar", "text", "char",
                    "date", "datetime", "timestamp", "float", "double", "decimal", "boolean",
                ),
                lineComments = arrayOf("--"), caseInsensitive = true, typeHl = false,
            ),
        )
        put("json", Lang(setOf("true", "false", "null"), typeHl = false))
        put("css", Lang(emptySet(), lineComments = emptyArray(), typeHl = false))
        val conf = Lang(
            setOf("true", "false", "null", "on", "off", "yes", "no"),
            lineComments = arrayOf("#", ";"), blockStart = null, typeHl = false,
        )
        for (e in listOf("yml", "yaml", "toml", "ini", "conf", "cfg", "properties")) put(e, conf)
        val xml = Lang(emptySet(), xml = true)
        put("xml", xml); put("html", xml); put("htm", xml)
        val md = Lang(emptySet(), markdown = true)
        put("md", md); put("markdown", md)
    }

    /** 按文件名找语言配置;不认识返回 null(不着色)。 */
    fun langFor(name: String): Lang? = LANGS[name.substringAfterLast('.', "").lowercase()]

    /** 词法扫描产出 token 表(可离线算好,主题切换时复用重上色)。 */
    fun tokenize(text: String, lang: Lang): List<Token> {
        if (lang.xml) return tokenizeXml(text)
        if (lang.markdown) return tokenizeMarkdown(text)
        val out = ArrayList<Token>(minOf(text.length / 16, MAX_TOKENS))
        val n = text.length
        var i = 0
        while (i < n && out.size < MAX_TOKENS) {
            val c = text[i]
            val lc = lang.lineComments.firstOrNull { text.startsWith(it, i) }
            if (lc != null) {
                var j = text.indexOf('\n', i)
                if (j < 0) j = n
                out.add(Token(i, j, COMMENT)); i = j; continue
            }
            if (lang.blockStart != null && text.startsWith(lang.blockStart, i)) {
                var j = text.indexOf(lang.blockEnd, i + lang.blockStart.length)
                j = if (j < 0) n else j + lang.blockEnd.length
                out.add(Token(i, j, COMMENT)); i = j; continue
            }
            if (c in lang.quotes || (lang.backtick && c == '`')) {
                i = scanString(text, i, c, lang, out); continue
            }
            if (c.isDigit()) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
                out.add(Token(i, j, NUMBER)); i = j; continue
            }
            if (c == lang.annotation && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
                out.add(Token(i, j, ANNOTATION)); i = j; continue
            }
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                val kw = if (lang.caseInsensitive) word.lowercase() in lang.keywords else word in lang.keywords
                when {
                    kw -> out.add(Token(i, j, KEYWORD))
                    j < n && text[j] == '(' -> out.add(Token(i, j, FUNC))
                    lang.typeHl && c.isUpperCase() -> out.add(Token(i, j, TYPE))
                }
                i = j; continue
            }
            i++
        }
        return out
    }

    private fun scanString(text: String, start: Int, q: Char, lang: Lang, out: MutableList<Token>): Int {
        val n = text.length
        if (lang.triple && q != '`' && text.startsWith("$q$q$q", start)) {
            val close = text.indexOf("$q$q$q", start + 3)
            val end = if (close < 0) n else close + 3
            out.add(Token(start, end, STRING))
            return end
        }
        var i = start + 1
        while (i < n) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == q) { i++; break }
            if (c == '\n' && q != '`') break // 未闭合引号止于行尾,防后文整篇染成字符串色
            i++
        }
        i = minOf(i, n)
        out.add(Token(start, i, STRING))
        return i
    }

    /** XML/HTML:注释、标签名(关键字色)、属性名(类型色)、属性值(字符串色)。 */
    private fun tokenizeXml(text: String): List<Token> {
        val out = ArrayList<Token>()
        val n = text.length
        var i = 0
        while (i < n && out.size < MAX_TOKENS) {
            if (text.startsWith("<!--", i)) {
                var j = text.indexOf("-->", i + 4)
                j = if (j < 0) n else j + 3
                out.add(Token(i, j, COMMENT)); i = j; continue
            }
            if (text[i] == '<') {
                var j = i + 1
                if (j < n && (text[j] == '/' || text[j] == '?' || text[j] == '!')) j++
                val ns = j
                while (j < n && (text[j].isLetterOrDigit() || text[j] in ":_-.")) j++
                if (j > ns) out.add(Token(i, j, KEYWORD))
                while (j < n && text[j] != '>') {
                    val ch = text[j]
                    when {
                        ch == '"' || ch == '\'' -> {
                            var k = j + 1
                            while (k < n && text[k] != ch) k++
                            k = minOf(k + 1, n)
                            out.add(Token(j, k, STRING)); j = k
                        }
                        ch.isLetter() || ch == '_' -> {
                            var k = j + 1
                            while (k < n && (text[k].isLetterOrDigit() || text[k] in ":_-.")) k++
                            out.add(Token(j, k, TYPE)); j = k
                        }
                        else -> j++
                    }
                }
                if (j < n) j++
                i = j; continue
            }
            i++
        }
        return out
    }

    private val MD_FENCE = Regex("^(`{3,}|~{3,})")
    private val MD_HEADER_RE = Regex("^(#{1,6})\\s")
    private val MD_HR_RE = Regex("^([-*_])\\1{2,}$")
    private val MD_LIST_RE = Regex("^([-*+]|\\d+\\.)\\s")
    private val MD_TABLE_SEP_RE = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")

    /**
     * 逐行扫描块级结构(标题/引用/列表/分隔线/围栏代码块/表格),块内文字再走
     * [tokenizeMarkdownInline] 扫行内语法(粗斜体/行内代码/链接)。围栏代码块与表格
     * 内部不做行内扫描——原样只加背景+等宽,不重排列宽(不改动原文字符,避免破坏
     * 搜索用的字符偏移),不追求"块内按语言二次高亮"。
     */
    private fun tokenizeMarkdown(text: String): List<Token> {
        val out = ArrayList<Token>()
        val n = text.length
        var i = 0
        var fenceOpen = -1
        var fenceMarker = ""
        while (i < n && out.size < MAX_TOKENS) {
            val lineStart = i
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) n else it }
            val line = text.substring(lineStart, lineEnd)
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            val next = if (lineEnd < n) lineEnd + 1 else n

            if (fenceOpen >= 0) {
                if (trimmed.startsWith(fenceMarker)) {
                    out.add(Token(fenceOpen, lineEnd, MD_CODE_BLOCK))
                    fenceOpen = -1
                }
                i = next; continue
            }
            val fence = MD_FENCE.find(trimmed)
            if (fence != null) {
                fenceOpen = lineStart
                fenceMarker = if (fence.value[0] == '`') "```" else "~~~"
                i = next; continue
            }
            val header = MD_HEADER_RE.find(trimmed)
            if (header != null) {
                out.add(Token(lineStart, lineEnd, MD_HEADER, header.groupValues[1].length))
                i = next; continue
            }
            if (trimmed.startsWith(">")) {
                out.add(Token(lineStart, lineEnd, MD_QUOTE))
                i = next; continue
            }
            if (MD_HR_RE.matches(trimmed)) {
                out.add(Token(lineStart, lineEnd, MD_HR))
                i = next; continue
            }
            if (trimmed.contains('|') && next < n) {
                val sepEnd = text.indexOf('\n', next).let { if (it < 0) n else it }
                if (MD_TABLE_SEP_RE.matches(text.substring(next, sepEnd).trim())) {
                    var tableEnd = sepEnd
                    var k = if (sepEnd < n) sepEnd + 1 else n
                    while (k < n) {
                        val rowEnd = text.indexOf('\n', k).let { if (it < 0) n else it }
                        val row = text.substring(k, rowEnd)
                        if (row.isBlank() || !row.contains('|')) break
                        tableEnd = rowEnd
                        k = if (rowEnd < n) rowEnd + 1 else n
                    }
                    out.add(Token(lineStart, tableEnd, MD_CODE_BLOCK))
                    i = k; continue
                }
            }
            var contentStart = lineStart
            val list = MD_LIST_RE.find(trimmed)
            if (list != null) {
                val markerEnd = lineStart + indent + list.value.length
                out.add(Token(lineStart + indent, markerEnd, MD_LIST))
                contentStart = markerEnd
            }
            tokenizeMarkdownInline(text, contentStart, lineEnd, out)
            i = next
        }
        if (fenceOpen in 0 until n) out.add(Token(fenceOpen, n, MD_CODE_BLOCK)) // 未闭合围栏,染到文末
        return out
    }

    /** 单行内扫描:反引号行内代码、星号或下划线的粗体与斜体、[text](url) 链接。 */
    private fun tokenizeMarkdownInline(text: String, start: Int, end: Int, out: MutableList<Token>) {
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close in (i + 1) until end) {
                        out.add(Token(i, close + 1, MD_CODE_INLINE)); i = close + 1
                    } else i++
                }
                (c == '*' || c == '_') && i + 1 < end && text[i + 1] == c -> {
                    val marker = "$c$c"
                    val close = text.indexOf(marker, i + 2)
                    if (close in (i + 2) until end) {
                        out.add(Token(i, close + 2, MD_BOLD)); i = close + 2
                    } else i++
                }
                c == '*' || c == '_' -> {
                    val close = text.indexOf(c, i + 1)
                    if (close in (i + 1) until end) {
                        out.add(Token(i, close + 1, MD_ITALIC)); i = close + 1
                    } else i++
                }
                c == '[' -> {
                    val textClose = text.indexOf(']', i + 1)
                    val urlClose = if (textClose in (i + 1) until end && textClose + 1 < end &&
                        text[textClose + 1] == '('
                    ) {
                        text.indexOf(')', textClose + 2)
                    } else {
                        -1
                    }
                    if (urlClose in (textClose + 2) until end) {
                        out.add(Token(i, urlClose + 1, MD_LINK)); i = urlClose + 1
                    } else i++
                }
                else -> i++
            }
        }
    }

    private fun headerScale(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 1.5f
        2 -> 1.35f
        3 -> 1.22f
        4 -> 1.12f
        5 -> 1.05f
        else -> 1f
    }

    // 行内代码/围栏代码块的底色:半透明灰,叠在任何主题背景上都有区分度,不用为
    // Markdown 单独扩主题配色表。
    private const val CODE_CHIP_BG = 0x33888888

    /**
     * 高亮 span 的标记接口。编辑模式下要在 [Editable] 上反复"清掉旧色重上",
     * 清理时必须只摘自己加的这些——`getSpans(Any::class)` 一把清会连搜索高亮
     * 和输入法的 composing span 一起摘掉(后者一摘,拼音串当场散架)。
     * 各 span 类型薄薄包一层实现本接口,[applyTo] 靠它精确回收。
     */
    interface Hl

    private class HlFg(color: Int) : ForegroundColorSpan(color), Hl
    private class HlBg(color: Int) : BackgroundColorSpan(color), Hl
    private class HlStyle(style: Int) : StyleSpan(style), Hl
    private class HlSize(proportion: Float) : RelativeSizeSpan(proportion), Hl
    private class HlFont(family: String) : TypefaceSpan(family), Hl
    private class HlUnderline : UnderlineSpan(), Hl

    /**
     * 按主题把 token 摊成 span,逐个交给 [emit]。Markdown 专用 kind 需要颜色以外的
     * 效果(粗斜体/字号/背景),单独分支。[length] 是目标文本长度,用于越界保护。
     */
    private inline fun emitSpans(
        length: Int,
        tokens: List<Token>,
        theme: Theme,
        emit: (Any, Int, Int) -> Unit,
    ) {
        for (t in tokens) {
            if (t.end > length) continue
            when (t.kind) {
                MD_HEADER -> {
                    emit(HlFg(theme.colors[KEYWORD]), t.start, t.end)
                    emit(HlStyle(Typeface.BOLD), t.start, t.end)
                    emit(HlSize(headerScale(t.level)), t.start, t.end)
                }
                MD_BOLD -> emit(HlStyle(Typeface.BOLD), t.start, t.end)
                MD_ITALIC -> emit(HlStyle(Typeface.ITALIC), t.start, t.end)
                MD_CODE_INLINE, MD_CODE_BLOCK -> {
                    emit(HlBg(CODE_CHIP_BG), t.start, t.end)
                    emit(HlFont("monospace"), t.start, t.end)
                }
                MD_LINK -> {
                    emit(HlFg(theme.colors[TYPE]), t.start, t.end)
                    emit(HlUnderline(), t.start, t.end)
                }
                MD_QUOTE -> emit(HlFg(theme.colors[COMMENT]), t.start, t.end)
                MD_LIST -> emit(HlFg(theme.colors[KEYWORD]), t.start, t.end)
                MD_HR -> emit(HlFg(theme.colors[COMMENT]), t.start, t.end)
                else -> emit(HlFg(theme.colors[t.kind]), t.start, t.end)
            }
        }
    }

    /** 按主题把 token 上色成 Spannable(只读路径:整份文本一次成型)。 */
    fun render(text: String, tokens: List<Token>, theme: Theme): SpannableString {
        val s = SpannableString(text)
        emitSpans(s.length, tokens, theme) { span, start, end ->
            s.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /**
     * 编辑路径:就地给 [editable] 换色,**不重建文本**——`setText` 会重置光标与选区,
     * 更要命的是打断输入法的 composing 状态(中文拼音会丢字/重复上屏)。
     * 先摘掉上一轮的 [Hl] span,再打新的;搜索高亮与 composing span 不受影响。
     */
    fun applyTo(editable: Editable, tokens: List<Token>, theme: Theme) {
        for (old in editable.getSpans(0, editable.length, Hl::class.java)) editable.removeSpan(old)
        emitSpans(editable.length, tokens, theme) { span, start, end ->
            editable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 超过该大小不着色(span 过多 TextView 布局会卡)。 */
    const val MAX_HIGHLIGHT = 512 * 1024

    /**
     * 编辑期间"边打字边重着色"的上限,比 [MAX_HIGHLIGHT] 严得多:每敲一次都要全量
     * removeSpan+setSpan 再触发重排,接近 512KB 时主线程明显掉帧。超过它就只在
     * 进入编辑前/保存后各上一次色,打字期间不动 span。
     */
    const val MAX_LIVE_HIGHLIGHT = 100 * 1024
    private const val MAX_TOKENS = 20_000
}
