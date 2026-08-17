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
 * 局域网设备发现:**探测 / 应答**,不是周期性广播。
 *
 * 共享端在 [PORT] 上收 UDP,收到探测包就单播回一份 JSON 名片;扫描端广播一发、
 * 收几百毫秒的回信就完事。比"每 2 秒广播一次心跳"省电得多,而且发现本来就是
 * 用户点了「扫描」才发生的一次性动作——没人在扫的时候安静地待着才是对的。
 *
 * 只有 Twig 认得这套私有协议;电脑端仍然是浏览器输 IP(或按 WebDAV 挂载),
 * 那条路不需要发现。
 */
object Discovery {

    const val PORT = 45654
    private const val PROBE = "TWIG-DISCOVER?"
    private const val MAGIC = "twig-share"

    /** 扫到的一台设备。 */
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
     * 共享端的应答器。与 HTTP 服务同生共死,由 [WebShare] 管理。
     */
    class Beacon(private val cfg: ShareConfig, private val scopeLabel: String) {

        @Volatile private var socket: DatagramSocket? = null
        @Volatile private var running = false

        fun start() {
            // 发现失败不该拖垮共享本身:端口被占就安静地不提供发现
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
     * 扫描:广播一次探测,收 [timeoutMs] 毫秒的回信。阻塞,须在工作线程调用。
     *
     * 往 255.255.255.255 **和**各网卡自己的子网广播地址各发一份:不少国产 ROM /
     * AP 隔离配置会丢掉受限广播 255.255.255.255,但子网广播(如 192.168.1.255)能过。
     */
    fun scan(ctx: Context, timeoutMs: Int = 2500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        val probe = PROBE.toByteArray(Charsets.UTF_8)
        // WiFi 组播锁:部分设备省电时会把非本机地址的广播包直接丢掉
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
            // 扫描失败(没网卡/权限被拒)返回已收到的部分即可
        } finally {
            runCatching { lock?.release() }
        }
        return found.values.toList()
    }

    /** 各网卡的子网广播地址。 */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}

/** 本机地址相关的小工具(共享页要显示"电脑上该输什么")。 */
object Net {

    /**
     * 本机可用的 IPv4 地址,WiFi 网卡排在最前面。
     *
     * 排序有意义:手机上常同时存在 wlan0(局域网)、rmnet(移动数据)、
     * 甚至 tun0(VPN),摆在第一位的必须是对面**真能连上**的那个。
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

    /** 有没有连着 WiFi(没有就提醒用户,免得对着移动数据的地址干瞪眼)。 */
    fun onWifi(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any {
            it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap")) &&
                it.inetAddresses.toList().any { a -> a is java.net.Inet4Address && !a.isLoopbackAddress }
        }
    }.getOrDefault(false)
}
