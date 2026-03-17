import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const bridgeUrl = process.env.AGENT_BRIDGE_URL || "http://localhost:3000";
const agentName = process.env.AGENT_NAME || "";
const agentPrefix = agentName ? `/agent/${agentName}` : "";

// Tool filtering: AGENT_TOOLS env var contains comma-separated allowed tool names
// Empty or unset = all tools allowed
const allowedTools: Set<string> | null = process.env.AGENT_TOOLS
  ? new Set(process.env.AGENT_TOOLS.split(",").map(t => t.trim()))
  : null;

function isAllowed(toolName: string): boolean {
  if (!allowedTools) return true;
  return allowedTools.has(toolName);
}

async function bridgeFetch(path: string, method = "GET", body?: unknown): Promise<string> {
  const opts: RequestInit = {
    method,
    headers: { "Content-Type": "application/json" },
  };
  if (body) opts.body = JSON.stringify(body);
  // Per-agent endpoints use prefix. /actions is global (no prefix).
  // /memory/ routes through agent prefix so memory gets scoped automatically.
  const prefix = path === "/actions" ? "" : agentPrefix;
  const res = await fetch(`${bridgeUrl}${prefix}${path}`, opts);
  return res.text();
}

const server = new McpServer({
  name: "agent-bridge",
  version: "0.1.0",
});

// === Always-registered tools ===

server.tool("get_observation", "Get current agent state (position, inventory, nearby blocks/entities)", {},
  async () => ({
    content: [{ type: "text", text: await bridgeFetch("/observation") }],
  })
);

server.tool("execute_sequence", "Execute a sequence of actions one after another in a single call. Sync actions run instantly, async actions (move_to, mine_block, mine_area, use_item_on_area) are ticked to completion. Stops on first failure.",
  {
    steps: z.array(z.record(z.string(), z.any())).describe("Array of action objects. Each must have 'action' field plus that action's parameters. Example: [{action:'equip',item:'wooden_hoe',slot:'mainhand'},{action:'use_item_on_area',x1:10,z1:20,x2:15,z2:25,y:63,face:'up'}]"),
  },
  async ({ steps }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "execute_sequence", steps }) }],
  })
);

// --- Memory tools (always registered) ---

server.tool("remember", "Save a memory. The agent remembers locations, facilities, resources, procedures. If no location is provided, current position is used. Use @memory:mXXX in content to reference other memories.",
  {
    title: z.string().describe("Short title (1 line)"),
    description: z.string().describe("Keywords-rich description for matching"),
    content: z.string().describe("Detailed information. Use @memory:mXXX to reference other memories"),
    category: z.enum(["storage", "facility", "area", "event", "skill"]),
    location: z.object({
      type: z.enum(["point", "area"]),
      x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
      x1: z.number().optional(), y1: z.number().optional(), z1: z.number().optional(),
      x2: z.number().optional(), y2: z.number().optional(), z2: z.number().optional(),
    }).optional().describe("point: (x,y,z). area: (x1,y1,z1)-(x2,y2,z2). Required for storage/area, optional for facility/event"),
  },
  async ({ title, description, content, category, location }) => ({
    content: [{ type: "text", text: await bridgeFetch("/memory/create", "POST", { title, description, content, category, location }) }],
  })
);

server.tool("update_memory", "Update an existing memory entry (partial update)",
  {
    id: z.string().describe("Memory ID like 'm001'"),
    title: z.string().optional(),
    description: z.string().optional(),
    content: z.string().optional(),
    category: z.enum(["storage", "facility", "area", "event", "skill"]).optional(),
  },
  async ({ id, title, description, content, category }) => {
    const fields: Record<string, unknown> = { id };
    if (title !== undefined) fields.title = title;
    if (description !== undefined) fields.description = description;
    if (content !== undefined) fields.content = content;
    if (category !== undefined) fields.category = category;
    return {
      content: [{ type: "text", text: await bridgeFetch("/memory/update", "POST", fields) }],
    };
  }
);

server.tool("forget", "Delete a memory entry",
  { id: z.string().describe("Memory ID like 'm001'") },
  async ({ id }) => ({
    content: [{ type: "text", text: await bridgeFetch("/memory/delete", "POST", { id }) }],
  })
);

server.tool("recall", "Retrieve full content of a memory entry. Use when you see a relevant title in the title_index but need the details.",
  { id: z.string().describe("Memory ID like 'm001'") },
  async ({ id }) => ({
    content: [{ type: "text", text: await bridgeFetch("/memory/get", "POST", { id }) }],
  })
);

server.tool("search_memory", "Search memories by keyword and/or category",
  {
    query: z.string().optional().default("").describe("Keyword to search in title and description"),
    category: z.enum(["storage", "facility", "area", "event", "skill"]).optional(),
  },
  async ({ query, category }) => ({
    content: [{ type: "text", text: await bridgeFetch("/memory/search", "POST", { query, category }) }],
  })
);

// === Persona-filtered tools ===

if (isAllowed("move_to")) {
  server.tool("move_to", "Move agent to coordinates. Use stop_distance to stop before reaching the exact target (e.g. 2-3 when approaching a player or entity).",
    { x: z.number(), y: z.number(), z: z.number(), stop_distance: z.number().optional().default(0).describe("Stop when within this many blocks of target (default 0 = exact position). Use 2-3 for approaching players/entities.") },
    async ({ x, y, z: zCoord, stop_distance }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "move_to", x, y, z: zCoord, stop_distance }) }],
    })
  );
}

if (isAllowed("mine_block")) {
  server.tool("mine_block", "Mine a block at coordinates",
    { x: z.number(), y: z.number(), z: z.number() },
    async ({ x, y, z: zCoord }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "mine_block", x, y, z: zCoord }) }],
    })
  );
}

if (isAllowed("mine_area")) {
  server.tool("mine_area", "Mine all blocks in a rectangular area. Agent walks to each block and mines it automatically.",
    {
      x1: z.number().describe("Start X"),
      y1: z.number().describe("Start Y"),
      z1: z.number().describe("Start Z"),
      x2: z.number().describe("End X"),
      y2: z.number().describe("End Y"),
      z2: z.number().describe("End Z"),
    },
    async ({ x1, y1, z1, x2, y2, z2 }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "mine_area", x1, y1, z1, x2, y2, z2 }) }],
    })
  );
}

if (isAllowed("place_block")) {
  server.tool("place_block", "Place a single block at coordinates (vanilla placement with collision check). For placing blocks across a 2D area (floors, platforms), equip the block item and use use_item_on_area instead.",
    { x: z.number(), y: z.number(), z: z.number(), block: z.string() },
    async ({ x, y, z: zCoord, block }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "place_block", x, y, z: zCoord, block }) }],
    })
  );
}

if (isAllowed("drop_item")) {
  server.tool("drop_item", "Drop an item from inventory",
    { item: z.string(), count: z.number().optional().default(1) },
    async ({ item, count }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "drop_item", item, count }) }],
    })
  );
}

if (isAllowed("equip")) {
  server.tool("equip", "Equip an item to a slot",
    { item: z.string(), slot: z.enum(["mainhand", "offhand", "head", "chest", "legs", "feet"]) },
    async ({ item, slot }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "equip", item, slot }) }],
    })
  );
}

if (isAllowed("attack")) {
  server.tool("attack", "Attack an entity",
    { entity_id: z.number() },
    async ({ entity_id }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "attack", entity_id }) }],
    })
  );
}

if (isAllowed("interact")) {
  server.tool("interact", "Interact with an entity",
    { entity_id: z.number() },
    async ({ entity_id }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "interact", entity_id }) }],
    })
  );
}

if (isAllowed("open_container")) {
  server.tool("open_container", "Open a container (chest, furnace, etc.) and return all slot contents. Slots include both container and agent inventory.",
    { x: z.number(), y: z.number(), z: z.number() },
    async ({ x, y, z: zCoord }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "open_container", x, y, z: zCoord }) }],
    })
  );
}

if (isAllowed("click_slot")) {
  server.tool("click_slot", "Click a slot in the open container. Returns before/after state of the slot. Use with execute_sequence for bulk operations.",
    { slot: z.number(), action: z.enum(["pickup", "quick_move", "throw"]).optional().default("pickup") },
    async ({ slot, action }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "click_slot", slot, click_action: action }) }],
    })
  );
}

if (isAllowed("close_container")) {
  server.tool("close_container", "Close the currently open container", {},
    async () => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "close_container" }) }],
    })
  );
}

if (isAllowed("craft")) {
  server.tool("craft", "Craft an item using a recipe. Checks inventory for ingredients, consumes them, and produces output. Requires crafting table nearby for 3x3 recipes.",
    {
      recipe: z.string().describe("Recipe ID like 'minecraft:iron_pickaxe'"),
      count: z.number().optional().default(1).describe("How many times to craft (default 1)"),
    },
    async ({ recipe, count }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "craft", recipe, count }) }],
    })
  );
}

if (isAllowed("smelt")) {
  server.tool("smelt", "Look up a smelting recipe for an item",
    { input: z.string().describe("Item ID to smelt like 'minecraft:iron_ore'") },
    async ({ input }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "smelt", input }) }],
    })
  );
}

if (isAllowed("use_item_on")) {
  server.tool("use_item_on", "Use held item on a block face (right-click). Works for: hoe on dirt, seeds on farmland, doors, levers, buckets, mod blocks.",
    { x: z.number(), y: z.number(), z: z.number(), face: z.enum(["up", "down", "north", "south", "east", "west"]).optional().default("up") },
    async ({ x, y, z: zCoord, face }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "use_item_on", x, y, z: zCoord, face }) }],
    })
  );
}

if (isAllowed("use_item_on_area")) {
  server.tool("use_item_on_area", "Use held item on every block in a 2D area (fixed Y). Agent walks in serpentine pattern. Use for: hoeing farmland, planting seeds, placing blocks on a surface, or any repeated right-click across an area. Equip the item first.",
    {
      x1: z.number().describe("Start X"),
      z1: z.number().describe("Start Z"),
      x2: z.number().describe("End X"),
      z2: z.number().describe("End Z"),
      y: z.number().describe("Y level of blocks to interact with"),
      face: z.enum(["up", "down", "north", "south", "east", "west"]).optional().default("up"),
    },
    async ({ x1, z1, x2, z2, y, face }) => ({
      content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "use_item_on_area", x1, z1, x2, z2, y, face }) }],
    })
  );
}

// Dynamic tool registration from bridge
async function registerDynamicTools() {
  try {
    const actionsJson = await bridgeFetch("/actions");
    const actions = JSON.parse(actionsJson) as string[];
    console.error(`Bridge reports ${actions.length} available actions: ${actions.join(", ")}`);
  } catch {
    console.error("Could not fetch dynamic actions from bridge (bridge may not be ready yet)");
  }
}

async function main() {
  if (allowedTools) {
    console.error(`Persona tool filter active: ${[...allowedTools].join(", ")}`);
  }
  await registerDynamicTools();
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch(console.error);
