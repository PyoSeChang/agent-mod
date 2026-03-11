// Tests HTTP Bridge endpoints
// Requires: Minecraft running with agent-mod loaded

import { readFile } from "fs/promises";
import { join } from "path";

const BRIDGE_CONFIG_PATH = process.env.BRIDGE_CONFIG || "C:/PyoSeChang/minecraft-mode/agent-mod/run/.agent/bridge-server.json";

async function getBridgeUrl(): Promise<string> {
  try {
    const config = JSON.parse(await readFile(BRIDGE_CONFIG_PATH, "utf-8"));
    return `http://localhost:${config.port}`;
  } catch (e) {
    throw new Error(`Cannot read bridge config at ${BRIDGE_CONFIG_PATH}. Is Minecraft running?`);
  }
}

async function testEndpoint(name: string, url: string, method: string, body?: unknown): Promise<boolean> {
  try {
    const opts: RequestInit = { method, headers: { "Content-Type": "application/json" } };
    if (body) opts.body = JSON.stringify(body);
    const res = await fetch(url, opts);
    const text = await res.text();
    let json: any;
    try { json = JSON.parse(text); } catch { json = text; }
    const ok = res.ok;
    console.log(`${ok ? "\u2713" : "\u2717"} ${name} [${res.status}]`, JSON.stringify(json).slice(0, 200));
    return ok;
  } catch (e) {
    console.log(`\u2717 ${name} - ERROR: ${e instanceof Error ? e.message : e}`);
    return false;
  }
}

async function main() {
  const bridgeUrl = await getBridgeUrl();
  console.log(`Bridge URL: ${bridgeUrl}\n`);

  let passed = 0, total = 0;

  async function test(name: string, url: string, method: string, body?: unknown) {
    total++;
    if (await testEndpoint(name, url, method, body)) passed++;
  }

  // Test all endpoints
  await test("GET /status", `${bridgeUrl}/status`, "GET");
  await test("GET /actions", `${bridgeUrl}/actions`, "GET");
  await test("GET /observation (no agent)", `${bridgeUrl}/observation`, "GET");

  // Spawn agent
  await test("POST /spawn", `${bridgeUrl}/spawn`, "POST", { x: 0, y: 100, z: 0 });
  await test("GET /status (spawned)", `${bridgeUrl}/status`, "GET");
  await test("GET /observation (spawned)", `${bridgeUrl}/observation`, "GET");

  // Test actions
  await test("POST /action move_to", `${bridgeUrl}/action`, "POST", { action: "move_to", x: 5, y: 100, z: 5 });
  await test("GET /observation (after move)", `${bridgeUrl}/observation`, "GET");

  // Test log/intervention endpoints
  await test("POST /log", `${bridgeUrl}/log`, "POST", { type: "test", message: "Bridge test log" });
  await test("GET /intervention (empty)", `${bridgeUrl}/intervention`, "GET");

  // Test unknown action
  await test("POST /action unknown", `${bridgeUrl}/action`, "POST", { action: "nonexistent_action" });

  // Despawn
  await test("POST /despawn", `${bridgeUrl}/despawn`, "POST", {});
  await test("GET /status (despawned)", `${bridgeUrl}/status`, "GET");

  console.log(`\n${passed}/${total} tests passed`);
  process.exit(passed === total ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
