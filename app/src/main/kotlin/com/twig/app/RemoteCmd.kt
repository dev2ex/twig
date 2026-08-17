package com.twig.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.twig.app.ui.RunCommandActivity
import org.json.JSONObject

/**
 * 一条「在某台服务器上跑的命令」。桌面快捷方式点一下就执行它:可以进终端里跑
 * (看得到输出、能交互),也可以静默跑完只报结果。
 *
 * ★ 存的是 [connLabel](`SavedConnection.label()`)而不是 scheme —— scheme 是
 * 会话内动态注册的,快捷方式要跨启动可用,必须像收藏/最近位置那样存「怎么到达」。
 * 执行时经 [Connections.find] + [Connections.ensure] 现连现取 scheme。
 */
data class RemoteCmd(
    val connLabel: String,
    /** 远端工作目录;空 = 用登录默认目录。 */
    val workdir: String,
    val command: String,
    /** true = 开一条终端会话跑(可交互);false = 后台静默跑,只报结果。 */
    val inTerminal: Boolean,
    /**
     * 静默执行时是否套一层**登录 shell**。默认不套:sshd 执行 `cmd` 用的是该用户
     * 登录 shell 的 `-c`(zsh 用户就是 zsh),但那是**非交互非登录**,不读
     * `.zshrc`/`.zprofile`/`.profile`,PATH 往往比终端里窄。套上以后走
     * `$SHELL -lc`——`$SHELL` 由 sshd 按 passwd 里的 shell 设置,所以**默认 shell
     * 是 zsh 就是 zsh 在跑**,不会被写死成 bash。终端模式用不到这个:那本来
     * 就是交互式登录 shell。
     */
    val loginShell: Boolean = false,
    /** 快捷方式/通知上显示的名字。 */
    val label: String,
) {
    /**
     * 把命令本体塞进意图。**只能用于应用内部、目标组件未导出的场景**
     * (现在只有 [ui.CmdService],它是 `exported="false"`,外部应用 start 不了)。
     * 跨进程边界的快捷方式走 [launchIntent] —— 那里只传 id,原因见 [RemoteCmdStore]。
     */
    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_CONN, connLabel)
        putExtra(EXTRA_WORKDIR, workdir)
        putExtra(EXTRA_CMD, command)
        putExtra(EXTRA_TERM, inTerminal)
        putExtra(EXTRA_LOGIN, loginShell)
        putExtra(EXTRA_LABEL, label)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("conn", connLabel); put("workdir", workdir); put("command", command)
        put("terminal", inTerminal); put("login", loginShell); put("label", label)
    }

    /**
     * 点击后进 [RunCommandActivity](无界面中转,判断走终端还是后台服务)。
     *
     * ★ 意图里**只带 id,不带命令文本**:这个 Activity 必须导出(见 [RemoteCmdStore]
     * 的说明),带文本就等于把"用用户凭据执行任意远程命令"的能力开放给了设备上所有应用。
     */
    fun launchIntent(ctx: Context): Intent =
        Intent(ctx, RunCommandActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ID, RemoteCmdStore.put(ctx, this@RemoteCmd))
        }

    companion object {
        private const val EXTRA_CONN = "cmd_conn"
        private const val EXTRA_WORKDIR = "cmd_workdir"
        private const val EXTRA_CMD = "cmd_command"
        private const val EXTRA_TERM = "cmd_terminal"
        private const val EXTRA_LOGIN = "cmd_login"
        private const val EXTRA_LABEL = "cmd_label"
        private const val EXTRA_ID = "cmd_id"

        /** 从内部意图取出命令本体(仅限未导出的组件,见 [putInto])。 */
        fun from(intent: Intent): RemoteCmd? {
            val conn = intent.getStringExtra(EXTRA_CONN) ?: return null
            val command = intent.getStringExtra(EXTRA_CMD) ?: return null
            return RemoteCmd(
                connLabel = conn,
                workdir = intent.getStringExtra(EXTRA_WORKDIR).orEmpty(),
                command = command,
                inTerminal = intent.getBooleanExtra(EXTRA_TERM, false),
                loginShell = intent.getBooleanExtra(EXTRA_LOGIN, false),
                label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifEmpty { command.take(24) },
            )
        }

        /**
         * 从**桌面快捷方式**的意图还原命令:只认 id,查不到就返回 null。
         * 这里绝不能回退去读 [EXTRA_CMD] —— 那等于把刚堵上的口子重新打开。
         * 老版本(0.79.1 及之前)固定的快捷方式带的是命令文本、没有 id,会走到 null,
         * 由调用方提示用户重新创建一次。
         */
        fun fromShortcut(ctx: Context, intent: Intent): RemoteCmd? =
            intent.getStringExtra(EXTRA_ID)?.let { RemoteCmdStore.get(ctx, it) }

        fun fromJson(o: JSONObject) = RemoteCmd(
            connLabel = o.getString("conn"),
            workdir = o.optString("workdir", ""),
            command = o.getString("command"),
            inTerminal = o.optBoolean("terminal", false),
            loginShell = o.optBoolean("login", false),
            label = o.optString("label", ""),
        )

        /**
         * 拼成实际下发的命令行:有工作目录就先 cd 过去(cd 失败即中止,不在错的
         * 地方跑);[RemoteCmd.loginShell] 时整体再套一层 `$SHELL -lc`,`$SHELL`
         * 由远端 sshd 按用户 passwd 设置,zsh 用户跑的就是 zsh。
         */
        fun shellLine(cmd: RemoteCmd): String {
            val inner =
                if (cmd.workdir.isEmpty()) cmd.command
                else "cd " + sq(cmd.workdir) + " && " + cmd.command
            return if (!cmd.loginShell) inner else "\${SHELL:-/bin/sh} -lc " + sq(inner)
        }

        fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /** 请求把这条命令固定到桌面。 */
        fun pin(ctx: Context, cmd: RemoteCmd): Boolean {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
            val id = "cmd_" + (cmd.connLabel + "|" + cmd.workdir + "|" + cmd.command).hashCode()
            val shortcut = ShortcutInfoCompat.Builder(ctx, id)
                .setShortLabel(cmd.label.ifEmpty { cmd.command.take(24) })
                // 用 SFTP 类型图标(树上服务器行、路径栏同一个),一眼看出是哪台机器上的活
                .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_server))
                .setIntent(cmd.launchIntent(ctx))
                .build()
            ShortcutManagerCompat.requestPinShortcut(ctx, shortcut, null)
            return true
        }
    }
}
