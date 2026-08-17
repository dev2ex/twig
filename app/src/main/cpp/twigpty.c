/*
 * A pseudo-terminal for the privileged helper process.
 *
 * Why this exists at all: a terminal needs a real PTY on the side where the
 * shell runs — that is what makes the shell interactive (prompt, line editing,
 * job control) and lets full-screen programs work. Pipes cannot provide it.
 * Shizuku's newProcess only hands back pipes, so the PTY has to be allocated
 * inside the privileged process, which is what Shizuku's user service gives us.
 *
 * Why not reuse termux's libtermux.so, which already does this and is already in
 * the APK: its `JNI` class is package-private and its static initializer calls
 * System.loadLibrary("termux"). In the privileged process there is no app
 * context and the native libraries are stored uncompressed *inside* the APK
 * (they are never extracted to nativeLibraryDir), so that lookup fails — and a
 * class whose static initializer threw is permanently unusable, there is no
 * retry. Owning the class means we can load the .so by absolute path instead.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Copies a Java string array into a NULL-terminated char* array. */
static char **to_c_array(JNIEnv *env, jobjectArray arr, const char *first) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    jsize extra = first ? 1 : 0;
    char **out = calloc((size_t) (n + extra + 1), sizeof(char *));
    if (!out) return NULL;
    if (first) out[0] = strdup(first);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i + extra] = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_c_array(char **a) {
    if (!a) return;
    for (char **p = a; *p; p++) free(*p);
    free(a);
}

/*
 * Allocates a PTY and starts [cmd] on its slave side. Returns the master fd, or
 * -1 on failure; the child pid is written to pid_out[0].
 *
 * forkpty() does openpty + fork + setsid + TIOCSCTTY + dup2 in one call, which
 * is exactly the sequence that makes the slave the child's *controlling*
 * terminal — without that last part there is no job control and Ctrl+C does
 * nothing.
 *
 * ★ Everything the child needs is converted to C *before* the fork: after
 * fork() in a JVM process only async-signal-safe calls are legal, and any JNI
 * call may take a lock that another thread held at the moment of the fork.
 */
JNIEXPORT jint JNICALL
Java_com_twig_app_priv_Pty_nativeOpen(JNIEnv *env, jobject thiz, jstring cmd_, jstring cwd_,
                                      jobjectArray args_, jobjectArray env_, jintArray pid_out,
                                      jint rows, jint cols) {
    (void) thiz;
    const char *cmd_j = (*env)->GetStringUTFChars(env, cmd_, NULL);
    const char *cwd_j = cwd_ ? (*env)->GetStringUTFChars(env, cwd_, NULL) : NULL;
    char *cmd = strdup(cmd_j ? cmd_j : "");
    char *cwd = cwd_j ? strdup(cwd_j) : NULL;
    (*env)->ReleaseStringUTFChars(env, cmd_, cmd_j);
    if (cwd_) (*env)->ReleaseStringUTFChars(env, cwd_, cwd_j);

    char **argv = to_c_array(env, args_, cmd);
    char **envp = to_c_array(env, env_, NULL);
    if (!argv || !envp) {
        free(cmd); free(cwd); free_c_array(argv); free_c_array(envp);
        return -1;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        free(cmd); free(cwd); free_c_array(argv); free_c_array(envp);
        return -1;
    }

    if (pid == 0) {
        /*
         * ★ The JVM blocks most signals and ignores SIGPIPE; a shell inherits
         * that mask through exec and then behaves oddly — Ctrl+C looks dead and
         * writes to a closed pipe never terminate anything. Reset both.
         */
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);

        if (cwd && chdir(cwd) != 0) { /* fall through: a bad cwd must not be fatal */ }
        execve(cmd, argv, envp);
        _exit(127);
    }

    jint p = (jint) pid;
    (*env)->SetIntArrayRegion(env, pid_out, 0, 1, &p);
    free(cmd); free(cwd); free_c_array(argv); free_c_array(envp);
    return master;
}

JNIEXPORT void JNICALL
Java_com_twig_app_priv_Pty_nativeSetWinSize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void) env; (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/** Blocks until [pid] exits; returns its exit status (128+signal if killed). */
JNIEXPORT jint JNICALL
Java_com_twig_app_priv_Pty_nativeWaitFor(JNIEnv *env, jobject thiz, jint pid) {
    (void) env; (void) thiz;
    int status = 0;
    while (waitpid((pid_t) pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_twig_app_priv_Pty_nativeKill(JNIEnv *env, jobject thiz, jint pid) {
    (void) env; (void) thiz;
    /* The whole foreground group, so the shell's children go too. */
    kill((pid_t) -pid, SIGHUP);
    kill((pid_t) pid, SIGHUP);
}

JNIEXPORT void JNICALL
Java_com_twig_app_priv_Pty_nativeClose(JNIEnv *env, jobject thiz, jint fd) {
    (void) env; (void) thiz;
    if (fd >= 0) close(fd);
}
