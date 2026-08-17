package com.twig.fs.smb

/** 一个 SMB 共享的连接配置。 */
data class SmbConfig(
    val host: String,
    val share: String,
    val user: String = "guest",
    val password: String = "",
    val domain: String = "WORKGROUP",
)
