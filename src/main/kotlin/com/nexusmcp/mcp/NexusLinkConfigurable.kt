// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Rider 设置面板 —— Tools > Nexus MCP。
 * 提供端口配置和 AI 客户端接入配置生成功能。
 */
class NexusLinkConfigurable : Configurable {

    private var dialogPanel: DialogPanel? = null

    /** 配置预览区域，点击按钮后填入对应格式配置，供用户手动复制。 */
    private val configPreview = JTextArea(8, 60).apply {
        isEditable = false
        lineWrap = false
        font = Font("Monospaced", Font.PLAIN, 12)
        text = "← 点击左侧按钮生成对应配置"
    }

    override fun getDisplayName() = "Nexus MCP"

    override fun createComponent(): JComponent {
        dialogPanel = buildPanel()
        return dialogPanel!!
    }

    override fun isModified() = dialogPanel?.isModified() == true

    override fun apply() {
        val prevEnabled = NexusLinkSettings.instance.state.enabled
        dialogPanel?.apply()
        val nowEnabled = NexusLinkSettings.instance.state.enabled

        if (prevEnabled != nowEnabled) {
            val projects = ProjectManager.getInstance().openProjects
            AppExecutorUtil.getAppExecutorService().submit {
                projects.forEach { project ->
                    if (nowEnabled) NexusLinkStartupActivity.startServer(project)
                    else            NexusLinkStartupActivity.stopServer(project)
                }
            }
        }
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun buildPanel(): DialogPanel {
        val state = NexusLinkSettings.instance.state

        return panel {
            // ── 总开关 ──────────────────────────────────────────────
            row {
                checkBox("启用 Nexus MCP 服务器")
                    .bindSelected(state::enabled)
                    .comment("开启后立即启动 MCP 代理服务器，关闭后立即停止。")
            }

            // ── 服务器端口 ──────────────────────────────────────────
            group("服务器端口") {
                row("MCP 端口:") {
                    intTextField(1024..65535)
                        .bindIntText(state::mcpPort)
                    comment("AI 客户端连接此端口。修改后需重启 Rider 生效。")
                }
            }

            // ── UE 实例发现 ─────────────────────────────────────────
            group("UE 实例发现") {
                row {
                    comment("并发端口扫描：对范围内端口发 GET /status，探测已启用 NexusLink MCP 的 UE 实例。")
                }
                row("扫描端口范围:") {
                    intTextField(1024..65535)
                        .bindIntText(state::scanPortStart)
                    label("—")
                    intTextField(1024..65535)
                        .bindIntText(state::scanPortEnd)
                }
                row("扫描间隔:") {
                    intTextField(1..3600)
                        .bindIntText(state::scanIntervalSeconds)
                    label("秒")
                    comment("修改后重启 MCP 服务器生效")
                }
            }

            // ── 接入 AI 客户端 ──────────────────────────────────────
            group("接入 AI 客户端") {
                row {
                    comment(
                        "端点地址：<code>http://127.0.0.1:${state.mcpPort}/stream</code>（Streamable HTTP）" +
                        " | <code>http://127.0.0.1:${state.mcpPort}/sse</code>（SSE）<br>" +
                        "点击下方按钮生成对应配置片段，从预览框中选择并复制。"
                    )
                }
                row {
                    button("Streamable HTTP 配置") {
                        configPreview.text = buildStreamConfig(state.mcpPort)
                        configPreview.caretPosition = 0
                    }
                    button("SSE 配置") {
                        configPreview.text = buildSseConfig(state.mcpPort)
                        configPreview.caretPosition = 0
                    }.align(AlignX.LEFT)
                }
                row {
                    cell(JScrollPane(configPreview)).align(AlignX.FILL)
                }
            }
        }
    }

    /** 生成 Streamable HTTP（/stream）配置片段。 */
    private fun buildStreamConfig(port: Int): String = """
# ── CodeBuddy / Windsurf ──────────────────────────────────
# 配置路径：自定义 MCP → 粘贴到 mcpServers 节点下
"Nexus": {
  "url": "http://127.0.0.1:$port/stream",
  "transportType": "streamable-http",
  "description": "NexusLink MCP Server for Unreal Engine",
  "disabled": false
}

# ── Cursor ────────────────────────────────────────────────
# 配置路径：~/.cursor/mcp.json → mcpServers 节点下
"nexus-unreal": {
  "url": "http://127.0.0.1:$port/stream"
}
    """.trimIndent()

    /** 生成 SSE（/sse）配置片段。 */
    private fun buildSseConfig(port: Int): String = """
# ── CodeBuddy / Windsurf ──────────────────────────────────
# 配置路径：自定义 MCP → 粘贴到 mcpServers 节点下
"Nexus": {
  "url": "http://127.0.0.1:$port/sse",
  "disabled": false
}

# ── Cursor ────────────────────────────────────────────────
# 配置路径：~/.cursor/mcp.json → mcpServers 节点下
"nexus-unreal": {
  "url": "http://127.0.0.1:$port/sse"
}
    """.trimIndent()
}
