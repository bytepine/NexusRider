// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject
import java.util.ArrayDeque

/** 代理层失败分类（对应 UE `nexus/proxy_feedback` 的 category 参数）。 */
enum class ProxyFeedbackCategory(val wireValue: String) {
    TIMEOUT("proxy_timeout"),
    DISCONNECT("proxy_disconnect"),
    CONNECT_FAIL("proxy_connect_fail"),
}

data class ProxyFeedbackEvent(
    val category: ProxyFeedbackCategory,
    val tool: String? = null,
    val errorText: String? = null,
    val note: String? = null,
)

/**
 * 代理层失败事件的进程内环形缓冲。
 * 断连时先入队，待连上 UE 后由 flush 逐条经 `nexus/proxy_feedback` 上报。
 * 旧版 NexusLink（未实现该 method）返回 Method not found 后标记 unsupported，
 * 之后静默跳过，不再发送、不再入队——保证新代理连旧 UE 时零感知报错。
 */
class ProxyFeedbackBuffer {
    companion object {
        private const val MAX_SIZE = 50
    }

    private val queue = ArrayDeque<ProxyFeedbackEvent>()
    @Volatile
    var isUnsupported: Boolean = false
        private set

    @Synchronized
    fun enqueue(event: ProxyFeedbackEvent) {
        if (isUnsupported) return
        queue.addLast(event)
        while (queue.size > MAX_SIZE) queue.removeFirst()
    }

    /** 取出并清空当前所有待发事件。 */
    @Synchronized
    fun drain(): List<ProxyFeedbackEvent> {
        val events = queue.toList()
        queue.clear()
        return events
    }

    /** 未 flush 成功的事件重新放回队首，供下次连上后重试。 */
    @Synchronized
    fun requeue(event: ProxyFeedbackEvent) {
        if (isUnsupported) return
        queue.addFirst(event)
        while (queue.size > MAX_SIZE) queue.removeLast()
    }

    @Synchronized
    fun hasPending(): Boolean = queue.isNotEmpty()

    /** 标记本会话 UE 不支持 proxy_feedback：清空缓冲，停止后续上报。 */
    @Synchronized
    fun markUnsupported() {
        isUnsupported = true
        queue.clear()
    }
}

/** 判定 WS 响应是否为「方法未找到」（UE 未实现 nexus/proxy_feedback，即旧版 NexusLink）。 */
fun isMethodNotFoundError(response: JSONObject?): Boolean {
    val err = response?.optJSONObject("error") ?: return false
    if (err.optInt("code", 0) == -32601) return true
    val message = err.optString("message", "")
    return message.contains("Method not found") || message.contains("方法未找到")
}
