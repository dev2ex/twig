package com.twig.app.priv

/**
 * `libtwigpty.so` 的绑定:分配一个伪终端并在从属端起进程。
 *
 * ★ **没有静态初始化块**,加载由调用方显式做([load])。这一点是故意的:
 * 特权进程里没有 app context,而我们的 native 库是**不解压**地存在 APK 内
 * (`extractNativeLibs=false`,`lib/arm64/` 是空目录),`System.loadLibrary` 找不到它;
 * 只能先把 .so 抠出来再按绝对路径 `System.load`。termux 的 `JNI` 类恰恰相反——
 * 静态块里写死 `loadLibrary("termux")`,一旦失败这个类就永久损坏、没有重试机会,
 * 这就是不复用它的原因。
 *
 * app 进程里正常走 `loadLibrary`(那边 classloader 知道 APK 里的 lib 路径)。
 */
object Pty {

    @Volatile
    private var loaded = false

    /** app 进程内加载(有 classloader 兜底)。 */
    @Synchronized
    fun loadInApp() {
        if (loaded) return
        System.loadLibrary("twigpty")
        loaded = true
    }

    /** 特权进程内按绝对路径加载。 */
    @Synchronized
    fun load(soPath: String) {
        if (loaded) return
        System.load(soPath)
        loaded = true
    }

    /**
     * 返回 PTY 主设备端 fd(失败 -1),子进程 pid 写进 [pidOut]。
     * [args] 不含 argv[0],由 native 侧用 [cmd] 补上。
     */
    external fun nativeOpen(
        cmd: String,
        cwd: String?,
        args: Array<String>,
        env: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
    ): Int

    /** 改窗口大小并给前台进程组发 SIGWINCH(全屏程序靠它重排)。 */
    external fun nativeSetWinSize(fd: Int, rows: Int, cols: Int)

    external fun nativeWaitFor(pid: Int): Int

    external fun nativeKill(pid: Int)

    external fun nativeClose(fd: Int)
}
