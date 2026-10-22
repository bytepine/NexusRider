// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

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

internal fun testStartupLogs(directory: Path) {
    val first = StartupLog(directory, "nexus-rider")
    first.write("INFO", "first startup")
    val firstFile = first.file
    val before = Files.readString(firstFile)
    val second = StartupLog(directory, "nexus-rider")
    second.write("INFO", "second startup")
    check(firstFile != second.file && Files.readString(firstFile) == before)
    check(!Files.readString(second.file).contains("first startup"))
    first.write("INFO", "x".repeat(4 * 1024 * 1024))
    first.write("INFO", "after rotation")
    check(first.file != firstFile && Files.exists(firstFile))
    check(Files.readString(first.file).contains("after rotation"))
    nexusLogger<StartupLog>().info("startup mirror marker")
    check(Files.list(NexusLog.directory()).use { files ->
        files.anyMatch { Files.readString(it).contains("startup mirror marker") }
    }) { "plugin logs must also reach the startup file" }
    println("startup file isolation and rotation OK")
}
