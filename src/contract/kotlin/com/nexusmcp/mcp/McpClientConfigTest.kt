// Copyright byteyang. All Rights Reserved.
package com.nexusmcp.mcp

import org.json.JSONObject

internal fun testClientConfig() {
    val token = "0".repeat(64)
    check(McpClient.entries.size == 6)
    for (client in McpClient.entries) {
        check(client.instructions.isNotBlank())
        for (protocol in listOf("stream", "sse")) for (requireAuth in listOf(false, true)) {
            val text = buildMcpClientConfig(protocol, client, 6801, token, "192.0.2.10", requireAuth)
            if (client == McpClient.CODEX) {
                check(text.startsWith("[mcp_servers.nexus-unreal]\nurl = \"http://192.0.2.10:6801/stream\"\n"))
                check(text.contains("http_headers = { Authorization = \"Bearer $token\" }") == requireAuth)
                continue
            }
            val root = JSONObject("{$text}")
            val entry = root.getJSONObject(if (client == McpClient.CODEBUDDY) "Nexus" else "nexus-unreal")
            check(entry.getString("url") == "http://192.0.2.10:6801/$protocol")
            val streamType = when (client) {
                McpClient.HTTP -> "http"
                McpClient.CLINE -> "streamableHttp"
                McpClient.ROO -> "streamable-http"
                else -> ""
            }
            check(entry.optString("type") == if (streamType.isNotEmpty() && protocol == "sse") "sse" else streamType)
            check(entry.optString("transportType") == if (client == McpClient.CODEBUDDY && protocol == "stream") "streamable-http" else "")
            check(entry.has("headers") == requireAuth)
            if (requireAuth) check(entry.getJSONObject("headers").getString("Authorization") == "Bearer $token")
        }
    }
    check(!buildMcpClientConfig("stream", McpClient.CODEX, 6801, "", "127.0.0.1", true).contains("http_headers"))
    println("MCP client configuration checks passed")
}
