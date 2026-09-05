package com.twig.app.priv

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Helper that runs inside the Shizuku-privileged process (launched via
 * `Shizuku.bindUserService`).
 *
 * Its only reason to exist is to **allocate a PTY on the privileged side**:
 * a terminal needs a prompt, line editing, job control, and full-screen
 * programs, all of which require the shell's end to be a real terminal —
 * whereas `Shizuku.newProcess` only gives a pipe. Shizuku uses app_process
 * to launch **our own APK** running this class, and the APK lives under
 * `/data/app` (label `apk_data_file`), so it bypasses the
 * "`untrusted_app` may not load `app_data_file`" restriction — that is
 * exactly the wall `rish` ran into.
 *
 * ★ This class **must not touch anything that needs a Context**: this
 * process has no Application, and it is not a real Android component — it
 * is just a binder.
 */
class TwigPrivService : ITwigPrivService.Stub() {

    override fun getUid(): Int = Process.myUid()

    /**
     * Bind to the app's liveness token. As soon as the app process is gone,
     * this binder dies and we exit with it.
     *
     * ★ [destroy] alone is not enough: when the app is upgraded or killed
     * the binder is already disconnected, so Shizuku's destroy() never
     * reaches us, and **a root process is left behind on the device**
     * (verified on 2026-08-17: Shizuku's log says "Remove service record
     * ... all connections are gone", yet `ps` still shows com.twig.app:priv
     * alive).
     */
    override fun attach(token: android.os.IBinder?) {
        token ?: return
        runCatching {
            token.linkToDeath({
                Log.i(TAG, "client died, exiting")
                System.exit(0)
            }, 0)
        }.onFailure {
            // Already dead — nothing left to wait for
            Log.w(TAG, "client token already dead", it)
            System.exit(0)
        }
    }

    override fun start(
        apkPath: String,
        abi: String,
        cmd: String,
        cwd: String?,
        env: Array<String>,
        rows: Int,
        cols: Int,
        pid: IntArray,
    ): ParcelFileDescriptor? {
        val so = runCatching { ensureLib(apkPath, abi) }.getOrElse {
            Log.e(TAG, "cannot prepare libtwigpty", it)
            return null
        }
        runCatching { Pty.load(so) }.onFailure {
            Log.e(TAG, "cannot load $so", it)
            return null
        }
        val fd = Pty.nativeOpen(cmd, cwd, arrayOf(), env, pid, rows, cols)
        if (fd < 0) {
            Log.e(TAG, "forkpty failed for $cmd")
            return null
        }
        Log.i(TAG, "started $cmd pid=${pid[0]} uid=${Process.myUid()}")
        // adoptFd: ownership goes to the PFD — once it crosses the binder to
        // the app side, this side no longer holds it
        return ParcelFileDescriptor.adoptFd(fd)
    }

    override fun kill(pid: Int) {
        runCatching { Pty.nativeKill(pid) }
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    /**
     * Extract `libtwigpty.so` from the APK and return an absolute path that
     * `System.load` can use.
     *
     * Why extract: the .so inside the APK is stored **uncompressed**
     * (`extractNativeLibs=false`, the modern default), so `nativeLibraryDir`
     * is an empty directory, and this process has no app classloader to map
     * it from inside the APK — `System.loadLibrary` is guaranteed to fail.
     *
     * It lands in `/data/local/tmp`: both shell and root can write to it,
     * and it is **not** labelled `app_data_file`, so it does not collide
     * with W^X. Whether to re-extract is decided by size, so an upgrade
     * with a new library is picked up automatically.
     */
    private fun ensureLib(apkPath: String, abi: String): String {
        val entryName = "lib/$abi/$LIB"
        val dir = File(TMP_DIR).apply { mkdirs() }
        val out = File(dir, LIB)
        ZipFile(apkPath).use { zip ->
            val e = zip.getEntry(entryName) ?: error("no $entryName in $apkPath")
            if (!out.isFile || out.length() != e.size) {
                // ★ Write to a temp name → harden permissions first → atomic
                // rename. Writing straight to the target file leaves a
                // **world-writable** window between creation and chmod, and
                // this .so is then loaded into a root process — that is a
                // local privilege-escalation path.
                // (Verified 2026-08-17: an earlier version landed it as
                // -rwxrwxrwx.)
                val tmp = File(dir, "$LIB.tmp")
                tmp.delete()
                zip.getInputStream(e).use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                }
                harden(tmp)
                // The previous output is read-only, but deletion depends on
                // **the directory's** write permission, not the file's own
                // permission bits — so it can still be removed.
                out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.delete()
                    error("could not put $LIB in place")
                }
            }
        }
        // The "already exists, no re-extract" path must also go through this:
        // writable files left behind by older versions need to be re-hardened.
        harden(out)
        return out.absolutePath
    }

    /**
     * Readable, executable, **and not writable by anyone**.
     *
     * The non-writable bit is a hard requirement: the system is already
     * warning "Attempt to load writable file ... will throw on a future
     * Android version", and writable means the code loaded into a root
     * process can be swapped out. We do not restrict to "owner only"
     * because Shizuku may run as root this time and as shell the next,
     * and a different owner would lock us out of our own previous file.
     */
    private fun harden(f: File) {
        f.setReadable(true, false)
        f.setExecutable(true, false)
        f.setWritable(false, false)
    }

    private companion object {
        const val TAG = "twig-privsvc"
        const val LIB = "libtwigpty.so"
        const val TMP_DIR = "/data/local/tmp/twig"
    }
}
