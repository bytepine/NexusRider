// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject

internal fun testDiagnostics() {
    // 与 Desktop / VSCode 使用同一摘要格式，完整 ID 与正文不得进入诊断字段。
    check(McpDiagnostics.session("abc") == "ba7816bf8f01")
    check(McpDiagnostics.session("") == "none")
    check(McpDiagnostics.text("line\nbreak") == "\"line\\nbreak\"")
    check(JSONObject("{\"value\":${McpDiagnostics.text("x".repeat(1000))}}").getString("value").length == 80)
    val request = JSONObject("""{"method":"tools/call","params":{"name":"CallCapability","arguments":{"Capability":"GetExample","Arguments":{"value":"private-argument-value"}}}}""")
    val fields = McpDiagnostics.fields(request)
    check(fields.contains("capability=\"GetExample\""))
    check(!fields.contains("private-argument-value"))
    val failure = McpDiagnostics.failure("""{"error":{"code":-32603,"message":"private-error","data":{"errorKind":"proxy_timeout"}}}""")
    check(failure == "rpc_code=-32603 error_kind=\"proxy_timeout\"")
    check(McpDiagnostics.failure("""{"result":{"isError":true,"content":[{"text":"private-response"}]}}""") == "isError=true")
    check(McpDiagnostics.failure("""{"result":{}}""") == null)
    check(McpDiagnostics.failure("") == null)
    println("diagnostics redaction contract OK")
}
