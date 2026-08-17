package com.twig.app.ui

import android.content.Context
import com.twig.app.R
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 文本写回同一来源。原来是 [TextViewerActivity] 的私有实现,双栏 diff 的"合并这段
 * 到对侧"也要往回写,搬出来共用——这套路里的每个分支都是踩出来的,复制一份迟早走样。
 */

/**
 * 严格 UTF-8 解码,不是合法 UTF-8 就返回 null(不做宽容替换)。
 *
 * 读取端一律按 UTF-8 解,GBK 之类的文件本来就显示成乱码;宽容解码出来的 U+FFFD
 * 再写回去是**不可逆损坏**,所以凡是要写回的场景都得先过这关。
 */
internal fun strictUtf8(bytes: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

/**
 * 保存写回。默认路径是"写同目录临时文件 → 删原文件 → rename 顶上":
 * [com.twig.core.FileSystem.openOutput] 绝大多数实现是截断原文件再往里写,网络中途断线
 * 就只剩残片,原文件回不来了。来源自称 [com.twig.core.FileSystem.atomicOverwrite] 的
 * (zip:整包重写到 .twigtmp 再替换)直接写,免掉额外两次整包重写。
 */
internal fun Context.writeAtomically(file: XFile, bytes: ByteArray) {
    val fs = FsRegistry.of(file)
    if (fs.atomicOverwrite()) {
        fs.openOutput(file).use { it.write(bytes) }
        return
    }
    val parent = fs.resolve(file.parentPath)
    val tmp = fs.createFile(parent, "${file.name}.twigtmp")
    runCatching { if (fs.exists(tmp)) fs.delete(tmp) } // 清掉上次失败的残留

    // ★ "能改这个文件"和"能在这个目录里新建文件"是两个独立权限(SMB/NTFS 的
    // FILE_WRITE_DATA vs 目录的 FILE_ADD_FILE),实测有共享只给前者——建临时文件
    // 直接 STATUS_ACCESS_DENIED。用户明明有权限改这个文件,不该因为我们选了这种
    // 实现方式就存不上,于是降级成直接覆写原文件(没有原子保护,但存得上)。
    // 降级只认"打开失败"这一种情况:写到一半失败是网络/空间问题,降级照样会失败,
    // 而且会把原文件截断——那时原文件还完好,直接报错更安全。
    val out = runCatching { fs.openOutput(tmp) }.getOrNull()
    if (out == null) {
        runCatching { if (fs.exists(tmp)) fs.delete(tmp) }
        fs.openOutput(file).use { it.write(bytes) }
        return
    }
    try {
        out.use { it.write(bytes) }
    } catch (e: Throwable) {
        runCatching { if (fs.exists(tmp)) fs.delete(tmp) }
        throw e
    }

    // ★ 过了这行 tmp 就是内容的唯一完整副本,原文件即将被删——后面无论哪步失败都
    // 绝不能再删 tmp(早先版本在这里一并清理,等于把用户刚写的东西也抹掉),
    // 只把 tmp 的名字报出去让用户能捞回来。
    if (fs.exists(file)) fs.delete(file)
    try {
        fs.rename(tmp, file.name)
    } catch (e: Throwable) {
        throw FsException(
            getString(R.string.err_saved_but_rename_failed, tmp.name, file.name, e.message ?: ""),
            e,
        )
    }
}

/** 这个条目现在能不能写:整个来源只读的靠 `writable()` 兜底,条目自身的可写位要现查。 */
internal fun canWriteTo(file: XFile): Boolean {
    val fs = FsRegistry.of(file)
    // canWrite 默认 true 不可信(按 intent/路径拼出来的 XFile 没问过来源),所以 resolve 一次
    return fs.writable() && runCatching { fs.resolve(file.path).canWrite }.getOrDefault(true)
}
