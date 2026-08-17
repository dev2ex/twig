package com.twig.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** 轻量偏好存储(SharedPreferences)。 */
object Prefs {
    private const val FILE = "twig_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DENSITY = "row_density"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 行高密度:0=紧凑(默认) 1=正常 2=宽松。 */
    fun density(ctx: Context): Int = sp(ctx).getInt(KEY_DENSITY, 0)

    fun setDensity(ctx: Context, level: Int) {
        sp(ctx).edit().putInt(KEY_DENSITY, level.coerceIn(0, 2)).apply()
    }

    /**
     * 目录对比的上次选项(JSON,编码见 `CompareSession.optionsToJson`);空 = 用默认。
     * 整块存 JSON 而不是拆成一堆键:这组选项只被对比页整体读写,拆开只会多出一堆
     * 要同步维护的键名,而且加一项就得改三处。
     */
    fun compareOptions(ctx: Context): String = sp(ctx).getString("compare_options", "") ?: ""

    fun setCompareOptions(ctx: Context, json: String) {
        sp(ctx).edit().putString("compare_options", json).apply()
    }

    private const val KEY_REMEMBER = "remember_location"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val KEY_IMG_AUTOFIT = "img_autofit"
    private const val KEY_VIDEO_SCALE = "video_scale_mode"
    private const val KEY_TERM_KEEP_AWAKE = "terminal_keep_awake"

    /** 终端页屏幕常亮,默认关(避免无谓耗电)。 */
    fun terminalKeepAwake(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_TERM_KEEP_AWAKE, false)

    fun setTerminalKeepAwake(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_TERM_KEEP_AWAKE, on).apply()
    }

    /** 终端字号(px),双指缩放调整后记住;0 = 没设过,用屏幕密度算默认值。 */
    fun terminalTextSize(ctx: Context): Int = sp(ctx).getInt("terminal_text_size", 0)

    fun setTerminalTextSize(ctx: Context, px: Int) {
        sp(ctx).edit().putInt("terminal_text_size", px).apply()
    }

    /** 终端自定义字体文件路径(应用私有目录内);空 = 系统等宽。见 TerminalFont。 */
    fun terminalFont(ctx: Context): String = sp(ctx).getString("terminal_font", "") ?: ""

    fun setTerminalFont(ctx: Context, path: String) {
        sp(ctx).edit().putString("terminal_font", path).apply()
    }

    /** 终端配色方案文件路径(应用私有目录内);空 = termux 默认。见 TermColors。 */
    fun terminalColors(ctx: Context): String = sp(ctx).getString("terminal_colors", "") ?: ""

    fun setTerminalColors(ctx: Context, path: String) {
        sp(ctx).edit().putString("terminal_colors", path).apply()
    }

    /** 文本查看器自动换行,默认开。 */
    fun viewerWrap(ctx: Context): Boolean = sp(ctx).getBoolean("viewer_wrap", true)

    fun setViewerWrap(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("viewer_wrap", on).apply()
    }

    /** 文本查看器字号(sp),双指缩放调整后记住,默认 13(与布局初始值一致)。 */
    fun viewerTextSize(ctx: Context): Float = sp(ctx).getFloat("viewer_text_size", 13f)

    fun setViewerTextSize(ctx: Context, size: Float) {
        sp(ctx).edit().putFloat("viewer_text_size", size).apply()
    }

    /** 十六进制查看器字号(sp),默认 12——比文本小一号,一屏能多摆几列字节。 */
    fun hexTextSize(ctx: Context): Float = sp(ctx).getFloat("hex_text_size", 12f)

    fun setHexTextSize(ctx: Context, size: Float) {
        sp(ctx).edit().putFloat("hex_text_size", size).apply()
    }

    /** 网络音乐磁盘缓存保留的最近首数(播放/波形/seek 共用同一份下载,不重复下),默认 5。 */
    fun audioCacheCount(ctx: Context): Int = sp(ctx).getInt("audio_cache_count", 5)

    fun setAudioCacheCount(ctx: Context, n: Int) {
        sp(ctx).edit().putInt("audio_cache_count", n.coerceIn(1, 20)).apply()
    }

    /** 代码查看器配色主题(CodeHighlighter.THEMES 下标),默认 0 = Monokai。 */
    fun codeTheme(ctx: Context): Int = sp(ctx).getInt("code_theme", 0)

    fun setCodeTheme(ctx: Context, i: Int) {
        sp(ctx).edit().putInt("code_theme", i).apply()
    }

    /** 文本对比用上下两栏(默认关 = 左右并排 / 竖屏单侧切换)。 */
    fun diffStacked(ctx: Context): Boolean = sp(ctx).getBoolean("diff_stacked", false)

    fun setDiffStacked(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("diff_stacked", on).apply()
    }

    // 终端 window-change(resize)能力,按服务器 scheme 记忆(探测结果)
    const val RESIZE_UNKNOWN = 0
    const val RESIZE_OK = 1
    const val RESIZE_BROKEN = 2

    /** 该服务器对 window-change 的容忍度:未探测 / 支持 / 一发就断(永不再发)。 */
    fun termResizeCap(ctx: Context, scheme: String): Int =
        sp(ctx).getInt("term_resize_cap_$scheme", RESIZE_UNKNOWN)

    fun setTermResizeCap(ctx: Context, scheme: String, cap: Int) {
        sp(ctx).edit().putInt("term_resize_cap_$scheme", cap).apply()
    }


    /** 图片按屏幕方向旋转适配(横图在竖屏时旋转显示),默认开。 */
    /** 视频画面模式:0=最佳适配 1=裁切填满 2=拉伸填充。 */
    fun videoScaleMode(ctx: Context): Int = sp(ctx).getInt(KEY_VIDEO_SCALE, 0)

    fun setVideoScaleMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_VIDEO_SCALE, mode).apply()
    }

    /** 按住画面临时加速的倍数,存百分比(200 = 2×),默认 2×。 */
    fun longPressSpeed(ctx: Context): Float = sp(ctx).getInt("longpress_speed", 200) / 100f

    fun setLongPressSpeed(ctx: Context, percent: Int) {
        sp(ctx).edit().putInt("longpress_speed", percent).apply()
    }

    /** 记住视频播放进度、下次从上次的位置接着播,默认开(记录见 [PlaybackStore])。 */
    fun resumePlayback(ctx: Context): Boolean = sp(ctx).getBoolean("resume_playback", true)

    fun setResumePlayback(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("resume_playback", on).apply()
    }

    fun imageAutoFit(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_IMG_AUTOFIT, true)

    fun setImageAutoFit(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_IMG_AUTOFIT, on).apply()
    }


    /** 全屏(隐藏系统状态栏),默认关。 */
    fun fullscreen(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_FULLSCREEN, false)

    fun setFullscreen(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_FULLSCREEN, on).apply()
    }


    /** 是否记住上次打开的位置(默认开)。 */
    fun rememberLocation(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_REMEMBER, true)

    fun setRememberLocation(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_REMEMBER, on).apply()
    }

    /** 上次的活动面板(0=左,1=右),随"记住上次位置"一起生效。 */
    fun activePane(ctx: Context): Int = sp(ctx).getInt("active_pane", 0)

    fun setActivePane(ctx: Context, i: Int) {
        sp(ctx).edit().putInt("active_pane", i).apply()
    }

    /** 保存某面板的上次位置(展开的本地目录 + 当前目录)。 */
    fun saveLocation(ctx: Context, pane: Int, expandedPaths: List<String>, currentPath: String?) {
        sp(ctx).edit()
            .putString("loc_exp_$pane", expandedPaths.joinToString("\n"))
            .putString("loc_cur_$pane", currentPath ?: "")
            .apply()
    }

    fun locationExpanded(ctx: Context, pane: Int): List<String> =
        sp(ctx).getString("loc_exp_$pane", null)?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    fun locationCurrent(ctx: Context, pane: Int): String? =
        sp(ctx).getString("loc_cur_$pane", null)?.takeIf { it.isNotEmpty() }

    // restic 备份密码(按仓库路径保存,用户可选)
    private fun resticSp(ctx: Context) = ctx.getSharedPreferences("twig_restic_pw", Context.MODE_PRIVATE)

    fun resticPassword(ctx: Context, repoPath: String): String? =
        resticSp(ctx).getString(repoPath, null)

    fun setResticPassword(ctx: Context, repoPath: String, pw: String?) {
        resticSp(ctx).edit().apply { if (pw == null) remove(repoPath) else putString(repoPath, pw) }.apply()
    }

    // 加密压缩包的密码(按归档路径保存,用户可选;与 restic 那套同样的取舍)
    private fun archiveSp(ctx: Context) = ctx.getSharedPreferences("twig_archive_pw", Context.MODE_PRIVATE)

    fun archivePassword(ctx: Context, archivePath: String): String? =
        archiveSp(ctx).getString(archivePath, null)

    fun setArchivePassword(ctx: Context, archivePath: String, pw: String?) {
        archiveSp(ctx).edit().apply { if (pw == null) remove(archivePath) else putString(archivePath, pw) }.apply()
    }

    // ---- 缩略图 ----

    /** 缩略图总开关,默认关;关闭时列表与现状完全一致。 */
    fun thumbs(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_on", false)

    fun setThumbs(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_on", on).apply()
    }

    /** 网格模式:0=关闭(树内大图标) 1=仅媒体文件 2=全部文件。 */
    fun thumbsGrid(ctx: Context): Int = sp(ctx).getInt("thumbs_grid", 0)

    fun setThumbsGrid(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("thumbs_grid", mode.coerceIn(0, 2)).apply()
    }

    /** 网格里是否显示文件名,默认显示。 */
    fun thumbsGridNames(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_grid_names", true)

    fun setThumbsGridNames(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_grid_names", on).apply()
    }

    /** 是否对网络文件生成缩略图(整文件下载解码),默认关;关闭时仍读 EXIF 内嵌图。 */
    fun thumbsNetwork(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_network", false)

    fun setThumbsNetwork(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_network", on).apply()
    }

    /** 优先使用文件内置(EXIF)缩略图,默认关(内置图小,放大显示较糊)。 */
    fun thumbsEmbedded(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_embedded", false)

    fun setThumbsEmbedded(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_embedded", on).apply()
    }

    /** 显示隐藏文件(点开头的文件与目录),默认关。 */
    fun showHidden(ctx: Context): Boolean = sp(ctx).getBoolean("show_hidden", false)

    fun setShowHidden(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("show_hidden", on).apply()
    }

    /** 列表行之间画淡分割线,默认关。 */
    fun rowDivider(ctx: Context): Boolean = sp(ctx).getBoolean("row_divider", false)

    fun setRowDivider(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("row_divider", on).apply()
    }

    /**
     * 特权访问模式:[Privileged.OFF] / [Privileged.ROOT] / [Privileged.SHIZUKU],默认关。
     * 记住选择是为了下次启动自动接上,**不代表授权还在** —— Magisk 可以"仅一次"授权,
     * Shizuku 服务重启后也要重新握手,所以恢复时一律重新连一遍、失败就静默留在关闭态。
     */
    fun privilegedMode(ctx: Context): Int = sp(ctx).getInt("privileged_mode", Privileged.OFF)

    fun setPrivilegedMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("privileged_mode", mode).apply()
    }

    /** 影响主界面布局的偏好签名;从设置页返回时若变化,MainActivity 重建生效。 */
    fun uiSignature(ctx: Context): String = listOf(
        density(ctx), thumbs(ctx), thumbsGrid(ctx), thumbsGridNames(ctx), showHidden(ctx),
        rowDivider(ctx),
    ).joinToString(",")

    /** 上次活动的播放队列 id(NOW 或某个命名播放列表),供冷启动恢复播放定位到正确列表。 */
    fun lastQueueId(ctx: Context): String? = sp(ctx).getString("last_queue_id", null)?.takeIf { it.isNotEmpty() }

    fun setLastQueueId(ctx: Context, id: String) {
        sp(ctx).edit().putString("last_queue_id", id).apply()
    }

    // ---- 幻灯片 ----

    /** 幻灯片自动播放间隔(毫秒),默认 3000。 */
    fun slideshowIntervalMs(ctx: Context): Long = sp(ctx).getLong("slideshow_interval_ms", 3000L)

    fun setSlideshowIntervalMs(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong("slideshow_interval_ms", ms).apply()
    }

    /** 从目录菜单进入幻灯片时是否默认随机播放,默认关(播放中可随时用按钮切换)。 */
    fun slideshowShuffle(ctx: Context): Boolean = sp(ctx).getBoolean("slideshow_shuffle", false)

    fun setSlideshowShuffle(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("slideshow_shuffle", on).apply()
    }

    /** 顺序播完最后一张后是否回到开头继续,默认开;关闭则停在最后一张。 */
    fun slideshowLoop(ctx: Context): Boolean = sp(ctx).getBoolean("slideshow_loop", true)

    fun setSlideshowLoop(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("slideshow_loop", on).apply()
    }

    /** 自动播放期间屏幕是否常亮,默认开。 */
    fun slideshowKeepAwake(ctx: Context): Boolean = sp(ctx).getBoolean("slideshow_keep_awake", true)

    fun setSlideshowKeepAwake(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("slideshow_keep_awake", on).apply()
    }

    /** 递归扫描图片数量上限(防超大目录树无限扫描),默认 3000。 */
    fun slideshowMaxImages(ctx: Context): Int = sp(ctx).getInt("slideshow_max_images", 3000)

    fun setSlideshowMaxImages(ctx: Context, n: Int) {
        sp(ctx).edit().putInt("slideshow_max_images", n).apply()
    }

    /** 主题模式,取值为 AppCompatDelegate.MODE_NIGHT_*;默认跟随系统。 */
    fun themeMode(ctx: Context): Int =
        sp(ctx).getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setThemeMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
