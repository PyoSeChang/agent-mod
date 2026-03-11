import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const bridgeUrl = process.env.AGENT_BRIDGE_URL || "http://localhost:3000";

async function bridgeFetch(path: string, method = "GET", body?: unknown): Promise<string> {
  const opts: RequestInit = {
    method,
    headers: { "Content-Type": "application/json" },
  };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(`${bridgeUrl}${path}`, opts);
  return res.text();
}

const server = new McpServer({
  name: "agent-bridge",
  version: "0.1.0",
});

// Core observation
server.tool("get_observation", "Get current agent state (position, inventory, nearby blocks/entities)", {},
  async () => ({
    content: [{ type: "text", text: await bridgeFetch("/observation") }],
  })
);

// Movement
server.tool("move_to", "Move agent to coordinates",
  { x: z.number(), y: z.number(), z: z.number() },
  async ({ x, y, z: zCoord }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "move_to", x, y, z: zCoord }) }],
  })
);

// Mining
server.tool("mine_block", "Mine a block at coordinates",
  { x: z.number(), y: z.number(), z: z.number() },
  async ({ x, y, z: zCoord }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "mine_block", x, y, z: zCoord }) }],
  })
);

// Placing
server.tool("place_block", "Place a block at coordinates",
  { x: z.number(), y: z.number(), z: z.number(), block: z.string() },
  async ({ x, y, z: zCoord, block }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "place_block", x, y, z: zCoord, block }) }],
  })
);

// Item management
server.tool("pickup_items", "Pick up nearby items",
  { radius: z.number().optional().default(5) },
  async ({ radius }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "pickup_items", radius }) }],
  })
);

server.tool("drop_item", "Drop an item from inventory",
  { item: z.string(), count: z.number().optional().default(1) },
  async ({ item, count }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "drop_item", item, count }) }],
  })
);

server.tool("equip", "Equip an item to a slot",
  { item: z.string(), slot: z.enum(["mainhand", "offhand", "head", "chest", "legs", "feet"]) },
  async ({ item, slot }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "equip", item, slot }) }],
  })
);

// Combat/interaction
server.tool("attack", "Attack an entity",
  { entity_id: z.number() },
  async ({ entity_id }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "attack", entity_id }) }],
  })
);

server.tool("interact", "Interact with an entity",
  { entity_id: z.number() },
  async ({ entity_id }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "interact", entity_id }) }],
  })
);

// Container operations
server.tool("open_container", "Open a container (chest, furnace, etc.) at coordinates",
  { x: z.number(), y: z.number(), z: z.number() },
  async ({ x, y, z: zCoord }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "open_container", x, y, z: zCoord }) }],
  })
);

server.tool("click_slot", "Click a slot in the open container",
  { slot: z.number(), action: z.enum(["pickup", "quick_move", "throw"]).optional().default("pickup") },
  async ({ slot, action }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "click_slot", slot, click_action: action }) }],
  })
);

server.tool("close_container", "Close the currently open container", {},
  async () => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "close_container" }) }],
  })
);

server.tool("craft", "Look up a crafting recipe",
  { recipe: z.string().describe("Recipe ID like 'minecraft:iron_pickaxe'") },
  async ({ recipe }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "craft", recipe }) }],
  })
);

server.tool("smelt", "Look up a smelting recipe for an item",
  { input: z.string().describe("Item ID to smelt like 'minecraft:iron_ore'") },
  async ({ input }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "smelt", input }) }],
  })
);

// Memory tools
server.tool("remember_location", "Remember a named location",
  { name: z.string(), x: z.number(), y: z.number(), z: z.number(), note: z.string().optional() },
  async (params) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "remember_location", ...params }) }],
  })
);

server.tool("remember_facility", "Remember a facility (furnace, storage, etc.)",
  { name: z.string(), x: z.number(), y: z.number(), z: z.number(), type: z.string(), note: z.string().optional() },
  async (params) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "remember_facility", ...params }) }],
  })
);

server.tool("remember_preference", "Remember a user preference",
  { key: z.string(), value: z.string() },
  async (params) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "remember_preference", ...params }) }],
  })
);

server.tool("recall", "Search agent memory for information",
  { query: z.string() },
  async ({ query }) => ({
    content: [{ type: "text", text: await bridgeFetch("/action", "POST", { action: "recall", query }) }],
  })
);

// Dynamic tool registration from bridge
async function registerDynamicTools() {
  try {
    const actionsJson = await bridgeFetch("/actions");
    const actions = JSON.parse(actionsJson) as string[];
    // Log available actions but don't re-register built-in ones
    console.error(`Bridge reports ${actions.length} available actions: ${actions.join(", ")}`);
  } catch {
    console.error("Could not fetch dynamic actions from bridge (bridge may not be ready yet)");
  }
}

async function main() {
  await registerDynamicTools();
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch(console.error);
