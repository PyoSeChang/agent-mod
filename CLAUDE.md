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
agent-mod/
├── src/main/java/com/pyosechang/agent/
│   ├── AgentMod.java                 # Entry point: action registration, lifecycle
│   ├── core/
│   │   ├── AgentAnimation.java       # lookAt/swingArm packet broadcast
│   │   ├── AgentLogger.java          # Structured JSONL logger (per-session)
│   │   ├── AgentTickHandler.java     # Server tick: action tick + auto item pickup
│   │   ├── FakePlayerManager.java    # FakePlayer spawn/despawn + visibility packets
│   │   ├── ObservationBuilder.java   # Build state observation JSON
│   │   ├── action/
│   │   │   ├── Action.java           # Sync action interface
│   │   │   ├── AsyncAction.java      # Multi-tick action interface (+ getTimeoutMs)
│   │   │   ├── ActiveActionManager.java  # Singleton: one active async action
│   │   │   ├── ActionRegistry.java   # Name → Action mapping
│   │   │   ├── MoveToAction.java     # A* pathfinding + tick walk (async)
│   │   │   ├── MineBlockAction.java  # Tick-based mining with crack animation (async)
│   │   │   ├── MineAreaAction.java   # Area mining: walk+mine state machine (async)
│   │   │   ├── UseItemOnAction.java  # Right-click block face (sync)
│   │   │   ├── UseItemOnAreaAction.java  # 2D area right-click, serpentine (async)
│   │   │   ├── SequenceAction.java   # Action array executor (async)
│   │   │   ├── CraftAction.java      # Real crafting: ingredients → output (sync)
│   │   │   ├── EquipAction.java      # Equip to slot (sync)
│   │   │   ├── AttackAction.java     # Attack entity (sync)
│   │   │   ├── InteractAction.java   # Right-click entity (sync)
│   │   │   ├── PlaceBlockAction.java # Place block from inventory (sync)
│   │   │   ├── PickupItemsAction.java # Pick up nearby items (sync)
│   │   │   ├── DropItemAction.java   # Drop item (sync)
│   │   │   ├── OpenContainerAction.java  # Open chest/furnace (sync)
│   │   │   ├── ClickSlotAction.java  # Click container slot (sync)
│   │   │   ├── CloseContainerAction.java # Close container (sync)
│   │   │   └── SmeltAction.java      # Smelting recipe lookup (sync)
│   │   └── pathfinding/
│   │       ├── Pathfinder.java       # A* algorithm
│   │       └── PathFollower.java     # Tick-based smooth movement
│   ├── network/
│   │   └── AgentHttpServer.java      # HTTP bridge (localhost:0, dynamic timeout)
│   ├── command/                      # In-game commands
│   ├── compat/                       # Mod compatibility (AE2, Create)
│   ├── monitor/                      # Chat monitoring, intervention, terminal
│   └── runtime/
│       └── RuntimeManager.java       # Launch/stop agent-runtime process
├── agent-runtime/                    # TypeScript (Claude Agent SDK + MCP)
│   └── src/
│       ├── index.ts                  # SDK query loop (maxTurns=50)
│       ├── mcp-server.ts             # 25 MCP tools → HTTP bridge
│       └── prompt.ts                 # System prompt for Claude
├── scripts/
│   └── parse-agent-log.js           # Pretty-print JSONL logs
└── run/.agent/
    ├── bridge-server.json            # HTTP port file (auto-generated)
    └── logs/                         # Session logs (YYYY-MM-DD_HHmmss.jsonl)
```

## Architecture

```
[Player command] → RuntimeManager → agent-runtime (Claude SDK)
                                      ↓
                                    MCP Server (25 tools)
                                      ↓
                                    HTTP Bridge (AgentHttpServer)
                                      ↓
                                    Action System → FakePlayer
```

## Components (for changelog tracking)

| ID | Scope | Key files |
|----|-------|-----------|
| `brain` | Claude's decisions (prompt, tool descriptions, config) | prompt.ts, mcp-server.ts, index.ts |
| `actions` | Action implementations (what happens) | core/action/*.java |
| `body` | Visual/physical presence (animation, pickup) | AgentAnimation, AgentTickHandler, FakePlayerManager |
| `infra` | Plumbing (timeouts, pathfinding, bridge) | AsyncAction, AgentHttpServer, Pathfinder |

See `docs/quality/components.md` for full definitions.

## Key Actions

| Action | Type | Description |
|--------|------|-------------|
| `move_to` | async | A* pathfinding + tick walk |
| `mine_block` | async | Single block mining with crack animation |
| `mine_area` | async | Rectangular area mining (max 256 blocks) |
| `use_item_on` | sync | Right-click block face |
| `use_item_on_area` | async | 2D area right-click (serpentine walk) |
| `execute_sequence` | async | Run action array sequentially |
| `craft` | sync | Real crafting (ingredient consume, batch count) |
| `equip` | sync | Equip item to slot |

## Conventions

- Forge 1.20.1 (forge 47.2.0), Java 17, Gradle 8.1.1
- Package root: `com.pyosechang.agent`
- agent-runtime: Node.js, TypeScript, ESM
- HTTP bridge on localhost:0 (auto port), port file at `run/.agent/bridge-server.json`
- All async actions support dynamic timeout via `getTimeoutMs()`
- All actions broadcast lookAt/swingArm animations
- Auto item pickup every tick (2-block radius)

## Logging

Structured JSONL logs per session at `run/.agent/logs/`.

```bash
# Pretty-print latest log
cd agent-mod && node scripts/parse-agent-log.js

# Summary only
node scripts/parse-agent-log.js --summary

# Failures only
node scripts/parse-agent-log.js --failures

# Specific file
node scripts/parse-agent-log.js run/.agent/logs/2026-03-11_223507.jsonl
```

## Quality Tracking

Version history, test scenarios, and run records in `docs/quality/`.

```
docs/quality/
├── CHANGELOG.md              # Version history [component tags]
├── components.md             # Component definitions
├── scenarios/                # Test scenario definitions
│   ├── _registry.md          # Scenario index (active/retired)
│   ├── gathering.md
│   ├── farming.md
│   └── composite.md
└── runs/                     # Test run results per version
    └── v0.2.0/
```

## Testing (no Minecraft needed)

```bash
cd agent-runtime
npm run test:mock    # SDK → MCP → mock HTTP bridge (full chain)
npm run test:mcp     # MCP server tool listing
```
