package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.method.KeyListener
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.twig.app.Format
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityTextViewerBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置纯文本查看器/编辑器。通过 [FsRegistry] 经 openInput 读取,因此本地、压缩包内、FTP 上的
 * 文本文件都能直接查看,无需先解压/下载——又一次复用统一的 FileSystem 抽象。
 * 认识的代码类扩展名走 [CodeHighlighter] 词法着色(Monokai 等主题,菜单可切换);
 * 标题栏搜索图标展开搜索栏,高亮全部命中,▲▼ 在命中间跳转。
 *
 * 编辑:同一个 EditText 就地切换只读/可编辑([enterEdit]),保存经 [writeAtomically]
 * 写回同一来源。三种情况不给编辑(见 [editBlockReason] 与 [canEdit]),都是"存下去会
 * 毁掉原文件"的场景,宁可不给入口:
 *  1. 文件超 [MAX_BYTES] 被截断——存回去等于把文件砍成 1MB;
 *  2. 内容不是合法 UTF-8——[readText] 宽容解码出的 U+FFFD 存回去是不可逆损坏;
 *  3. 来源整体只读(7z/RAR/restic/git 视图/`share`)或条目不可写。
 */
class TextViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityTextViewerBinding
    private var raw: String = ""
    private var rawLower: String = ""
    private var tokens: List<CodeHighlighter.Token> = emptyList()
    private var isCode = false
    private var isMarkdown = false
    private lateinit var currentFile: XFile
    private var fileName: String = ""
    private var lang: CodeHighlighter.Lang? = null
    private var isHtml = false
    private var isPreviewable = false
    private var previewMode = false
    private var previewLoaded = false
    private var previewMenuItem: MenuItem? = null
    private var searchMenuItem: MenuItem? = null

    // 编辑态
    private var truncated = false
    private var isUtf8 = true
    private var canEdit = false
    private var editMode = false
    private var dirty = false
    private var saving = false
    private var liveHighlight = false
    private var settingText = false // applyTheme 的 setText 不该被当成用户编辑
    private var hlGen = 0 // 在途重着色的作废标记
    private var editMenuItem: MenuItem? = null
    private var saveMenuItem: MenuItem? = null

    /** 只读态把 keyListener 摘掉(仍可选中/复制),编辑态还回去——原件存这。 */
    private var savedKeyListener: KeyListener? = null

    // 双指缩放字号:sp 单位,onCreate 从 Prefs 恢复,手势结束时写回。
    private var contentTextSizeSp = 13f

    // 搜索状态:命中起点表、当前序号、已加的高亮 span(换主题重建文本后须重打)
    private var matches: List<Int> = emptyList()
    private var matchLen = 0
    private var cur = -1
    private val hitSpans = ArrayList<Any>()
    private var curSpan: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTextViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: path

        fileName = name
        b.toolbar.title = name
        b.toolbar.setNavigationOnClickListener { handleBack() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(name))

        // 只读态:摘掉 keyListener——不弹键盘、不显光标。仅摘 keyListener 并不够,
        // 得靠 setTextIsSelectable(true) 才真正保证长按能选中/复制(它不碰 keyListener,
        // 只管 movement method 与 focusable/clickable 那套,后续 setText 仍显式传
        // BufferType.EDITABLE,不会被它内部改成 SPANNABLE)。进编辑模式再把 keyListener
        // 还回去即可(见 enterEdit),不用换视图、不搬文本。
        savedKeyListener = b.content.keyListener
        b.content.keyListener = null
        b.content.setTextIsSelectable(true)
        b.content.isCursorVisible = false
        b.content.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        b.content.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        wireEditor()

        // 字号:从上次记住的值恢复,双指捏合实时调整、松手写回 Prefs。
        contentTextSizeSp = Prefs.viewerTextSize(this)
        b.content.setTextSize(TypedValue.COMPLEX_UNIT_SP, contentTextSizeSp)
        b.scroll.onScale = { factor -> applyTextSize(contentTextSizeSp * factor) }
        b.scroll.onScaleEnd = { Prefs.setViewerTextSize(this, contentTextSizeSp) }

        // 横向滚动指示条钉在视口底(原生的画在内容底边,不滚到文末看不见)
        b.scroll.hsv = b.hscroll
        b.hscroll.setOnScrollChangeListener { _, _, _, _, _ -> b.scroll.invalidate() }

        searchMenuItem = b.toolbar.menu.add(R.string.viewer_search).apply {
            setIcon(R.drawable.ic_search)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { toggleSearch(true); true }
        }
        editMenuItem = b.toolbar.menu.add(R.string.viewer_edit).apply {
            setIcon(R.drawable.ic_edit)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false // 载入完才知道能不能编辑
            setOnMenuItemClickListener { enterEdit(); true }
        }
        saveMenuItem = b.toolbar.menu.add(R.string.viewer_save).apply {
            setIcon(R.drawable.ic_save)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
            setOnMenuItemClickListener { save(); true }
        }
        b.toolbar.menu.add(R.string.viewer_wrap).apply {
            isCheckable = true
            isChecked = Prefs.viewerWrap(this@TextViewerActivity)
            setOnMenuItemClickListener {
                val on = !Prefs.viewerWrap(this@TextViewerActivity)
                Prefs.setViewerWrap(this@TextViewerActivity, on)
                it.isChecked = on
                applyWrap()
                true
            }
        }
        lang = CodeHighlighter.langFor(name)
        if (lang != null) {
            b.toolbar.menu.add(R.string.viewer_theme).setOnMenuItemClickListener { pickTheme(); true }
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        isHtml = ext == "html" || ext == "htm"
        isPreviewable = isHtml || lang?.markdown == true
        if (isPreviewable) {
            previewMenuItem = b.toolbar.menu.add(R.string.viewer_preview).apply {
                isCheckable = true
                setOnMenuItemClickListener {
                    previewMode = !previewMode
                    it.isChecked = previewMode
                    if (previewMode) renderPreview()
                    applyPreviewVisibility()
                    true
                }
            }
        }
        b.scroll.post { applyWrap() } // 等布局完拿到视口宽

        // displayName 带上:content:// 的 path 里没有文件名,丢了它扩展名就没了(语法高亮/预览判定要用)
        currentFile = XFile(scheme = scheme, path = path, isDir = false, displayName = name)
        setupWebView()
        wireSearchBar()
        load(currentFile, intent.getBooleanExtra(EXTRA_PREVIEW, false))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = handleBack()

    override fun onDestroy() {
        b.content.removeCallbacks(relightTask)
        super.onDestroy()
    }

    /** 编辑中的返回:先退编辑态,有改动先问;都没有才真退出。 */
    private fun handleBack() {
        when {
            saving -> return // 写入进行中,别让 Activity 跑掉
            editMode && dirty -> confirmDiscard()
            editMode -> exitEdit()
            else -> finish()
        }
    }

    /**
     * 预览模式 WebView:JS 关闭(纯展示,不需要脚本能力,降低攻击面);相对资源请求
     * (图片/CSS)靠 [shouldInterceptRequest] 映射回 [currentFile] 同目录,经统一的
     * FileSystem 抽象读取——本地/压缩包内/SMB/WebDAV 等来源天然都能显示。内部相对
     * 链接与外部 http(s) 链接都拦截:前者暂不支持文档间跳转,后者丢给系统浏览器。
     */
    private fun setupWebView() {
        b.webview.settings.javaScriptEnabled = false
        b.webview.settings.setSupportZoom(true)
        b.webview.settings.builtInZoomControls = true
        b.webview.settings.displayZoomControls = false
        b.webview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (request.url.host != WEBVIEW_HOST) return null
                return runCatching {
                    // Uri.path 已经是解码过的路径,且 base URL 本身就是文件真实所在目录
                    // (见 webviewBaseUrl()),浏览器解析 "../" 时按真实目录深度往上跳,
                    // 不会在这之前被"假根路径"提前夹断——父目录相对引用天然可用。
                    val abs = normalizePath(request.url.path.orEmpty().ifEmpty { return null })
                    val target = XFile(currentFile.scheme, abs, isDir = false)
                    val input = FsRegistry.of(target).openInput(target)
                    WebResourceResponse(OpenFiles.mimeOf(target.name), "", input)
                }.getOrNull()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                }
                return true
            }
        }
    }

    private fun renderPreview() {
        val html = if (isHtml) raw else MarkdownHtml.render(raw)
        b.webview.loadDataWithBaseURL(webviewBaseUrl(), html, "text/html", "utf-8", null)
        previewLoaded = true
    }

    /**
     * 假 origin(不联网)+ 文件真实所在目录路径,拼成相对资源解析的 base URL。
     * 路径深度必须是真的——如果固定用根路径("https://host/"),浏览器解析
     * "../xxx" 会在根处直接夹断(RFC 3986 remove_dot_segments 对着空路径没法再往上跳),
     * 父目录的相对引用就永远失效,只有同级/子目录能用。按真实目录深度铺出 base URL,
     * ".." 才能正确沿真实层级网上跳。
     */
    private fun webviewBaseUrl(): String {
        val dir = currentFile.parentPath.trim('/')
        val encoded = if (dir.isEmpty()) "" else dir.split('/').joinToString("/") { Uri.encode(it) }
        return "https://$WEBVIEW_HOST/$encoded/"
    }

    private fun applyPreviewVisibility() {
        b.scroll.visibility = if (previewMode) View.GONE else View.VISIBLE
        b.webview.visibility = if (previewMode) View.VISIBLE else View.GONE
        syncEditMenu() // 搜索/编辑入口在预览下都要让位
        if (previewMode && b.searchBar.visibility == View.VISIBLE) toggleSearch(false)
    }

    /** 规整路径:折叠 `.`/`..`,去掉多余斜杠(与 [com.twig.app.M3uPlaylist] 同款算法)。 */
    private fun normalizePath(path: String): String {
        val stack = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            else -> stack.add(p)
        }
        return "/" + stack.joinToString("/")
    }

    /**
     * 自动换行:TextView 在 HorizontalScrollView 里被 UNSPECIFIED 测量,layoutParams
     * 宽度不起作用,但 maxWidth 在非 EXACTLY 模式下仍生效——限到视口宽即换行,
     * 横向滚动自然消失;关掉恢复 MAX_VALUE 回到长行横滚。不用动布局层级。
     */
    private fun applyTextSize(sp: Float) {
        contentTextSizeSp = sp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        b.content.setTextSize(TypedValue.COMPLEX_UNIT_SP, contentTextSizeSp)
    }

    private fun applyWrap() {
        b.content.maxWidth = if (Prefs.viewerWrap(this)) {
            // 减掉光标预留位:CodeEditText 量完会把它加回去,不减的话换行后正好比视口
            // 宽出这几像素,凭空多出一段横向滚动
            val vp = if (b.scroll.width > 0) b.scroll.width else resources.displayMetrics.widthPixels
            (vp - b.content.cursorPad).coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
    }

    // ---- 搜索 ----

    private fun wireSearchBar() {
        b.searchInput.doAfterTextChanged { runSearch(jumpFirst = true) }
        b.searchInput.setOnEditorActionListener { _, _, _ -> move(1); true }
        b.searchPrev.setOnClickListener { move(-1) }
        b.searchNext.setOnClickListener { move(1) }
        b.searchClose.setOnClickListener { toggleSearch(false) }
    }

    private fun toggleSearch(show: Boolean) {
        b.searchBar.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            b.searchInput.requestFocus()
            imm.showSoftInput(b.searchInput, 0)
        } else {
            imm.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
            b.searchInput.setText("")
            runSearch(jumpFirst = false) // 清空高亮
        }
    }

    /** 全文查找并高亮所有命中(不区分大小写,命中数封顶防极端输入)。 */
    private fun runSearch(jumpFirst: Boolean) {
        val sp = b.content.text as? Spannable
        hitSpans.forEach { sp?.removeSpan(it) }
        hitSpans.clear()
        curSpan?.let { sp?.removeSpan(it) }
        curSpan = null
        cur = -1
        val q = b.searchInput.text.toString()
        matchLen = q.length
        if (q.isEmpty() || sp == null) {
            matches = emptyList()
            b.searchCount.text = ""
            return
        }
        val ql = q.lowercase()
        val list = ArrayList<Int>()
        var i = rawLower.indexOf(ql)
        while (i >= 0 && list.size < MAX_MATCHES) {
            list.add(i)
            i = rawLower.indexOf(ql, i + ql.length)
        }
        matches = list
        for (m in list) {
            val s = BackgroundColorSpan(HIT_BG)
            sp.setSpan(s, m, m + matchLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            hitSpans.add(s)
        }
        if (list.isEmpty()) b.searchCount.text = "0" else if (jumpFirst) jumpTo(0)
    }

    private fun move(delta: Int) {
        if (matches.isEmpty()) return
        jumpTo((cur + delta + matches.size) % matches.size)
    }

    private fun jumpTo(index: Int) {
        val sp = b.content.text as? Spannable ?: return
        cur = index
        val pos = matches[index]
        curSpan?.let { sp.removeSpan(it) }
        val s = BackgroundColorSpan(CUR_BG)
        sp.setSpan(s, pos, pos + matchLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        curSpan = s
        b.searchCount.text = "${index + 1}/${matches.size}"
        b.content.post {
            val layout = b.content.layout ?: return@post
            val line = layout.getLineForOffset(pos)
            val y = layout.getLineTop(line) - b.scroll.height / 3
            b.scroll.smoothScrollTo(0, maxOf(0, y))
            val x = layout.getPrimaryHorizontal(pos).toInt() - b.hscroll.width / 3
            b.hscroll.smoothScrollTo(maxOf(0, x), 0)
        }
    }

    // ---- 编辑 ----

    private fun wireEditor() {
        b.content.doAfterTextChanged {
            if (settingText || !editMode) return@doAfterTextChanged
            hlGen++ // 文本变了,在途的那批 token 作废
            if (!dirty) {
                dirty = true
                updateTitle()
            }
            if (liveHighlight) scheduleRelight()
        }
    }

    /** 不能编辑的理由;null 表示可以。来源不可写不在此列——那种情况直接不给入口。 */
    private fun editBlockReason(): String? = when {
        truncated -> getString(R.string.viewer_readonly_truncated, Format.size(MAX_BYTES))
        !isUtf8 -> getString(R.string.viewer_readonly_encoding)
        else -> null
    }

    private fun enterEdit() {
        editBlockReason()?.let { toast(it); return }
        if (!canEdit) return
        editMode = true
        if (b.searchBar.visibility == View.VISIBLE) toggleSearch(false)
        b.content.keyListener = savedKeyListener
        b.content.isCursorVisible = true
        // ★ 光标闪烁的定时器只在**焦点真的变化**时才重启(Editor.onFocusChanged →
        //   makeBlink),而 setCursorVisible(true) 只是 invalidate 一次。进编辑态时
        //   正文往往**早就是焦点**了——onCreate 的 setTextIsSelectable 让它成了页面上
        //   唯一可聚焦的视图,布局时就自动拿到焦点,requestFocus() 直接返回什么都不做。
        //   于是那一帧画出来的光标若正好落在"灭"的相位上,就再没人重画它,表现为
        //   「光标看不见,打一个字才出现」(文本变化会走 handleTextChanged → makeBlink)。
        //   先 clearFocus 逼出一次真正的焦点变化;它内部会让根视图重新找焦点,
        //   本来就只有正文可聚焦,焦点原地转一圈回来,闪烁也就活了。
        b.content.clearFocus()
        b.content.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.content, 0)
        syncEditMenu()
    }

    private fun exitEdit() {
        editMode = false
        b.content.keyListener = null
        b.content.isCursorVisible = false
        b.content.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.content.windowToken, 0)
        syncEditMenu()
    }

    private fun syncEditMenu() {
        editMenuItem?.isVisible = canEdit && !editMode && !previewMode
        saveMenuItem?.isVisible = editMode
        // 编辑期间搜索/预览让位:搜索的命中偏移会被编辑打乱,预览与编辑互斥
        searchMenuItem?.isVisible = !editMode && !previewMode
        previewMenuItem?.isVisible = !editMode
    }

    private fun updateTitle() {
        b.toolbar.title = if (dirty) "*$fileName" else fileName
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_discard_title)
            .setMessage(R.string.viewer_discard_msg)
            .setPositiveButton(R.string.viewer_save) { _, _ -> save(exitAfter = true) }
            .setNegativeButton(R.string.viewer_discard_ok) { _, _ ->
                dirty = false
                applyTheme() // 丢掉改动,把 raw 重新铺回去
                updateTitle()
                exitEdit()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun save(exitAfter: Boolean = false) {
        if (saving) return
        val text = b.content.text.toString()
        saving = true
        b.loadingBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    writeAtomically(currentFile, text.toByteArray(Charsets.UTF_8))
                }
            }
            saving = false
            b.loadingBox.visibility = View.GONE
            r.fold(
                onSuccess = {
                    raw = text
                    rawLower = text.lowercase()
                    dirty = false
                    updateTitle()
                    if (isCode && !liveHighlight) relight() // 大文件打字期间没重扫,存完补一次
                    toast(getString(R.string.viewer_saved))
                    if (exitAfter) exitEdit() // 返回键问出来的保存:存完就该走
                },
                onFailure = { showSaveError(it) },
            )
        }
    }

    private fun scheduleRelight() {
        b.content.removeCallbacks(relightTask)
        b.content.postDelayed(relightTask, RELIGHT_DELAY)
    }

    private val relightTask = Runnable { relight() }

    /**
     * 编辑期间重新着色。**整篇重扫**——跨行注释/多行字符串/md 围栏的状态只有整篇
     * tokenize 才对(与 [DiffActivity] 整侧扫一次再切片同理),局部重扫会串色。
     * 扫描本身放后台,回主线程只做 [CodeHighlighter.applyTo] 的就地换 span。
     */
    private fun relight() {
        val l = lang ?: return
        val ed: Editable = b.content.text ?: return
        // ★ 输入法组词期间绝不动 span:composing 文本本身就是靠 span 标出来的,
        //   这一轮把它摘掉,拼音串当场散架(丢字/重复上屏)。推迟到上屏之后再补。
        if (BaseInputConnection.getComposingSpanStart(ed) >= 0) {
            scheduleRelight()
            return
        }
        val text = ed.toString()
        if (text.length > CodeHighlighter.MAX_HIGHLIGHT) return
        val gen = ++hlGen
        lifecycleScope.launch {
            val toks = withContext(Dispatchers.Default) { CodeHighlighter.tokenize(text, l) }
            if (gen != hlGen) return@launch // 扫的过程中又改了,这批作废
            val live: Editable = b.content.text ?: return@launch
            if (live.length != text.length) return@launch
            tokens = toks
            CodeHighlighter.applyTo(live, toks, currentTheme())
        }
    }

    // ---- 加载与主题 ----

    private fun load(file: XFile, startPreview: Boolean) {
        val l = lang
        b.loadingBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val read = readText(file)
                    val full = if (read.truncated) {
                        read.text + "\n\n" + getString(R.string.viewer_too_large, Format.size(MAX_BYTES))
                    } else {
                        read.text
                    }
                    // token 离线算好,主题切换时仅重上色,不重扫
                    val toks = if (l != null && full.length <= CodeHighlighter.MAX_HIGHLIGHT) {
                        CodeHighlighter.tokenize(full, l)
                    } else {
                        emptyList()
                    }
                    Loaded(full, full.lowercase(), toks, read.truncated, read.utf8, canWriteTo(file))
                }
            }
            b.loadingBox.visibility = View.GONE
            result.fold(
                onSuccess = { r ->
                    raw = r.text
                    rawLower = r.lower
                    tokens = r.tokens
                    truncated = r.truncated
                    isUtf8 = r.utf8
                    isCode = l != null
                    isMarkdown = l?.markdown == true
                    // 边打字边整篇重扫,量大了会掉帧;超阈值就只在进编辑前/保存后各上一次色
                    liveHighlight = isCode && raw.length <= CodeHighlighter.MAX_LIVE_HIGHLIGHT
                    // 截断/非 UTF-8 仍给按钮,点了 Toast 说明原因;来源只读则彻底没入口
                    canEdit = r.writable
                    applyTheme()
                    syncEditMenu()
                    if (startPreview && isPreviewable) {
                        previewMode = true
                        previewMenuItem?.isChecked = true
                        renderPreview()
                        applyPreviewVisibility()
                    } else if (intent.getBooleanExtra(EXTRA_EDIT, false)) {
                        enterEdit() // 建不成也无妨:enterEdit 自己会挡下不可编辑的情况
                    }
                },
                onFailure = {
                    setContentText(getString(R.string.viewer_load_failed, it.message ?: ""))
                },
            )
        }
    }

    private fun currentTheme(): CodeHighlighter.Theme =
        CodeHighlighter.THEMES[Prefs.codeTheme(this).coerceIn(0, CodeHighlighter.THEMES.size - 1)]

    /**
     * 把 [raw] 铺回视图并按主题上色。
     *
     * ★ 先建 Spannable 再 setText 会踩到大文件的老坑:文本以 EDITABLE 持有,
     * `setText(spannable, EDITABLE)` 要把万级 span 逐个拷进 SpannableStringBuilder
     * (平方级开销)。所以这里先 setText 纯文本(零 span 可拷),再往 Editable 上
     * 直接 [CodeHighlighter.applyTo],只剩一趟 setSpan。
     */
    private fun applyTheme() {
        // Markdown 正文用系统默认字体(区别于代码),围栏代码块/行内代码的 span 会
        // 自带 monospace 覆盖回来,其余文件类型不受影响,继续走布局里的等宽字体。
        b.content.typeface = if (isMarkdown) Typeface.DEFAULT else Typeface.MONOSPACE
        setContentText(raw)
        // 背景/默认前景色不分是否认识语言——没有词法着色的纯文本也套用同一套主题色,
        // 不然打开一个不认识扩展名的文件时颜色跟旁边高亮过的文件对不上。
        val t = currentTheme()
        b.scroll.setBackgroundColor(t.bg)
        b.content.setTextColor(t.fg)
        if (isCode) {
            b.content.text?.let { CodeHighlighter.applyTo(it, tokens, t) }
        }
        // 文本换了新实例,搜索高亮 span 全丢,若搜索栏开着就重打
        hitSpans.clear()
        curSpan = null
        if (b.searchBar.visibility == View.VISIBLE && b.searchInput.text.isNotEmpty()) {
            runSearch(jumpFirst = false)
        }
    }

    /** 铺文本;[settingText] 兜住 doAfterTextChanged,免得程序性赋值被当成用户编辑。 */
    private fun setContentText(text: CharSequence) {
        settingText = true
        b.content.setText(text, TextView.BufferType.EDITABLE)
        settingText = false
    }

    private fun pickTheme() {
        val names = CodeHighlighter.THEMES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_theme)
            .setSingleChoiceItems(names, Prefs.codeTheme(this).coerceIn(0, names.size - 1)) { d, i ->
                Prefs.setCodeTheme(this, i)
                // 编辑态不能重铺文本(光标与未保存的改动都会没),只就地换色
                if (editMode) {
                    val t = currentTheme()
                    b.scroll.setBackgroundColor(t.bg)
                    b.content.setTextColor(t.fg)
                    b.content.text?.let { e -> CodeHighlighter.applyTo(e, tokens, t) }
                } else {
                    applyTheme()
                }
                d.dismiss()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 保存失败走对话框不走 Toast:底层错误串(SMB 的 NT 状态等)长,Toast 显示不全。 */
    private fun showSaveError(e: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_save_failed_title)
            .setMessage(e.message ?: e.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private class Loaded(
        val text: String,
        val lower: String,
        val tokens: List<CodeHighlighter.Token>,
        val truncated: Boolean,
        val utf8: Boolean,
        val writable: Boolean,
    )

    private class ReadResult(val text: String, val truncated: Boolean, val utf8: Boolean)

    /**
     * 读取文本,并顺带判定内容是不是合法 UTF-8——这决定能不能编辑:宽容解码把非法
     * 字节换成 U+FFFD,显示成乱码尚可接受(只是看),但存回去 U+FFFD 会被当成真字符
     * 写下去,原字节永久丢失。所以先用严格解码器试一遍,成功就直接用它的结果,
     * 失败才回落到宽容解码(显示行为与从前一致)并标记不可编辑。
     */
    private fun readText(file: XFile): ReadResult {
        FsRegistry.of(file).openInput(file).use { input ->
            val buf = ByteArray(MAX_BYTES.toInt())
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            val truncated = input.read() >= 0
            val strict = strictUtf8(buf.copyOf(read))
            if (strict != null) return ReadResult(strict, truncated, utf8 = true)
            // 截断处很可能正好切在多字节字符中间——那是我们自己切的,不能算文件编码有
            // 问题(截断本身已经禁用编辑了,不必再叠一条编码理由)。
            return ReadResult(String(buf, 0, read, Charsets.UTF_8), truncated, utf8 = truncated)
        }
    }

    companion object {
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PREVIEW = "preview"
        private const val EXTRA_EDIT = "edit"
        private const val MAX_BYTES = 1L * 1024 * 1024 // 1 MB 上限,避免 OOM
        private const val MAX_MATCHES = 2000
        private const val RELIGHT_DELAY = 300L // 打字停顿多久后重着色
        private const val MIN_TEXT_SIZE_SP = 8f
        private const val MAX_TEXT_SIZE_SP = 40f
        private const val HIT_BG = 0x66FFC107 // 全部命中:半透明琥珀
        private val CUR_BG = 0xB3FF6F00.toInt() // 当前命中:深橙

        // 预览 WebView 的假源:shouldInterceptRequest 靠 host 识别"这是我们自己发出的
        // 相对资源请求",不是真的联网——图片/CSS 等相对路径实际经 FileSystem 读取。
        // base URL 的路径部分按当前文件真实所在目录动态拼(见 webviewBaseUrl()),
        // 不能固定成根路径,否则 "../" 会被提前夹断,见该函数注释。
        private const val WEBVIEW_HOST = "twig.local"

        /** [edit]:载入完直接进编辑态(新建空文件后打开用,省一次点击)。 */
        fun start(context: Context, file: XFile, preview: Boolean = false, edit: Boolean = false) {
            context.startActivity(
                Intent(context, TextViewerActivity::class.java).apply {
                    putExtra(EXTRA_SCHEME, file.scheme)
                    putExtra(EXTRA_PATH, file.path)
                    putExtra(EXTRA_NAME, file.name)
                    if (preview) putExtra(EXTRA_PREVIEW, true)
                    if (edit) putExtra(EXTRA_EDIT, true)
                },
            )
        }
    }
}
