package com.twig.fs.archive

/**
 * 归档要密码才能读:[wrong] 为 false 表示还没给过密码,为 true 表示给的那个不对。
 * UI 靠这两种情况分别弹「请输入密码」与「密码错误,请重试」。
 *
 * 消息是英文的 —— 纯 JVM 模块拿不到 Context/R(见 CLAUDE.md 的文案约定);
 * app 那边捕获这个类型后用 strings.xml 里的中文覆盖,不会把英文露给用户。
 */
class ArchivePasswordException(
    val archivePath: String,
    val wrong: Boolean = false,
) : RuntimeException(
    if (wrong) "Wrong password for archive: $archivePath" else "Password required for archive: $archivePath",
)
