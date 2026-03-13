# agent-mod

Minecraft Forge 1.20.1 AI agent mod — Multi-agent system with AgentPlayer (ServerPlayer subclass), persona-based roles, and scoped memory. Each agent runs its own Claude Agent SDK runtime with filtered MCP tools.

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
│   │   ├── AgentPlayer.java          # ServerPlayer subclass (sleep voting, invulnerability, no-op client ops)
│   │   ├── AgentNetHandler.java     # Mock network handler (EmbeddedChannel, absorbs packets)
│   │   ├── AgentContext.java         # Per-agent state bundle (ServerPlayer, ActionManager, Persona, etc.)
│   │   ├── AgentManager.java        # Multi-agent manager (spawn/despawn/routing)
│   │   ├── PersonaConfig.java       # PERSONA.md parser (role, personality, tools)
│   │   ├── AgentAnimation.java      # lookAt/swingArm packet broadcast
│   │   ├── AgentLogger.java         # Structured JSONL logger (per-session)
│   │   ├── AgentTickHandler.java    # Server tick: all agents action tick + auto item pickup
│   │   ├── ObservationBuilder.java  # Build state observation JSON (with scoped memory)
│   │   ├── action/
│   │   │   ├── Action.java           # Sync action interface
│   │   │   ├── AsyncAction.java      # Multi-tick action interface (+ getTimeoutMs)
│   │   │   ├── ActiveActionManager.java  # Per-agent: one active async action
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
│   │   │   ├── DropItemAction.java   # Drop item (sync)
│   │   │   ├── OpenContainerAction.java  # Open chest/furnace (sync)
│   │   │   ├── ClickSlotAction.java  # Click container slot (sync)
│   │   │   ├── CloseContainerAction.java # Close container (sync)
│   │   │   └── SmeltAction.java      # Smelting recipe lookup (sync)
│   │   ├── memory/
│   │   │   ├── MemoryManager.java    # Global + per-agent scoped memory (CRUD, JSON persistence)
│   │   │   ├── MemoryEntry.java      # Unified data model
│   │   │   └── MemoryLocation.java   # Point/area location model
│   │   └── pathfinding/
│   │       ├── Pathfinder.java       # A* algorithm
│   │       └── PathFollower.java     # Tick-based smooth movement
│   ├── client/
│   │   ├── ClientSetup.java          # Keybindings (M=memory, G=agents)
│   │   ├── BridgeClient.java         # Async HTTP client (GUI ↔ server)
│   │   ├── AgentManagementScreen.java # Agent CRUD + persona editor (G key)
│   │   ├── MemoryListScreen.java     # Memory list with scope/category tabs (M key)
│   │   ├── MemoryEditScreen.java     # Memory editor with scope selector
│   │   └── AreaMarkHandler.java      # World block area selection
│   ├── network/
│   │   └── AgentHttpServer.java      # HTTP bridge (per-agent routing, persona, memory)
│   ├── command/
│   │   └── AgentCommand.java         # /agent spawn|despawn|tell|list|status|stop|pause|resume
│   ├── compat/                       # Mod compatibility (AE2, Create)
│   ├── monitor/                      # Chat monitoring, intervention, terminal
│   └── runtime/
│       └── RuntimeManager.java       # Launch/stop per-agent runtime processes
├── agent-runtime/                    # TypeScript (Claude Agent SDK + MCP)
│   └── src/
│       ├── index.ts                  # SDK query loop (maxTurns=50, per-agent session)
│       ├── mcp-server.ts             # MCP tools → HTTP bridge (persona-filtered)
│       ├── prompt.ts                 # Dynamic system prompt (persona injection)
│       └── intervention.ts           # Per-agent intervention polling
├── scripts/
│   └── parse-agent-log.js           # Pretty-print JSONL logs
└── run/.agent/
    ├── bridge-server.json            # HTTP port file (auto-generated)
    ├── memory.json                   # Global memory
    ├── agents/
    │   └── {name}/
    │       ├── PERSONA.md            # Agent persona (role, personality, tools)
    │       └── memory.json           # Agent-specific memory
    └── logs/                         # Session logs (YYYY-MM-DD_HHmmss.jsonl)
```

## Architecture

```
[Player command] → /agent tell <name> <msg>
                      ↓
                  RuntimeManager → agent-runtime (Claude SDK, per-agent process)
                                      ↓
                                    MCP Server (persona-filtered tools)
                                      ↓
                                    HTTP Bridge (/agent/{name}/...)
                                      ↓
                                    AgentContext → ActionManager → AgentPlayer (ServerPlayer)
```

### Multi-Agent Flow

```
AgentManager (ConcurrentHashMap<String, AgentContext>)
  ├── "alex" → AgentContext { AgentPlayer, ActionManager, PersonaConfig, InterventionQueue }
  ├── "steve" → AgentContext { AgentPlayer, ActionManager, PersonaConfig, InterventionQueue }
  └── ...

Per-agent isolation:
  - Each agent is an AgentPlayer (ServerPlayer subclass) with mock AgentNetHandler
  - Each agent has its own ActionManager (no shared singleton)
  - Each agent runs its own Node.js runtime process
  - MCP tool list filtered per PERSONA.md
  - Memory: global (shared) + agent-specific (private)
```

### Persona System

```
.agent/agents/{name}/PERSONA.md
  ## Role        → injected into system prompt
  ## Personality  → injected into system prompt
  ## Tools        → filters MCP tool registration (empty = all allowed)

Applied via: despawn → modify PERSONA.md → respawn
```

## Components (for changelog tracking)

| ID | Scope | Key files |
|----|-------|-----------|
| `brain` | Claude's decisions (prompt, tool descriptions, config) | prompt.ts, mcp-server.ts, index.ts |
| `actions` | Action implementations (what happens) | core/action/*.java |
| `body` | Visual/physical presence (animation, tick) | AgentAnimation, AgentTickHandler |
| `multi-agent` | Agent lifecycle, persona, commands, management GUI | AgentContext, AgentManager, PersonaConfig, AgentCommand |
| `memory` | Knowledge persistence, scoping, memory GUI | MemoryManager, MemoryEntry, MemoryLocation, MemoryListScreen |
| `infra` | Plumbing (HTTP bridge, pathfinding, runtime, logging) | AgentHttpServer, Pathfinder, RuntimeManager, AgentLogger |

See `docs/quality/components/index.md` for full definitions and per-component changelogs.

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

## In-Game Commands

```
/agent @<name> spawn          # Spawn agent at player position
/agent @<name> despawn        # Remove agent
/agent @<name> <message>      # Send command to agent (direct message, no subcommand)
/agent @<name> status         # Agent status
/agent @<name> stop           # Stop agent runtime
/agent @<name> pause          # Pause agent (queue intervention)
/agent @<name> resume         # Resume agent
/agent list                   # List all spawned agents (global)
```

## GUI Keybindings

| Key | Screen | Features |
|-----|--------|----------|
| `G` | Agent Management | Agent list, persona editor, tool checkboxes, spawn/despawn/create/delete |
| `M` | Memory Browser | Scope tabs (All/Global/per-agent), category tabs, search, create/edit/delete |

## Conventions

- Forge 1.20.1 (forge 47.2.0), Java 17, Gradle 8.1.1
- Package root: `com.pyosechang.agent`
- agent-runtime: Node.js, TypeScript, ESM
- HTTP bridge on localhost:0 (auto port), port file at `run/.agent/bridge-server.json`
- Per-agent HTTP routing: `/agent/{name}/observation`, `/agent/{name}/action`, etc.
- All async actions support dynamic timeout via `getTimeoutMs()`
- All actions broadcast lookAt/swingArm animations
- Agents are AgentPlayer (ServerPlayer subclass) — full entity system integration (physics, tracking, rendering)
- Auto item pickup every tick (2-block radius) for all agents
- Agent persona files at `run/.agent/agents/{name}/PERSONA.md`
- Memory: global at `run/.agent/memory.json`, per-agent at `run/.agent/agents/{name}/memory.json`

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
