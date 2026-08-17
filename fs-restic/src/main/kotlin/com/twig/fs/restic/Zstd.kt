package com.twig.fs.restic

/**
 * zstd 解压抽象。restic v2 用 zstd 压缩;不同平台注入不同实现
 * (Android 用原生 libzstd,JVM 测试用纯 Java),避免在 Android 上依赖 sun.misc.Unsafe。
 */
fun interface Zstd {
    /**
     * 解压一个完整 zstd 帧。
     * @param expectedSize >=0 为已知原长(pack blob,来自 index 的 uncompressed_length);
     *                     -1 表示从帧头读取原长(用于快照/索引等文件)。
     */
    fun decompress(input: ByteArray, expectedSize: Int): ByteArray
}
