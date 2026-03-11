// Full integration test: SDK → MCP → Bridge
// Requires: Minecraft running with agent-mod

import { query } from "@anthropic-ai/claude-agent-sdk";
import { readFile } from "fs/promises";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const BRIDGE_CONFIG_PATH = "C:/PyoSeChang/minecraft-mode/agent-mod/run/.agent/bridge-server.json";

async function main() {
  // 1. Verify bridge is up
  let bridgeUrl: string;
  try {
    const config = JSON.parse(await readFile(BRIDGE_CONFIG_PATH, "utf-8"));
    bridgeUrl = `http://localhost:${config.port}`;
    const res = await fetch(`${bridgeUrl}/status`);
    console.log("✓ Bridge is up:", await res.text());
  } catch (e) {
    console.error("✗ Bridge not available. Is Minecraft running?");
    process.exit(1);
  }

  // 2. Spawn agent if not already
  const statusRes = await fetch(`${bridgeUrl}/status`);
  const status = await statusRes.json() as any;
  if (!status.spawned) {
    await fetch(`${bridgeUrl}/spawn`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ x: 0, y: 100, z: 0 }),
    });
    console.log("✓ Agent spawned for test");
  } else {
    console.log("✓ Agent already spawned");
  }

  // 3. Run SDK with MCP and a simple command
  console.log("\n--- Running SDK query with MCP tools ---\n");

  const env = { ...process.env };
  delete env.CLAUDECODE;

  const mcpServerPath = join(__dirname, "../src/mcp-server.ts");

  let toolCalls: string[] = [];
  let assistantTexts: string[] = [];

  try {
    for await (const msg of query({
      prompt: `You are a Minecraft agent. Call get_observation to see your current state, then report what you see. Do NOT make up data - you MUST use tools.`,
      options: {
        maxTurns: 3,
        env,
        permissionMode: "bypassPermissions",
        allowDangerouslySkipPermissions: true,
        tools: [],
        mcpServers: {
          "agent-bridge": {
            command: "npx",
            args: ["tsx", mcpServerPath],
            env: {
              AGENT_BRIDGE_URL: bridgeUrl,
              AGENT_WORLD_PATH: "",
            },
          },
        },
      },
    })) {
      if (msg.type === "assistant" && msg.message?.content) {
        for (const block of msg.message.content) {
          if ("text" in block && block.text) {
            assistantTexts.push(block.text);
            console.log("[thought]", block.text.slice(0, 150));
          }
          if ("name" in block) {
            toolCalls.push((block as any).name);
            console.log("[tool_call]", (block as any).name);
          }
        }
      } else if (msg.type === "result") {
        console.log(`[result] turns=${msg.num_turns}, cost=$${msg.total_cost_usd?.toFixed(4)}`);
      }
    }
  } catch (e) {
    console.error("✗ SDK query failed:", e instanceof Error ? e.message : e);
    process.exit(1);
  }

  // 4. Verify results
  console.log("\n--- Results ---");
  console.log(`Tool calls: ${toolCalls.length > 0 ? toolCalls.join(", ") : "NONE"}`);

  const hasObservation = toolCalls.some(t => t.includes("get_observation"));
  if (hasObservation) {
    console.log("✓ PASS: get_observation was called via MCP");
  } else {
    console.log("✗ FAIL: get_observation was NOT called. MCP tools not connected.");
    console.log("  Assistant just generated text without tool calls.");
  }
}

main().catch(e => { console.error(e); process.exit(1); });
