// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JLabel
import javax.swing.JPasswordField
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * Rider 设置面板 —— Tools > Nexus MCP。
 * 提供端口配置和 AI 客户端接入配置生成功能。
 */
class NexusLinkConfigurable : Configurable {

    private var dialogPanel: DialogPanel? = null

    private val extraTokenModel = DefaultListModel<String>()
    private val extraTokenList = JBList(extraTokenModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "点击 + 添加其他机器的 token"
        visibleRowCount = 3
    }

    override fun getDisplayName() = "Nexus MCP"

    override fun createComponent(): JComponent {
        val st = NexusLinkSettings.instance.state
        st.proxyToken = NexusMcpAuth.loadOrCreateMachineToken(st.proxyToken)
        reloadExtraTokens()
        dialogPanel = buildPanel()
        return dialogPanel!!
    }

    override fun isModified() =
        dialogPanel?.isModified() == true || extraTokensFromUi() != extraTokensFromState()

    @Throws(ConfigurationException::class)
    override fun apply() {
        val state = NexusLinkSettings.instance.state
        val prevEnabled = state.enabled
        val prevPort = state.mcpPort
        val prevScanStart = state.scanPortStart
        val prevScanEnd = state.scanPortEnd
        val prevInterval = state.scanIntervalSeconds
        val prevListenLan = state.listenLan
        val prevRequireAuth = state.requireAuth
        val prevRemote = state.remoteUnrealText
        dialogPanel?.apply()
        state.extraAuthTokens = extraTokensFromUi().joinToString("\n")
        if (!NexusLinkSettings.isValidPort(state.mcpPort)
            || !NexusLinkSettings.isValidPort(state.scanPortStart)
            || !NexusLinkSettings.isValidPort(state.scanPortEnd)
        ) {
            state.mcpPort = prevPort
            state.scanPortStart = prevScanStart
            state.scanPortEnd = prevScanEnd
            state.scanIntervalSeconds = prevInterval
            dialogPanel?.reset()
            throw ConfigurationException("端口须为 1024–65535 的数字")
        }
        if (state.scanIntervalSeconds < NexusLinkSettings.MIN_SCAN_INTERVAL_SECONDS) {
            state.scanIntervalSeconds = prevInterval
            dialogPanel?.reset()
            throw ConfigurationException("扫描间隔须为不小于 1 的整数秒")
        }
        if (NexusLinkSettings.scanPortSpan(state.scanPortStart, state.scanPortEnd) > NexusLinkSettings.MAX_SCAN_PORT_SPAN) {
            state.scanPortStart = prevScanStart
            state.scanPortEnd = prevScanEnd
            dialogPanel?.reset()
            throw ConfigurationException("扫描区间不能超过 ${NexusLinkSettings.MAX_SCAN_PORT_SPAN} 个端口")
        }
        val enteredDanger = state.listenLan && !state.requireAuth && !(prevListenLan && !prevRequireAuth)
        if (enteredDanger) {
            val ok = Messages.showYesNoDialog(
                "局域网可达且没做身份验证时，同网段主机都能控制编辑器。确定继续？不要做公网映射。",
                "Nexus MCP",
                "继续",
                "取消",
                null,
            ) == Messages.YES
            if (!ok) {
                state.listenLan = prevListenLan
                state.requireAuth = prevRequireAuth
                dialogPanel?.reset()
                return
            }
        }
        val enteredRemote = LanHost.parseRemoteText(prevRemote).isEmpty()
            && LanHost.parseRemoteText(state.remoteUnrealText).isNotEmpty()
        if (enteredRemote) {
            val ok = Messages.showYesNoDialog(
                "连远程 UE 走明文 WebSocket，身份验证 token 可被同网段看到。确定继续？",
                "远程 UE 明文连接",
                "继续",
                "取消",
                null,
            ) == Messages.YES
            if (!ok) {
                state.remoteUnrealText = prevRemote
                dialogPanel?.reset()
                return
            }
        }
        val nowEnabled = state.enabled
        val portChanged = prevPort != state.mcpPort || prevListenLan != state.listenLan
        val scanChanged = prevScanStart != state.scanPortStart
            || prevScanEnd != state.scanPortEnd
            || prevInterval != state.scanIntervalSeconds
            || prevRemote != state.remoteUnrealText
        val gate = ProxySessionPolicy.parseWriteGate(state.writeGate)
        val projects = ProjectManager.getInstance().openProjects
        projects.forEach { project ->
            project.getUserData(NexusLinkStartupActivity.MANAGER_KEY)?.sessionHub?.writeGate = gate
        }

        AppExecutorUtil.getAppExecutorService().submit {
            projects.forEach { project ->
                when {
                    !nowEnabled -> NexusLinkStartupActivity.stopServer(project, "config_disabled")
                    portChanged || prevEnabled != nowEnabled -> {
                        if (portChanged) NexusLinkStartupActivity.stopServer(project, "listener_config_changed")
                        NexusLinkStartupActivity.startServer(project)
                    }
                    scanChanged -> NexusLinkStartupActivity.applyLiveScanSettings(project)
                }
            }
        }
    }

    override fun reset() {
        dialogPanel?.reset()
        reloadExtraTokens()
    }

    override fun disposeUIResources() {
        dialogPanel = null
        extraTokenModel.clear()

    }

    private fun extraTokensFromState(): List<String> =
        NexusMcpAuth.parseAuthTokens(NexusLinkSettings.instance.state.extraAuthTokens)

    private fun extraTokensFromUi(): List<String> =
        (0 until extraTokenModel.size()).map { extraTokenModel.getElementAt(it) }

    private fun reloadExtraTokens() {
        extraTokenModel.clear()
        extraTokensFromState().forEach { extraTokenModel.addElement(it) }
    }

    private fun addExtraTokensFromInput() {
        val text = Messages.showInputDialog(
            "粘贴其他机器的 token（多个会按逗号/分号自动拆开）",
            "添加 Token",
            null,
        ) ?: return
        val parsed = NexusMcpAuth.parseAuthTokens(text)
        if (parsed.isEmpty()) {
            Messages.showErrorDialog("不是有效的身份验证 token（32–128 位十六进制）。", "Nexus MCP")
            return
        }
        val existing = extraTokensFromUi().toHashSet()
        parsed.forEach { t ->
            if (existing.add(t)) extraTokenModel.addElement(t)
        }
    }

    private fun buildPanel(): DialogPanel {
        val state = NexusLinkSettings.instance.state

        return panel {
            // ── 总开关 ──────────────────────────────────────────────
            row {
                checkBox("启用 Nexus MCP 服务器")
                    .bindSelected(state::enabled)
                checkBox("允许局域网接入")
                    .bindSelected(state::listenLan)
                    .comment("默认关（仅 127.0.0.1）。勾选后 MCP 绑 0.0.0.0，远程 AI 可用本机网卡 IP 连接。勿映射到公网。")
            }

            // ── 服务器端口 ──────────────────────────────────────────
            group("服务器端口") {
                row("MCP 端口:") {
                    intTextField(1024..65535)
                        .bindIntText(state::mcpPort)
                    comment("AI 客户端连接此端口。修改后保存即生效。")
                }
            }

            group("接入 AI 客户端") {
                row {
                    button("MCP 客户端配置…") { showClientConfig() }
                    comment("使用已保存的设置生成 JSON / TOML；修改设置后请先应用。")
                }
            }

            // ── UE 实例发现 ─────────────────────────────────────────
            collapsibleGroup("UE 实例发现（高级）") {
                row {
                    comment("自动发现本机已启用 NexusLink MCP 的 UE 实例。")
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
                    comment("修改后保存即生效")
                }
                row("远程 UE:") {
                    textArea()
                        .bindText(state::remoteUnrealText)
                        .align(AlignX.FILL)
                    comment("每行 host:mcpPort [token...]（token 可省略或填多个）。本机 UE 无需配置，自动读 mcp-auth-token。")
                }
            }

            group("写入前确认") {
                row("确认范围:") {
                    comboBox(listOf("off", "destructive", "all"))
                        .bindItem(
                            { state.writeGate },
                            { v -> state.writeGate = v ?: "destructive" },
                        )
                    comment("off 不确认；destructive（默认）删除/重命名/停 PIE；all 所有写操作。")
                }
            }

            group("身份验证") {
                row {
                    checkBox("启用 MCP 身份验证")
                        .bindSelected(state::requireAuth)
                        .comment("默认开启。关闭后，AI 客户端连接本代理不再需要身份验证。")
                }
                row("本机 Token:") {
                    cell(JPasswordField(state.proxyToken).apply { isEditable = false })
                        .align(AlignX.FILL)
                    button("复制") {
                        val token = NexusMcpAuth.loadOrCreateMachineToken(
                            NexusLinkSettings.instance.state.proxyToken,
                        )
                        NexusLinkSettings.instance.state.proxyToken = token
                        CopyPasteManager.getInstance().setContents(StringSelection(token))
                    }
                }.comment("同机 UE / Desktop / VSCode 共用；按钮只复制 token 本身")
                row("额外 Token:") {
                    val listPanel = ToolbarDecorator.createDecorator(extraTokenList)
                        .setAddAction { _ -> addExtraTokensFromInput() }
                        .setRemoveAction { _ ->
                            val i = extraTokenList.selectedIndex
                            if (i >= 0) extraTokenModel.remove(i)
                        }
                        .createPanel()
                    listPanel.preferredSize = JBUI.size(420, 92)
                    cell(listPanel).align(AlignX.FILL)
                    comment("点 + 逐条添加；粘贴多个会自动拆开。本机 token 无需再填。")
                }
            }


        }
    }

    /** 独立配置窗使用 IDE 原生底部操作区，长内容只在预览区滚动。 */
    private fun showClientConfig() {
        val state = NexusLinkSettings.instance.state
        val port = ProjectManager.getInstance().openProjects
            .mapNotNull { it.getUserData(NexusLinkStartupActivity.MCP_SERVER_KEY) }
            .firstOrNull { it.isRunning }?.port ?: state.mcpPort
        val host = pickCopyHost(state.listenLan) ?: return
        val client = ComboBox(McpClient.entries.toTypedArray())
        val protocol = ComboBox(arrayOf("Streamable HTTP", "SSE"))
        val instructions = JTextArea(3, 50).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = JLabel().font
        }
        val preview = JTextArea(8, 50).apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 12)
        }
        val status = JLabel(" ")
        fun refresh() {
            val selected = client.selectedItem as McpClient
            protocol.isEnabled = selected != McpClient.CODEX
            if (selected == McpClient.CODEX && protocol.selectedIndex != 0) protocol.selectedIndex = 0
            instructions.text = selected.instructions
            preview.text = buildMcpClientConfig(if (protocol.selectedIndex == 1) "sse" else "stream",
                selected, port, state.proxyToken, host, state.requireAuth)
            preview.caretPosition = 0
            status.text = " "
        }
        client.addActionListener { refresh() }
        protocol.addActionListener { refresh() }
        refresh()
        object : DialogWrapper(true) {
            init {
                title = "MCP 客户端配置"
                init()
                setOKButtonText("复制配置")
                setCancelButtonText("关闭")
            }
            override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
                preferredSize = JBUI.size(640, 400)
                add(panel {
                    row("客户端:") { cell(client).align(AlignX.FILL) }
                    row("连接协议:") { cell(protocol).align(AlignX.FILL) }
                    row { cell(instructions).align(AlignX.FILL) }
                }, BorderLayout.NORTH)
                add(JScrollPane(preview), BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
            }
            override fun doOKAction() {
                CopyPasteManager.getInstance().setContents(StringSelection(preview.text))
                status.text = "配置已复制"
            }
            override fun getPreferredFocusedComponent(): JComponent = client
        }.show()
    }

    /** 开 LAN 且多网卡时选择写入配置的 IP；取消则返回 null。 */
    private fun pickCopyHost(listenLan: Boolean): String? {
        val (auto, choices) = LanHost.copyHostChoices(listenLan)
        if (choices.isEmpty()) return auto
        val combo = ComboBox(choices.map { "${it.name} ${it.address}" }.toTypedArray())
        val ok = object : DialogWrapper(true) {
            init {
                title = "Nexus MCP"
                init()
            }
            override fun createCenterPanel(): JComponent = panel {
                row { label("选择网卡 IP（写入配置的服务器地址）") }
                row { cell(combo).align(AlignX.FILL) }
            }
            override fun getPreferredFocusedComponent(): JComponent = combo
        }.showAndGet()
        if (!ok) return null
        val idx = combo.selectedIndex
        if (idx < 0) return null
        return choices[idx].address
    }
}
