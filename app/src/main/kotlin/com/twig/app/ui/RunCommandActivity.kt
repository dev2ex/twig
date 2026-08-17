package com.twig.app.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.RemoteCmd
import com.twig.app.TwigApp

/**
 * 桌面命令快捷方式的入口:无界面中转页(同 [ViewIntentActivity] 的路子),
 * 判断这条命令该进终端还是后台跑,派发完立刻 finish。
 *
 * ★ 这是个**导出**的 Activity(minSdk 24 的老 INSTALL_SHORTCUT 广播路径要求如此),
 * 所以只认意图里的随机 id、去 [com.twig.app.RemoteCmdStore] 查命令本体,**绝不直接
 * 采信意图里的命令文本**——否则设备上任何一个免权限应用都能让 Twig 拿着用户保存的
 * 凭据去服务器上跑任意命令。详见 RemoteCmdStore 的类注释。
 *
 * ★ 必须是纯 [Activity] 而不是 AppCompatActivity:它配的是
 * `Theme.Translucent.NoTitleBar`(无界面中转页要的透明主题),那不是 AppCompat
 * 主题的后代,AppCompatActivity 在 onPostCreate 里会直接抛
 * 「You need to use a Theme.AppCompat theme」崩掉。
 *
 * 走终端时连接要现建——快捷方式可能在进程冷启动时点开,那台服务器还没注册过
 * scheme;[Connections.ensure] 会阻塞连接,所以扔后台线程,拿到 scheme 再回主线程
 * 开终端页。走后台时交给 [CmdService](前台服务,见那里的说明)。
 */
class RunCommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TwigApp.registerBaseFs(this)

        val cmd = RemoteCmd.fromShortcut(this, intent)
        if (cmd == null) {
            Toast.makeText(this, R.string.cmd_bad_shortcut, Toast.LENGTH_LONG).show()
            finish(); return
        }

        if (!cmd.inTerminal) {
            CmdService.start(this, cmd)
            finish(); return
        }

        val conn = Connections.find(this, cmd.connLabel)
        if (conn == null) {
            Toast.makeText(this, R.string.cmd_conn_missing, Toast.LENGTH_LONG).show()
            finish(); return
        }
        Thread({
            val scheme = runCatching { Connections.ensure(this, conn) }.getOrNull()
            runOnUiThread {
                if (scheme == null) {
                    Toast.makeText(this, R.string.cmd_conn_missing, Toast.LENGTH_LONG).show()
                } else {
                    TerminalActivity.start(
                        this,
                        scheme,
                        conn.shortLabel(),
                        dir = cmd.workdir.ifEmpty { null },
                        command = cmd.command,
                    )
                }
                finish()
            }
        }, "twig-cmd-connect").start()
    }
}
