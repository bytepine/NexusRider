// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject
import java.security.MessageDigest

/** 只处理诊断字段，不记录请求参数、错误正文或工具结果。 */
object McpDiagnostics {
    fun text(value: Any?): String = JSONObject.quote((value as? String).orEmpty().take(80))

    fun session(id: String): String = if (id.isEmpty()) "none" else
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fields(msg: JSONObject?): String {
        val params = msg?.optJSONObject("params")
        val args = params?.optJSONObject("arguments")
        val legacy = params?.optString("name") == "call_capability"
        val cap = args?.opt(if (legacy) "capability" else "Capability")
            ?: args?.opt(if (legacy) "Capability" else "capability")
        return "method=${text(msg?.opt("method"))} tool=${text(params?.opt("name"))} capability=${text(cap)}"
    }

    fun failure(response: String): String? {
        if (response.isBlank()) return null
        val msg = try { JSONObject(response) } catch (_: Exception) { return "reason=invalid_response" }
        msg.optJSONObject("error")?.let {
            return "rpc_code=${it.optInt("code")} error_kind=${text(it.optJSONObject("data")?.opt("errorKind"))}"
        }
        return if (msg.optJSONObject("result")?.optBoolean("isError") == true) "isError=true" else null
    }
}
