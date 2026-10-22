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

    private val destructiveCaps = setOf("deleteasset", "renameasset")
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

    // 仅供本地策略判断；保留原始工具名和参数，原样转发给对应版本。
    private fun policyName(name: String): String = name.replace("_", "").lowercase()

    private fun policyField(args: JSONObject, key: String, legacy: Boolean = false): Any? {
        val camel = key.replaceFirstChar { it.lowercase() }
        val primary = if (legacy) camel else key
        return if (args.has(primary)) args.opt(primary) else args.opt(if (legacy) key else camel)
    }

    fun isWriteCapability(cap: String): Boolean {
        if (cap.isEmpty()) return false
        val name = policyName(cap)
        if (name == "submitfeedback" || name == "searchcapabilities") return false
        if (name.startsWith("get") || name.startsWith("list") || name.startsWith("search")) return false
        return true
    }

    fun isDestructive(cap: String, inner: JSONObject): Boolean {
        val name = policyName(cap)
        val legacy = cap.contains('_')
        if (name in destructiveCaps) return true
        if (name == "controlpie") {
            return (policyField(inner, "Action", legacy) as? String)?.lowercase() in stopPie
        }
        if (name.startsWith("manage")) {
            val ops = policyField(inner, "Operations", legacy) as? JSONArray ?: return false
            for (i in 0 until ops.length()) {
                val op = ops.optJSONObject(i) ?: continue
                val action = (policyField(op, "Action", legacy) as? String)?.lowercase() ?: continue
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

    fun isVolatileCap(cap: String): Boolean {
        val name = policyName(cap)
        return name == "getoutputlog" || name == "captureviewport" ||
            name.startsWith("listruntime") || name.startsWith("getruntime")
    }

    fun isDurableRead(cap: String): Boolean {
        if (isWriteCapability(cap) || isVolatileCap(cap)) return false
        val name = policyName(cap)
        return name.startsWith("search") || name.startsWith("getasset") || name.startsWith("geteditor") ||
            name == "getgameplaytags" || name == "getassetrefs" || name == "getassetluabinding"
    }

    fun defaultTtlMs(cap: String): Long = when {
        isVolatileCap(cap) -> DEFAULT_VOLATILE_TTL_MS
        policyName(cap).startsWith("search") -> DEFAULT_SEARCH_TTL_MS
        else -> DEFAULT_READ_TTL_MS
    }

    fun extractIdentity(args: JSONObject, legacy: Boolean = false): String {
        for (key in listOf("AssetPath", "ActorName", "WidgetName", "LuaPath", "ScriptPath")) {
            val v = policyField(args, key, legacy) as? String ?: continue
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    fun extractSections(args: JSONObject, legacy: Boolean = false): List<String>? {
        val raw = policyField(args, "Sections", legacy) as? JSONArray ?: return null
        val out = mutableListOf<String>()
        for (i in 0 until raw.length()) {
            raw.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    fun sectionsCovered(cached: List<String>?, requested: List<String>?): Boolean {
        if (cached == null) return false
        if (cached.any { it.equals("All", ignoreCase = true) }) return true
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
        if (policyName(toolName) == "callcapability") {
            val legacy = toolName.contains('_')
            if (policyField(top, "Calls", legacy) is JSONArray) {
                isBatch = true
                capability = "$toolName.Calls"
            } else {
                capability = policyField(top, "Capability", legacy) as? String ?: ""
                inner = policyField(top, "Arguments", legacy) as? JSONObject ?: JSONObject()
            }
        }
        val legacy = capability.contains('_')
        val identity = extractIdentity(inner, legacy)
        val sections = extractSections(inner, legacy)
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
        val snap = src.optString("SnapshotAt", src.optString("_snapshotAt", ""))
        if (snap.isNotEmpty()) {
            try {
                snapshotAtMs = java.time.Instant.parse(snap).toEpochMilli()
                snapshotAtIso = snap
            } catch (_: Exception) { /* 保留 now */ }
        }
        var ttlMs = defaultTtlMs(cap)
        val ttlKey = if (src.has("TtlSeconds")) "TtlSeconds" else "_ttl_seconds"
        if (src.has(ttlKey)) {
            val sec = src.optDouble(ttlKey, -1.0)
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
