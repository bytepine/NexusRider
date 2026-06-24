// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject

/** WebSocket JSON-RPC 请求结果（区分断连 vs 超时，避免误报「未连接」）。 */
enum class WsRequestStatus {
    OK,
    DISCONNECTED,
    TIMEOUT,
}

data class WsRequestOutcome(
    val status: WsRequestStatus,
    val response: JSONObject? = null,
) {
    companion object {
        fun ok(response: JSONObject) = WsRequestOutcome(WsRequestStatus.OK, response)
        fun disconnected() = WsRequestOutcome(WsRequestStatus.DISCONNECTED)
        fun timeout() = WsRequestOutcome(WsRequestStatus.TIMEOUT)
    }
}
