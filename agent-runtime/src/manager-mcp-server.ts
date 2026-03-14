import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const bridgeUrl = process.env.MANAGER_BRIDGE_URL || "http://localhost:3000";

async function managerBridgeFetch(path: string, method = "GET", body?: unknown): Promise<string> {
  const opts: RequestInit = {
    method,
    headers: { "Content-Type": "application/json" },
  };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(`${bridgeUrl}${path}`, opts);
  return res.text();
}

const server = new McpServer({
  name: "manager-bridge",
  version: "0.1.0",
});

// === Schedule CRUD ===

server.tool("create_schedule", "Create a scheduled task. TIME_OF_DAY triggers at game time, INTERVAL repeats every N ticks, OBSERVER triggers on block/entity events.", {
  type: z.enum(["TIME_OF_DAY", "INTERVAL", "OBSERVER"]),
  target_agent: z.string().describe("Agent name to receive the prompt when triggered"),
  message: z.string().describe("Prompt message sent to the agent on trigger"),
  time_of_day: z.number().min(0).max(23999).optional().describe("Game tick (0=sunrise, 6000=noon, 12000=sunset, 18000=midnight)"),
  repeat_days: z.number().min(0).optional().describe("Repeat every N days. 0=once"),
  interval_ticks: z.number().min(20).optional().describe("Tick interval (20=1sec, 1200=1min)"),
  repeat: z.boolean().optional().describe("Repeat interval schedule"),
  observers: z.array(z.object({
    x: z.number(), y: z.number(), z: z.number(),
    event: z.enum(["crop_grow", "sapling_grow", "block_break", "block_place",
                    "baby_spawn", "entity_death", "explosion"]),
    condition: z.string().optional().describe("e.g. 'age=7', 'type=zombie'"),
  })).optional(),
  threshold: z.number().min(1).optional().describe("How many observers must trigger"),
  title: z.string().optional().describe("Schedule display name"),
  enabled: z.boolean().optional().describe("Enable on creation (default true)"),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/schedule/create", "POST", params) }],
}));

server.tool("update_schedule", "Update a schedule (partial update)", {
  id: z.string().describe("Schedule memory ID like 'm005'"),
  message: z.string().optional(),
  target_agent: z.string().optional(),
  enabled: z.boolean().optional(),
  time_of_day: z.number().min(0).max(23999).optional(),
  repeat_days: z.number().min(0).optional(),
  interval_ticks: z.number().min(20).optional(),
  threshold: z.number().min(1).optional(),
  title: z.string().optional(),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/schedule/update", "POST", params) }],
}));

server.tool("delete_schedule", "Delete a schedule and its observers", {
  id: z.string().describe("Schedule memory ID"),
}, async ({ id }) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/schedule/delete", "POST", { id }) }],
}));

server.tool("list_schedules", "List all schedules with status", {
  target_agent: z.string().optional().describe("Filter by target agent"),
  enabled_only: z.boolean().optional().describe("Only show enabled schedules"),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/schedule/list", "POST", params) }],
}));

server.tool("get_schedule", "Get schedule details including observer states", {
  id: z.string(),
}, async ({ id }) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/schedule/get", "POST", { id }) }],
}));

// === Observer management ===

server.tool("add_observers", "Add observers to an existing OBSERVER schedule", {
  schedule_id: z.string(),
  observers: z.array(z.object({
    x: z.number(), y: z.number(), z: z.number(),
    event: z.enum(["crop_grow", "sapling_grow", "block_break", "block_place",
                    "baby_spawn", "entity_death", "explosion"]),
    condition: z.string().optional(),
  })),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/observer/add", "POST", params) }],
}));

server.tool("remove_observers", "Remove observers by position from a schedule", {
  schedule_id: z.string(),
  positions: z.array(z.object({ x: z.number(), y: z.number(), z: z.number() })),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/observer/remove", "POST", params) }],
}));

server.tool("list_observers", "List all observers for a schedule with triggered status", {
  schedule_id: z.string(),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/observer/list", "POST", params) }],
}));

// === Agent management ===

server.tool("list_agents", "List all agents (defined + spawned)", {}, async () => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/agents/list") }],
}));

server.tool("spawn_agent", "Spawn an agent at coordinates", {
  name: z.string(),
  x: z.number(), y: z.number(), z: z.number(),
}, async ({ name, x, y, z: zCoord }) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch(`/agent/${name}/spawn`, "POST", { name, x, y, z: zCoord }) }],
}));

server.tool("despawn_agent", "Despawn an agent", {
  name: z.string(),
}, async ({ name }) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch(`/agent/${name}/despawn`, "POST", {}) }],
}));

server.tool("tell_agent", "Send a message/command to an agent. Launches runtime if not running.", {
  name: z.string().describe("Target agent name"),
  message: z.string().describe("Message to send"),
}, async ({ name, message }) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch(`/agent/${name}/tell`, "POST", { message }) }],
}));

server.tool("get_agent_status", "Get agent status (position, runtime, action)", {
  name: z.string(),
}, async ({ name }) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch(`/agent/${name}/status`) }],
}));

// === Memory (global scope) ===

server.tool("search_memory", "Search memories by keyword and/or category", {
  query: z.string().optional().describe("Keyword to search"),
  category: z.enum(["storage", "facility", "area", "event", "skill"]).optional(),
  scope: z.string().optional().describe("'all', 'global', 'agent:name', 'only:name'"),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/memory/search", "POST", params) }],
}));

server.tool("remember", "Save a global memory. Use @memory:mXXX in content to reference other memories.", {
  title: z.string(),
  description: z.string(),
  content: z.string(),
  category: z.enum(["storage", "facility", "area", "event", "skill"]),
  visible_to: z.array(z.string()).optional().describe("Agent names. Empty = global"),
  location: z.object({
    type: z.enum(["point", "area"]),
    x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
    x1: z.number().optional(), y1: z.number().optional(), z1: z.number().optional(),
    x2: z.number().optional(), y2: z.number().optional(), z2: z.number().optional(),
  }).optional().describe("point: (x,y,z). area: (x1,y1,z1)-(x2,y2,z2). Required for storage/area, optional for facility/event"),
}, async (params) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/memory/create", "POST", params) }],
}));

server.tool("recall", "Get full content of a memory entry", {
  id: z.string(),
}, async ({ id }) => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/memory/get", "POST", { id }) }],
}));

// === Game state (manager-only) ===

server.tool("get_world_time", "Get current game time info", {}, async () => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/manager/world_time") }],
}));

server.tool("get_supported_events", "List supported observer event types with descriptions", {}, async () => ({
  content: [{ type: "text" as const, text: await managerBridgeFetch("/manager/events") }],
}));

// === Start server ===

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch(console.error);
