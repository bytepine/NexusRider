// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONArray
import org.json.JSONObject
import com.sun.net.httpserver.HttpServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 三端共同契约：PascalCase、host/port 隔离、section/degraded 与按实例失效。 */
fun main() {
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
    testDispatcherRouting()
    println("proxy policy contract OK")
}

/** 本地 HTTP/WS 协议替身，验证真实 Manager 与 Dispatcher，不启动 UE。 */
private class RoutingInstance(name: String) : AutoCloseable {
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
            if (request.optString("method") == "tools/call") {
                calls.incrementAndGet()
                result.put("content", JSONArray().put(JSONObject().put("type", "text")
                    .put("text", JSONObject().put("source", name).toString())))
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
            call("ManageAssetBlueprint")
            check(call("GetAssetBlueprint", a.port) == "A" && a.calls.get() == 1) { "write B invalidated A" }
            check(call("GetAssetBlueprint") == "B" && b.calls.get() == 3) { "write B did not invalidate B" }
            manager.disconnect()
            check(manager.cacheScope == LanHost.instanceKey("", b.port))
        } finally { manager.disconnect() }
    } }
}
