// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.diagnostic.logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * MCP 会话状态。
 */
enum class McpSessionState {
    /** 等待 initialize 请求。 */
    WaitingForInitialize,
    /** 已收到 initialize，等待 initialized 通知。 */
    WaitingForInitialized,
    /** 正常运行。 */
    Running,
}

/**
 * MCP JSON-RPC 2.0 分发器。
 * 无网络依赖，负责解析 JSON-RPC 消息并路由到对应的 MCP 方法处理函数。
 *
 * 代理模式：自身不注册工具，而是通过 [UnrealInstanceManager] 将
 * tools/list 和 tools/call 请求转发给当前连接的 UE 实例。
 */
class NexusMcpDispatcher(
    private val unrealManager: UnrealInstanceManager,
    /** MCP 会话进入 Running 后回调，用于向 SSE 客户端推送 tools/list_changed。 */
    private val onSessionReady: (() -> Unit)? = null,
) {

    private val log = logger<NexusMcpDispatcher>()

    /** @Volatile 保证多线程下状态可见性（CachedThreadPool 下并发 dispatch）。 */
    @Volatile var state = McpSessionState.WaitingForInitialize
        private set

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_NAME = "Nexus-Rider"

        /**
         * 运行时读取插件版本：从打包资源 nexus-mcp-version.txt 读取（由 Gradle 注入），
         * 不依赖任何 @ApiStatus.Internal 平台 API；IDE 开发态资源缺失时回退 "0.0.0"。
         */
        private val SERVER_VERSION: String by lazy {
            NexusMcpDispatcher::class.java.getResourceAsStream("/nexus-mcp-version.txt")
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText().trim() }
                ?.takeIf { it.isNotEmpty() }
                ?: "0.0.0"
        }

        /** initialize 握手时 UE 预热的最大等待时间（超时先返回 prefix，后台继续）。 */
        private const val INITIALIZE_WARMUP_MS = 2_000L

        // JSON-RPC 2.0 错误码
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603
    }

    /**
     * 处理一条 JSON-RPC 消息，返回响应 JSON 字符串。
     */
    fun dispatch(jsonLine: String): String {
        val msg: JSONObject
        try {
            msg = JSONObject(jsonLine)
        } catch (e: Exception) {
            log.warn("JSON 解析失败: $jsonLine")
            return makeError(null, PARSE_ERROR, "Parse error")
        }

        val jsonrpc = msg.optString("jsonrpc", "")
        if (jsonrpc != "2.0") {
            return makeError(null, INVALID_REQUEST, "Invalid JSON-RPC version")
        }

        val method = msg.optString("method", "")
        if (method.isBlank()) {
            return makeError(null, INVALID_REQUEST, "Missing method")
        }

        val id = if (msg.has("id")) msg.get("id") else null
        val params = msg.optJSONObject("params")

        return when (method) {
            "initialize" -> handleInitialize(id, params)
            "notifications/initialized" -> {
                handleInitialized()
                "" // 通知无响应
            }
            "ping" -> makeResult(id, JSONObject())
            "tools/list" -> {
                if (state != McpSessionState.Running) {
                    return makeError(id, INVALID_REQUEST, "Session not initialized")
                }
                handleToolsList(id)
            }
            "tools/call" -> {
                if (state != McpSessionState.Running) {
                    return makeError(id, INVALID_REQUEST, "Session not initialized")
                }
                handleToolsCall(id, params)
            }
            else -> {
                log.warn("未知方法: $method")
                if (id != null) makeError(id, METHOD_NOT_FOUND, "Method not found: $method") else ""
            }
        }
    }

    /**
     * @Synchronized 保证 initialize/initialized 状态转换的原子性，
     * 防止并发请求下出现 check-then-act 竞态。
     */
    @Synchronized
    private fun handleInitialize(id: Any?, params: JSONObject?): String {
        if (state != McpSessionState.WaitingForInitialize) {
            log.info("收到重复的 initialize 请求，重置会话状态")
            state = McpSessionState.WaitingForInitialize
        }

        // 尽量回显客户端版本，确保兼容性；不支持时回退到服务端最高版本
        val clientVersion = params?.optString("protocolVersion", "") ?: ""
        val negotiatedVersion = if (clientVersion.isNotBlank()) clientVersion else PROTOCOL_VERSION

        // 握手时主动 discover + 连 Editor，再拉 proxy_config / instructions。
        // 加 INITIALIZE_WARMUP_MS 上限：超时先返回 prefix，后台继续预热，避免全端口扫描阻塞握手。
        var upstream = unrealManager.getUpstreamInstructions()
        val warmupExecutor = Executors.newSingleThreadExecutor()
        val warmupFuture: Future<*> = warmupExecutor.submit {
            try {
                unrealManager.maintainConnection()
                if (unrealManager.isWsOpen()) {
                    unrealManager.fetchProxyConfig()
                    upstream = unrealManager.fetchUpstreamInstructions()
                }
            } catch (_: Exception) { /* UE 未运行时仍返回 prefix */ }
        }
        try {
            warmupFuture.get(INITIALIZE_WARMUP_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // 超时先返回，warmupFuture 在后台继续执行，为下次 tools/call 预热缓存
            log.info("initialize 预热超时（${INITIALIZE_WARMUP_MS}ms），先返回 prefix，后台继续预热")
        } catch (_: Exception) { /* 其他异常忽略 */ }
        warmupExecutor.shutdown()

        val activeConfig = unrealManager.getProxyConfig()
        val connectedNote = if (unrealManager.isWsOpen()) {
            "(Connected via Rider plugin.)"
        } else {
            "(UE not connected — call list_unreal_instances + connect_unreal_instance when needed.)"
        }
        val prefix = "${activeConfig.initializePrefix}\n$connectedNote"
        val instructionsText = if (upstream.isNotEmpty()) {
            "$prefix\n\n--- Upstream (Unreal) ---\n$upstream"
        } else {
            prefix
        }

        val result = JSONObject().apply {
            put("protocolVersion", negotiatedVersion)
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject().put("listChanged", true))
            })
            put("serverInfo", JSONObject().apply {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            })
            put("instructions", instructionsText)
            // Prompt Caching：instructions 稳定文本，标记为 ephemeral 供支持缓存的客户端复用
            put("cache_control", JSONObject().put("type", "ephemeral"))
        }
        state = McpSessionState.WaitingForInitialized
        log.info("initialize 握手完成")
        return makeResult(id, result)
    }

    @Synchronized
    private fun handleInitialized() {
        if (state != McpSessionState.WaitingForInitialized) {
            log.warn("在非预期状态下收到 'initialized' 通知（当前状态: $state）")
            return
        }
        state = McpSessionState.Running
        log.info("会话初始化完成，已就绪")
        // AI 客户端重连后不会自动拉 tools/list；预热缓存并推送 list_changed 刷新工具清单。
        try {
            unrealManager.fetchToolsList()
        } catch (_: Exception) { /* UE 未连接时仍通知客户端刷新 */ }
        onSessionReady?.invoke()
    }

    /**
     * tools/list：代理自有工具 + 已连接 UE 实例的远端工具。
     */
    private fun handleToolsList(id: Any?): String {
        val toolsArray = JSONArray()
        val proxyConfig = unrealManager.getProxyConfig()

        for (tool in proxyConfig.connectionTools) {
            toolsArray.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", tool.inputSchema)
            })
        }

        val remoteTools = unrealManager.fetchToolsList()

        if (remoteTools != null) {
            for (i in 0 until remoteTools.length()) {
                toolsArray.put(remoteTools.getJSONObject(i))
            }
        }

        return makeResult(id, JSONObject().apply {
            put("tools", toolsArray)
        })
    }

    /**
     * tools/call：代理自有工具本地处理，远端工具转发到 UE。
     */
    private fun handleToolsCall(id: Any?, params: JSONObject?): String {
        if (params == null) return makeError(id, INVALID_PARAMS, "Missing params")
        val toolName = params.optString("name", "")
        if (toolName.isBlank()) return makeError(id, INVALID_PARAMS, "Missing tool name")

        val proxyConfig = unrealManager.getProxyConfig()

        // 代理自有工具（名称由 UE proxy_config 定义）
        if (proxyConfig.localToolNames.contains(toolName)) {
            return when (toolName) {
                "list_unreal_instances" -> executeListInstances(id)
                "connect_unreal_instance" -> executeConnect(id, params.optJSONObject("arguments"))
                else -> makeError(id, METHOD_NOT_FOUND, "Unknown local tool: $toolName")
            }
        }

        // 可选 targetPort：一次性路由到指定实例，不改动长连接绑定
        var targetPort = -1
        val forwardParams = JSONObject(params.toString())
        val args = forwardParams.optJSONObject("arguments")
        if (args != null && args.has("targetPort")) {
            val tp = args.optInt("targetPort", -1)
            if (tp >= 1024) {
                targetPort = tp
                args.remove("targetPort")
            }
        }

        // 远端工具转发：默认长连接；仅显式 targetPort 走一次性 WS。
        var outcome = if (targetPort > 0) {
            unrealManager.forwardToolCallToPort(targetPort, forwardParams)
        } else {
            unrealManager.ensureLongConnection()
            var first = unrealManager.forwardToolCall(forwardParams)
            if (first.status == WsRequestStatus.DISCONNECTED && unrealManager.ensureLongConnection()) {
                unrealManager.forwardToolCall(forwardParams)
            } else {
                first
            }
        }
        when (outcome.status) {
            WsRequestStatus.DISCONNECTED ->
                return makeError(id, INTERNAL_ERROR, proxyConfig.errorMessages.notConnected)
            WsRequestStatus.TIMEOUT -> {
                val sec = UnrealInstanceManager.TOOLS_CALL_TIMEOUT_MS / 1000
                return makeError(
                    id,
                    INTERNAL_ERROR,
                    "UE request timed out after ${sec}s. ${proxyConfig.errorMessages.timeoutHint}",
                )
            }
            WsRequestStatus.OK -> { /* continue */ }
        }
        val response = outcome.response
            ?: return makeError(id, INTERNAL_ERROR, "Invalid response from UE instance")
        return if (response.has("result")) {
            makeResult(id, response.getJSONObject("result"))
        } else if (response.has("error")) {
            val err = response.getJSONObject("error")
            makeError(id, err.optInt("code", INTERNAL_ERROR), err.optString("message", "Unknown error"))
        } else {
            makeError(id, INTERNAL_ERROR, "Invalid response from UE instance")
        }
    }

    // --- 代理自有工具实现 ---

    private fun executeListInstances(id: Any?): String {
        val instances = unrealManager.discoverInstances()
        // connected 同时要求 WebSocket 仍为 OPEN：Windows TCP 半开态下 connectedPort
        // 可能滞留几十秒，仅比对 port 会产生假阳性。
        val wsOpen = unrealManager.isWsOpen()
        val arr = JSONArray()
        instances.forEach { info ->
            arr.put(JSONObject().apply {
                put("port", info.port)
                put("projectName", info.projectName)
                put("engineVersion", info.engineVersion)
                put("connected", info.port == unrealManager.connectedPort && wsOpen)
                if (info.netRole.isNotEmpty()) put("netRole", info.netRole)
            })
        }
        val content = JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", arr.toString(2))
        })
        return makeResult(id, JSONObject().apply {
            put("content", content)
            put("isError", false)
        })
    }

    private fun executeConnect(id: Any?, params: JSONObject?): String {
        val port = params?.optInt("port", -1) ?: -1
        if (port < 1024) {
            return makeError(id, INVALID_PARAMS, "Invalid port: $port")
        }
        val success = unrealManager.connectTo(port, setPreferred = true)
        if (success) {
            // 连接成功后主动预热工具缓存并推送 tools/list_changed；
            // refresh task 跑到时 wasWsOpen 已为 true，无法再检测新连接事件，须在此主动触发。
            try { unrealManager.fetchToolsList() } catch (_: Exception) { }
            onSessionReady?.invoke()
        }
        val msg = if (success) "已连接到 UE 实例 (端口 $port)" else "连接失败：端口 $port 无响应"
        val content = JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", msg)
        })
        return makeResult(id, JSONObject().apply {
            put("content", content)
            put("isError", !success)
        })
    }

    // --- JSON-RPC 辅助 ---

    private fun makeResult(id: Any?, result: JSONObject): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JSONObject.NULL)
            put("result", result)
        }.toString()
    }

    private fun makeError(id: Any?, code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }
}
