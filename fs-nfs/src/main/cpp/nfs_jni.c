#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <nfsc/libnfs.h>

/* A file stream owns a session. Kotlin serializes every use of each session. */
struct session { struct nfs_context *nfs; struct nfsfh *file; };
static struct session *session(jlong h) { return (struct session *)(intptr_t)h; }
static jstring string(JNIEnv *env, const char *p);
static void fail(JNIEnv *env, struct session *s) {
    if ((*env)->ExceptionCheck(env)) return;
    const char *message = s ? nfs_get_error(s->nfs) : "NFS allocation failed";
    jclass cls = (*env)->FindClass(env, "com/twig/core/FsException");
    if (!cls) return;
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V");
    if (!ctor) { (*env)->DeleteLocalRef(env, cls); return; }
    jstring msg = string(env, message ? message : "NFS operation failed");
    jthrowable error = msg ? (*env)->NewObject(env, cls, ctor, msg, NULL) : NULL;
    if (error) (*env)->Throw(env, error);
    (*env)->DeleteLocalRef(env, msg);
    (*env)->DeleteLocalRef(env, error);
    (*env)->DeleteLocalRef(env, cls);
}
static char *text(JNIEnv *env, jbyteArray bytes) {
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *p = calloc((size_t)n + 1, 1);
    if (!p) { fail(env, NULL); return NULL; }
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)p);
    if ((*env)->ExceptionCheck(env)) { free(p); return NULL; }
    return p;
}
/* JNI NewStringUTF uses modified UTF-8; file names use ordinary UTF-8. */
static jstring string(JNIEnv *env, const char *p) {
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)strlen(p));
    if (!bytes) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)strlen(p), (const jbyte *)p);
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    if (!cls) { (*env)->DeleteLocalRef(env, bytes); return NULL; }
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "([BLjava/lang/String;)V");
    if (!ctor) { (*env)->DeleteLocalRef(env, bytes); (*env)->DeleteLocalRef(env, cls); return NULL; }
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    jstring result = charset ? (*env)->NewObject(env, cls, ctor, bytes, charset) : NULL;
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}
#define JNI(name) Java_com_twig_fs_nfs_NativeNfsClient_##name
JNIEXPORT jlong JNICALL JNI(nConnect)(JNIEnv *env, jobject self, jbyteArray host,
        jbyteArray export, jint version, jint uid, jint gid, jint port) {
    struct session *s = calloc(1, sizeof(*s));
    if (!s) { fail(env, NULL); return 0; }
    s->nfs = nfs_init_context();
    if (!s->nfs) { free(s); fail(env, NULL); return 0; }
    nfs_set_uid(s->nfs, uid); nfs_set_gid(s->nfs, gid);
    nfs_set_nfsport(s->nfs, port);
    nfs_set_timeout(s->nfs, 15000);
    nfs_set_retrans(s->nfs, 0);
    nfs_set_autoreconnect(s->nfs, 0);
    nfs_set_dircache(s->nfs, 0);
    char *h = text(env, host);
    char *e = h ? text(env, export) : NULL;
    int result = -1;
    if (h && e && nfs_set_version(s->nfs, version) == 0)
        result = nfs_mount(s->nfs, h, e);
    free(h); free(e);
    if (result < 0) {
        if (!(*env)->ExceptionCheck(env)) fail(env, s);
        nfs_destroy_context(s->nfs); free(s); return 0;
    }
    return (jlong)(intptr_t)s;
}
JNIEXPORT void JNICALL JNI(nDisconnect)(JNIEnv *env, jobject self, jlong handle) {
    struct session *s = session(handle);
    if (s->file && nfs_close(s->nfs, s->file) < 0) fail(env, s);
    nfs_destroy_context(s->nfs); free(s);
}
JNIEXPORT jobjectArray JNICALL JNI(nList)(JNIEnv *env, jobject self, jlong handle, jbyteArray path) {
    struct session *s = session(handle);
    char *p = text(env, path);
    if (!p) return NULL;
    struct nfsdir *dir = NULL;
    int result = nfs_opendir(s->nfs, p, &dir);
    free(p);
    if (result < 0) { fail(env, s); return NULL; }
    int count = 0;
    struct nfsdirent *e;
    while ((e = nfs_readdir(s->nfs, dir)))
        if (strcmp(e->name, ".") && strcmp(e->name, "..")) count++;
    nfs_rewinddir(s->nfs, dir);
    jclass cls = (*env)->FindClass(env, "com/twig/fs/nfs/NfsEntry");
    if (!cls) { nfs_closedir(s->nfs, dir); return NULL; }
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;JJJ)V");
    if (!ctor) { nfs_closedir(s->nfs, dir); return NULL; }
    jobjectArray entries = (*env)->NewObjectArray(env, count, cls, NULL);
    int i = 0;
    while (entries && (e = nfs_readdir(s->nfs, dir))) {
        if (!strcmp(e->name, ".") || !strcmp(e->name, "..")) continue;
        jstring name = string(env, e->name);
        if (!name) break;
        jobject entry = (*env)->NewObject(env, cls, ctor, name,
            (jlong)(e->mode | (e->type == 2 ? S_IFDIR : 0)), (jlong)e->size,
            (jlong)e->mtime.tv_sec * 1000 + e->mtime.tv_usec / 1000);
        if (entry) (*env)->SetObjectArrayElement(env, entries, i++, entry);
        (*env)->DeleteLocalRef(env, name); (*env)->DeleteLocalRef(env, entry);
        if ((*env)->ExceptionCheck(env)) break;
    }
    nfs_closedir(s->nfs, dir);
    return entries;
}
JNIEXPORT jlongArray JNICALL JNI(nStat)(JNIEnv *env, jobject self, jlong handle, jbyteArray path, jboolean follow) {
    struct session *s = session(handle);
    char *p = text(env, path);
    if (!p) return NULL;
    struct nfs_stat_64 st;
    int result = follow ? nfs_stat64(s->nfs, p, &st) : nfs_lstat64(s->nfs, p, &st);
    free(p);
    if (result == -ENOENT) return NULL;
    if (result < 0) { fail(env, s); return NULL; }
    jlong values[] = { (jlong)st.nfs_mode, (jlong)st.nfs_size,
        (jlong)st.nfs_mtime * 1000 + (jlong)st.nfs_mtime_nsec / 1000000 };
    jlongArray out = (*env)->NewLongArray(env, 3);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 3, values);
    return out;
}
JNIEXPORT void JNICALL JNI(nOpen)(JNIEnv *env, jobject self, jlong handle, jbyteArray path, jboolean write) {
    struct session *s = session(handle);
    char *p = text(env, path);
    if (!p) return;
    int result = write ? nfs_creat(s->nfs, p, 0666, &s->file)
                       : nfs_open(s->nfs, p, O_RDONLY, &s->file);
    free(p);
    if (result < 0) fail(env, s);
}
JNIEXPORT jlong JNICALL JNI(nSize)(JNIEnv *env, jobject self, jlong handle) {
    struct session *s = session(handle);
    struct nfs_stat_64 st;
    if (nfs_fstat64(s->nfs, s->file, &st) < 0) { fail(env, s); return 0; }
    return (jlong)st.nfs_size;
}
JNIEXPORT jint JNICALL JNI(nRead)(JNIEnv *env, jobject self, jlong handle, jlong position,
        jbyteArray bytes, jint offset, jint length) {
    struct session *s = session(handle);
    void *buffer = malloc((size_t)length);
    if (!buffer) { fail(env, NULL); return -1; }
    int result = nfs_pread(s->nfs, s->file, buffer, (size_t)length, (uint64_t)position);
    if (result > 0) (*env)->SetByteArrayRegion(env, bytes, offset, result, buffer);
    free(buffer);
    if (result < 0) fail(env, s);
    return result == 0 ? -1 : result;
}
JNIEXPORT void JNICALL JNI(nWrite)(JNIEnv *env, jobject self, jlong handle, jlong position,
        jbyteArray bytes, jint offset, jint length) {
    struct session *s = session(handle);
    char *buffer = malloc((size_t)length);
    if (!buffer) { fail(env, NULL); return; }
    (*env)->GetByteArrayRegion(env, bytes, offset, length, (jbyte *)buffer);
    if ((*env)->ExceptionCheck(env)) { free(buffer); return; }
    int done = 0;
    while (done < length) {
        int n = nfs_pwrite(s->nfs, s->file, buffer + done, (size_t)(length - done), (uint64_t)position + done);
        if (n <= 0) {
            if (n < 0) fail(env, s);
            else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "NFS write made no progress");
            break;
        }
        done += n;
    }
    free(buffer);
}
JNIEXPORT void JNICALL JNI(nMutate)(JNIEnv *env, jobject self, jlong handle, jint operation,
        jbyteArray path, jbyteArray target) {
    struct session *s = session(handle);
    char *p = text(env, path);
    char *t = p ? text(env, target) : NULL;
    if (!p || !t) { free(p); free(t); return; }
    int result = -EINVAL;
    switch (operation) {
        case 0: result = nfs_mkdir2(s->nfs, p, 0777); break;
        case 1: result = nfs_unlink(s->nfs, p); break;
        case 2: result = nfs_rmdir(s->nfs, p); break;
        case 3: result = nfs_rename(s->nfs, p, t); break;
    }
    free(p); free(t);
    if (result < 0) fail(env, s);
}
