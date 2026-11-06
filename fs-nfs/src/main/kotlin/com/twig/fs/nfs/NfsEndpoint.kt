package com.twig.fs.nfs

import java.net.URI

/** Address-field parsing only; never resolves a hostname or performs network I/O. */
data class NfsEndpoint(val host: String, val port: Int = DEFAULT_PORT) {
    fun authority(): String {
        val address = if (':' in host) "[$host]" else host
        return if (port == DEFAULT_PORT) address else "$address:$port"
    }

    companion object {
        const val DEFAULT_PORT = 2049

        fun parse(address: String, defaultPort: Int = DEFAULT_PORT): NfsEndpoint {
            val value = address.trim().removePrefix("nfs://")
            require(value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() || it in "/?#@" }) {
                "Invalid NFS server address"
            }
            val host: String
            val portText: String?
            if (value.startsWith('[')) {
                val end = value.indexOf(']')
                require(end > 1) { "Invalid bracketed IPv6 address" }
                host = value.substring(1, end)
                val tail = value.substring(end + 1)
                require(tail.isEmpty() || tail.startsWith(':')) { "Invalid NFS port" }
                portText = if (tail.isEmpty()) null else tail.substring(1)
                require(':' in host) { "Brackets are only valid for IPv6" }
            } else if (value.count { it == ':' } == 1) {
                host = value.substringBefore(':')
                portText = value.substringAfter(':')
            } else {
                host = value
                portText = null // Unbracketed IPv6 is an address, never host:port.
            }
            require(host.isNotEmpty() && '[' !in host && ']' !in host) { "Invalid NFS server address" }
            if (':' in host) {
                // URI validates IPv6 literals without calling a DNS resolver.
                require(runCatching { URI("nfs://[$host]").host != null }.getOrDefault(false)) {
                    "Invalid IPv6 address"
                }
            }
            val port = if (portText == null) defaultPort else {
                require(portText.isNotEmpty() && portText.all { it in '0'..'9' }) { "Invalid NFS port" }
                portText.toIntOrNull() ?: throw IllegalArgumentException("Invalid NFS port")
            }
            require(port in 1..65535) { "NFS port must be between 1 and 65535" }
            return NfsEndpoint(host, port)
        }
    }
}
