#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>

extern "C" {
#include "libsmb2/include/smb2/smb2.h"
#include "libsmb2/include/smb2/libsmb2.h"
}

#include <fcntl.h>

#define TAG "TwigSmbJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

// ── helpers ──────────────────────────────────────────────────────────────────

static const char* jstr(JNIEnv* env, jstring s) {
    return s ? env->GetStringUTFChars(s, nullptr) : nullptr;
}
static void reljstr(JNIEnv* env, jstring s, const char* c) {
    if (s && c) env->ReleaseStringUTFChars(s, c);
}

// ── connection lifecycle ──────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeConnect(
        JNIEnv* env, jobject /*thiz*/,
        jstring jHost, jstring jShare, jstring jUser, jstring jPass, jstring jDomain) {

    const char* host   = jstr(env, jHost);
    const char* share  = jstr(env, jShare);
    const char* user   = jstr(env, jUser);
    const char* pass   = jstr(env, jPass);
    const char* domain = jstr(env, jDomain);

    smb2_context* ctx = smb2_init_context();
    if (!ctx) {
        LOGE("smb2_init_context failed");
        reljstr(env, jHost, host); reljstr(env, jShare, share);
        reljstr(env, jUser, user); reljstr(env, jPass, pass);
        reljstr(env, jDomain, domain);
        return 0;
    }

    if (user)   smb2_set_user(ctx, user);
    if (pass)   smb2_set_password(ctx, pass);
    if (domain) smb2_set_domain(ctx, domain);
    smb2_set_security_mode(ctx, SMB2_NEGOTIATE_SIGNING_ENABLED);

    int ret = smb2_connect_share(ctx, host, share, nullptr);
    reljstr(env, jHost, host); reljstr(env, jShare, share);
    reljstr(env, jUser, user); reljstr(env, jPass, pass);
    reljstr(env, jDomain, domain);

    if (ret < 0) {
        LOGE("smb2_connect_share failed: %s", smb2_get_error(ctx));
        smb2_destroy_context(ctx);
        return 0;
    }
    LOGI("Connected");
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeDisconnect(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (!handle) return;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    smb2_disconnect_share(ctx);
    smb2_destroy_context(ctx);
    LOGI("Disconnected");
}

// 硬销毁:只释放 context(不 logoff、不 smb2_close),供媒体专用连接收尾用。
// 避免 smb2_close/smb2_disconnect_share 里的 wait_for_reply 在收尾时释放陈旧 reply pdu 导致堆崩溃。
extern "C"
JNIEXPORT void JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (!handle) return;
    smb2_destroy_context(reinterpret_cast<smb2_context*>(handle));
    LOGI("Destroyed (no logoff)");
}

// ── directory listing ─────────────────────────────────────────────────────────
// Each entry encoded as "name\tisDir\tsize\tmtime" (\t = 0x09, 文件名几乎不含).

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeListDirectory(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPath) {

    if (!handle) return nullptr;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    const char* path = jstr(env, jPath);

    smb2dir* dir = smb2_opendir(ctx, path ? path : "");
    reljstr(env, jPath, path);
    if (!dir) {
        LOGE("smb2_opendir failed: %s", smb2_get_error(ctx));
        return nullptr;
    }

    int count = 0;
    smb2dirent* ent;
    while ((ent = smb2_readdir(ctx, dir)) != nullptr) {
        if (strcmp(ent->name, ".") == 0 || strcmp(ent->name, "..") == 0) continue;
        count++;
    }
    smb2_rewinddir(ctx, dir);

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(count, strClass, nullptr);

    int idx = 0;
    char numbuf[64];
    while ((ent = smb2_readdir(ctx, dir)) != nullptr) {
        if (strcmp(ent->name, ".") == 0 || strcmp(ent->name, "..") == 0) continue;
        bool isDir = (ent->st.smb2_type == SMB2_TYPE_DIRECTORY);

        std::string s(ent->name);
        s += '\t';
        s += (isDir ? '1' : '0');
        s += '\t';
        snprintf(numbuf, sizeof(numbuf), "%llu", (unsigned long long) ent->st.smb2_size);
        s += numbuf;
        s += '\t';
        snprintf(numbuf, sizeof(numbuf), "%llu", (unsigned long long) ent->st.smb2_mtime);
        s += numbuf;

        jstring jEntry = env->NewStringUTF(s.c_str());
        env->SetObjectArrayElement(result, idx++, jEntry);
        env->DeleteLocalRef(jEntry);
    }

    smb2_closedir(ctx, dir);
    return result;
}

// ── read ──────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeOpenFile(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPath) {
    if (!handle) return 0;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    const char* path = jstr(env, jPath);
    smb2fh* fh = smb2_open(ctx, path, O_RDONLY);
    reljstr(env, jPath, path);
    if (!fh) { LOGE("smb2_open(r) failed: %s", smb2_get_error(ctx)); return 0; }
    return reinterpret_cast<jlong>(fh);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeGetFileSize(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong fhHandle) {
    if (!handle || !fhHandle) return -1;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    auto* fh  = reinterpret_cast<smb2fh*>(fhHandle);
    struct smb2_stat_64 st{};
    if (smb2_fstat(ctx, fh, &st) < 0) return -1;
    return static_cast<jlong>(st.smb2_size);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeReadFile(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jlong fhHandle,
        jbyteArray jBuf, jint offset, jint length) {
    if (!handle || !fhHandle) return -1;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    auto* fh  = reinterpret_cast<smb2fh*>(fhHandle);
    if (length <= 0) return 0;
    // 让 libsmb2 只写原生缓冲(其 reply iovector 直接指向此指针),读完再拷回 Java 数组,
    // 避免它写进 GetByteArrayElements 可能返回的 JVM 副本导致的堆交互问题。
    auto* tmp = static_cast<uint8_t*>(malloc(static_cast<size_t>(length)));
    if (!tmp) return -1;
    int n = smb2_read(ctx, fh, tmp, static_cast<uint32_t>(length));
    if (n > 0) env->SetByteArrayRegion(jBuf, offset, n, reinterpret_cast<const jbyte*>(tmp));
    free(tmp);
    if (n < 0) LOGE("smb2_read failed: %s", smb2_get_error(ctx));
    return n;
}

// ── 定位读(pread):供媒体随机 seek,避免 skip 的 O(n) 重下载 ────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativePread(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jlong fhHandle,
        jlong offset, jbyteArray jBuf, jint bufOffset, jint length) {
    if (!handle || !fhHandle) return -1;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    auto* fh  = reinterpret_cast<smb2fh*>(fhHandle);
    if (length <= 0) return 0;
    auto* tmp = static_cast<uint8_t*>(malloc(static_cast<size_t>(length)));
    if (!tmp) return -1;
    int n = smb2_pread(ctx, fh, tmp,
                       static_cast<uint32_t>(length), static_cast<uint64_t>(offset));
    if (n > 0) env->SetByteArrayRegion(jBuf, bufOffset, n, reinterpret_cast<const jbyte*>(tmp));
    free(tmp);
    if (n < 0) LOGE("smb2_pread failed: %s", smb2_get_error(ctx));
    return n;
}

// ── write ───────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeOpenWrite(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPath) {
    if (!handle) return 0;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    const char* path = jstr(env, jPath);
    smb2fh* fh = smb2_open(ctx, path, O_WRONLY | O_CREAT | O_TRUNC);
    reljstr(env, jPath, path);
    if (!fh) { LOGE("smb2_open(w) failed: %s", smb2_get_error(ctx)); return 0; }
    return reinterpret_cast<jlong>(fh);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeWriteFile(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jlong fhHandle,
        jbyteArray jBuf, jint offset, jint length) {
    if (!handle || !fhHandle) return -1;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    auto* fh  = reinterpret_cast<smb2fh*>(fhHandle);
    jbyte* buf = env->GetByteArrayElements(jBuf, nullptr);
    int n = smb2_write(ctx, fh, reinterpret_cast<uint8_t*>(buf + offset),
                       static_cast<uint32_t>(length));
    env->ReleaseByteArrayElements(jBuf, buf, JNI_ABORT);
    if (n < 0) LOGE("smb2_write failed: %s", smb2_get_error(ctx));
    return n;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeCloseFile(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong fhHandle) {
    if (!handle || !fhHandle) return;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    auto* fh  = reinterpret_cast<smb2fh*>(fhHandle);
    smb2_close(ctx, fh);
}

// ── mutating ops ──────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeMkdir(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPath) {
    if (!handle) return -1;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    const char* path = jstr(env, jPath);
    int ret = smb2_mkdir(ctx, path);
    reljstr(env, jPath, path);
    return ret;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeDelete(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPath, jboolean isDir) {
    if (!handle) return -1;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    const char* path = jstr(env, jPath);
    int ret = isDir ? smb2_rmdir(ctx, path) : smb2_unlink(ctx, path);
    reljstr(env, jPath, path);
    return ret;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeRename(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jFrom, jstring jTo) {
    if (!handle) return -1;
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    const char* from = jstr(env, jFrom);
    const char* to   = jstr(env, jTo);
    int ret = smb2_rename(ctx, from, to);
    reljstr(env, jFrom, from); reljstr(env, jTo, to);
    return ret;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeGetLastError(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    if (!handle) return env->NewStringUTF("no context");
    auto* ctx = reinterpret_cast<smb2_context*>(handle);
    const char* err = smb2_get_error(ctx);
    return env->NewStringUTF(err ? err : "");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_twig_fs_smb_NativeSmbClient_nativeDialect(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (!handle) return 0;
    return (jint) smb2_get_dialect(reinterpret_cast<smb2_context*>(handle));
}
