package com.twig.app.priv;

/**
 * Helper that runs inside Shizuku's privileged process.
 *
 * That process is started by Shizuku via app_process, with **the classpath
 * being our own APK** (under /data/app, tagged apk_data_file) — so it is
 * NOT subject to the "untrusted_app may not load app_data_file" rule that
 * killed the rish path.
 */
interface ITwigPrivService {

    /** The effective uid of this privileged process: 0 = root, 2000 = shell. */
    int getUid();

    /**
     * Hand over a "live" credential: when the app process dies, this binder
     * dies with it, so the helper kills itself.
     *
     * ★ We can't rely solely on Shizuku's destroy(): when the app is upgraded
     *   or killed the binder is already gone, so that call never reaches us,
     *   leaving **a root process resident** (hit in the wild on 2026-08-17).
     */
    void attach(IBinder token);

    /**
     * Allocate a PTY and start [cmd] on the slave side, returning the
     * **master-side fd**; null on failure.
     * The child PID is written to pid[0] (for kill).
     *
     * [apkPath] / [abi] are used to extract libtwigpty.so from the APK and
     * load it — see TwigPrivService.ensureLib for the details.
     */
    ParcelFileDescriptor start(String apkPath, String abi, String cmd, String cwd,
                               in String[] env, int rows, int cols, out int[] pid);

    /** End the session: send SIGHUP to the whole foreground process group. */
    void kill(int pid);

    /** Called by Shizuku on unbind so this process exits. */
    void destroy();
}
