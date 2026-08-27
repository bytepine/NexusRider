// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import java.net.NetworkInterface

object LanHost {
    const val LOOPBACK = "127.0.0.1"

    data class RemoteUnreal(
        val host: String,
        val mcpPort: Int,
        val authToken: String,
    )

    data class LanIPv4(
        val name: String,
        val address: String,
    )

    fun normalizeHost(host: String?): String {
        val h = host?.trim().orEmpty()
        if (h.isEmpty() || h.equals("localhost", ignoreCase = true) || h == "::1") {
            return LOOPBACK
        }
        return h
    }

    fun instanceKey(host: String?, port: Int): String = "${normalizeHost(host)}:$port"

    /** 每行 `host:mcpPort [token...]`；忽略本机 loopback。token 可省略（用 extraAuthTokens）。 */
    fun parseRemoteText(text: String): List<RemoteUnreal> {
        return text.lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val sp = t.indexOf(' ')
            val addr = if (sp <= 0) t else t.substring(0, sp).trim()
            val token = if (sp <= 0) "" else t.substring(sp + 1).trim()
            val colon = addr.lastIndexOf(':')
            if (colon <= 0) return@mapNotNull null
            val host = normalizeHost(addr.substring(0, colon))
            val port = addr.substring(colon + 1).toIntOrNull() ?: return@mapNotNull null
            if (host == LOOPBACK || port < 1024 || port > 65535) return@mapNotNull null
            RemoteUnreal(host, port, token)
        }.toList()
    }

    /** 已启用、非 loopback、非 169.254 的 IPv4（含网卡名）。 */
    fun listLanIPv4(): List<LanIPv4> {
        val nics = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        val out = mutableListOf<LanIPv4>()
        for (nic in nics) {
            if (!nic.isUp || nic.isLoopback) continue
            val name = nic.displayName ?: nic.name
            for (addr in nic.inetAddresses) {
                if (addr.isLoopbackAddress || addr.hostAddress.contains(':')) continue
                val ip = addr.hostAddress.substringBefore('%')
                if (ip.startsWith("169.254.")) continue
                out.add(LanIPv4(name, ip))
            }
        }
        return out
    }

    fun firstLanIPv4(): String? = listLanIPv4().firstOrNull()?.address

    /**
     * 复制 mcp.json 用的 host。
     * 未开 LAN → 127.0.0.1；仅一块局域网 IP → 直接用；多块 → choices 非空供 UI 选择。
     */
    fun copyHostChoices(listenLan: Boolean): Pair<String, List<LanIPv4>> {
        if (!listenLan) return LOOPBACK to emptyList()
        val lan = listLanIPv4()
        if (lan.isEmpty()) return LOOPBACK to emptyList()
        if (lan.size == 1) return lan[0].address to emptyList()
        return "" to (listOf(LanIPv4("本机", LOOPBACK)) + lan)
    }

    fun mcpDisplayHost(listenLan: Boolean): String {
        if (!listenLan) return LOOPBACK
        return firstLanIPv4() ?: LOOPBACK
    }
}
