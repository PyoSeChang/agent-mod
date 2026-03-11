// Mock Minecraft HTTP Bridge — no Minecraft instance needed
// Simulates all AgentHttpServer endpoints with fake world state

import { createServer, IncomingMessage, ServerResponse } from "http";

interface Position { x: number; y: number; z: number; }
interface InventoryItem { slot: number; item: string; count: number; }
interface NearbyBlock { pos: Position; block_id: string; block_name: string; }
interface NearbyEntity { type: string; pos: Position; health: number; name: string; id: number; }

// --- Simulated world state ---
let spawned = false;
let position: Position = { x: 0, y: 64, z: 0 };
let inventory: InventoryItem[] = [];
let containerOpen = false;
const logs: { type: string; message: string; ts: number }[] = [];
const interventions: string[] = [];
let nextEntityId = 100;

const nearbyBlocks: NearbyBlock[] = [
  { pos: { x: 3, y: 63, z: 0 }, block_id: "minecraft:iron_ore", block_name: "Iron Ore" },
  { pos: { x: -2, y: 64, z: 4 }, block_id: "minecraft:oak_log", block_name: "Oak Log" },
  { pos: { x: 1, y: 64, z: -1 }, block_id: "minecraft:chest", block_name: "Chest" },
  { pos: { x: 5, y: 64, z: 5 }, block_id: "minecraft:furnace", block_name: "Furnace" },
  { pos: { x: 0, y: 63, z: 0 }, block_id: "minecraft:grass_block", block_name: "Grass Block" },
  { pos: { x: 0, y: 62, z: 0 }, block_id: "minecraft:dirt", block_name: "Dirt" },
];

const nearbyEntities: NearbyEntity[] = [
  { type: "minecraft:cow", pos: { x: 8, y: 64, z: 3 }, health: 10, name: "Cow", id: nextEntityId++ },
  { type: "minecraft:chicken", pos: { x: -5, y: 64, z: 7 }, health: 4, name: "Chicken", id: nextEntityId++ },
];

const availableActions = [
  "move_to", "mine_block", "place_block", "pickup_items", "drop_item",
  "equip", "attack", "interact", "open_container", "click_slot",
  "close_container", "craft", "smelt",
  "remember_location", "remember_facility", "remember_preference", "recall",
];

// --- Action handlers ---
function handleAction(body: any): { status: number; response: any } {
  const action = body.action;
  if (!spawned && action !== "spawn") {
    return { status: 400, response: { error: "Agent not spawned" } };
  }

  switch (action) {
    case "move_to": {
      position = { x: body.x, y: body.y, z: body.z };
      return { status: 200, response: { ok: true, position } };
    }
    case "mine_block": {
      const bx = body.x, by = body.y, bz = body.z;
      const idx = nearbyBlocks.findIndex(b =>
        b.pos.x === bx && b.pos.y === by && b.pos.z === bz);
      if (idx === -1) {
        return { status: 200, response: { ok: false, error: "No block at position" } };
      }
      const block = nearbyBlocks.splice(idx, 1)[0];
      // Add mined item to inventory
      const itemId = block.block_id.replace("_ore", "_raw");
      const existing = inventory.find(i => i.item === itemId);
      if (existing) {
        existing.count++;
      } else {
        inventory.push({ slot: inventory.length, item: itemId, count: 1 });
      }
      return { status: 200, response: { ok: true, mined: block.block_id, dropped: itemId } };
    }
    case "place_block": {
      const blockId = body.block || "minecraft:cobblestone";
      nearbyBlocks.push({
        pos: { x: body.x, y: body.y, z: body.z },
        block_id: blockId,
        block_name: blockId.replace("minecraft:", "").replace(/_/g, " "),
      });
      return { status: 200, response: { ok: true } };
    }
    case "pickup_items": {
      const radius = body.radius || 5;
      const picked = [
        { item: "minecraft:iron_ingot", count: 3 },
        { item: "minecraft:oak_planks", count: 8 },
      ];
      for (const p of picked) {
        const existing = inventory.find(i => i.item === p.item);
        if (existing) existing.count += p.count;
        else inventory.push({ slot: inventory.length, item: p.item, count: p.count });
      }
      return { status: 200, response: { ok: true, picked_up: picked } };
    }
    case "drop_item": {
      const idx = inventory.findIndex(i => i.item === body.item);
      if (idx === -1) return { status: 200, response: { ok: false, error: "Item not in inventory" } };
      const count = body.count || 1;
      inventory[idx].count -= count;
      if (inventory[idx].count <= 0) inventory.splice(idx, 1);
      return { status: 200, response: { ok: true, dropped: body.item, count } };
    }
    case "equip": {
      return { status: 200, response: { ok: true, equipped: body.item, slot: body.slot } };
    }
    case "attack": {
      const entity = nearbyEntities.find(e => e.id === body.entity_id);
      if (!entity) return { status: 200, response: { ok: false, error: "Entity not found" } };
      entity.health -= 3;
      const killed = entity.health <= 0;
      if (killed) {
        const idx = nearbyEntities.findIndex(e => e.id === body.entity_id);
        nearbyEntities.splice(idx, 1);
      }
      return { status: 200, response: { ok: true, damage: 3, killed, remaining_health: Math.max(0, entity.health) } };
    }
    case "interact": {
      const entity = nearbyEntities.find(e => e.id === body.entity_id);
      if (!entity) return { status: 200, response: { ok: false, error: "Entity not found" } };
      return { status: 200, response: { ok: true, interacted_with: entity.type } };
    }
    case "open_container": {
      containerOpen = true;
      return { status: 200, response: { ok: true, container_type: "chest", slots: [
        { slot: 0, item: "minecraft:diamond", count: 5 },
        { slot: 1, item: "minecraft:iron_ingot", count: 32 },
      ]}};
    }
    case "click_slot": {
      if (!containerOpen) return { status: 200, response: { ok: false, error: "No container open" } };
      return { status: 200, response: { ok: true, slot: body.slot, action: body.click_action || "pickup" } };
    }
    case "close_container": {
      containerOpen = false;
      return { status: 200, response: { ok: true } };
    }
    case "craft": {
      return { status: 200, response: { ok: true, crafted: body.recipe, count: 1 } };
    }
    case "smelt": {
      return { status: 200, response: { ok: true, input: body.input, output: body.input.replace("_ore", "_ingot") } };
    }
    case "remember_location": {
      return { status: 200, response: { ok: true, saved: "location", name: body.name } };
    }
    case "remember_facility": {
      return { status: 200, response: { ok: true, saved: "facility", name: body.name } };
    }
    case "remember_preference": {
      return { status: 200, response: { ok: true, saved: "preference", key: body.key } };
    }
    case "recall": {
      return { status: 200, response: {
        ok: true,
        results: [
          { type: "location", name: "Main Storage", x: 100, y: 64, z: 200, note: "Big chest room" },
        ],
      }};
    }
    default:
      return { status: 400, response: { error: `Unknown action: ${action}` } };
  }
}

// --- HTTP Server ---
function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString()));
  });
}

function sendJson(res: ServerResponse, status: number, data: unknown) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(data));
}

const server = createServer(async (req, res) => {
  const url = req.url || "/";
  const method = req.method || "GET";
  let body: any = {};

  if (method === "POST") {
    try { body = JSON.parse(await readBody(req)); } catch { body = {}; }
  }

  // Route
  if (url === "/status" && method === "GET") {
    sendJson(res, 200, {
      spawned,
      position: spawned ? position : null,
      containerOpen,
      inventorySize: inventory.length,
    });

  } else if (url === "/spawn" && method === "POST") {
    if (spawned) {
      sendJson(res, 400, { error: "Already spawned" });
      return;
    }
    position = { x: body.x ?? 0, y: body.y ?? 64, z: body.z ?? 0 };
    spawned = true;
    console.log(`[mock] Agent spawned at ${position.x}, ${position.y}, ${position.z}`);
    sendJson(res, 200, { ok: true, position });

  } else if (url === "/despawn" && method === "POST") {
    spawned = false;
    containerOpen = false;
    inventory = [];
    console.log("[mock] Agent despawned");
    sendJson(res, 200, { ok: true });

  } else if (url === "/observation" && method === "GET") {
    if (!spawned) {
      sendJson(res, 400, { error: "Agent not spawned" });
      return;
    }
    sendJson(res, 200, {
      position,
      inventory,
      nearby_blocks: nearbyBlocks,
      nearby_entities: nearbyEntities,
    });

  } else if (url === "/action" && method === "POST") {
    const result = handleAction(body);
    console.log(`[mock] Action: ${body.action} → ${result.response.ok ?? "error"}`);
    sendJson(res, result.status, result.response);

  } else if (url === "/actions" && method === "GET") {
    sendJson(res, 200, availableActions);

  } else if (url === "/log" && method === "POST") {
    logs.push({ type: body.type, message: body.message, ts: Date.now() });
    console.log(`[mock] Log [${body.type}]: ${body.message?.slice(0, 100)}`);
    sendJson(res, 200, { ok: true });

  } else if (url === "/intervention" && method === "GET") {
    const msg = interventions.shift();
    sendJson(res, 200, msg ? { message: msg } : {});

  } else {
    sendJson(res, 404, { error: `Not found: ${method} ${url}` });
  }
});

const PORT = parseInt(process.env.MOCK_PORT || "0");
server.listen(PORT, "localhost", () => {
  const addr = server.address();
  const port = typeof addr === "object" && addr ? addr.port : PORT;
  console.log(`[mock-bridge] Listening on http://localhost:${port}`);
  console.log(`[mock-bridge] Use: AGENT_BRIDGE_PORT=${port} or AGENT_BRIDGE_URL=http://localhost:${port}`);

  // If spawned as part of a test, write port to stdout for parsing
  if (process.send) {
    process.send({ port });
  }
});

// Graceful shutdown
process.on("SIGINT", () => { server.close(); process.exit(0); });
process.on("SIGTERM", () => { server.close(); process.exit(0); });

export { server };
