// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

/**
 * 三端协议时序。数值由工作区 Script/check_proxy_contract.py 对账，改动须三端一起改。
 * 不引用 IntelliJ，供纯 JUnit 使用。
 */
object ProxyTiming {
    const val SCAN_PORT_START = 45000
    const val SCAN_PORT_END = 45100
    const val SCAN_CONCURRENCY = 20
    const val PROBE_MAX_BYTES = 65_536
    const val TOOLS_CALL_TIMEOUT_MS = 120_000L
    const val FULL_SCAN_EVERY_N_TICKS = 6
    const val WS_KEEPALIVE_BUSY_MS = 5_000L
    const val WS_KEEPALIVE_IDLE_MS = 15_000L
}

enum class MaintainAction {
    /** 有挂起请求，连接正在被使用，本轮不做探测。 */
    SKIP,
    /** 只探测当前端口。 */
    PROBE,
    /** 全端口扫描，并把倒计时重置为 FULL_SCAN_EVERY_N_TICKS。 */
    FULL_SCAN,
}

data class MaintainDecision(val action: MaintainAction, val nextCountdown: Int)

/**
 * 决定本轮维护动作。
 * @param wsOpen 长连接已打开且已绑定端口（connectedPort > 0）。
 */
fun decideMaintain(wsOpen: Boolean, pending: Int, countdown: Int): MaintainDecision {
    if (wsOpen && pending > 0) {
        return MaintainDecision(MaintainAction.SKIP, countdown)
    }
    if (wsOpen && countdown > 0) {
        return MaintainDecision(MaintainAction.PROBE, countdown - 1)
    }
    return MaintainDecision(MaintainAction.FULL_SCAN, ProxyTiming.FULL_SCAN_EVERY_N_TICKS)
}
