package com.twig.app.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.twig.app.Prefs
import java.io.File

/**
 * 终端自定义字体:用户导入的 ttf/otf 存进应用私有目录(SAF 的 content:// 权限
 * 跨进程/重启后不可靠,和 SFTP 私钥导入同一套路——拷进来自己管)。
 *
 * 为什么要给终端换字体:termux 的 `TerminalRenderer` 把列宽定为 `measureText("X")`,
 * 画每段文本时若实测宽度对不上「列数 × 列宽」,就 `canvas.scale(比例, 1f)` **只横向**
 * 压拉塞进网格。系统 MONOSPACE 里没有汉字,回落到 Noto Sans CJK(汉字 1.0em)而
 * 拉丁 X 只有 ≈0.6em,2 列目标 1.2em > 实测 1.0em → 中文被横向拉宽 20%;● (U+25CF)
 * 是 East Asian Ambiguous,wcwidth 算 1 列却是全角字形 → 被压到六成宽、高度不变,
 * 看着又细又高。换成 CJK 等宽字体(拉丁 0.5em / 汉字 1.0em,如更纱黑体 Sarasa Mono)
 * 两个比例都变成 1.0,变形自然消失。字体不打进包,零 APK 体积代价。
 */
object TerminalFont {

    private const val DIR = "fonts"

    /** 已导入字体的文件名;null = 用系统等宽。 */
    fun currentName(ctx: Context): String? {
        val p = Prefs.terminalFont(ctx)
        if (p.isEmpty()) return null
        val f = File(p)
        return if (f.isFile) f.name else null
    }

    /** 当前该用的字体;导入的文件丢失或加载失败一律回落系统等宽。 */
    fun typeface(ctx: Context): Typeface {
        val p = Prefs.terminalFont(ctx)
        if (p.isEmpty()) return Typeface.MONOSPACE
        return runCatching { Typeface.createFromFile(p) }.getOrNull() ?: Typeface.MONOSPACE
    }

    /** 清除自定义字体,回到系统等宽。 */
    fun clear(ctx: Context) {
        Prefs.setTerminalFont(ctx, "")
        runCatching { File(ctx.filesDir, DIR).deleteRecursively() }
    }

    /**
     * 导入 SAF 选中的字体文件:校验 sfnt 头 + 试加载,通过才落地并记住路径。
     * 只保留一份(先清空目录),返回文件名;失败返回 null。
     */
    fun import(ctx: Context, uri: Uri): String? {
        val name = displayName(ctx, uri)
        val dir = File(ctx.filesDir, DIR)
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            // 无效文件 createFromFile 在部分版本上不抛异常而是静默返回默认字体,
            // 所以先自己认 sfnt 魔数,再让系统试着加载一次
            isFont(out) && runCatching { Typeface.createFromFile(out) }.isSuccess
        }.getOrDefault(false)
        if (!ok) {
            runCatching { dir.deleteRecursively() }
            return null
        }
        Prefs.setTerminalFont(ctx, out.path)
        return out.name
    }

    /** sfnt 魔数:TrueType(0x00010000 / "true")、OpenType-CFF("OTTO")、集合("ttcf")。 */
    private fun isFont(f: File): Boolean = runCatching {
        val head = ByteArray(4)
        f.inputStream().use { if (it.read(head) != 4) return false }
        val tag = String(head, Charsets.ISO_8859_1)
        tag == "OTTO" || tag == "true" || tag == "ttcf" ||
            (head[0].toInt() == 0 && head[1].toInt() == 1 && head[2].toInt() == 0 && head[3].toInt() == 0)
    }.getOrDefault(false)

    private fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font.ttf"
}
