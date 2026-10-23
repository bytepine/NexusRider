**Language / Language**: [简体中文](README.md) · **English**

# nexus-rider — Rider Plugin

JetBrains Rider MCP **proxy**: local HTTP server (default `:6800`), discovers UE instances, and forwards AI tool calls to **NexusLink** over WebSocket. Blueprints, assets, PIE, and other capabilities come from the UE plugin; this plugin does not implement game logic.

Ports and switch layers: [NexusLink usage guide](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md). This plugin is the IDE layer of the three-layer switch. Do not run NexusDesktop or the VSCode proxy on the same machine at the same time.

---

## Requirements

| Component | Requirement |
|-----------|-------------|
| **nexus-rider** | Align with UE `proxy_config.minProxyVersion`; use the latest release |
| **NexusLink** | `nexus-mcp-unreal-*.zip` from [NexusLink Releases](https://github.com/bytepine/NexusLink/releases); UE **4.26+** |
| **JetBrains Rider** | 2025.3+ (build `253`, through `263.*`) |
| **JDK** (local build only) | 21 |

---

## Install & use

> **Open a project**: the MCP server starts in `ProjectActivity`. The welcome screen alone will not listen, even if enabled.

### 1. UE prerequisites

1. Install and enable NexusLink, then check **Enable MCP Server** ([usage-guide §2](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md))
2. If unchecked, scans are empty and the status bar shows **⬡ Nexus**

### 2. Install this plugin

Preferred: Rider **Settings → Plugins → Marketplace**, search **Nexus MCP**.

Fallback: download the zip from [NexusRider Releases](https://github.com/bytepine/NexusRider/releases) → **Install Plugin from Disk** → restart → **open a project**.

Then **Settings → Tools → Nexus MCP** → check **Enable Nexus MCP Server** (off by default; starts immediately, default `:6800`).

### 3. Settings

**Settings → Tools → Nexus MCP**

| Setting | Default | Notes |
|---------|---------|-------|
| Enable Nexus MCP Server | `false` | Master switch; starts/stops immediately |
| MCP port | `6800` | AI client port; listen restarts immediately after save |
| Scan port range | `45000`–`45100` | UE discovery; applied immediately after save |
| Scan interval | `5` s | Periodic discovery; applied immediately after save |
| Write gate | `destructive` | `off` / `destructive` (delete, rename, stop PIE) / `all` |
| Allow LAN | off | Bind MCP to `0.0.0.0`; generated config uses this machine's LAN IP |
| Require MCP auth | on | Off: AI clients need no Bearer (legacy proxy); UE WS auth still follows the editor |
| Extra auth tokens | (empty) | Add via **+**; local UE reads the file automatically |
| Machine auth token | (read-only) | Shared on this PC; **Copy** writes the token only |
| Remote UE | (empty) | One `host:mcpPort [token...]` per line; no subnet scan |

Cross-machine and auth: [usage-guide §1](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md).

The panel lets you pick a transport (Streamable HTTP / SSE) and a client (Cursor / CodeBuddy) so each generate/copy is a single snippet; if LAN is on and multiple NICs exist, pick an IP first. Copied Bearer is this machine's token only.

### 4. Status bar

The status bar always shows the current connection state. The tooltip also indicates when agent forwarding is paused.

Batch calls, including partial failures, invalidate the target instance's read cache. Failed reads are never cached. Large-response files are isolated between hubs; only files older than one hour are cleaned up.

Released 2.x and current tool-call formats are supported without rewriting requests. New payloads use a separate temporary root so older 2.x processes cannot delete them during startup cleanup.

| Display | Meaning |
|---------|---------|
| **⬢ project name** | Connected to UE |
| **⬡ Nexus** | Not connected |

Click to switch instances. A single instance auto-connects; multiple instances prefer `hostKind=Editor` (legacy UE without that field falls back to `netRole=Editor`). Tool-list cache is kept across disconnects. Read caches and disconnected snapshots are isolated by UE host/port. The popup can pause/resume agent forwarding. Session contract: [proxy-session.md](https://github.com/bytepine/NexusLink/blob/master/docs/proxy-session.md).

---

## AI client

Default `http://127.0.0.1:6800/stream`. On collision the port advances; use the startup notice or settings panel. Bound to `127.0.0.1`.

**Cursor** (`~/.cursor/mcp.json`). Copy the token from the settings panel **Copy** next to the machine token. Multiple tokens: `Bearer <tok1>, <tok2>`. If **Require MCP auth** is off, omit `headers`. See [usage-guide §1.1](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md#11-身份验证).

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

Legacy MCP clients can use `http://127.0.0.1:6800/sse`. For CodeBuddy / Windsurf, pick that client in the settings panel, then copy.

When connected, `tools/list` merges UE tools. For concurrent multi-instance calls, pass `targetPort` in `arguments` (one-shot WS; does not change the long-lived binding).

---

## FAQ

### "MCP initialize timeout"

Confirm a **project is open**, **Enable Nexus MCP Server** is on, UE MCP is on, status bar shows **⬢ project name**, and the AI port matches the actual listen port.

### Multiple Rider project windows

The MCP server is per **project**. Extra windows may advance from `6800` (`6801`…). Use the port from that window's notice.

### Tool list does not refresh

`notifications/tools/list_changed` is pushed on connect/disconnect. Reconnect MCP or restart the AI session if the client did not update.

### Logs

Click **Nexus status bar → 打开日志目录 (Open log directory)**, including when the proxy is disabled. Each IDE/plugin startup creates a new `nexus-rider_<startup>_pid<PID>_<part>_*.log`; a new part is created after 4 MiB and old files are preserved. Project windows share the current startup log. Files are in `NexusMCP/` under Rider's log directory. Logs also remain in `idea.log`, accessible through **Help → Show Log in Explorer/Finder**.

Default INFO/WARN logs include client versions, session creation and initialization, invalid or evicted sessions, HTTP rejections, SSE connections, call start/completion/duration/failure, server stop reasons and connection recovery. Match sessions by their hashed `session` value and calls by `request` within one server run. `missing_session` / `unknown_session` distinguish absent headers from unknown IDs. Tokens, complete session IDs, tool arguments and response bodies are omitted.

The server retains up to 50 MCP sessions. At capacity, it reclaims only sessions with no requests for 30 minutes and no in-flight calls or SSE connections (`idle_timeout`). New connections never evict sessions that are initializing or active; if no capacity can be freed, initialization returns 503 with `Retry-After` (`session_limit`). Clients can end a session with `DELETE /stream` and its session ID (`client_delete`). Reclaimed IDs require a new `initialize`.

For disconnect reports, include the incident time, AI client name/version and client log alongside the relevant Rider log. Restarting the server invalidates sessions; clients must initialize again, which can be triggered by toggling the MCP connection. Routine scans and heartbeats do not produce INFO noise.

### Asset edits not on disk

That is NexusLink persist behavior, not this proxy. See the [usage-guide FAQ](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md).

---

## Local build & release

```bash
./gradlew buildPlugin    # Windows: gradlew.bat buildPlugin
```

Output: `build/distributions/*.zip`. Debug: `./gradlew runIde` (local Rider 2025.3 + JDK 21).

Run `./gradlew check buildPlugin` (`gradlew.bat check buildPlugin` on Windows) before submitting. The included `testProxyPolicy` task checks the PascalCase contract, local HTTP/WebSocket multi-instance routing, MCP session concurrency and capacity reclamation, and diagnostic redaction without launching Rider or UE. CI runs the same check before building.

GitHub Release notes come only from `CHANGELOG.md` (`py scripts/extract_release_notes.py --version X.Y.Z --verify`). Tag: `nexus-rider-vX.Y.Z`. Record product changes in [CHANGELOG.md](CHANGELOG.md) `[Unreleased]`.

Configure the `JETBRAINS_MARKETPLACE_TOKEN` repository secret under **Settings → Secrets and variables → Actions**, using a [Marketplace token](https://plugins.jetbrains.com/author/me/tokens) with permission to publish this plugin. Stable releases automatically submit the same ZIP from GitHub Release to JetBrains Marketplace; prereleases skip the store. The first plugin listing must be created manually, and uploaded updates remain subject to review.

To submit an existing stable release, open **Actions → Publish to JetBrains Marketplace → Run workflow** and enter its version (for example, `2.2.0`). This workflow accepts only existing stable GitHub Releases and uploads the published ZIP. The same version cannot be uploaded twice.

Source: `src/main/kotlin/com/nexusmcp/mcp/`

---

## License

[MIT](LICENSE) © byteyang
