// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

object NexusMcpAuth {
    const val MAX_BODY_BYTES = 1024 * 1024

    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun extractBearer(authorization: String?): String {
        if (authorization.isNullOrBlank()) return ""
        val prefix = "Bearer "
        if (!authorization.startsWith(prefix, ignoreCase = true)) return ""
        return authorization.substring(prefix.length).trim()
    }

    fun tokensEqual(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a.length != b.length) return false
        var acc = 0
        for (i in a.indices) acc = acc or (a[i].code xor b[i].code)
        return acc == 0
    }

    fun readUeAuthToken(mcpPort: Int): String? {
        val dir = File(System.getProperty("java.io.tmpdir"), "NexusLink")
        val files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("\\d+\\.json")) } ?: return null
        for (f in files) {
            try {
                val json = JSONObject(f.readText())
                if (json.optInt("mcpPort") == mcpPort) {
                    val token = json.optString("authToken", "")
                    if (token.isNotBlank()) return token
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
