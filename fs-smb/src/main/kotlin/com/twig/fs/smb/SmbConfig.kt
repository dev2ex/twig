package com.twig.fs.smb

/** Connection configuration for an SMB server. */
data class SmbConfig(
    val host: String,
    /**
     * Where this connection is rooted, as one string:
     * - empty → **the whole server**, the root listing every disk share it publishes
     *   (enumerated over srvsvc on an IPC$ connection);
     * - `Public` → that share;
     * - `Public/Photos` → a directory inside it, and nothing above it is reachable.
     *
     * A share name and a start path are the same question asked once, so there is only
     * one field. ★ [SmbFileSystem] splits it and always hands **a bare share name** to
     * [NativeSmbClient.connect] — libsmb2 attaches a context to a share, it knows
     * nothing about a path.
     */
    val share: String = "",
    /** Empty username and password select anonymous access; explicit guest stays a guest login. */
    val user: String = "",
    val password: String = "",
    /** Optional authentication domain; an ordinary workgroup does not need one. */
    val domain: String = "",
)
