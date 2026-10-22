// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject
import org.json.JSONArray

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
    val cacheScope: String,
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

    private val destructiveCaps = setOf("DeleteAsset", "RenameAsset")
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
        if (cap == "SubmitFeedback" || cap == "SearchCapabilities") return false
        if (cap.startsWith("Get") || cap.startsWith("List") || cap.startsWith("Search")) return false
        return true
    }

    fun isDestructive(cap: String, inner: JSONObject): Boolean {
        if (cap in destructiveCaps) return true
        if (cap == "ControlPie") {
            return inner.optString("Action").lowercase() in stopPie
        }
        if (cap.startsWith("Manage")) {
            val ops = inner.optJSONArray("Operations") ?: return false
            for (i in 0 until ops.length()) {
                val action = ops.optJSONObject(i)?.optString("Action")?.lowercase() ?: continue
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
        cap == "GetOutputLog" || cap == "CaptureViewport" ||
            cap.startsWith("ListRuntime") || cap.startsWith("GetRuntime")

    fun isDurableRead(cap: String): Boolean {
        if (isWriteCapability(cap) || isVolatileCap(cap)) return false
        return cap.startsWith("Search") || cap.startsWith("GetAsset") || cap.startsWith("GetEditor") ||
            cap == "GetGameplayTags" || cap == "GetAssetRefs" || cap == "GetAssetLuaBinding"
    }

    fun defaultTtlMs(cap: String): Long = when {
        isVolatileCap(cap) -> DEFAULT_VOLATILE_TTL_MS
        cap.startsWith("Search") -> DEFAULT_SEARCH_TTL_MS
        else -> DEFAULT_READ_TTL_MS
    }

    fun extractIdentity(args: JSONObject): String {
        for (key in listOf("AssetPath", "ActorName", "WidgetName", "LuaPath", "ScriptPath")) {
            val v = args.optString(key, "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    fun extractSections(args: JSONObject): List<String>? {
        val raw = args.optJSONArray("Sections") ?: return null
        val out = mutableListOf<String>()
        for (i in 0 until raw.length()) {
            raw.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    fun sectionsCovered(cached: List<String>?, requested: List<String>?): Boolean {
        if (cached == null) return false
        if (cached.contains("All")) return true
        if (requested.isNullOrEmpty()) return false
        return requested.all { it in cached }
    }

    fun explicitTargetPort(args: JSONObject?): Int? {
        val port = (args?.opt("targetPort") as? Number)?.toDouble() ?: return null
        return if (port.isFinite() && port >= 1024 && port <= 65535 && port % 1 == 0.0) port.toInt() else null
    }

    private fun stableJson(value: Any?): String = when (value) {
        is JSONObject -> value.keySet().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + stableJson(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { stableJson(value.get(it)) }
        else -> JSONObject.valueToString(value)
    }

    fun parseCall(toolName: String, args: JSONObject?, instanceScope: String = ""): CallInfo {
        val top = args ?: JSONObject()
        val targetPort = explicitTargetPort(top)
        val cacheScope = if (targetPort != null) LanHost.instanceKey(top.optString("targetHost", ""), targetPort) else instanceScope
        var capability = toolName
        // 仅剥离顶层路由字段；能力内的同名字段仍参与缓存键。
        var inner = JSONObject(top.toString()).apply { remove("targetPort"); remove("targetHost") }
        var isBatch = false
        if (toolName == "CallCapability") {
            if (top.optJSONArray("Calls") != null) {
                isBatch = true
                capability = "CallCapability.Calls"
            } else {
                capability = top.optString("Capability", "")
                inner = top.optJSONObject("Arguments") ?: JSONObject()
            }
        }
        val identity = extractIdentity(inner)
        val sections = extractSections(inner)
        val exactKey = JSONArray(listOf(cacheScope, toolName, capability, stableJson(inner))).toString()
        val identityKey = JSONArray(listOf(cacheScope, capability, identity)).toString()
        return CallInfo(
            toolName, capability, inner, identity, sections,
            exactKey, identityKey, cacheScope,
            isBatch || isWriteCapability(capability), isBatch,
        )
    }

    fun extractTtlMeta(result: JSONObject, cap: String, nowMs: Long): TtlMeta {
        val src = resultTextObject(result) ?: result
        var snapshotAtMs = nowMs
        var snapshotAtIso = java.time.Instant.ofEpochMilli(nowMs).toString()
        val snap = src.optString("SnapshotAt", "")
        if (snap.isNotEmpty()) {
            try {
                snapshotAtMs = java.time.Instant.parse(snap).toEpochMilli()
                snapshotAtIso = snap
            } catch (_: Exception) { /* 保留 now */ }
        }
        var ttlMs = defaultTtlMs(cap)
        if (src.has("TtlSeconds")) {
            val sec = src.optDouble("TtlSeconds", -1.0)
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
