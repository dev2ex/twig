#include <jni.h>
#include <cstdint>
#include "zstd.h"

// Java: com.twig.fs.zstd.NativeZstd.nativeDecompress(byte[] input, int expectedSize) -> byte[]?
// expectedSize >= 0 is used as the output length (pack blob, where the index already
// knows the original length); -1 reads it from the frame header.
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

// ---- Streaming decompression (com.twig.fs.zstd.ZstdInputStream) ----
// Whole-buffer decompression ([nativeDecompress]) requires the source to fit in
// memory at once — files inside archives can't be read that way: a .tar.zst may
// decompress to several GB. This exposes the ZSTD_decompressStream trio, driven
// by the Kotlin side using InputStream semantics.
//
// Concatenated frames (`cat a.zst b.zst`) work naturally: after one frame is
// drained, the DStream just keeps reading the next frame from the same context,
// the caller doesn't need to be aware of the boundary.

extern "C"
JNIEXPORT jlong JNICALL
Java_com_twig_fs_zstd_ZstdInputStream_nativeCreate(JNIEnv* /*env*/, jclass /*clazz*/) {
    return (jlong) (uintptr_t) ZSTD_createDStream();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_twig_fs_zstd_ZstdInputStream_nativeFree(JNIEnv* /*env*/, jclass /*clazz*/, jlong ctx) {
    if (ctx) ZSTD_freeDStream((ZSTD_DStream*) (uintptr_t) ctx);
}

// Returns (consumed << 32) | produced; -1 on error.
// Both counts are bounded by the buffer size (far below 2^31), so packing them
// into a single jlong saves one cross-language allocation per call — this is
// the decompression hot path, called once per chunk read.
extern "C"
JNIEXPORT jlong JNICALL
Java_com_twig_fs_zstd_ZstdInputStream_nativeDecompress(
        JNIEnv* env, jclass /*clazz*/, jlong ctx,
        jbyteArray src, jint srcOff, jint srcLen,
        jbyteArray dst, jint dstOff, jint dstLen) {

    ZSTD_DStream* zds = (ZSTD_DStream*) (uintptr_t) ctx;
    if (!zds) return -1;

    jbyte* in = env->GetByteArrayElements(src, nullptr);
    if (!in) return -1;
    jbyte* out = env->GetByteArrayElements(dst, nullptr);
    if (!out) {
        env->ReleaseByteArrayElements(src, in, JNI_ABORT);
        return -1;
    }

    ZSTD_inBuffer inBuf = { in + srcOff, (size_t) srcLen, 0 };
    ZSTD_outBuffer outBuf = { out + dstOff, (size_t) dstLen, 0 };
    size_t r = ZSTD_decompressStream(zds, &outBuf, &inBuf);

    env->ReleaseByteArrayElements(src, in, JNI_ABORT);
    // Output buffer must be written back to the Java heap (mode 0); input is
    // read-only, JNI_ABORT saves a copy-back
    env->ReleaseByteArrayElements(dst, out, 0);

    if (ZSTD_isError(r)) return -1;
    return ((jlong) inBuf.pos << 32) | (jlong) outBuf.pos;
}
