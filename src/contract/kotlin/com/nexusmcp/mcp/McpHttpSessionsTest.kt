// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal fun testHttpSessions() {
    val clock = AtomicLong()
    val sessions = McpHttpSessions(clock::get)
    val manager = UnrealInstanceManager()
    fun dispatcher() = NexusMcpDispatcher(manager)
    val executor = Executors.newFixedThreadPool(2)
    try {
        // 握手尚未结束时并发创建另一会话，两个 WaitingForInitialize 都必须保留。
        val first = executor.submit<McpHttpSession?> { sessions.create("a", dispatcher()) }
        val second = executor.submit<McpHttpSession?> { sessions.create("b", dispatcher()) }
        val a = first.get(2, TimeUnit.SECONDS)!!
        val b = second.get(2, TimeUnit.SECONDS)!!
        check(a.dispatcher.state == McpSessionState.WaitingForInitialize)
        check(sessions.acquire("a") === a && sessions.acquire("b") === b)
        sessions.release(a)
        sessions.release(b)
        repeat(NexusMcpServer.MAX_SESSIONS - 2) {
            sessions.release(sessions.create("idle-$it", dispatcher())!!)
        }
        check(sessions.create("overflow", dispatcher()) == null) { "full active table must reject newcomers" }
        check(sessions.size == NexusMcpServer.MAX_SESSIONS)

        clock.addAndGet(NexusMcpServer.SESSION_IDLE_NANOS)
        // A 仍在初始化，B 持有长调用/SSE；另一个旧会话刚收到普通请求。
        val recent = sessions.acquire("idle-0")!!
        sessions.release(recent)
        val next = sessions.create("next", dispatcher())!!
        check(sessions.size == 4)
        check(sessions.acquire("a") === a && sessions.acquire("b") === b)
        sessions.release(a)
        sessions.release(b)
        check(sessions.acquire("idle-0") === recent && sessions.acquire("idle-1") == null)
        sessions.release(recent)
        sessions.release(a)
        sessions.release(b)
        check(a.active == 0 && b.active == 0 && a.lastUsed == clock.get())
        check(sessions.remove("next") && !sessions.remove("next"))
        sessions.release(next) // 主动删除不取消已经执行中的请求，也不恢复旧 ID。
        check(sessions.acquire("next") == null && sessions.acquire("a") === a)
        sessions.release(a)
        sessions.clear()
        check(sessions.size == 0 && sessions.acquire("a") == null)
        println("HTTP session initialization, capacity, activity and deletion contract OK")
    } finally {
        executor.shutdownNow()
    }
}
