package com.twig.fs.archive

import com.twig.core.FsException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 7z 文件系统(只读),基于 Apache Commons Compress(纯 Java)。
 * 加密包直接用库自带的 AES-256 解密([SevenZFile.Builder.setPassword]),包括**头加密**
 * ——那种包连文件名都要密码才列得出来,所以 [readEntries] 也得带着密码走。
 */
class SevenZFileSystem : ArchiveFileSystem() {

    override val scheme: String = SCHEME
    override val displayName: String = "7z archive"
    override fun writable(): Boolean = false

    /** 走定位读通道:本地与远程通用,远程只拉归档头与被访问的数据段。 */
    private fun openSz(archivePath: String, password: String? = passwordOf(archivePath)): SevenZFile =
        SevenZFile.builder()
            .setSeekableByteChannel(openChannel(archivePath))
            .also { b -> password?.let { b.setPassword(it) } }
            .get()

    override fun readEntries(archivePath: String): List<ArchiveEntry> {
        val list = ArrayList<ArchiveEntry>()
        try {
            openSz(archivePath).use { sz ->
                var e: SevenZArchiveEntry? = sz.nextEntry
                while (e != null) {
                    list.add(
                        ArchiveEntry(
                            name = e.name,
                            isDir = e.isDirectory,
                            size = if (e.isDirectory) 0L else e.size,
                            time = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L,
                        ),
                    )
                    e = sz.nextEntry
                }
            }
        } catch (t: Throwable) {
            // 头加密的包没密码连清单都读不出来 —— 换成 UI 认得的异常去弹密码框
            throw asPasswordIssue(archivePath, t) ?: t
        }
        return list
    }

    override fun openEntry(archivePath: String, inner: String): InputStream {
        val sz = try {
            openSz(archivePath)
        } catch (t: Throwable) {
            throw asPasswordIssue(archivePath, t) ?: t
        }
        try {
            var e: SevenZArchiveEntry? = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.replace('\\', '/') == inner) {
                    val input = sz.getInputStream(e)
                    return object : FilterInputStream(input) {
                        override fun close() {
                            try { super.close() } finally { sz.close() }
                        }
                    }
                }
                e = sz.nextEntry
            }
        } catch (t: Throwable) {
            sz.close()
            throw asPasswordIssue(archivePath, t) ?: t
        }
        sz.close()
        throw FsException("No such file in 7z archive: $inner")
    }

    // ---- 密码 ----

    /** 归档 → 无密码时是否读不动(头加密或内容加密);探测一次记下来。 */
    private val encCache = HashMap<String, Boolean>()

    override fun needsPassword(archivePath: String): Boolean {
        val key = stampOf(archivePath) // 同名文件被换掉时自动失效
        synchronized(encCache) { encCache[key]?.let { return it } }
        val enc = try {
            openSz(archivePath, password = null).use { sz ->
                // 头没加密的话清单能列出来,但内容仍可能是加密的:试读第一个非空文件
                var e: SevenZArchiveEntry? = sz.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.size > 0) {
                        sz.getInputStream(e).read()
                        break
                    }
                    e = sz.nextEntry
                }
            }
            false
        } catch (t: Throwable) {
            // 只有"缺密码"才算需要密码;包本身坏了要让原异常在正常路径上报出来
            isPasswordIssue(t)
        }
        synchronized(encCache) { encCache[key] = enc }
        return enc
    }

    /**
     * 校验密码:第一个非空条目**整读**一遍(7z 的 CRC 要读到流末才校验,只读个开头
     * 判不出密码对错)。条目很大时封顶 [VERIFY_LIMIT],那时靠 LZMA 解码当场报错兜底
     * ——密码错解出来的是随机字节,轮不到 CRC 就已经不是合法的 LZMA 流了。
     */
    override fun checkPassword(archivePath: String, password: String): Boolean = try {
        openSz(archivePath, password).use { sz ->
            var e: SevenZArchiveEntry? = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.size > 0) {
                    val input = sz.getInputStream(e)
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (total < VERIFY_LIMIT) {
                        val n = input.read(buf)
                        if (n < 0) break // 整条读完了,CRC 也就跟着校验过了
                        total += n
                    }
                    break
                }
                e = sz.nextEntry
            }
        }
        true
    } catch (t: Throwable) {
        if (isPasswordIssue(t) || isChecksumIssue(t)) false else throw t
    }

    private fun asPasswordIssue(archivePath: String, t: Throwable): ArchivePasswordException? = when {
        t is ArchivePasswordException -> t
        isPasswordIssue(t) -> ArchivePasswordException(archivePath, wrong = hasPassword(archivePath))
        isChecksumIssue(t) && hasPassword(archivePath) -> ArchivePasswordException(archivePath, wrong = true)
        else -> null
    }

    companion object {
        const val SCHEME = "7z"

        /** 校验密码时最多读这么多(足够让 LZMA 解码在密码错时炸出来)。 */
        private const val VERIFY_LIMIT = 4L * 1024 * 1024

        private fun causes(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause.takeIf { c -> c !== it } }

        private fun isPasswordIssue(t: Throwable): Boolean = causes(t).any {
            it is org.apache.commons.compress.PasswordRequiredException ||
                it.message?.contains("password", ignoreCase = true) == true ||
                it.message?.contains("encrypted", ignoreCase = true) == true
        }

        /**
         * 密码错时 AES 解出来的是垃圾:LZMA 解码当场报 `CorruptedInputException`,
         * 侥幸解得动也过不了 CRC。**只在"已经给过密码"的前提下**用这个判据——
         * 否则真损坏的包会被说成密码错。
         */
        private fun isChecksumIssue(t: Throwable): Boolean = causes(t).any {
            it is org.tukaani.xz.CorruptedInputException ||
                it is java.io.EOFException ||
                it.message?.contains("checksum", ignoreCase = true) == true ||
                it.message?.contains("corrupt", ignoreCase = true) == true
        }
    }
}
