# Changelog — NexusRider

**语言 / Language**: [简体中文](CHANGELOG.md) · **English**

All changes follow [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Version numbers follow [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

## [2.3.3] - 2026-11-10

### Fixed

- Section caches and disconnected snapshots are isolated by the complete read arguments, preventing data from leaking across components, properties, filters, and default-value baselines; different Sections can still reuse covering results.

## [2.3.2] - 2026-11-05

### Added

- Long editor operations support progress notifications, owned task history, and cancellation under the same session identity. Task responses are not cached and writes are not retried after disconnection. Read caches and disconnected snapshots are suspended for the target instance during asynchronous writes, then cleared and restored after all known writes finish.

### Changed

- Standard MCP images pass through inline with their metadata; responses containing images bypass large-text offloading and proxy caching.
- The plugin display name and store description emphasize Unreal Engine and NexusLink; generated connection configuration includes authentication.

### Fixed

- Explicit `TtlSeconds: 0` responses bypass caching and clear the corresponding old caches and disconnected snapshots; standard structured responses, text JSON, and legacy fields are supported.
- Project-wide writes such as GameplayTags invalidate the target UE instance's read cache, including partial failures; other instances stay isolated.
- Offline connection guidance uses the current SearchCapabilities tool name.

## [2.3.1-beta.1] - 2026-10-28

> Pre-release; use outside production environments only.

### Changed

- Responses containing standard MCP image blocks retain inline images and existing metadata, bypassing large-text offloading and proxy caching.

- Add Unreal Engine to the display name, align Chinese and English READMEs and store descriptions, and highlight NexusLink's purpose, UE plugin dependency, and GitHub installation entry. Connection steps use generated configuration that includes authentication.

### Added

- Long editor operations use dedicated connections with per-request MCP progress and wait cancellation; session identities are isolated, task-state caching and write retries are disabled.


## [2.3.0] - 2026-10-27

### Added

- Client configuration supports Cursor / Windsurf, Codex, Claude Code / VS Code, CodeBuddy, Cline, and Roo Code. Identical formats share an option; Codex automatically generates Streamable HTTP TOML.
- Stable plugin packages are submitted to JetBrains Marketplace automatically.

### Changed

- Client configuration opens in a separate window with Copy and Close fixed at the bottom; placement instructions and copy results are displayed separately.
- Infrequently used UE discovery options are collapsed, the machine token is hidden by default, and copied content contains only client configuration.

## [2.2.0] - 2026-10-23

### Added

- The status bar provides Open Log Directory even while the proxy is disabled. Every startup creates separate logs with 4 MiB rotation, while retaining idea.log.

### Fixed

- Fix disconnections caused by concurrent initialization and session-capacity reclamation; protect active sessions and support client-initiated session deletion.
- Add diagnostic logs for MCP sessions, HTTP, tool calls, and UE connections without recording sensitive data.

## [2.1.2] - 2026-10-22

### Changed

- The status bar always shows connection state; remove the recent-call label and retain forwarding pause state in the tooltip.

### Fixed

- Support both released 2.x and current tool-call formats, handling cache lifetimes and dangerous-write confirmation correctly while forwarding requests unchanged.
- Read caches and disconnected snapshots are isolated by UE instance. Batch calls, including partial failures, invalidate the target instance's cache; failed reads are never cached.
- Large-response temporary files are isolated by session. Old and new 2.x processes cannot delete one another's results, and offloading preserves the original cache lifetime.
- Reject invalid target ports directly instead of forwarding to the current instance.

## [2.1.1] - 2026-10-08

### Changed

- docs: Replace coined terms in documentation and UI with ordinary wording: authentication and confirmation before writes.

### Fixed

- fix(api): Use `DialogWrapper` for network-adapter selection instead of deprecated `Messages.showChooseDialog`, removing Rider 2026.3 validator warnings about deprecated APIs.
- fix(proxy): Always Allow This Session remembers the capability only within the current MCP session.
- fix(mcp): Set HTTP header-read timeout to 10s and idle timeout to 60s; limit SSE connections to 32.
- fix(config): Limit the UE scan range width to 200; reject invalid ports, intervals, and excessively wide ranges when saving settings.
- fix(proxy): Use permissions 0700 for large-response directories and 0600 for files; clean startup leftovers and delete files older than one hour during writes.
- fix(ui): Confirm the token exposure risk of plaintext WebSocket when first configuring remote UE; copied MCP configuration uses the actual listening port.

## [2.1.0] - 2026-09-21

### Added

- feat(unreal): Prefer `hostKind=Editor` for multi-instance auto-connection, falling back to `netRole=Editor` for older UE; expose `hostKind` / `hasPlayWorld` in `list_unreal_instances`.

### Changed

- docs: Change the UE plugin installation path to `Plugins/NexusLink`.

## [2.0.1] - 2026-09-04

### Changed

- ui: Generate one AI-client configuration snippet at a time by transport (Streamable HTTP / SSE) and client (Cursor / CodeBuddy), so copying never includes another client's configuration.

## [2.0.0] - 2026-08-31

### Added

- feat(mcp): Add a proxy session layer with TTL/section read caches, disconnected `degraded` snapshots, a configurable write gate, status-bar pause/resume of agent forwarding and recent calls, and temporary-file offloading for oversized responses.
- feat(mcp): Support LAN access and a remote UE list (`host:mcpPort token`), using `host:port` as instance identity.
- feat(ui): Display the machine authentication token in settings with an adjacent Copy button that copies only the token.

### Changed

- perf(mcp): Make `handleInitialize` connection text stable and defer live state to tools/list, preserving `cache_control: ephemeral` across WebSocket connection changes.
- docs: Turn the README into a product landing page covering installation, settings, and FAQ; link the shared port and switch matrix to NexusLink `docs/usage-guide.md`.
- feat(mcp): Use one machine token shared with UE / Desktop / VSCode in `NexusLink/mcp-auth-token`; add Require MCP Auth, enabled by default, and comma-separated extra tokens and Bearer values. Local UE reads the token file automatically. Configuration generation supports IP selection with multiple adapters and includes only the machine token in Bearer. Confirm LAN access without authentication; link README authentication guidance to usage-guide §1.1.
- ui: Manage extra authentication tokens with an add/remove list instead of manually typed semicolon or comma separators.

### Fixed

- fix(mcp): Restart listening immediately after saving the MCP port; rebuild discovery tasks immediately after saving scan ranges or intervals.
- fix(proxy): Cancel the dialog future after a write-gate timeout returns DENY, freeing the thread pool and preventing a delayed duplicate prompt.
- refactor(mcp): Use `NexusMcpAuth.MAX_BODY_BYTES` consistently for the HTTP body limit instead of duplicating a literal in the Netty pipeline.

### Security

- Require Bearer authentication and reject Origin on MCP `/stream`; remove CORS `*`.
- Read the instance-registry token when connecting to UE and send `auth` as the first WebSocket frame; `GET /status` does not follow redirects.
- Return structured JSON from `exceptionCaught` instead of concatenating raw exception text.
- Keep loopback binding as the default; document default loopback and optional LAN access in `plugin.xml`.

## [1.4.5] - 2026-07-17

### Added
- feat: Report proxy forwarding failures, including disconnections, timeouts, and failed `connect_unreal_instance`, through the new `nexus/proxy_feedback` method to UE `.nexus-feedback/`, allowing AI feedback to include relay errors. Buffer in-process and resend after reconnecting; older NexusLink versions without this method degrade silently without disrupting normal use.

## [1.4.4] - 2026-07-07

### Fixed
- Do not automatically reconnect after the user manually disconnects UE until they select an instance or restart the plugin.

### Docs
- Add the VSCode proxy and standalone NexusDesktop tray app to the README connection-method comparison.

### Chore
- chore(release): Support Pre-release publishing: `X.Y.Z-beta.N` creates a GitHub pre-release, and `extract_release_notes.py` validates pre-release versions.

## [1.4.3] - 2026-06-29

- fix(api): Read the plugin version from Gradle-injected `nexus-mcp-version.txt`, removing all `PluginManager`/`PluginManagerCore` (`@ApiStatus.Internal`) calls and Marketplace internal-API warnings.
- fix(api): Compile Kotlin with `-jvm-default=no-compatibility` to avoid compatibility bridges for inherited deprecated interface defaults. Remove three deprecated API reports, including `StatusBarWidget.getPresentation(PlatformType)` / `PlatformType`, and redundant `@Suppress("OVERRIDE_DEPRECATION")` annotations.

## [1.4.2] - 2026-06-26

- fix(api): Read the `NexusMcpDispatcher` version with public `PluginManager.getPluginByClass()` instead of internal `PluginManagerCore`.
- fix(api): Add `@Suppress("OVERRIDE_DEPRECATION")` to `NexusLinkStatusBarWidget.getPresentation()` and remove deprecated `StatusBarWidgetFactory.canBeEnabledOn()` overrides, whose default is true on 253+, eliminating Marketplace deprecated/internal API warnings.

## [1.4.1] - 2026-06-26

- docs: Put English before Chinese in the bilingual `plugin.xml` `<description>` to meet JetBrains Marketplace validation requirements.

## [1.4.0] - 2026-06-26

- docs: Put Chinese before English in `plugin.xml` `<description>` and complete the Chinese overview, capabilities, installation, quick start, features, and privacy sections, matching the NexusVSCode store description order.
- docs: Replace the README's own nexus-rider version requirement (`≥ 1.3.3`) with a recommendation to use the latest release, avoiding release drift while retaining UE 4.26+, Rider 2025.3, and other environment requirements.
- feat(unreal): Add application-level keepalive pings every 5s while busy and 15s while idle, reusing `reconnectExecutor`. Start after successful `connectTo` and cancel in `resetWsConnection`, replacing passive `connectionLostTimeout=0` behavior and aligning with VSCode `WS_KEEPALIVE` constants.
- feat(ui): Add Disconnect to the connected status-bar popup, invoking `manager.disconnect()` to match VSCode.
- feat(ui): Add Copy to the AI-client settings section to copy the current preview to the system clipboard, matching VSCode `copyMcpConfig`.
- fix(unreal): Increase `probeStatus` `connectTimeout` from 500 to 1000ms to match VSCode HTTP probing.
- fix(mcp): Parse JSON in `handlePost` to identify `initialize`, preventing unused sessions when tool arguments contain that substring; retain the previous `contains` check as a fallback after parsing failure.
- feat(mcp): Store `httpSessions` in a synchronized ordered `LinkedHashMap`; above 50 entries, evict the oldest noncurrent session to prevent reconnect loops from growing memory.
- feat(ui): Make Copy a silent no-op when the preview is placeholder text or blank, avoiding copying instructions accidentally.
- feat(mcp): Read `NexusMcpDispatcher.SERVER_VERSION` lazily from `PluginManagerCore` so packaged `serverInfo.version` contains the actual version.
- feat(ui): Read the current port input when clicking Streamable HTTP Configuration or SSE Configuration, allowing correct previews before applying settings; make hints dynamic.
- feat(mcp): Write SSE comment heartbeats `: keepalive` every 20s through the Netty eventLoop and cancel scheduling on `closeFuture`, preventing idle proxy/NAT disconnects and lost notifications.
- fix(unreal): Add an early `Content-Length` limit check and bounded chunked reads (64KB) in `probeStatus`, preventing abnormal ports from exhausting memory with oversized bodies.
- perf(mcp): Bound `handleInitialize` warmup to 2s (`INITIALIZE_WARMUP_MS`) with `Future.get(2s)`. On timeout, return the prefix and keep warmup running in the background so full-port scanning cannot block the handshake.
- perf(unreal): Resolve `wsPort` from the `instances` cache in `forwardToolCallToPort`, falling back to `probeStatus` only on a miss to avoid redundant HTTP round trips in cross-instance calls.
- feat: Warn through `log.warn` and `notifyError` when `startServer` detects `mcpPort` within `[scanMin, scanMax]`, avoiding probing the proxy port as UE.

## [1.3.7] - 2026-06-26

- docs: Remove manually maintained Current Version / Plugin ID lines from the README; use CHANGELOG/Releases for versions.
- docs: Improve the `plugin.xml` `<description>` overview, primarily in English with Chinese, adding NexusLink GitHub/Releases links, installation and enablement steps, and loopback-binding/no-telemetry statements.
- chore: Add `META-INF/pluginIcon.svg` and dark `pluginIcon_dark.svg`, using a 40×40 hexagon and connection hub to meet Marketplace logo requirements.
- chore(ci): Require the Release `--verify` check; release notes come exclusively from the CHANGELOG section and cannot be handwritten.
- docs: Add a README release workflow consistent with NexusLink / NexusVSCode.
- chore(ci): Add a tag-triggered Release workflow that builds packages and publishes GitHub Releases automatically.
- docs: Add README.en.md and top-of-page language links in both READMEs.
- chore: Correct the `plugin.xml` vendor to byteyang; configure GitHub repository description and topics.
- fix(ci): Fix missing executable permissions on gradlew in Linux Actions runners with `chmod +x` and a workflow fallback.

## [1.3.6] - 2026-06-24

- docs: Rewrite the README as a self-contained repository guide with architecture, setup, proxy-tool reference, FAQ, and public Release links.
- docs: Correct UE discovery guidance in settings, removing the obsolete process-enumeration description.
- chore: Add `scripts/build_rider.py` and a GitHub Actions build workflow.

## [1.3.5] - 2026-06-09

- Fix stale tool lists and failed calls after AI clients (Cursor/Codebuddy) reconnect: warm tools/list and send list_changed after initialized, resend on same-port WebSocket recovery, and retain tools during disconnection.

## [1.3.4] - 2026-06-04

- Consume UE `nexus/proxy_config` for dynamic connection-tool descriptions and errors. Connect to UE and fetch complete instructions during `initialize`, fixing AI clients that skipped MCP on their first turn.

## [1.3.3] - 2026-06-04

- Optimize WebSocket handling: prefer persistent forwarding through `ensureLongConnection`; lightweight `maintainConnection` heartbeat reduces steady-state probing from 101 ports per cycle to one. Disable library ping checks (`connectionLostTimeout=0`) and serialize discovery/reconnection with `connectionLock` to eliminate connection churn.

## [1.3.2] - 2026-06-01

- Harden persistent WebSockets with a 120s tools/list timeout, serialized requests, `connectionLostTimeout=120`, and connection generations to prevent incorrect disconnects; restore persistent forwarding by default.
- With asynchronous UE message dispatch, fix slow tools blocking Tick and disconnecting the IDE's persistent connection.

## [1.3.1] - 2026-06-01

- fix: Increase tools/call forwarding timeout from 5s to 120s and distinguish disconnected and timed-out errors.
- fix: Record the preferred port in `connect_unreal_instance` so scheduled scans cannot override manual selection.
- fix: Use one-shot WebSockets by default for tools/call to connected instances, preventing the persistent connection from closing prematurely during slow tools.

## [1.3.0] - 2026-05-09

- feat: Append UE `InitializeInstructions.md` to `initialize.instructions`. After connecting, `UnrealInstanceManager` asynchronously calls the new `nexus/instructions` WebSocket method and caches its content. `handleInitialize` appends an `--- Upstream (Unreal) ---` section after its three-line proxy guide, exposing the complete capability workflow (Tool Model, Workflows, Rules, Feedback, Filter syntax) in one handshake; clear the cache on disconnection.
- docs: Update tool names in `NexusMcpDispatcher` connection examples from `list_assets` to `search_assets` to `search_asset`, matching UE NexusLink.
- refactor: Remove proxy-side `call_tool`; direct single-capability calls use UE `call_capability` instead.

## [1.2.2] - 2026-04-24

- fix: Hide `call_tool` from tools/list without a UE connection; show it only when connected in Starter/Custom mode.
- fix: Remove `cache_control: ephemeral` from tools/list responses so client caching cannot hide connection-state or mode changes.
- fix: Determine `call_tool` visibility from the actual tool list by checking for `search_tools`; switching Full/Starter mode takes effect after refreshing tools without reconnecting.
- fix: Strengthen initialize.instructions guidance to call tools directly while connected; use `connect_unreal_instance` only when tools/list has no UE tools, avoiding repeated serialized connection flows.
- fix: Change disconnected errors from `"No connected UE instance or forward failed"` to `"No connected UE instance. Call connect_unreal_instance first."` to guide active reconnection.

## [1.2.1] - 2026-04-24

- perf: `handleToolsList` controls `call_tool` visibility using the UE instance's `toolsListMode`: hide it in Full mode, where all tools are visible, and retain it in Starter/Custom mode.
- perf: `UnrealInstanceManager.probeStatus` reads `toolsListMode` from `/status` and exposes `connectedToolsListMode` to the dispatcher.

## [1.2.0] - 2026-04-24

- fix: Correct port-collision log interpolation in `NexusMcpServer.start` from `$this.port` to `${this.port}`, printing the port instead of the object's toString.
- fix: Correct active-SSE count logging in `sendToolsChangedNotification`: `sseContexts.size` is already post-removal, so do not subtract `dead.size` again and produce negative counts.
- fix: Correct refresh-task creation in `NexusLinkStartupActivity.startServer`. Previously `isDone == false` returned while a task existed, but stopServer canceled it; now reuse an active task or cancel and recreate the old one, preserving scheduled scans after stop/start cycles.
- fix: Clear `preferredPort` in `UnrealInstanceManager.disconnect` so the next `discoverInstances` cycle cannot undo a manual disconnect.
- fix: Pass `setPreferred=true` when selecting an instance in `NexusLinkStatusBarWidget`, preserving manual selection against auto-discovery.
- fix: Swap reversed `scanPortStart > scanPortEnd` settings in `scanPortsParallel`, avoiding `IllegalArgumentException` from a negative `Executors.newFixedThreadPool` size.
- fix: Use shared daemon single-thread `reconnectExecutor` for asynchronous rescans in `UnrealInstanceManager.onClose`, eliminating leaked unclosed executors created per disconnect.
- fix: Explicitly call `oneShot?.close()` after `forwardToolCallToPort` times out, preventing hanging one-shot WebSockets.
- fix: Prioritize `preferredPort` in discoverInstances auto-reconnect, restoring the manually chosen instance before the Editor; add `setPreferred` to `connectTo`.
- fix: Update initialize instructions to call tools directly while connected and run list/connect only when disconnected, avoiding redundant discovery and connection steps.
- feat: During multi-instance discovery, prefer automatic connection to `netRole == "Editor"`; otherwise auto-connect only when a single instance exists, reducing AI initialization round trips.
- fix: Trigger asynchronous `discoverInstances` immediately on WebSocket onClose, reducing UE crash/restart recovery delay instead of waiting for the next scheduled scan.
- fix: Check `wsClient?.isOpen` in list_unreal_instances.connected to prevent false positives from half-open TCP after UE crashes.
- fix: Broadcast `notifications/tools/list_changed` only after reconnection, not on disconnect, matching nexus-vscode and preventing Cursor from downgrading tools.
- feat: Add the `call_tool` proxy tool accepting `{name, arguments}` and forwarding to the connected UE instance, letting AI invoke nonstarter tools discovered with search_tools in Starter mode and completing progressive discovery and invocation.
- perf: P2 Prompt Caching adds `cache_control: {"type": "ephemeral"}` to initialize and tools/list responses for clients supporting the Anthropic beta.
- fix: Add the `GET /stream` SSE endpoint using the same handler as GET /sse, complying with Streamable HTTP.
- refactor: Remove detection of UE processes without NexusLink (`UeProcessInfo`, `ueProcessesWithoutNexus`, process enumeration, and status-bar hints) because it was inaccurate and incurred enumeration overhead.

## [1.1.0] - 2026-04-20

- chore(script): `build_rider.py` injects the latest five CHANGELOG versions into `plugin.xml <change-notes>` during packaging, populating the JetBrains Marketplace Changelog tab.
- feat: Support optional `arguments.targetPort` in tools/call, routing once to a specified UE instance with a one-shot WebSocket without changing the persistent connection, for concurrent DS and multiple-client queries.
- feat: Add `netRole` (`DedicatedServer`/`ListenServer`/`Client`/`Standalone`/`Editor`) from UE `/status` to list_unreal_instances responses.
- docs: Add the MIT LICENSE file.
- chore: Add GitHub Actions smoke builds (`.github/workflows/build.yml`) checking `./gradlew buildPlugin` automatically.

## [1.0.2] - 2026-04-15

- refactor: Isolate per-session dispatchers on HTTP using `Mcp-Session-Id`, allowing concurrent AI clients without interference.
- fix: Standardize the MCP protocol version to `2025-06-18`, matching nexus-unreal.
- docs: Correct NexusRider's Netty dependency declaration to `compileOnly`.

## [1.0.1] - 2026-04-14

- fix: Convert list_unreal_instances and connect_unreal_instance from custom MCP methods to standard tools.
- fix: Add clean to the build script to ensure a full compilation every time.

## [1.0.0] - 2026-04-13

- feat: Implement a standalone Netty MCP HTTP server supporting Streamable HTTP and SSE.
- feat: Discover UE instances through a three-stage process-enumeration, registry-file, and parallel-port-scan strategy.
- feat: Proxy tool calls to UE through persistent WebSockets.
- feat: Add settings under Tools > Nexus MCP for ports and one-click Cursor/CodeBuddy configuration copying.
- feat: Add a status-bar widget showing live UE connection state and allowing target switching.
- feat: Discover new UE instances in the background every 5s.
- fix: Improve stability through WebSocket binary-frame decoding, MCP version negotiation, and thread-pool isolation.
