// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONArray
import org.json.JSONObject

/** IDE 代理连接工具定义（由 UE nexus/proxy_config 下发）。 */
data class ProxyConnectionTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
)

/** UE 驱动的代理层配置。未连接时使用 DEFAULT_PROXY_CONFIG。 */
data class ProxyConfig(
    val protocolVersion: String,
    val minProxyVersion: String,
    val nexusLinkVersion: String = "",
    val initializePrefix: String,
    val localToolNames: List<String>,
    val connectionTools: List<ProxyConnectionTool>,
    val errorMessages: ProxyErrorMessages,
)

data class ProxyErrorMessages(
    val notConnected: String,
    val timeoutHint: String,
)

object ProxyConfigDefaults {

    private val FALLBACK_CONNECT_SCHEMA = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().put("port", JSONObject().put("type", "integer")))
        put("required", JSONArray().put("port"))
    }

    val DEFAULT: ProxyConfig = ProxyConfig(
        protocolVersion = "2025-06-18",
        minProxyVersion = "1.3.3",
        initializePrefix = listOf(
            "NexusLink MCP Proxy for Unreal Engine.",
            "MUST USE MCP when user mentions UE/Unreal/蓝图/Blueprint/资产/Widget/UMG/材质/PIE/Actor/GAS etc.",
            "Do NOT guess /Game/ paths or answer from repo grep alone.",
            "If tools/list has UE tools → call directly; if only list/connect → list → connect → search_capabilities.",
        ).joinToString(" "),
        localToolNames = listOf("list_unreal_instances", "connect_unreal_instance"),
        connectionTools = listOf(
            ProxyConnectionTool(
                name = "list_unreal_instances",
                description = "Discover running UE instances with NexusLink loaded.",
                inputSchema = JSONObject().put("type", "object"),
            ),
            ProxyConnectionTool(
                name = "connect_unreal_instance",
                description = "Connect to a UE instance by port from list_unreal_instances.",
                inputSchema = FALLBACK_CONNECT_SCHEMA,
            ),
        ),
        errorMessages = ProxyErrorMessages(
            notConnected = "No connected UE instance. Call connect_unreal_instance first.",
            timeoutHint = "Retry or narrow the query; heavy tools may need a moment.",
        ),
    )

    fun parse(raw: JSONObject?): ProxyConfig {
        if (raw == null) return DEFAULT

        val localNames = mutableListOf<String>()
        raw.optJSONArray("localToolNames")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "")
                if (name.isNotEmpty()) localNames.add(name)
            }
        }
        if (localNames.isEmpty()) localNames.addAll(DEFAULT.localToolNames)

        val tools = mutableListOf<ProxyConnectionTool>()
        raw.optJSONArray("connectionTools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name", "")
                if (name.isEmpty()) continue
                tools.add(
                    ProxyConnectionTool(
                        name = name,
                        description = item.optString("description", ""),
                        inputSchema = item.optJSONObject("inputSchema")
                            ?: JSONObject().put("type", "object"),
                    )
                )
            }
        }
        if (tools.isEmpty()) tools.addAll(DEFAULT.connectionTools)

        val err = raw.optJSONObject("errorMessages")
        return ProxyConfig(
            protocolVersion = raw.optString("protocolVersion", DEFAULT.protocolVersion),
            minProxyVersion = raw.optString("minProxyVersion", DEFAULT.minProxyVersion),
            nexusLinkVersion = raw.optString("nexusLinkVersion", ""),
            initializePrefix = raw.optString("initializePrefix", DEFAULT.initializePrefix),
            localToolNames = localNames,
            connectionTools = tools,
            errorMessages = ProxyErrorMessages(
                notConnected = err?.optString("notConnected", DEFAULT.errorMessages.notConnected)
                    ?: DEFAULT.errorMessages.notConnected,
                timeoutHint = err?.optString("timeoutHint", DEFAULT.errorMessages.timeoutHint)
                    ?: DEFAULT.errorMessages.timeoutHint,
            ),
        )
    }
}
