// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.MouseEvent

/**
 * 状态栏 Widget 工厂，注册到 IDE 状态栏。
 */
class NexusLinkStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId() = NexusLinkStatusBarWidget.WIDGET_ID

    override fun getDisplayName() = "Nexus MCP"

    override fun isAvailable(project: Project) = true

    override fun createWidget(project: Project) = NexusLinkStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        (widget as? Disposable)?.let {
            com.intellij.openapi.util.Disposer.dispose(it)
        }
    }

}

/**
 * 弹出菜单条目：可以是 UE 实例，也可以是"刷新搜索"操作。
 */
private class PopupItem(val label: String, val action: (() -> Unit)?)

/**
 * 显示当前 UE 连接状态的状态栏组件。
 *
 * - 未连接：显示 "⬡ Nexus"，有实例未连接时显示 "⬡ Nexus (N)"
 * - 已连接：显示 "⬢ {项目名}"
 * - 点击：弹出实例列表，可选择切换连接目标或手动刷新搜索
 */
class NexusLinkStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation, Disposable {

    companion object {
        const val WIDGET_ID = "NexusLinkStatus"
    }

    private var statusBar: StatusBar? = null

    override fun ID() = WIDGET_ID

    override fun getPresentation() = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getText(): String {
        val manager = project.getUserData(NexusLinkStartupActivity.MANAGER_KEY)
            ?: return "⬡ Nexus"
        return if (manager.connectedPort > 0) {
            val info = manager.instances.find { it.port == manager.connectedPort }
            val name = info?.projectName?.takeIf { it.isNotEmpty() } ?: "${manager.connectedPort}"
            "⬢ $name"
        } else {
            val count = manager.instances.size
            if (count > 0) "⬡ Nexus ($count)" else "⬡ Nexus"
        }
    }

    override fun getTooltipText(): String {
        val server = project.getUserData(NexusLinkStartupActivity.MCP_SERVER_KEY)
        val serverLine = if (server?.isRunning == true)
            "MCP 服务器：http://127.0.0.1:${server.port}/stream (stream) | /sse (sse)"
        else
            "MCP 服务器：未运行"

        val manager = project.getUserData(NexusLinkStartupActivity.MANAGER_KEY)
            ?: return "$serverLine\nUE：未初始化"

        val ueLine = if (manager.connectedPort > 0) {
            val info = manager.instances.find { it.port == manager.connectedPort }
            val name = info?.projectName?.takeIf { it.isNotEmpty() } ?: "${manager.connectedPort}"
            val ver = info?.engineVersion?.takeIf { it.isNotEmpty() }?.let { " · UE $it" } ?: ""
            "已连接 UE：$name$ver（端口 ${manager.connectedPort}）"
        } else {
            "UE：未连接"
        }
        return "$serverLine\n$ueLine\n点击管理 UE 实例"
    }

    override fun getAlignment() = 0f

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer { event ->
        showInstancePopup(event)
    }

    /** 弹出 UE 实例选择列表，末尾始终附带"刷新搜索"选项。 */
    private fun showInstancePopup(event: MouseEvent) {
        val manager = project.getUserData(NexusLinkStartupActivity.MANAGER_KEY) ?: return
        val instances = manager.instances.toList()

        val items = mutableListOf<PopupItem>()

        if (instances.isEmpty()) {
            items.add(PopupItem("（未发现活跃的 UE 实例）", null))
        } else {
            // 可连接的 NexusLink 实例
            instances.forEach { info ->
                val name = info.projectName.ifEmpty { "端口 ${info.port}" }
                val ver = if (info.engineVersion.isNotEmpty()) "   UE ${info.engineVersion}" else ""
                val mark = if (info.port == manager.connectedPort) "  ✓" else ""
                items.add(PopupItem("$name$ver   :${info.port}$mark") {
                    AppExecutorUtil.getAppExecutorService().submit {
                        // 用户主动选择 → 记录为 preferredPort，避免被自动发现逻辑覆盖
                        manager.connectTo(info.port, setPreferred = true)
                        statusBar?.updateWidget(WIDGET_ID)
                    }
                })
            }
        }

        // 已连接时提供断开选项（与 VSCode 对齐）
        if (manager.connectedPort > 0) {
            items.add(PopupItem("⊘  断开连接") {
                AppExecutorUtil.getAppExecutorService().submit {
                    manager.disconnect()
                    statusBar?.updateWidget(WIDGET_ID)
                }
            })
        }

        // 末尾始终提供手动刷新，让用户不依赖 5 秒定时器
        items.add(PopupItem("⟳  刷新搜索") {
            AppExecutorUtil.getAppExecutorService().submit {
                manager.discoverInstances()
                statusBar?.updateWidget(WIDGET_ID)
            }
        })

        val title = when {
            instances.isEmpty() -> "Nexus MCP"
            else -> "选择 UE 实例（${instances.size} 个）"
        }

        val step = object : BaseListPopupStep<PopupItem>(title, items) {
            override fun getTextFor(value: PopupItem) = value.label

            override fun onChosen(selectedValue: PopupItem, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) selectedValue.action?.invoke()
                return PopupStep.FINAL_CHOICE
            }

            override fun isSelectable(value: PopupItem) = value.action != null
        }

        val popup: ListPopup = JBPopupFactory.getInstance().createListPopup(step)
        popup.show(RelativePoint(event))
    }

    /** 供外部触发状态栏文字刷新。 */
    fun refresh() {
        statusBar?.updateWidget(WIDGET_ID)
    }
}
