#include <jni.h>
#include "zstd.h"

// Java: com.twig.fs.zstd.NativeZstd.nativeDecompress(byte[] input, int expectedSize) -> byte[]?
// expectedSize>=0 用作输出长度(pack blob,index 已知原长);-1 从帧头取原长。
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_twig_fs_zstd_NativeZstd_nativeDecompress(
        JNIEnv* env, jobject /*thiz*/, jbyteArray input, jint expectedSize) {

    jsize len = env->GetArrayLength(input);
    jbyte* in = env->GetByteArrayElements(input, nullptr);
    if (!in) return nullptr;

    unsigned long long outSize;
    if (expectedSize >= 0) {
        outSize = (unsigned long long) expectedSize;
    } else {
        outSize = ZSTD_getFrameContentSize(in, (size_t) len);
        if (outSize == ZSTD_CONTENTSIZE_UNKNOWN || outSize == ZSTD_CONTENTSIZE_ERROR) {
            env->ReleaseByteArrayElements(input, in, JNI_ABORT);
            return nullptr;
        }
    }

    jbyteArray out = env->NewByteArray((jsize) outSize);
    if (!out) {
        env->ReleaseByteArrayElements(input, in, JNI_ABORT);
        return nullptr;
    }
    jbyte* outBuf = env->GetByteArrayElements(out, nullptr);

    size_t r = ZSTD_decompress(outBuf, (size_t) outSize, in, (size_t) len);

    env->ReleaseByteArrayElements(input, in, JNI_ABORT);
    bool err = ZSTD_isError(r) || r != outSize;
    env->ReleaseByteArrayElements(out, outBuf, 0);
    return err ? nullptr : out;
}
