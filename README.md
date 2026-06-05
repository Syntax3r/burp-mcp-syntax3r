# Burp MCP Server — Session Variable Layer Fork

> **Community fork** of [PortSwigger/mcp-server](https://github.com/PortSwigger/mcp-server) by [@Syntax3r](https://github.com/Syntax3r)  
> Adds a **session variable substitution layer** that compresses repeated HTTP headers (JWT, Cookie, User-Agent) into `{{VAR}}` placeholders — reducing Claude token usage by up to **69% per request**.

---

## ⬇️ Quick Install (no build needed)

1. Go to [**Releases**](https://github.com/Syntax3r/burp-mcp-syntax3r/releases/latest) and download `burp-mcp-syntax3r.jar`
2. In Burp Suite: **Extensions → Add → Type: Java** → select the downloaded JAR
3. Open the **MCP** tab → **Variable Layer** sub-tab → flip the master toggle **ON**

That's it. Browse a target through Burp's proxy, then ask Claude to show you the proxy history — repeated headers will be automatically compressed.

---

## Why this fork?

When Claude calls tools like `get_proxy_http_history` or `send_http_request`, every entry repeats the same large header values. Over a typical bug-bounty engagement this adds up to hundreds of thousands of wasted input tokens.

### Token savings — real measurement (Opaque mode, 6-header set)

| Header | Raw tokens | With VarLayer | Saved |
|---|---|---|---|
| `Authorization: Bearer <JWT>` | ~182 tok | ~5 tok `{{JWT}}` | **177 tok** |
| `Cookie: <8 cookies>` | ~131 tok | ~5 tok `{{COOKIES}}` | **126 tok** |
| `User-Agent: Mozilla/5.0 …` | ~32 tok | ~5 tok `{{UA}}` | **27 tok** |
| **Total per request** | **~518 tok** | **~159 tok** | **−69%** |

| Engagement size | Raw total | With VarLayer | Saved |
|---|---|---|---|
| Quick recon (80 req) | 41,440 tok | 12,820 tok | **−28,620** |
| Medium (350 req) | 181,300 tok | 55,750 tok | **−125,550** |
| Deep dive (1,000 req) | 518,000 tok | 159,100 tok | **−358,900** |

---

## What's been added

### Session Variable Layer (VarLayer)

- **Compress on read** — when Claude reads proxy history or active editor content, repeated header values are replaced with `{{JWT}}`, `{{COOKIES}}`, `{{UA}}`, `{{UA_CH}}`, `{{ENC}}`, `{{LANG}}`
- **Expand on write** — when Claude sends a request using `{{VAR}}` placeholders, the layer substitutes real values before the request reaches Burp
- **Promotion threshold** — a value must appear N times (default: 3) before being promoted to a variable. Configurable.
- **JSON-aware** — handles the JSON-encoded format that `get_proxy_http_history` actually returns
- **Locked headers** — Host, Origin, Referer, Content-Length, Transfer-Encoding, X-Forwarded-*, X-Original-URL are **never** templated. These carry attack-surface information for host injection, request smuggling, and access-control bypass

### New UI inside the MCP tab

Four sub-tabs replace the original single panel:

| Tab | Contents |
|---|---|
| **Server** | Original config (host, port, auto-approve targets) — unchanged |
| **Variable Layer** | Master toggle · Apply-to (History, Repeater, Intruder, Scanner) · Default mode (Opaque / Structured) · Promotion threshold · Reveal-approval |
| **Sessions** | Live table of captured variables · Full value in preview · Horizontal scroll · Double-click column header to auto-fit · Right-click → copy cell / copy full raw value · Hover tooltip |
| **Audit Log** | 500-entry capped circular buffer · VAR_PROMOTED, VAR_UPDATED, VAR_REVEALED, JWT_EXPIRING events |

---

## Configuration

### Enable the layer
MCP tab → **Variable Layer** → enable **"Enable session variable substitution"**

### Tune the promotion threshold
Default is **3** — a header value must appear in 3 separate tool responses before being promoted. Set to **1** for instant promotion (useful for testing).

### Apply-to surface
By default the layer applies to **Proxy history** and **Repeater** reads. Intruder and Scanner are off because they generate wild header permutations that would pollute the variable store.

### Checking it works
After enabling and browsing a target through Burp, ask Claude:

> "Show me the last 5 requests from proxy HTTP history"

Then check:
- **Extensions → Output tab** — you should see `MCP VarLayer: promoted {{JWT}}` etc.
- **Sessions tab → Refresh** — captured variables appear in the table
- **Audit Log tab → Refresh** — `VAR_PROMOTED` events are listed

---

## Build from source

```bash
# Clone this fork
git clone https://github.com/Syntax3r/burp-mcp-syntax3r.git
cd burp-mcp-syntax3r

# Download the upstream MCP proxy JAR (required for the build)
mkdir -p libs
gh release download --repo PortSwigger/mcp-proxy \
  --pattern 'mcp-proxy-all.jar' --dir libs/

# Build
./gradlew shadowJar

# Output: build/libs/burp-mcp-all.jar
```

**Requirements:** Java 21+, Gradle (wrapper included)

---

## Roadmap

| Phase | Status | Description |
|---|---|---|
| 1A — Foundation | ✅ Merged | Hook infrastructure, session store, audit log, config persistence |
| 1B — Opaque mode | ✅ Merged | Actual compress/expand logic, JSON-aware, unit tested |
| 1C — UI | ✅ Merged | JTabbedPane, VarLayerPanel, SessionsPanel, AuditLogPanel |
| 1D — Structured mode | 🔜 Planned | Per-host scoping · JWT claims visible (`{{JWT\|alg=RS256 sub=… role=user}}`) · Cookie classification · per-header policy table |
| 1E — Reveal tool | 🔜 Planned | `burp_reveal_variable` MCP tool · Burp approval dialog |
| 1F — JWT expiry | 🔜 Planned | Expiry detection daemon · proactive warning to Claude |

---

## Attribution

This fork is based on [PortSwigger/mcp-server](https://github.com/PortSwigger/mcp-server).  
All original functionality is preserved. The original README continues below.

---

# Burp Suite MCP Server Extension

## Overview

Integrate Burp Suite with AI Clients using the Model Context Protocol (MCP).

For more information about the protocol visit: [modelcontextprotocol.io](https://modelcontextprotocol.io/)

## Features

- Connect Burp Suite to AI clients through MCP
- Automatic installation for Claude Desktop
- Comes with packaged Stdio MCP proxy server

## Usage

- Install the extension in Burp Suite
- Configure your Burp MCP server in the extension settings
- Configure your MCP client to use the Burp SSE MCP server or stdio proxy
- Interact with Burp through your client!

## Installation

### Prerequisites

Ensure that the following prerequisites are met before building and installing the extension:

1. **Java**: Java must be installed and available in your system's PATH. You can verify this by running `java --version` in your terminal.
2. **jar Command**: The `jar` command must be executable and available in your system's PATH. You can verify this by running `jar --version` in your terminal. This is required for building and installing the extension.

### Building the Extension

1. **Clone the Repository**: Obtain the source code for the MCP Server Extension.
   ```
   git clone https://github.com/PortSwigger/mcp-server.git
   ```

2. **Navigate to the Project Directory**: Move into the project's root directory.
   ```
   cd mcp-server
   ```

3. **Build the JAR File**: Use Gradle to build the extension.
   ```
   ./gradlew embedProxyJar
   ```

   This command compiles the source code and packages it into a JAR file located in `build/libs/burp-mcp-all.jar`.

### Loading the Extension into Burp Suite

1. **Open Burp Suite**: Launch your Burp Suite application.
2. **Access the Extensions Tab**: Navigate to the `Extensions` tab.
3. **Add the Extension**:
    - Click on `Add`.
    - Set `Extension Type` to `Java`.
    - Click `Select file ...` and choose the JAR file built in the previous step.
    - Click `Next` to load the extension.

Upon successful loading, the MCP Server Extension will be active within Burp Suite.

## Configuration

### Configuring the Extension
Configuration for the extension is done through the Burp Suite UI in the `MCP` tab.
- **Toggle the MCP Server**: The `Enabled` checkbox controls whether the MCP server is active.
- **Enable config editing**: The `Enable tools that can edit your config` checkbox allows the MCP server to expose tools which can edit Burp configuration files.
- **Advanced options**: You can configure the port and host for the MCP server. By default, it listens on `http://127.0.0.1:9876`.

### Claude Desktop Client

To fully utilize the MCP Server Extension with Claude, you need to configure your Claude client settings appropriately.
The extension has an installer which will automatically configure the client settings for you.

1. Currently, Claude Desktop only support STDIO MCP Servers
   for the service it needs.
   This approach isn't ideal for desktop apps like Burp, so instead, Claude will start a proxy server that points to the
   Burp instance,  
   which hosts a web server at a known port (`localhost:9876`).

2. **Configure Claude to use the Burp MCP server**  
   You can do this in one of two ways:

    - **Option 1: Run the installer from the extension**
      This will add the Burp MCP server to the Claude Desktop config.

    - **Option 2: Manually edit the config file**  
      Open the file located at `~/Library/Application Support/Claude/claude_desktop_config.json`,
      and replace or update it with the following:
      ```json
      {
        "mcpServers": {
          "burp": {
            "command": "<path to Java executable packaged with Burp>",
            "args": [
                "-jar",
                "/path/to/mcp/proxy/jar/mcp-proxy-all.jar",
                "--sse-url",
                "<your Burp MCP server URL configured in the extension>"
            ]
          }
        }
      }
      ```

3. **Restart Claude Desktop** - assuming Burp is running with the extension loaded.

## Manual installations
If you want to install the MCP server manually you can either use the extension's SSE server directly or the packaged
Stdio proxy server.

### SSE MCP Server
In order to use the SSE server directly you can just provide the url for the server in your client's configuration. Depending
on your client and your configuration in the extension this may be with or without the `/sse` path.
```
http://127.0.0.1:9876
```
or
```
http://127.0.0.1:9876/sse
```

### Stdio MCP Proxy Server
The source code for the proxy server can be found here: [MCP Proxy Server](https://github.com/PortSwigger/mcp-proxy)

In order to support MCP Clients which only support Stdio MCP Servers, the extension comes packaged with a proxy server for
passing requests to the SSE MCP server extension.

If you want to use the Stdio proxy server you can use the extension's installer option to extract the proxy server jar.
Once you have the jar you can add the following command and args to your client configuration:
```
/path/to/packaged/burp/java -jar /path/to/proxy/jar/mcp-proxy-all.jar --sse-url http://127.0.0.1:9876
```

### Creating / modifying tools

Tools are defined in `src/main/kotlin/net/portswigger/mcp/tools/Tools.kt`. To define new tools, create a new serializable
data class with the required parameters which will come from the LLM.

The tool name is auto-derived from its parameters data class. A description is also needed for the LLM. You can return
a string (or richer PromptMessageContents) to provide data back to the LLM.

Extend the Paginated interface to add auto-pagination support.
