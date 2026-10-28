// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import org.json.JSONObject
import com.nexusmcp.mcp.nexusLogger as logger
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CacheEntry(
    val exactKey: String,
    val identityKey: String,
    val cacheScope: String,
    val capability: String,
    val identity: String,
    val sections: List<String>?,
    val result: JSONObject,
    val storedAtMs: Long,
    val snapshotAtIso: String,
    val ttlMs: Long,
)

data class CacheHit(
    val result: JSONObject,
    val kind: CacheKind,
    val snapshotAt: String,
)

/**
 * 进程（项目）级会话枢纽：TTL 缓存、Pause、写门控。
 */
class ProxySessionHub {
    private val log = logger<ProxySessionHub>()

    @Volatile var writeGate: WriteGateMode = WriteGateMode.DESTRUCTIVE
    @Volatile var gatePrompter: ((CallInfo) -> GateDecision)? = null

    private val pauseLock = ReentrantLock()
    private val pauseCondition = pauseLock.newCondition()
    @Volatile private var paused = false
    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > ProxySessionPolicy.MAX_CACHE_ENTRIES
    }
    private val cacheLock = Any()
    private val offloadDir = File(offloadRoot, UUID.randomUUID().toString())

    init {
        pruneOffload()
    }

    fun isPaused(): Boolean = paused

    fun setPaused(value: Boolean) {
        pauseLock.withLock {
            if (paused == value) return
            paused = value
            log.info("Agent 转发暂停状态 paused=$value")
            if (!value) pauseCondition.signalAll()
        }
    }

    fun waitIfPaused() {
        pauseLock.withLock {
            while (paused) pauseCondition.await()
        }
    }

    fun confirmIfNeeded(info: CallInfo): GateDecision {
        if (!ProxySessionPolicy.needsGate(writeGate, info.capability, info.innerArgs)) return GateDecision.ALLOW
        val prompter = gatePrompter ?: return GateDecision.ALLOW
        log.info("等待写入确认 capability=${McpDiagnostics.text(info.capability)} timeout_ms=${ProxySessionPolicy.GATE_TIMEOUT_MS}")
        val future = CompletableFuture.supplyAsync { prompter(info) }
        return try {
            future.get(ProxySessionPolicy.GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS).also {
                log.info("写入确认结束 capability=${McpDiagnostics.text(info.capability)} decision=$it")
            }
        } catch (_: TimeoutException) {
            log.warn("写入确认超时 capability=${McpDiagnostics.text(info.capability)} timeout_ms=${ProxySessionPolicy.GATE_TIMEOUT_MS}")
            // 超时判 DENY 后要取消 future，否则弹窗任务会一直占着线程池并可能事后再弹
            future.cancel(true)
            GateDecision.DENY
        } catch (e: Exception) {
            log.warn("写入确认失败 capability=${McpDiagnostics.text(info.capability)} type=${e.javaClass.simpleName}")
            future.cancel(true)
            GateDecision.DENY
        }
    }

    fun lookupFresh(info: CallInfo, nowMs: Long): CacheHit? {
        if (info.isWrite || info.isBatch) return null
        return lookup(info, nowMs, allowExpired = false)
    }

    fun lookupDegraded(info: CallInfo, nowMs: Long): CacheHit? {
        if (info.isWrite || info.isBatch) return null
        return lookup(info, nowMs, allowExpired = true)
    }

    fun store(info: CallInfo, result: JSONObject, nowMs: Long): JSONObject {
        var stored = JSONObject(result.toString())
        // 批量操作可能部分成功，先失效目标实例，避免保留旧快照。
        if (info.isBatch) {
            invalidateIdentity("", info.cacheScope)
        } else if (info.isWrite && info.identity.isNotEmpty()) {
            invalidateIdentity(info.identity, info.cacheScope)
        }
        if (stored.optBoolean("isError", false)) return stored
        val ttl = ProxySessionPolicy.extractTtlMeta(stored, info.capability, nowMs)
        val content = stored.optJSONArray("content")
        val hasImage = content != null && (0 until content.length()).any { content.optJSONObject(it)?.optString("type") == "image" }
        if (ProxySessionPolicy.contentTextLength(stored) > ProxySessionPolicy.OFFLOAD_CHARS && !hasImage) {
            stored = offload(stored)
        }
        if (!info.isWrite && !info.isBatch && info.capability.isNotEmpty() && !hasImage) {
            synchronized(cacheLock) {
                cache[info.exactKey] = CacheEntry(
                    info.exactKey, info.identityKey, info.cacheScope, info.capability, info.identity, info.sections,
                    JSONObject(stored.toString()), nowMs, ttl.snapshotAtIso, ttl.ttlMs,
                )
            }
        }
        return stored
    }

    private fun lookup(info: CallInfo, nowMs: Long, allowExpired: Boolean): CacheHit? {
        synchronized(cacheLock) {
            cache[info.exactKey]?.let { exact ->
                if (usable(exact, nowMs, allowExpired, info.capability)) {
                    return CacheHit(JSONObject(exact.result.toString()), CacheKind.HIT, exact.snapshotAtIso)
                }
            }
            if (!info.identity.isEmpty() && info.sections != null) {
                for (e in cache.values) {
                    if (e.identityKey != info.identityKey) continue
                    if (!ProxySessionPolicy.sectionsCovered(e.sections, info.sections)) continue
                    val withinWindow = nowMs - e.storedAtMs <= ProxySessionPolicy.SECTION_WINDOW_MS
                    if (!withinWindow && !allowExpired) continue
                    if (!usable(e, nowMs, allowExpired || withinWindow, info.capability)) continue
                    return CacheHit(JSONObject(e.result.toString()), CacheKind.SECTION_HIT, e.snapshotAtIso)
                }
            }
        }
        return null
    }

    private fun usable(entry: CacheEntry, nowMs: Long, allowExpired: Boolean, cap: String): Boolean {
        if (nowMs - entry.storedAtMs <= entry.ttlMs) return true
        if (!allowExpired) return false
        return ProxySessionPolicy.isDurableRead(cap)
    }

    private fun invalidateIdentity(identity: String, cacheScope: String) {
        synchronized(cacheLock) {
            val keys = cache.filter { it.value.cacheScope == cacheScope && (identity.isEmpty() || it.value.identity == identity) }.keys.toList()
            keys.forEach { cache.remove(it) }
        }
    }

    private fun offload(result: JSONObject): JSONObject {
        pruneOffload()
        val dir = offloadDir
        if (!dir.isDirectory && !dir.mkdirs()) {
            log.warn("大响应落盘失败 stage=mkdir，保留原响应")
            return result
        }
        tryPosix(dir, setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ))
        val file = File(dir, "offload-${UUID.randomUUID()}.json")
        try {
            file.writeText(result.toString())
        } catch (_: java.io.IOException) {
            log.warn("大响应落盘失败 stage=write，保留原响应")
            file.delete()
            return result
        }
        tryPosix(file, setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        ))
        val meta = JSONObject()
            .put("offloaded", true)
            .put("path", file.absolutePath)
            .put("bytes", file.length())
        log.info("大响应已落盘 bytes=${file.length()} file=${McpDiagnostics.text(file.absolutePath)}")
        val summary = JSONObject()
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text",
                JSONObject().put("summary", "payload offloaded").put("path", file.absolutePath).put("bytes", file.length()).toString())))
            .put("isError", false)
        return ProxySessionPolicy.injectProxyMeta(summary, meta)
    }

    companion object {
        private val offloadRoot = File(System.getProperty("java.io.tmpdir"), "nexus-mcp-offload-v2")
        private const val OFFLOAD_MAX_AGE_MS = 3_600_000L

        private fun tryPosix(file: File, perms: Set<PosixFilePermission>) {
            try {
                Files.setPosixFilePermissions(file.toPath(), perms)
            } catch (_: Exception) {
                // Windows 等无 POSIX 权限模型
            }
        }

        private fun pruneOffload(dir: File = offloadRoot, cutoff: Long = System.currentTimeMillis() - OFFLOAD_MAX_AGE_MS) {
            dir.listFiles()?.forEach { file ->
                if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    pruneOffload(file, cutoff)
                    file.delete() // 仅删除空目录，保留其他实例的新文件。
                } else if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }

        fun wrapCached(result: JSONObject, kind: CacheKind, snapshotAt: String): JSONObject {
            val meta = JSONObject()
                .put("cache", if (kind == CacheKind.HIT) "hit" else "section_hit")
                .put("snapshotAt", snapshotAt)
            return ProxySessionPolicy.injectProxyMeta(result, meta)
        }

        fun wrapDegraded(result: JSONObject, snapshotAt: String): JSONObject {
            val meta = JSONObject()
                .put("degraded", "unavailable")
                .put("snapshotAt", snapshotAt)
                .put("note", ProxySessionPolicy.DEGRADED_NOTE)
            return ProxySessionPolicy.injectProxyMeta(result, meta)
        }
    }
}
