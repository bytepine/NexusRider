// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONArray
import org.json.JSONObject
import com.sun.net.httpserver.HttpServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 独立契约源集：三端 PascalCase、host/port 隔离、section/degraded 与按实例失效。 */
fun main() {
    testClientConfig()
    val testRoot = Files.createTempDirectory("nexus-policy-test-").toFile()
    System.setProperty("java.io.tmpdir", testRoot.absolutePath)
    System.setProperty("idea.log.path", testRoot.resolve("logs").absolutePath)
    Runtime.getRuntime().addShutdownHook(Thread { testRoot.deleteRecursively() })
    testDiagnostics()
    testStartupLogs(testRoot.resolve("startup-logs").toPath())
    testHttpSessions()
    testLegacyV2Compatibility()
    testEditorOperationWire()
    fun query(port: Int, host: String = "", section: String = "All") = JSONObject()
        .put("Capability", "GetAssetBlueprint")
        .put("Arguments", JSONObject().put("AssetPath", "/synthetic/BP").put("Sections", JSONArray(listOf(section))))
        .put("targetPort", port).put("targetHost", host)
    val a = ProxySessionPolicy.parseCall("CallCapability", query(45000))
    val b = ProxySessionPolicy.parseCall("CallCapability", query(45001))
    val remote = ProxySessionPolicy.parseCall("CallCapability", query(45000, "192.0.2.1"))
    check(!a.isWrite && a.identity == "/synthetic/BP") { "canonical read misclassified" }
    check(a.exactKey == ProxySessionPolicy.parseCall("CallCapability", query(45000, "localhost")).exactKey)
    val defaultA = ProxySessionPolicy.parseCall("CallCapability", JSONObject().put("Capability", "GetAssetBlueprint").put("Arguments", a.innerArgs), "127.0.0.1:45000")
    check(defaultA.exactKey == a.exactKey) { "default and explicit routes must share scope" }
    val payload = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")
        .put("text", """{"TtlSeconds":1,"SnapshotAt":"2026-10-22T00:00:00Z"}""")))
    val hub = ProxySessionHub()
    hub.store(a, payload, 1000)
    check(hub.lookupFresh(a, 1100) != null)
    check(hub.lookupFresh(b, 1100) == null && hub.lookupFresh(remote, 1100) == null) { "exact cache crossed instances" }
    check(hub.lookupFresh(ProxySessionPolicy.parseCall("CallCapability", query(45001, section = "Defaults")), 1100) == null) { "section cache crossed instances" }
    check(hub.lookupDegraded(b, 100000) == null) { "degraded snapshot crossed instances" }
    check(hub.lookupDegraded(a, 100000) != null) { "same-instance durable snapshot lost" }
    check(hub.lookupFresh(a, 31001) == null)
    val ttl = ProxySessionPolicy.extractTtlMeta(payload, a.capability, 1000)
    check(ttl.ttlMs == 1000L && ttl.snapshotAtIso == "2026-10-22T00:00:00Z")
    check(ProxySessionPolicy.defaultTtlMs("GetOutputLog") == 8000L)
    check(!ProxySessionPolicy.isDurableRead("GetRuntimeActorProperty"))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, "DeleteAsset", JSONObject()))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, "ControlPie", JSONObject().put("Action", "Stop")))
    val operations = JSONObject().put("Operations", JSONArray().put(JSONObject().put("Action", "RemoveNode")))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, "ManageAssetBlueprint", operations))
    hub.store(b, payload, 1000)
    val write = ProxySessionPolicy.parseCall("ManageAssetBlueprint", JSONObject().put("AssetPath", "/synthetic/BP").put("targetPort", 45000))
    hub.store(write, JSONObject(), 1200)
    check(hub.lookupFresh(a, 1300) == null && hub.lookupFresh(b, 1300) != null) { "writes must invalidate only the same instance" }
    val reordered = JSONObject().put("Arguments", JSONObject().put("Sections", JSONArray(listOf("All"))).put("AssetPath", "/synthetic/BP"))
        .put("Capability", "GetAssetBlueprint").put("targetPort", 45000)
    check(ProxySessionPolicy.parseCall("CallCapability", reordered).exactKey == a.exactKey)
    for (port in listOf(45000.5, 65536, "45000")) {
        check(ProxySessionPolicy.explicitTargetPort(JSONObject().put("targetPort", port)) == null)
    }
    check(ProxySessionPolicy.parseCall("CallCapability", JSONObject().put("Calls", JSONArray())).isBatch)
    val failure = JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "temporary failure")))
    for (result in listOf(payload, failure)) {
        hub.store(a, payload, 1000)
        hub.store(b, payload, 1000)
        val batch = ProxySessionPolicy.parseCall("CallCapability", JSONObject().put("targetPort", 45000)
            .put("Calls", JSONArray().put(JSONObject().put("Capability", "ManageAssetBlueprint").put("Arguments", JSONObject().put("AssetPath", "/synthetic/BP")))))
        hub.store(batch, result, 1100)
        check(hub.lookupFresh(a, 1200) == null && hub.lookupDegraded(a, 100000) == null && hub.lookupFresh(b, 1200) != null) {
            "batch must invalidate only its instance, including partial failure"
        }
    }
    hub.store(a, failure, 1000)
    check(hub.lookupFresh(a, 1100) == null && hub.lookupDegraded(a, 100000) == null) { "failed reads must not be cached" }
    val large = JSONObject().put("isError", false).put("content", JSONArray().put(JSONObject().put("type", "text")
        .put("text", JSONObject().put("payload", "x".repeat(50000)).put("TtlSeconds", 1).toString())))
    val offloadCall = ProxySessionPolicy.parseCall("GetAssetBlueprint", JSONObject().put("AssetPath", "/synthetic/BP").put("targetPort", 45000))
    fun offloadPath(value: JSONObject) = java.io.File(JSONObject(value.getJSONArray("content").getJSONObject(0).getString("text")).getString("path"))
    val file = offloadPath(hub.store(offloadCall, large, 1000))
    val stale = offloadPath(hub.store(offloadCall, large, 1000))
    check(file != stale) { "payload filenames must not collide" }
    check(stale.setLastModified(System.currentTimeMillis() - 7200000))
    val otherHub = ProxySessionHub()
    check(file.isFile && !stale.exists()) { "new hub must keep fresh payloads and prune expired nested files" }
    val otherFile = offloadPath(otherHub.store(offloadCall, large, 1000))
    check(file.parentFile != otherFile.parentFile && file.readText() == large.toString()) { "offload directories must be isolated" }
    val legacyRoot = testRoot.resolve("nexus-mcp-offload")
    check(legacyRoot.mkdirs())
    legacyRoot.resolve("old.json").writeText("old")
    legacyRoot.listFiles()?.forEach { it.deleteRecursively() }
    check(!file.toPath().startsWith(legacyRoot.toPath()) && file.isFile) { "old 2.x startup cleanup must not touch new payloads" }
    check(hub.lookupFresh(offloadCall, 2001) == null) { "offloading must preserve TTL metadata" }
    val largeFailure = JSONObject(large.toString()).put("isError", true)
    check(otherHub.store(b, largeFailure, 1000).toString() == largeFailure.toString()) { "large errors must not be replaced with successful file summaries" }
    check(otherHub.lookupFresh(b, 1100) == null)
    testDispatcherRouting()
    testToolListReconnect()
    println("proxy policy contract OK")
}

/** 已发布 2.x 的名称、业务参数和元数据只在策略层解析，不改写请求。 */
private fun testLegacyV2Compatibility() {
    val args = JSONObject().put("capability", "get_asset_blueprint")
        .put("arguments", JSONObject().put("assetPath", "/synthetic/BP").put("sections", JSONArray(listOf("all")))).put("targetPort", 45000)
    val before = args.toString()
    val read = ProxySessionPolicy.parseCall("call_capability", args)
    check(!read.isWrite && read.identity == "/synthetic/BP")
    check(!ProxySessionPolicy.needsGate(WriteGateMode.ALL, read.capability, read.innerArgs))
    check(!ProxySessionPolicy.isWriteCapability("get_asset_blueprint"))
    check(ProxySessionPolicy.defaultTtlMs("get_output_log") == 8000L && ProxySessionPolicy.defaultTtlMs("search_asset") == 60000L)
    check(ProxySessionPolicy.isDurableRead("get_asset_blueprint") && !ProxySessionPolicy.isDurableRead("list_runtime_actors"))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, "delete_asset", JSONObject()))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, "rename_asset", JSONObject()))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, "control_pie", JSONObject().put("action", "stop").put("Action", "start")))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, "manage_asset_blueprint", JSONObject().put("operations", JSONArray().put(JSONObject().put("action", "remove_node")))))
    val conflicting = ProxySessionPolicy.parseCall("call_capability", JSONObject().put("capability", "delete_asset").put("Capability", "GetAssetBlueprint"))
    check(ProxySessionPolicy.needsGate(WriteGateMode.DESTRUCTIVE, conflicting.capability, conflicting.innerArgs))
    val payload = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", """{"_ttl_seconds":1,"_snapshotAt":"2026-10-22T00:00:00Z"}""")))
    val hub = ProxySessionHub()
    hub.store(read, payload, 1000)
    val ttl = ProxySessionPolicy.extractTtlMeta(payload, read.capability, 1000)
    check(ttl.ttlMs == 1000L && ttl.snapshotAtIso == "2026-10-22T00:00:00Z")
    val subset = ProxySessionPolicy.parseCall("call_capability", JSONObject(args.toString()).put("arguments", JSONObject().put("assetPath", "/synthetic/BP").put("sections", JSONArray(listOf("defaults")))))
    check(hub.lookupFresh(subset, 1100)?.kind == CacheKind.SECTION_HIT)
    check(hub.lookupFresh(read, 32000) == null && hub.lookupDegraded(read, 100000) != null)
    val modern = ProxySessionPolicy.parseCall("CallCapability", JSONObject().put("Capability", "GetAssetBlueprint").put("Arguments", JSONObject().put("AssetPath", "/synthetic/BP")).put("targetPort", 45000))
    check(hub.lookupFresh(modern, 1100) == null) { "different wire formats must not share cached response shapes" }
    val batch = ProxySessionPolicy.parseCall("call_capability", JSONObject().put("targetPort", 45000).put("calls", JSONArray().put(JSONObject().put("capability", "manage_asset_blueprint").put("arguments", JSONObject().put("assetPath", "/synthetic/BP")))))
    hub.store(batch, JSONObject(), 1200)
    check(batch.isBatch && hub.lookupFresh(read, 1300) == null)
    check(args.toString() == before) { "policy parsing must not mutate wire arguments" }
}

/** 本地 HTTP/WS 协议替身，验证真实 Manager 与 Dispatcher，不启动 UE。 */
private class RoutingInstance(name: String, private val toolNames: List<String> = emptyList()) : AutoCloseable {
    val calls = AtomicInteger()
    private val ready = CountDownLatch(1)
    private val ws = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        override fun onStart() { ready.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onError(conn: WebSocket?, error: Exception) { error.printStackTrace() }
        override fun onMessage(conn: WebSocket, text: String) {
            val request = JSONObject(text)
            val result = JSONObject()
            if (request.optString("method") == "auth") result.put("ok", request.getJSONObject("params").optString("token") == "contract-test-token")
            if (request.optString("method") == "tools/list" && toolNames.isNotEmpty()) {
                result.put("tools", JSONArray(toolNames.map { JSONObject().put("name", it).put("inputSchema", JSONObject().put("type", "object")) }))
            }
            if (request.optString("method") == "tools/call") {
                if (toolNames.isNotEmpty() && request.getJSONObject("params").optString("name") !in toolNames) {
                    conn.send(JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id"))
                        .put("error", JSONObject().put("code", -32601).put("message", "Tool not found")).toString())
                    return
                }
                calls.incrementAndGet()
                val args = request.getJSONObject("params").optJSONObject("arguments")
                if (args?.optBoolean("ContractTimeout") == true) return
                result.put("content", JSONArray().put(JSONObject().put("type", "text")
                    .put("text", JSONObject().put("source", name).toString())))
                if (args?.optBoolean("ContractFailure") == true) result.put("isError", true).put("structuredContent",
                    JSONObject().put("ErrorKind", "ArgInvalid").put("bRolledBack", false).put("Error", "contract failure"))
            }
            conn.send(JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id")).put("result", result).toString())
        }
    }
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = http.address.port
    init {
        ws.start()
        check(ready.await(5, TimeUnit.SECONDS)) { "WebSocket server did not start" }
        http.createContext("/status") { exchange ->
            val body = JSONObject().put("server", "NexusLink").put("wsPort", ws.port).toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
    }
    override fun close() { http.stop(0); ws.stop(1000) }
}

private fun testDispatcherRouting() {
    RoutingInstance("A").use { a -> RoutingInstance("B").use { b ->
        val manager = UnrealInstanceManager()
        try {
            manager.scanPortStart = a.port
            manager.scanPortEnd = a.port
            check(manager.connectTo(a.port, true))
            val dispatcher = NexusMcpDispatcher(manager)
            dispatcher.dispatch(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize").toString())
            dispatcher.dispatch(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString())
            fun call(capability: String, port: Int? = null): String {
                val args = JSONObject().put("Capability", capability).put("Arguments", JSONObject().put("AssetPath", "/synthetic/BP"))
                if (port != null) args.put("targetPort", port)
                val request = JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                    .put("params", JSONObject().put("name", "CallCapability").put("arguments", args))
                val response = JSONObject(dispatcher.dispatch(request.toString()))
                check(!response.has("error")) { response.toString() }
                return JSONObject(response.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")).getString("source")
            }
            check(call("GetAssetBlueprint") == "A" && call("GetAssetBlueprint", b.port) == "B")
            check(manager.connectTo(b.port, true))
            check(call("GetAssetBlueprint") == "B" && b.calls.get() == 1) { "switch did not isolate default cache" }
            val prompts = AtomicInteger()
            manager.sessionHub.gatePrompter = { prompts.incrementAndGet(); GateDecision.ALLOW }
            for (port in listOf(-1, 0, 1023, 65536, 45000.5, "45000", JSONObject.NULL, true)) {
                for (capability in listOf("GetAssetBlueprint", "ManageAssetBlueprint", "DeleteAsset")) {
                    val args = JSONObject().put("Capability", capability).put("Arguments", JSONObject().put("AssetPath", "/synthetic/BP")).put("targetPort", port)
                    val request = JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                        .put("params", JSONObject().put("name", "CallCapability").put("arguments", args))
                    val response = JSONObject(dispatcher.dispatch(request.toString()))
                    check(response.optJSONObject("error")?.optInt("code") == -32602) { "invalid explicit target must be rejected: $response" }
                }
            }
            check(a.calls.get() == 1 && b.calls.get() == 1 && prompts.get() == 0) { "invalid targets must not forward, serve cached reads, or prompt" }
            call("ManageAssetBlueprint")
            check(call("GetAssetBlueprint", a.port) == "A" && a.calls.get() == 1) { "write B invalidated A" }
            check(call("GetAssetBlueprint") == "B" && b.calls.get() == 3) { "write B did not invalidate B" }
            manager.disconnect()
            check(manager.cacheScope == LanHost.instanceKey("", b.port))
        } finally { manager.disconnect() }
    } }
}

private fun testToolListReconnect() {
    val legacyNames = listOf("search_capabilities", "call_capability", "submit_feedback")
    val modernNames = listOf("SearchCapabilities", "CallCapability", "SubmitFeedback")
    RoutingInstance("legacy", legacyNames).use { legacy -> RoutingInstance("modern", modernNames).use { modern ->
        val manager = UnrealInstanceManager()
        manager.scanPortStart = modern.port
        manager.scanPortEnd = modern.port
        try {
            check(manager.connectTo(legacy.port, true))
            val dispatcher = NexusMcpDispatcher(manager)
            dispatcher.dispatch(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize").toString())
            dispatcher.dispatch(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString())
            fun list(): List<String> {
                val response = JSONObject(dispatcher.dispatch("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
                val tools = response.getJSONObject("result").getJSONArray("tools")
                return (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
            }
            check(list().containsAll(legacyNames))
            check(manager.connectTo(modern.port, true))
            val updated = list()
            check(updated.containsAll(modernNames) && legacyNames.none { it in updated }) { "stale tool names after reconnect: $updated" }
            for (name in modernNames) {
                val params = JSONObject().put("name", name).put("arguments", JSONObject().put("Query", "")
                    .put("Capability", "GetEditorInfo").put("Arguments", JSONObject()))
                val request = JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call").put("params", params)
                val response = JSONObject(dispatcher.dispatch(request.toString()))
                check(!response.has("error")) { response.toString() }
            }
            check(modern.calls.get() == 3) { "new tools must actually reach the new UE" }
            // 通过真实串行请求路径，以短超时验证写请求不重试。
            val send = UnrealInstanceManager::class.java.getDeclaredMethod("sendWsRequest", String::class.java, JSONObject::class.java, Long::class.javaPrimitiveType)
            send.isAccessible = true
            val auth = send.invoke(manager, "auth", JSONObject().put("token", "contract-test-token"), 1000L) as WsRequestOutcome
            check(auth.response?.getJSONObject("result")?.getBoolean("ok") == true)
            val failRequest = JSONObject().put("jsonrpc", "2.0").put("id", 4).put("method", "tools/call")
                .put("params", JSONObject().put("name", "CallCapability").put("arguments", JSONObject()
                    .put("Capability", "ManageAssetDataTable").put("Arguments", JSONObject()).put("ContractFailure", true)))
            val failed = JSONObject(dispatcher.dispatch(failRequest.toString())).getJSONObject("result")
            check(failed.getBoolean("isError") && failed.getJSONObject("structuredContent").getString("ErrorKind") == "ArgInvalid")
            val timeout = send.invoke(manager, "tools/call", JSONObject().put("name", "CallCapability").put("arguments",
                JSONObject().put("Capability", "ManageAssetDataTable").put("ContractTimeout", true)), 50L) as WsRequestOutcome
            check(timeout.status == WsRequestStatus.TIMEOUT && modern.calls.get() == 5) { "failed/timed-out writes must be sent once" }
        } finally { manager.disconnect() }
    } }
}


private fun testEditorOperationWire() {
    val ready = CountDownLatch(1)
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val count = AtomicInteger()
    val ws = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        override fun onStart() { ready.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onError(conn: WebSocket?, ex: Exception) { ready.countDown() }
        override fun onMessage(conn: WebSocket, message: String) {
            val request = JSONObject(message)
            when (request.optString("method")) {
                "tools/call" -> {
                    conn.send(JSONObject().put("jsonrpc","2.0").put("id",-100).put("result",JSONObject()).toString())
                    conn.send(JSONObject().put("jsonrpc","2.0").put("method","notifications/progress")
                        .put("params",JSONObject().put("progressToken","token").put("progress",1)).toString())
                }
                "notifications/cancelled" -> conn.send(JSONObject().put("jsonrpc","2.0")
                    .put("id",request.getJSONObject("params").get("requestId"))
                    .put("result",JSONObject().put("cancelled",true)).toString())
            }
        }
    }
    ws.start(); check(ready.await(3,TimeUnit.SECONDS))
    val http = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    http.createContext("/status") { exchange ->
        val body = JSONObject().put("server","NexusLink").put("wsPort",ws.port).toString().toByteArray()
        exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) }
    }
    http.start()
    val manager = UnrealInstanceManager()
    try {
        val result = manager.forwardToolCallToPort(http.address.port,JSONObject().put("name","GetEditorOperation")
            .put("_meta",JSONObject().put("nexusSessionId","wire-test")),3000L, progress = {
            count.incrementAndGet(); cancelled.set(true)
        }, isCancelled = {cancelled.get()})
        check(result.status == WsRequestStatus.OK && result.response?.getJSONObject("result")?.getBoolean("cancelled") == true)
        check(count.get() == 1)
    } finally { manager.disconnect(); http.stop(0); ws.stop(1000) }
}
