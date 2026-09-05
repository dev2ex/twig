package com.twig.app.share

import android.content.Context
import com.twig.app.Format
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The browser-side face: directory listing + upload / new / rename / delete
 * + inline image / audio / video preview.
 *
 * Styles and scripts live in [WebPage]; this class only owns the **structure**
 * plus the few write operations on the server side.
 *
 * Two design choices:
 *  - **Sort and filter are done in the browser**. One request ships the
 *    whole directory, after which clicking headers or typing in the filter
 *    does not hit the server — and when the phone is the server, every
 *    round-trip is another SMB / SFTP hop, which is expensive.
 *  - **Previews use the file itself**, with no separate thumbnail service.
 *    Range is supported, so `<video>` seek bars work; adding server-side
 *    decode / scaling would mean running a decoder on the phone and caching
 *    thumbnails, which conflicts with size-first.
 */
class WebUi(
    private val ctx: Context,
    private val cfg: ShareConfig,
    private val root: ShareRoot,
) {

    private fun str(id: Int): String = ctx.getString(id)
    private fun str(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    // ---- Rendering ----

    /** The "all sources" mode's virtual root: lists each browsable source. */
    fun renderSources(res: HttpResponder) {
        val rows = root.sources().joinToString("") { s ->
            row(
                href = "/" + HttpServer.encodeSegment(s.segment) + "/",
                name = s.label,
                isDir = true, size = -1, mtime = 0, writable = false,
            )
        }
        page(
            res, str(R.string.share_web_sources), emptyList(), rows,
            writable = false, parent = null, stats = "", error = null,
        )
    }

    fun renderDir(req: HttpRequest, res: HttpResponder, dir: XFile) {
        // When the listing fails, **do not** return a bare 500: render the
        // page as usual and put the reason at the top. "Could not open" and
        // "opened but empty" look identical in the browser, and the reason
        // (permission denied, SMB connection dropped, directory gone) is
        // exactly the only clue that lets the user investigate.
        val children = runCatching { root.list(dir) }.getOrElse { e ->
            page(
                res, dir.name.ifEmpty { "/" }, crumbs(req.rawPath), "",
                writable = false, parent = parentHref(req.rawPath), stats = "",
                error = e.message ?: e::class.java.simpleName,
            )
            return
        }
        val writable = !cfg.readOnly && root.writable(dir) && dir.canWrite
        val sorted = children.sortedWith(
            compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() },
        )
        val base = req.rawPath.trimEnd('/')
        val rows = sorted.joinToString("") { f ->
            row(
                href = "$base/" + HttpServer.encodeSegment(f.name) + if (f.isDir) "/" else "",
                name = f.name,
                isDir = f.isDir,
                size = if (f.isDir) -1 else f.size,
                mtime = f.lastModified,
                writable = writable,
            )
        }
        val dirs = sorted.count { it.isDir }
        val files = sorted.size - dirs
        val bytes = sorted.filter { !it.isDir }.sumOf { maxOf(0L, it.size) }
        page(
            res, dir.name.ifEmpty { "/" }, crumbs(req.rawPath), rows,
            writable = writable, parent = parentHref(req.rawPath),
            stats = str(R.string.share_web_stats, dirs, files, Format.size(bytes)),
            error = null,
        )
    }

    /** Up-directory link; null when already at the share root (nowhere to go "up" to). */
    private fun parentHref(rawPath: String): String? {
        val segs = rawPath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        return "/" + segs.dropLast(1).joinToString("/") + if (segs.size > 1) "/" else ""
    }

    /** Breadcrumbs: every level is a clickable link. [rawPath] is already encoded; it is decoded only for display. */
    private fun crumbs(rawPath: String): List<Pair<String, String>> {
        val segs = rawPath.trim('/').split('/').filter { it.isNotEmpty() }
        val out = ArrayList<Pair<String, String>>()
        var acc = ""
        for (s in segs) {
            acc += "/$s"
            out += HttpServer.decodePath(s) to "$acc/"
        }
        return out
    }

    /**
     * One row. The `data-*` attributes hold the **raw values** used by the
     * front-end for sort and filter — the displayed text is formatted, and
     * sorting on it produces the lexicographic joke of "1 KB" < "9 B".
     */
    private fun row(
        href: String,
        name: String,
        isDir: Boolean,
        size: Long,
        mtime: Long,
        writable: Boolean,
    ): String {
        val cat = if (isDir) Cat.FOLDER else Cat.of(name)
        val sizeText = if (isDir) "—" else Format.size(maxOf(0L, size))
        val timeText = if (mtime > 0) DATE_FMT.format(java.util.Date(mtime)) else "—"
        // Image / audio / video file names preview inline; everything else
        // just downloads or drills into the directory
        val prev = if (isDir) null else previewKind(name)
        val prevAttr = prev?.let { """ data-prev="$it"""" } ?: ""
        val acts = buildString {
            if (!isDir) append(act("dl", "i-dl", str(R.string.share_web_download), href = href))
            if (writable) {
                append(act("rn", "i-pen", str(R.string.share_web_rename), name = name))
                append(act("del", "i-x", str(R.string.share_web_delete), name = name))
            }
        }
        val check = if (writable) {
            """<td class="ck"><input type="checkbox" class="sel" data-name="${attr(name)}"></td>"""
        } else {
            "" // Read-only shares have no checkbox column; leaving the cell empty would still take up column width for nothing
        }
        return """
            <tr data-c="${cat.id}" data-name="${attr(name.lowercase())}"
                data-size="${maxOf(0L, size)}" data-time="$mtime" data-dir="${if (isDir) 1 else 0}">
              $check
              <td class="ic">${icon(cat.icon)}</td>
              <td class="nm"><a href="${attr(href)}"$prevAttr>${html(name)}</a></td>
              <td class="sz">$sizeText</td>
              <td class="dt">$timeText</td>
              <td class="ac">$acts</td>
            </tr>
        """.trimIndent()
    }

    /** The little icon buttons at the end of each row; when [href] is non-null it is a download link, otherwise a button carrying data-name. */
    private fun act(cls: String, sym: String, title: String, href: String? = null, name: String? = null): String =
        if (href != null) {
            """<a class="act $cls" href="${attr(href)}" download title="${attr(title)}">${icon(sym)}</a>"""
        } else {
            """<button class="act $cls" data-name="${attr(name.orEmpty())}" """ +
                """title="${attr(title)}">${icon(sym)}</button>"""
        }

    private fun icon(id: String) = """<svg class="ic"><use href="#$id"/></svg>"""

    /** Short label for the share scope, used as the first breadcrumb. */
    private fun scopeLabel(): String = when (val s = cfg.scope) {
        is ShareScope.Dir -> s.label
        ShareScope.AllSources -> str(R.string.share_scope_all)
    }

    private fun page(
        res: HttpResponder,
        title: String,
        crumbs: List<Pair<String, String>>,
        rows: String,
        writable: Boolean,
        parent: String?,
        stats: String,
        error: String?,
    ) {
        // The first crumb is the share scope ("All sources" / shared directory
        // name), **not the device name** — the device name is already in the
        // top bar, and showing it again here would put the same word next to
        // itself twice
        val crumbHtml = buildString {
            append("""<a href="/">${icon("i-home")}${html(scopeLabel())}</a>""")
            for ((name, href) in crumbs) {
                append("""<i>/</i><a href="${attr(href)}">${html(name)}</a>""")
            }
        }
        val roChip = if (cfg.readOnly) {
            """<span class="chip" title="${attr(str(R.string.share_web_readonly))}">""" +
                """${icon("i-lock")}${html(str(R.string.share_mode_readonly))}</span>"""
        } else {
            ""
        }
        val errorHtml = error?.let {
            """<div class="err">${icon("i-x")}<span>${html(str(R.string.share_web_error, it))}</span></div>"""
        } ?: ""
        val tools = if (writable) {
            """
            <div class="tools">
              <button id="pick" class="primary">${icon("i-up-arrow")}${html(str(R.string.share_web_upload))}</button>
              <button id="mk">${icon("i-plus")}${html(str(R.string.share_web_new_folder))}</button>
              <button id="delsel" class="danger" hidden>${icon("i-x")}<span></span></button>
              <input type="file" id="files" multiple hidden>
            </div>
            <div id="drop">${html(str(R.string.share_web_drop_hint))}</div>
            <div id="up"><div id="upname"></div><div id="bar"><div id="fill"></div></div></div>
            """
        } else {
            ""
        }
        val parentRow = parent?.let {
            """<tr class="up">${if (writable) "<td class=\"ck\"></td>" else ""}""" +
                """<td class="ic">${icon("i-up")}</td>""" +
                """<td class="nm"><a href="${attr(it)}">${html(str(R.string.share_web_parent))}</a></td>""" +
                """<td class="sz"></td><td class="dt"></td><td class="ac"></td></tr>"""
        } ?: ""
        val selHead = if (writable) """<th class="ck"><input type="checkbox" id="all"></th>""" else ""
        val emptyHidden = if (rows.isEmpty() && error == null) "" else "hidden"
        val body = """
            ${WebPage.SPRITE}
            <header>
              <div class="hd">
                <div class="brand">${icon("i-device")}<span>${html(cfg.nameOrModel())}</span></div>
                $roChip
              </div>
              <nav class="crumbs">$crumbHtml</nav>
            </header>
            <main>
              $errorHtml
              $tools
              <section class="card">
                <div class="bar2">
                  <input id="filter" type="search" placeholder="${attr(str(R.string.share_web_filter))}"
                         autocomplete="off">
                  <span id="stats">${html(stats)}</span>
                </div>
                <table id="t">
                  <thead><tr>
                    $selHead
                    <th class="ic"></th>
                    <th class="s" data-k="name"><span>${html(str(R.string.share_web_name))}</span></th>
                    <th class="s sz" data-k="size"><span>${html(str(R.string.share_web_size))}</span></th>
                    <th class="s dt" data-k="time"><span>${html(str(R.string.share_web_modified))}</span></th>
                    <th class="ac"></th>
                  </tr></thead>
                  <tbody>$parentRow$rows</tbody>
                </table>
                <div id="empty" $emptyHidden>${html(str(R.string.share_web_empty))}</div>
              </section>
            </main>
            <div id="lb"><button id="lbx">${icon("i-x")}</button>
              <div id="lbbody"><div id="lbname"></div></div></div>
            <div id="toast"></div>
            <script>${WebPage.JS_COMMON}${if (writable) writeScript() else ""}</script>
        """.trimIndent()
        val doc = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\">" +
            "<title>${html(title)}</title><style>${WebPage.CSS}</style></head><body>$body</body></html>"
        res.send(200, "text/html; charset=utf-8", doc.toByteArray(Charsets.UTF_8))
    }

    /** The half of the script only present in writable shares (upload / new / rename / delete). */
    private fun writeScript(): String = """
        var drop=${'$'}('#drop'),upBox=${'$'}('#up'),fill=${'$'}('#fill'),upName=${'$'}('#upname'),
            input=${'$'}('#files'),delsel=${'$'}('#delsel');
        function post(op,body){var x=new XMLHttpRequest();
          x.open('POST',location.pathname+'?op='+op);
          if(x.upload){x.upload.onprogress=function(e){if(e.lengthComputable){
            upBox.style.display='block';fill.style.width=(e.loaded/e.total*100)+'%';}};}
          x.onload=function(){upBox.style.display='none';fill.style.width='0';
            if(x.status<300){location.reload();}
            else{toast((x.responseText||'')+' ('+x.status+')',1);}};
          x.onerror=function(){upBox.style.display='none';
            toast(${jsStr(str(R.string.share_web_net_error))},1);};
          x.send(body);}
        function upload(fs){if(!fs||!fs.length)return;var fd=new FormData(),names=[];
          for(var i=0;i<fs.length;i++){fd.append('f',fs[i],fs[i].name);names.push(fs[i].name);}
          upName.textContent=names.join(', ');upBox.style.display='block';post('upload',fd);}
        ${'$'}('#pick').onclick=function(){input.click();};
        input.onchange=function(){upload(input.files);input.value='';};
        ${'$'}('#mk').onclick=function(){
          var n=prompt(${jsStr(str(R.string.share_web_new_folder))});if(!n)return;
          var fd=new FormData();fd.append('name',n);post('mkdir',fd);};
        ['dragenter','dragover'].forEach(function(e){
          document.addEventListener(e,function(ev){ev.preventDefault();drop.classList.add('over');});});
        ['dragleave','drop'].forEach(function(e){
          document.addEventListener(e,function(ev){ev.preventDefault();drop.classList.remove('over');});});
        document.addEventListener('drop',function(ev){upload(ev.dataTransfer.files);});
        document.addEventListener('click',function(ev){
          if(!ev.target.closest)return;var b;
          if((b=ev.target.closest('.del'))){var n=b.getAttribute('data-name');
            if(!confirm(${jsStr(str(R.string.share_web_confirm_delete))}+'\n'+n))return;
            var fd=new FormData();fd.append('name',n);post('delete',fd);return;}
          if((b=ev.target.closest('.rn'))){var o=b.getAttribute('data-name');
            var v=prompt(${jsStr(str(R.string.share_web_new_name))},o);if(!v||v===o)return;
            var f2=new FormData();f2.append('name',o);f2.append('to',v);post('rename',f2);return;}});
        function selected(){return ${'$'}${'$'}('.sel:checked')
          .map(function(c){return c.getAttribute('data-name');});}
        function syncSel(){var n=selected().length;delsel.hidden=n===0;
          delsel.querySelector('span').textContent=
            ${jsStr(str(R.string.share_web_delete_selected))}+' ('+n+')';
          ${'$'}${'$'}('.sel').forEach(function(c){
            c.closest('tr').classList.toggle('sel-on',c.checked);});}
        document.addEventListener('change',function(ev){
          if(ev.target.id==='all'){${'$'}${'$'}('.sel').forEach(
            function(c){if(!c.closest('tr').hidden)c.checked=ev.target.checked;});}
          if(ev.target.classList.contains('sel')||ev.target.id==='all')syncSel();});
        delsel.onclick=function(){var names=selected();if(!names.length)return;
          if(!confirm(${jsStr(str(R.string.share_web_confirm_delete))}+'\n'+names.join('\n')))return;
          var fd=new FormData();names.forEach(function(n){fd.append('name',n);});
          post('delete',fd);};
    """.trimIndent()

    // ---- Write operations (browser form) ----

    /**
     * The browser-side write operations, distinguished by `?op=`:
     *  - `upload`: multipart/form-data, **written as it arrives** (see
     *    [Multipart]), no temporary file;
     *  - `mkdir` / `delete` / `rename`: also multipart (the front-end uses
     *    FormData everywhere, so there is one fewer encoding to handle).
     *    `delete` can carry multiple `name` fields — that is the bulk delete.
     */
    fun post(req: HttpRequest, res: HttpResponder) {
        val dir = root.resolve(req.path)
        if (dir == null || !dir.isDir) { res.sendText(404, "Not found"); return }
        if (!root.writable(dir) || !dir.canWrite) { res.sendText(403, "Read-only"); return }
        val boundary = boundaryOf(req.header("content-type"))
            ?: run { res.sendText(400, "Expected multipart/form-data"); return }
        val fs = FsRegistry.of(dir)

        when (req.query["op"]) {
            "upload" -> {
                var count = 0
                Multipart(req.body, boundary).forEachPart { _, filename, body ->
                    if (filename.isNullOrEmpty()) { body.drain(); return@forEachPart }
                    val safe = safeName(filename) ?: run { body.drain(); return@forEachPart }
                    val dest = fs.createFile(dir, safe)
                    fs.openOutput(dest, append = false).use { out -> body.pump(out) }
                    count++
                }
                root.invalidate(dir.path)
                res.sendText(200, "ok:$count")
            }
            "mkdir" -> {
                val name = fields(req.body, boundary)["name"]?.firstOrNull()?.let { safeName(it) }
                    ?: run { res.sendText(400, "Bad name"); return }
                fs.mkdir(dir, name)
                root.invalidate(dir.path)
                res.sendText(200, "ok")
            }
            "delete" -> {
                val names = fields(req.body, boundary)["name"].orEmpty().mapNotNull { safeName(it) }
                if (names.isEmpty()) { res.sendText(400, "Bad name"); return }
                val byName = root.list(dir).associateBy { it.name }
                var done = 0
                for (n in names) {
                    val victim = byName[n] ?: continue
                    FsRegistry.of(victim).delete(victim)
                    done++
                }
                root.invalidate(dir.path)
                if (done == 0) res.sendText(404, "Not found") else res.sendText(200, "ok:$done")
            }
            "rename" -> {
                val f = fields(req.body, boundary)
                val from = f["name"]?.firstOrNull()?.let { safeName(it) }
                val to = f["to"]?.firstOrNull()?.let { safeName(it) }
                if (from == null || to == null) { res.sendText(400, "Bad name"); return }
                val victim = root.list(dir).firstOrNull { it.name == from }
                    ?: run { res.sendText(404, "Not found"); return }
                // When a same-name target already exists, FileSystem.rename
                // is contracted to throw rather than silently overwrite
                FsRegistry.of(victim).rename(victim, to)
                root.invalidate(dir.path)
                res.sendText(200, "ok")
            }
            else -> res.sendText(400, "Unknown op")
        }
    }

    /**
     * Narrow a raw uploaded name down to something safe: only the last
     * segment (the browser can include `webkitRelativePath` when uploading
     * a whole directory), separators and `.`/`..` are stripped.
     * **Do not rely on the downstream FileSystem to filter this out** —
     * every implementation has its own path-joining rules and whether it
     * defends against this is a matter of luck; failing here means writing
     * outside the shared directory.
     */
    private fun safeName(raw: String): String? {
        val n = raw.replace('\\', '/').substringAfterLast('/').trim()
        if (n.isEmpty() || n == "." || n == "..") return null
        if ('/' in n) return null
        return n
    }

    /** Read all non-file fields at once (same name may repeat; bulk delete relies on this). */
    private fun fields(body: InputStream, boundary: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        Multipart(body, boundary).forEachPart { name, filename, part ->
            if (name != null && filename == null) {
                out.getOrPut(name) { ArrayList() }
                    .add(part.readBytes().toString(Charsets.UTF_8).trim())
            } else {
                part.drain()
            }
        }
        return out
    }

    private fun boundaryOf(contentType: String?): String? {
        val ct = contentType ?: return null
        if (!ct.startsWith("multipart/", ignoreCase = true)) return null
        val b = ct.split(';').map { it.trim() }.firstOrNull { it.startsWith("boundary=", true) }
            ?.substringAfter('=')?.trim() ?: return null
        return b.trim('"')
    }

    /**
     * The file category — only drives the **icon and colour**.
     *
     * Classified by extension, not by MIME — at listing time only the name is
     * in hand, and sniffing the header of every file to categorise them would
     * mean dozens of extra IOs per directory page (dozens of round-trips on
     * network sources).
     */
    private enum class Cat(val id: String, val icon: String) {
        FOLDER("folder", "i-folder"),
        IMAGE("image", "i-image"),
        VIDEO("video", "i-video"),
        AUDIO("audio", "i-audio"),
        ARCHIVE("archive", "i-archive"),
        APP("app", "i-app"),
        DOC("doc", "i-doc"),
        CODE("code", "i-code"),
        FILE("file", "i-file"),
        ;

        companion object {
            fun of(name: String): Cat = when (name.substringAfterLast('.', "").lowercase()) {
                in IMAGE_EXT -> IMAGE
                in VIDEO_EXT -> VIDEO
                in AUDIO_EXT -> AUDIO
                in ARCHIVE_EXT -> ARCHIVE
                in APP_EXT -> APP
                in DOC_EXT -> DOC
                in CODE_EXT -> CODE
                else -> FILE
            }

            private val IMAGE_EXT = setOf(
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg", "ico", "heic", "heif", "tif", "tiff",
            )
            private val VIDEO_EXT = setOf(
                "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "rmvb", "3gp", "ogv",
            )
            private val AUDIO_EXT = setOf(
                "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "ape", "opus", "dsf", "dff",
            )
            private val ARCHIVE_EXT = setOf(
                "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "jar",
                "tgz", "txz", "tbz", "tbz2",
            )
            private val APP_EXT = setOf("apk", "xapk", "apks", "aab")
            private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "epub")
            private val CODE_EXT = setOf(
                "kt", "kts", "java", "js", "ts", "json", "xml", "html", "htm", "css", "py",
                "sh", "bash", "c", "cpp", "h", "hpp", "go", "rs", "rb", "php", "yml", "yaml",
                "toml", "ini", "conf", "sql", "gradle", "properties",
            )
        }
    }

    /**
     * Whether clicking a name should preview it inline, and which tag to use.
     *
     * **Decided separately from [Cat]**: the icon should be "this is a video",
     * but the preview can only be one of the formats the browser can actually
     * play. mkv/ape/heic are all perfectly valid video/audio/image formats,
     * yet opening them in `<video>` is just a blank frame — in that case
     * honestly letting them download is better than popping up a black box.
     */
    private fun previewKind(name: String): String? =
        when (name.substringAfterLast('.', "").lowercase()) {
            in PREVIEW_IMAGE -> "image"
            in PREVIEW_VIDEO -> "video"
            in PREVIEW_AUDIO -> "audio"
            else -> null
        }

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        // The formats browsers can actually be relied on to play (see previewKind)
        private val PREVIEW_IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg", "ico")
        private val PREVIEW_VIDEO = setOf("mp4", "webm", "m4v", "ogv")
        private val PREVIEW_AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

        fun html(s: String): String = ShareHandler.xml(s)

        /** Attribute value: same escape set as text (including the quote), which is enough. */
        fun attr(s: String): String = ShareHandler.xml(s)

        /** A string literal to be embedded in <script>. */
        fun jsStr(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\u003c") // do not let a </script> in the string end the script block early
                '&' -> append("\\u0026")
                else -> append(c)
            }
            append('"')
        }
    }
}
