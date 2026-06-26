// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.diagnostic.logger
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

/**
 * UE 实例信息（已加载 NexusLink，可连接）。
 */
data class UnrealInstanceInfo(
    val port: Int,
    val wsPort: Int = port + 10000,
    val projectName: String = "",
    val engineVersion: String = "",
    /** UE 网络角色（DedicatedServer/ListenServer/Client/Standalone/Editor）。 */
    val netRole: String = "",
    /** UE 工具列表暴露模式（历史字段），供状态探测；代理侧仅暴露 list/connect。 */
    val toolsListMode: String = "starter",
)

/**
 * 管理多个 Unreal Engine 实例的发现和连接。
 *
 * - 发现：`GET /status` 探测（1 次 HTTP 请求即知存活 + 项目信息）
 * - 通信：WebSocket 长连接（JSON-RPC，无 MCP 握手开销）
 */
class UnrealInstanceManager {

    private val log = logger<UnrealInstanceManager>()

    /** 已连接且加载了 NexusLink 的 UE 实例列表。 */
    val instances = CopyOnWriteArrayList<UnrealInstanceInfo>()

    var connectedPort: Int = -1
        private set

    /** 当前连接的 UE 实例的工具列表模式（full/starter/custom），断连时重置为 "starter"。 */
    @Volatile var connectedToolsListMode: String = "starter"
        private set

    /** WebSocket 是否仍为 OPEN 状态（供上层判定 connected 字段真实性）。 */
    fun isWsOpen(): Boolean = wsClient?.isOpen == true

    /**
     * 转发 tools/call 前确保长连接可用：已 OPEN 则直接成功；
     * 否则按 connectedPort / preferredPort 重建，或 discoverInstances 自动连 Editor。
     */
    fun ensureLongConnection(): Boolean {
        if (isWsOpen()) return true // 快路径：免锁，绝大多数 tools/call 命中此处

        synchronized(connectionLock) {
            if (isWsOpen()) return true // 双检：可能已被并发线程重连

            val reconnectPort = when {
                connectedPort > 0 -> connectedPort
                preferredPort > 0 -> preferredPort
                else -> -1
            }

            clearStaleConnectionState()

            if (reconnectPort > 0) {
                return connectTo(reconnectPort, setPreferred = false)
            }

            discoverInstances()
            return isWsOpen()
        }
    }

    /** connectedPort 滞留但 WS 已死（Windows TCP 半开等），在无挂起请求时清理。 */
    private fun clearStaleConnectionState() {
        if (connectedPort > 0 && !isWsOpen() && pendingRequests.isEmpty()) {
            resetWsConnection(clearPreferred = false)
        }
    }

    var scanPortStart = 45000
    var scanPortEnd = 45100

    companion object {
        /** 并发端口扫描的线程数上限。 */
        private const val SCAN_THREAD_COUNT = 20

        /** probeStatus 响应体读取上限，防异常端口返回超大 body 占内存。 */
        private const val PROBE_MAX_BYTES = 65_536

        /** tools/call 默认超时：资产搜索等 GameThread 任务常超过 5s。 */
        const val TOOLS_CALL_TIMEOUT_MS = 120_000L

        /** 与 tools/call 对齐，避免 tools/list 仍用 3s 与慢工具并发。 */
        const val WS_LIGHT_REQUEST_TIMEOUT_MS = TOOLS_CALL_TIMEOUT_MS

        /** 长连接存活时，每隔 N 次维护轮才做一次全端口扫描；其余轮仅探测 connectedPort。 */
        private const val FULL_SCAN_EVERY_N_TICKS = 6

        /** 有挂起请求时（忙）的应用层保活 ping 间隔（与 VSCode WS_KEEPALIVE_BUSY_MS 对齐）。 */
        private const val WS_KEEPALIVE_BUSY_MS = 5_000L

        /** 无挂起请求时（闲）的应用层保活 ping 间隔（与 VSCode WS_KEEPALIVE_IDLE_MS 对齐）。 */
        private const val WS_KEEPALIVE_IDLE_MS = 15_000L
    }

    /** 连接状态变更串行锁：防止扫描定时器与 onClose 重扫叠加导致连接抖动。 */
    private val connectionLock = Any()

    /** 维护轮倒计时：0 表示本轮应全量扫描。 */
    private val fullScanCountdown = AtomicInteger(0)

    /** 长连接 WS 请求互斥，避免 tools/list 与 tools/call 并发。 */
    private val wsRequestLock = Any()

    /**
     * 共享的单线程执行器，用于断连后的异步重扫，避免每次 onClose 创建新线程池导致泄漏。
     * daemon=true 确保 JVM 退出时不阻塞。
     */
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nexus-reconnect").also { it.isDaemon = true }
    }

    /**
     * 用户手动指定的目标端口（-1 表示未指定，由自动发现决定）。
     * 断连后自动重连优先连此端口，避免覆盖用户选择。
     */
    @Volatile var preferredPort: Int = -1

    /** 当前活跃的 WebSocket 连接（多线程访问，需 @Volatile 保证可见性）。 */
    @Volatile private var wsClient: WebSocketClient? = null

    /** 当前应用层保活 ping 任务句柄，resetWsConnection 时取消。 */
    @Volatile private var pingFuture: ScheduledFuture<*>? = null

    /**
     * 工具列表缓存。
     * UE 端切换工具启用状态时会推送 notifications/tools/list_changed 令缓存失效。
     * 非主动断连时保留，避免 AI 客户端把 tools/call 降级成 Tool not found。
     */
    @Volatile private var cachedToolsList: JSONArray? = null

    /** UE 端 InitializeInstructions.md 内容缓存（连接成功后异步拉取）。 */
    @Volatile private var upstreamInstructions: String = ""

    /** UE 端 ProxyConfig.json 内容缓存（连接成功后异步拉取）。 */
    @Volatile private var cachedProxyConfig: ProxyConfig? = null

    /** UE 端工具列表变更时的回调（清缓存后触发），由外部设置以转发通知给 MCP 客户端。 */
    @Volatile var onToolsChanged: (() -> Unit)? = null

    /** JSON-RPC 请求 ID 自增计数器。 */
    private val idCounter = AtomicInteger(1)

    /** 连接代次：旧 socket 的 onClose 不误清新连接。 */
    private val connectionEpoch = AtomicInteger(0)

    /** 挂起的请求：id → (latch, response holder)。 */
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<Int, Pair<CountDownLatch, Array<String?>>>()

    /**
     * 定时维护连接：长连接存活时优先廉价心跳（仅探测 connectedPort），
     * 仅在长连接断开、对端消失、或每隔 N 轮时才做全端口扫描，
     * 大幅降低稳态背景探测开销（101 端口/轮 → 1 端口/轮）。
     */
    fun maintainConnection() {
        if (isWsOpen() && connectedPort > 0) {
            // 有挂起请求 = 连接正被使用，本身即存活证明，跳过探测避免 GameThread 忙时误判
            if (pendingRequests.isNotEmpty()) return
            if (fullScanCountdown.getAndDecrement() > 0) {
                if (probeStatus(connectedPort) != null) return // 心跳成功，省去全量扫描
                resetWsConnection(clearPreferred = false)      // 确实失联 → 重置后转全量重扫
            }
        }
        fullScanCountdown.set(FULL_SCAN_EVERY_N_TICKS)
        discoverInstances()
    }

    /**
     * 发现所有活跃的 UE 实例。
     *
     * 发现策略：并发端口扫描，探测 NexusLink HTTP /status 端点。
     * 整体在 connectionLock 下串行，避免并发发现叠加导致连接抖动。
     */
    fun discoverInstances(): List<UnrealInstanceInfo> = synchronized(connectionLock) {
        val found = scanPortsParallel()

        instances.clear()
        instances.addAll(found)

        // 检查 WebSocket 是否仍然打开（处理 onClose 与 connectedPort 赋值的竞态问题）
        if (connectedPort > 0 && wsClient?.isOpen != true) {
            if (pendingRequests.isEmpty()) {
                log.info("WebSocket 连接已关闭（端口 $connectedPort），重置连接状态")
                resetWsConnection(clearPreferred = false)
            }
        }

        if (connectedPort > 0 && found.none { it.port == connectedPort }) {
            log.info("已连接的 UE 实例 (端口 $connectedPort) 已不可用，自动断开")
            resetWsConnection(clearPreferred = false)
        }

        if (connectedPort < 0 && found.isNotEmpty()) {
            // 优先连用户手动指定的端口，其次连 Editor，最后取第一个
            val target = when {
                preferredPort > 0 && found.any { it.port == preferredPort } ->
                    found.first { it.port == preferredPort }
                else ->
                    found.firstOrNull { it.netRole.equals("Editor", ignoreCase = true) } ?: found[0]
            }
            connectTo(target.port)
        }

        found
    }

    /**
     * 并发扫描端口范围，发现活跃的 UE 实例。
     * 使用线程池避免顺序扫描的累积延迟。
     */
    private fun scanPortsParallel(): List<UnrealInstanceInfo> {
        // 防御：用户将 start/end 配置颠倒时自动交换，避免 fixedThreadPool(负数) 抛异常
        val start = minOf(scanPortStart, scanPortEnd)
        val end = maxOf(scanPortStart, scanPortEnd)
        val pool = Executors.newFixedThreadPool(
            minOf(SCAN_THREAD_COUNT, end - start + 1)
        )
        return try {
            val futures = (start..end).map { port ->
                pool.submit<UnrealInstanceInfo?> { probeStatus(port) }
            }
            futures.mapNotNull { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * 通过 WebSocket 连接到指定端口的 UE 实例。
     */
    fun connectTo(port: Int, setPreferred: Boolean = false): Boolean = synchronized(connectionLock) {
        if (setPreferred) preferredPort = port
        // 已连到同端口且 WS 存活：直接复用，避免 reset→重建造成连接抖动
        if (connectedPort == port && isWsOpen()) return true
        val info = probeStatus(port) ?: return false
        resetWsConnection(clearPreferred = false)

        try {
            val wsUri = URI("ws://127.0.0.1:${info.wsPort}")
            val epoch = connectionEpoch.incrementAndGet()
            wsClient = object : WebSocketClient(wsUri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    // 0=关闭库层 ping 探活：本地回环无需保活，半开由扫描定时器兜底，
                    // 避免长 GameThread 任务（search_asset 等）期间因 pong 迟到被误判丢失而断链
                    connectionLostTimeout = 0
                    log.info("WebSocket 已连接到 UE 实例（端口 $port, 项目: ${info.projectName}）")
                }

                override fun onMessage(message: String) {
                    handleWsMessage(message)
                }

                // UE 的 libwebsockets 以 Binary 帧发送响应，需要显式解码 UTF-8
                override fun onMessage(bytes: java.nio.ByteBuffer) {
                    handleWsMessage(Charsets.UTF_8.decode(bytes).toString())
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    if (connectionEpoch.get() != epoch) return
                    log.info("WebSocket 连接关闭: code=$code, reason=${reason.orEmpty()}, remote=$remote, port=$port")
                    if (connectedPort == port) {
                        connectedPort = -1
                        connectedToolsListMode = "starter"
                        upstreamInstructions = ""
                        cachedProxyConfig = null
                    }
                    // 立即释放所有挂起请求，避免 UE 断链导致 tools/list 等待 10s 超时
                    pendingRequests.values.forEach { (latch, _) -> latch.countDown() }
                    pendingRequests.clear()
                    // 断链后复用共享线程池触发重扫，避免每次创建新线程池导致泄漏
                    reconnectExecutor.execute {
                        try { discoverInstances() } catch (_: Exception) { }
                    }
                }

                override fun onError(ex: Exception?) {
                    log.warn("WebSocket 错误（端口 $port）: ${ex?.message}")
                }
            }

            // connectBlocking 内部等待握手完成（onOpen 已在其中被调用）
            val success = wsClient!!.connectBlocking(3, TimeUnit.SECONDS)

            // 握手后再确认 isOpen，排除握手完成但随即被对端关闭的竞态
            if (success && wsClient?.isOpen == true) {
                connectedPort = port
                connectedToolsListMode = info.toolsListMode
                // 启动应用层保活 ping（忙 5s / 闲 15s），替代库层 connectionLostTimeout
                schedulePing()
                // 异步拉取 UE 端 instructions 缓存，供下次 handleInitialize 拼接
                reconnectExecutor.execute {
                    try {
                        fetchUpstreamInstructions()
                        fetchProxyConfig()
                    } catch (_: Exception) { }
                }
                true
            } else {
                log.warn("WebSocket 握手后连接未就绪（端口 $port），放弃")
                wsClient?.close()
                wsClient = null
                false
            }
        } catch (e: Exception) {
            log.warn("WebSocket 连接失败（端口 $port）: ${e.message}")
            wsClient = null
            false
        }
    }

    fun disconnect() {
        resetWsConnection(clearPreferred = true)
    }

    /** 关闭 WS 并重置连接状态；clearPreferred 仅用户主动断开时为 true。 */
    private fun resetWsConnection(clearPreferred: Boolean) {
        connectionEpoch.incrementAndGet()
        // 取消保活 ping
        pingFuture?.cancel(false)
        pingFuture = null
        pendingRequests.values.forEach { (latch, _) -> latch.countDown() }
        pendingRequests.clear()
        wsClient?.close()
        wsClient = null
        connectedPort = -1
        connectedToolsListMode = "starter"
        cachedToolsList = null
        upstreamInstructions = ""
        cachedProxyConfig = null
        if (clearPreferred) {
            preferredPort = -1
        }
    }

    /**
     * 启动应用层保活 ping 循环：
     * 有挂起请求（忙）→ WS_KEEPALIVE_BUSY_MS，无（闲）→ WS_KEEPALIVE_IDLE_MS。
     * 复用 reconnectExecutor，无需额外线程池。
     */
    private fun schedulePing() {
        pingFuture?.cancel(false)
        val delay = if (pendingRequests.isNotEmpty()) WS_KEEPALIVE_BUSY_MS else WS_KEEPALIVE_IDLE_MS
        pingFuture = reconnectExecutor.schedule({
            val ws = wsClient
            if (ws != null && ws.isOpen) {
                try { ws.sendPing() } catch (_: Exception) { }
                schedulePing() // 自重调，下次间隔根据当时忙闲重新决定
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    /**
     * 通过 WebSocket 获取远端工具列表。
     *
     * 优先返回缓存以避免重复 WS 往返；UE 端切换工具启用状态时推送
     * notifications/tools/list_changed 令缓存自动失效。
     */
    fun fetchToolsList(): JSONArray? {
        cachedToolsList?.let { return it }
        val outcome = sendWsRequest("tools/list", null, timeoutMs = WS_LIGHT_REQUEST_TIMEOUT_MS)
        if (outcome.status != WsRequestStatus.OK) return null
        val tools = outcome.response?.optJSONObject("result")?.optJSONArray("tools") ?: return null
        cachedToolsList = tools
        return tools
    }

    /**
     * 通过 WebSocket 转发 tools/call。
     */
    fun forwardToolCall(params: JSONObject): WsRequestOutcome {
        return sendWsRequest("tools/call", params, timeoutMs = TOOLS_CALL_TIMEOUT_MS)
    }

    /**
     * 拉取并缓存 UE 端 InitializeInstructions.md 内容。
     * UE WebSocket 通道无 MCP 握手，故走自定义 method `nexus/instructions`。
     */
    fun fetchUpstreamInstructions(): String {
        if (upstreamInstructions.isNotEmpty()) return upstreamInstructions
        val outcome = sendWsRequest("nexus/instructions", null, timeoutMs = WS_LIGHT_REQUEST_TIMEOUT_MS)
        if (outcome.status != WsRequestStatus.OK) return upstreamInstructions
        val text = outcome.response?.optJSONObject("result")?.optString("instructions", "") ?: ""
        if (text.isNotEmpty()) upstreamInstructions = text
        return upstreamInstructions
    }

    /** 同步读取已缓存的 UE 端 instructions（未连接或未拉取时返回空串）。 */
    fun getUpstreamInstructions(): String = upstreamInstructions

    /**
     * 拉取并缓存 UE 端 ProxyConfig.json（nexus/proxy_config）。
     */
    fun fetchProxyConfig(): ProxyConfig {
        cachedProxyConfig?.let { return it }
        val outcome = sendWsRequest("nexus/proxy_config", null, timeoutMs = WS_LIGHT_REQUEST_TIMEOUT_MS)
        if (outcome.status != WsRequestStatus.OK) return getProxyConfig()
        val result = outcome.response?.optJSONObject("result")
        val parsed = ProxyConfigDefaults.parse(result)
        cachedProxyConfig = parsed
        return parsed
    }

    /** 读取代理配置：已缓存则返回 UE 配置，否则 DEFAULT fallback。 */
    fun getProxyConfig(): ProxyConfig = cachedProxyConfig ?: ProxyConfigDefaults.DEFAULT

    /**
     * 通过一次性 WebSocket 连接向指定端口转发 tools/call，不改动长连接绑定。
     * 用于 AI 指定 targetPort 跨实例并发查询（DS/Client1/Client2 同时取）。
     * 优先用最近一次扫描的 instances 缓存解析 wsPort，省冗余 HTTP 探测。
     */
    fun forwardToolCallToPort(
        port: Int,
        params: JSONObject,
        timeoutMs: Long = TOOLS_CALL_TIMEOUT_MS,
    ): WsRequestOutcome {
        val info = instances.find { it.port == port } ?: probeStatus(port) ?: return WsRequestOutcome.disconnected()
        val id = idCounter.getAndIncrement()
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put("params", params)
        }

        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        var oneShot: WebSocketClient? = null
        return try {
            oneShot = object : WebSocketClient(URI("ws://127.0.0.1:${info.wsPort}")) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    send(request.toString())
                }
                override fun onMessage(message: String) {
                    try {
                        val json = JSONObject(message)
                        if (json.optInt("id", -1) == id) {
                            holder[0] = message
                            latch.countDown()
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
                override fun onMessage(bytes: java.nio.ByteBuffer) {
                    onMessage(Charsets.UTF_8.decode(bytes).toString())
                }
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    latch.countDown()
                }
                override fun onError(ex: Exception?) {
                    log.warn("一次性 WS 转发错误（端口 $port）: ${ex?.message}")
                    latch.countDown()
                }
            }
            if (!oneShot.connectBlocking(3, TimeUnit.SECONDS)) return WsRequestOutcome.disconnected()
            // 超时后强制关闭连接，避免挂起
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("一次性 WS 转发超时（端口 $port）")
                try { oneShot.close() } catch (_: Exception) { }
                return WsRequestOutcome.timeout()
            }
            holder[0]?.let { WsRequestOutcome.ok(JSONObject(it)) } ?: WsRequestOutcome.disconnected()
        } catch (e: Exception) {
            log.warn("一次性 WS 转发失败（端口 $port）: ${e.message}")
            WsRequestOutcome.disconnected()
        } finally {
            try { oneShot?.close() } catch (_: Exception) { /* ignore */ }
        }
    }

    // --- 内部方法 ---

    /**
     * 通过 GET /status 探测 UE 实例（1 次 HTTP，无 MCP 握手）。
     */
    private fun probeStatus(port: Int): UnrealInstanceInfo? {
        return try {
            val url = URI("http://127.0.0.1:$port/status").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 1000
                readTimeout = 1000
            }
            if (conn.responseCode != 200) return null

            // Content-Length 超限时快速跳过，避免无界读取
            val contentLength = conn.contentLengthLong
            if (contentLength > PROBE_MAX_BYTES) return null

            val body = conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var totalRead = 0
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    totalRead += n
                    if (totalRead > PROBE_MAX_BYTES) return null
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            val json = JSONObject(body)

            val serverName = json.optString("server", "")
            if (!serverName.contains("Nexus", ignoreCase = true)) return null

            UnrealInstanceInfo(
                port = port,
                wsPort = json.optInt("wsPort", port + 10000),
                projectName = json.optString("projectName", ""),
                engineVersion = json.optString("engineVersion", ""),
                netRole = json.optString("netRole", ""),
                toolsListMode = json.optString("toolsListMode", "starter"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过 WebSocket 发送 JSON-RPC 请求并同步等待响应。
     */
    private fun sendWsRequest(method: String, params: JSONObject?, timeoutMs: Long = 5000): WsRequestOutcome {
        synchronized(wsRequestLock) {
            return sendWsRequestUnlocked(method, params, timeoutMs)
        }
    }

    private fun sendWsRequestUnlocked(method: String, params: JSONObject?, timeoutMs: Long): WsRequestOutcome {
        val ws = wsClient
        if (ws == null || !ws.isOpen) {
            clearStaleConnectionState()
            return WsRequestOutcome.disconnected()
        }

        val id = idCounter.getAndIncrement()
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        pendingRequests[id] = Pair(latch, holder)

        return try {
            ws.send(request.toString())
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("WebSocket 请求超时: $method (id=$id)")
                WsRequestOutcome.timeout()
            } else {
                holder[0]?.let { WsRequestOutcome.ok(JSONObject(it)) } ?: WsRequestOutcome.disconnected()
            }
        } catch (e: Exception) {
            log.warn("WebSocket 请求失败: ${e.message}")
            WsRequestOutcome.disconnected()
        } finally {
            pendingRequests.remove(id)
        }
    }

    /**
     * 处理 WebSocket 收到的消息：匹配挂起的请求响应，或处理 UE 端主动推送的通知。
     */
    private fun handleWsMessage(message: String) {
        try {
            val json = JSONObject(message)
            val id = json.optInt("id", -1)
            if (id >= 0) {
                val pending = pendingRequests[id]
                if (pending != null) {
                    pending.second[0] = message
                    pending.first.countDown()
                    return
                }
            }
            // UE 端主动推送通知
            val method = json.optString("method", "")
            if (method == "notifications/tools/list_changed") {
                log.info("收到 UE 工具列表变更通知，清除缓存")
                cachedToolsList = null
                onToolsChanged?.invoke()
            }
        } catch (e: Exception) {
            log.warn("WebSocket 消息解析失败: ${e.message}")
        }
    }
}
