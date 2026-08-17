package com.twig.fs.network

/** 一个 FTP 连接的配置。 */
data class FtpConfig(
    val host: String,
    val port: Int = 21,
    val user: String = "anonymous",
    val password: String = "",
    /** 控制连接编码,老服务器可能用 GBK。 */
    val encoding: String = "UTF-8",
)
