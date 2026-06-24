// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * 插件启动入口。
 * 在 IDE 打开项目后，启动独立 MCP 服务器并初始化 UE 实例管理器。
 */
class NexusLinkStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        try {
            startServer(project)
        } catch (e: Throwable) {
            log.error("Nexus MCP 启动异常", e)
            notifyError(project, "Nexus MCP 启动异常：${e.javaClass.simpleName} — ${e.message}<br/>请查看 idea.log 获取完整堆栈")
        }
    }

    companion object {
        val MCP_SERVER_KEY  = com.intellij.openapi.util.Key.create<NexusMcpServer>("nexusmcp.server")
        val MANAGER_KEY     = com.intellij.openapi.util.Key.create<UnrealInstanceManager>("nexusmcp.manager")
        val REFRESH_TASK_KEY = com.intellij.openapi.util.Key.create<java.util.concurrent.ScheduledFuture<*>>("nexusmcp.refresh")

        private val log = logger<NexusLinkStartupActivity>()

        /**
         * 启动 MCP 服务器及 UE 实例发现定时任务。
         * 若已在运行则直接返回；若设置中禁用则跳过。
         */
        fun startServer(project: Project) {
            val settings = NexusLinkSettings.instance.state

            if (!settings.enabled) {
                log.info("Nexus MCP 服务器已禁用，跳过启动")
                return
            }

            // 避免重复启动
            val existing = project.getUserData(MCP_SERVER_KEY)
            if (existing?.isRunning == true) return

            val manager = project.getUserData(MANAGER_KEY) ?: UnrealInstanceManager().also {
                it.scanPortStart = settings.scanPortStart
                it.scanPortEnd   = settings.scanPortEnd
                project.putUserData(MANAGER_KEY, it)
            }

            // UE 端工具列表变更时，清缓存后通知所有 SSE 客户端刷新
            manager.onToolsChanged = {
                project.getUserData(MCP_SERVER_KEY)?.sendToolsChangedNotification()
            }

            val server = NexusMcpServer(manager)
            val port   = findAvailablePort(settings.mcpPort)

            if (port < 0) {
                log.error("端口 ${settings.mcpPort} 及后续 100 个端口均被占用")
                notifyError(project, "端口 ${settings.mcpPort} 及后续 100 个端口均被占用，MCP 服务器未能启动")
                return
            }

            if (!server.start(port)) {
                log.error("MCP 服务器启动失败（端口 $port）")
                notifyError(project, "Nexus MCP 服务器启动失败（端口 $port），请检查端口占用或查看 idea.log")
                return
            }

            project.putUserData(MCP_SERVER_KEY, server)
            log.info("Nexus MCP 已启动：stream=http://127.0.0.1:$port/stream  sse=http://127.0.0.1:$port/sse")

            val msg = if (port != settings.mcpPort)
                "MCP 服务器已启动（端口 ${settings.mcpPort} 被占用，实际端口：<b>$port</b>）<br/>请将 AI 客户端地址更新为：<code>http://127.0.0.1:$port/stream</code>（Streamable HTTP）"
            else
                "MCP 服务器已就绪：<code>http://127.0.0.1:$port/stream</code>（Streamable HTTP）| <code>http://127.0.0.1:$port/sse</code>（SSE）"
            notifyInfo(project, msg)

            // 启动 UE 实例发现定时任务（若尚未运行则创建新任务）
            // 语义：已有任务且仍在运行 → 直接复用；否则（null 或已结束）→ 取消后重建
            val existingTask = project.getUserData(REFRESH_TASK_KEY)
            if (existingTask != null && !existingTask.isDone) {
                refreshStatusBar(project)
                return
            }
            existingTask?.cancel(false)
            val refreshTask = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(
                    {
                        val wasWsOpen = manager.isWsOpen()
                        val prevPort = manager.connectedPort
                        manager.maintainConnection()
                        val currPort = manager.connectedPort
                        val nowWsOpen = manager.isWsOpen()
                        // 重连成功或同端口 WS 恢复时广播 list_changed，让 AI 客户端刷新工具清单。
                        // 断线时不广播：cachedToolsList 保留上次工具名，避免调用被降级成 "Tool not found"。
                        if (currPort > 0 && ((!wasWsOpen && nowWsOpen) || prevPort != currPort)) {
                            manager.fetchToolsList()
                            project.getUserData(MCP_SERVER_KEY)?.sendToolsChangedNotification()
                        }
                        refreshStatusBar(project)
                    },
                    0, settings.scanIntervalSeconds.toLong(), TimeUnit.SECONDS
                )
            project.putUserData(REFRESH_TASK_KEY, refreshTask)
            refreshStatusBar(project)
        }

        /** 停止 MCP 服务器及定时任务。 */
        fun stopServer(project: Project) {
            project.getUserData(REFRESH_TASK_KEY)?.cancel(false)
            project.putUserData(REFRESH_TASK_KEY, null)

            val server = project.getUserData(MCP_SERVER_KEY) ?: return
            server.stop()
            project.putUserData(MCP_SERVER_KEY, null)
            log.info("Nexus MCP 已停止")
            refreshStatusBar(project)
        }

        fun notifyInfo(project: Project, content: String) {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("NexusMCP")
            val n = group?.createNotification("Nexus MCP", content, NotificationType.INFORMATION)
                ?: Notification("NexusMCP.fallback", "Nexus MCP", content, NotificationType.INFORMATION)
            Notifications.Bus.notify(n, project)
        }

        fun notifyError(project: Project, content: String) {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("NexusMCP")
            val n = group?.createNotification("Nexus MCP", content, NotificationType.ERROR)
                ?: Notification("NexusMCP.fallback", "Nexus MCP", content, NotificationType.ERROR)
            Notifications.Bus.notify(n, project)
        }

        fun refreshStatusBar(project: Project) {
            WindowManager.getInstance().getStatusBar(project)
                ?.updateWidget(NexusLinkStatusBarWidget.WIDGET_ID)
        }

        fun findAvailablePort(startPort: Int, maxAttempts: Int = 100): Int {
            for (i in 0 until maxAttempts) {
                val port = startPort + i
                if (port > 65535) break
                try {
                    java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { return port }
                } catch (_: Exception) { }
            }
            return -1
        }
    }
}
