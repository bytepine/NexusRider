// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject

/** 写门控模式（与 docs/proxy-session.md 对齐）。 */
enum class WriteGateMode { OFF, DESTRUCTIVE, ALL }

enum class CacheKind { HIT, SECTION_HIT }

enum class GateDecision { ALLOW, DENY, ALWAYS }

data class CallInfo(
    val toolName: String,
    val capability: String,
    val innerArgs: JSONObject,
    val identity: String,
    val sections: List<String>?,
    val exactKey: String,
    val identityKey: String,
    val isWrite: Boolean,
    val isBatch: Boolean,
)

data class TtlMeta(
    val snapshotAtMs: Long,
    val ttlMs: Long,
    val snapshotAtIso: String,
)

object ProxySessionPolicy {
    const val SECTION_WINDOW_MS = 30_000L
    const val DEFAULT_VOLATILE_TTL_MS = 8_000L
    const val DEFAULT_READ_TTL_MS = 30_000L
    const val DEFAULT_SEARCH_TTL_MS = 60_000L
    const val MAX_CACHE_ENTRIES = 64
    const val OFFLOAD_CHARS = 48_000
    const val GATE_TIMEOUT_MS = 120_000L

    const val DEGRADED_NOTE =
        "UE editor unreachable (compile/restart?). Serving last snapshot. Do not loop list_unreal_instances."

    private val destructiveCaps = setOf("delete_asset", "rename_asset")
    private val stopPie = setOf("stop", "end", "quit", "endplay", "end_play", "end-play")

    fun parseWriteGate(raw: String?): WriteGateMode = when (raw) {
        "off" -> WriteGateMode.OFF
        "all" -> WriteGateMode.ALL
        else -> WriteGateMode.DESTRUCTIVE
    }

    fun gateWire(mode: WriteGateMode): String = when (mode) {
        WriteGateMode.OFF -> "off"
        WriteGateMode.ALL -> "all"
        WriteGateMode.DESTRUCTIVE -> "destructive"
    }

    fun isWriteCapability(cap: String): Boolean {
        if (cap.isEmpty()) return false
        if (cap == "submit_feedback" || cap == "search_capabilities") return false
        if (cap.startsWith("get_") || cap.startsWith("list_") || cap.startsWith("search_")) return false
        return true
    }

    fun isDestructive(cap: String, inner: JSONObject): Boolean {
        if (cap in destructiveCaps) return true
        if (cap == "control_pie") {
            return inner.optString("action").lowercase() in stopPie
        }
        if (cap.startsWith("manage_")) {
            val ops = inner.optJSONArray("operations") ?: return false
            for (i in 0 until ops.length()) {
                val action = ops.optJSONObject(i)?.optString("action")?.lowercase() ?: continue
                if (action.contains("delete") || action.contains("remove") || action == "destroy") return true
            }
        }
        return false
    }

    fun needsGate(mode: WriteGateMode, cap: String, inner: JSONObject): Boolean = when (mode) {
        WriteGateMode.OFF -> false
        WriteGateMode.ALL -> isWriteCapability(cap)
        WriteGateMode.DESTRUCTIVE -> isDestructive(cap, inner)
    }

    fun isVolatileCap(cap: String): Boolean =
        cap == "get_output_log" || cap == "capture_viewport" ||
            cap.startsWith("list_runtime_") || cap.startsWith("get_runtime_")

    fun isDurableRead(cap: String): Boolean {
        if (isWriteCapability(cap) || isVolatileCap(cap)) return false
        return cap.startsWith("search_") || cap.startsWith("get_asset_") || cap.startsWith("get_editor_") ||
            cap == "get_gameplay_tags" || cap == "get_asset_refs" || cap == "get_asset_lua_binding"
    }

    fun defaultTtlMs(cap: String): Long = when {
        isVolatileCap(cap) -> DEFAULT_VOLATILE_TTL_MS
        cap.startsWith("search_") -> DEFAULT_SEARCH_TTL_MS
        else -> DEFAULT_READ_TTL_MS
    }

    fun extractIdentity(args: JSONObject): String {
        for (key in listOf("assetPath", "actorName", "widgetName", "luaPath", "scriptPath")) {
            val v = args.optString(key, "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    fun extractSections(args: JSONObject): List<String>? {
        val raw = args.optJSONArray("sections") ?: return null
        val out = mutableListOf<String>()
        for (i in 0 until raw.length()) {
            raw.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    fun sectionsCovered(cached: List<String>?, requested: List<String>?): Boolean {
        if (cached == null) return false
        if (cached.contains("all")) return true
        if (requested.isNullOrEmpty()) return false
        return requested.all { it in cached }
    }

    fun parseCall(toolName: String, args: JSONObject?): CallInfo {
        val top = args ?: JSONObject()
        var capability = toolName
        var inner = top
        var isBatch = false
        if (toolName == "call_capability") {
            if (top.has("calls")) {
                isBatch = true
                capability = "call_capability.calls"
            } else {
                capability = top.optString("capability", "")
                inner = top.optJSONObject("arguments") ?: JSONObject()
            }
        }
        val identity = extractIdentity(inner)
        val sections = extractSections(inner)
        val exactKey = "$toolName|$capability|${inner.toString()}"
        return CallInfo(
            toolName, capability, inner, identity, sections,
            exactKey, "$capability|$identity",
            isBatch || isWriteCapability(capability), isBatch,
        )
    }

    fun extractTtlMeta(result: JSONObject, cap: String, nowMs: Long): TtlMeta {
        val src = resultTextObject(result) ?: result
        var snapshotAtMs = nowMs
        var snapshotAtIso = java.time.Instant.ofEpochMilli(nowMs).toString()
        val snap = src.optString("_snapshotAt", "")
        if (snap.isNotEmpty()) {
            try {
                snapshotAtMs = java.time.Instant.parse(snap).toEpochMilli()
                snapshotAtIso = snap
            } catch (_: Exception) { /* 保留 now */ }
        }
        var ttlMs = defaultTtlMs(cap)
        if (src.has("_ttl_seconds")) {
            val sec = src.optDouble("_ttl_seconds", -1.0)
            if (sec > 0) ttlMs = (sec * 1000).toLong()
        }
        return TtlMeta(snapshotAtMs, ttlMs, snapshotAtIso)
    }

    fun resultTextObject(result: JSONObject): JSONObject? {
        val content = result.optJSONArray("content") ?: return null
        if (content.length() == 0) return null
        val text = content.optJSONObject(0)?.optString("text") ?: return null
        return try { JSONObject(text) } catch (_: Exception) { null }
    }

    fun injectProxyMeta(result: JSONObject, meta: JSONObject): JSONObject {
        val clone = JSONObject(result.toString())
        val inner = resultTextObject(clone)
        if (inner != null) {
            val prev = inner.optJSONObject("_proxy") ?: JSONObject()
            meta.keys().forEach { prev.put(it, meta.get(it)) }
            inner.put("_proxy", prev)
            clone.getJSONArray("content").getJSONObject(0).put("text", inner.toString())
            return clone
        }
        val prev = clone.optJSONObject("_proxy") ?: JSONObject()
        meta.keys().forEach { prev.put(it, meta.get(it)) }
        clone.put("_proxy", prev)
        return clone
    }

    fun contentTextLength(result: JSONObject): Int =
        result.optJSONArray("content")?.optJSONObject(0)?.optString("text")?.length ?: 0
}
