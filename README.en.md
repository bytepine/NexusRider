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

The panel has **Streamable HTTP** / **SSE** buttons that generate copy-paste JSON for AI clients.

### 4. Status bar

| Display | Meaning |
|---------|---------|
| **⬢ project name** | Connected to UE |
| **⬡ Nexus** | Not connected |

Click to switch instances. A single instance auto-connects; multiple instances prefer `netRole=Editor`. Tool-list cache is kept across disconnects. The popup can pause/resume agent forwarding. Session contract: [proxy-session.md](https://github.com/bytepine/NexusLink/blob/master/docs/proxy-session.md).

---

## AI client

Default `http://127.0.0.1:6800/stream`. On collision the port advances; use the startup notice or settings panel. Bound to `127.0.0.1`.

**Cursor** (`~/.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "nexus-unreal": {
      "url": "http://127.0.0.1:6800/stream"
    }
  }
}
```

Legacy MCP clients can use `http://127.0.0.1:6800/sse`. Copy snippets for CodeBuddy / Windsurf from the settings panel.

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

`%LOCALAPPDATA%\JetBrains\Rider<version>\log\idea.log` (macOS/Linux: `~/Library/Logs/JetBrains/Rider<version>/idea.log`). Search `Nexus MCP`.

### Asset edits not on disk

That is NexusLink persist behavior, not this proxy. See the [usage-guide FAQ](https://github.com/bytepine/NexusLink/blob/master/docs/usage-guide.md).

---

## Local build & release

```bash
./gradlew buildPlugin    # Windows: gradlew.bat buildPlugin
```

Output: `build/distributions/*.zip`. Debug: `./gradlew runIde` (local Rider 2025.3 + JDK 21).

GitHub Release notes come only from `CHANGELOG.md` (`py scripts/extract_release_notes.py --version X.Y.Z --verify`). Tag: `nexus-rider-vX.Y.Z`. Record product changes in [CHANGELOG.md](CHANGELOG.md) `[Unreleased]`.

Source: `src/main/kotlin/com/nexusmcp/mcp/`

---

## License

[MIT](LICENSE) © byteyang
