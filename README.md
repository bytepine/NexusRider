# nexus-rider — Rider 插件

**语言 / Language**: **简体中文** · [English](README.en.md)

JetBrains Rider 端 MCP **代理**：本地 HTTP 服务器（默认 `:6800`），发现 UE 实例，经 WebSocket 把 AI 工具调用转发给 **NexusLink**。蓝图、资产、PIE 等能力由 UE 侧提供，本插件不实现游戏逻辑。

四端端口与开关层数见 [NexusLink 使用指南](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md)。本插件是三层开关中的 IDE 层。本机不要与 NexusDesktop / VSCode 代理同时开。

---

## 依赖

| 组件 | 要求 |
|------|------|
| **nexus-rider** | 与 UE `proxy_config.minProxyVersion` 对齐，建议最新版 |
| **NexusLink** | [NexusLink Releases](https://github.com/bytepine/NexusLink/releases) 的 `nexus-mcp-unreal-*.zip`；UE **4.26+** |
| **JetBrains Rider** | 2025.3+（build `253`，兼容至 `263.*`） |
| **JDK**（仅本地构建） | 21 |

---

## 安装与使用

> **须 Open Project**：MCP 服务在 `ProjectActivity` 中启动。仅打开欢迎页、未打开项目时，即使勾选启用也不会监听。

### 1. UE 前置

1. 安装并启用 NexusLink，勾选 **启用 MCP 服务器**（步骤见 [usage-guide §2](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md)）
2. 未勾选时扫描为空，状态栏显示 **⬡ Nexus**

### 2. 安装本插件

推荐：Rider **Settings → Plugins → Marketplace** 搜索 **Nexus MCP**。

备用：从 [NexusRider Releases](https://github.com/bytepine/NexusRider/releases) 下载 zip → **Install Plugin from Disk** → 重启 → **打开一个项目**。

然后 **Settings → Tools → Nexus MCP** → 勾选 **启用 Nexus MCP 服务器**（默认关闭；勾选后立即启动，默认 `:6800`）。

### 3. 配置项

入口：**Settings → Tools → Nexus MCP**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 启用 Nexus MCP 服务器 | `false` | 总开关；勾选/取消立即启停 |
| MCP 端口 | `6800` | AI 客户端连接端口；保存后立即重启监听 |
| 扫描端口范围 | `45000`–`45100` | UE 发现范围；保存后立即生效 |
| 扫描间隔 | `5` 秒 | 定时发现间隔；保存后立即生效 |
| 写操作门控 | `destructive` | `off` / `destructive`（删除、重命名、停 PIE）/ `all` |
| 允许局域网接入 | 关 | MCP 绑 `0.0.0.0`；复制配置时填本机网卡 IP |
| 远程 UE | （空） | 每行 `host:mcpPort token`，不扫网段 |

跨机见 [usage-guide §1](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md)。

设置面板提供「Streamable HTTP 配置」「SSE 配置」按钮，生成可复制的 AI 客户端 JSON。

### 4. 状态栏

| 显示 | 含义 |
|------|------|
| **⬢ 项目名** | 已连接 UE |
| **⬡ Nexus** | 未连接 |

点击状态栏切换实例。唯一实例自动连接；多实例优先 `netRole=Editor`。断线保留工具列表缓存，重连后刷新。弹单可暂停/恢复 Agent 转发。会话层契约见 [proxy-session.md](https://github.com/bytepine/NexusLink/blob/master/docs/proxy-session.md)。

---

## AI 客户端

默认 `http://127.0.0.1:6800/stream`。端口被占用会顺延，以启动通知或设置面板为准。绑定 `127.0.0.1`。

**Cursor**（`~/.cursor/mcp.json`）。Token 从设置面板「复制」取得：

```json
{
  "mcpServers": {
    "nexus-unreal": {
      "url": "http://127.0.0.1:6800/stream",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

旧版 MCP 客户端可用 `http://127.0.0.1:6800/sse`。CodeBuddy / Windsurf 等片段可用设置面板一键复制。

已连接时 `tools/list` 合并 UE 工具。多实例并发可在 `arguments` 中带 `targetPort`（一次性 WS，不改长连接绑定）。

---

## 常见问题

### AI 客户端「MCP 初始化超时」

确认已 **打开项目**、勾选 **启用 Nexus MCP 服务器**、UE 已启用 MCP、状态栏为 **⬢ 项目名**、AI 端口与实际监听一致。

### 多个 Rider 项目窗口

MCP 按**项目**挂载。多窗口可能争用 `6800`，后续窗口顺延（`6801`…），以各窗口通知中的端口为准。

### 工具列表不刷新

连接/断开后会推送 `notifications/tools/list_changed`。客户端未更新时重连 MCP 或重启 AI 会话。

### 查看日志

`%LOCALAPPDATA%\JetBrains\Rider<version>\log\idea.log`（macOS/Linux：`~/Library/Logs/JetBrains/Rider<version>/idea.log`），搜索 `Nexus MCP`。

### 改了 UE 资产但磁盘未变化

属 NexusLink 侧落盘行为，与本代理无关。见 [usage-guide FAQ](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md)。

---

## 本地构建与发版

```bash
./gradlew buildPlugin    # Windows: gradlew.bat buildPlugin
```

产物：`build/distributions/*.zip`。调试：`./gradlew runIde`（需本机 Rider 2025.3 + JDK 21）。

GitHub Release 正文仅来自 `CHANGELOG.md` 对应段落（`py scripts/extract_release_notes.py --version X.Y.Z --verify`）。tag：`nexus-rider-vX.Y.Z`。功能变更写入 [CHANGELOG.md](CHANGELOG.md) `[Unreleased]`。

源码：`src/main/kotlin/com/nexusmcp/mcp/`

---

## License

[MIT](LICENSE) © byteyang
