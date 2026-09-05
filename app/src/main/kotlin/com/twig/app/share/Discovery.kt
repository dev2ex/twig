package com.twig.app.share

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * LAN device discovery: **probe / reply**, not periodic broadcast.
 *
 * The sharing end listens for UDP on [PORT] and unicasts a JSON business
 * card back to whoever sent a probe; the scanning end sends one broadcast
 * and collects replies for a few hundred milliseconds. That is far more
 * power-efficient than "broadcast a heartbeat every 2 seconds", and
 * discovery is a one-shot action triggered by the user pressing "Scan" —
 * the right thing to do when no one is scanning is to be quiet.
 *
 * Only Twig recognises this private protocol; computers still type the IP
 * into a browser (or mount via WebDAV), which is a path that does not need
 * discovery.
 */
object Discovery {

    const val PORT = 45654
    private const val PROBE = "TWIG-DISCOVER?"
    private const val MAGIC = "twig-share"

    /** One device that was found. */
    class Found(
        val host: String,
        val port: Int,
        val name: String,
        val readOnly: Boolean,
        val needsAuth: Boolean,
        val scope: String,
    ) {
        fun url(): String = "http://$host:$port"
    }

    /**
     * The sharing side's responder. Lives and dies with the HTTP service,
     * managed by [WebShare].
     */
    class Beacon(private val cfg: ShareConfig, private val scopeLabel: String) {

        @Volatile private var socket: DatagramSocket? = null
        @Volatile private var running = false

        fun start() {
            // Discovery failure must not bring sharing down with it: if the
            // port is taken, simply don't offer discovery
            val s = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                    soTimeout = 1000
                }
            }.getOrNull() ?: return
            socket = s
            running = true
            Thread({ loop(s) }, "twig-discovery").apply { isDaemon = true }.start()
        }

        fun stop() {
            running = false
            runCatching { socket?.close() }
            socket = null
        }

        private fun loop(s: DatagramSocket) {
            val buf = ByteArray(512)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (running) continue else break
                }
                val msg = String(p.data, p.offset, p.length, Charsets.UTF_8).trim()
                if (!msg.startsWith(PROBE)) continue
                val reply = card().toByteArray(Charsets.UTF_8)
                runCatching { s.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
            }
        }

        private fun card(): String = JSONObject().apply {
            put("magic", MAGIC)
            put("name", cfg.nameOrModel())
            put("port", cfg.port)
            put("ro", cfg.readOnly)
            put("auth", cfg.needsAuth)
            put("scope", scopeLabel)
        }.toString()
    }

    /**
     * Scan: broadcast one probe, collect replies for [timeoutMs] milliseconds.
     * Blocks, must be called on a worker thread.
     *
     * Sends to **both** 255.255.255.255 and each interface's own subnet
     * broadcast address: quite a few Chinese ROMs / AP-isolation setups drop
     * the limited broadcast 255.255.255.255, but the subnet broadcast (e.g.
     * 192.168.1.255) goes through.
     */
    fun scan(ctx: Context, timeoutMs: Int = 2500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        val probe = PROBE.toByteArray(Charsets.UTF_8)
        // WiFi multicast lock: some devices in power-save drop broadcast
        // packets not addressed to themselves
        val lock = runCatching {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("twig-scan").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 300
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                for (addr in targets) {
                    runCatching { s.send(DatagramPacket(probe, probe.size, addr, PORT)) }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        break
                    }
                    val body = String(p.data, p.offset, p.length, Charsets.UTF_8)
                    val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
                    if (o.optString("magic") != MAGIC) continue
                    val host = p.address?.hostAddress ?: continue
                    found[host] = Found(
                        host = host,
                        port = o.optInt("port", ShareConfig.DEFAULT_PORT),
                        name = o.optString("name", host),
                        readOnly = o.optBoolean("ro", true),
                        needsAuth = o.optBoolean("auth", false),
                        scope = o.optString("scope"),
                    )
                }
            }
        } catch (e: Exception) {
            // Scan failure (no network / permission denied) — return whatever has been collected so far
        } finally {
            runCatching { lock?.release() }
        }
        return found.values.toList()
    }

    /** Each interface's subnet broadcast address. */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}

/** Small utilities for local addresses (the share dialog has to display "what to type on the computer"). */
object Net {

    /**
     * The device's usable IPv4 addresses, with the WiFi interface first.
     *
     * The order matters: a phone often has wlan0 (LAN), rmnet (mobile data)
     * and even tun0 (VPN) at the same time, and the one put first has to be
     * the one the other end can actually connect to.
     */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it } }
            .filter { (_, a) -> !a.isLoopbackAddress && a is java.net.Inet4Address }
            .sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") || name.startsWith("ap") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("tun") || name.startsWith("ppp") -> 3
                    else -> 2
                }
            }
            .mapNotNull { (_, a) -> a.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    /** Whether WiFi is connected (so the dialog can warn the user before they stare at a mobile-data address). */
    fun onWifi(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any {
            it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap")) &&
                it.inetAddresses.toList().any { a -> a is java.net.Inet4Address && !a.isLoopbackAddress }
        }
    }.getOrDefault(false)
}
