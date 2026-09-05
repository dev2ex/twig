package com.twig.app.priv

/**
 * Binding for `libtwigpty.so`: allocates a pseudo-terminal and starts a
 * process on the slave side.
 *
 * ★ **There is no static init block** — loading is done explicitly by the
 * caller ([load]). This is deliberate: the privileged process has no app
 * Context, and our native library lives **uncompressed** inside the APK
 * (`extractNativeLibs=false`, `lib/arm64/` is an empty directory), so
 * `System.loadLibrary` cannot find it; the only option is to extract the
 * .so and `System.load` it by absolute path. Termux's `JNI` class is the
 * opposite — its static block hard-codes `loadLibrary("termux")`, and if it
 * fails the class is permanently broken with no chance to retry, which is
 * exactly why we do not reuse it.
 *
 * The app process uses `loadLibrary` normally (the classloader there knows
 * the lib path inside the APK).
 */
object Pty {

    @Volatile
    private var loaded = false

    /** Load inside the app process (the classloader handles the path). */
    @Synchronized
    fun loadInApp() {
        if (loaded) return
        System.loadLibrary("twigpty")
        loaded = true
    }

    /** Load in the privileged process by absolute path. */
    @Synchronized
    fun load(soPath: String) {
        if (loaded) return
        System.load(soPath)
        loaded = true
    }

    /**
     * Returns the fd of the PTY master end (-1 on failure); the child pid is
     * written into [pidOut]. [args] does not include argv[0]; the native side
     * fills it in from [cmd].
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

    /** Resize the window and send SIGWINCH to the foreground process group (full-screen programs use it to relayout). */
    external fun nativeSetWinSize(fd: Int, rows: Int, cols: Int)

    external fun nativeWaitFor(pid: Int): Int

    external fun nativeKill(pid: Int)

    external fun nativeClose(fd: Int)
}
