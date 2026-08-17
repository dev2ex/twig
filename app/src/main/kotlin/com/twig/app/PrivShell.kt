package com.twig.app

import android.content.Context
import java.io.File

/**
 * 让**本地终端**跑在特权身份上(root 或 Shizuku)。
 *
 * ★ 关键在于:这里不需要任何桥接、AIDL 或 fd 传递 —— 本地终端本来就有一个
 * 由 termux 的 `JNI.createSubprocess` 分配的**真 PTY**,只要把"跑什么"从
 * `/system/bin/sh` 换成 `su`(或 rish)就行,resize、作业控制、全屏程序
 * 一概照旧,因为它们依赖的是这个本地 PTY。
 *
 * 两条路形态不同,但对调用方是同一件事:
 *  - **root**:`su` 直接在这个本地 PTY 里起 root shell,本文件负责。
 *  - **Shizuku**:PTY 由特权进程分配、fd 传回来,见
 *    [com.twig.app.priv.TwigPrivService];本文件只负责判断它能不能起。
 */
object PrivShell {

    /**
     * `su` 的绝对路径,找不到返回 null。
     *
     * 不用 `which`:PATH 里通常没有它。也**不要拿这个结果当"有没有 root"的定论** ——
     * Magisk 的 DenyList 会对未授权应用藏掉 `su`,探测不到不等于真没有,
     * 所以调用方仍应把 root 选项摆出来让用户试。
     */
    fun suPath(): String? = SU_PATHS.firstOrNull { File(it).exists() }

    /**
     * 某种特权身份现在能不能开终端。
     *
     * ★ **Shizuku 那条曾经想走 rish,此路不通**(2026-08-16 实测定案):rish 的 dex
     * 只能落到应用私有目录,而 `untrusted_app` **不允许加载 `app_data_file` 标签的
     * 文件**(W^X 的 SELinux 落地形式),`app_process` 报 `ClassNotFoundException`。
     * 同一份 dex、同一套环境、同一条命令,换成 `u:r:su:s0`(adb root)或
     * `u:r:runas_app:s0`(run-as,**uid 与应用完全相同**)就正常加载 —— 变量只有
     * SELinux 域。Termux 能用 rish 是因为它常年停在 targetSdk 28。
     *
     * 现在改走 `bindUserService`:跑的是**我们自己的 APK**(`/data/app`,标签
     * `apk_data_file`),不受此限,见 [com.twig.app.priv.TwigPrivService]。
     */
    fun available(ctx: Context, mode: Int): Boolean = when (mode) {
        Privileged.ROOT -> suPath() != null
        // Shizuku 终端不再走 rish(被 SELinux 挡死),改由特权进程分配 PTY —— 见
        // com.twig.app.priv.TwigPrivService。只要 Shizuku 服务在跑就能起。
        Privileged.SHIZUKU -> Privileged.shizukuRunning()
        else -> true
    }

    /**
     * 这条会话要跑的可执行文件与参数。返回 null 表示这种身份现在起不来。
     * [Privileged.OFF] 走原来的 `/system/bin/sh`。
     */
    fun command(ctx: Context, mode: Int): Pair<String, Array<String>>? = when (mode) {
        Privileged.OFF -> SHELL to arrayOf()
        // 不传 `-p`:各家 su(Magisk/KernelSU/APatch)对它的支持不完全一致,
        // 不认的会直接报错退出。环境本来就由 createSubprocess 传进去。
        Privileged.ROOT -> suPath()?.let { it to arrayOf<String>() }
        // Shizuku 不走本地进程,由 TwigPrivService 分配 PTY —— 见 available() 的说明
        else -> null
    }

    const val SHELL = "/system/bin/sh"

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su",
    )
}
