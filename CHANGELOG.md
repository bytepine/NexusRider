# Changelog — nexus-rider (Rider 插件)

所有变更记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。
版本号遵循 [语义化版本控制](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

- docs: 优化 `plugin.xml` `<description>` 概览（英文为主 + 中文；补 NexusLink GitHub/Releases 链接、安装与启用步骤、绑定 127.0.0.1 无遥测声明）
- chore: 新增插件图标 `META-INF/pluginIcon.svg` 与暗色 `pluginIcon_dark.svg`（40×40，六边形 + 连接枢纽，满足 Marketplace logo 规范）
- chore(ci): Release 强制 `--verify` 门禁，正文仅来源于 CHANGELOG 段落（禁手写 Release 说明）
- docs: README 补充发版流程（与 NexusLink / NexusVSCode 一致）
- chore(ci): 新增 tag 触发 Release workflow，CI 自动打包并发布 GitHub Release
- docs: 新增英文 README（README.en.md）与中英文顶部语言切换
- chore: `plugin.xml` vendor 修正为 byteyang；GitHub 仓库描述与 topics 已配置

- fix(ci): gradlew 在 Linux runner 无执行权限导致 Actions 失败（`chmod +x` + workflow 兜底）

## [1.3.6] - 2026-06-24

- docs: README 独立仓自包含重写（架构图、安装配置、代理工具参考、FAQ、公开仓 Releases 链接）
- docs: 设置面板 UE 发现说明修正（移除已废弃的进程枚举描述）
- chore: 新增 `scripts/build_rider.py` 与 GitHub Actions 构建工作流

## [1.3.5] - 2026-06-09

- 修复 AI 客户端（Cursor/Codebuddy）重连后工具列表不刷新、调用失败：`initialized` 完成时预热 tools/list 并推送 `list_changed`；同端口 WS 恢复时补发通知；断线期间保留工具缓存

## [1.3.4] - 2026-06-04

- 消费 UE `nexus/proxy_config` 动态下发连接工具 description 与错误文案；`initialize` 握手时主动连 UE 并拉满 instructions，修复 AI 第一轮不调 MCP

## [1.3.3] - 2026-06-04

- WebSocket 框架优化：恢复长连接优先转发（`ensureLongConnection`），轻量心跳 `maintainConnection` 将稳态后台探测从 101 端口/轮降至 1 端口/轮；关闭库层 ping 探活（`connectionLostTimeout=0`），`connectionLock` 串行化发现/重连消除抖动

## [1.3.2] - 2026-06-01

- 加固 WebSocket 长连接：`tools/list` 超时 120s、请求串行化、`connectionLostTimeout=120` 与连接代次防误断；恢复默认长连接转发
- 配合 UE 收包异步派发，修复慢工具阻塞 Tick 导致 IDE 长连接断开

## [1.3.1] - 2026-06-01

- fix: `tools/call` 转发超时由 5s 增至 120s，并区分「未连接」与「超时」错误文案
- fix: `connect_unreal_instance` 标记用户偏好端口，避免定时扫描覆盖手动选择
- fix: 已连接实例的 `tools/call` 默认改走一次性 WebSocket，避免慢工具期间长连接提前断开

## [1.3.0] - 2026-05-09

- feat: `initialize.instructions` 拼接 UE 端 `InitializeInstructions.md` —— `UnrealInstanceManager` 连接成功后异步调用新增的 `nexus/instructions` WS method 缓存上游内容；`handleInitialize` 在自身 3 行代理引导后追加 `--- Upstream (Unreal) ---` 段，让 AI 一次握手即可看到完整 capability 工作流说明（含 Tool Model / Workflows / Rules / Feedback / Filter syntax）；连接断开时清空缓存
- docs: `NexusMcpDispatcher` 连接引导示例中的工具名 `list_assets` → `search_assets` → `search_asset`（与 UE NexusLink 对齐）
- refactor: 移除代理侧 `call_tool`（单 Capability 直达改由 UE `call_capability`）

## [1.2.2] - 2026-04-24

- fix: `call_tool` 未连接 UE 时不再出现在 tools/list（仅已连接且模式为 Starter/Custom 时显示）
- fix: 移除 `tools/list` 响应中的 `cache_control: ephemeral`，避免客户端缓存导致连接状态/模式变更后工具列表不刷新
- fix: `call_tool` 显隐改为从实际工具列表动态判断（检测 `search_tools` 是否存在），切换 Full/Starter 模式后刷新工具列表即生效，无需重连
- fix: `initialize.instructions` 强化"已连接直接调用"提示，明确只有 tools/list 中完全没有 UE 工具时才需要 `connect_unreal_instance`，避免 AI 每次都串行走连接流程
- fix: 断连时错误信息由 `"No connected UE instance or forward failed"` 改为 `"No connected UE instance. Call connect_unreal_instance first."`，引导 AI 主动重连

## [1.2.1] - 2026-04-24

- perf: `handleToolsList` 根据 UE 实例的 `toolsListMode` 动态开关 `call_tool`：Full 模式下隐藏（AI 直接可见全量工具），Starter/Custom 模式保留
- perf: `UnrealInstanceManager.probeStatus` 读取 `/status` 返回的 `toolsListMode` 字段，暴露 `connectedToolsListMode` 属性供 Dispatcher 使用

## [1.2.0] - 2026-04-24

- fix: `NexusMcpServer.start` 端口冲突日志字符串插值 bug（`$this.port` → `${this.port}`），原代码会打印对象 toString 而非端口号
- fix: `sendToolsChangedNotification` 活跃 SSE 数日志算术错误（`sseContexts.size` 已是移除后值，再减 `dead.size` 得负数），改为直接输出
- fix: `NexusLinkStartupActivity.startServer` 的刷新任务创建条件反向——原 `isDone == false` 会在已有任务运行时提前 `return` 但随后 `stopServer` 又只 cancel 任务；现改为"已有任务仍运行则复用、否则取消旧任务后重建"，修复 stop→start 循环后扫描定时器丢失
- fix: `UnrealInstanceManager.disconnect` 同步清空 `preferredPort`，避免用户主动断开后下一轮 `discoverInstances` 又把连接自动恢复
- fix: `NexusLinkStatusBarWidget` 点击实例菜单连接时传 `setPreferred=true`，让用户手动选择被尊重，不被自动发现逻辑覆盖
- fix: `scanPortsParallel` 自动交换 `scanPortStart > scanPortEnd` 的颠倒配置，避免 `Executors.newFixedThreadPool(负数)` 抛 `IllegalArgumentException`
- fix: `UnrealInstanceManager` `onClose` 回调改用共享 `reconnectExecutor`（daemon 单线程池）触发异步重扫，修复每次断连创建新 `SingleThreadExecutor` 不关闭导致的线程泄漏
- fix: `forwardToolCallToPort` 超时后显式调用 `oneShot?.close()` 强制关闭一次性 WebSocket 连接，避免连接挂起
- fix: `discoverInstances` 自动重连新增 `preferredPort` 优先级，断连后优先恢复用户手动指定的实例，不再无条件连 Editor 实例；`connectTo` 新增 `setPreferred` 参数
- fix: `initialize` 的 `instructions` 改为"已连接时直接调用工具，仅未连接时才走 list+connect 流程"，避免 AI 每次都多余地执行发现/连接步骤
- feat: `discoverInstances` 多实例时优先自动连接 `netRole == "Editor"` 的实例，其次仅有一个实例时才自动连接，减少 AI 初始化往返
- fix: WebSocket `onClose` 时立即异步触发 `discoverInstances`，缩短 UE 崩溃重启后的重连延迟，不再等待下一个定时周期
- fix: `list_unreal_instances` 的 `connected` 字段增加 `wsClient?.isOpen` 校验，防止 UE 崩溃后 TCP 半开态导致假阳性
- fix: 断线时不广播 `notifications/tools/list_changed`，仅重连成功时广播，与 nexus-vscode 策略对齐，避免 Cursor 工具降级
- feat: 新增 `call_tool` 代理工具 — 接收 `{name, arguments}` 并将调用透传至当前连接的 UE 实例，使 AI 在 Starter 模式下通过 `search_tools` 发现工具后可直接调用非启动套件工具，完善渐进发现 → 调用闭环
- perf: P2 Prompt Caching — initialize 和 tools/list 响应附加 `cache_control: {"type": "ephemeral"}` 标记，供支持 Anthropic beta 的客户端缓存稳定文本
- fix: HTTP 路由补充 `GET /stream` SSE 端点，与 `GET /sse` 共用同一处理器，符合 Streamable HTTP 规范
- refactor: 删除"未加载 NexusLink 的 UE 进程"检测功能（`UeProcessInfo`、`ueProcessesWithoutNexus`、进程枚举逻辑及状态栏 UI 提示），该功能准确性差且有进程枚举性能开销

## [1.1.0] - 2026-04-20

- chore(script): `build_rider.py` 打包时自动从 `nexus-rider/CHANGELOG.md` 提取最近 5 个版本注入 `plugin.xml <change-notes>`，JetBrains Marketplace 详情页 Changelog 标签页不再为空
- feat: `tools/call` 支持可选 `arguments.targetPort` —— 代理层一次性路由到指定 UE 实例（一次性 WebSocket），不改动长连接绑定；适用于 AI 并发查询 DS+多 Client 场景
- feat: `list_unreal_instances` 返回体新增 `netRole` 字段（`DedicatedServer`/`ListenServer`/`Client`/`Standalone`/`Editor`），来自 UE 端 `/status` 探测
- docs: 新增 MIT LICENSE 文件
- chore: 接入 GitHub Actions 冒烟构建（`.github/workflows/build.yml`），自动校验 `./gradlew buildPlugin`

## [1.0.2] - 2026-04-15

- refactor: HTTP 通道实现 per-session Dispatcher 会话隔离（`Mcp-Session-Id` header），多 AI 客户端并发连接互不干扰
- fix: MCP 协议版本统一为 `2025-06-18`，与 nexus-unreal 对齐
- docs: 修正 NexusRider 规范中 Netty 依赖声明为 `compileOnly`

## [1.0.1] - 2026-04-14

- fix: 将 `list_unreal_instances` 和 `connect_unreal_instance` 从自定义 MCP 方法改为标准工具
- fix: 构建脚本增加 `clean` 步骤，确保每次全量编译

## [1.0.0] - 2026-04-13

- feat: 基于 Netty 实现独立 MCP HTTP 服务器（Streamable HTTP + SSE 双协议兼容）
- feat: UE 实例自动发现：进程枚举 → 注册文件读取 → 并发端口扫描三级策略
- feat: WebSocket 长连接代理，MCP 工具调用透传至 UE
- feat: 设置面板（Tools > Nexus MCP）：端口配置、一键复制 Cursor/CodeBuddy 接入配置
- feat: 状态栏组件，实时显示 UE 连接状态，点击切换连接目标
- feat: 后台每 5 秒自动发现新 UE 实例
- fix: WebSocket Binary 帧解码、MCP 协议版本协商、线程池隔离等稳定性修复
