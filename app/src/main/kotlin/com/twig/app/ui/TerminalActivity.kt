package com.twig.app.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.terminal.KeyHandler
import com.termux.terminal.TermBridge
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import com.twig.app.Prefs
import com.twig.app.PrivShell
import com.twig.app.Privileged
import com.twig.app.R
import com.twig.app.databinding.ActivityTerminalBinding
import com.twig.core.FsRegistry
import com.twig.fs.network.SftpFileSystem
import kotlin.math.roundToInt

/**
 * 一条常驻的终端会话(独立于 Activity 生命周期):存于 [TermManager],返回文件管理
 * 后仍在后台累积输出,重进原样接续。每条会话有独立的 SSH 连接与终端模拟器。
 */
class TermSession(
    val id: Long,
    /** SSH 会话 = 该服务器的 scheme;本地会话 = [LOCAL_SCHEME]。 */
    val scheme: String,
    var title: String,
) {
    /**
     * 本地 shell 会话:直接用 termux 自己的本地 PTY 那条路(`initializeEmulator`
     * fork 出 /system/bin/sh),不接 SSH——于是桥接线程、resize 探测、断线重连
     * 这些为远程准备的东西一概不需要。
     */
    val isLocal: Boolean get() = scheme == LOCAL_SCHEME

    lateinit var session: TerminalSession
    lateinit var emulator: TerminalEmulator

    /**
     * 还没就绪时为 null。SSH 会话在建视图之前就注入好模拟器,本地会话却要等
     * 进程 fork 起来才有——凡是可能在会话就绪前跑到的地方(界面回调、遍历所有
     * 会话)都得走这个,直接读 lateinit 会抛 UninitializedPropertyAccessException。
     */
    val emulatorOrNull: TerminalEmulator? get() = if (::emulator.isInitialized) emulator else null
    var shell: SftpFileSystem.ShellSession? = null
    @Volatile var connecting = true
    @Volatile var alive = false
    /** 每次 (重新)接通 shell 递增;桥接线程靠它判断自己是否已被新连接取代。 */
    @Volatile var gen = 0
    /** 用户主动结束会话时置真,阻止断线重连逻辑误把它当掉线。 */
    @Volatile var closing = false

    /** 会话进程起来的时刻。用来分辨"用户敲了 exit"和"根本没起来就死了"。 */
    @Volatile var startedAt = System.currentTimeMillis()

    /** 特权 PTY 会话:主设备端 fd 与子进程 pid(经 Shizuku 助手拿到);普通会话为 null。 */
    @Volatile var privFd: android.os.ParcelFileDescriptor? = null
    @Volatile var privPid = 0
    /** 仅当前展示中的会话设此回调刷新界面;后台会话为 null,只静默累积到模拟器。 */
    @Volatile var onOutput: (() -> Unit)? = null

    fun close() {
        closing = true
        alive = false
        connecting = false
        onOutput = null
        privFd?.let { pfd ->
            privFd = null
            // 先杀进程组再关 fd:只关 fd 的话 shell 要等到下次写才收到 SIGHUP,
            // 而一个卡在读的 shell 可能一直不写。
            if (privPid > 0) runCatching { com.twig.app.priv.Pty.nativeKill(privPid) }
            runCatching { pfd.close() }
            return
        }
        if (isLocal) {
            if (::session.isInitialized) runCatching { session.finishIfRunning() }
            return
        }
        val sh = shell
        shell = null
        if (sh != null) Thread({ runCatching { sh.close() } }, "twig-term-close").start()
    }
}

/** 本地 shell 会话的 scheme 标记(不是 FsRegistry 里的来源)。 */
const val LOCAL_SCHEME = "local"

/**
 * 多会话管理器(静态,跨 Activity 生命周期常驻)。终端页顶部下拉即这份列表,
 * 「在此打开终端 / SSH 终端 / 新建」都往这里追加会话,选中切换、显式结束才移除。
 */
object TermManager {
    private val sessions = ArrayList<TermSession>()
    @Volatile var current: TermSession? = null
        private set
    private var nextId = 1L

    @Synchronized fun list(): List<TermSession> = ArrayList(sessions)

    @Synchronized fun isEmpty(): Boolean = sessions.isEmpty()

    @Synchronized fun create(scheme: String, title: String): TermSession {
        val t = TermSession(nextId++, scheme, title)
        sessions.add(t)
        current = t
        return t
    }

    @Synchronized fun select(t: TermSession) {
        if (sessions.contains(t)) current = t
    }

    /** 移除并关闭一条会话;返回移除后应展示的会话(可能为 null)。 */
    @Synchronized fun remove(t: TermSession): TermSession? {
        val idx = sessions.indexOf(t)
        t.close()
        sessions.remove(t)
        if (current === t) {
            current = sessions.getOrNull(idx) ?: sessions.lastOrNull()
        }
        return current
    }

    @Synchronized fun closeAll() {
        for (s in sessions) s.close()
        sessions.clear()
        current = null
    }
}

/**
 * SSH 终端:Termux 终端模拟器/渲染 + SSHJ shell 通道,支持多会话。
 *
 * Termux 的 TerminalSession 是 final 且绑定本地 PTY(JNI 子进程),这里不启动它的
 * 进程,而是反射注入自建 TerminalEmulator(输出直写 SSH stdin),置 mShellPid=1
 * 使按键入队,由桥接线程搬运到 SSH。会话存于 [TermManager],顶部下拉切换;
 * Activity 在独立任务栈,可与文件管理来回切换,会话不断。
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var b: ActivityTerminalBinding
    private val main = Handler(Looper.getMainLooper())
    private val sessionClient = SessionClient()
    private var ctrlPending = false
    private var altPending = false
    private var shiftPending = false

    /** 当前展示中的会话(视图绑定的那条)。 */
    private var displayed: TermSession? = null
    private var suppressSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyFullscreen()
        CmdShims.ensureAsync(this) // 本地 shell 的命令补全垫片,后台建/补 symlink
        SshHome.ensureAsync(this) // .mkshrc 的 ssh alias 要 -F 这份 config,得先存在

        b.toolbar.setNavigationOnClickListener { finish() } // 返回文管,会话保持
        b.toolbar.menu.add(0, MENU_END_CURRENT, 0, getString(R.string.terminal_end_current)).apply {
            icon = ContextCompat.getDrawable(this@TerminalActivity, R.drawable.ic_close)
                ?.mutate()?.apply { setTint(Color.WHITE) }
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        b.toolbar.menu.add(0, MENU_NEW, 1, getString(R.string.terminal_new)).apply {
            icon = ContextCompat.getDrawable(this@TerminalActivity, R.drawable.ic_add)
                ?.mutate()?.apply { setTint(Color.WHITE) }
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        b.toolbar.menu.add(0, MENU_KEYBOARD, 2, getString(R.string.terminal_keyboard)).apply {
            icon = ContextCompat.getDrawable(this@TerminalActivity, R.drawable.ic_keyboard)
                ?.mutate()?.apply { setTint(Color.WHITE) }
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        b.toolbar.menu.add(0, MENU_NEW_LOCAL, 3, getString(R.string.terminal_new_local))
        // 特权会话:只在特权访问已连上、且那条路真能起来时才出现
        if (Privileged.active != Privileged.OFF && PrivShell.available(this, Privileged.active)) {
            b.toolbar.menu.add(0, MENU_NEW_PRIV, 4, getString(R.string.terminal_new_priv))
        }
        b.toolbar.menu.add(0, MENU_FONT, 4, getString(R.string.settings_term_font))
        b.toolbar.menu.add(0, MENU_COLORS, 5, getString(R.string.settings_term_colors))
        b.toolbar.menu.add(0, MENU_END_ALL, 6, getString(R.string.terminal_end_all))
        b.toolbar.menu.add(0, MENU_KEEP_AWAKE, 7, getString(R.string.terminal_keep_awake)).apply {
            isCheckable = true
            isChecked = Prefs.terminalKeepAwake(this@TerminalActivity)
        }
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_NEW -> { newOnCurrentServer(); true }
                MENU_KEYBOARD -> { toggleIme(); true }
                MENU_END_CURRENT -> { endCurrent(); true }
                MENU_FONT -> { chooseFont(); true }
                MENU_COLORS -> { chooseColors(); true }
                MENU_NEW_LOCAL -> { openLocal(null); true }
                MENU_NEW_PRIV -> { openPrivileged(null); true }
                MENU_END_ALL -> { TermManager.closeAll(); finish(); true }
                MENU_KEEP_AWAKE -> {
                    val on = !it.isChecked
                    it.isChecked = on
                    Prefs.setTerminalKeepAwake(this, on)
                    b.terminal.keepScreenOn = on
                    true
                }
                else -> false
            }
        }

        // ★ setTextSize 必须在 setTypeface 之前:后者直接读 mRenderer.mTextSize,
        // 而 mRenderer 只在 setTextSize 里创建,反过来会 NPE。
        applyTextSize(
            Prefs.terminalTextSize(this).takeIf { it > 0 }
                ?: (13 * resources.displayMetrics.density).toInt(),
        )
        applyFont()
        b.terminal.keepScreenOn = Prefs.terminalKeepAwake(this)
        b.terminal.setTerminalViewClient(ViewClient())
        b.sessionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                val t = TermManager.list().getOrNull(pos) ?: return
                if (t !== displayed) showSession(t)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        buildExtraKeys()
        applyColors()
        // 布局变化(软键盘弹出收回/横竖屏)会让 termux-view 改本地 emulator 行列;
        // 防抖后把新尺寸同步给远程 PTY,修 htop 等全屏程序键盘收回后的留白。
        // 是否敢发按服务器探测记忆,见 syncRemoteSize()。
        b.terminal.viewTreeObserver.addOnGlobalLayoutListener {
            scheduleSizeSync()
            syncKeyboardIcon() // 键盘弹/收都会改布局,顺手把按钮图标切到对应状态
        }

        handleIntent(intent)
    }

    /**
     * 当前终端字号(px)。双指缩放必须以它为基准累乘——termux 的 `mScaleFactor` 是
     * 累积因子且被 [ViewClient.onScale] 的返回值覆写,我们每次返回 1.0f 把它清零,
     * 若基准还取固定的初始字号,一次手势就只会在 base×阈值 那一档上反复设同一个值,
     * 表现为「捏了没反应」。
     */
    private var textSizePx = 0

    /**
     * 换字号。★ 必须自己补 `invalidate()`:termux 的 `setTextSize` → `updateSize()`
     * 里 `invalidate()` 关在「行列有变化」的分支内(`setTypeface` 就自己补了一次),
     * 字号微调没跨过行列边界时画面会保持旧字号不动。
     */
    private fun applyTextSize(px: Int) {
        textSizePx = px.coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        b.terminal.setTextSize(textSizePx)
        b.terminal.invalidate()
    }

    /** 已应用的字体路径,用来在设置页改过之后按需重新加载。 */
    private var appliedFont: String? = null

    /**
     * 应用设置里选的字体(见 [TerminalFont])。termux 的 `setTypeface` 自己会
     * `updateSize()` + `invalidate()`,换字体导致行列变化时还会回调 `onEmulatorSet`
     * 把新尺寸同步给远端,这里不用额外补。
     */
    private fun applyFont() {
        appliedFont = Prefs.terminalFont(this)
        b.terminal.setTypeface(TerminalFont.typeface(this))
    }

    /**
     * 套用配色。renderer 只画到网格边界,右/下不足一格的余数区域露的是 View 背景,
     * 所以 View 背景也得跟着方案走(默认那层黑色在浅色方案下会露出黑边);
     * 附加键条同理,不然浅色方案配深色键条很割裂。
     */
    private fun applyColors() {
        TermColors.apply(this)
        TermColors.applyToSessions()
        b.terminal.setBackgroundColor(TermColors.bg())
        b.extraKeys.setBackgroundColor(TermColors.keyBarBg())
        for (btn in extraKeyButtons) btn.setTextColor(TermColors.fg())
        markMod(ctrlBtn, ctrlPending)
        markMod(altBtn, altPending)
        markMod(shiftBtn, shiftPending)
        b.terminal.onScreenUpdated()
    }

    /** 顶栏「终端配色」快速入口。 */
    private val colorsPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TermColors.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_colors_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_colors_applied, name), Toast.LENGTH_SHORT).show()
            }
            applyColors()
        }

    private fun chooseColors() =
        TermColors.showPicker(
            this,
            { colorsPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_colors))) },
            { applyColors() },
        )

    /** 顶栏「终端字体」快速入口(细项同样在设置页里,见项目约定)。 */
    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TerminalFont.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_font_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_font_applied, name), Toast.LENGTH_SHORT).show()
            }
            applyFont()
        }

    private fun chooseFont() {
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
            .setTitle(getString(R.string.settings_term_font))
            .setItems(items) { _, w ->
                // MIME 用 */*:不少文件应用不给 ttf 正确的 MIME,限死会选不到
                if (w == 0) {
                    fontPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_font)))
                } else {
                    TerminalFont.clear(this)
                    applyFont()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** 防抖:软键盘动画期间布局连发,只同步落定后的最终尺寸。 */
    private val sizeSync = Runnable { syncRemoteSize() }

    private fun scheduleSizeSync() {
        main.removeCallbacks(sizeSync)
        main.postDelayed(sizeSync, 300)
    }

    /**
     * 把本地模拟器尺寸同步给远程 PTY(window-change)。按服务器记忆能力:
     * unknown 时首发即探测,3 秒内连接死了标 broken 此后永不再发(自动重连兜底,
     * 回到固定尺寸旧行为);活着标 ok 放心转发。resize() 与 stdin 同锁串行化、去重。
     *
     * ★ 发送必须在后台线程:主线程碰 socket 会抛 NetworkOnMainThreadException,
     * 而 SSHJ 在真正写 socket **之前**就已推进出站包序号与加密流状态——异常被吞
     * 后状态已脏,下一个正常出站包(往往是第一个按键)MAC 对不上,服务器直接
     * 关 TCP。这就是历史上「一输入就断」在**所有**服务器复现的真正根因,与
     * 服务器、termux-view 均无关。
     */
    private fun syncRemoteSize() {
        val t = displayed ?: return
        if (t.isLocal) return // 本地 PTY 的尺寸 TerminalView.updateSize 已经同步过了
        if (!t.alive) return
        val sh = t.shell ?: return
        if (Prefs.termResizeCap(this, t.scheme) == Prefs.RESIZE_BROKEN) return
        val cols = t.emulator.mColumns.coerceIn(20, 500)
        val rows = t.emulator.mRows.coerceIn(6, 300)
        Thread({
            val sent = sh.resize(cols, rows)
            if (sent && Prefs.termResizeCap(this, t.scheme) == Prefs.RESIZE_UNKNOWN) {
                val gen = t.gen
                main.postDelayed({
                    if (t.closing) return@postDelayed // 用户主动结束,无法判定,下次再探
                    val ok = t.gen == gen && t.alive && sh.isOpen
                    Prefs.setTermResizeCap(
                        this,
                        t.scheme,
                        if (ok) Prefs.RESIZE_OK else Prefs.RESIZE_BROKEN,
                    )
                    Log.i(TAG, "resize probe ${t.scheme}: ${if (ok) "ok" else "broken"}")
                }, 3000)
            }
        }, "twig-term-resize").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen() // 系统可能在切换后恢复状态栏,重新应用
    }

    /** 跟随主界面的「全屏(隐藏状态栏)」设置。 */
    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Prefs.fullscreen(this)) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    /** scheme 非空 = 来自文管的「打开终端」请求,总是新建一条;否则展示当前会话。 */
    private fun handleIntent(intent: Intent) {
        val scheme = intent.getStringExtra(EXTRA_SCHEME)
        if (scheme == LOCAL_SCHEME) {
            val priv = intent.getIntExtra(EXTRA_PRIV, Privileged.OFF)
            if (priv == Privileged.OFF) openLocal(intent.getStringExtra(EXTRA_DIR))
            else openPrivileged(intent.getStringExtra(EXTRA_DIR))
        } else if (scheme != null) {
            val title = intent.getStringExtra(EXTRA_TITLE)
                ?: TermManager.current?.title ?: scheme
            openNew(scheme, title, intent.getStringExtra(EXTRA_DIR), intent.getStringExtra(EXTRA_CMD))
        } else {
            // 一条会话都没有时不再直接退出:开一条本地 shell,顶栏那个终端入口
            // 因此可以常驻(点进来总有东西可用)
            val cur = TermManager.current
            if (cur == null) openLocal(intent.getStringExtra(EXTRA_DIR)) else showSession(cur)
        }
    }

    /** 「新建」菜单:在当前会话所属服务器上再开一条 shell。 */
    private fun newOnCurrentServer() {
        val cur = displayed ?: TermManager.current
        if (cur == null || cur.isLocal) { openLocal(null); return }
        openNew(cur.scheme, cur.title, cwd = null)
    }

    /**
     * 新建**本地** shell 会话:走 termux 原本的路子——`TerminalSession` 自己
     * `JNI.createSubprocess` fork 出 /system/bin/sh、分配 PTY、起读写线程喂模拟器,
     * 所以这里既不用桥接线程,也没有连接/重连/尺寸探测那一套(本地 resize 就是
     * 一次 ioctl,`TerminalView.updateSize` 已经替我们调了)。
     *
     * 限制:进程就是本 app 的 uid,没有 root;可用命令是系统自带的 mksh + toybox。
     * Android 10 起不能 execve 应用私有目录里的文件,所以脚本要用 `sh xxx.sh` 跑。
     */
    private fun openLocal(cwd: String?, priv: Int = Privileged.OFF) {
        val dir = cwd?.takeIf { java.io.File(it).isDirectory }
            ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        val cmd = PrivShell.command(this, priv)
        if (cmd == null) {
            // 起不来就直说是哪种身份起不来,别默默退回普通 shell —— 用户以为自己
            // 在 root 下敲命令,实际是应用 uid,那比报错危险得多。
            Toast.makeText(this, getString(R.string.terminal_priv_unavailable), Toast.LENGTH_LONG).show()
            if (TermManager.current == null) openLocal(cwd, Privileged.OFF)
            return
        }
        val (exe, args) = cmd
        if (priv != Privileged.OFF) Log.i(PRIV_TAG, "local session priv=$priv exec=$exe args=${args.joinToString(" ")}")
        val title = when (priv) {
            Privileged.ROOT -> getString(R.string.terminal_local_root)
            Privileged.SHIZUKU -> getString(R.string.terminal_local_shizuku)
            else -> getString(R.string.terminal_local)
        }
        val t = TermManager.create(LOCAL_SCHEME, title)
        val env = localEnv(dir)
        val s = TerminalSession(exe, dir, args, env, 5000, sessionClient)
        t.session = s

        // attach 让视图量出真实列/行。★ TerminalView.updateSize → TerminalSession.updateSize
        // 在模拟器为空时**会自己 initializeEmulator**(即 fork 出 shell),所以 attach
        // 这一步通常就已经把进程起起来了;下面必须先检查,否则再调一次会 fork 出第二个
        // shell,并把第一个的 fd/模拟器覆盖掉泄漏。
        showSession(t)
        b.terminal.postDelayed({
            if (t.closing) return@postDelayed
            if (s.getEmulator() == null) {
                // 视图当时还没量出尺寸(updateSize 会直接 return),补起一次
                val cols = (b.terminal.mEmulator?.mColumns ?: 80).coerceIn(20, 500)
                val rows = (b.terminal.mEmulator?.mRows ?: 24).coerceIn(6, 300)
                runCatching { s.initializeEmulator(cols, rows) }
                    .onFailure { Log.e(TAG, "local shell", it) }
                b.terminal.attachSession(s)
            }
            val em = s.getEmulator()
            if (em == null) {
                Toast.makeText(this, R.string.terminal_failed, Toast.LENGTH_SHORT).show()
                closeExited(t)
                return@postDelayed
            }
            t.emulator = em
            t.alive = true
            t.connecting = false
            refreshSessions()
        }, 120)
    }

    /**
     * 本地 shell 的环境。
     *
     * - **PATH 头上插 [CmdShims] 的 symlink 目录**:系统 PATH 目录应用列不出来
     *   (`drwxr-x--x`,只有 x 没有 r),命令补全一个候选都读不到;那个目录自己可读。
     * - **PATH 其余部分直接继承本进程的**(zygote 从 init.environ.rc 拿到的那一串),于是
     *   `/product/bin`、`/system_ext/bin`、`/vendor/bin`、`/apex/…/bin` 这些厂商/
     *   分区目录都在——有些 ROM 的 `ssh`、`curl` 就装在 `/product/bin` 下,写死
     *   `/system/bin` 会让它们统统"命令找不到"。
     * - **TERM 必须给**,不然全屏程序不知道终端能力。
     * - **HOME/TMPDIR 指到应用私有目录**:外部存储建不了 Unix socket,权限位也是
     *   固定的——`ssh` 要求私钥 600,放 /sdcard 上它会拒绝使用。
     * - **ENV 指到 [rcFile]**:mksh 交互式启动时 source 它,用户可以在里面加
     *   自己的 PATH / alias。
     */
    private fun localEnv(dir: String): Array<String> = (
        listOf(
            "TERM=xterm-256color",
            "HOME=" + filesDir.absolutePath,
            "TMPDIR=" + cacheDir.absolutePath,
            "PATH=" + CmdShims.dir(this).absolutePath + ":" +
                (System.getenv("PATH") ?: "/system/bin:/system/xbin"),
            "LANG=en_US.UTF-8",
            "PWD=" + dir,
            "ENV=" + rcFile().absolutePath,
            "EXTERNAL_STORAGE=" + android.os.Environment.getExternalStorageDirectory().absolutePath,
        ) + RUNTIME_ENV.mapNotNull { k -> System.getenv(k)?.let { "$k=$it" } }
        ).toTypedArray()

    /**
     * mksh 的启动脚本($ENV,交互式 shell 每次启动都会 source)。首次自动生成一份,
     * 先引系统自带的 /system/etc/mkshrc(提示符等),用户要加自己的 PATH / alias /
     * 函数往下写就行,新开会话即生效。
     *
     * **别再试图设 `HISTFILE`**(2026-08-06 实测定案):Android 自带的
     * `/system/bin/sh` 是 `HAVE_PERSISTENT_HISTORY=0` 编译的 mksh R59,
     * `strings` 里**连 `HISTFILE` 这个字符串都没有**(只有 `HISTSIZE`)——
     * 设了既不写也不读(预先造好文件、当环境变量传进去,`fc -l` 照样
     * "no history (yet)")。历史是纯内存数组,`fc` 也没有 bash `history -r`
     * 那样的加载命令,所以**跨会话翻历史在自带 shell 上做不到**;App 层也补不了,
     * 唯一通路是 PTY 输入,而输入即执行。要真做只能打包带持久历史的 shell
     * (放 nativeLibraryDir 绕过 W^X),与体积优先冲突,评估后不做。
     * `HISTSIZE` 是有效的,用来加长会话内历史。
     *
     * 文件内容一律英文:它落在磁盘上、用户会自己编辑,不像 UI 文案能跟随语言切换。
     */
    private fun rcFile(): java.io.File {
        val rc = java.io.File(filesDir, ".mkshrc")
        if (!rc.exists()) {
            runCatching {
                rc.writeText(
                    "# Twig local shell startup script (mksh \$ENV) — sourced by every new session.\n" +
                        "# Put your own PATH / aliases / functions below; a new session picks them up.\n" +
                        "[ -f /system/etc/mkshrc ] && . /system/etc/mkshrc\n" +
                        "\n" +
                        "# Longer in-session history. Note there is no cross-session history:\n" +
                        "# Android's /system/bin/sh is built without persistent history support\n" +
                        "# (no HISTFILE at all), so history lives in memory only and is gone\n" +
                        "# when the session closes. Setting HISTFILE here would do nothing.\n" +
                        "export HISTSIZE=5000\n" +
                        "\n" +
                        "alias ll='ls -lAh'\n" +
                        "alias l='ls -CF'\n" +
                        "\n" +
                        "# Up/Down search history by what you already typed (mksh binds these to\n" +
                        "# PageUp/PageDown by default, which no phone keyboard has). With an empty\n" +
                        "# line they behave exactly like plain up-history/down-history.\n" +
                        "# '^[[' is normal cursor keys, '^[O' the application-mode variant.\n" +
                        "bind '^[[A'=search-history-up 2>/dev/null\n" +
                        "bind '^[[B'=search-history-down 2>/dev/null\n" +
                        "bind '^[OA'=search-history-up 2>/dev/null\n" +
                        "bind '^[OB'=search-history-down 2>/dev/null\n" +
                        "\n" +
                        "# \$HOME/bin holds symlinks to system commands, generated by Twig and\n" +
                        "# prepended to PATH. Reason: /system/bin & friends are drwxr-x--x, so an\n" +
                        "# app uid may run a command by name but cannot list the directory — Tab\n" +
                        "# completion would find no candidates at all. Anything missing (a vendor\n" +
                        "# tool Twig doesn't know about) can be added by hand and is kept:\n" +
                        "#   ln -sf \"\$(command -v somecmd)\" \$HOME/bin/\n" +
                        "\n" +
                        "# ssh & friends resolve '~' via getpwuid(), which is /data (not writable)\n" +
                        "# on Android — \$HOME is ignored, so a plain ~/.ssh never works. Twig keeps\n" +
                        "# \$HOME/.ssh/config (absolute paths inside it); these point ssh at it.\n" +
                        "alias ssh='ssh -F \$HOME/.ssh/config'\n" +
                        "alias scp='scp -F \$HOME/.ssh/config'\n" +
                        "alias sftp='sftp -F \$HOME/.ssh/config'\n" +
                        "\n" +
                        "# New keys land in \$HOME/.ssh as well. Modes where -f means something\n" +
                        "# other than \"the key to create\" (-R/-F rewrite known_hosts, -l/-y/-p/-e/-i\n" +
                        "# read an existing key) are passed through untouched, as is your own -f.\n" +
                        "ssh-keygen() {\n" +
                        "    local a\n" +
                        "    for a in \"\$@\"; do\n" +
                        "        case \$a in\n" +
                        "        -*[fRFlypeiAQ]*) command ssh-keygen \"\$@\"; return ;;\n" +
                        "        esac\n" +
                        "    done\n" +
                        "    command ssh-keygen -f \"\$HOME/.ssh/id_ed25519\" \"\$@\"\n" +
                        "}\n",
                )
            }
        }
        return rc
    }

    /**
     * 新建会话:先 attach 让终端视图算出真实列/行,再用这个尺寸建立 shell。
     * 之后的尺寸变化经 [syncRemoteSize] 按服务器能力(探测+记忆)选择性转发。
     */
    private fun openNew(scheme: String, title: String, cwd: String?, command: String? = null) {
        val fs = runCatching { FsRegistry.of(scheme) }.getOrNull() as? SftpFileSystem
        if (fs == null) {
            Toast.makeText(this, R.string.terminal_failed, Toast.LENGTH_SHORT).show()
            if (TermManager.isEmpty()) finish()
            return
        }
        val t = TermManager.create(scheme, title)
        // 不调 initializeEmulator(会 JNI 起本地进程);模拟器反射注入
        val s = TerminalSession("/system/bin/sh", "/", arrayOf(), arrayOf(), 5000, sessionClient)
        val output = SshOutput()
        val emulator = TerminalEmulator(output, 80, 24, 5000, sessionClient)
        TerminalSession::class.java.getDeclaredField("mEmulator")
            .apply { isAccessible = true }.set(s, emulator)
        // write() 只有 mShellPid>0 才入队
        TerminalSession::class.java.getDeclaredField("mShellPid")
            .apply { isAccessible = true }.setInt(s, 1)
        // 模拟器应答(光标位置查询等)也投递到输入队列,stdin 只有桥接线程一个写者
        output.redirect = { d, o, c -> s.write(d, o, c) }
        t.session = s
        t.emulator = emulator

        // 先 attach:让视图布局并把 emulator 尺寸更新为真实列/行(本地,不碰 SSH)
        showSession(t)

        // 布局稳定后拿真实尺寸,用它一次性建立 shell(此后不再 resize)。
        // 即便此刻已返回文管(isDestroyed),仍照常建连,会话在后台可用、回来接续。
        b.terminal.postDelayed({
            if (!t.connecting) return@postDelayed // 连接前就被结束
            val cols = emulator.mColumns.coerceIn(20, 500)
            val rows = emulator.mRows.coerceIn(6, 300)
            Thread({
                val sh = runCatching { fs.openShell(cols, rows) }
                    .onFailure { Log.e(TAG, "openShell", it) }
                    .getOrNull()
                main.post {
                    if (TermManager.list().none { it === t }) { sh?.close(); return@post }
                    if (sh == null) {
                        if (!isDestroyed) {
                            Toast.makeText(this, R.string.terminal_failed, Toast.LENGTH_SHORT).show()
                        }
                        val next = TermManager.remove(t)
                        if (displayed === t) {
                            displayed = null
                            if (next == null) finish() else showSession(next)
                        } else {
                            refreshSessions()
                        }
                    } else {
                        wire(t, sh, cwd, command)
                    }
                }
            }, "twig-term-connect").start()
        }, 120)
    }

    /**
     * 开一条特权会话。两种身份形态不同,但对调用方是同一件事:
     *  - **root**:`su` 直接在 termux 自己 fork 的本地 PTY 里起 root shell;
     *  - **Shizuku**:PTY 由特权进程分配,fd 传回来(见 [openPrivilegedPty])。
     */
    private fun openPrivileged(cwd: String?) {
        when (Privileged.active) {
            Privileged.ROOT -> openLocal(cwd, Privileged.ROOT)
            Privileged.SHIZUKU -> openPrivilegedPty(cwd)
            else -> Toast.makeText(this, R.string.terminal_priv_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 会话里的进程结束了。
     *
     * ★ **刚起来就死的不收掉**:那是启动失败,不是用户敲了 exit,而失败原因正打在
     * 这块屏幕上。直接收掉的话界面一闪就回文件列表,唯一的线索也跟着没了。
     */
    private fun onSessionEnded(t: TermSession) {
        val quick = System.currentTimeMillis() - t.startedAt < QUICK_EXIT_MS
        if (!quick) {
            closeExited(t)
            return
        }
        Log.w(PRIV_TAG, "session '${t.title}' exited immediately; keeping it for the message")
        t.alive = false
        t.connecting = false
        val note = "\r\n" + getString(R.string.terminal_exited_quickly) + "\r\n"
        runCatching { t.emulatorOrNull?.append(note.toByteArray(), note.toByteArray().size) }
        refreshSessions()
        if (t === displayed) b.terminal.onScreenUpdated()
    }

    /**
     * 新建**特权 PTY** 会话(Shizuku 身份)。
     *
     * 与本地会话的区别只有一处:PTY 不是我们自己 fork 的,而是让跑在特权进程里的
     * [com.twig.app.priv.TwigPrivService] 去 forkpty,再把**主设备端 fd** 经
     * ParcelFileDescriptor 传回来。拿到之后一切照旧——所以这里复用的是 SSH 那套
     * (注入模拟器 + 两条桥接线程),而不是本地那套(termux 自己 fork)。
     *
     * ★ 为什么要绕这一圈:rish 那条路(在本进程里 app_process 加载 Shizuku 的 dex)
     * 被 SELinux 挡死——`untrusted_app` 不许加载 `app_data_file` 标签的文件。
     * 而这个助手跑的是**我们自己的 APK**(`/data/app`,`apk_data_file`),不受此限。
     */
    private fun openPrivilegedPty(cwd: String?) {
        val dir = cwd?.takeIf { it.isNotBlank() } ?: "/"
        val t = TermManager.create(LOCAL_SCHEME, getString(R.string.terminal_local_shizuku))
        val s = TerminalSession("/system/bin/sh", "/", arrayOf(), arrayOf(), 5000, sessionClient)
        val output = SshOutput()
        val emulator = TerminalEmulator(output, 80, 24, 5000, sessionClient)
        TerminalSession::class.java.getDeclaredField("mEmulator")
            .apply { isAccessible = true }.set(s, emulator)
        TerminalSession::class.java.getDeclaredField("mShellPid")
            .apply { isAccessible = true }.setInt(s, 1)
        output.redirect = { d, o, c -> s.write(d, o, c) }
        t.session = s
        t.emulator = emulator

        showSession(t)
        b.terminal.postDelayed({
            if (!t.connecting) return@postDelayed
            val cols = emulator.mColumns.coerceIn(20, 500)
            val rows = emulator.mRows.coerceIn(6, 300)
            val apk = com.twig.app.priv.PrivService.apkPath(this)
            val abi = com.twig.app.priv.PrivService.abi()
            val env = privEnv(dir)
            Thread({
                val pidOut = IntArray(1)
                val pfd = runCatching {
                    val svc = com.twig.app.priv.PrivService.get()
                        ?: error("could not bind the Shizuku helper")
                    Log.i(PRIV_TAG, "helper uid=${svc.uid}")
                    svc.start(apk, abi, "/system/bin/sh", dir, env, rows, cols, pidOut)
                        ?: error("helper could not allocate a pty")
                }.onFailure { Log.w(PRIV_TAG, "privileged pty failed", it) }.getOrNull()
                main.post {
                    if (TermManager.list().none { it === t }) {
                        runCatching { pfd?.close() }
                        return@post
                    }
                    if (pfd == null) {
                        if (!isDestroyed) {
                            Toast.makeText(this, R.string.terminal_priv_unavailable, Toast.LENGTH_LONG).show()
                        }
                        closeExited(t)
                    } else {
                        wirePty(t, pfd, pidOut[0], cwd)
                    }
                }
            }, "twig-term-priv").start()
        }, 120)
    }

    /**
     * 特权 shell 的环境。**不能照搬本地那份** —— `HOME`/`TMPDIR` 指向我们的应用
     * 私有目录,而助手是 shell 身份时根本读不进去(0700,属主是应用 uid)。
     * 落到 `/data/local/tmp/twig`:shell 与 root 都写得动。
     */
    private fun privEnv(dir: String): Array<String> = (
        listOf(
            "TERM=xterm-256color",
            "HOME=/data/local/tmp/twig",
            "TMPDIR=/data/local/tmp/twig",
            "PATH=" + (System.getenv("PATH") ?: "/system/bin:/system/xbin"),
            "LANG=en_US.UTF-8",
            "PWD=" + dir,
        ) + RUNTIME_ENV.mapNotNull { k -> System.getenv(k)?.let { "$k=$it" } }
        ).toTypedArray()

    /**
     * 把传回来的 PTY 主设备端接到模拟器上。
     *
     * ★ 顺手把真实 fd 反射注进 `mTerminalFileDescriptor`:termux 的
     * `TerminalSession.updateSize` 本来就会拿这个字段去 `setPtyWindowSize`,
     * 注进去之后**窗口大小同步一行都不用写**,横竖屏切换/键盘弹收自动带 SIGWINCH。
     * SSH 那边要靠 [syncRemoteSize] 探测+记忆,是因为那头没有本地 fd 可用。
     */
    private fun wirePty(t: TermSession, pfd: android.os.ParcelFileDescriptor, pid: Int, cwd: String?) {
        val s = t.session
        val emulator = t.emulator
        t.privFd = pfd
        t.privPid = pid
        t.alive = true
        t.connecting = false
        val myGen = ++t.gen
        runCatching {
            TerminalSession::class.java.getDeclaredField("mTerminalFileDescriptor")
                .apply { isAccessible = true }.setInt(s, pfd.fd)
        }.onFailure { Log.w(PRIV_TAG, "cannot inject pty fd; resize will not reach the shell", it) }
        refreshSessions()

        val out = java.io.FileOutputStream(pfd.fileDescriptor)
        val ins = java.io.FileInputStream(pfd.fileDescriptor)
        val readInput = TermBridge.inputReader(s)
        Thread({
            val buf = ByteArray(4096)
            try {
                while (!t.closing && t.gen == myGen) {
                    val n = readInput(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(PRIV_TAG, "pty stdin bridge ended", e)
            }
        }, "twig-priv-in").start()

        Thread({
            val buf = ByteArray(8192)
            try {
                while (t.gen == myGen) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n)
                    main.post {
                        emulator.append(chunk, chunk.size)
                        t.onOutput?.invoke()
                    }
                }
            } catch (e: Exception) {
                // shell 退出时主设备端读会抛 EIO,这是正常收尾,不是故障
                Log.i(PRIV_TAG, "pty closed: ${e.message}")
            }
            if (t.gen == myGen && !t.closing) main.post { onSessionEnded(t) }
        }, "twig-priv-out").start()

        cwd?.let { s.write(cdCommand(it)) }
    }

    /**
     * shell (重新)就绪后接通桥接。stdin 桥接线程只在首次连接([myGen] == 1)时
     * 起一条,伴随 TermSession 全程存活——重连只是把 [TermSession.shell] 换成新
     * 连接,避免重连时并发起第二条 stdin 线程去抢同一份按键队列(谁先抢到不
     * 确定,会丢按键)。stdout 桥接线程则每条物理连接各起一条,断线/换连接
     * 时随之结束。
     */
    private fun wire(t: TermSession, sh: SftpFileSystem.ShellSession, cwd: String?, command: String? = null) {
        val s = t.session
        val emulator = t.emulator
        val myGen = ++t.gen
        t.shell = sh
        t.alive = true
        t.connecting = false
        refreshSessions()
        // 连上后立即同步一次尺寸:unknown 服务器在此完成探测(shell 刚建、没跑
        // 全屏程序,是最安全的探测时机);断线重连后本地尺寸可能已变,也靠这补上
        if (displayed === t) scheduleSizeSync()

        if (myGen == 1) {
            val readInput = TermBridge.inputReader(s)
            // 键盘输入 → session 队列 → SSH stdin(整个会话生命周期唯一写者)
            Thread({
                val buf = ByteArray(4096)
                try {
                    while (!t.closing) {
                        val n = readInput(buf)
                        if (n <= 0) break
                        t.shell?.write(buf, 0, n) // 重连间隙 shell 为 null,静默丢弃
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stdin bridge", e)
                }
            }, "twig-term-in").start()
        }

        // SSH stdout → 主线程 → 模拟器(界面不在时也累积,回来接着看)
        Thread({
            val buf = ByteArray(8192)
            try {
                while (t.gen == myGen) {
                    val n = sh.stdout.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n)
                    main.post {
                        emulator.append(chunk, chunk.size)
                        t.onOutput?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "stdout bridge", e)
            }
            // 非用户主动结束、也没被更新连接取代时,才需要判断这次 EOF 是什么
            if (t.gen == myGen && !t.closing) {
                if (sh.exitedCleanly()) {
                    main.post { if (t.gen == myGen) closeExited(t) }
                } else {
                    reconnect(t, myGen)
                }
            }
        }, "twig-term-out").start()

        cwd?.let { s.write(cdCommand(it)) }
        // 命令快捷方式:cd 之后把命令按进去(带回车,像用户自己敲的一样,输出照常滚)
        command?.takeIf { it.isNotBlank() }?.let { s.write(it.trimEnd() + "\r") }
    }

    /**
     * Win32-OpenSSH 的 SFTP 根是虚拟的("/"下列出各盘符),Windows 路径在这个 app
     * 里也被拼成 "/D:/bin" 这样带开头斜杠的形式——不能直接靠是否以 "/" 开头区分。
     * 真正的信号是"斜杠后紧跟单个盘符字母 + 冒号"([WINDOWS_PATH])。命中时要把
     * 这个人为加的开头斜杠去掉(cmd.exe 不认 "/D:/bin",必须是 "D:/bin"),默认
     * shell 是 cmd.exe:不认单引号转义,跨盘符还必须 `cd /d` 才切得过去。
     */
    private fun cdCommand(dir: String): String =
        if (WINDOWS_PATH.containsMatchIn(dir)) {
            "cd /d \"${dir.removePrefix("/").replace("\"", "")}\" && cls\r"
        } else {
            "cd ${shq(dir)} && clear\r"
        }

    /**
     * 远端 shell 自己退出了(`exit` / Ctrl+D):不重连,直接把这条会话收掉——
     * 和手动「结束当前会话」同一个归宿。当前会话退出就切到相邻那条,一条都不剩
     * 就退出终端页;后台会话退出只需从列表里去掉。
     */
    private fun closeExited(t: TermSession) {
        val wasDisplayed = t === displayed
        val next = TermManager.remove(t) // 内部会 close(),连接一并关掉
        if (isDestroyed) return // 界面已经退出,移除即可
        if (!wasDisplayed) { refreshSessions(); return }
        displayed = null
        if (next == null) finish() else showSession(next)
    }

    /**
     * 掉线重连:息屏/切后台被系统或运营商 NAT 静默掐断连接是常见情形,退避重试
     * 几次,都失败才最终标记「已结束」。[myGen] 是断线时的连接代数,期间若已有
     * 更新连接接上、或用户主动结束该会话,立即放弃。
     */
    private fun reconnect(t: TermSession, myGen: Int) {
        t.shell = null
        main.post { if (t.gen == myGen) { t.alive = false; t.connecting = true; refreshSessions() } }
        val fs = runCatching { FsRegistry.of(t.scheme) }.getOrNull() as? SftpFileSystem
        var sh: SftpFileSystem.ShellSession? = null
        if (fs != null) {
            for (attempt in 0 until 3) {
                if (t.gen != myGen || t.closing) { sh?.close(); return }
                Thread.sleep(3000L * (attempt + 1))
                if (t.gen != myGen || t.closing) { sh?.close(); return }
                val cols = t.emulator.mColumns.coerceIn(20, 500)
                val rows = t.emulator.mRows.coerceIn(6, 300)
                sh = runCatching { fs.openShell(cols, rows) }.getOrNull()
                if (sh != null) break
            }
        }
        main.post {
            if (t.gen != myGen || t.closing) { sh?.close(); return@post }
            if (sh != null) {
                wire(t, sh, null)
            } else {
                t.alive = false
                t.connecting = false
                refreshSessions()
                t.onOutput?.invoke()
            }
        }
    }

    /** 切换到 [t] 并绑定视图:解绑旧会话回调,注册新会话刷新回调。 */
    private fun showSession(t: TermSession) {
        val prev = displayed
        if (prev !== t) prev?.onOutput = null
        displayed = t
        TermManager.select(t)
        t.onOutput = { if (!isDestroyed) b.terminal.onScreenUpdated() }
        runCatching { t.session.updateTerminalSessionClient(sessionClient) }
        b.terminal.attachSession(t.session)
        b.terminal.post { maybeShowIme() }
        scheduleSizeSync() // attach 会把该会话的本地 emulator 尺寸改成当前视图大小
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription("SSH · ${t.title}"))
        refreshSessions()
    }

    /** 结束当前会话:关闭并从列表移除,切到相邻会话;没有会话则退出。 */
    private fun endCurrent() {
        val t = displayed ?: return
        val next = TermManager.remove(t)
        if (t === displayed) displayed = null
        if (next == null) { finish(); return }
        showSession(next)
    }

    /** 重建下拉列表(标题 + 连接/结束状态标记),并把选中项对准当前会话。 */
    private fun refreshSessions() {
        if (isDestroyed) return
        val list = TermManager.list()
        if (list.isEmpty()) { finish(); return }
        val labels = list.map { it.title + statusTag(it) }
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, labels,
        ) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(pos, cv, parent) as TextView).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                }
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getDropDownView(pos, cv, parent) as TextView).apply {
                    setTextColor(0xFFEEEEEE.toInt())
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressSpinner = true
        b.sessionSpinner.adapter = adapter
        val idx = list.indexOfFirst { it === displayed }
        if (idx >= 0) b.sessionSpinner.setSelection(idx)
        b.sessionSpinner.post { suppressSpinner = false }
    }

    private fun statusTag(t: TermSession): String = when {
        t.connecting -> getString(R.string.terminal_connecting_tag)
        !t.alive -> getString(R.string.terminal_closed_tag)
        else -> ""
    }

    private fun focusTerminal() {
        b.terminal.isFocusable = true
        b.terminal.isFocusableInTouchMode = true
        b.terminal.requestFocus()
    }

    /** 强制弹键盘(不管光标状态——[maybeShowIme] 猜错时的手动兜底)。 */
    private fun showIme() {
        focusTerminal()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.terminal, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.terminal.windowToken, 0)
    }

    /** 工具栏「键盘」按钮:弹着就收起,收着就弹出。 */
    private fun toggleIme() {
        if (imeShown()) hideIme() else showIme()
        // 收/弹要等布局跑完才反映到 insets 上,图标同步交给 OnGlobalLayoutListener
    }

    /**
     * 键盘现在是不是弹着的。API 30+ 有权威答案(`WindowInsets` 的 ime 可见性);更早的
     * 系统上 `WindowInsetsCompat.isVisible(ime())` 只是恒为 true 的占位实现,只能按
     * 「窗口可见区域被从底下压掉了多少」来判——本页是 `adjustResize`,键盘一弹可见区
     * 必然缩掉一大截,这个判据够用了(阈值取屏高 1/5,躲开导航栏/挖孔那些几十像素的差)。
     */
    private fun imeShown(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets?.let {
                return it.isVisible(android.view.WindowInsets.Type.ime())
            }
        }
        val r = android.graphics.Rect()
        val root = b.root
        root.getWindowVisibleDisplayFrame(r)
        val screenH = root.rootView.height
        return screenH > 0 && screenH - r.bottom > screenH / 5
    }

    /** 键盘按钮的图标跟着实际状态走:弹着时换成「收起」那颗。 */
    private fun syncKeyboardIcon() {
        val shown = imeShown()
        if (shown == keyboardIconShown) return
        keyboardIconShown = shown
        val res = if (shown) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard
        b.toolbar.menu.findItem(MENU_KEYBOARD)?.icon =
            ContextCompat.getDrawable(this, res)?.mutate()?.apply { setTint(Color.WHITE) }
    }

    /** [syncKeyboardIcon] 上次设成的状态,避免每次布局都重建 drawable。 */
    private var keyboardIconShown = false

    /**
     * 点击终端/切换会话时按需弹键盘:全屏程序(htop/less/vim 等)通常会隐藏光标
     * (DECTCEM),此时大概率不是在等你打字,不弹键盘更像 Termux 的体验;
     * shell 提示符等光标可见的场景照常弹出。
     */
    private fun maybeShowIme() {
        focusTerminal()
        if (displayed?.emulatorOrNull?.isCursorEnabled != false) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.terminal, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private val TerminalSession.emulator: TerminalEmulator?
        get() = runCatching { getEmulator() }.getOrNull()

    /** 模拟器的回写通道(终端应答如光标位置查询);经 redirect 投递到输入队列。 */
    private class SshOutput : TerminalOutput() {
        var redirect: ((ByteArray, Int, Int) -> Unit)? = null
        override fun write(data: ByteArray, offset: Int, count: Int) {
            runCatching { redirect?.invoke(data, offset, count) }
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun copyToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        displayed?.session?.emulator?.paste(text)
    }

    private inner class SessionClient : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) {
            if (!isDestroyed && s === displayed?.session) b.terminal.onScreenUpdated()
        }
        override fun onTitleChanged(s: TerminalSession) = Unit
        /**
         * 本地 shell 进程退出(exit / Ctrl+D):和 SSH 正常退出一样直接收掉会话。
         *
         * ★ 但**刚起来就死的不收**:那是启动失败,不是用户敲了 exit,而失败原因正打在
         * 这块屏幕上。直接收掉的话界面一闪就回文件列表,唯一的线索也跟着没了
         * (rish/su 起不来时就是这个样子)。留着会话让人读得到,顺便进 logcat。
         */
        override fun onSessionFinished(s: TerminalSession) {
            val t = TermManager.list().firstOrNull { it.session === s } ?: return
            if (t.closing) return
            main.post { onSessionEnded(t) }
        }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) = copyToClipboard(text)
        override fun onPasteTextFromClipboard(s: TerminalSession) = pasteFromClipboard()
        override fun onBell(s: TerminalSession) = Unit
        override fun onColorsChanged(s: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {
            Log.e(TAG, "$tag: $message")
        }
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(TAG, "$tag: $message", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(TAG, "stack", e)
        }
    }

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale > 0.95f && scale < 1.05f) return scale // 攒够变化再动,免得抖
            var next = (textSizePx * scale).roundToInt()
            // 小字号时 ×1.05 取整后可能还是原值,会卡住不动;至少挪一格
            if (next == textSizePx) next += if (scale > 1f) 1 else -1
            if (next != textSizePx) applyTextSize(next)
            return 1.0f // 基准已换成新字号,累积因子清零
        }
        override fun onSingleTapUp(e: MotionEvent) = maybeShowIme()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey(): Boolean = ctrlPending.also { if (it) main.post { setCtrl(false) } }
        override fun readAltKey(): Boolean = altPending.also { if (it) main.post { setAlt(false) } }
        override fun readShiftKey(): Boolean = shiftPending.also { if (it) main.post { setShift(false) } }
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        /**
         * termux 只在「行列真的变了」时回调这里。字号缩放走的正是这条路——
         * `setTextSize` 不 `requestLayout()`,`OnGlobalLayoutListener` 不触发,
         * 光靠那个监听器远端 PTY 收不到新尺寸,会继续按旧 cols/rows 输出(也不发
         * SIGWINCH 让程序重画),屏幕上就留着按旧宽度画的旧内容。
         */
        override fun onEmulatorSet() = scheduleSizeSync()
        override fun logError(tag: String?, message: String?) {
            Log.e(TAG, "$tag: $message")
        }
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(TAG, "$tag: $message", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(TAG, "stack", e)
        }
    }

    // ---- 附加键条 ----

    /** 全部附加键按钮,换配色时统一改字色。 */
    private val extraKeyButtons = ArrayList<Button>()

    private var ctrlBtn: Button? = null
    private var altBtn: Button? = null
    private var shiftBtn: Button? = null

    private fun setCtrl(on: Boolean) {
        ctrlPending = on
        markMod(ctrlBtn, on)
    }

    private fun setAlt(on: Boolean) {
        altPending = on
        markMod(altBtn, on)
    }

    private fun setShift(on: Boolean) {
        shiftPending = on
        markMod(shiftBtn, on)
    }

    /**
     * 修饰键的按下态用「底色 + 加粗」表示,不再用半透明——透明度一压,字色本就
     * 是配色的前景色(未必是亮白),读起来太浅。所有键一律全不透明。
     */
    private fun markMod(btn: Button?, on: Boolean) {
        btn ?: return
        btn.setBackgroundColor(if (on) TermColors.keyActiveBg() else Color.TRANSPARENT)
        btn.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    @SuppressLint("SetTextI18n")
    private fun buildExtraKeys() {
        fun key(label: String, onClick: (View) -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(TermColors.fg())
                background = null
                // 关键:不抢终端焦点,否则软键盘目标漂移、按键路由错乱
                isFocusable = false
                isFocusableInTouchMode = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener(onClick)
            }

        // pending 修饰态只被软键盘经 readControlKey/... 消费;虚拟键得自己取用并清掉,
        // 否则 SHIFT+TAB 这类组合永远发不出去
        fun takeMods(): Int {
            var m = 0
            if (ctrlPending) m = m or KeyHandler.KEYMOD_CTRL
            if (altPending) m = m or KeyHandler.KEYMOD_ALT
            if (shiftPending) m = m or KeyHandler.KEYMOD_SHIFT
            if (m != 0) { setCtrl(false); setAlt(false); setShift(false) }
            return m
        }
        fun sendKey(code: Int) {
            if (b.terminal.mEmulator == null) return // 视图未就绪时避免空指针
            b.terminal.handleKeyCode(code, takeMods())
        }
        fun sendBytes(str: String) {
            var s = str
            val mods = takeMods()
            if (s.length == 1 && mods and KeyHandler.KEYMOD_CTRL != 0) {
                val c = s[0].uppercaseChar().code
                if (c in 0x40..0x7e) s = (c and 0x1f).toChar().toString()
            }
            if (mods and KeyHandler.KEYMOD_ALT != 0) s = "\u001b" + s
            displayed?.session?.write(s)
        }
        val row1 = listOf<Pair<String, (View) -> Unit>>(
            "ESC" to { _ -> sendBytes("\u001b") },
            "/" to { _ -> sendBytes("/") },
            "|" to { _ -> sendBytes("|") },
            "-" to { _ -> sendBytes("-") },
            "HOME" to { _ -> sendKey(KeyEvent.KEYCODE_MOVE_HOME) },
            "▲" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_UP) },
            "END" to { _ -> sendKey(KeyEvent.KEYCODE_MOVE_END) },
            "PGUP" to { _ -> sendKey(KeyEvent.KEYCODE_PAGE_UP) },
        )
        val row2 = listOf<Pair<String, (View) -> Unit>>(
            "TAB" to { _ -> sendKey(KeyEvent.KEYCODE_TAB) },
            "CTRL" to { _ -> setCtrl(!ctrlPending) },
            "SHIFT" to { _ -> setShift(!shiftPending) },
            "ALT" to { _ -> setAlt(!altPending) },
            "◀" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
            "▼" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },
            "▶" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
            "PGDN" to { _ -> sendKey(KeyEvent.KEYCODE_PAGE_DOWN) },
        )

        fun addRow(keys: List<Pair<String, (View) -> Unit>>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for ((label, action) in keys) {
                val btn = key(label, action)
                when (label) {
                    "CTRL" -> ctrlBtn = btn
                    "ALT" -> altBtn = btn
                    "SHIFT" -> shiftBtn = btn
                }
                extraKeyButtons += btn
                row.addView(btn)
            }
            b.extraKeys.addView(row)
        }
        addRow(row1)
        addRow(row2)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.terminalFont(this) != appliedFont) applyFont() // 设置页刚换过字体
    }

    override fun onPause() {
        super.onPause()
        // 缩放过程中每帧都写 SharedPreferences 没必要,离开页面时落一次
        if (textSizePx > 0) Prefs.setTerminalTextSize(this, textSizePx)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 会话保留在 TermManager;只解除对本界面的引用
        displayed?.onOutput = null
    }

    companion object {
        private const val TAG = "TwigTerm"
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PRIV = "priv"
        private const val MENU_NEW = 1
        private const val MENU_KEYBOARD = 2
        private const val MENU_END_CURRENT = 3
        private const val MENU_END_ALL = 4
        private const val MENU_KEEP_AWAKE = 5
        private const val MENU_FONT = 6
        private const val MENU_COLORS = 7
        private const val MENU_NEW_LOCAL = 8
        private const val MENU_NEW_PRIV = 9

        /** 起来不到这个时长就退出的本地会话,当作"没起来"而不是"用户退出"。 */
        private const val QUICK_EXIT_MS = 3000L

        /** 与 Privileged 同一个 tag,特权相关的事一条命令看全。 */
        private const val PRIV_TAG = "twig-priv"

        /**
         * 从本进程原样传下去的运行时环境变量。
         *
         * ★ [localEnv] 是**整套替换**环境的,不是在现有环境上追加 —— 只给 TERM/HOME/PATH
         * 那几个的话,**任何要启动 ART 的东西都会零输出秒退**:`app_process` 找不到
         * `ANDROID_ROOT`/`ANDROID_DATA`/`ANDROID_ART_ROOT`/`BOOTCLASSPATH` 就直接死,
         * 而且**一个字都不打**(2026-08-16 实测:同一条命令带完整环境能跑,精简环境
         * 退出码 0、无输出)。屏幕上只剩 termux 那句 "[Process completed]",看着像
         * "命令不存在"。
         *
         * 受影响的远不止 rish:`am` / `pm` / `dumpsys` / `settings` 全都是
         * `app_process` 的包装脚本,少了这些变量它们在本地终端里一直是静默失败的。
         *
         * 只白名单这几个、不整套继承:`ANDROID_SOCKET_*` 这类是父进程的私有 fd 约定,
         * 传给子进程没有意义还可能被误用。
         */
        private val RUNTIME_ENV = listOf(
            "ANDROID_ROOT",
            "ANDROID_DATA",
            "ANDROID_ART_ROOT",
            "ANDROID_I18N_ROOT",
            "ANDROID_TZDATA_ROOT",
            "ANDROID_ASSETS",
            "ANDROID_STORAGE",
            "BOOTCLASSPATH",
            "DEX2OATBOOTCLASSPATH",
            "SYSTEMSERVERCLASSPATH",
        )

        /** 系统自带 shell(mksh);toybox 的命令都在 /system/bin 下。 */
        private const val MIN_TEXT_PX = 18
        private const val MAX_TEXT_PX = 96

        /** "/D:"、"/D:/bin" 这类被 join() 强加了开头斜杠的 Windows 盘符路径。 */
        private val WINDOWS_PATH = Regex("^/[A-Za-z]:(/|$)")

        private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

        fun start(
            context: Context,
            scheme: String,
            title: String,
            dir: String? = null,
            command: String? = null,
        ) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .putExtra(EXTRA_SCHEME, scheme)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_DIR, dir)
                    .putExtra(EXTRA_CMD, command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /**
         * 以某个本地目录为工作目录开一条本地 shell(本地目录长按菜单用)。
         * [priv] 非 [Privileged.OFF] 时这条会话跑在特权身份上(su / rish)。
         */
        fun startLocal(context: Context, dir: String?, priv: Int = Privileged.OFF) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .putExtra(EXTRA_SCHEME, LOCAL_SCHEME)
                    .putExtra(EXTRA_PRIV, priv)
                    .putExtra(EXTRA_DIR, dir)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** 不带 scheme:回到已有会话(没有则新建一条本地),供文管顶部的终端入口用。 */
        fun resume(context: Context) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
