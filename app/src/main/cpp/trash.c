/* Same-volume moves that atomically refuse an existing destination. */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

static int rename_exclusive(const char *from, const char *to) {
#ifdef TWIG_TRASH_FORCE_LEGACY
    (void)from;
    (void)to;
    return EINVAL;
#else
#ifdef SYS_renameat2
    return syscall(SYS_renameat2, AT_FDCWD, from, AT_FDCWD, to, RENAME_NOREPLACE) == 0 ? 0 : errno;
#else
    return ENOSYS;
#endif
#endif
}

/* Old Android FUSE rejects rename flags. Reserve the name exclusively, then
 * replace only our empty reservation. Ordinary concurrent creators cannot win
 * the destination; external unlink/replacement of the reservation is outside
 * this guarantee (unlike the atomic renameat2 path).
 */
static int move_no_replace(const char *from, const char *to) {
    int error = rename_exclusive(from, to);
    if (error != EINVAL && error != ENOSYS && error != EOPNOTSUPP) return error;

    struct stat source, reserved, current;
    if (lstat(from, &source) != 0) return errno;
    int directory = S_ISDIR(source.st_mode);
    int fd = -1;
    if (directory) {
        if (mkdir(to, 0700) != 0) return errno;
        if (lstat(to, &reserved) != 0) return errno;
    } else {
        fd = open(to, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (fd < 0) return errno;
        if (fstat(fd, &reserved) != 0) {
            error = errno;
            close(fd);
            return error;
        }
    }
    if (lstat(to, &current) != 0) error = errno;
    else if (current.st_dev != reserved.st_dev || current.st_ino != reserved.st_ino)
        error = EEXIST;
    else error = rename(from, to) == 0 ? 0 : errno;

    /* Never clean up a name another actor has replaced. A failed move keeps
     * the source and its recovery metadata; no copy/delete fallback.
     */
    if (error && lstat(to, &current) == 0 &&
            current.st_dev == reserved.st_dev && current.st_ino == reserved.st_ino) {
        if (directory) rmdir(to); else unlink(to);
    }
    if (fd >= 0) close(fd);
    return error;
}

static char *path_bytes(JNIEnv *env, jbyteArray bytes) {
    jsize length = (*env)->GetArrayLength(env, bytes);
    char *path = malloc((size_t)length + 1);
    if (!path) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)path);
    path[length] = '\0';
    return path;
}

JNIEXPORT jint JNICALL
Java_com_twig_app_TrashMoves_nativeRename(JNIEnv *env, jobject self,
                                          jbyteArray source, jbyteArray destination) {
    (void)self;
    char *from = path_bytes(env, source);
    char *to = path_bytes(env, destination);
    int error = ENOMEM;
    if (from && to && !(*env)->ExceptionCheck(env)) {
        error = move_no_replace(from, to);
    }
    free(from);
    free(to);
    return error;
}
