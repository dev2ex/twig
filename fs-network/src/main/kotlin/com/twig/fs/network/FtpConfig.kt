package com.twig.fs.network

/** Configuration for an FTP connection. */
data class FtpConfig(
    val host: String,
    val port: Int = 21,
    val user: String = "anonymous",
    val password: String = "",
    /** Control connection encoding; older servers may use GBK. */
    val encoding: String = "UTF-8",
    /**
     * Optional directory the connection is rooted at ("pub/photos"); empty = the server
     * root. Everything above it is unreachable, and every path the UI sees is relative
     * to it, and one place inside [FtpFileSystem] translates back.
     */
    val path: String = "",
)
