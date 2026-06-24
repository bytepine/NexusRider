# nexus-rider — Rider 插件

NexusMCP 的 Rider 端代理插件。运行独立 MCP 服务器，负责发现和连接多个 Unreal Engine 实例，将 MCP 工具调用转发到目标 UE 进程。

## 架构概览

```
AI Client ─── MCP (HTTP) ──→ nexus-rider (Netty :6800)
                                │   POST /stream ── Streamable HTTP
                                │   GET  /sse ───── SSE 通知流
                                │   GET  /stream ── SSE 通知流（别名）
                                │
                                │  发现: 并发端口扫描（GET /status 真实校验）
                                │  通信: WebSocket (JSON-RPC, 双向长连接)
                                │
                                └──→ nexus-unreal (HTTP + WS)
```

## 功能列表

### 独立 MCP 服务器（面向 AI 客户端）

- [x] 基于 Netty 的 HTTP/1.1 MCP 服务器（不依赖 Rider 内置 MCP）
- [x] 双端点：`POST /stream`（Streamable HTTP）+ `GET /sse` / `GET /stream`（SSE 通知流，两路由共用同一处理器）
- [x] 兼容 MCP 旧 SSE 传输（2024-11-05）和新 Streamable HTTP（2025-03-26）双协议
- [x] JSON-RPC 2.0 协议 + MCP 会话状态机（initialize/initialized/ping/tools）
- [x] per-session 会话隔离（`Mcp-Session-Id` header），多 AI 客户端并发连接互不干扰
- [x] 监听端口可配置（默认 6800），端口冲突自动顺延

### UE 实例发现与管理

- [x] 端口扫描发现策略：并发扫描指定端口范围，每轮 `GET /status` 真实连通性校验（不保留死进程残留）；**前提** UE 须在 Editor Preferences → Plugins → NexusLink 勾选 **启用 MCP 服务器**（默认关闭）
- [x] 后台每 5 秒自动重新发现，新启动的 UE 编辑器可被自动感知
- [x] WebSocket 长连接通信（JSON-RPC，无 MCP 握手开销）
- [x] 多实例支持：列出所有发现的 UE 实例及其项目信息
- [x] 连接/切换：选择特定 UE 实例进行 WebSocket 连接；多实例时优先自动连接 `netRole=Editor` 实例，单实例时自动连接
- [x] `preferredPort` 保留用户手动选择，`discoverInstances` 断连重扫时优先恢复指定实例
- [x] 连接断开立即异步触发重扫，缩短 UE 崩溃重启后的重连延迟；断线不广播 `tools/list_changed`，重连成功或 MCP 会话 `initialized` 时广播刷新工具清单

### IDE 集成

- [x] 设置面板（**Tools > Nexus MCP**）：端口配置、一键复制 Cursor / CodeBuddy 接入配置
- [x] 状态栏组件：实时显示 UE 连接状态（⬢ 项目名 / ⬡ Nexus），点击弹出实例列表切换目标
- [x] UE 连接/断开时自动推送 `notifications/tools/list_changed`

### MCP 工具代理

- [x] `list_unreal_instances` — 发现所有运行中的 UE 实例（返回体含 `netRole`：`DedicatedServer`/`ListenServer`/`Client`/`Standalone`/`Editor`，来自 UE 端 `/status` 探测）
- [x] `connect_unreal_instance` — 连接到指定端口的 UE 实例
- [x] `initialize.instructions` 透传 UE 端 capability 工作流说明：连接成功后异步拉取 `nexus/instructions`，将 UE 侧 `InitializeInstructions.*.md` 拼接到 AI 握手响应
- [x] 连接工具 description / initialize 前缀 / 错误文案由 UE `nexus/proxy_config` 下发（`ProxyConfig.json`），代理不再硬编码 Capability 名；未连接时使用通用 fallback
- [x] 远端工具透传：tools/list 合并远端工具列表，tools/call 默认经 **WebSocket 长连接**转发（`ensureLongConnection` 自动重建半开连接）；仅 `arguments.targetPort` 使用一次性 WS（多实例并发，不改动长连接绑定）

---

## 安装与使用

最终用户安装、端口配置、AI 客户端接入等步骤见 [docs/usage-guide.md](../docs/usage-guide.md) §3。

打包：根目录执行 `build.bat rider`，产物在 `build/nexus-mcp-rider-<version>.zip`。

---

## License

[MIT](LICENSE) © byteyang

---

> 新增功能时请同步更新本文件 + `../docs/tool-reference.md`（如涉及工具），并按 `.cursor/rules/文档同步.mdc` 映射表自检。大功能同步根目录 [README.md](../README.md)。
