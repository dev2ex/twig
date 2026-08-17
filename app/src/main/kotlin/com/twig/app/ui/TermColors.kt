package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.twig.app.Prefs
import com.twig.app.R
import java.io.File
import java.util.Properties

/**
 * 终端配色方案。用的就是 termux 官方 `~/.termux/colors.properties` 那套格式
 * (`foreground` / `background` / `cursor` / `color0..255`,值为 `#RGB`、`#RRGGBB`
 * 或 `#AARRGGBB`),解析交给 termux 自己的 `TerminalColorScheme.updateWith`,
 * 于是 base16 那一大堆现成方案(chriskempson/base16-xresources 等)可以直接导入。
 *
 * `TerminalColors.COLOR_SCHEME` 是进程内**静态**的,新建会话的 `TerminalColors()`
 * 构造时就从它拷贝,所以换方案后新会话自动生效;已经建好的会话得各自
 * `mColors.reset()` 才会重新取值(见 [applyToSessions])。
 */
object TermColors {

    private const val DIR = "colors"

    /** 内置方案的 Prefs 值前缀;其余非空值一律当作自定义文件的绝对路径。 */
    private const val BUILTIN = "builtin:"

    /**
     * 随包内置的方案(assets/colors/<id>.properties,四个加起来 2KB)。
     * 名字是配色本身的专有名词,不翻译。
     */
    val PRESETS = listOf(
        "base16-atelierseaside-dark" to "Atelier Seaside Dark",
        "base16-atelierseaside-light" to "Atelier Seaside Light",
        "solarized-dark" to "Solarized Dark",
        "solarized-light" to "Solarized Light",
    )

    /** 合法值:#RGB / #RRGGBB / #AARRGGBB。 */
    private val COLOR = Regex("^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?([0-9a-fA-F]{2})?$")

    /** 当前方案的显示名;null = termux 默认配色。 */
    fun currentName(ctx: Context): String? {
        val id = Prefs.terminalColors(ctx)
        if (id.isEmpty()) return null
        if (id.startsWith(BUILTIN)) {
            val key = id.removePrefix(BUILTIN)
            return PRESETS.firstOrNull { it.first == key }?.second
        }
        val f = File(id)
        return if (f.isFile) f.name else null
    }

    /** 已导入的自定义方案文件名(内置方案返回 null),用来在选择列表里单列一项。 */
    fun customName(ctx: Context): String? {
        val id = Prefs.terminalColors(ctx)
        if (id.isEmpty() || id.startsWith(BUILTIN)) return null
        val f = File(id)
        return if (f.isFile) f.name else null
    }

    /**
     * 配色选择列表:termux 默认 + 内置方案 +(已导入的自定义)+「导入…」。
     * 选中即生效;终端页与设置页共用,[onChanged] 各自刷新界面。
     */
    fun showPicker(act: Activity, onImport: () -> Unit, onChanged: () -> Unit) {
        val ids = ArrayList<String?>() // null 表示末尾的「导入…」
        val labels = ArrayList<String>()
        ids += ""
        labels += act.getString(R.string.settings_term_colors_default)
        for ((key, name) in PRESETS) {
            ids += BUILTIN + key
            labels += name
        }
        customName(act)?.let {
            ids += Prefs.terminalColors(act)
            labels += it
        }
        ids += null
        labels += act.getString(R.string.settings_term_colors_import)

        AlertDialog.Builder(act)
            .setTitle(R.string.settings_term_colors)
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(Prefs.terminalColors(act))) { dlg, w ->
                dlg.dismiss()
                val id = ids[w]
                if (id == null) {
                    onImport()
                } else {
                    Prefs.setTerminalColors(act, id)
                    apply(act)
                    applyToSessions()
                    onChanged()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 把配置套进全局方案。`updateWith` 内部会先 reset,所以传空 Properties
     * 就是恢复 termux 默认,切换方案也不会残留上一套的颜色。
     */
    fun apply(ctx: Context) {
        val props = Properties()
        val id = Prefs.terminalColors(ctx)
        when {
            id.startsWith(BUILTIN) -> runCatching {
                ctx.assets.open("colors/${id.removePrefix(BUILTIN)}.properties")
                    .use { props.load(it) }
            }
            id.isNotEmpty() -> runCatching { File(id).inputStream().use { props.load(it) } }
        }
        runCatching { TerminalColors.COLOR_SCHEME.updateWith(sanitize(props)) }
    }

    /** 让已经建好的会话重新取色(新建的会话在构造里就拷过了,不用管)。 */
    fun applyToSessions() {
        for (t in TermManager.list()) t.emulatorOrNull?.mColors?.reset()
    }

    fun bg(): Int = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]

    fun fg(): Int = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]

    /** 附加键条:在背景色上掺一点前景色,深浅两种方案都能和终端区分开又不刺眼。 */
    fun keyBarBg(): Int = blend(bg(), fg(), 0.10f)

    /** 修饰键按下时的底色,比键条再明显一档(仍是同色系,不抢眼)。 */
    fun keyActiveBg(): Int = blend(bg(), fg(), 0.32f)

    fun clear(ctx: Context) {
        Prefs.setTerminalColors(ctx, "")
        runCatching { File(ctx.filesDir, DIR).deleteRecursively() }
        apply(ctx)
    }

    /**
     * 导入 SAF 选中的 .properties:能解析出至少一条认识的颜色才算数。
     * 与字体导入同样拷进应用私有目录(content:// 权限跨重启不可靠),只留一份。
     */
    fun import(ctx: Context, uri: Uri): String? {
        val props = Properties()
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { props.load(it) }
            sanitize(props).isNotEmpty()
        }.getOrDefault(false)
        if (!ok) return null

        val name = displayName(ctx, uri)
        val dir = File(ctx.filesDir, DIR)
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val saved = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }.isSuccess
        if (!saved) {
            runCatching { dir.deleteRecursively() }
            return null
        }
        Prefs.setTerminalColors(ctx, out.path)
        apply(ctx)
        return out.name
    }

    /**
     * 只留认得出的 key 和合法颜色值再交给 termux —— `updateWith` 是「先 reset 再
     * 逐条套用」,中途遇到坏值抛出去就会留下一套只套了一半的配色,先过滤掉最省事。
     */
    private fun sanitize(props: Properties): Properties {
        val out = Properties()
        for ((k, v) in props) {
            val key = k as? String ?: continue
            val value = (v as? String)?.trim() ?: continue
            if (!COLOR.matches(value)) continue
            val known = key == "foreground" || key == "background" || key == "cursor" ||
                (key.startsWith("color") && key.drop(5).toIntOrNull() in 0..255)
            if (known) out[key] = value
        }
        return out
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * ratio).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "colors.properties"
}
