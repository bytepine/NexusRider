// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.nexusmcp.mcp.nexusLogger as logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.timeout.ReadTimeoutHandler
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * nexus-rider 独立 MCP HTTP 服务器（per-session 会话隔离）。
 * 同时支持两种 MCP 传输协议：
 *   POST /stream   → Streamable HTTP，通过 Mcp-Session-Id 头隔离会话
 *   GET  /sse      → SSE 长连接，仅用于服务端推送通知
 *   DELETE /stream → 客户端主动结束会话
 *
 * 不依赖 Rider 内置 MCP Server，可独立运行。
 */
class NexusMcpServer(private val unrealManager: UnrealInstanceManager, private val proxyToken: String) {

    private val log = logger<NexusMcpServer>()
    private val requestCounter = AtomicLong()

    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null

    /** 用于执行阻塞 dispatch（如 WS 转发等），避免阻塞 Netty IO 线程。 */
    private var dispatchExecutor: ExecutorService? = null

    /** 活跃的 SSE 客户端连接，用于推送 MCP 服务端通知。 */
    private val sseContexts = CopyOnWriteArrayList<ChannelHandlerContext>()

    internal val httpSessions = McpHttpSessions()

    companion object {
        /** 容量不足时仅回收空闲会话，保留正在使用的连接。 */
        internal const val MAX_SESSIONS = 50
        internal val SESSION_IDLE_NANOS = TimeUnit.MINUTES.toNanos(30)
        internal const val MAX_SSE_CLIENTS = 32
    }

    var port: Int = 0
        private set

    fun start(port: Int, bindHost: String = LanHost.LOOPBACK): Boolean {
        if (channel != null) {
            log.warn("MCP 服务器已在端口 ${this.port} 运行")
            return true
        }
        this.port = port
        bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        workerGroup = MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory())
        dispatchExecutor = Executors.newCachedThreadPool()

        val executor = dispatchExecutor!!
        val sse = sseContexts
        val sessions = httpSessions
        val mgr = unrealManager
        return try {
            val bootstrap = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .localAddress(InetSocketAddress(bindHost, port))
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().apply {
                            addLast(ReadTimeoutHandler(10))
                            addLast(IdleStateHandler(0, 0, 60, TimeUnit.SECONDS))
                            addLast(object : ChannelInboundHandlerAdapter() {
                                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                                    if (evt is IdleStateEvent && evt.state() == IdleState.ALL_IDLE) {
                                        ctx.close()
                                        return
                                    }
                                    super.userEventTriggered(ctx, evt)
                                }
                            })
                            addLast(HttpServerCodec())
                            addLast(object : HttpObjectAggregator(NexusMcpAuth.MAX_BODY_BYTES) {
                                override fun handleOversizedMessage(ctx: ChannelHandlerContext, oversized: HttpMessage) {
                                    log.warn("MCP HTTP 请求拒绝 status=413 reason=payload_too_large remote=${McpDiagnostics.text(ctx.channel().remoteAddress().toString())}")
                                    super.handleOversizedMessage(ctx, oversized)
                                }
                            })
                            addLast(McpHttpHandler(sessions, mgr, executor, sse, proxyToken, requestCounter) { sendToolsChangedNotification() })
                        }
                    }
                })

            channel = bootstrap.bind().sync().channel()
            log.info("MCP HTTP 服务器已启动  bind=$bindHost  stream: http://${LanHost.mcpDisplayHost(bindHost != LanHost.LOOPBACK)}:$port/stream")
            true
        } catch (e: Exception) {
            log.error("MCP 服务器启动失败：${e.message}")
            stop("start_failed")
            false
        }
    }

    fun stop(reason: String = "stop_requested") {
        log.info("MCP HTTP 停止 reason=${McpDiagnostics.text(reason)} port=$port sessions=${httpSessions.size} sse=${sseContexts.size}；现有会话将失效")
        sseContexts.forEach { it.close() }
        sseContexts.clear()
        httpSessions.clear()
        channel?.close()?.sync()
        channel = null
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
        workerGroup = null
        bossGroup = null
        dispatchExecutor?.shutdown()
        dispatchExecutor = null
        log.info("MCP HTTP 服务器已停止")
    }

    /**
     * 向所有活跃的 SSE 客户端推送 notifications/tools/list_changed。
     * UE 实例连接状态变化时调用，触发客户端重新拉取工具列表。
     */
    fun sendToolsChangedNotification() {
        if (sseContexts.isEmpty()) return
        val json = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
        val sseData = "data: $json\n\n"
        val buf = Unpooled.copiedBuffer(sseData, StandardCharsets.UTF_8)
        val dead = mutableListOf<ChannelHandlerContext>()
        sseContexts.forEach { ctx ->
            if (ctx.channel().isActive) {
                ctx.writeAndFlush(DefaultHttpContent(buf.retainedDuplicate())).addListener { future ->
                    if (!future.isSuccess) log.warn("MCP SSE 通知发送失败 type=${future.cause()?.javaClass?.simpleName}")
                }
            } else {
                dead.add(ctx)
            }
        }
        sseContexts.removeAll(dead)
        buf.release()
        log.debug("已推送 notifications/tools/list_changed（活跃 SSE: ${sseContexts.size}）")
    }

    val isRunning: Boolean get() = channel?.isActive == true

    fun getProxyToken(): String = proxyToken
}

private const val MCP_SESSION_HEADER = "Mcp-Session-Id"

internal class McpHttpSession(val dispatcher: NexusMcpDispatcher, var lastUsed: Long, var active: Int = 1)

// 单次持锁完成查找与占用，初始化、长调用和 SSE 都不会被并发创建误删。
internal class McpHttpSessions(private val now: () -> Long = System::nanoTime) {
    private val sessions = mutableMapOf<String, McpHttpSession>()
    private val log = logger<McpHttpSessions>()
    val size: Int get() = synchronized(this) { sessions.size }

    @Synchronized fun create(id: String, dispatcher: NexusMcpDispatcher): McpHttpSession? {
        val time = now()
        if (sessions.size >= NexusMcpServer.MAX_SESSIONS) {
            sessions.entries.removeIf {
                val idle = it.value.active == 0 && time - it.value.lastUsed >= NexusMcpServer.SESSION_IDLE_NANOS
                if (idle) log.info("MCP 会话移除 session=${McpDiagnostics.session(it.key)} reason=idle_timeout")
                idle
            }
        }
        if (sessions.size >= NexusMcpServer.MAX_SESSIONS) return null
        return McpHttpSession(dispatcher, time).also { sessions[id] = it }
    }

    @Synchronized fun acquire(id: String): McpHttpSession? = sessions[id]?.also {
        it.active++
        it.lastUsed = now()
    }

    @Synchronized fun release(session: McpHttpSession) {
        session.active--
        session.lastUsed = now()
    }

    @Synchronized fun remove(id: String): Boolean = sessions.remove(id) != null
    @Synchronized fun clear() = sessions.clear()
}

/**
 * Netty HTTP 请求处理器（per-session 会话隔离）。
 *   POST /stream  → Streamable HTTP，通过 Mcp-Session-Id 头隔离会话
 *   GET  /sse     → SSE 长连接，静默保持，用于服务端通知推送
 */
private class McpHttpHandler(
    private val sessions: McpHttpSessions,
    private val unrealManager: UnrealInstanceManager,
    private val executor: ExecutorService,
    private val sseContexts: CopyOnWriteArrayList<ChannelHandlerContext>,
    private val proxyToken: String,
    private val requestCounter: AtomicLong,
    private val onSessionReady: () -> Unit,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    private val log = logger<McpHttpHandler>()

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val origin = request.headers().get(HttpHeaderNames.ORIGIN)
        if (!origin.isNullOrBlank()) {
            logRejection(ctx, request, 403, "browser_origin")
            sendResponse(ctx, HttpResponseStatus.FORBIDDEN, """{"error":"Browser Origin is not allowed"}""")
            return
        }
        val presented = NexusMcpAuth.extractBearer(request.headers().get(HttpHeaderNames.AUTHORIZATION))
        val st = NexusLinkSettings.instance.state
        if (st.requireAuth && !NexusMcpAuth.isTokenAccepted(presented, proxyToken, st.extraAuthTokens)) {
            logRejection(ctx, request, 401, "invalid_authorization")
            sendResponse(ctx, HttpResponseStatus.UNAUTHORIZED, """{"error":"Missing or invalid Authorization: Bearer token"}""")
            return
        }

        val path = request.uri().split("?").first()
        val method = request.method()
        when {
            path == "/stream" && method == HttpMethod.POST    -> handlePost(ctx, request)
            path == "/sse"    && method == HttpMethod.GET     -> handleGet(ctx, request)
            path == "/stream" && method == HttpMethod.GET     -> handleGet(ctx, request)
            path == "/stream" && method == HttpMethod.DELETE -> {
                val id = request.headers().get(MCP_SESSION_HEADER).orEmpty()
                if (sessions.remove(id)) {
                    log.info("MCP 会话移除 session=${McpDiagnostics.session(id)} reason=client_delete")
                    sendNoContent(ctx, HttpResponseStatus.NO_CONTENT)
                } else sendResponse(ctx, HttpResponseStatus.NOT_FOUND, """{"error":"Invalid or missing Mcp-Session-Id"}""")
            }
            else -> {
                logRejection(ctx, request, 404, "unsupported_endpoint")
                sendResponse(ctx, HttpResponseStatus.NOT_FOUND, """{"error":"Not Found"}""")
            }
        }
    }

    private fun handlePost(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val started = System.nanoTime()
        val requestId = requestCounter.incrementAndGet()
        val body = request.content().toString(StandardCharsets.UTF_8)
        if (body.isBlank()) {
            logRejection(ctx, request, 400, "empty_body")
            sendResponse(ctx, HttpResponseStatus.BAD_REQUEST, """{"error":"empty body"}""")
            return
        }

        val incomingSessionId = request.headers().get(MCP_SESSION_HEADER) ?: ""
        // 先尝试 JSON 解析取 method，避免工具参数含 "initialize" 子串时误判
        val msg = try { org.json.JSONObject(body) } catch (_: Exception) { null }
        val bIsInitialize = msg?.optString("method")?.let { it == "initialize" } ?: body.contains("\"initialize\"")
        val remote = McpDiagnostics.text(ctx.channel().remoteAddress().toString())
        val fields = "request=$requestId ${McpDiagnostics.fields(msg)}"

        // 在独立线程执行 dispatch，防止 WS 转发的同步等待阻塞 Netty IO 线程
        executor.submit {
            var session: McpHttpSession? = null
            try {
                val sessionId: String

                if (bIsInitialize) {
                    sessionId = UUID.randomUUID().toString()
                    session = sessions.create(sessionId, NexusMcpDispatcher(unrealManager, onSessionReady))
                    if (session == null) {
                        log.warn("MCP HTTP 请求拒绝 status=503 reason=session_limit remote=$remote $fields")
                        sendResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, """{"error":"too many active MCP sessions"}""", retryAfter = true)
                        return@submit
                    }
                    val params = msg?.optJSONObject("params")
                    val client = params?.optJSONObject("clientInfo")
                    log.info("MCP 新会话 session=${McpDiagnostics.session(sessionId)} previous=${McpDiagnostics.session(incomingSessionId)} client=${McpDiagnostics.text(client?.opt("name"))} version=${McpDiagnostics.text(client?.opt("version"))} protocol=${McpDiagnostics.text(params?.opt("protocolVersion"))} remote=$remote sessions=${sessions.size}")
                } else {
                    sessionId = incomingSessionId
                    session = sessions.acquire(sessionId)
                }
                if (session == null) {
                    val reason = if (incomingSessionId.isBlank()) "missing_session" else "unknown_session"
                    log.warn("MCP HTTP 请求拒绝 status=404 reason=$reason session=${McpDiagnostics.session(incomingSessionId)} remote=$remote $fields sessions=${sessions.size}；客户端需要重新 initialize")
                    sendResponse(ctx, HttpResponseStatus.NOT_FOUND, """{"error":"Invalid or missing Mcp-Session-Id"}""")
                    return@submit
                }

                val callFields = "session=${McpDiagnostics.session(sessionId)} $fields"
                if (msg?.optString("method") == "tools/call") log.info("MCP 请求开始 $callFields")
                val responseJson = session.dispatcher.dispatch(body)
                val timing = "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}"
                val failure = McpDiagnostics.failure(responseJson)
                if (failure != null) log.warn("MCP 请求失败 $callFields $failure $timing")
                else if (msg?.optString("method") in listOf("initialize", "notifications/initialized", "tools/list", "tools/call")) {
                    log.info("MCP 请求完成 $callFields $timing")
                }
                if (responseJson.isBlank()) {
                    sendNoContent(ctx, HttpResponseStatus.ACCEPTED, sessionId)
                } else {
                    sendResponse(ctx, HttpResponseStatus.OK, responseJson, "application/json", sessionId)
                }
            } catch (e: Exception) {
                log.warn("MCP dispatch 异常 $fields type=${e.javaClass.simpleName} elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}")
                sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, """{"error":"internal error"}""")
            } finally {
                session?.let { sessions.release(it) }
            }
        }
    }

    /**
     * GET /sse — SSE 长连接。
     * 静默保持连接，仅用于服务端推送通知（如 notifications/tools/list_changed）。
     * 每 SSE_KEEPALIVE_MS 写一行注释帧避免经反代/NAT idle 被断开。
     */
    private fun handleGet(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val started = System.nanoTime()
        val fields = "session=${McpDiagnostics.session(request.headers().get(MCP_SESSION_HEADER).orEmpty())} remote=${McpDiagnostics.text(ctx.channel().remoteAddress().toString())}"
        if (sseContexts.size >= NexusMcpServer.MAX_SSE_CLIENTS) {
            logRejection(ctx, request, 503, "sse_limit")
            sendResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, """{"error":"too many SSE clients"}""")
            return
        }
        val session = sessions.acquire(request.headers().get(MCP_SESSION_HEADER).orEmpty())
        ctx.pipeline().get(ReadTimeoutHandler::class.java)?.let { ctx.pipeline().remove(it) }
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
            set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            set(HttpHeaderNames.CONNECTION, "keep-alive")
            set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        }
        ctx.writeAndFlush(response)

        sseContexts.add(ctx)
        log.info("MCP SSE 已连接 $fields clients=${sseContexts.size}")

        // 周期心跳帧（复用 Netty channel event loop，避免额外线程）
        val keepaliveFuture: ScheduledFuture<*> = ctx.channel().eventLoop().scheduleAtFixedRate({
            if (!ctx.channel().isActive) return@scheduleAtFixedRate
            val buf = Unpooled.copiedBuffer(": keepalive\n\n", StandardCharsets.UTF_8)
            ctx.writeAndFlush(DefaultHttpContent(buf)).addListener { future ->
                if (!future.isSuccess) log.warn("MCP SSE 心跳发送失败 $fields type=${future.cause()?.javaClass?.simpleName}")
            }
        }, SSE_KEEPALIVE_MS, SSE_KEEPALIVE_MS, TimeUnit.MILLISECONDS)

        ctx.channel().closeFuture().addListener(ChannelFutureListener {
            session?.let { sessions.release(it) }
            keepaliveFuture.cancel(false)
            sseContexts.remove(ctx)
            log.info("MCP SSE 已断开 $fields elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}")
        })
    }

    companion object {
        private const val SSE_KEEPALIVE_MS = 20_000L
    }

    private fun sendResponse(
        ctx: ChannelHandlerContext,
        status: HttpResponseStatus,
        body: String,
        contentType: String = "application/json",
        sessionId: String? = null,
        retryAfter: Boolean = false,
    ) {
        val buf = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, contentType)
            set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes())
        }
        if (sessionId != null) response.headers().set(MCP_SESSION_HEADER, sessionId)
        if (retryAfter) response.headers().set(HttpHeaderNames.RETRY_AFTER, "1")
        ctx.writeAndFlush(response).addListener { future ->
            if (!future.isSuccess) log.warn("MCP 响应发送失败 status=${status.code()} session=${McpDiagnostics.session(sessionId.orEmpty())} type=${future.cause()?.javaClass?.simpleName}")
        }
    }

    private fun sendNoContent(ctx: ChannelHandlerContext, status: HttpResponseStatus, sessionId: String? = null) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        if (sessionId != null) response.headers().set(MCP_SESSION_HEADER, sessionId)
        ctx.writeAndFlush(response).addListener { future ->
            if (!future.isSuccess) log.warn("MCP 响应发送失败 status=${status.code()} session=${McpDiagnostics.session(sessionId.orEmpty())} type=${future.cause()?.javaClass?.simpleName}")
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.warn("MCP HTTP 异常 status=500 type=${cause.javaClass.simpleName} remote=${McpDiagnostics.text(ctx.channel().remoteAddress().toString())}")
        val payload = org.json.JSONObject().put("error", cause.message ?: "internal error").toString()
        sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, payload)
        ctx.close()
    }

    private fun logRejection(ctx: ChannelHandlerContext, request: FullHttpRequest, status: Int, reason: String) {
        log.warn("MCP HTTP 请求拒绝 status=$status reason=$reason http_method=${McpDiagnostics.text(request.method().name())} path=${McpDiagnostics.text(request.uri().substringBefore('?'))} remote=${McpDiagnostics.text(ctx.channel().remoteAddress().toString())} session=${McpDiagnostics.session(request.headers().get(MCP_SESSION_HEADER).orEmpty())}")
    }
}
