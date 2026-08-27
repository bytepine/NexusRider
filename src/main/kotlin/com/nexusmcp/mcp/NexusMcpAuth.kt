// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

object NexusMcpAuth {
    const val MAX_BODY_BYTES = 1024 * 1024

    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidAuthToken(s: String): Boolean = s.matches(Regex("^[0-9a-fA-F]{32,128}$"))

    /** 本机共享 token 路径：{LocalAppData|Application Support|.config}/NexusLink/mcp-auth-token */
    fun machineAuthTokenFile(): File {
        val os = System.getProperty("os.name").orEmpty()
        val base = when {
            os.startsWith("Windows", ignoreCase = true) ->
                System.getenv("LOCALAPPDATA")
                    ?: File(System.getProperty("user.home"), "AppData${File.separator}Local").path
            os.contains("Mac", ignoreCase = true) ->
                File(System.getProperty("user.home"), "Library${File.separator}Application Support").path
            else ->
                System.getenv("XDG_CONFIG_HOME")
                    ?: File(System.getProperty("user.home"), ".config").path
        }
        return File(File(base, "NexusLink"), "mcp-auth-token")
    }

    /**
     * 同机 UE / Desktop / Rider / VSCode 共用一份 token。
     * 文件已有则读取；否则用 seed（旧版 settings.proxyToken）或新生成并写入。
     */
    fun loadOrCreateMachineToken(seed: String = ""): String {
        val file = machineAuthTokenFile()
        readValidToken(file)?.let { return it }
        if (file.exists()) file.delete()
        val trimmed = seed.trim()
        val token = if (isValidAuthToken(trimmed)) trimmed.lowercase() else generateToken()
        try {
            file.parentFile?.mkdirs()
            Files.write(
                file.toPath(),
                token.toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            return token
        } catch (_: Exception) {
            return readValidToken(file) ?: token
        }
    }

    private fun readValidToken(file: File): String? {
        if (!file.isFile) return null
        return try {
            val raw = file.readText(Charsets.UTF_8).trim()
            if (isValidAuthToken(raw)) raw.lowercase() else null
        } catch (_: Exception) {
            null
        }
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

    /** 逗号/分号/空白分隔，去重保序。 */
    fun parseAuthTokens(vararg chunks: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (chunk in chunks) {
            for (p in chunk.split(Regex("[,;\\s]+"))) {
                val t = p.trim()
                if (isValidAuthToken(t)) seen.add(t.lowercase())
            }
        }
        return seen.toList()
    }

    fun isTokenAccepted(presentedRaw: String, machine: String, extra: String = ""): Boolean {
        val presented = parseAuthTokens(presentedRaw)
        if (presented.isEmpty()) return false
        val accepted = parseAuthTokens(machine, extra)
        return presented.any { p -> accepted.any { a -> tokensEqual(p, a) } }
    }

    fun readMachineAuthToken(): String? = readValidToken(machineAuthTokenFile())

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
