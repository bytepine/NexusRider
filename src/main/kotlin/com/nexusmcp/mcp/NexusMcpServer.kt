// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.diagnostic.logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * nexus-rider 独立 MCP HTTP 服务器（per-session 会话隔离）。
 * 同时支持两种 MCP 传输协议：
 *   POST /stream   → Streamable HTTP，通过 Mcp-Session-Id 头隔离会话
 *   GET  /sse      → SSE 长连接，仅用于服务端推送通知
 *   OPTIONS /stream, OPTIONS /sse → CORS 预检
 *
 * 不依赖 Rider 内置 MCP Server，可独立运行。
 */
class NexusMcpServer(private val unrealManager: UnrealInstanceManager) {

    private val log = logger<NexusMcpServer>()

    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null

    /** 用于执行阻塞 dispatch（如 WS 转发等），避免阻塞 Netty IO 线程。 */
    private var dispatchExecutor: ExecutorService? = null

    /** 活跃的 SSE 客户端连接，用于推送 MCP 服务端通知。 */
    private val sseContexts = CopyOnWriteArrayList<ChannelHandlerContext>()

    /** HTTP 通道的 per-session Dispatcher 表（按 Mcp-Session-Id 索引）。 */
    val httpSessions = ConcurrentHashMap<String, NexusMcpDispatcher>()

    var port: Int = 0
        private set

    fun start(port: Int): Boolean {
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
                .localAddress(InetSocketAddress("127.0.0.1", port))
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().apply {
                            addLast(HttpServerCodec())
                            addLast(HttpObjectAggregator(1024 * 1024))
                            addLast(McpHttpHandler(sessions, mgr, executor, sse) { sendToolsChangedNotification() })
                        }
                    }
                })

            channel = bootstrap.bind().sync().channel()
            log.info("MCP HTTP 服务器已启动  stream: http://127.0.0.1:$port/stream  sse: http://127.0.0.1:$port/sse")
            true
        } catch (e: Exception) {
            log.error("MCP 服务器启动失败：${e.message}")
            stop()
            false
        }
    }

    fun stop() {
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
                ctx.writeAndFlush(DefaultHttpContent(buf.retainedDuplicate()))
            } else {
                dead.add(ctx)
            }
        }
        sseContexts.removeAll(dead)
        buf.release()
        log.info("已推送 notifications/tools/list_changed（活跃 SSE: ${sseContexts.size}）")
    }

    val isRunning: Boolean get() = channel?.isActive == true
}

private const val MCP_SESSION_HEADER = "Mcp-Session-Id"

/**
 * Netty HTTP 请求处理器（per-session 会话隔离）。
 *   POST /stream  → Streamable HTTP，通过 Mcp-Session-Id 头隔离会话
 *   GET  /sse     → SSE 长连接，静默保持，用于服务端通知推送
 *   OPTIONS /stream、OPTIONS /sse → CORS 预检
 */
private class McpHttpHandler(
    private val sessions: ConcurrentHashMap<String, NexusMcpDispatcher>,
    private val unrealManager: UnrealInstanceManager,
    private val executor: ExecutorService,
    private val sseContexts: CopyOnWriteArrayList<ChannelHandlerContext>,
    private val onSessionReady: () -> Unit,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    private val log = com.intellij.openapi.diagnostic.logger<McpHttpHandler>()

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val path = request.uri().split("?").first()
        val method = request.method()
        when {
            path == "/stream" && method == HttpMethod.POST    -> handlePost(ctx, request)
            path == "/sse"    && method == HttpMethod.GET     -> handleGet(ctx)
            path == "/stream" && method == HttpMethod.GET     -> handleGet(ctx)
            method == HttpMethod.OPTIONS && (path == "/stream" || path == "/sse") -> handleOptions(ctx)
            else -> sendResponse(ctx, HttpResponseStatus.NOT_FOUND, """{"error":"Not Found"}""")
        }
    }

    private fun handlePost(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val body = request.content().toString(StandardCharsets.UTF_8)
        if (body.isBlank()) {
            sendResponse(ctx, HttpResponseStatus.BAD_REQUEST, """{"error":"empty body"}""")
            return
        }

        val incomingSessionId = request.headers().get(MCP_SESSION_HEADER) ?: ""
        val bIsInitialize = body.contains("\"initialize\"")

        // 在独立线程执行 dispatch，防止 WS 转发的同步等待阻塞 Netty IO 线程
        executor.submit {
            try {
                val sessionId: String
                val dispatcher: NexusMcpDispatcher

                if (bIsInitialize) {
                    sessionId = UUID.randomUUID().toString()
                    dispatcher = NexusMcpDispatcher(unrealManager, onSessionReady)
                    sessions[sessionId] = dispatcher
                    // 清理已关闭的会话
                    sessions.entries.removeIf { it.value.state == McpSessionState.WaitingForInitialize && it.key != sessionId }
                    log.info("新建 MCP 会话: $sessionId（当前 ${sessions.size} 个活跃会话）")
                } else if (incomingSessionId.isNotBlank() && sessions.containsKey(incomingSessionId)) {
                    sessionId = incomingSessionId
                    dispatcher = sessions[sessionId]!!
                } else {
                    log.warn("POST /stream 缺少或无效 Mcp-Session-Id: $incomingSessionId")
                    sendResponse(ctx, HttpResponseStatus.NOT_FOUND, """{"error":"Invalid or missing Mcp-Session-Id"}""")
                    return@submit
                }

                val responseJson = dispatcher.dispatch(body)
                if (responseJson.isBlank()) {
                    sendNoContent(ctx, HttpResponseStatus.ACCEPTED, sessionId)
                } else {
                    sendResponse(ctx, HttpResponseStatus.OK, responseJson, "application/json", sessionId)
                }
            } catch (e: Exception) {
                log.error("dispatch 异常: ${e.message}", e)
                sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, """{"error":"internal error"}""")
            }
        }
    }

    /**
     * GET /sse — SSE 长连接。
     * 静默保持连接，仅用于服务端推送通知（如 notifications/tools/list_changed）。
     */
    private fun handleGet(ctx: ChannelHandlerContext) {
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
            set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            set(HttpHeaderNames.CONNECTION, "keep-alive")
            set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        }
        addCorsHeaders(response)
        ctx.writeAndFlush(response)

        sseContexts.add(ctx)
        ctx.channel().closeFuture().addListener(ChannelFutureListener { sseContexts.remove(ctx) })
    }

    private fun handleOptions(ctx: ChannelHandlerContext) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER
        )
        addCorsHeaders(response)
        ctx.writeAndFlush(response)
    }

    private fun sendResponse(
        ctx: ChannelHandlerContext,
        status: HttpResponseStatus,
        body: String,
        contentType: String = "application/json",
        sessionId: String? = null,
    ) {
        val buf = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, contentType)
            set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes())
        }
        if (sessionId != null) response.headers().set(MCP_SESSION_HEADER, sessionId)
        addCorsHeaders(response)
        ctx.writeAndFlush(response)
    }

    private fun sendNoContent(ctx: ChannelHandlerContext, status: HttpResponseStatus, sessionId: String? = null) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        if (sessionId != null) response.headers().set(MCP_SESSION_HEADER, sessionId)
        addCorsHeaders(response)
        ctx.writeAndFlush(response)
    }

    private fun addCorsHeaders(response: HttpResponse) {
        response.headers().apply {
            set("Access-Control-Allow-Origin", "*")
            set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            set("Access-Control-Allow-Headers", "Content-Type, $MCP_SESSION_HEADER")
            set("Access-Control-Expose-Headers", MCP_SESSION_HEADER)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, """{"error":"${cause.message}"}""")
        ctx.close()
    }
}
