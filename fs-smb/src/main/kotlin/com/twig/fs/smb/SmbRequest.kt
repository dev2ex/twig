package com.twig.fs.smb

import com.twig.core.FsException

/** Called on the SMB IO thread so errors are captured before any other native call. */
internal fun <T> smbRequest(
    operation: String,
    failed: (T) -> Boolean,
    error: () -> String,
    denied: () -> Boolean,
    reconnect: () -> Boolean,
    request: () -> T,
): T {
    val first = request()
    if (!failed(first)) return first
    val original = error().takeIf { it.isNotBlank() } ?: "$operation failed (result $first)"
    if (denied() || !reconnect()) throw FsException(original)
    val retry = request()
    if (!failed(retry)) return retry
    throw FsException(error().takeIf { it.isNotBlank() }
        ?: "$operation failed (result $retry); $original")
}
