// Copyright byteyang. All Rights Reserved.

package com.nexusmcp.mcp

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * NexusLink 插件持久化配置。
 * 存储路径：IDE 配置目录 / nexus-link.xml。
 * 通过 [NexusLinkSettings.instance] 访问单例。
 */
@Service
@State(name = "NexusLinkSettings", storages = [Storage("nexus-link.xml")])
class NexusLinkSettings : PersistentStateComponent<NexusLinkSettings.State> {

    /** 配置数据类，IDE 序列化时使用。 */
    data class State(
        /** 是否启用 MCP 服务器。 */
        var enabled: Boolean = false,
        /** MCP 服务器监听端口。 */
        var mcpPort: Int = 6800,
        /** UE 实例扫描起始端口。 */
        var scanPortStart: Int = 45000,
        /** UE 实例扫描结束端口。 */
        var scanPortEnd: Int = 45100,
        /** UE 实例发现定时扫描间隔（秒）。 */
        var scanIntervalSeconds: Int = 5,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        val instance: NexusLinkSettings get() = service()
    }
}
