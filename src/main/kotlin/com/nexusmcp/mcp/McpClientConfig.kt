// Copyright byteyang. All Rights Reserved.
package com.nexusmcp.mcp

import org.json.JSONObject

internal enum class McpClient(val label: String, val instructions: String) {
    CURSOR("Cursor / Windsurf", "合并到 mcpServers。Cursor：~/.cursor/mcp.json；Windsurf（Cascade）：~/.codeium/windsurf/mcp_config.json。"),
    CODEX("Codex", "将 TOML 合并到 ~/.codex/config.toml（或自定义 CODEX_HOME）；已有同名段时替换。使用 Streamable HTTP。"),
    HTTP("Claude Code / VS Code", "Claude Code：项目 .mcp.json 的 mcpServers；VS Code：项目 .vscode/mcp.json 的 servers。"),
    CODEBUDDY("CodeBuddy", "将配置条目合并到客户端自定义 MCP 配置的 mcpServers。"),
    CLINE("Cline", "在 Cline 的 MCP Servers → Configure → Configure MCP Servers 中，将条目合并到 mcpServers。"),
    ROO("Roo Code", "将条目合并到项目 .roo/mcp.json 的 mcpServers，或在 Roo Code 的 MCP 设置中编辑全局配置。");
    override fun toString() = label
}

/** 只生成可粘贴的配置；不把操作说明混进 JSON / TOML。 */
internal fun buildMcpClientConfig(protocol: String, client: McpClient, port: Int,
    token: String, host: String, requireAuth: Boolean): String {
    val path = if (client == McpClient.CODEX || protocol == "stream") "stream" else "sse"
    val url = "http://$host:$port/$path"
    val authorization = if (requireAuth && token.isNotBlank()) "Bearer $token" else null
    if (client == McpClient.CODEX) {
        return "[mcp_servers.nexus-unreal]\nurl = ${JSONObject.quote(url)}\n" +
            (authorization?.let { "http_headers = { Authorization = ${JSONObject.quote(it)} }\n" } ?: "")
    }
    val entry = JSONObject().put("url", url)
    if (client == McpClient.CODEBUDDY && protocol == "stream") entry.put("transportType", "streamable-http")
    val streamType = when (client) {
        McpClient.HTTP -> "http"
        McpClient.CLINE -> "streamableHttp"
        McpClient.ROO -> "streamable-http"
        else -> null
    }
    if (streamType != null) entry.put("type", if (protocol == "sse") "sse" else streamType)
    if (authorization != null) entry.put("headers", JSONObject().put("Authorization", authorization))
    val name = if (client == McpClient.CODEBUDDY) "Nexus" else "nexus-unreal"
    return "${JSONObject.quote(name)}: ${entry.toString(2)}"
}
