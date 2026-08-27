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

    fun firstLanIPv4(): String? {
        val nics = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in nics) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr.isLoopbackAddress || addr.hostAddress.contains(':')) continue
                return addr.hostAddress
            }
        }
        return null
    }

    fun mcpDisplayHost(listenLan: Boolean): String {
        if (!listenLan) return LOOPBACK
        return firstLanIPv4() ?: LOOPBACK
    }
}
