package com.twig.app.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.twig.app.Format
import com.twig.app.PlaybackStore
import com.twig.app.Prefs
import com.twig.app.Privileged
import com.twig.app.R
import rikka.shizuku.Shizuku

/**
 * 独立设置页(手写布局,不引 preference 库):显示(行高)+ 缩略图。
 * 改动写入 Prefs 即生效;MainActivity 回前台按 [Prefs.uiSignature] 变化自动重建。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val dp get() = resources.displayMetrics.density

    /** 缩略图子项行(总开关关闭时整组置灰禁用)。 */
    private val thumbRows = ArrayList<View>()

    private companion object {
        /** Shizuku 授权请求码;本页只有这一处请求,取值任意。 */
        const val SHIZUKU_REQ = 4001

        /** 与 Privileged 用同一个 tag,`adb logcat -s twig-priv` 一次看全整条链路。 */
        const val PRIV_TAG = "twig-priv"
    }

    /** 终端字体导入:拷进应用私有目录后整页重建,副标题跟着换。 */
    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TerminalFont.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_font_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_font_applied, name), Toast.LENGTH_SHORT).show()
            }
            build()
        }

    private fun fontSubtitle(): String =
        TerminalFont.currentName(this) ?: getString(R.string.settings_term_font_default)

    /** 终端配色导入:termux 的 colors.properties 格式,base16 现成方案可直接用。 */
    private val colorsPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TermColors.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_colors_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_colors_applied, name), Toast.LENGTH_SHORT).show()
            }
            TermColors.applyToSessions()
            build()
        }

    private fun colorsSubtitle(): String =
        TermColors.currentName(this) ?: getString(R.string.settings_term_colors_default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.toolbar))
            setTitleTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            title = getString(R.string.action_settings)
            navigationIcon = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.ic_up)
            setNavigationOnClickListener { finish() }
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (16 * dp).toInt())
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    toolbar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        actionBarHeight(),
                    ),
                )
                addView(
                    ScrollView(this@SettingsActivity).apply { addView(list) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
                )
            },
        )
        build()
    }

    private fun actionBarHeight(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)
        return android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
    }

    private fun build() {
        list.removeAllViews()
        thumbRows.clear()

        section(getString(R.string.settings_section_display))
        choiceRow(
            getString(R.string.action_density),
            arrayOf(getString(R.string.density_compact), getString(R.string.density_normal), getString(R.string.density_large)),
            get = { Prefs.density(this) },
            set = { Prefs.setDensity(this, it) },
        )
        switchRow(
            getString(R.string.settings_show_hidden), getString(R.string.settings_show_hidden_desc),
            get = { Prefs.showHidden(this) },
            set = { Prefs.setShowHidden(this, it) },
        )
        switchRow(
            getString(R.string.settings_row_divider), getString(R.string.settings_row_divider_desc),
            get = { Prefs.rowDivider(this) },
            set = { Prefs.setRowDivider(this, it) },
        )

        section(getString(R.string.settings_section_grid))
        choiceRow(
            getString(R.string.settings_grid_mode),
            arrayOf(
                getString(R.string.settings_grid_off),
                getString(R.string.settings_grid_media),
                getString(R.string.settings_grid_all),
            ),
            get = { Prefs.thumbsGrid(this) },
            set = { Prefs.setThumbsGrid(this, it) },
        )
        switchRow(
            getString(R.string.settings_grid_names), getString(R.string.settings_grid_names_desc),
            get = { Prefs.thumbsGridNames(this) },
            set = { Prefs.setThumbsGridNames(this, it) },
        )

        section(getString(R.string.settings_section_slideshow))
        val intervals = intArrayOf(2000, 3000, 5000, 8000, 15000, 30000)
        choiceRow(
            getString(R.string.settings_slideshow_interval),
            intervals.map { getString(R.string.settings_slideshow_interval_n, it / 1000) }.toTypedArray(),
            get = { intervals.indexOf(Prefs.slideshowIntervalMs(this).toInt()).coerceAtLeast(0) },
            set = { Prefs.setSlideshowIntervalMs(this, intervals[it].toLong()) },
        )
        switchRow(
            getString(R.string.settings_slideshow_shuffle), getString(R.string.settings_slideshow_shuffle_desc),
            get = { Prefs.slideshowShuffle(this) },
            set = { Prefs.setSlideshowShuffle(this, it) },
        )
        switchRow(
            getString(R.string.settings_slideshow_loop), getString(R.string.settings_slideshow_loop_desc),
            get = { Prefs.slideshowLoop(this) },
            set = { Prefs.setSlideshowLoop(this, it) },
        )
        switchRow(
            getString(R.string.settings_slideshow_keep_awake), getString(R.string.settings_slideshow_keep_awake_desc),
            get = { Prefs.slideshowKeepAwake(this) },
            set = { Prefs.setSlideshowKeepAwake(this, it) },
        )
        val maxCounts = intArrayOf(500, 1000, 3000, 10000, 30000)
        choiceRow(
            getString(R.string.settings_slideshow_max),
            maxCounts.map { getString(R.string.settings_slideshow_max_n, it) }.toTypedArray(),
            get = { maxCounts.indexOf(Prefs.slideshowMaxImages(this)).coerceAtLeast(0) },
            set = { Prefs.setSlideshowMaxImages(this, maxCounts[it]) },
        )

        section(getString(R.string.settings_section_thumbs))
        switchRow(
            getString(R.string.settings_thumbs_switch), getString(R.string.settings_thumbs_switch_desc),
            get = { Prefs.thumbs(this) },
            set = { Prefs.setThumbs(this, it); applyEnabled() },
        )
        thumbRows += switchRow(
            getString(R.string.settings_thumbs_network),
            getString(R.string.settings_thumbs_network_desc),
            get = { Prefs.thumbsNetwork(this) },
            set = { Prefs.setThumbsNetwork(this, it) },
        )
        thumbRows += switchRow(
            getString(R.string.settings_thumbs_embedded), getString(R.string.settings_thumbs_embedded_desc),
            get = { Prefs.thumbsEmbedded(this) },
            set = { Prefs.setThumbsEmbedded(this, it) },
        )
        thumbRows += actionRow(getString(R.string.settings_clear_thumbs_cache), Format.size(Thumbs.cacheBytes(this))) { subtitle ->
            Thumbs.clearCache(this)
            subtitle.text = Format.size(0)
            Toast.makeText(this, R.string.msg_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        section(getString(R.string.settings_section_privileged))
        actionRow(getString(R.string.settings_privileged), privilegedSubtitle()) { sub ->
            val options = arrayOf(
                getString(R.string.priv_mode_off),
                getString(R.string.priv_mode_root),
                getString(R.string.priv_mode_shizuku),
            )
            AlertDialog.Builder(this)
                // ★ 说明文字必须走 setCustomTitle,不能用 setMessage —— AlertDialog 的
                // 内容面板只放得下一样东西,同时设了 message 和选项列表时 message 赢,
                // **列表整个不渲染**。表现是对话框里只有一段说明和「取消」,一个选项都点不到,
                // 而代码看着完全正常(0.x 上线时就是这样,谁都选不了)。
                .setCustomTitle(dialogHeader(getString(R.string.settings_privileged), getString(R.string.priv_note_shizuku)))
                .setSingleChoiceItems(options, Privileged.active) { dlg, w ->
                    dlg.dismiss()
                    choosePrivileged(w, sub)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        section(getString(R.string.settings_section_terminal))
        actionRow(getString(R.string.settings_term_font), fontSubtitle()) { sub ->
            val custom = TerminalFont.currentName(this)
            val items = if (custom != null) {
                arrayOf(
                    getString(R.string.settings_term_font_import),
                    getString(R.string.settings_term_font_reset),
                )
            } else {
                arrayOf(getString(R.string.settings_term_font_import))
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_term_font)
                .setItems(items) { _, w ->
                    if (w == 0) {
                        // MIME 用 */*:不少文件应用不给 ttf 正确的 MIME,限死会选不到
                        fontPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_font)))
                    } else {
                        TerminalFont.clear(this)
                        sub.text = fontSubtitle()
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        actionRow(getString(R.string.settings_term_colors), colorsSubtitle()) { _ ->
            TermColors.showPicker(
                this,
                { colorsPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_colors))) },
                { build() },
            )
        }

        section(getString(R.string.settings_section_player))
        val speeds = intArrayOf(150, 200, 300)
        choiceRow(
            getString(R.string.settings_longpress_speed),
            speeds.map { getString(R.string.settings_speed_n, it / 100f) }.toTypedArray(),
            get = { speeds.indexOf((Prefs.longPressSpeed(this) * 100).toInt()).coerceAtLeast(0) },
            set = { Prefs.setLongPressSpeed(this, speeds[it]) },
        )
        switchRow(
            getString(R.string.settings_resume_playback),
            getString(R.string.settings_resume_playback_desc, PlaybackStore.MAX),
            get = { Prefs.resumePlayback(this) },
            set = { Prefs.setResumePlayback(this, it) },
        )
        actionRow(
            getString(R.string.settings_clear_playback),
            getString(R.string.settings_playback_count, PlaybackStore.all(this).size),
        ) { subtitle ->
            PlaybackStore.clear(this)
            subtitle.text = getString(R.string.settings_playback_count, 0)
            Toast.makeText(this, R.string.msg_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        section(getString(R.string.settings_section_music))
        val cacheCounts = intArrayOf(3, 5, 8, 10, 15)
        choiceRow(
            getString(R.string.settings_audio_cache_count),
            cacheCounts.map { getString(R.string.settings_audio_cache_n, it) }.toTypedArray(),
            get = { cacheCounts.indexOf(Prefs.audioCacheCount(this)).coerceAtLeast(0) },
            set = { Prefs.setAudioCacheCount(this, cacheCounts[it]); AudioCache.setMax(cacheCounts[it]) },
        )
        actionRow(getString(R.string.settings_clear_music_cache), Format.size(AudioCache.cacheBytes(this))) { subtitle ->
            AudioCache.clearCache(this)
            subtitle.text = Format.size(0)
            Toast.makeText(this, R.string.msg_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        applyEnabled()
    }

    private fun applyEnabled() {
        val on = Prefs.thumbs(this)
        thumbRows.forEach { row ->
            row.alpha = if (on) 1f else 0.4f
            setEnabledDeep(row, on)
        }
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    // ---- 行构建 ----

    private fun section(title: String) {
        list.addView(
            TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent))
                setPadding((8 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            },
        )
    }

    private fun addRow(
        title: String,
        subtitle: String?,
        end: View? = null,
        onClick: (() -> Unit)? = null,
    ): Pair<LinearLayout, TextView> {
        val subView = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            if (subtitle == null) visibility = View.GONE else text = subtitle
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (52 * dp).toInt()
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@SettingsActivity).apply {
                            text = title
                            textSize = 15f
                            setTextColor(
                                ContextCompat.getColor(this@SettingsActivity, R.color.text_primary),
                            )
                        },
                    )
                    addView(subView)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
            )
            end?.let {
                addView(it)
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = (8 * dp).toInt()
            }
            onClick?.let { cb -> setOnClickListener { cb() } }
        }
        list.addView(row)
        return row to subView
    }

    private fun switchRow(
        title: String,
        subtitle: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
    ): View {
        val sw = SwitchCompat(this).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        val (row, _) = addRow(title, subtitle, sw) { sw.toggle() }
        return row
    }

    private fun choiceRow(
        title: String,
        options: Array<String>,
        get: () -> Int,
        set: (Int) -> Unit,
    ): View {
        lateinit var subRef: TextView
        val (row, sub) = addRow(title, options[get().coerceIn(0, options.size - 1)]) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, get()) { dlg, w ->
                    set(w)
                    subRef.text = options[w]
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
        subRef = sub
        return row
    }

    // ---- 特权访问(root / Shizuku) ----

    /**
     * 标题 + 一段说明,给「既要说明又要给选项」的对话框当自定义标题用。
     * 手写布局,与本页其余部分一致(不引 preference / 不加 XML)。
     */
    private fun dialogHeader(title: String, note: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * dp).toInt(), (18 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        addView(
            TextView(this@SettingsActivity).apply {
                text = title
                textSize = 19f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            },
        )
        addView(
            TextView(this@SettingsActivity).apply {
                text = note
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                setPadding(0, (8 * dp).toInt(), 0, 0)
            },
        )
    }

    /**
     * 状态按**实际拿到的 uid** 说,不按用户选的模式说 —— Shizuku 服务本身既可以由
     * adb 起(shell,uid 2000)也可以由 root 起(uid 0),同一个「Shizuku」选项在两台
     * 设备上能力完全不同,写死"以 shell 运行"会骗人。
     */
    private fun privilegedSubtitle(): String = when {
        Privileged.active == Privileged.ROOT -> getString(R.string.priv_state_root)
        Privileged.active == Privileged.SHIZUKU && Privileged.uid == 0 ->
            getString(R.string.priv_state_shizuku_root)
        Privileged.active == Privileged.SHIZUKU ->
            getString(R.string.priv_state_shizuku, Privileged.uid)
        else -> getString(R.string.priv_state_off)
    }

    /**
     * ★ 授权必须在这里、由用户这一次点击驱动:Android 10+ 挡掉后台启动 Activity,
     * Magisk 的授权框从后台弹不出来,只会退化成一条通知 —— 表现为"点了没反应"。
     */
    private fun choosePrivileged(mode: Int, sub: TextView) {
        // 每个分支都留痕:前置检查里退出去的那几条原来只弹个 toast,查问题时
        // logcat 上一片空白,分不清"用户没点"和"点了但在第一道检查就被挡回来"。
        Log.i(PRIV_TAG, "choose(mode=$mode) ${Privileged.diagnostics(this)}")
        if (mode == Privileged.OFF) {
            Privileged.disable(this)
            sub.text = privilegedSubtitle()
            return
        }
        if (mode == Privileged.SHIZUKU) {
            if (!Privileged.shizukuInstalled(this)) {
                Log.w(PRIV_TAG, "choose: shizuku not installed")
                Toast.makeText(this, R.string.priv_shizuku_not_installed, Toast.LENGTH_LONG).show()
                return
            }
            if (!Privileged.shizukuRunning()) {
                Log.w(PRIV_TAG, "choose: shizuku binder not alive")
                Toast.makeText(this, R.string.priv_err_shizuku_absent, Toast.LENGTH_LONG).show()
                return
            }
            if (!Privileged.shizukuGranted()) {
                Log.i(PRIV_TAG, "choose: not granted yet, requesting")
                requestShizuku(sub)
                return
            }
        }
        connectPrivileged(mode, sub)
    }

    /** Shizuku 的授权结果是异步回来的;监听器用完即摘,免得设置页反复进出叠一堆。 */
    private fun requestShizuku(sub: TextView) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    connectPrivileged(Privileged.SHIZUKU, sub)
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.priv_err_shizuku_denied, Toast.LENGTH_LONG).show()
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(SHIZUKU_REQ) }
            .onSuccess { Log.i(PRIV_TAG, "requestPermission sent") }
            .onFailure {
                Log.w(PRIV_TAG, "requestPermission threw", it)
                Shizuku.removeRequestPermissionResultListener(listener)
                Toast.makeText(this, "${getString(R.string.priv_err_shizuku_failed)}\n\n$it", Toast.LENGTH_LONG).show()
            }
    }

    /** [Privileged.enable] 会一直阻塞到用户在授权框上点完,只能在后台线程跑。 */
    private fun connectPrivileged(mode: Int, sub: TextView) {
        Toast.makeText(this, R.string.priv_connecting, Toast.LENGTH_SHORT).show()
        Thread {
            val err = Privileged.enable(applicationContext, mode)
            val diag = if (err == null) null else Privileged.diagnostics(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sub.text = privilegedSubtitle()
                if (err == null) {
                    Toast.makeText(this, R.string.priv_granted, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // 失败走对话框而不是 toast:原因可能有好几行,而这正是用户唯一
                // 能拿去查/告诉我的线索,一闪而过等于没有。
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_privileged)
                    .setMessage("$err\n\n$diag")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun actionRow(title: String, subtitle: String, onClick: (TextView) -> Unit): View {
        lateinit var subRef: TextView
        val (row, sub) = addRow(title, subtitle) { onClick(subRef) }
        subRef = sub
        return row
    }
}
