package com.twig.app.priv;

/**
 * 跑在 Shizuku 特权进程里的助手。
 *
 * 这个进程由 Shizuku 用 app_process 拉起,**classpath 是我们自己的 APK**
 * (在 /data/app 下,标签 apk_data_file),所以不受"untrusted_app 不许加载
 * app_data_file"那条限制——rish 那条路正是死在这上面。
 */
interface ITwigPrivService {

    /** 这个特权进程实际的 uid:0 = root,2000 = shell。 */
    int getUid();

    /**
     * 交一个"活着"的凭据过来:app 进程一死,这个 binder 随之死亡,助手就自杀。
     *
     * ★ 不能只靠 Shizuku 的 destroy():app 被升级/杀死时 binder 已经断了,
     * 那个调用根本送不到,**留下一个 root 进程常驻**(2026-08-17 实测撞到)。
     */
    void attach(IBinder token);

    /**
     * 分配一个 PTY 并在从属端启动 [cmd],返回**主设备端 fd**;失败返回 null。
     * 子进程 pid 写进 pid[0](供 kill 用)。
     *
     * [apkPath] / [abi] 用来把 libtwigpty.so 从 APK 里抠出来加载 —— 见
     * TwigPrivService.ensureLib 的说明。
     */
    ParcelFileDescriptor start(String apkPath, String abi, String cmd, String cwd,
                               in String[] env, int rows, int cols, out int[] pid);

    /** 结束会话:给整个前台进程组发 SIGHUP。 */
    void kill(int pid);

    /** Shizuku 解绑时会调它,让这个进程退出。 */
    void destroy();
}
