// Integration test using mock bridge — NO Minecraft needed
// Tests: SDK → MCP → mock HTTP bridge → action responses
//
// Usage: npm run test:mock

import { query } from "@anthropic-ai/claude-agent-sdk";
import { createServer } from "http";
import { join, dirname } from "path";
import { fileURLToPath } from "url";
import { spawn, ChildProcess } from "child_process";

const __dirname = dirname(fileURLToPath(import.meta.url));

// --- Step 1: Start mock bridge ---
async function startMockBridge(): Promise<{ port: number; proc: ChildProcess }> {
  return new Promise((resolve, reject) => {
    const proc = spawn("npx", ["tsx", join(__dirname, "mock-bridge.ts")], {
      env: { ...process.env, MOCK_PORT: "0" },
      stdio: ["pipe", "pipe", "pipe"],
      shell: true,
    });

    let output = "";
    proc.stdout!.on("data", (data: Buffer) => {
      output += data.toString();
      const match = output.match(/localhost:(\d+)/);
      if (match) {
        resolve({ port: parseInt(match[1]), proc });
      }
    });

    proc.stderr!.on("data", (data: Buffer) => {
      console.error("[mock-bridge stderr]", data.toString());
    });

    proc.on("error", reject);
    setTimeout(() => reject(new Error("Mock bridge startup timeout")), 10000);
  });
}

// --- Step 2: Spawn agent via mock bridge ---
async function spawnAgent(bridgeUrl: string): Promise<void> {
  const res = await fetch(`${bridgeUrl}/spawn`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ x: 0, y: 64, z: 0 }),
  });
  if (!res.ok) throw new Error(`Spawn failed: ${await res.text()}`);
  console.log("✓ Agent spawned on mock bridge");
}

// --- Step 3: Run SDK with MCP tools ---
async function runAgent(bridgeUrl: string, prompt: string): Promise<{
  toolCalls: string[];
  thoughts: string[];
  turns: number;
  cost: number;
}> {
  const distDir = join(__dirname, "..", "dist");
  const mcpServerPath = join(distDir, "mcp-server.js");

  const env = { ...process.env };
  delete env.CLAUDECODE;

  const toolCalls: string[] = [];
  const thoughts: string[] = [];
  let turns = 0;
  let cost = 0;

  for await (const msg of query({
    prompt,
    options: {
      maxTurns: 5,
      env,
      permissionMode: "bypassPermissions",
      allowDangerouslySkipPermissions: true,
      tools: [],
      mcpServers: {
        "agent-bridge": {
          command: "node",
          args: [mcpServerPath],
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
          thoughts.push(block.text);
        }
        if ("name" in block) {
          toolCalls.push((block as any).name);
        }
      }
    } else if (msg.type === "result") {
      turns = msg.num_turns || 0;
      cost = msg.total_cost_usd || 0;
    }
  }

  return { toolCalls, thoughts, turns, cost };
}

// --- Test scenarios ---
interface TestResult { name: string; pass: boolean; detail: string; }

async function runTests(bridgeUrl: string): Promise<TestResult[]> {
  const results: TestResult[] = [];

  // Test 1: Observation — Claude should call get_observation
  console.log("\n=== Test 1: get_observation ===");
  try {
    const r = await runAgent(bridgeUrl,
      "You are a Minecraft agent. Call get_observation to check your current state, then briefly report what you see. You MUST use tools."
    );
    const hasObs = r.toolCalls.some(t => t.includes("get_observation"));
    console.log(`  Tool calls: ${r.toolCalls.map(t => t.replace("mcp__agent-bridge__", "")).join(", ") || "NONE"}`);
    console.log(`  Turns: ${r.turns}`);
    results.push({
      name: "get_observation called",
      pass: hasObs,
      detail: hasObs ? `Called in ${r.turns} turns` : "NOT called — MCP tools may not be connected",
    });
  } catch (e) {
    results.push({ name: "get_observation called", pass: false, detail: String(e) });
  }

  // Re-spawn for next test (despawn + spawn to reset state)
  await fetch(`${bridgeUrl}/despawn`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  await spawnAgent(bridgeUrl);

  // Test 2: Movement — Claude should call move_to
  console.log("\n=== Test 2: move_to ===");
  try {
    const r = await runAgent(bridgeUrl,
      "You are a Minecraft agent at position (0, 64, 0). Move to coordinates (10, 64, 0). Use the move_to tool. Then confirm you moved by calling get_observation."
    );
    const hasMove = r.toolCalls.some(t => t.includes("move_to"));
    console.log(`  Tool calls: ${r.toolCalls.map(t => t.replace("mcp__agent-bridge__", "")).join(", ") || "NONE"}`);
    results.push({
      name: "move_to called",
      pass: hasMove,
      detail: hasMove ? "Movement executed" : "move_to NOT called",
    });
  } catch (e) {
    results.push({ name: "move_to called", pass: false, detail: String(e) });
  }

  // Reset
  await fetch(`${bridgeUrl}/despawn`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  await spawnAgent(bridgeUrl);

  // Test 3: Mining — Claude should call mine_block
  console.log("\n=== Test 3: mine_block ===");
  try {
    const r = await runAgent(bridgeUrl,
      "You are a Minecraft agent. First call get_observation to see nearby blocks. Then mine the iron ore you find. Use mine_block tool with the exact coordinates from observation."
    );
    const hasMine = r.toolCalls.some(t => t.includes("mine_block"));
    console.log(`  Tool calls: ${r.toolCalls.map(t => t.replace("mcp__agent-bridge__", "")).join(", ") || "NONE"}`);
    results.push({
      name: "mine_block called",
      pass: hasMine,
      detail: hasMine ? "Mining executed" : "mine_block NOT called",
    });
  } catch (e) {
    results.push({ name: "mine_block called", pass: false, detail: String(e) });
  }

  // Reset
  await fetch(`${bridgeUrl}/despawn`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  await spawnAgent(bridgeUrl);

  // Test 4: Multi-step task — observe + move + mine
  console.log("\n=== Test 4: Multi-step (observe → move → mine) ===");
  try {
    const r = await runAgent(bridgeUrl,
      "You are a Minecraft agent. Execute these steps IN ORDER using tools:\n1) Call get_observation to see nearby blocks\n2) Call move_to with the iron ore coordinates from step 1\n3) Call mine_block at the same coordinates\nYou MUST call all three tools: get_observation, move_to, mine_block."
    );
    const hasObs = r.toolCalls.some(t => t.includes("get_observation"));
    const hasMove = r.toolCalls.some(t => t.includes("move_to"));
    const hasMine = r.toolCalls.some(t => t.includes("mine_block"));
    const allThree = hasObs && hasMove && hasMine;
    const calls = r.toolCalls.map(t => t.replace("mcp__agent-bridge__", "")).join(" → ");
    console.log(`  Tool chain: ${calls || "NONE"}`);
    console.log(`  Turns: ${r.turns}`);
    results.push({
      name: "Multi-step execution",
      pass: allThree,
      detail: allThree
        ? `Full chain: ${calls}`
        : `Missing: ${[!hasObs && "observe", !hasMove && "move", !hasMine && "mine"].filter(Boolean).join(", ")}`,
    });
  } catch (e) {
    results.push({ name: "Multi-step execution", pass: false, detail: String(e) });
  }

  return results;
}

// --- Main ---
async function main() {
  console.log("Starting mock bridge...");
  const { port, proc } = await startMockBridge();
  const bridgeUrl = `http://localhost:${port}`;
  console.log(`✓ Mock bridge running at ${bridgeUrl}`);

  try {
    // Verify bridge is up
    const status = await fetch(`${bridgeUrl}/status`);
    console.log("✓ Bridge status:", await status.json());

    // Spawn agent
    await spawnAgent(bridgeUrl);

    // Verify dist/mcp-server.js exists
    const distDir = join(__dirname, "..", "dist");
    try {
      const { statSync } = await import("fs");
      statSync(join(distDir, "mcp-server.js"));
      console.log("✓ dist/mcp-server.js exists");
    } catch {
      console.error("✗ dist/mcp-server.js not found — run 'npm run build' first");
      process.exit(1);
    }

    // Run tests
    const results = await runTests(bridgeUrl);

    // Summary
    console.log("\n" + "=".repeat(50));
    console.log("RESULTS:");
    console.log("=".repeat(50));
    let passed = 0;
    for (const r of results) {
      const icon = r.pass ? "✓" : "✗";
      console.log(`  ${icon} ${r.name}: ${r.detail}`);
      if (r.pass) passed++;
    }
    console.log(`\n${passed}/${results.length} tests passed`);

    process.exit(passed === results.length ? 0 : 1);
  } finally {
    proc.kill();
  }
}

main().catch(e => {
  console.error("Fatal:", e);
  process.exit(1);
});
