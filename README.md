# nexus-rider — Rider 插件

**语言 / Language**: **简体中文** · [English](README.en.md)

JetBrains Rider 端 MCP **代理**：本地 HTTP 服务器（默认 `:6800`），发现 UE 实例，经 WebSocket 把 AI 工具调用转发给 **NexusLink**。蓝图、资产、PIE 等能力由 UE 里提供，本插件不实现游戏逻辑。

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

### 1. 先准备好 UE

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
| 写入前确认 | `destructive` | `off` / `destructive`（删除、重命名、停 PIE）/ `all` |
| 允许局域网接入 | 关 | MCP 绑 `0.0.0.0`；复制配置时填本机网卡 IP |
| 启用 MCP 身份验证 | 开 | 关闭后 AI 连本插件无需 Bearer（同旧版）；连 UE 仍看对方身份验证 |
| 额外身份验证 Token | （空） | 点 + 逐条添加其他机器 token；本机 UE 自动读文件 |
| 本机身份验证 Token | （只读） | 同机共享；旁有「复制」只写入 token |
| 远程 UE | （空） | 每行 `host:mcpPort [token...]`，不扫网段 |

跨机与身份验证见 [usage-guide §1](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md)。

设置面板按传输协议（Streamable HTTP / SSE）与客户端（Cursor / CodeBuddy）各选一项，一次生成一份；开 LAN 且多网卡时先选 IP。生成的 Bearer 仅本机 token。

### 4. 状态栏

状态栏始终显示当前连接状态，悬浮提示保留 Agent 转发暂停状态。

批量调用（含部分失败）会清理目标实例的读缓存，失败读取不会进入缓存。大响应临时文件相互隔离，仅清理超过 1 小时的文件。

兼容已发布 2.x 的工具调用格式和当前格式，请求原样转发。新版临时目录与旧 2.x 分开，避免旧进程清理新版结果。

| 显示 | 含义 |
|------|------|
| **⬢ 项目名** | 已连接 UE |
| **⬡ Nexus** | 未连接 |

点击状态栏切换实例。唯一实例自动连接；多实例优先 `hostKind=Editor`（旧 UE 无该字段时改用 `netRole=Editor`）。断线保留工具列表缓存，重连后刷新。读缓存与断线快照按 UE host/port 隔离，切换实例不会复用另一实例的数据。弹出的菜单可暂停/恢复 Agent 转发。会话层约定见 [proxy-session.md](https://github.com/bytepine/NexusLink/blob/master/docs/proxy-session.md)。

---

## AI 客户端

默认 `http://127.0.0.1:6800/stream`。端口被占用会自动换成下一个端口，以启动通知或设置面板为准。绑定 `127.0.0.1`。

**Cursor**（`~/.cursor/mcp.json`）。Token 用设置面板 **本机 Token** 旁的「复制」。可写多个：`Bearer <tok1>, <tok2>`。关闭「启用 MCP 身份验证」时可不带 `headers`。规则见 [usage-guide §1.1](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md#11-身份验证)。

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

旧版 MCP 客户端可用 `http://127.0.0.1:6800/sse`。CodeBuddy / Windsurf 在设置面板选对应客户端后再复制。

已连接时 `tools/list` 合并 UE 工具。多实例并发可在 `arguments` 中带 `targetPort`（一次性 WS，不改长连接绑定），须为 1024–65535 的整数；非法显式端口返回 `-32602`，不会转发到当前实例或返回缓存。

---

## 常见问题

### AI 客户端「MCP 初始化超时」

确认已 **打开项目**、勾选 **启用 Nexus MCP 服务器**、UE 已启用 MCP、状态栏为 **⬢ 项目名**、AI 端口与实际监听一致。

### 多个 Rider 项目窗口

MCP 按**项目**挂载。多窗口可能抢同一个端口 `6800`，后续窗口自动换成下一个端口（`6801`…），以各窗口通知中的端口为准。

### 工具列表不刷新

连接/断开后会推送 `notifications/tools/list_changed`。客户端未更新时重连 MCP 或重启 AI 会话。

### 查看日志

点击 **Nexus 状态栏 → 打开日志目录**，代理未启用时也可使用。目录中的 `nexus-rider_<启动时间>_pid<进程ID>_<分段>_*.log` 每次 IDE/插件启动新建，超过 4 MiB 后继续写新分段，旧文件保留；所有项目窗口共用本次启动的日志。目录位于 Rider 日志目录下的 `NexusMCP/`，原有 `idea.log` 同时保留，可通过 **Help → Show Log in Explorer/Finder** 查看。

默认 INFO/WARN 即记录客户端版本、会话创建与握手、失效或淘汰、HTTP 拒绝、SSE 通断、调用开始/完成/耗时与失败，以及服务器停止原因和连接恢复。`session` 是会话摘要，`request` 是本次服务器运行内的请求编号；`missing_session` / `unknown_session` 区分缺少 ID 与 ID 已不存在。Token、完整会话 ID、工具参数和响应正文不写入日志。

最多保留 50 个 MCP 会话；容量满时仅回收超过 30 分钟没有请求、也没有进行中调用或 SSE 连接的会话（`idle_timeout`）。正在初始化或使用的会话不会被新连接淘汰；无法回收时，新连接返回 503 和 `Retry-After`（`session_limit`）。客户端可通过携带会话 ID 的 `DELETE /stream` 主动结束会话（`client_delete`）。回收后的 ID 需要重新 `initialize`。

反馈断连时请同时提供报错时间、AI 客户端名称与版本以及客户端日志。服务器重启会使原会话失效，客户端需要重新 `initialize`，可关闭再开启该 MCP 连接。常规扫描和心跳不会刷 INFO。

### 改了 UE 资产但磁盘未变化

属 NexusLink 里保存到磁盘的行为，与本代理无关。见 [usage-guide FAQ](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md)。

---

## 本地构建与发版

```bash
./gradlew buildPlugin    # Windows: gradlew.bat buildPlugin
```

提交前运行 `./gradlew check buildPlugin`（Windows：`gradlew.bat check buildPlugin`）。`check` 包含独立 `contract` 源集的 `testProxyPolicy`，验证当前 PascalCase 契约、本地 HTTP/WebSocket 多实例转发、旧/新工具清单重连刷新及实际调用、非法目标拒绝、MCP 会话并发与容量回收及日志字段脱敏；也可单独运行该任务。无需启动 Rider 或 UE，CI 执行同一检查。

产物：`build/distributions/*.zip`。调试：`./gradlew runIde`（需本机 Rider 2025.3 + JDK 21）。

GitHub Release 正文仅来自 `CHANGELOG.md` 对应段落（`py scripts/extract_release_notes.py --version X.Y.Z --verify`）。tag：`nexus-rider-vX.Y.Z`。功能变更写入 [CHANGELOG.md](CHANGELOG.md) `[Unreleased]`。

在仓库 **Settings → Secrets and variables → Actions** 配置 `JETBRAINS_MARKETPLACE_TOKEN`（具有插件发布权限的 [Marketplace Token](https://plugins.jetbrains.com/author/me/tokens)）。正式版创建 GitHub Release 后，会自动将同一 ZIP 提交到 JetBrains Marketplace；预发布跳过商店。首次上架需先在 Marketplace 手动创建插件，上传后的版本仍需审核。

补传已有正式版：**Actions → Publish to JetBrains Marketplace → Run workflow**，输入版本号（例如 `2.2.0`）。该入口只接受已有 GitHub Release 的正式版，复用已发布 ZIP；同一版本不能重复上传。

源码：`src/main/kotlin/com/nexusmcp/mcp/`

---

## License

[MIT](LICENSE) © byteyang
