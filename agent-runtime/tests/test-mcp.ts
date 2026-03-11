// Tests MCP server tools
// Requires: Minecraft running with agent-mod (for bridge connection)

import { spawn } from "child_process";
import { readFile } from "fs/promises";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const BRIDGE_CONFIG_PATH = process.env.BRIDGE_CONFIG || "C:/PyoSeChang/minecraft-mode/agent-mod/run/.agent/bridge-server.json";

let msgId = 1;

function jsonrpc(method: string, params?: any): string {
  return JSON.stringify({ jsonrpc: "2.0", id: msgId++, method, params: params || {} }) + "\n";
}

async function main() {
  // Read bridge port
  let bridgeUrl: string;
  try {
    const config = JSON.parse(await readFile(BRIDGE_CONFIG_PATH, "utf-8"));
    bridgeUrl = `http://localhost:${config.port}`;
  } catch {
    console.error("Cannot read bridge config. Is Minecraft running?");
    process.exit(1);
  }

  console.log(`Bridge URL: ${bridgeUrl}`);
  console.log("Starting MCP server...\n");

  const mcpServer = spawn("node", [join(__dirname, "../dist/mcp-server.js")], {
    env: { ...process.env, AGENT_BRIDGE_URL: bridgeUrl, AGENT_WORLD_PATH: "" },
    stdio: ["pipe", "pipe", "pipe"],
  });

  let buffer = "";
  const responses: any[] = [];

  mcpServer.stdout!.on("data", (data) => {
    buffer += data.toString();
    const lines = buffer.split("\n");
    buffer = lines.pop()!;
    for (const line of lines) {
      if (line.trim()) {
        try {
          responses.push(JSON.parse(line));
        } catch { /* ignore non-JSON */ }
      }
    }
  });

  mcpServer.stderr!.on("data", (data) => {
    console.error("[MCP stderr]", data.toString().trim());
  });

  function waitForResponse(id: number, timeout = 10000): Promise<any> {
    return new Promise((resolve, reject) => {
      const start = Date.now();
      const check = setInterval(() => {
        const found = responses.find(r => r.id === id);
        if (found) {
          clearInterval(check);
          resolve(found);
        } else if (Date.now() - start > timeout) {
          clearInterval(check);
          reject(new Error(`Timeout waiting for response id=${id}`));
        }
      }, 100);
    });
  }

  async function send(method: string, params?: any): Promise<any> {
    const id = msgId;
    mcpServer.stdin!.write(jsonrpc(method, params));
    return waitForResponse(id);
  }

  try {
    // Initialize
    const initRes = await send("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "test-client", version: "1.0" },
    });
    console.log("\u2713 Initialize:", initRes.result ? "OK" : "FAIL");

    // Send initialized notification (no response expected)
    mcpServer.stdin!.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) + "\n");
    await new Promise(r => setTimeout(r, 500));

    // List tools
    const toolsRes = await send("tools/list", {});
    const toolNames = toolsRes.result?.tools?.map((t: any) => t.name) || [];
    console.log(`\u2713 Tools available (${toolNames.length}):`, toolNames.join(", "));

    // Call get_observation
    const obsRes = await send("tools/call", { name: "get_observation", arguments: {} });
    console.log("\u2713 get_observation:", JSON.stringify(obsRes.result?.content?.[0]?.text || obsRes.error).slice(0, 200));

    // Call move_to
    const moveRes = await send("tools/call", { name: "move_to", arguments: { x: 10, y: 100, z: 10 } });
    console.log("\u2713 move_to:", JSON.stringify(moveRes.result?.content?.[0]?.text || moveRes.error).slice(0, 200));

    console.log("\nAll MCP tests completed!");
  } catch (e) {
    console.error("Test error:", e);
  } finally {
    mcpServer.kill();
  }
}

main().catch(e => { console.error(e); process.exit(1); });
