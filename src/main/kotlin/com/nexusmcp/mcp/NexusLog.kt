// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/** 每次 IDE/插件启动独立文件；同次启动超过 4 MiB 时继续写入新的分段。 */
class StartupLog(private val directory: Path, prefix: String) {
    private val stem = "${prefix}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}_pid${ProcessHandle.current().pid()}"
    private var part = 0
    var file: Path = newPart()
        private set

    private fun newPart(): Path = Files.createTempFile(Files.createDirectories(directory), "${stem}_${++part}_", ".log")

    @Synchronized
    fun write(level: String, message: String) {
        if (Files.size(file) >= 4 * 1024 * 1024) file = newPart()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
        Files.writeString(file, "$timestamp [$level] $message\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }
}

/** 同时保留 idea.log 和本次启动的 Nexus 专用日志，不持有跨插件卸载的文件句柄。 */
object NexusLog {
    private val startup by lazy { StartupLog(PathManager.getLogDir().resolve("NexusMCP"), "nexus-rider") }
    private val fileErrorReported = AtomicBoolean()

    fun directory(): Path = startup.file.parent

    fun write(level: String, message: String) {
        try {
            startup.write(level, message)
        } catch (e: Exception) {
            if (fileErrorReported.compareAndSet(false, true)) {
                Logger.getInstance(NexusLog::class.java).warn("Nexus MCP 独立日志写入失败，仍可查看 idea.log type=${e.javaClass.simpleName}")
            }
        }
    }
}

class NexusLogger(private val platform: Logger) {
    fun info(message: String) {
        NexusLog.write("INFO", message)
        platform.info(message)
    }

    fun warn(message: String) {
        NexusLog.write("WARN", message)
        platform.warn(message)
    }

    fun error(message: String, error: Throwable? = null) {
        NexusLog.write("ERROR", message + (error?.let { " type=${it.javaClass.simpleName}" } ?: ""))
        if (error == null) platform.error(message) else platform.error(message, error)
    }

    fun debug(message: String) {
        if (!platform.isDebugEnabled) return
        NexusLog.write("DEBUG", message)
        platform.debug(message)
    }
}

inline fun <reified T : Any> nexusLogger(): NexusLogger = NexusLogger(Logger.getInstance(T::class.java))
