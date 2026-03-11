# agent-mod

Minecraft Forge 1.20.1 AI agent mod — FakePlayer that executes natural language commands via Claude Agent SDK.

## Build & Run

```bash
./gradlew build                    # Build mod JAR
./gradlew runClient                # Launch Minecraft with agent-mod
cd agent-runtime && npm install && npm run build  # Build TypeScript runtime
```

## Project Structure

```
agent-mod/                        # Standalone Gradle project + git repo
├── src/                          # Java Forge mod (FakePlayer, HTTP bridge, actions)
├── agent-runtime/                # TypeScript (Claude Agent SDK + MCP server)
│   ├── src/                      # Runtime source
│   └── tests/                    # Tests (mock bridge, integration)
├── docs/                         # Design docs & planning
├── build.gradle                  # Forge mod build config
└── settings.gradle               # Gradle root (self-contained)
```

## Conventions

- Forge 1.20.1 (forge 47.2.0), Java 17, Gradle 8.1.1
- Package root: `com.pyosechang.agent`
- agent-runtime: Node.js, TypeScript, ESM
- HTTP bridge on localhost:0 (auto port), port file at `run/.agent/bridge-server.json`

## Testing (no Minecraft needed)

```bash
cd agent-runtime
npm run test:mock    # SDK → MCP → mock HTTP bridge (full chain)
npm run test:mcp     # MCP server tool listing
```
